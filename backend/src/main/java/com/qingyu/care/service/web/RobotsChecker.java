package com.qingyu.care.service.web;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * robots.txt 抓取与解析。爬取开始时取一次，规则在本轮爬取内复用。
 *
 * <p>只解析 {@code User-agent: *} 段——本项目不声明自定义 UA，通用段即适用段。
 *
 * <p>取 robots.txt 走 {@link WebPageFetcher#downloadRaw}，复用它那一整套出站防线
 * （SSRF 钉 IP 防 rebinding、硬关重定向、超时、5MB 上限、Content-Type 白名单——
 * {@code text/plain} 本来就在白名单里）。新开一条抓取路径就等于新开一个 SSRF 缺口。
 *
 * @author ruan
 */
@Slf4j
public final class RobotsChecker {

    private RobotsChecker() {
    }

    /**
     * 解析后的规则。{@code crawlDelayMs} 为 null 表示站点未声明，由调用方用默认限速。
     */
    public record RobotsRules(List<String> disallowedPaths, Long crawlDelayMs) {

        /** 无限制。robots.txt 缺失或解析失败时用它——见 {@link RobotsChecker#fetch}。 */
        public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), null);

        /**
         * 该 URL 是否允许抓取。按<b>路径</b>前缀匹配，查询串不参与判定。
         */
        public boolean allows(String url) {
            if (disallowedPaths.isEmpty()) {
                return true;
            }
            String path = pathOf(url);
            return disallowedPaths.stream().noneMatch(path::startsWith);
        }

        private static String pathOf(String url) {
            try {
                String path = new URI(url).getPath();
                return path == null || path.isEmpty() ? "/" : path;
            } catch (URISyntaxException e) {
                // 解析不出路径的 URL 本来也进不了爬取队列（LinkExtractor.normalize 会拒），
                // 这里返回根路径是保守侧：只要站点 Disallow 了 "/" 就一并拦下
                return "/";
            }
        }
    }

    /**
     * 取并解析 {@code {origin}/robots.txt}。
     *
     * <p>⚠️ <b>取不到一律按「允许全部」</b>：绝大多数站点没有这个文件，
     * {@code downloadRaw} 对 404 会抛 {@code BusinessException}（第一期的
     * {@code requireSuccessStatus}），fail-closed 会让本功能对多数站点直接不可用。
     */
    public static RobotsRules fetch(String origin, WebPageFetcher fetcher) {
        String url = origin.endsWith("/") ? origin + "robots.txt" : origin + "/robots.txt";
        try {
            WebPageFetcher.Downloaded downloaded = fetcher.downloadRaw(url);
            Charset charset = downloaded.charsetName() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(downloaded.charsetName());
            RobotsRules rules = parse(new String(downloaded.body(), charset));
            log.debug("robots.txt 已解析 url={} disallow={} crawlDelayMs={}",
                    url, rules.disallowedPaths().size(), rules.crawlDelayMs());
            return rules;
        } catch (Exception e) {
            log.debug("robots.txt 取不到或解析失败，按允许全部处理 url={}: {}", url, e.getMessage());
            return RobotsRules.ALLOW_ALL;
        }
    }

    /** 解析 robots.txt 正文。包级 static 免网络单测。 */
    static RobotsRules parse(String body) {
        List<String> disallowed = new ArrayList<>();
        Long crawlDelayMs = null;
        boolean inStarSection = false;

        for (String rawLine : body.split("\\R")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            switch (key) {
                case "user-agent" -> inStarSection = "*".equals(value);
                case "disallow" -> {
                    // Disallow 空值在 robots 规范里表示「允许全部」，不能收成前缀 "" 拦下一切
                    if (inStarSection && !value.isEmpty()) {
                        disallowed.add(value);
                    }
                }
                case "crawl-delay" -> {
                    if (inStarSection) {
                        Long parsed = parseDelayMs(value);
                        if (parsed != null) {
                            crawlDelayMs = parsed;
                        }
                    }
                }
                default -> {
                    // Sitemap / Allow / Host 等本期不处理
                }
            }
        }
        return new RobotsRules(List.copyOf(disallowed), crawlDelayMs);
    }

    /** {@code Crawl-delay} 单位是秒，可为小数；非法值返回 null（当作未声明）。 */
    private static Long parseDelayMs(String value) {
        try {
            double seconds = Double.parseDouble(value);
            return seconds <= 0 ? null : (long) (seconds * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

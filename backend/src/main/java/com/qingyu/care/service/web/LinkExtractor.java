package com.qingyu.care.service.web;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 链接发现：从已解析的 DOM 抽出「同域 + 在范围内 + 已规范化」的链接。
 * 纯函数，不碰网络也不碰数据库。
 *
 * <p>注意 Jsoup 的 Document 与本项目实体 Document 同名，此处一律用全限定名区分
 * （同 {@link WebPageFetcher} 的既有写法）。
 *
 * @author ruan
 */
@Slf4j
public final class LinkExtractor {

    private LinkExtractor() {
    }

    /**
     * 纯 tracking 参数：去掉不改变页面内容。
     * 刻意不收 {@code ref}/{@code source}——部分站点用它们做真实的内容参数，
     * 误删会把两个不同页面折叠成一个。
     */
    private static final Set<String> TRACKING_PARAMS =
            Set.of("fbclid", "gclid", "msclkid", "yclid", "spm");

    /**
     * URL 规范化。非法、非 http(s)、无主机名一律返回 {@code null}，由调用方过滤。
     *
     * <p>不做规范化的话，{@code /faq}、{@code /faq/}、{@code /faq#top}、
     * {@code /faq?utm_source=x} 会被当成四页各抓一次各建一篇档，同一篇内容在知识库里
     * 重复四份、检索时互相挤占 top-K。
     */
    public static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host.toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(normalizePath(uri.getPath()));
        String query = cleanQuery(uri.getQuery());
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    /**
     * 路径末尾斜杠统一去掉，<b>根路径例外</b>——{@code https://x.com/} 若也去掉斜杠
     * 就退化成不带路径的裸 origin，与 {@link #inScope} 的边界处理对不上。
     */
    private static String normalizePath(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /** 剔除 tracking 参数，其余按名排序（分页/语言参数是有意义的，必须保留）。 */
    private static String cleanQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return Arrays.stream(query.split("&"))
                .filter(pair -> !pair.isBlank())
                .filter(pair -> {
                    int eq = pair.indexOf('=');
                    String name = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase(Locale.ROOT);
                    return !name.startsWith("utm_") && !TRACKING_PARAMS.contains(name);
                })
                .sorted()
                .collect(Collectors.joining("&"));
    }

    /** 取 host（已小写），非法返回 null。 */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 范围判定。两个入参都必须是 {@link #normalize} 的输出。
     *
     * <p>⚠️ <b>不能写成裸 {@code startsWith}</b>，三个边界会同时出问题：
     * <ol>
     *   <li>规范化把 {@code https://x.com/help/} 变成 {@code https://x.com/help}，而
     *       用户配置的前缀常带末尾斜杠——裸 {@code startsWith(prefix)} 会把<b>入口页
     *       自己</b>判为范围外，爬取一开始就空转；</li>
     *   <li>反过来拿去掉斜杠的前缀做 {@code startsWith}，又会放进
     *       {@code https://x.com/helpdesk}（{@code /help} 是它的前缀），把隔壁目录
     *       整个爬进来；</li>
     *   <li>前缀本身是根路径（{@code https://x.com/}）时，简单地"补一个斜杠再比"
     *       会拼出 {@code https://x.com//}，一条都匹配不上。</li>
     * </ol>
     * 下面三个 return 分支各对应上面一个边界，改动前先读测试里同名的三条用例。
     */
    public static boolean inScope(String normalizedUrl, String normalizedPrefix) {
        if (normalizedUrl == null || normalizedPrefix == null) {
            return false;
        }
        String base = normalizedPrefix.endsWith("/") ? normalizedPrefix : normalizedPrefix + "/";
        String bareBase = base.substring(0, base.length() - 1);
        return normalizedUrl.equals(base)
                || normalizedUrl.equals(bareBase)
                || normalizedUrl.startsWith(base);
    }

    /**
     * 从 DOM 抽链接。{@code normalizedPrefix} 必须是 {@link #normalize} 的输出。
     *
     * <p>⚠️ 传进来的 DOM 必须是<b>剔噪之前</b>的那棵树：帮助中心的目录导航常在
     * {@code nav}/{@code header} 里，而 {@code WebPageFetcher.extractFrom} 会就地
     * 移除这些标签。详见 {@code WebPageFetcher.fetch} 里的 clone 说明。
     */
    public static Set<String> extract(org.jsoup.nodes.Document dom, String normalizedPrefix) {
        if (dom == null || normalizedPrefix == null) {
            return Set.of();
        }
        String prefixHost = hostOf(normalizedPrefix);
        if (prefixHost == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (org.jsoup.nodes.Element a : dom.select("a[href]")) {
            // 排除空 href 属性
            String href = a.attr("href");
            if (href == null || href.isBlank()) {
                continue;
            }
            String abs = a.absUrl("href");
            String normalized = normalize(abs);
            if (normalized == null) {
                continue;
            }
            // 同域是硬约束：范围前缀已由服务层校验过同 host，这里再挡一次
            // 是为了防「站内相对链接解析出别的 host」这类边角情况
            if (!prefixHost.equals(hostOf(normalized))) {
                continue;
            }
            if (!inScope(normalized, normalizedPrefix)) {
                continue;
            }
            out.add(normalized);
        }
        return out;
    }
}

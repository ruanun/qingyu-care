package com.qingyu.care.service.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
     * URL 规范化。非法、非 http(s)、无主机名、路径含目录穿越段一律返回 {@code null}，
     * 由调用方过滤。
     *
     * <p>不做规范化的话，{@code /faq}、{@code /faq/}、{@code /faq#top}、
     * {@code /faq?utm_source=x} 会被当成四页各抓一次各建一篇档，同一篇内容在知识库里
     * 重复四份、检索时互相挤占 top-K。
     *
     * <p>⚠️ 路径与查询串必须用 {@link URI#getRawPath()}/{@link URI#getRawQuery()}
     * （百分号编码原样保留）而不是 {@link URI#getPath()}/{@link URI#getQuery()}
     * （已解码）重建，两个后果都用 {@code java.net.URI} 实测过：
     * <ol>
     *   <li>{@code %20} 这类编码会被 {@code getPath()} 解码成字面空格，拼回字符串后
     *       下游 {@code WebPageFetcher.downloadRaw} 里 {@code URI.create(url).getHost()}
     *       直接抛 {@code IllegalArgumentException}（路径带字面空格是非法 URI），
     *       逃到 {@code processPage} 的 {@code catch (Exception)} 计 failed——帮助中心
     *       常见的 {@code /help/getting%20started.html} 这类地址会大批链接发现失败；</li>
     *   <li>{@code %26}（编码的 {@code &}）会被 {@code getQuery()} 解码成字面 {@code &}，
     *       把 {@code q=a%26b&utm_source=x} 这一个参数误拆成三段，query 清洗的结果不可预期。</li>
     * </ol>
     * 用原始（已编码）分量重建则两条都不发生。
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
        String rawPath = uri.getRawPath();
        if (containsDotSegment(rawPath)) {
            return null;
        }

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host.toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(normalizePath(rawPath));
        String query = cleanQuery(uri.getRawQuery());
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    /**
     * 路径含 {@code .}/{@code ..} 目录穿越段时判非法，交由 {@link #normalize} 整体拒绝
     * （而不是折叠成规范路径）。
     *
     * <p>⚠️ 必须先按段<b>解码</b>再比对，不能直接在 {@code rawPath}（已编码）上找字面
     * {@code ".."}：{@link URI#normalize()} 只折叠<b>字面</b>点号段，对
     * {@code %2E%2E} 这种已编码写法完全不生效——已用 {@code java.net.URI} 实测确认，
     * {@code new URI("https://x.com/help/%2E%2E/admin").normalize().getPath()} 仍是
     * {@code "/help/../admin"}，没有被折叠。直接放行的后果：规范化结果里仍带着
     * {@code ..}，而实际发起请求时会被下游解析成 {@code /admin}——静默绕出运营配置的
     * {@code scopePrefix}（同域内，不是跨域越权，但会把知识库灌进运营没打算收录的页面）。
     *
     * <p>选择<b>拒绝整条 URL</b> 而不是「解码后折叠回规范路径再重新编码」：手写一遍
     * RFC 3986 的 dot-segment removal 算法本身就是常见的绕过来源（大小写混合编码、
     * 多层编码等变体），拒绝更简单，也不需要为「折叠后的路径该如何重新编码」再操心。
     *
     * <p>⚠️ <b>边界</b>：本方法只覆盖<b>单次解码</b>后的点号段。{@code %252E%252E}
     * （双重编码，服务端要再解码一次才会变成 {@code ..}）与 {@code ..;}（path parameter，
     * 部分非标准服务器在路由匹配前会剥掉 {@code ;} 之后的部分）这类依赖「对端也做了同样
     * 非标准解码/剥离」的变体本方法不拦——已实测确认这两种连同 {@code ....//}、{@code ..%00}
     * 一起共四种形态能穿过本方法，但均不构成活的绕过：前两者不是 RFC 3986 定义的点号段、
     * 标准 URI 实现（含本项目实际用来发请求的 HttpClient5）不会把它们折叠成 {@code ..}；
     * 后两者只在会二次解码或剥 path parameter 的非标准服务器上才生效，且后果仅限同域内
     * 多抓一个页面，不构成越权。别把本方法当成完备的路径穿越防线——它只堵「对标准 URI
     * 语义生效的那一类」，不是「对所有可能的服务端实现都生效的那一类」。
     */
    private static boolean containsDotSegment(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        String decoded;
        try {
            // 路径段的 '+' 不是空格的同义写法（那是 query 字符串的约定），先转义成
            // %2B 防止 URLDecoder 把字面 '+' 误译成空格
            decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法的 % 转义序列：按非法 URL 处理更安全，不放行
            return true;
        }
        for (String segment : decoded.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 路径末尾斜杠统一去掉，<b>根路径例外</b>——{@code https://x.com/} 若也去掉斜杠
     * 就退化成不带路径的裸 origin，与 {@link #inScope} 的边界处理对不上。
     *
     * <p>入参是{@link URI#getRawPath()}（已编码），只做纯 ASCII 的 {@code /} 判断与截断，
     * 不涉及解码，编码内容原样保留。
     */
    private static String normalizePath(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /**
     * 剔除 tracking 参数，其余按名排序（分页/语言参数是有意义的，必须保留）。
     * 入参是 {@link URI#getRawQuery()}（已编码）——分隔符 {@code &}/{@code =} 按字面切分，
     * 值里编码的 {@code %26}/{@code %3D} 不会被误当分隔符。
     */
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
     * 下面这一个 return 语句用三个 {@code ||} 分支覆盖上面三个边界，顺序与之一一对应。
     * 改动前先读测试里「边界一」「边界二」「边界三」的三条同名用例。
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

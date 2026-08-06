package com.qingyu.care.service.web;

import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * 网页抓取器：把一个 URL 变成「干净正文 + 标题 + 内容哈希」，不碰数据库。
 *
 * <p>安全：出站前经 {@link SsrfGuard} 解析校验，并把校验期的 IP 钉入连接期（同
 * {@code RestClientHttpToolExecutor} 的做法），杜绝 DNS rebinding；硬关重定向跟随，
 * 否则 302 能把抓取从公网带进内网。另有超时、响应体大小上限与 Content-Type 白名单。
 *
 * <p>正文提取用 Jsoup 启发式剔除导航/页脚等噪声——网页不比 PDF，这些文字混进知识库
 * 会稀释检索相似度。
 *
 * @author ruan
 */
@Component
@Slf4j
public class WebPageFetcher {

    private static final int TIMEOUT_MS = 10_000;
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("text/html", "text/plain");

    /** 噪声容器：这些标签下的文字不属于正文。 */
    private static final String NOISE_SELECTOR =
            "script, style, nav, header, footer, aside, noscript, iframe, form";

    /**
     * 抓取结果。{@code dom} 是<b>剔噪之前</b>的 DOM 副本，供 {@link LinkExtractor} 抽链接。
     *
     * <p>⚠️ 必须是副本而不是原树引用：{@link #extractFrom} 会就地移除
     * {@code nav}/{@code header}/{@code footer}，而帮助中心的目录导航恰恰常在这些标签里。
     * 交出残树的后果是链接发现几乎抓不到东西，且<b>不报错</b>——表现为「爬了 1 页就结束」。
     *
     * <p>⚠️ 加了 {@code dom} 字段后，record 自动生成的 {@code equals()}/{@code hashCode()}/
     * {@code toString()} 已经不能按字面意思用了，两条都要留意：
     * <ul>
     *   <li>{@code equals()}：Jsoup 的 {@code org.jsoup.nodes.Document} 没有重写
     *       {@code equals}/{@code hashCode}，用的是 {@link Object} 的恒等语义——连
     *       {@code a.equals(a.clone())} 都是 {@code false}。<b>不要用 {@code FetchResult}
     *       做相等比较</b>（含把它塞进 {@code Set}/当 {@code Map} 键），内容完全相同的两次抓取
     *       结果也会被判不等。</li>
     *   <li>{@code toString()}：会把 {@code dom} 整棵 DOM 树序列化成 outerHtml 字符串塞进去。
     *       <b>不要把整个 {@code FetchResult} 记进日志</b>（含用 {@code {}} 占位符隐式调用
     *       {@code toString()}）——响应体上限是 5MB，对应的树一次就是一次日志事故。</li>
     * </ul>
     */
    public record FetchResult(String title, String content, String contentHash,
                              int httpStatus, org.jsoup.nodes.Document dom) {}

    /**
     * 下载结果：正文原始字节 + Content-Type 声明的编码（可为 null）+ 真实 HTTP 状态码。
     *
     * <p>保留字节而不是就地解码成 String：响应头没带 charset 时，正确编码只写在 HTML 的
     * {@code <meta charset>} 里，必须把字节交给 Jsoup 自适应（见 {@link #parseHtml}）。
     */
    public record Downloaded(byte[] body, String charsetName, int status) {}

    /** 单页抓取，走启发式噪声剔除（等价于 {@code fetch(url, null)}）。 */
    public FetchResult fetch(String url) {
        return fetch(url, null);
    }

    /**
     * 单页抓取。{@code contentSelector} 非空时只取该选择器命中的容器内文本；
     * 为空、选不中或语法非法时回退启发式剔噪（行为与不传选择器完全一致）。
     *
     * <p>⚠️ 三步的顺序不能变，且 {@code domForLinks} 必须在剔噪之前克隆：
     * {@link #extractFrom} 会就地改树，标题与链接都得赶在它之前拿到。把顺序陷阱
     * 收在本方法内部，调用方无从写错。
     */
    public FetchResult fetch(String url, String contentSelector) {
        Downloaded downloaded = downloadRaw(url);
        org.jsoup.nodes.Document parsed = parseHtml(downloaded.body(), downloaded.charsetName(), url);

        // 剔噪之前留一份副本给链接发现（extractFrom 会就地移除 nav/header/footer）
        org.jsoup.nodes.Document domForLinks = parsed.clone();
        // 标题必须在剔噪声之前取：extractFrom 会就地改这棵树
        String title = extractTitle(parsed, url);

        String content = null;
        if (contentSelector != null && !contentSelector.isBlank()) {
            content = extractBySelector(parsed, contentSelector);
            if (content.isBlank()) {
                log.warn("正文选择器未命中任何元素，回退启发式剔噪 selector={} url={}", contentSelector, url);
                content = null;
            }
        }
        if (content == null) {
            content = extractFrom(parsed);
        }
        if (content.isBlank()) {
            throw new BusinessException("网页正文为空，可能是需要 JS 渲染的页面: " + url);
        }
        return new FetchResult(title, content, sha256(content), downloaded.status(), domForLinks);
    }

    /**
     * 按 CSS 选择器取正文（白名单语义）：命中元素的文本按序拼接。
     *
     * <p>选不中或选择器语法非法都返回空串而<b>不抛异常</b>——运营写错一个选择器不该
     * 让整站抓取全线失败，回退启发式并在日志留痕是更可用的行为。
     * 包级 static 免容器单测（同本类其余提取方法的惯例）。
     */
    static String extractBySelector(org.jsoup.nodes.Document doc, String selector) {
        try {
            org.jsoup.select.Elements matched = doc.select(selector);
            if (matched.isEmpty()) {
                return "";
            }
            return matched.stream()
                    .map(org.jsoup.nodes.Element::text)
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (RuntimeException e) {
            log.warn("正文选择器语法非法，将回退启发式剔噪 selector={}: {}", selector, e.getMessage());
            return "";
        }
    }

    /**
     * 安全下载：SSRF 钉 IP + 禁重定向 + 超时 + 限量 + Content-Type 白名单。
     *
     * <p>public 是为了让 {@link RobotsChecker} 复用这一整套出站防线取 robots.txt——
     * 新开一条抓取路径就等于新开一个 SSRF 缺口，robots.txt 也必须走这里。
     */
    public Downloaded downloadRaw(String url) {
        final String host = URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException("URL 缺少主机名: " + url);
        }
        final InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);

        try (CloseableHttpClient httpClient = buildHttpClient(pinnedResolver(host, pinned))) {
            return exchange(httpClient, url);
        } catch (IOException e) {
            throw new BusinessException("网页抓取失败: " + e.getMessage());
        }
    }

    /**
     * 连接期 DNS 钉住：只对校验期那个 host 返回校验期得到的地址，其它 host 一律拒绝
     * （fail-closed）。杜绝 DNS rebinding——校验用的解析结果与实际连接用的必须是同一份。
     * 拆成包级 static 是为了能被单测直接调用：这条防线整体没有别的可观测出口。
     */
    static DnsResolver pinnedResolver(String host, InetAddress[] pinned) {
        return new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String h) throws UnknownHostException {
                if (!host.equalsIgnoreCase(h)) {
                    throw new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + h);
                }
                return pinned;
            }
        };
    }

    /**
     * 构建抓取用的 HttpClient：钉住 DNS + <b>硬关重定向跟随</b>（否则 302 能把抓取从公网
     * 带进内网，绕过上面刚做完的 SSRF 校验）+ 连接/读写/响应超时。
     * 拆成包级 static 供单测把它指向本地监听器，验证 302 真的不被跟随。
     */
    static CloseableHttpClient buildHttpClient(DnsResolver dnsResolver) {
        Timeout timeout = Timeout.ofMilliseconds(TIMEOUT_MS);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(timeout).setSocketTimeout(timeout).build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build();
    }

    /**
     * 发请求并读回正文。三道门槛的<b>顺序不能变</b>：先按状态码拒绝非 2xx（否则 404/500
     * 的错误页文案会被当正文摄入），再判 Content-Type 白名单，最后才边读边计数地取 body。
     */
    static Downloaded exchange(CloseableHttpClient httpClient, String url) {
        RestClient client = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();

        return client.get().uri(url).exchange((request, response) -> {
            int status = response.getStatusCode().value();
            requireSuccessStatus(status);
            MediaType contentType = response.getHeaders().getContentType();
            requireAllowedContentType(contentType);
            Charset charset = contentType.getCharset();
            return new Downloaded(readLimited(response.getBody()),
                    charset == null ? null : charset.name(), status);
        });
    }

    /**
     * 只放行 HTML/纯文本；PDF、图片、二进制等一律拒绝——它们既解析不出正文，
     * 又会白读满 5MB 上限。static 免容器单测，是这条风险的唯一防线。
     */
    static void requireAllowedContentType(MediaType contentType) {
        if (contentType == null
                || !ALLOWED_TYPES.contains(contentType.getType() + "/" + contentType.getSubtype())) {
            throw new BusinessException("不支持的内容类型（只接受 HTML/纯文本）: " + contentType);
        }
    }

    /**
     * 只放行 2xx；非成功状态码一律拒绝，不让错误页正文（"页面不存在""服务器错误"等）
     * 混入知识库参与检索。static 免容器单测，是这条风险的唯一防线。
     */
    static void requireSuccessStatus(int status) {
        if (status < 200 || status >= 300) {
            throw new BusinessException("网页返回 HTTP " + status);
        }
    }

    /** 边读边计数，超过上限立即中断——不能先全读进内存再判断。static 免容器单测。 */
    static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BYTES) {
                throw new BusinessException("网页内容超过 5MB 上限");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 解析正文字节为 DOM。{@code charsetName} 来自 Content-Type 头，为 null 时交给 Jsoup
     * 按 BOM/{@code <meta charset>} 自适应、再回退 UTF-8。
     *
     * <p>⚠️ 不能在这一步之前就用 UTF-8 硬解码：国内 GBK 老站（帮助中心/FAQ 的重灾区）
     * 大多不在响应头声明 charset，硬解码出的是乱码；而<b>乱码的 hash 也是稳定的</b>，
     * 会被下一轮同步判为"内容未变"，从此长期服务错误内容且永不自愈。
     *
     * <p>注意 Jsoup 的 Document 与本项目实体 Document 同名，此处用全限定名区分。
     */
    static org.jsoup.nodes.Document parseHtml(byte[] body, String charsetName, String baseUri) {
        try {
            return Jsoup.parse(new ByteArrayInputStream(body), charsetName, baseUri);
        } catch (IOException e) {
            // 入参是内存字节流，实际不会发生；包成业务异常以免调用方被迫处理受检异常
            throw new BusinessException("网页正文解析失败: " + e.getMessage());
        }
    }

    /**
     * 从 DOM 提取正文：先剔噪声容器，再取 body 文本（<b>会就地修改这棵树</b>，
     * 标题要在调用它之前取）。static 免容器单测（同 ChatServiceImpl#meterContentStream 惯例）。
     */
    static String extractFrom(org.jsoup.nodes.Document doc) {
        doc.select(NOISE_SELECTOR).remove();
        return doc.body() == null ? "" : doc.body().text();
    }

    /** 标题：优先 title 标签，其次首个 h1，都没有则回退用 URL。 */
    static String extractTitle(org.jsoup.nodes.Document doc, String fallback) {
        String title = doc.title();
        if (!title.isBlank()) {
            return title;
        }
        org.jsoup.nodes.Element h1 = doc.selectFirst("h1");
        return h1 != null && !h1.text().isBlank() ? h1.text() : fallback;
    }

    /** 正文的 SHA-256（小写十六进制，64 字符）。 */
    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("计算内容哈希失败", e);
        }
    }
}

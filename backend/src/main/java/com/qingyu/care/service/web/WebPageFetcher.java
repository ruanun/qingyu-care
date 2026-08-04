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

    public record FetchResult(String title, String content, String contentHash, int httpStatus) {}

    /**
     * 下载结果：正文原始字节 + Content-Type 声明的编码（可为 null）+ 真实 HTTP 状态码。
     *
     * <p>保留字节而不是就地解码成 String：响应头没带 charset 时，正确编码只写在 HTML 的
     * {@code <meta charset>} 里，必须把字节交给 Jsoup 自适应（见 {@link #parseHtml}）。
     */
    record Downloaded(byte[] body, String charsetName, int status) {}

    public FetchResult fetch(String url) {
        Downloaded downloaded = download(url);
        org.jsoup.nodes.Document parsed = parseHtml(downloaded.body(), downloaded.charsetName(), url);
        // 标题必须在剔噪声之前取：extractFrom 会就地改这棵树
        String title = extractTitle(parsed, url);
        String content = extractFrom(parsed);
        if (content.isBlank()) {
            throw new BusinessException("网页正文为空，可能是需要 JS 渲染的页面: " + url);
        }
        return new FetchResult(title, content, sha256(content), downloaded.status());
    }

    /** 安全下载：SSRF 钉 IP + 禁重定向 + 超时 + 限量 + Content-Type 白名单。 */
    private Downloaded download(String url) {
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

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

    public FetchResult fetch(String url) {
        String html = download(url);
        String content = extractFrom(html, url);
        if (content.isBlank()) {
            throw new BusinessException("网页正文为空，可能是需要 JS 渲染的页面: " + url);
        }
        return new FetchResult(extractTitle(html, url), content, sha256(content), 200);
    }

    /** 安全下载：SSRF 钉 IP + 禁重定向 + 超时 + 限量 + Content-Type 白名单。 */
    private String download(String url) {
        final String host = URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException("URL 缺少主机名: " + url);
        }
        final InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);

        // 连接期只对该 host 返回已校验地址，其它 host 一律拒绝（fail-closed）
        DnsResolver pinnedResolver = new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String h) throws UnknownHostException {
                if (!host.equalsIgnoreCase(h)) {
                    throw new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + h);
                }
                return pinned;
            }
        };

        Timeout timeout = Timeout.ofMilliseconds(TIMEOUT_MS);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(timeout).setSocketTimeout(timeout).build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedResolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build();

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build()) {

            RestClient client = RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                    .build();

            return client.get().uri(url).exchange((request, response) -> {
                MediaType contentType = response.getHeaders().getContentType();
                if (contentType == null
                        || !ALLOWED_TYPES.contains(contentType.getType() + "/" + contentType.getSubtype())) {
                    throw new BusinessException("不支持的内容类型（只接受 HTML/纯文本）: " + contentType);
                }
                Charset charset = contentType.getCharset() != null
                        ? contentType.getCharset() : StandardCharsets.UTF_8;
                return new String(readLimited(response.getBody()), charset);
            });
        } catch (IOException e) {
            throw new BusinessException("网页抓取失败: " + e.getMessage());
        }
    }

    /** 边读边计数，超过上限立即中断——不能先全读进内存再判断。 */
    private static byte[] readLimited(InputStream in) throws IOException {
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
     * 从 HTML 提取正文：先剔噪声容器，再取 body 文本。
     * static 免容器单测（同 ChatServiceImpl#meterContentStream 惯例）。
     * 注意 Jsoup 的 Document 与本项目实体 Document 同名，此处用全限定名区分。
     */
    static String extractFrom(String html, String baseUrl) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html, baseUrl);
        doc.select(NOISE_SELECTOR).remove();
        return doc.body() == null ? "" : doc.body().text();
    }

    /** 标题：优先 title 标签，其次首个 h1，都没有则回退用 URL。 */
    static String extractTitle(String html, String fallback) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
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

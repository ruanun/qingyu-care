package com.qingyu.care.service.tool;

import com.qingyu.care.config.PinnedHttpClientFactory;
import com.qingyu.care.config.SsrfGuard;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.io.CloseMode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 基于 RestClient + Apache HttpClient 5 的 HTTP 执行器。出站前经 {@link SsrfGuard} 解析并校验目标地址，
 * 并经 {@link PinnedHttpClientFactory} 把「校验期解析出的 IP」钉入连接期，杜绝 SSRF 的 DNS rebinding/TOCTOU：
 * TTL=0 的域名不能再「校验时返回公网 IP、连接时翻转成 169.254.169.254/内网」绕过硬门槛。
 * 主机名在 URL 中原样保留，故 TLS 的 SNI 与证书按主机名校验，不破坏合法 HTTPS。
 * 重定向跟随由工厂硬关：重定向目标不再经 SsrfGuard 校验，跳去别的 host 会走真实 DNS。
 * 响应体边读边计数（{@link #MAX_RESPONSE_BYTES}），整次调用有墙钟总时长上限，单段超时封顶 {@link #MAX_TIMEOUT_MS}。
 *
 * @author ruan
 */
@Component
public class RestClientHttpToolExecutor implements HttpToolExecutor {

    /** 单段超时上限：租户可配 {@code timeoutMs}，但不能配成 {@code Integer.MAX_VALUE} 把对话线程挂一整天。 */
    static final int MAX_TIMEOUT_MS = 30_000;
    /** 响应体读取上限：工具结果最终会被截到 {@code maxResponseChars}（默认 4000 字），1MB 绰绰有余。 */
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    /** 非 2xx 时交给模型的错误正文片段上限：够模型看懂「订单不存在」，又不把整页错误塞进上下文。 */
    private static final int ERROR_SNIPPET_CHARS = 500;

    @Override
    public String execute(String method, String url, String authHeader, String body, int timeoutMs) {
        // 出站前解析+校验目标主机一次，拿到「已校验」的全部地址，供连接期复用。
        final String host = URI.create(url).getHost();
        final InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);
        final int timeout = effectiveTimeout(timeoutMs);

        try (CloseableHttpClient httpClient = PinnedHttpClientFactory.create(
                PinnedHttpClientFactory.pinnedResolver(host, pinned), timeout)) {

            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            RestClient client = RestClient.builder().requestFactory(factory).build();

            RestClient.RequestBodySpec spec = client
                    .method("POST".equalsIgnoreCase(method) ? HttpMethod.POST : HttpMethod.GET)
                    .uri(url);
            applyAuthHeader(spec, authHeader);
            if ("POST".equalsIgnoreCase(method) && body != null && !body.isBlank()) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            RestClient.RequestBodySpec finalSpec = spec;
            // 2026-09-30 安全审查 H4：此前 retrieve().body(String.class) 把整个响应读进内存（且自动解 gzip），
            // 截断发生在读完之后——一个 gzip 炸弹或无限吐字节的对端就能把 JVM 打 OOM。
            // 现在边读边计数、超限即截断断开，整次调用另有墙钟总时长上限。
            return PinnedHttpClientFactory.callWithDeadline(httpClient, 2L * timeout,
                    () -> finalSpec.exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        Charset charset = charsetOf(response.getHeaders().getContentType());
                        String text = readLimited(response.getBody(), charset, httpClient);
                        if (status < 200 || status >= 300) {
                            // 只给状态码与正文片段，不带 URL：Spring 默认的错误消息含请求路径，
                            // 路径里可能有密钥，而这条消息会交给模型、可被访客诱导复述
                            throw new IllegalStateException("工具返回 HTTP " + status
                                    + (text.isBlank() ? "" : "：" + truncate(text, ERROR_SNIPPET_CHARS)));
                        }
                        return text;
                    }));
        } catch (IOException e) {
            throw new IllegalStateException("工具 HTTP 调用失败: " + e.getClass().getSimpleName(), e);
        }
    }

    static int effectiveTimeout(int timeoutMs) {
        if (timeoutMs <= 0) {
            return 5000;
        }
        return Math.min(timeoutMs, MAX_TIMEOUT_MS);
    }

    /**
     * 边读边计数，超过 {@link #MAX_RESPONSE_BYTES} 即截断并立刻关掉客户端——否则 Spring 关闭响应时会把剩余
     * body 读完。读到的是解压后的字节（HttpClient 自动解 gzip），所以压缩炸弹同样被这道上限挡住。
     */
    static String readLimited(InputStream in, Charset charset, CloseableHttpClient httpClient) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            int room = MAX_RESPONSE_BYTES - out.size();
            if (n >= room) {
                out.write(buf, 0, room);
                httpClient.close(CloseMode.IMMEDIATE);
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toString(charset);
    }

    private static Charset charsetOf(MediaType contentType) {
        Charset cs = contentType == null ? null : contentType.getCharset();
        return cs == null ? StandardCharsets.UTF_8 : cs;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 把 "Header-Name: value" 整行头拆成键值加到请求上。 */
    private void applyAuthHeader(RestClient.RequestBodySpec spec, String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return;
        }
        int idx = authHeader.indexOf(':');
        if (idx > 0) {
            spec.header(authHeader.substring(0, idx).trim(), authHeader.substring(idx + 1).trim());
        } else {
            spec.header(HttpHeaders.AUTHORIZATION, authHeader.trim());
        }
    }
}

package com.qingyu.care.service.web;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.support.DripHttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 单元测试：真正跑一遍 {@code WebPageFetcher.download()} 的四道安全门槛。
 *
 * <p>存在的理由：这四道门槛（5MB 上限 / Content-Type 白名单 / 禁重定向跟随 / DNS 钉住防
 * rebinding）此前<b>一行都没被执行过</b>——把任意一处改坏，全量 {@code mvn test} 与
 * {@code mvn verify} 依然全绿。其中三条是设计文档 §8 明确列出的必测项。
 *
 * <p>做法：起一个本地 {@link HttpServer}，把生产的 {@link WebPageFetcher#pinnedResolver}
 * 指向环回地址，再用生产的 {@link WebPageFetcher#buildHttpClient} 建客户端——测的是真实的
 * 客户端配置与真实的门槛顺序，不是另写一份仿制品。注意此路径刻意<b>绕过</b>
 * {@code SsrfGuard}（它会拒绝环回地址，见 {@link #内网地址在出站前就被SSRF守卫拒绝()}
 * 单独覆盖），否则本地监听器根本连不上。
 *
 * @author ruan
 */
class WebPageFetcherDownloadTest {

    private static final String FAKE_HOST = "web-fetcher-it.example.com";

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

        server.createContext("/ok", ex -> respond(ex, 200, "text/html;charset=UTF-8",
                "<html><head><title>正常页</title></head><body><p>正文内容</p></body></html>"
                        .getBytes(StandardCharsets.UTF_8)));

        // 302 指向同一台服务器上的 /ok：若重定向被跟随，调用方会拿到 200 而非 302
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://" + FAKE_HOST + ":" + port + "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        server.createContext("/big", ex -> respond(ex, 200, "text/html",
                new byte[5 * 1024 * 1024 + 4096]));

        server.createContext("/pdf", ex -> respond(ex, 200, "application/pdf",
                "%PDF-1.7 假装是个 PDF".getBytes(StandardCharsets.UTF_8)));

        // 国内 GBK 老站的典型形态：响应头不带 charset，真编码只写在 <meta> 里
        server.createContext("/gbk", ex -> respond(ex, 200, "text/html",
                ("<html><head><meta charset=\"gbk\"><title>退货政策</title></head>"
                        + "<body><p>自购买之日起七天内可无理由退货。</p></body></html>")
                        .getBytes(Charset.forName("GBK"))));

        // body 刻意超过 5MB 上限：这样「先判状态码、再读 body」的顺序才有区分力——顺序若被
        // 改成先读后判，抛出的会是「超过 5MB 上限」而不是「HTTP 404」，用例立刻转红。
        // 用小 body 时两种顺序都抛「HTTP 404」，测试对顺序毫无察觉
        server.createContext("/notfound", ex -> respond(ex, 404, "text/html",
                new byte[5 * 1024 * 1024 + 4096]));

        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** 用生产代码的 resolver + client 配置访问本地监听器。 */
    private WebPageFetcher.Downloaded download(String path) throws IOException {
        DnsResolver resolver = WebPageFetcher.pinnedResolver(
                FAKE_HOST, new InetAddress[]{InetAddress.getLoopbackAddress()});
        try (CloseableHttpClient client = WebPageFetcher.buildHttpClient(resolver)) {
            return WebPageFetcher.exchange(client, "http://" + FAKE_HOST + ":" + port + path);
        }
    }

    @Test
    void 正常页面能读回正文与状态码() throws Exception {
        WebPageFetcher.Downloaded downloaded = download("/ok");

        assertThat(downloaded.status()).isEqualTo(200);
        assertThat(new String(downloaded.body(), StandardCharsets.UTF_8)).contains("正文内容");
        assertThat(downloaded.charsetName()).isEqualTo("UTF-8");
    }

    @Test
    void 重定向不被跟随_302直接判失败() {
        // disableRedirectHandling() 是这条防线的唯一实现：一旦被跟随，302 能把抓取从公网
        // 带进内网，绕过上一步刚做完的 SsrfGuard 校验（校验的是原始 URL 的地址）。
        // 删掉那一行后本用例会拿到 /ok 的 200 而不抛异常，测试转红
        assertThatThrownBy(() -> download("/redirect"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("302");
    }

    @Test
    void 超过5MB的响应被拒() {
        assertThatThrownBy(() -> download("/big"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5MB");
    }

    @Test
    void 非白名单ContentType的响应被拒() {
        assertThatThrownBy(() -> download("/pdf"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的内容类型");
    }

    @Test
    void 非2xx响应先于读body被拒_错误页文案不会进正文() {
        assertThatThrownBy(() -> download("/notfound"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("404")
                // 拒绝理由必须是状态码。/notfound 的 body 超过 5MB（见 startServer），
                // 所以"先读 body 再判状态码"会抛「超过 5MB 上限」——这两条断言合起来
                // 才真的钉住顺序，而不只是钉住"404 会被拒"
                .hasMessageNotContainingAny("5MB", "正文为空", "不支持的内容类型");
    }

    @Test
    void 响应头无charset时按meta自适应而非硬解UTF8() throws Exception {
        WebPageFetcher.Downloaded downloaded = download("/gbk");
        assertThat(downloaded.charsetName()).isNull();   // 服务端确实没声明

        org.jsoup.nodes.Document doc = WebPageFetcher.parseHtml(
                downloaded.body(), downloaded.charsetName(), "http://" + FAKE_HOST + "/gbk");

        assertThat(WebPageFetcher.extractTitle(doc, "fallback")).isEqualTo("退货政策");
        assertThat(WebPageFetcher.extractFrom(doc)).contains("七天内可无理由退货");
    }

    // ===== DNS 钉住（防 rebinding）=====

    @Test
    void 钉住的解析器只放行校验期那个主机() throws Exception {
        InetAddress[] pinned = {InetAddress.getLoopbackAddress()};
        DnsResolver resolver = WebPageFetcher.pinnedResolver("example.com", pinned);

        assertThat(resolver.resolve("example.com")).isEqualTo(pinned);
        assertThat(resolver.resolve("EXAMPLE.COM")).isEqualTo(pinned);   // 主机名大小写不敏感
    }

    @Test
    void 钉住的解析器拒绝任何其它主机() {
        DnsResolver resolver = WebPageFetcher.pinnedResolver(
                "example.com", new InetAddress[]{InetAddress.getLoopbackAddress()});

        // 这是防 DNS rebinding 的唯一实现：删掉 resolve() 里的 host 比对后，
        // 连接期对任意主机都会返回钉住的地址，本用例转红
        assertThatThrownBy(() -> resolver.resolve("evil.internal"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("SSRF");
    }

    @Test
    void 内网地址在出站前就被SSRF守卫拒绝() {
        // fetch() 里的 SsrfGuard.resolveAndValidate 才是拒绝内网目标的入口；
        // 删掉它这里会变成连接失败（BusinessException），断言的异常类型即刻转红
        assertThatThrownBy(() -> new WebPageFetcher().fetch("http://127.0.0.1:" + port + "/ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }

    /**
     * 2026-09-30 安全审查 H3 同类：5MB 上限挡「吐得多」，挡不住「吐得慢」——每 200ms 滴 1 字节永远不触发
     * 10 秒的空闲超时，此前这次抓取会一直占着长任务线程与全局爬取名额。总时长上限到点即中断。
     */
    @Test
    void 慢速滴灌的页面到总时长上限即中断() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (DripHttpServer drip = new DripHttpServer("text/html", 200)) {
                DnsResolver resolver = WebPageFetcher.pinnedResolver(
                        "localhost", new InetAddress[]{InetAddress.getLoopbackAddress()});
                try (CloseableHttpClient client = WebPageFetcher.buildHttpClient(resolver)) {
                    assertThatThrownBy(() -> WebPageFetcher.exchange(client, drip.url("/slow"),
                            Set.of("text/html"), 1500))
                            .isInstanceOf(BusinessException.class)
                            .hasMessageContaining("秒上限");
                }
            }
        });
    }
}

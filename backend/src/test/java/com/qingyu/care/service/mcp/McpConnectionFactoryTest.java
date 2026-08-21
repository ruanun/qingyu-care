package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import com.qingyu.care.enums.McpTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF 守卫。这几条守的是「静默失效」——漏掉 resolver 或 followRedirect(false) 后
 * 代码照常编译、连接照常建立、功能照常可用，只是防护没了。
 *
 * @author ruan
 */
class McpConnectionFactoryTest {

    private final McpConnectionFactory factory = new McpConnectionFactory();

    @Test
    @DisplayName("出站客户端必须钉住 PinnedAddressResolverGroup 并禁用重定向")
    void buildHttpClient_pinsResolverAndDisablesRedirect() throws Exception {
        InetAddress[] pinned = {InetAddress.getByName("93.184.216.34")};

        var client = McpConnectionFactory.buildHttpClient("example.com", pinned, 10000);

        // 必须精确判类型：isNotNull() 连「换成 DefaultAddressResolverGroup.INSTANCE」都拦不住，
        // 而那正是这段防护要挡的 SSRF 绕过（DefaultAddressResolverGroup.INSTANCE 本身非 null）。
        assertThat(client.configuration().resolver()).isInstanceOf(PinnedAddressResolverGroup.class);
        // ⚠️ 这条断言不 pin 住 buildHttpClient 里 .followRedirect(false) 那一行本身——
        // reactor-netty 1.3.6 的 HttpClient.create() 默认已是「不跟随重定向」，
        // .followRedirect(false) 在全新 client 上是字面意义的 no-op（源码
        // HttpClient.java:981-988：!followRedirect 且三个内部字段皆 null 时直接 return this），
        // 删掉那一行这条断言原样通过。它仍然守住另一个方向：谁往回加一次
        // .followRedirect(true)，或上游默认值翻转成「默认跟随」，这条会立刻转红。
        // 真正验证「重定向确实没被跟随」这个行为的是下面 buildHttpClient_doesNotFollowRedirect。
        assertThat(client.configuration().followRedirectPredicate()).isNull();
    }

    @Test
    @DisplayName("真实请求命中 302 时原样暴露状态码，不会自动跟随跳转")
    void buildHttpClient_doesNotFollowRedirect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // 跳转目标故意指向一个必然连不上的地址：如果客户端真的跟随了重定向，
            // 这次请求会因连接失败而抛异常，而不是静默返回 302——保证了这条测试的判别力。
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/unreachable");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            InetAddress[] pinned = {InetAddress.getByName("127.0.0.1")};
            var httpClient = McpConnectionFactory.buildHttpClient("localhost", pinned, 5000);

            Integer status = httpClient.get()
                    .uri("http://localhost:" + port + "/")
                    .responseSingle((response, body) -> Mono.just(response.status().code()))
                    .block(Duration.ofSeconds(5));

            assertThat(status).isEqualTo(302);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("指向内网地址的 server 在建连接前就被拒")
    void create_rejectsInternalAddress() {
        McpServer server = McpServer.builder()
                .name("恶意")
                .code("evil")
                .url("http://169.254.169.254/latest/meta-data")
                .timeoutMs(5000)
                .build();

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SSE 传输本期不接线，明确拒绝而非留一个静默连不上的分支")
    void create_rejectsSseTransport() {
        McpServer server = McpServer.builder()
                .name("旧版系统")
                .code("legacy")
                .transport(McpTransport.SSE)
                .url("https://example.com/sse")
                .timeoutMs(5000)
                .build();

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("STREAMABLE_HTTP");
    }
}

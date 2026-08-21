package com.qingyu.care.service.mcp;

import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.enums.McpTransport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.netty.channel.ChannelOption;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;

/**
 * 按 {@link McpServer} 建一个已初始化的 {@link McpConnection}。**唯一碰 MCP 协议的地方**。
 *
 * <p>出站前经 {@link SsrfGuard} 解析并校验目标地址，并把「校验期解析出的 IP」钉入连接期，
 * 杜绝 SSRF 的 DNS rebinding/TOCTOU——语义与 {@code RestClientHttpToolExecutor} 对等，
 * 只是那边用 Apache HttpClient 的 DnsResolver、这边用 Reactor Netty 的 AddressResolverGroup。
 *
 * <p>⚠️ 两处防护都是**静默失效**型的，改动时别删：
 * <ul>
 *   <li>{@code .resolver(...)} 一旦去掉，退回真实 DNS，「校验时公网、连接时翻内网」即可绕过；
 *   <li>{@code .followRedirect(false)} 一旦去掉，302 能把请求带去未经校验的主机。
 * </ul>
 * 两者去掉后代码照常编译、连接照常建立、功能照常可用，没有任何现象提醒。
 *
 * <p>⚠️ 传输只能用 webflux 这条线。httpclient 版基于 JDK HttpClient，不支持自定义 DNS
 * 解析，钉 IP 无从谈起（见 MCP 设计文档 §9）。
 *
 * <p>⚠️ {@code McpTransport.SSE} 本期不接线：{@code WebFluxSseClientTransport} 已被 MCP SDK
 * 标 {@code @Deprecated(forRemoval=true)}，且只有 {@code sseEndpoint(String)}、没有 base-URL
 * setter——base URL 只能来自 {@code WebClient.Builder.baseUrl(...)}，而这里只设了
 * {@code clientConnector}，硬接只会做出一个对着相对路径 {@code /sse} 发请求、静默连不上的分支。
 * 遇到 SSE 直接拒绝，好过留一个看似支持实则连不通的坑；{@link McpTransport#SSE} 枚举值本身保留，
 * 供将来 SDK 修好/换传输后再接线。
 *
 * @author ruan
 */
@Component
public class McpConnectionFactory {

    /**
     * 建立并初始化一个 MCP 连接。调用方负责 {@link McpConnection#close()}。
     *
     * @throws IllegalArgumentException      目标地址未通过 SSRF 校验
     * @throws UnsupportedOperationException {@code server} 的传输方式是 SSE（本期未接线）
     */
    public McpConnection create(McpServer server) {
        if (server.getTransport() == McpTransport.SSE) {
            throw new UnsupportedOperationException(
                    "SSE 传输暂不支持，请使用 STREAMABLE_HTTP: server=" + server.getCode());
        }

        String host = URI.create(server.getUrl()).getHost();
        if (host == null) {
            throw new IllegalArgumentException("MCP server URL 无法解析主机名: " + server.getUrl());
        }
        InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);

        PinnedAddressResolverGroup resolverGroup = new PinnedAddressResolverGroup(host, pinned);
        HttpClient httpClient = buildHttpClient(resolverGroup, server.getTimeoutMs());
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (server.getAuthHeader() != null && !server.getAuthHeader().isBlank()) {
            applyAuthHeader(webClientBuilder, server.getAuthHeader());
        }

        McpClientTransport transport = WebClientStreamableHttpTransport.builder(webClientBuilder)
                .endpoint(server.getUrl())
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(server.getTimeoutMs()))
                .build();
        try {
            client.initialize();
        } catch (RuntimeException e) {
            // initialize() 失败时 client 还没交给调用方——调用方压根没拿到引用，无从清理；
            // 此处必须自己兜底关闭 client 与 resolverGroup，否则已建立的连接与 resolver group
            // 一起永久泄漏。两者都尝试关，任一失败都追加进原始异常而不吞没另一个。
            closeQuietly(client, resolverGroup, e);
            throw e;
        }
        return new McpConnection(client, resolverGroup);
    }

    /**
     * 构造钉住已校验地址、禁用重定向的出站客户端。包级可见供测试断言这两处防护还在。
     */
    static HttpClient buildHttpClient(String host, InetAddress[] pinned, int timeoutMs) {
        return buildHttpClient(new PinnedAddressResolverGroup(host, pinned), timeoutMs);
    }

    private static HttpClient buildHttpClient(PinnedAddressResolverGroup resolverGroup, int timeoutMs) {
        return HttpClient.create()
                .resolver(resolverGroup)
                .followRedirect(false)
                // 只设 responseTimeout 时连接阶段仍走 Netty 默认 30s，租户配的 timeoutMs 对
                // 「地址不可达/端口不通」这种最常见的错误配置形态完全不生效，必须显式钉连接超时。
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
    }

    /** initialize() 失败时的兜底清理：两者都尝试关，失败互不吞没，都追加进原始异常。 */
    private static void closeQuietly(McpSyncClient client, PinnedAddressResolverGroup resolverGroup,
            RuntimeException original) {
        try {
            client.closeGracefully();
        } catch (RuntimeException closeFailure) {
            original.addSuppressed(closeFailure);
        }
        try {
            resolverGroup.close();
        } catch (RuntimeException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    /** 把 "Header-Name: value" 整行头拆成键值（格式同 AiTool.authHeader）。 */
    private void applyAuthHeader(WebClient.Builder builder, String authHeader) {
        int idx = authHeader.indexOf(':');
        if (idx > 0) {
            builder.defaultHeader(authHeader.substring(0, idx).trim(),
                    authHeader.substring(idx + 1).trim());
        } else {
            builder.defaultHeader("Authorization", authHeader.trim());
        }
    }
}

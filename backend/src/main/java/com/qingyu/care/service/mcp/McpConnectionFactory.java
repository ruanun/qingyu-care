package com.qingyu.care.service.mcp;

import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.enums.McpTransport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.netty.resolver.AddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.ai.mcp.client.webflux.transport.WebFluxSseClientTransport;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;

/**
 * 按 {@link McpServer} 建一个已初始化的 {@link McpSyncClient}。**唯一碰 MCP 协议的地方**。
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
 * @author ruan
 */
@Component
@Slf4j
public class McpConnectionFactory {

    /**
     * 建立并初始化一个 MCP 客户端。调用方负责 {@code closeGracefully()}。
     *
     * @throws IllegalArgumentException 目标地址未通过 SSRF 校验
     */
    public McpSyncClient create(McpServer server) {
        String host = URI.create(server.getUrl()).getHost();
        if (host == null) {
            throw new IllegalArgumentException("MCP server URL 无法解析主机名: " + server.getUrl());
        }
        InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);

        HttpClient httpClient = buildHttpClient(host, pinned, server.getTimeoutMs());
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (server.getAuthHeader() != null && !server.getAuthHeader().isBlank()) {
            applyAuthHeader(webClientBuilder, server.getAuthHeader());
        }

        McpClientTransport transport = server.getTransport() == McpTransport.SSE
                ? WebFluxSseClientTransport.builder(webClientBuilder).build()
                : WebClientStreamableHttpTransport.builder(webClientBuilder)
                        .endpoint(server.getUrl())
                        .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(server.getTimeoutMs()))
                .build();
        client.initialize();
        return client;
    }

    /**
     * 构造钉住已校验地址、禁用重定向的出站客户端。包级可见供测试断言这两处防护还在。
     */
    static HttpClient buildHttpClient(String host, InetAddress[] pinned, int timeoutMs) {
        AddressResolverGroup<?> pinnedResolver = new PinnedAddressResolverGroup(host, pinned);
        return HttpClient.create()
                .resolver(pinnedResolver)
                .followRedirect(false)
                .responseTimeout(Duration.ofMillis(timeoutMs));
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

package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ⚠️ 适配 Ruling R8：{@link McpConnectionFactory#create} 返回的是 {@link McpConnection}
 * （持 {@link McpSyncClient} + 钉住的 resolver group），不是裸 {@code McpSyncClient}。
 * 本测试借同包可见的包级构造器包一层真实 {@link McpConnection}（含真实但从未真正解析
 * 的 {@link PinnedAddressResolverGroup}，构造与 {@code close()} 均不触发任何网络 I/O），
 * 内部的 {@code McpSyncClient} 仍是 mock——「连接被关闭」通过验证
 * {@code client.closeGracefully()} 被调用来间接断言（{@link McpConnection#close()}
 * 内部委托给它）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class RemoteMcpToolExecutorTest {

    @Mock
    private McpConnectionFactory connectionFactory;
    @Mock
    private McpSyncClient client;

    private RemoteMcpToolExecutor executor;

    private final McpServer server = McpServer.builder()
            .name("订单系统").code("order").url("https://mcp.example.com/mcp").timeoutMs(10000)
            .maxResponseChars(4000).build();

    private RemoteMcpToolExecutor newExecutor() {
        return new RemoteMcpToolExecutor(connectionFactory, new ObjectMapper());
    }

    /** 把 mock client 包进一个真实的 McpConnection，好让 close() 真的委托到它身上以便验证。 */
    private McpConnection wrap(McpSyncClient client) throws Exception {
        InetAddress[] pinned = {InetAddress.getByName("127.0.0.1")};
        return new McpConnection(client, new PinnedAddressResolverGroup("mcp.example.com", pinned));
    }

    @Test
    @DisplayName("listTools 把 Map 形态的 inputSchema 序列化成 JSON 文本")
    void listTools_serializesInputSchema() throws Exception {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("query_order")
                .description("查订单")
                .inputSchema(Map.of("type", "object"))
                .build();
        when(connectionFactory.create(server)).thenReturn(wrap(client));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        executor = newExecutor();

        List<McpToolDescriptor> result = executor.listTools(server);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("query_order");
        assertThat(result.get(0).inputSchemaJson()).contains("\"type\"").contains("\"object\"");
        verify(client).closeGracefully();
    }

    @Test
    @DisplayName("annotations 整个为 null 时 readOnlyHint 取 null 而不是 NPE")
    void listTools_toleratesNullAnnotations() throws Exception {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search")
                .inputSchema(Map.of("type", "object"))
                .build();
        when(connectionFactory.create(server)).thenReturn(wrap(client));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        executor = newExecutor();

        List<McpToolDescriptor> result = executor.listTools(server);

        assertThat(result.get(0).readOnlyHint()).isNull();
    }

    @Test
    @DisplayName("callTool 拼接 TextContent 并始终关闭连接")
    void callTool_joinsTextContentAndCloses() throws Exception {
        when(connectionFactory.create(server)).thenReturn(wrap(client));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("订单已发货")), false, null, null));
        executor = newExecutor();

        String out = executor.callTool(server, "query_order", Map.of("id", "A1"));

        assertThat(out).isEqualTo("订单已发货");
        verify(client).closeGracefully();
    }

    @Test
    @DisplayName("远端抛异常时连接仍被关闭，不泄漏")
    void callTool_closesOnFailure() throws Exception {
        when(connectionFactory.create(server)).thenReturn(wrap(client));
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenThrow(new IllegalStateException("boom"));
        executor = newExecutor();

        try {
            executor.callTool(server, "query_order", Map.of());
        } catch (RuntimeException ignored) {
            // 预期抛出，本用例只关心连接被关掉
        }

        verify(client).closeGracefully();
    }
}

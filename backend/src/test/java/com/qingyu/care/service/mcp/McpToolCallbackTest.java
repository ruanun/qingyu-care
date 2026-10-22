package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpToolCallbackTest {

    @Mock
    private McpToolExecutor executor;

    private final McpServer server = McpServer.builder()
            .name("订单系统").code("order").url("https://mcp.example.com/mcp")
            .timeoutMs(10000).maxResponseChars(10).build();

    private McpToolCallback callbackFor(McpTool tool) {
        return new McpToolCallback(server, tool, executor, new ObjectMapper());
    }

    private McpTool tool(String name, String schema) {
        return McpTool.builder().mcpServerId(1L).name(name).description("查订单").inputSchema(schema).build();
    }

    @Test
    @DisplayName("暴露给 LLM 的名字带 server code 前缀，调用时用原名")
    void definitionUsesPrefixedName_callUsesRawName() {
        McpToolCallback cb = callbackFor(tool("query_order", "{\"type\":\"object\"}"));
        when(executor.callTool(eq(server), eq("query_order"), anyMap())).thenReturn("ok");

        assertThat(cb.getToolDefinition().name()).isEqualTo("order__query_order");
        cb.call("{\"id\":\"A1\"}");
        verify(executor).callTool(eq(server), eq("query_order"), eq(Map.of("id", "A1")));
    }

    @Test
    @DisplayName("inputSchema 为空时兜底成合法空对象 schema")
    void definition_fallsBackToEmptySchema() {
        McpToolCallback cb = callbackFor(tool("search", null));

        assertThat(cb.getToolDefinition().inputSchema()).isEqualTo("{\"type\":\"object\",\"properties\":{}}");
    }

    @Test
    @DisplayName("远端失败时 fail-soft 返回文本，不抛异常中断整轮对话")
    void call_failsSoft() {
        McpToolCallback cb = callbackFor(tool("query_order", "{}"));
        when(executor.callTool(any(), anyString(), anyMap())).thenThrow(new IllegalStateException("连接超时"));

        String out = cb.call("{}");

        assertThat(out).contains("工具调用失败");   // 外层是 <tool_result> 定界（安全审查 L10）
    }

    @Test
    @DisplayName("响应按 maxResponseChars 截断")
    void call_truncates() {
        McpToolCallback cb = callbackFor(tool("query_order", "{}"));
        when(executor.callTool(any(), anyString(), anyMap())).thenReturn("0123456789ABCDEF");

        // 截断作用在正文上，<tool_result> 定界标签不占额度（安全审查 L10）
        String result = cb.call("{}");
        assertThat(result).startsWith("<tool_result>").endsWith("</tool_result>");
        assertThat(result.substring("<tool_result>".length(), result.length() - "</tool_result>".length())).hasSize(10);
    }
}

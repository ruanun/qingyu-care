package com.qingyu.care.service.tool;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AiTool tool() {
        return AiTool.builder()
                .name("query_order").description("查订单")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}")
                .httpMethod("GET").urlTemplate("http://8.8.8.8/orders/{orderId}")
                .authHeader("Authorization: Bearer k").timeoutMs(3000).maxResponseChars(10)
                .enabled(true).build();
    }

    @Test
    void definitionCarriesNameDescriptionSchema() {
        HttpToolCallback cb = new HttpToolCallback(tool(), (m, u, a, b, t) -> "", mapper);
        assertThat(cb.getToolDefinition().name()).isEqualTo("query_order");
        assertThat(cb.getToolDefinition().description()).isEqualTo("查订单");
        assertThat(cb.getToolDefinition().inputSchema()).contains("orderId");
    }

    @Test
    void callFillsUrlPlaceholderAndPassesAuthAndTimeout() {
        String[] captured = new String[4];
        HttpToolExecutor exec = (method, url, auth, body, timeout) -> {
            captured[0] = method; captured[1] = url; captured[2] = auth;
            captured[3] = String.valueOf(timeout);
            return "OK";
        };
        HttpToolCallback cb = new HttpToolCallback(tool(), exec, mapper);

        String result = cb.call("{\"orderId\":\"A123\"}");

        assertThat(captured[0]).isEqualTo("GET");
        assertThat(captured[1]).isEqualTo("http://8.8.8.8/orders/A123");
        assertThat(captured[2]).isEqualTo("Authorization: Bearer k");
        assertThat(captured[3]).isEqualTo("3000");
        assertThat(result).isEqualTo("OK");
    }

    @Test
    void callTruncatesResponseToMaxResponseChars() {
        HttpToolCallback cb = new HttpToolCallback(tool(), (m, u, a, b, t) -> "0123456789ABCDEF", mapper);
        String result = cb.call("{\"orderId\":\"A123\"}");
        assertThat(result).hasSize(10); // maxResponseChars=10
    }

    @Test
    void callReturnsErrorTextInsteadOfThrowing() {
        HttpToolCallback cb = new HttpToolCallback(tool(), (m, u, a, b, t) -> {
            throw new RuntimeException("boom");
        }, mapper);
        String result = cb.call("{\"orderId\":\"A123\"}");
        assertThat(result).contains("工具调用失败");
    }

    @Test
    void callUrlEncodesParameterValues() {
        String[] captured = new String[1];
        HttpToolExecutor exec = (method, url, auth, body, timeout) -> { captured[0] = url; return "OK"; };
        HttpToolCallback cb = new HttpToolCallback(tool(), exec, mapper);
        cb.call("{\"orderId\":\"a&b c\"}");
        assertThat(captured[0]).doesNotContain("orders/a&b"); // 结构字符已转义，未破坏 URL 结构
        assertThat(captured[0]).contains("a%26b");            // & 被编码为 %26
    }

    @Test
    void callSendsRawJsonBodyForPost() {
        AiTool postTool = AiTool.builder()
                .name("create_ticket").description("建单")
                .inputSchema("{}").httpMethod("POST").urlTemplate("http://8.8.8.8/tickets")
                .timeoutMs(3000).maxResponseChars(100).enabled(true).build();
        String[] capturedBody = new String[1];
        HttpToolExecutor exec = (method, url, auth, body, timeout) -> { capturedBody[0] = body; return "OK"; };
        HttpToolCallback cb = new HttpToolCallback(postTool, exec, mapper);
        cb.call("{\"title\":\"x\"}");
        assertThat(capturedBody[0]).isEqualTo("{\"title\":\"x\"}");
    }
}

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
        // 工具返回包在 <tool_result> 里，提示模型这是数据不是指令（安全审查 L10）
        assertThat(result).isEqualTo("<tool_result>OK</tool_result>");
    }

    @Test
    void callTruncatesResponseToMaxResponseChars() {
        HttpToolCallback cb = new HttpToolCallback(tool(), (m, u, a, b, t) -> "0123456789ABCDEF", mapper);
        String result = cb.call("{\"orderId\":\"A123\"}");
        // 截断作用在正文上，定界标签不占额度
        assertThat(result).isEqualTo("<tool_result>0123456789</tool_result>"); // maxResponseChars=10
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
    void callReturnsMissingParamErrorWhenPathPlaceholderUnfilled() {
        // 守的是本次新增的行为：UrlTemplateFiller.fill 在 path 段占位符未被 LLM 填时会抛
        // IllegalArgumentException（见 UrlTemplateFillerTest），这条异常必须被 call() 既有的
        // catch 接住转成工具结果文本，而不是逃逸中断整轮对话。与上一条不同——上一条测的是
        // executor 抛异常这一既有事实，这条测的是 URL 填充阶段抛出的新异常路径。
        HttpToolCallback cb = new HttpToolCallback(tool(), (m, u, a, b, t) -> "OK", mapper);
        String result = cb.call("{}");
        assertThat(result).isEqualTo("<tool_result>工具调用失败: 缺少必填参数 orderId</tool_result>");
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
    /**
     * 真机冒烟发现（2026-09-03）：工具调用失败时日志只记异常类名，
     * {@code 工具 xxx 调用失败: IllegalArgumentException} 看不出是被 SSRF 守卫拦下还是网络问题。
     * 本机代理是 fake-IP 模式（所有域名解析到 198.18.0.x，恰在守卫黑名单里），排查花了好几分钟；
     * 生产上若某个租户的工具域名解析到被拦网段，运维会完全无从下手。
     *
     * <p>守卫抛的 {@code IllegalArgumentException} 消息里只有 host，可安全入日志；
     * 其余异常只记类名——它们的消息可能带完整 URL，而 URL 的 query 段可能含密钥。
     */
    @Test
    void describeForLog_守卫异常记消息_其余只记类名() {
        assertThat(HttpToolCallback.describeForLog(
                new IllegalArgumentException("工具 URL 指向内网/环回地址，已拒绝: 198.18.0.116")))
                .contains("IllegalArgumentException")
                .contains("198.18.0.116");

        assertThat(HttpToolCallback.describeForLog(
                new IllegalStateException("工具 HTTP 调用失败: https://api.example.com/x?apikey=SECRET")))
                .isEqualTo("IllegalStateException")
                .doesNotContain("SECRET");
    }
}

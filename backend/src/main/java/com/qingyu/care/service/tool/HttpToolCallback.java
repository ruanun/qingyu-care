package com.qingyu.care.service.tool;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

/**
 * 把一个 {@link AiTool} 配置包装成 Spring AI 可调用的工具。构造期已绑定该租户的工具配置
 * （URL/authHeader 等），故执行时无需 ToolContext / TenantContext。
 *
 * @author ruan
 */
@Slf4j
public class HttpToolCallback implements ToolCallback {

    private final AiTool tool;
    private final HttpToolExecutor executor;
    private final ObjectMapper mapper;

    public HttpToolCallback(AiTool tool, HttpToolExecutor executor, ObjectMapper mapper) {
        this.tool = tool;
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String schema = (tool.getInputSchema() == null || tool.getInputSchema().isBlank())
                ? "{\"type\":\"object\",\"properties\":{}}"
                : tool.getInputSchema();
        return DefaultToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .inputSchema(schema)
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().build();
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> args = parseArgs(toolInput);
            String url;
            try {
                url = UrlTemplateFiller.fill(tool.getUrlTemplate(), args);
            } catch (IllegalArgumentException e) {
                // 「缺少必填参数 X」「参数 X 取值非法」：模型据此补参数重试，消息只含参数名
                throw new ToolCallFailure(e.getMessage(), e);
            }
            String body = "POST".equalsIgnoreCase(tool.getHttpMethod()) ? toolInput : null;
            // 审计：记录工具名与参数键（不记值，避免泄露）
            log.info("调用工具 {} 参数键={}", tool.getName(), args.keySet());
            String resp = executor.execute(tool.getHttpMethod(), url, tool.getAuthHeader(), body, tool.getTimeoutMs());
            return ToolResultDelimiter.wrap(truncate(resp, tool.getMaxResponseChars()));
        } catch (Exception e) {
            log.warn("工具 {} 调用失败: {}", tool.getName(), describeForLog(e));
            return ToolResultDelimiter.wrap("工具调用失败: " + describeForModel(e));
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    /**
     * 交给模型的失败原因（2026-09-30 安全审查）：这条消息进模型上下文，访客能诱导模型复述出来。
     * 只有抛出方声明过「消息可外露」的 {@link ToolCallFailure} 原样给；其余异常只给类名——
     * Spring 的 {@code ResourceAccessException}、{@code URI.create} 抛的 IAE 消息里都带完整请求 URL，路径或查询串里可能有密钥。
     */
    public static String describeForModel(Exception e) {
        if (e instanceof ToolCallFailure) {
            return e.getMessage();
        }
        return "网络或服务异常（" + e.getClass().getSimpleName() + "）";
    }

    /**
     * 失败原因的日志描述：守卫抛的 {@link IllegalArgumentException} 消息里只有 host（"指向内网/环回
     * 地址，已拒绝: x.x.x.x"、"缺少必填参数 X"），可安全入日志；其余异常只记类名——它们的消息可能
     * 带完整 URL，而 query 段可能含密钥。
     *
     * <p>真机冒烟发现（2026-09-03）：原先一律只记类名，被 SSRF 守卫拦下时日志只有
     * {@code IllegalArgumentException} 一个词，看不出是网段被拦、参数没填还是别的。
     *
     * <p>{@code public} 是因为 {@code McpToolCallback} 复用它——两条工具链路的失败日志口径必须一致，
     * 否则同一个 SSRF 拦截在 HTTP 工具上能看出原因、在 MCP 上看不出。
     */
    public static String describeForLog(Exception e) {
        return e instanceof IllegalArgumentException || e instanceof ToolCallFailure
                ? e.getClass().getSimpleName() + ": " + e.getMessage()
                : e.getClass().getSimpleName();
    }

    private Map<String, Object> parseArgs(String toolInput) throws Exception {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(toolInput, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

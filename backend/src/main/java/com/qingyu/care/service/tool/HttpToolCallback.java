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
            String url = UrlTemplateFiller.fill(tool.getUrlTemplate(), args);
            String body = "POST".equalsIgnoreCase(tool.getHttpMethod()) ? toolInput : null;
            // 审计：记录工具名与参数键（不记值，避免泄露）
            log.info("调用工具 {} 参数键={}", tool.getName(), args.keySet());
            String resp = executor.execute(tool.getHttpMethod(), url, tool.getAuthHeader(), body, tool.getTimeoutMs());
            return truncate(resp, tool.getMaxResponseChars());
        } catch (Exception e) {
            log.warn("工具 {} 调用失败: {}", tool.getName(), e.getClass().getSimpleName());
            return "工具调用失败: " + e.getMessage();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
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

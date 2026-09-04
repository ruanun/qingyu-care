package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 把一个已落库的 {@link McpTool} 包装成 Spring AI 可调用的工具。结构与 HttpToolCallback 对称：
 * 构造期绑定该租户的配置，执行时无需 ToolContext / TenantContext。
 *
 * <p>连接不在这里持有——每次 {@link #call} 才经 {@link McpToolExecutor} 建、用完即弃。
 *
 * @author ruan
 */
@Slf4j
public class McpToolCallback implements ToolCallback {

    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final McpServer server;
    private final McpTool tool;
    private final McpToolExecutor executor;
    private final ObjectMapper mapper;

    public McpToolCallback(McpServer server, McpTool tool, McpToolExecutor executor, ObjectMapper mapper) {
        this.server = server;
        this.tool = tool;
        this.executor = executor;
        this.mapper = mapper;
    }

    /**
     * LLM 可见的工具名 = {@code {server.code}__{tool.name}}。
     *
     * <p>同一 skill 可能绑两个 server 的同名工具（search / query / get_user 撞名概率很高），
     * 不加命名空间会让 LLM 收到两个同名工具，行为未定义。
     */
    public static String exposedName(McpServer server, McpTool tool) {
        return server.getCode() + "__" + tool.getName();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String schema = (tool.getInputSchema() == null || tool.getInputSchema().isBlank())
                ? EMPTY_SCHEMA
                : tool.getInputSchema();
        return DefaultToolDefinition.builder()
                .name(exposedName(server, tool))
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
            // 审计口径同 HttpToolCallback：记工具名与参数键，不记值，避免泄露
            log.info("调用 MCP 工具 {} 参数键={}", exposedName(server, tool), args.keySet());
            // ⚠️ 发给远端的是原始工具名，前缀只存在于暴露给 LLM 的那一层
            String resp = executor.callTool(server, tool.getName(), args);
            return com.qingyu.care.service.tool.ToolResultDelimiter.wrap(truncate(resp, server.getMaxResponseChars()));
        } catch (Exception e) {
            // fail-soft：抛出会中断整轮对话，同 HttpToolCallback
            log.warn("MCP 工具 {} 调用失败: {}", exposedName(server, tool),
                    com.qingyu.care.service.tool.HttpToolCallback.describeForLog(e));
            return com.qingyu.care.service.tool.ToolResultDelimiter.wrap("工具调用失败: " + e.getMessage());
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    private Map<String, Object> parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(toolInput, new TypeReference<Map<String, Object>>() {});
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

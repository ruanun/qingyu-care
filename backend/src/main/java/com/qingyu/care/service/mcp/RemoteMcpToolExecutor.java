package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于官方 MCP Java SDK 的执行器。每次操作独立建连接、用完即弃——见 MCP 设计文档 §2.4：
 * 用一次握手往返换掉整类连接池生命周期 bug（失效策略、陈旧连接、并发复用、跨租户串号）。
 *
 * <p>{@link McpConnectionFactory#create} 返回的 {@link McpConnection} 同时持有
 * {@code McpSyncClient} 与钉住的 resolver group，{@link McpConnection#close()} 保证两者
 * 都被释放——本类只管拿 {@link McpConnection#client()} 发协议请求，关闭时整体调用
 * {@link McpConnection#close()}，不单独关 client。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemoteMcpToolExecutor implements McpToolExecutor {

    private final McpConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpToolDescriptor> listTools(McpServer server) {
        McpConnection connection = connectionFactory.create(server);
        try {
            List<McpToolDescriptor> out = new ArrayList<>();
            for (McpSchema.Tool t : connection.client().listTools().tools()) {
                out.add(new McpToolDescriptor(
                        t.name(),
                        t.description(),
                        writeSchema(t.inputSchema()),
                        // ⚠️ annotations() 整个可能为 null，readOnlyHint() 也是装箱 Boolean
                        t.annotations() == null ? null : t.annotations().readOnlyHint()));
            }
            return out;
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public String callTool(McpServer server, String toolName, Map<String, Object> args) {
        McpConnection connection = connectionFactory.create(server);
        try {
            McpSchema.CallToolResult result =
                    connection.client().callTool(new McpSchema.CallToolRequest(toolName, args));
            return joinText(result);
        } finally {
            closeQuietly(connection);
        }
    }

    /** SDK 给的 inputSchema 是 Map，库里存 JSON 文本，此处做转换。 */
    private String writeSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(schema);
    }

    /** 只取 TextContent；图片等其它内容类型本期不回灌给 LLM。 */
    private String joinText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) {
            return "";
        }
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    /** 关闭失败不该盖掉业务异常，也不该阻断流程。 */
    private void closeQuietly(McpConnection connection) {
        try {
            connection.close();
        } catch (RuntimeException e) {
            log.warn("关闭 MCP 连接失败: {}", e.getClass().getSimpleName());
        }
    }
}

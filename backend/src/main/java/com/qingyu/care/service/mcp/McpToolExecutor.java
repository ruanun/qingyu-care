package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议操作的抽象边界。存在的意义是让上层（同步服务、ToolCallback）的单测能 mock 掉
 * 真实连接——范式同 HttpToolExecutor / RestClientHttpToolExecutor。
 *
 * @author ruan
 */
public interface McpToolExecutor {

    /** 拉取远端工具清单。每次调用独立建连接、用完即弃。 */
    List<McpToolDescriptor> listTools(McpServer server);

    /**
     * 调用一个远端工具，返回拼接后的文本结果。
     *
     * @param toolName 远端的原始工具名（不带 {@code {code}__} 前缀）
     */
    String callTool(McpServer server, String toolName, Map<String, Object> args);
}

package com.qingyu.care.enums;

/**
 * MCP 工具在本地清单中的状态。
 *
 * @author ruan
 */
public enum McpToolStatus {
    /** 上次同步时远端仍提供该工具 */
    ACTIVE,
    /**
     * 上次同步时远端已不再提供该工具。
     *
     * <p>刻意标记而非删行：工具可能已被 skill 的 mcpToolIds 绑定，删行会让绑定悬空
     * （resolve 查不到、界面也看不到，运营完全无从得知）。
     */
    DEPRECATED
}

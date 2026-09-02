import api from './client';
import type { ApiResponse } from '../types';

/**
 * 远端 MCP server 的连接配置（对应后端 McpServer 实体）。
 *
 * @author ruan
 */
export interface McpServer {
    id: number;
    name: string;
    code: string;
    description?: string | null;
    transport: 'STREAMABLE_HTTP' | 'SSE';
    url: string;
    authHeader?: string | null;
    timeoutMs: number;
    maxResponseChars: number;
    enabled: boolean;
    lastSyncedAt?: string | null;
    lastSyncError?: string | null;
    /** 归属租户，null 表示平台管理员自己建的行。能力包导出只收这种。 */
    tenantId?: number | null;
}

/** 从远端 MCP server 同步下来的单个工具（对应后端 McpTool 实体） */
export interface McpTool {
    id: number;
    mcpServerId: number;
    name: string;
    description?: string | null;
    inputSchema?: string | null;
    status: 'ACTIVE' | 'DEPRECATED';
    /** 租户标注的只读位，唯一的安全判据 */
    readOnly: boolean;
    /** 远端自报的建议值，仅供界面参考，不参与任何安全判定 */
    readOnlyHint?: boolean | null;
}

export const mcpApi = {
    listServers: (): Promise<ApiResponse<McpServer[]>> =>
        api.get('/mcp/servers'),

    createServer: (body: Partial<McpServer>): Promise<ApiResponse<McpServer>> =>
        api.post('/mcp/servers', body),

    updateServer: (id: number, body: Partial<McpServer>): Promise<ApiResponse<McpServer>> =>
        api.put(`/mcp/servers/${id}`, body),

    deleteServer: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/mcp/servers/${id}`),

    // 供删除确认弹窗显示「正被 N 个技能使用」
    usage: (id: number): Promise<ApiResponse<{ skillCount: number }>> =>
        api.get(`/mcp/servers/${id}/usage`),

    syncTools: (id: number): Promise<ApiResponse<McpTool[]>> =>
        api.post(`/mcp/servers/${id}/sync`),

    listTools: (id: number): Promise<ApiResponse<McpTool[]>> =>
        api.get(`/mcp/servers/${id}/tools`),

    markReadOnly: (toolIds: number[], readOnly: boolean): Promise<ApiResponse<number>> =>
        api.put('/mcp/tools/read-only', { toolIds, readOnly }),
};

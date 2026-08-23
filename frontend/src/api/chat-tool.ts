import api from './client';
import type { ApiResponse } from '../types';

export interface AiTool {
    id: number;
    name: string;
    description: string;
    inputSchema: string | null;
    httpMethod: string;
    urlTemplate: string;
    authHeader: string | null;
    timeoutMs: number;
    maxResponseChars: number;
    enabled: boolean;
    scope?: 'PLATFORM' | 'TENANT';
    createdAt: string;
    updatedAt: string;
}

export interface AiSkill {
    id: number;
    name: string;
    description: string | null;
    content: string | null;
    toolIds: number[];
    /** 绑定的 MCP 工具 id 集合（{@code mcp_tool.id}），与 toolIds 并列，见后端 AiSkill 实体 */
    mcpToolIds: number[];
    enabled: boolean;
    createdAt: string;
    updatedAt: string;
}

export const aiToolService = {
    list: (): Promise<ApiResponse<AiTool[]>> => api.get('/ai-tools'),
    getById: (id: number): Promise<ApiResponse<AiTool>> => api.get(`/ai-tools/${id}`),
    create: (data: Partial<AiTool>): Promise<ApiResponse<AiTool>> => api.post('/ai-tools', data),
    update: (id: number, data: Partial<AiTool>): Promise<ApiResponse<AiTool>> => api.put(`/ai-tools/${id}`, data),
    remove: (id: number): Promise<ApiResponse<void>> => api.delete(`/ai-tools/${id}`),
};

export const aiSkillService = {
    list: (): Promise<ApiResponse<AiSkill[]>> => api.get('/ai-skills'),
    getById: (id: number): Promise<ApiResponse<AiSkill>> => api.get(`/ai-skills/${id}`),
    create: (data: Partial<AiSkill>): Promise<ApiResponse<AiSkill>> => api.post('/ai-skills', data),
    update: (id: number, data: Partial<AiSkill>): Promise<ApiResponse<AiSkill>> => api.put(`/ai-skills/${id}`, data),
    remove: (id: number): Promise<ApiResponse<void>> => api.delete(`/ai-skills/${id}`),
};

export interface OpenApiToolCandidate {
    name: string;
    description: string;
    httpMethod: string;
    urlTemplate: string;
    inputSchema: string;
    warnings: string[];
    nameConflict: boolean;
}

export interface OpenApiParseResponse {
    candidates: OpenApiToolCandidate[];
    /** 「压根没能成为候选」的说明：被跳过的方法、非对象请求体、超上限截断 */
    globalWarnings: string[];
    truncated: boolean;
}

export const openApiImportService = {
    parse: (data: { schemaText?: string; schemaUrl?: string; baseUrl?: string }):
        Promise<ApiResponse<OpenApiParseResponse>> => api.post('/ai-tools/openapi/parse', data),
    // ⚠️ 不带 timeoutMs/maxResponseChars：导入面板从未提供对应输入框，后端 DTO 也已删掉
    // 这两个死字段，统一用服务端默认值（见 OpenApiImportService.DEFAULT_TIMEOUT_MS 等）。
    import: (data: {
        candidates: OpenApiToolCandidate[];
        authHeader?: string;
        enabled?: boolean;
    }): Promise<ApiResponse<AiTool[]>> => api.post('/ai-tools/openapi/import', data),
};

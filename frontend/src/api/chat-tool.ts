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

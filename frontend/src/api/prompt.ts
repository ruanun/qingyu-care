import api from './client';
import type { ApiResponse } from '../types';

export interface PromptTemplate {
    id: number;
    name: string;
    content: string;
    version: number;
    isActive: boolean;
    description: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface CreatePromptRequest {
    name: string;
    content: string;
    description?: string;
}

export const promptService = {
    // 获取所有版本
    getAllVersions: (): Promise<ApiResponse<PromptTemplate[]>> =>
        api.get('/prompts'),

    // 获取激活版本
    getActiveVersion: (): Promise<ApiResponse<PromptTemplate>> =>
        api.get('/prompts/active'),

    // 获取指定版本
    getById: (id: number): Promise<ApiResponse<PromptTemplate>> =>
        api.get(`/prompts/${id}`),

    // 创建新版本
    createVersion: (data: CreatePromptRequest): Promise<ApiResponse<PromptTemplate>> =>
        api.post('/prompts', data),

    // 激活指定版本
    activateVersion: (id: number): Promise<ApiResponse<PromptTemplate>> =>
        api.put(`/prompts/${id}/activate`),

    // 删除版本
    deleteVersion: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/prompts/${id}`),
};

import api from './client';
import type { ApiResponse } from '../types';

export interface AiModelConfig {
    id: number;
    name: string;
    provider: string;
    modelId: string;
    endpoint: string | null;
    apiKey: string | null;
    scenario: string;
    priority: number;
    enabled: boolean;
    remark: string | null;
    createdAt: string;
    updatedAt: string;
}

export const aiModelService = {
    // 获取所有模型配置
    getAllModels: (): Promise<ApiResponse<AiModelConfig[]>> =>
        api.get('/ai-models'),

    // 获取指定模型配置
    getById: (id: number): Promise<ApiResponse<AiModelConfig>> =>
        api.get(`/ai-models/${id}`),

    // 创建模型配置
    createModel: (data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
        api.post('/ai-models', data),

    // 更新模型配置
    updateModel: (id: number, data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
        api.put(`/ai-models/${id}`, data),

    // 删除模型配置
    deleteModel: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/ai-models/${id}`),

    // 测试连接
    testModel: (id: number): Promise<ApiResponse<string>> =>
        api.post(`/ai-models/${id}/test`),
};

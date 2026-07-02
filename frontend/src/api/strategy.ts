import api from './client';
import type { ApiResponse } from '../types';

export interface ChatStrategy {
    id: number;
    name: string;
    scenario: string;
    topK: number;
    similarityThreshold: number;
    allowNoKnowledge: boolean;
    fallbackMessage: string;
    promptTemplateId: number | null;
    enabled: boolean;
    isDefault: boolean;
    createdAt: string;
    updatedAt: string;
}

export const chatStrategyService = {
    // 获取所有策略
    getAll: (): Promise<ApiResponse<ChatStrategy[]>> =>
        api.get('/strategies'),

    // 获取指定策略
    getById: (id: number): Promise<ApiResponse<ChatStrategy>> =>
        api.get(`/strategies/${id}`),

    // 创建策略
    create: (data: Partial<ChatStrategy>): Promise<ApiResponse<ChatStrategy>> =>
        api.post('/strategies', data),

    // 更新策略
    update: (id: number, data: Partial<ChatStrategy>): Promise<ApiResponse<ChatStrategy>> =>
        api.put(`/strategies/${id}`, data),

    // 删除策略
    delete: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/strategies/${id}`),
};

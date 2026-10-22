import api from './client';
import type { ApiResponse } from '../types';

export type HandoverRuleType = 'KEYWORD' | 'LOW_CONFIDENCE' | 'NO_HIT_STREAK';

export interface HandoverRule {
    id: number;
    ruleType: HandoverRuleType;
    name: string;
    description: string | null;
    enabled: boolean;
    config: string;  // JSON 字符串
    priority: number;
    createdAt: string;
    updatedAt: string;
}

export interface UpdateRuleRequest {
    enabled?: boolean;
    config?: string;
}

export const handoverService = {
    // 获取所有规则
    getAllRules: (): Promise<ApiResponse<HandoverRule[]>> =>
        api.get('/handover-rules'),

    // 获取指定规则
    getById: (id: number): Promise<ApiResponse<HandoverRule>> =>
        api.get(`/handover-rules/${id}`),

    // 更新规则
    updateRule: (id: number, data: UpdateRuleRequest): Promise<ApiResponse<HandoverRule>> =>
        api.put(`/handover-rules/${id}`, data),
};

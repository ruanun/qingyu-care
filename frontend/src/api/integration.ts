import api from './client';
import type { ApiResponse, Application } from '../types';

export const integrationService = {
    // 获取应用详情（含 appKey/appSecret/限流）
    getApp: (id: number): Promise<ApiResponse<Application>> =>
        api.get(`/applications/${id}`),

    // 重置密钥
    regenerateSecret: (id: number): Promise<ApiResponse<Application>> =>
        api.post(`/applications/${id}/regenerate-secret`),

    // 更新限流额度（透传应用更新接口）
    updateRateLimit: (id: number, rateLimitPerMinute: number | null): Promise<ApiResponse<Application>> =>
        api.put(`/applications/${id}`, { rateLimitPerMinute }),
};

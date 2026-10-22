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

    // 更新 Widget 允许来源域名（透传应用更新接口，逗号分隔）
    updateAllowedOrigins: (id: number, allowedOrigins: string): Promise<ApiResponse<Application>> =>
        api.put(`/applications/${id}`, { allowedOrigins }),

    // 更新卡片图片域名白名单（换行、逗号、空格分隔均可，后端归一化）
    updateImageDomains: (id: number, imageAllowedDomains: string): Promise<ApiResponse<Application>> =>
        api.put(`/applications/${id}`, { imageAllowedDomains }),

    // 更新 Webhook 配置（回调 URL 与订阅事件，逗号分隔）
    updateWebhook: (id: number, payload: { webhookUrl: string; webhookEvents: string }): Promise<ApiResponse<Application>> =>
        api.put(`/applications/${id}`, payload),

    // 重置 Webhook 签名密钥
    regenerateWebhookSecret: (id: number): Promise<ApiResponse<Application>> =>
        api.post(`/applications/${id}/regenerate-webhook-secret`),

    // 发送测试事件
    sendWebhookTest: (id: number): Promise<ApiResponse<{ delivered: boolean; responseStatus: number | null; error: string | null }>> =>
        api.post(`/applications/${id}/webhook/test`),
};

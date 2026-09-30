import api from './client';
import type { ApiResponse } from '../types';
import type { AiModelConfig } from './ai-model';
import type { AiTool } from './chat-tool';

export interface PlatformOverview {
  health: string;
  tenantCount: number;
  applicationCount: number;
  userCount: number;
  sessionCount: number;
  todaySessionCount: number;
}

export interface TurnstileTestResult {
  success: boolean;
  errorCodes: string[];
  /** 令牌签发时所在页面的主机名，通过时才有 */
  hostname: string | null;
}

export const platformService = {
  // 平台配置
  getPlatformConfig: (): Promise<ApiResponse<Record<string, string>>> =>
    api.get('/platform/config'),
  updatePlatformConfig: (values: Record<string, string>): Promise<ApiResponse<void>> =>
    api.put('/platform/config', values),
  // 用表单当前的 secret（留空用已保存的）向 Cloudflare 校验一枚真实 Turnstile 令牌
  testTurnstile: (data: { token: string; secretKey?: string }): Promise<ApiResponse<TurnstileTestResult>> =>
    api.post('/platform/config/turnstile/test', data),

  // 共享模型池
  listSharedModels: (): Promise<ApiResponse<AiModelConfig[]>> =>
    api.get('/platform/models'),
  createSharedModel: (data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
    api.post('/platform/models', data),
  updateSharedModel: (id: number, data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
    api.put(`/platform/models/${id}`, data),
  deleteSharedModel: (id: number): Promise<ApiResponse<void>> =>
    api.delete(`/platform/models/${id}`),

  // 共享工具（路径不带 /v1——axios 实例的 baseURL 已经是 '/api/v1'，
  // 见 api/client.ts；写成 '/v1/platform/tools' 会拼成 /api/v1/v1/...）
  listSharedTools: (): Promise<ApiResponse<AiTool[]>> =>
    api.get('/platform/tools'),
  createSharedTool: (data: Partial<AiTool>): Promise<ApiResponse<AiTool>> =>
    api.post('/platform/tools', data),
  updateSharedTool: (id: number, data: Partial<AiTool>): Promise<ApiResponse<AiTool>> =>
    api.put(`/platform/tools/${id}`, data),
  deleteSharedTool: (id: number): Promise<ApiResponse<void>> =>
    api.delete(`/platform/tools/${id}`),

  // 系统概览
  getOverview: (): Promise<ApiResponse<PlatformOverview>> =>
    api.get('/platform/overview'),

  // 系统公告（任意登录用户可读，空串表示无公告）
  getAnnouncement: (): Promise<ApiResponse<string>> =>
    api.get('/platform/config/announcement'),
};

export const tenantConfigService = {
  // 租户配置
  getTenantConfig: (): Promise<ApiResponse<Record<string, string>>> =>
    api.get('/tenant/config'),
  updateTenantConfig: (values: Record<string, string>): Promise<ApiResponse<void>> =>
    api.put('/tenant/config', values),
};

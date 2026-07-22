import api from './client';
import type { ApiResponse } from '../types';
import type { AiModelConfig } from './ai-model';

export interface PlatformOverview {
  health: string;
  tenantCount: number;
  applicationCount: number;
  userCount: number;
  sessionCount: number;
  todaySessionCount: number;
}

export const platformService = {
  // 平台配置
  getPlatformConfig: (): Promise<ApiResponse<Record<string, string>>> =>
    api.get('/platform/config'),
  updatePlatformConfig: (values: Record<string, string>): Promise<ApiResponse<void>> =>
    api.put('/platform/config', values),

  // 共享模型池
  listSharedModels: (): Promise<ApiResponse<AiModelConfig[]>> =>
    api.get('/platform/models'),
  createSharedModel: (data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
    api.post('/platform/models', data),
  updateSharedModel: (id: number, data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
    api.put(`/platform/models/${id}`, data),
  deleteSharedModel: (id: number): Promise<ApiResponse<void>> =>
    api.delete(`/platform/models/${id}`),

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

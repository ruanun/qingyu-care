import api from './client';
import type { ApiResponse, Application } from '../types';

export const applicationService = {
    // 获取列表
    list: (): Promise<ApiResponse<Application[]>> =>
        api.get('/applications'),

    // 获取详情
    getById: (id: number): Promise<ApiResponse<Application>> =>
        api.get(`/applications/${id}`),

    // 创建
    create: (data: Partial<Application>): Promise<ApiResponse<Application>> =>
        api.post('/applications', data),

    // 更新
    update: (id: number, data: Partial<Application>): Promise<ApiResponse<Application>> =>
        api.put(`/applications/${id}`, data),

    // 删除
    delete: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/applications/${id}`),

    // 重新生成密钥
    regenerateSecret: (id: number): Promise<ApiResponse<Application>> =>
        api.post(`/applications/${id}/regenerate-secret`),
};

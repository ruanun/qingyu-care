import api from './client';
import type { ApiResponse, Tenant } from '../types';

export const tenantService = {
    // 获取租户列表
    list: (): Promise<ApiResponse<Tenant[]>> =>
        api.get('/tenants'),

    // 获取单个租户
    getById: (id: number): Promise<ApiResponse<Tenant>> =>
        api.get(`/tenants/${id}`),

    // 创建租户
    create: (data: Partial<Tenant>): Promise<ApiResponse<Tenant>> =>
        api.post('/tenants', data),

    // 更新租户
    update: (id: number, data: Partial<Tenant>): Promise<ApiResponse<Tenant>> =>
        api.put(`/tenants/${id}`, data),

    // 获取当前租户信息
    getCurrent: (): Promise<ApiResponse<Tenant>> =>
        api.get('/tenants/current'),
};

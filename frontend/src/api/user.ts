import api from './client';
import type { ApiResponse, User } from '../types';

export const userService = {
    // 获取列表
    list: (): Promise<ApiResponse<User[]>> =>
        api.get('/users'),

    // 获取详情
    getById: (id: number): Promise<ApiResponse<User>> =>
        api.get(`/users/${id}`),

    // 创建/邀请（平台管理员可通过 tenantId 指定所属租户）
    create: (data: Partial<User> & { password?: string; tenantId?: number }): Promise<ApiResponse<User>> =>
        api.post('/users', data),

    // 更新
    update: (id: number, data: Partial<User> & { password?: string }): Promise<ApiResponse<User>> =>
        api.put(`/users/${id}`, data),

    // 删除
    delete: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/users/${id}`),
};

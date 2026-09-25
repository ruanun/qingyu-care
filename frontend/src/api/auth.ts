import api from './client';
import type { ApiResponse } from '../types';

export interface LoginRequest {
    username: string;
    password: string;
}

export interface RegisterRequest {
    username: string;
    password: string;
    nickname?: string;
    email?: string;
    phone?: string;
    tenantCode?: string;
    tenantName?: string;
}

export interface LoginResponse {
    token: string;
    userId: number;
    username: string;
    nickname: string;
    avatar: string;
    role: 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'TENANT_MEMBER';
    tenantId: number | null;
    tenantName: string | null;
}

export const authService = {
    // 登录
    login: (data: LoginRequest): Promise<ApiResponse<LoginResponse>> =>
        api.post('/auth/login', data),

    // 登录页公开配置（免登录）：是否开放自助注册
    getPublicConfig: (): Promise<ApiResponse<{ selfRegisterEnabled: boolean }>> =>
        api.get('/auth/config'),

    // 注册
    register: (data: RegisterRequest): Promise<ApiResponse<LoginResponse>> =>
        api.post('/auth/register', data),

    // 退出登录
    logout: (): Promise<ApiResponse<void>> =>
        api.post('/auth/logout'),

    // 获取当前用户信息
    getCurrentUser: (): Promise<ApiResponse<LoginResponse>> =>
        api.get('/auth/me'),
};

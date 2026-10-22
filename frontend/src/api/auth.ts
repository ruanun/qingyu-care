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
    captchaId?: string;
    captchaCode?: string;
    turnstileToken?: string;
}

export type CaptchaMode = 'NONE' | 'IMAGE' | 'TURNSTILE';

export interface PublicAuthConfig {
    selfRegisterEnabled: boolean;
    captchaMode: CaptchaMode;
    /** 仅 TURNSTILE 模式非空；site key 本就是下发给浏览器的公开值 */
    turnstileSiteKey: string;
}

export interface ImageCaptcha {
    captchaId: string;
    /** data:image/png;base64,... */
    image: string;
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

    // 登录页公开配置（免登录）：是否开放自助注册、注册用哪种人机验证
    getPublicConfig: (): Promise<ApiResponse<PublicAuthConfig>> =>
        api.get('/auth/config'),

    // 注册用图形验证码（免登录，一次性）
    getCaptcha: (): Promise<ApiResponse<ImageCaptcha>> =>
        api.get('/auth/captcha'),

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

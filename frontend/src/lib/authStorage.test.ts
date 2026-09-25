import { beforeEach, describe, expect, it } from 'vitest';
import { authStateStorage, isRememberMe, readAuthToken, setRememberMe, AUTH_KEY } from './authStorage';
import { useAuthStore } from '@/stores/authStore';
import api from '@/api/client';
import { authHeaders } from '@/lib/sse';
import type { InternalAxiosRequestConfig } from 'axios';
import type { LoginResponse } from '@/api/auth';

const user: LoginResponse = { token: 'tok-1', userId: 1, username: 'a', nickname: 'a', avatar: '', role: 'TENANT_ADMIN', tenantId: 1, tenantName: 't' };

beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
});

describe('authStorage', () => {
    it('从未选择过时默认记住（不改变老用户体验）', () => {
        expect(isRememberMe()).toBe(true);
    });

    it('记住我 → 写 localStorage', () => {
        setRememberMe(true);
        authStateStorage.setItem(AUTH_KEY, '{"state":{"token":"t"}}');
        expect(localStorage.getItem(AUTH_KEY)).not.toBeNull();
        expect(sessionStorage.getItem(AUTH_KEY)).toBeNull();
    });

    /**
     * 从「记住」切到「不记住」时，localStorage 里上一次的 token 必须被清掉——
     * 否则关掉浏览器仍然免登录，这个勾选就白勾了。
     */
    it('不记住 → 写 sessionStorage，并清掉 localStorage 里的旧值', () => {
        localStorage.setItem(AUTH_KEY, '{"state":{"token":"old"}}');
        setRememberMe(false);
        authStateStorage.setItem(AUTH_KEY, '{"state":{"token":"new"}}');
        expect(sessionStorage.getItem(AUTH_KEY)).toContain('new');
        expect(localStorage.getItem(AUTH_KEY)).toBeNull();
    });
});

/**
 * 钉住 store ↔ 请求拦截器这条接缝：api/client.ts 与 lib/sse.ts 曾直接读 localStorage，
 * 只改 store 的话，不勾「记住我」时 token 在 sessionStorage 里，请求拦截器读不到 → 全部 401。
 */
describe('authStore 经 authStorage 持久化', () => {
    it('不记住时 setAuth 的 token 落在 sessionStorage，且拦截器用的 readAuthToken 读得到', () => {
        setRememberMe(false);
        useAuthStore.getState().setAuth(user);

        expect(sessionStorage.getItem(AUTH_KEY)).toContain('tok-1');
        expect(localStorage.getItem(AUTH_KEY)).toBeNull();
        expect(readAuthToken()).toBe('tok-1');
    });

    /**
     * 直接驱动两个真实消费方：只测 readAuthToken 守不住——把 client.ts / sse.ts 改回直接读
     * localStorage，上面几条照样绿，而线上「不记住我」登录后立刻被 401 踢回登录页。
     */
    it('不记住时请求拦截器与 SSE 鉴权头都带上 token', async () => {
        setRememberMe(false);
        useAuthStore.getState().setAuth(user);

        let sent: string | undefined;
        await api.get('/probe', {
            adapter: async (config: InternalAxiosRequestConfig) => {
                sent = config.headers['satoken'] as string | undefined;
                return { data: { code: 200 }, status: 200, statusText: 'OK', headers: {}, config };
            },
        });

        expect(sent).toBe('tok-1');
        expect(authHeaders()['satoken']).toBe('tok-1');
    });

    it('记住时 token 落在 localStorage', () => {
        setRememberMe(true);
        useAuthStore.getState().setAuth(user);

        expect(localStorage.getItem(AUTH_KEY)).toContain('tok-1');
        expect(readAuthToken()).toBe('tok-1');
    });
});

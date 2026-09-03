import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/authStore';
import { useAppStore } from '@/stores/appStore';

/**
 * `lib/session.performLogout` 的单元测试。
 *
 * 安全审查 M7（2026-09-03）：此前「退出登录」只清 localStorage，服务端 token 继续有效 30 天
 * （Sa-Token `timeout: 2592000`），公共电脑上退出等于没退。`api/auth.ts` 里的 logout 接口
 * 早就封装好了却无人调用。另外当前应用（`qingyu-current-app`）也要一起清：残留会让下一个
 * 登录者带着上一个租户的 X-App-Id 发请求。
 */
const { logoutMock } = vi.hoisted(() => ({ logoutMock: vi.fn() }));
vi.mock('@/api/auth', () => ({ authService: { logout: logoutMock } }));

import { performLogout } from './session';

describe('performLogout', () => {
    beforeEach(() => {
        logoutMock.mockReset();
        useAuthStore.setState({
            token: 'tok', isAuthenticated: true,
            user: { token: 'tok', userId: 1, username: 'a', nickname: 'a', avatar: '', role: 'TENANT_ADMIN', tenantId: 1, tenantName: 't' },
        });
        useAppStore.setState({ currentApp: { id: 7, name: '云舟商城客服' } });
    });

    it('先调后端注销接口，再清本地登录态与当前应用', async () => {
        logoutMock.mockResolvedValue({ code: 200, message: 'ok', data: undefined });

        await performLogout();

        expect(logoutMock).toHaveBeenCalledTimes(1);
        expect(useAuthStore.getState().token).toBeNull();
        expect(useAuthStore.getState().isAuthenticated).toBe(false);
        expect(useAppStore.getState().currentApp).toBeNull();
    });

    it('后端注销失败（如网络断开）也照样清本地状态', async () => {
        logoutMock.mockRejectedValue(new Error('网络错误'));

        await expect(performLogout()).resolves.toBeUndefined();

        expect(useAuthStore.getState().token).toBeNull();
        expect(useAppStore.getState().currentApp).toBeNull();
    });
});

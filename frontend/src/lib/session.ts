import { authService } from '@/api/auth';
import { useAuthStore } from '@/stores/authStore';
import { useAppStore } from '@/stores/appStore';

/**
 * 退出登录：先让服务端作废 token，再清本地登录态与当前应用。
 *
 * 只清 localStorage 是不够的——Sa-Token 的 token 在服务端仍然有效（默认 30 天），
 * 公共电脑上「退出」等于没退（安全审查 M7，2026-09-03）。后端注销失败（网络断开、
 * token 已过期）不应阻止本地清理，否则用户会被卡在一个退不出去的页面上。
 *
 * 当前应用一并清掉：`qingyu-current-app` 残留会让下一个登录者带着上一个租户的
 * X-App-Id 发请求。
 */
export async function performLogout(): Promise<void> {
    try {
        await authService.logout();
    } catch {
        // 服务端注销失败不影响本地清理
    }
    useAuthStore.getState().logout();
    useAppStore.getState().clearCurrentApp();
}

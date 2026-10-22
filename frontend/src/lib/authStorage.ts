import type { StateStorage } from 'zustand/middleware';

/**
 * 登录态存哪儿：勾了「记住我」→ localStorage（关浏览器仍在）；没勾 → sessionStorage（关标签页即失效）。
 *
 * ⚠️ 登录态**只许经本模块读写**。`api/client.ts` 的请求拦截器与 `lib/sse.ts` 曾直接
 * `localStorage.getItem('qingyu-auth')`——绕过 store 自己读。只改 store 的话，不勾「记住我」时 token
 * 落在 sessionStorage，这两处读不到，所有请求一律 401 跳回登录页，看上去像「登录成功又被踢出」。
 *
 * @author ruan
 */

export const AUTH_KEY = 'qingyu-auth';
/** 上次登录时「记住我」的选择；登录页据此回显勾选状态 */
const REMEMBER_KEY = 'qingyu-remember';

function safe<T>(fn: () => T, fallback: T): T {
    try {
        return fn();
    } catch {
        return fallback; // 隐私模式下 storage 可能整体不可用
    }
}

/** 默认 true：勾选框引入之前登录态一直持久化，不改变老用户的既有体验 */
export function isRememberMe(): boolean {
    return safe(() => localStorage.getItem(REMEMBER_KEY) !== '0', true);
}

/** 登录请求发出前调用，决定随后 setAuth 写入哪个 storage */
export function setRememberMe(remember: boolean): void {
    safe(() => localStorage.setItem(REMEMBER_KEY, remember ? '1' : '0'), undefined);
}

/** 读原始持久化串：两处都查，兼容切换选择前写下的旧值 */
export function readAuthRaw(): string | null {
    return safe(() => sessionStorage.getItem(AUTH_KEY) ?? localStorage.getItem(AUTH_KEY), null);
}

export function readAuthToken(): string | null {
    const raw = readAuthRaw();
    if (!raw) return null;
    return safe(() => JSON.parse(raw)?.state?.token ?? null, null);
}

export function clearAuth(): void {
    safe(() => {
        sessionStorage.removeItem(AUTH_KEY);
        localStorage.removeItem(AUTH_KEY);
    }, undefined);
}

/**
 * 给 zustand persist 用的存储。写入时按当前选择落到一处并清掉另一处——否则从「记住」切到「不记住」后，
 * localStorage 里的旧 token 还在，关掉浏览器照样免登录，这个勾选就白勾了。
 */
export const authStateStorage: StateStorage = {
    getItem: () => readAuthRaw(),
    setItem: (name, value) => {
        safe(() => {
            const [target, other] = isRememberMe() ? [localStorage, sessionStorage] : [sessionStorage, localStorage];
            target.setItem(name, value);
            other.removeItem(name);
        }, undefined);
    },
    removeItem: () => clearAuth(),
};

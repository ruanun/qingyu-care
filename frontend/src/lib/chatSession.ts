// frontend/src/lib/chatSession.ts
// 管理端 ChatPage 会话持久化：镜像 widget/src/session.ts 的轻量实现，
// 用 localStorage 保存 sessionId（带 24h TTL），支持刷新后恢复会话历史。
// @author ruan

/** 会话 TTL：24 小时，过期视为失效会话。 */
export const CHAT_SESSION_TTL_MS = 24 * 60 * 60 * 1000;

const keyOf = (userId: string) => `qingyu_admin_chat_session_${userId}`;

interface StoredChatSession {
    sessionId: string;
    createdAt: number;
}

/**
 * 读取未过期的 sessionId；过期/损坏/localStorage 不可用时返回 null 并清理。
 * 全程 fail-soft，不抛出异常。
 */
export function loadChatSession(userId: string): string | null {
    try {
        const raw = localStorage.getItem(keyOf(userId));
        if (!raw) return null;
        const data = JSON.parse(raw) as Partial<StoredChatSession>;
        if (
            !data ||
            typeof data.sessionId !== 'string' ||
            typeof data.createdAt !== 'number' ||
            Date.now() - data.createdAt > CHAT_SESSION_TTL_MS
        ) {
            clearChatSession(userId);
            return null;
        }
        return data.sessionId;
    } catch {
        return null; // localStorage 被禁用或数据损坏，降级为无会话
    }
}

/** 持久化当前 sessionId，供刷新后恢复。写失败静默忽略，不影响对话进行。 */
export function saveChatSession(userId: string, sessionId: string): void {
    try {
        const data: StoredChatSession = { sessionId, createdAt: Date.now() };
        localStorage.setItem(keyOf(userId), JSON.stringify(data));
    } catch {
        // 忽略写失败
    }
}

/** 清理指定用户的会话记录（历史加载失败/会话过期时调用）。 */
export function clearChatSession(userId: string): void {
    try {
        localStorage.removeItem(keyOf(userId));
    } catch {
        // 忽略
    }
}

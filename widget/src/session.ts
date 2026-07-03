export const SESSION_TTL_MS = 24 * 60 * 60 * 1000; // 24h

const keyOf = (appKey: string) => `qingyu_widget_session_${appKey}`;

interface Stored {
  sessionId: string;
  createdAt: number;
}

/** 读取未过期的 sessionId；过期/损坏/不可用时返回 null 并清理。 */
export function loadSession(appKey: string): string | null {
  try {
    const raw = localStorage.getItem(keyOf(appKey));
    if (!raw) return null;
    const data = JSON.parse(raw) as Partial<Stored>;
    if (
      !data ||
      typeof data.sessionId !== 'string' ||
      typeof data.createdAt !== 'number' ||
      Date.now() - data.createdAt > SESSION_TTL_MS
    ) {
      clearSession(appKey);
      return null;
    }
    return data.sessionId;
  } catch {
    return null; // localStorage 被禁用（隐私模式）或数据损坏，降级为无会话
  }
}

export function saveSession(appKey: string, sessionId: string): void {
  try {
    const data: Stored = { sessionId, createdAt: Date.now() };
    localStorage.setItem(keyOf(appKey), JSON.stringify(data));
  } catch {
    // 忽略写失败，不影响会话进行
  }
}

export function clearSession(appKey: string): void {
  try {
    localStorage.removeItem(keyOf(appKey));
  } catch {
    // 忽略
  }
}

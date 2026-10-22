import type { WidgetStorage } from './platform';

export const SESSION_TTL_MS = 24 * 60 * 60 * 1000; // 24h

const keyOf = (appKey: string) => `qingyu_widget_session_${appKey}`;

interface Stored {
  sessionId: string;
  createdAt: number;
}

/**
 * 读取未过期的 sessionId；过期/损坏/storage 不可用时返回 null 并清理。
 *
 * ⚠️ catch 里的降级有来历：浏览器隐私模式下 localStorage 读写会直接抛异常，
 * 那时应降级为「无会话」让对话照常开始，而不是让整个 widget 崩掉。
 */
export function loadSession(storage: WidgetStorage, appKey: string): string | null {
  try {
    const raw = storage.get(keyOf(appKey));
    if (!raw) return null;
    const data = JSON.parse(raw) as Partial<Stored>;
    if (
      !data ||
      typeof data.sessionId !== 'string' ||
      typeof data.createdAt !== 'number' ||
      Date.now() - data.createdAt > SESSION_TTL_MS
    ) {
      clearSession(storage, appKey);
      return null;
    }
    return data.sessionId;
  } catch {
    return null;
  }
}

export function saveSession(storage: WidgetStorage, appKey: string, sessionId: string): void {
  try {
    const data: Stored = { sessionId, createdAt: Date.now() };
    storage.set(keyOf(appKey), JSON.stringify(data));
  } catch {
    // 忽略写失败，不影响会话进行
  }
}

export function clearSession(storage: WidgetStorage, appKey: string): void {
  try {
    storage.remove(keyOf(appKey));
  } catch {
    // 忽略
  }
}

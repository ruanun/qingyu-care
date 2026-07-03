import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { loadSession, saveSession, clearSession, SESSION_TTL_MS } from './session';

const APP = 'app_123';
const KEY = `qingyu_widget_session_${APP}`;

beforeEach(() => localStorage.clear());
afterEach(() => vi.useRealTimers());

describe('session', () => {
  it('保存后可读回 sessionId', () => {
    saveSession(APP, 's-1');
    expect(loadSession(APP)).toBe('s-1');
  });

  it('超过 24h 视为过期并清除', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-03T00:00:00Z'));
    saveSession(APP, 's-1');
    vi.setSystemTime(new Date(Date.now() + SESSION_TTL_MS + 1000));
    expect(loadSession(APP)).toBeNull();
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('损坏数据按无会话降级且不抛错', () => {
    localStorage.setItem(KEY, '{不是json');
    expect(loadSession(APP)).toBeNull();
  });

  it('clearSession 移除会话', () => {
    saveSession(APP, 's-1');
    clearSession(APP);
    expect(loadSession(APP)).toBeNull();
  });
});

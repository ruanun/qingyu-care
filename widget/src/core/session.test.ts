// @vitest-environment node
import { beforeEach, describe, expect, it } from 'vitest';
import type { WidgetStorage } from './platform';
import { SESSION_TTL_MS, clearSession, loadSession, saveSession } from './session';

/**
 * 会话持久化。改造重点：从直接调 localStorage 变成接受注入的 storage。
 *
 * ⚠️ 本文件跑在 node 环境下（首行注释），所以 localStorage 根本不存在——
 * 若实现里还残留对它的引用，这里会直接炸，这正是我们要的。
 */

/** 内存实现，兼作「storage 抛异常」场景的注入点。 */
class MemoryStorage implements WidgetStorage {
  private data = new Map<string, string>();
  throwOnGet = false;
  throwOnSet = false;

  get(key: string): string | null {
    if (this.throwOnGet) throw new Error('storage 不可用');
    return this.data.get(key) ?? null;
  }
  set(key: string, value: string): void {
    if (this.throwOnSet) throw new Error('storage 不可用');
    this.data.set(key, value);
  }
  remove(key: string): void {
    this.data.delete(key);
  }
}

let storage: MemoryStorage;

beforeEach(() => {
  storage = new MemoryStorage();
});

describe('会话持久化', () => {
  it('存进去能读回来', () => {
    saveSession(storage, 'app1', 'sess-1');
    expect(loadSession(storage, 'app1')).toBe('sess-1');
  });

  it('不同 appKey 互不干扰', () => {
    saveSession(storage, 'app1', 'sess-1');
    saveSession(storage, 'app2', 'sess-2');
    expect(loadSession(storage, 'app1')).toBe('sess-1');
    expect(loadSession(storage, 'app2')).toBe('sess-2');
  });

  it('没存过时返回 null', () => {
    expect(loadSession(storage, 'app1')).toBeNull();
  });

  it('超过 TTL 的会话被判为失效并清理', () => {
    const stale = JSON.stringify({ sessionId: 'old', createdAt: Date.now() - SESSION_TTL_MS - 1 });
    storage.set('qingyu_widget_session_app1', stale);

    expect(loadSession(storage, 'app1')).toBeNull();
    expect(storage.get('qingyu_widget_session_app1')).toBeNull();
  });

  it('数据损坏时降级为无会话而不是抛错', () => {
    storage.set('qingyu_widget_session_app1', '这不是 JSON');
    expect(loadSession(storage, 'app1')).toBeNull();
  });

  it('结构不完整时降级为无会话', () => {
    storage.set('qingyu_widget_session_app1', JSON.stringify({ sessionId: 123 }));
    expect(loadSession(storage, 'app1')).toBeNull();
  });

  /**
   * ⚠️ 这条守的是一条有来历的降级：localStorage 在隐私模式下会被禁用、读写直接抛异常。
   * 原实现对此降级为「无会话」而不是让整个 widget 崩掉。搬家时最容易顺手"简化"掉。
   */
  it('storage 读抛异常时降级为无会话', () => {
    storage.throwOnGet = true;
    expect(loadSession(storage, 'app1')).toBeNull();
  });

  it('storage 写抛异常时不影响会话继续', () => {
    storage.throwOnSet = true;
    expect(() => saveSession(storage, 'app1', 'sess-1')).not.toThrow();
  });

  it('清理后读不到', () => {
    saveSession(storage, 'app1', 'sess-1');
    clearSession(storage, 'app1');
    expect(loadSession(storage, 'app1')).toBeNull();
  });
});

/**
 * 「一次咨询一个会话」（2026-09-29）：坐席结束会话时 inbox 推 session/ended，widget 当场丢掉该会话、关掉 inbox，
 * 下一条消息直接新开，不必先撞一次 404。单独成文件的理由同 main.closedSessionRestore.test.ts。
 *
 * @author ruan
 */
import { describe, it, expect, vi } from 'vitest';
import type { ChatStreamHandlers } from './core/chatStream';

const mocks = vi.hoisted(() => ({
  ui: {
    addUserMessage: vi.fn(() => ({ setMessageId: vi.fn() })),
    addBotMessage: vi.fn(),
    addAgentMessage: vi.fn(),
    showSessionState: vi.fn(),
    setTyping: vi.fn(),
    setInputEnabled: vi.fn(),
    setAttachEnabled: vi.fn(),
    markOpened: vi.fn(),
    beginBotMessage: vi.fn(() => ({ appendText: vi.fn(), appendCard: vi.fn(), finish: vi.fn() })),
    markReadUpTo: vi.fn(),
  },
  api: {
    getConfig: vi.fn(async () => ({ appName: '轻语', welcomeMessage: '', avatar: '', themeColor: '#000' })),
    getSession: vi.fn(async () => null),
    inboxUrl: vi.fn((sid: string) => `/open/v1/sessions/${sid}/inbox`),
    markRead: vi.fn(async () => undefined),
    stream: vi.fn(),
  },
  inboxClose: vi.fn(),
  clearSession: vi.fn(),
  sse: { onEvent: null as null | ((event: string, data: string) => void) },
  uiHandlers: null as null | { onSend?: (message: string) => void },
}));

vi.mock('./config', () => ({ resolveConfig: () => ({ appKey: 'k1', apiBase: 'http://host/api' }) }));
vi.mock('./api', () => ({ createApi: () => mocks.api, SessionExpiredError: class SessionExpiredError extends Error {} }));
vi.mock('./ui', () => ({
  mountUi: (_b: unknown, handlers: { onSend?: (m: string) => void }) => {
    mocks.uiHandlers = handlers;
    return mocks.ui;
  },
}));
vi.mock('./core/session', () => ({ loadSession: () => 'sess-1', saveSession: vi.fn(), clearSession: mocks.clearSession }));
vi.mock('./core/sse', () => ({
  connectSse: (_u: string, _k: string, onEvent: (event: string, data: string) => void) => {
    mocks.sse.onEvent = onEvent;
    return { close: mocks.inboxClose };
  },
}));

describe('坐席结束会话', () => {
  it('收到 ended 推送：提示文案、丢掉会话并关闭 inbox，下一条消息新开会话', async () => {
    await import('./main');
    await vi.waitFor(() => expect(typeof mocks.sse.onEvent).toBe('function'));
    expect(mocks.clearSession).not.toHaveBeenCalled();

    mocks.sse.onEvent!('session', JSON.stringify({ kind: 'ended', text: '会话已结束' }));

    expect(mocks.ui.showSessionState).toHaveBeenCalledWith('会话已结束');
    expect(mocks.clearSession).toHaveBeenCalledWith(expect.anything(), 'k1');
    expect(mocks.inboxClose).toHaveBeenCalled();
    expect(mocks.ui.setAttachEnabled).toHaveBeenLastCalledWith(false);

    mocks.api.stream.mockImplementationOnce(async (_m: string, _sid: string | null, h: ChatStreamHandlers) => {
      h.onStart?.({ sessionId: 'sess-2', suggestHandover: false, knowledgeHits: [], userMessageId: null });
      h.onDone?.(2, 1);
    });
    mocks.uiHandlers?.onSend?.('还有个问题');
    await vi.waitFor(() => expect(mocks.api.stream).toHaveBeenCalled());
    expect(mocks.api.stream.mock.calls[0][1]).toBeNull();
  });

  it('其他会话状态（如坐席接入）不丢会话', async () => {
    // 同文件模块状态延续自上一条：会话已被新开为 sess-2，inbox 也已重建
    await vi.waitFor(() => expect(typeof mocks.sse.onEvent).toBe('function'));
    mocks.clearSession.mockClear();

    mocks.sse.onEvent!('session', JSON.stringify({ kind: 'accepted', text: '客服已接入' }));

    expect(mocks.clearSession).not.toHaveBeenCalled();
  });
});

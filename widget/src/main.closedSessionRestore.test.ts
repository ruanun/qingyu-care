/**
 * 「一次咨询一个会话」（2026-09-29）：刷新页面时本地存着的会话若已结束 / 超时，就丢掉它，不恢复历史、不建 inbox，
 * 下一条消息不带 sessionId、由后端新开会话。
 *
 * main.ts 是只执行一次的副作用入口，模块级状态整份文件共享；会话被丢掉后会影响同文件后续用例，
 * 所以这个场景单独成文件（vitest 按文件隔离模块），做法同 main.test.ts 的说明。
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
    getSession: vi.fn(async () => ({
      sessionId: 'sess-old', status: 'TIMEOUT',
      messages: [{ id: 1, role: 'USER', content: '上次的问题' }],
    })),
    inboxUrl: vi.fn((sid: string) => `/open/v1/sessions/${sid}/inbox`),
    markRead: vi.fn(async () => undefined),
    stream: vi.fn(),
  },
  connectSse: vi.fn(() => ({ close: vi.fn() })),
  clearSession: vi.fn(),
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
vi.mock('./core/session', () => ({
  loadSession: () => 'sess-old',
  saveSession: vi.fn(),
  clearSession: mocks.clearSession,
}));
vi.mock('./core/sse', () => ({ connectSse: mocks.connectSse }));

describe('刷新时本地会话已关闭', () => {
  it('不恢复历史、不建 inbox、清掉本地会话，下一条消息新开会话', async () => {
    await import('./main');
    await vi.waitFor(() => expect(mocks.clearSession).toHaveBeenCalledWith(expect.anything(), 'k1'));

    expect(mocks.ui.addUserMessage).not.toHaveBeenCalled(); // 旧历史没有被渲染
    expect(mocks.connectSse).not.toHaveBeenCalled();         // 已关闭会话不再订阅
    expect(mocks.ui.setAttachEnabled).toHaveBeenLastCalledWith(false);

    mocks.api.stream.mockImplementationOnce(async (_m: string, _sid: string | null, h: ChatStreamHandlers) => {
      h.onStart?.({ sessionId: 'sess-new', suggestHandover: false, knowledgeHits: [], userMessageId: null });
      h.onDone?.(2, 1);
    });
    mocks.uiHandlers?.onSend?.('新问题');
    await vi.waitFor(() => expect(mocks.api.stream).toHaveBeenCalled());
    expect(mocks.api.stream.mock.calls[0][1]).toBeNull(); // 不带旧 sessionId
  });
});

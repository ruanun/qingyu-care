/**
 * 「一次咨询一个会话」（2026-09-29）：会话超时关闭时后端不会推 ended 事件，widget 只能在「转人工 / 上传附件」
 * 撞到 404 时才知道。两条路径都要丢掉会话并告诉用户怎么继续。单独成文件的理由同 main.closedSessionRestore.test.ts。
 *
 * @author ruan
 */
import { describe, it, expect, vi } from 'vitest';
import type { ChatStreamHandlers } from './core/chatStream';

const mocks = vi.hoisted(() => {
  class SessionExpiredError extends Error {}
  return {
    SessionExpiredError,
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
      handover: vi.fn(),
      uploadAttachment: vi.fn(),
    },
    clearSession: vi.fn(),
    uiHandlers: null as null | {
      onSend?: (message: string) => void;
      onHandover?: () => void;
      onUploadFile?: (file: File) => Promise<unknown>;
    },
  };
});

vi.mock('./config', () => ({ resolveConfig: () => ({ appKey: 'k1', apiBase: 'http://host/api' }) }));
vi.mock('./api', () => ({ createApi: () => mocks.api, SessionExpiredError: mocks.SessionExpiredError }));
vi.mock('./ui', () => ({
  mountUi: (_b: unknown, handlers: typeof mocks.uiHandlers) => {
    mocks.uiHandlers = handlers;
    return mocks.ui;
  },
}));
vi.mock('./core/session', () => ({ loadSession: () => 'sess-1', saveSession: vi.fn(), clearSession: mocks.clearSession }));
vi.mock('./core/sse', () => ({ connectSse: () => ({ close: vi.fn() }) }));

describe('会话已关闭时的转人工与附件上传', () => {
  it('转人工撞 404：丢掉会话并提示先发消息，不再谎报「已转接」', async () => {
    await import('./main');
    await vi.waitFor(() => expect(mocks.uiHandlers).not.toBeNull());
    mocks.api.handover.mockRejectedValueOnce(new mocks.SessionExpiredError());

    mocks.uiHandlers!.onHandover!();
    await vi.waitFor(() => expect(mocks.ui.addBotMessage).toHaveBeenCalled());

    expect(mocks.ui.addBotMessage).toHaveBeenCalledWith(expect.stringContaining('当前会话已结束'));
    expect(mocks.ui.addBotMessage).not.toHaveBeenCalledWith('已为你转接人工，请稍候。');
    expect(mocks.clearSession).toHaveBeenCalledWith(expect.anything(), 'k1');
  });

  it('转人工遇到其他失败时明确告知失败', async () => {
    // 先发一条消息重新建立会话（上一条已把会话丢掉）
    mocks.api.stream.mockImplementationOnce(async (_m: string, _sid: string | null, h: ChatStreamHandlers) => {
      h.onStart?.({ sessionId: 'sess-2', suggestHandover: false, knowledgeHits: [], userMessageId: null });
      h.onDone?.(2, 1);
    });
    mocks.uiHandlers!.onSend!('你好');
    await vi.waitFor(() => expect(mocks.api.stream).toHaveBeenCalled());
    mocks.ui.addBotMessage.mockClear();
    mocks.api.handover.mockRejectedValueOnce(new Error('转人工失败: 500'));

    mocks.uiHandlers!.onHandover!();
    await vi.waitFor(() => expect(mocks.ui.addBotMessage).toHaveBeenCalled());

    expect(mocks.ui.addBotMessage).toHaveBeenCalledWith('转人工失败，请稍后再试。');
  });

  it('上传附件撞 404：丢掉会话、禁用附件按钮并提示', async () => {
    mocks.clearSession.mockClear();
    mocks.ui.addBotMessage.mockClear();
    mocks.api.uploadAttachment.mockRejectedValueOnce(new mocks.SessionExpiredError());

    await expect(mocks.uiHandlers!.onUploadFile!(new File(['x'], 'a.txt'))).rejects.toBeInstanceOf(mocks.SessionExpiredError);

    expect(mocks.clearSession).toHaveBeenCalledWith(expect.anything(), 'k1');
    expect(mocks.ui.setAttachEnabled).toHaveBeenLastCalledWith(false);
    expect(mocks.ui.addBotMessage).toHaveBeenCalledWith(expect.stringContaining('当前会话已结束'));
  });
});

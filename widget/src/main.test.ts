/**
 * @author ruan
 */
import { describe, it, expect, vi } from 'vitest';
import { restoreHistory } from './history';
import type { WidgetUi } from './ui';

/**
 * main.ts 是「无导出的纯副作用 IIFE」（见 history.ts 的说明），inbox 事件回调是它的内部闭包，
 * 拿不到引用。故整套依赖全 mock 掉、靠动态 import 触发 init()，再从 connectSse 的 mock 里
 * 截下那个回调手动喂事件——这是不破坏"入口零导出"约束的唯一测法。
 */
const mocks = vi.hoisted(() => ({
  ui: {
    addUserMessage: vi.fn(),
    addBotMessage: vi.fn(),
    addAgentMessage: vi.fn(),
    showSessionState: vi.fn(),
    setTyping: vi.fn(),
    setInputEnabled: vi.fn(),
    setAttachEnabled: vi.fn(),
    markOpened: vi.fn(),
    beginBotMessage: vi.fn(),
  },
  api: {
    getConfig: vi.fn(async () => ({
      appName: '轻语', welcomeMessage: '你好', avatar: '', themeColor: '#000',
    })),
    // 返回 null：跳过 restoreHistory，让断言只反映 inbox 这一条路径
    getSession: vi.fn(async () => null),
    inboxUrl: vi.fn((sid: string) => `/open/v1/sessions/${sid}/inbox`),
  },
  /** connectSse 收到的事件回调（由 mock 截获） */
  sse: { onEvent: null as null | ((event: string, data: string) => void) },
}));

vi.mock('./config', () => ({
  resolveConfig: () => ({ appKey: 'k1', apiBase: 'http://host/api' }),
}));
vi.mock('./api', () => ({
  createApi: () => mocks.api,
  SessionExpiredError: class SessionExpiredError extends Error {},
}));
vi.mock('./ui', () => ({ mountUi: () => mocks.ui }));
vi.mock('./session', () => ({
  loadSession: () => 'sess-1',   // 本地已有会话 → init 直接建 inbox，无需先发消息
  saveSession: vi.fn(),
  clearSession: vi.fn(),
}));
vi.mock('./sse', () => ({
  connectSse: (_url: string, _appKey: string, onEvent: (event: string, data: string) => void) => {
    mocks.sse.onEvent = onEvent;
    return { close: vi.fn() };
  },
}));

/** 驱动 main.ts 初始化并取出 inbox 事件回调（模块只执行一次，重复调用取同一个）。 */
async function inboxHandler(): Promise<(event: string, data: string) => void> {
  await import('./main');
  await vi.waitFor(() => expect(typeof mocks.sse.onEvent).toBe('function'));
  mocks.ui.addAgentMessage.mockClear();
  return mocks.sse.onEvent!;
}

function fakeUi() {
  return {
    addUserMessage: vi.fn(),
    addBotMessage: vi.fn(),
    addAgentMessage: vi.fn(),
    showSessionState: vi.fn(),
    setTyping: vi.fn(),
    setInputEnabled: vi.fn(),
    markOpened: vi.fn(),
  } as unknown as WidgetUi & Record<string, ReturnType<typeof vi.fn>>;
}

describe('restoreHistory 会话恢复', () => {
  it('按角色渲染历史，ASSISTANT 带 uiParts', () => {
    const ui = fakeUi();
    restoreHistory(ui as unknown as WidgetUi, [
      { role: 'USER', content: '你好', createdAt: '' },
      { role: 'ASSISTANT', content: '卡片回复', createdAt: '', uiParts: [{ kind: 'ui', node: { type: 'card' } }] },
      { role: 'AGENT', content: '客服接入', createdAt: '' },
      { role: 'SYSTEM', content: '会话结束', createdAt: '' },
    ]);
    expect(ui.addUserMessage).toHaveBeenCalledWith('你好', undefined);
    expect(ui.addBotMessage).toHaveBeenCalledWith(
      '卡片回复', undefined, [{ kind: 'ui', node: { type: 'card' } }], undefined,
    );
    expect(ui.addAgentMessage).toHaveBeenCalledWith('客服接入', undefined);
    expect(ui.showSessionState).toHaveBeenCalledWith('会话结束');
  });

  it('USER/AGENT 历史消息带 attachments 时原样透传给 ui', () => {
    const ui = fakeUi();
    const atts = [{ id: 1, fileName: 'a.png', contentType: 'image/png', fileSize: 100 }];
    restoreHistory(ui as unknown as WidgetUi, [
      { role: 'USER', content: '看图', createdAt: '', attachments: atts },
      { role: 'AGENT', content: '收到', createdAt: '', attachments: atts },
    ]);
    expect(ui.addUserMessage).toHaveBeenCalledWith('看图', atts);
    expect(ui.addAgentMessage).toHaveBeenCalledWith('收到', atts);
  });

  it('空/未知角色不崩', () => {
    const ui = fakeUi();
    expect(() => restoreHistory(ui as unknown as WidgetUi, [
      { role: 'WEIRD', content: 'x', createdAt: '' },
    ])).not.toThrow();
  });
});

/**
 * inbox 实时通道：坐席在工作台发的图靠这条路径抵达访客（后端在推送那一刻现查附件塞进
 * payload）。丢掉 attachments 不会报错，只是图彻底不出现、刷新才有——历史恢复路径已被上面
 * 的用例守住，这里守的是实时路径。
 */
describe('inbox 坐席消息', () => {
  it('带 attachments 时原样透传给 addAgentMessage', async () => {
    const onEvent = await inboxHandler();
    const atts = [{ id: 9, fileName: 'p.png', contentType: 'image/png', fileSize: 128 }];

    onEvent('message', JSON.stringify({ role: 'ASSISTANT', content: '看这张图', attachments: atts }));

    expect(mocks.ui.addAgentMessage).toHaveBeenCalledWith('看这张图', atts);
  });

  it('无 attachments 字段时第二参为 undefined', async () => {
    const onEvent = await inboxHandler();

    onEvent('message', JSON.stringify({ role: 'AGENT', content: '纯文本' }));

    expect(mocks.ui.addAgentMessage).toHaveBeenCalledWith('纯文本', undefined);
  });
});

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
    addUserMessage: vi.fn(() => ({ setMessageId: vi.fn() })),
    addBotMessage: vi.fn(),
    addAgentMessage: vi.fn(),
    showSessionState: vi.fn(),
    setTyping: vi.fn(),
    setInputEnabled: vi.fn(),
    setAttachEnabled: vi.fn(),
    markOpened: vi.fn(),
    beginBotMessage: vi.fn(),
    markReadUpTo: vi.fn(),
  },
  api: {
    getConfig: vi.fn(async () => ({
      appName: '轻语', welcomeMessage: '你好', avatar: '', themeColor: '#000',
    })),
    // 返回 null：跳过 restoreHistory，让断言只反映 inbox 这一条路径
    getSession: vi.fn(async () => null),
    inboxUrl: vi.fn((sid: string) => `/open/v1/sessions/${sid}/inbox`),
    markRead: vi.fn(async () => undefined),
  },
  /** connectSse 收到的事件回调（由 mock 截获） */
  sse: { onEvent: null as null | ((event: string, data: string) => void) },
  /** mountUi 收到的 handlers（由 mock 截获），供 setPanelOpen 驱动真实的 onToggle 回调 */
  uiHandlers: null as null | { onToggle?: (open: boolean) => void },
}));

vi.mock('./config', () => ({
  resolveConfig: () => ({ appKey: 'k1', apiBase: 'http://host/api' }),
}));
vi.mock('./api', () => ({
  createApi: () => mocks.api,
  SessionExpiredError: class SessionExpiredError extends Error {},
}));
vi.mock('./ui', () => ({
  mountUi: (_brand: unknown, handlers: { onToggle?: (open: boolean) => void }) => {
    mocks.uiHandlers = handlers;
    return mocks.ui;
  },
}));
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

/**
 * 驱动 main.ts 初始化并取出 inbox 事件回调（模块只执行一次，重复调用取同一个）。
 * 顺带清掉本轮已读相关用例会断言的几个 mock 的既往调用记录——模块级状态（isOpen /
 * latestAgentMessageId 等）在整个文件里持续存在，但调用记录不清会让后一条用例的
 * `not.toHaveBeenCalled()` 被前一条用例的残留调用误判。
 */
async function inboxHandler(): Promise<(event: string, data: string) => void> {
  await import('./main');
  await vi.waitFor(() => expect(typeof mocks.sse.onEvent).toBe('function'));
  mocks.ui.addAgentMessage.mockClear();
  mocks.ui.showSessionState.mockClear();
  mocks.ui.markReadUpTo.mockClear();
  mocks.api.markRead.mockClear();
  return mocks.sse.onEvent!;
}

/** 用捕获的 onEvent 回调模拟一次 inbox SSE 推送（同 inboxHandler 返回值的用法，语义化包一层）。 */
function emitSse(event: string, payload: unknown): void {
  mocks.sse.onEvent?.(event, JSON.stringify(payload));
}

/**
 * 驱动面板展开/收起：main.ts 把 onToggle 注入给 mountUi 的 handlers，ui.ts 真实实现里
 * 点击悬浮气泡会调用它——这里直接调用被 mock 截获的同一个回调，而不是改 main.ts 内部
 * 变量，测的是「回调触发后 main.ts 的状态真的变了」而非测试自己伪造状态。
 */
function setPanelOpen(open: boolean): void {
  mocks.uiHandlers?.onToggle?.(open);
}

/** 桩标签页可见性。jsdom 下 visibilityState 只读，必须用 defineProperty 覆盖。 */
function stubVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => state,
  });
}

function fakeUi() {
  return {
    addUserMessage: vi.fn(() => ({ setMessageId: vi.fn() })),
    addBotMessage: vi.fn(),
    addAgentMessage: vi.fn(),
    showSessionState: vi.fn(),
    setTyping: vi.fn(),
    setInputEnabled: vi.fn(),
    markOpened: vi.fn(),
    markReadUpTo: vi.fn(),
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

  /**
   * 裁定二：历史访客消息必须回填 id，否则刷新页面后 markReadUpTo 无处可标——「刷新后已读
   * 状态保持」是这个特性最该稳的东西。这里断言 addUserMessage 返回的句柄真的被调用，
   * ui.test.ts 另有一条用真实 mountUi + markReadUpTo 验证 DOM 上真的标出来了。
   */
  it('USER 历史消息带 id 时回填到 addUserMessage 返回的句柄上', () => {
    const ui = fakeUi();
    const setMessageId = vi.fn();
    (ui.addUserMessage as ReturnType<typeof vi.fn>).mockReturnValueOnce({ setMessageId });
    restoreHistory(ui as unknown as WidgetUi, [
      { id: 41, role: 'USER', content: '你好', createdAt: '' },
    ]);
    expect(setMessageId).toHaveBeenCalledWith(41);
  });

  it('USER 历史消息没有 id 时不回填（不崩）', () => {
    const ui = fakeUi();
    expect(() => restoreHistory(ui as unknown as WidgetUi, [
      { role: 'USER', content: '你好', createdAt: '' },
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

/**
 * read 事件：坐席读了访客发的消息后，后端推 read 事件到访客 inbox。
 * ⚠️ 变异测试的好目标：把实现改成 ui.showSessionState('已读')（即复用 session 事件那条
 * 路径），下面第一条用例必须转红——这守的是「访客界面不冒莫名提示」。
 */
describe('read 事件（渲染已读，不落文案）', () => {
  it('read 事件不产生任何文案节点，只调用 markReadUpTo', async () => {
    await inboxHandler();

    emitSse('read', { sessionId: 'sess-1', upToMessageId: 7 });

    expect(mocks.ui.showSessionState).not.toHaveBeenCalled();
    expect(mocks.ui.markReadUpTo).toHaveBeenCalledWith(7);
  });

  it('upToMessageId 非数字时忽略，不崩、不调用 markReadUpTo', async () => {
    await inboxHandler();

    expect(() => emitSse('read', { sessionId: 'sess-1' })).not.toThrow();
    expect(mocks.ui.markReadUpTo).not.toHaveBeenCalled();
  });
});

/**
 * 已读上报：widget 展开 且 页面前台可见 两个条件同时成立才上报。收起是 widget 自己的
 * 内部状态，与页面可见性是两个独立条件，缺任一判断都会产生假已读。三个触发点各占一条。
 */
describe('已读上报（widget 展开 且 页面可见）', () => {
  // ⚠️ 每条用例都先 stubVisibility 再 setPanelOpen：main.ts 的模块状态在整个测试文件内持续
  // 存在（isOpen/latestAgentMessageId 不会在用例间重置），而 setPanelOpen(true) 本身会
  // 触发一次真实的上报判定（触发点一）。若先 setPanelOpen 后 stubVisibility，这次判定会用
  // 上一条用例遗留的 visibilityState 求值，产生与本用例设定不符的假阳性/假阴性上报。

  it('widget 收起时不上报已读', async () => {
    await inboxHandler();
    stubVisibility('visible');
    setPanelOpen(false);

    emitSse('message', { sessionId: 'sess-1', messageId: 9, role: 'AGENT', content: '在的' });

    expect(mocks.api.markRead).not.toHaveBeenCalled();
  });

  it('页面在后台时不上报已读，即使 widget 是展开的', async () => {
    await inboxHandler();
    stubVisibility('hidden');
    setPanelOpen(true);

    emitSse('message', { sessionId: 'sess-1', messageId: 9, role: 'AGENT', content: '在的' });

    expect(mocks.api.markRead).not.toHaveBeenCalled();
  });

  it('widget 展开且页面可见时上报，upToMessageId 是最新那条（触发点二）', async () => {
    await inboxHandler();
    stubVisibility('visible');
    setPanelOpen(true);

    emitSse('message', { sessionId: 'sess-1', messageId: 9, role: 'AGENT', content: '在的' });

    expect(mocks.api.markRead).toHaveBeenCalledWith('sess-1', 9);
  });

  it('面板从收起变为展开时补报已读（触发点一）', async () => {
    await inboxHandler();
    stubVisibility('visible');
    setPanelOpen(false);
    emitSse('message', { sessionId: 'sess-1', messageId: 13, role: 'AGENT', content: '你好' });
    expect(mocks.api.markRead).not.toHaveBeenCalled(); // 此刻仍收起，不该上报

    setPanelOpen(true);

    expect(mocks.api.markRead).toHaveBeenCalledWith('sess-1', 13);
  });

  it('标签页从后台切回前台时补报已读（触发点三）', async () => {
    await inboxHandler();
    stubVisibility('hidden');
    setPanelOpen(true);
    emitSse('message', { sessionId: 'sess-1', messageId: 21, role: 'AGENT', content: '还在吗' });
    expect(mocks.api.markRead).not.toHaveBeenCalled(); // 此刻标签页在后台，不该上报

    stubVisibility('visible');
    document.dispatchEvent(new Event('visibilitychange'));

    expect(mocks.api.markRead).toHaveBeenCalledWith('sess-1', 21);
  });
});

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// ⚠️ frontend/ 故意不开 globals：测试文件必须显式 import。
// ⚠️ src/test/setup.ts 里那行 afterEach(cleanup) 不能删——RTL 只在 afterEach 是全局函数时
//    才自己注册 cleanup，漏掉的症状是「单个测试跑绿、整套一起跑红」。
import { render, screen, act, fireEvent } from '@testing-library/react';
import AgentPage from './AgentPage';

/**
 * 坐席台「我的会话」列表 + 未读徽章。
 *
 * 守两个此前的真实缺陷：
 * ① 接管第二个会话会丢掉第一个——handleAcceptSession 只做 setSelectedSession，
 *    该会话同时因状态变更从待接队列消失，此前又没有「我的会话」查询接口，刷新也回不去；
 * ② 未读徽章是死代码永不显示——旧实现里 unread 状态的 key 来自 AGENT_PROCESSING 会话的
 *    SSE 推送，却渲染在 WAITING_AGENT 的待接队列 map 里，两个集合按定义不相交。
 */

// ⚠️ vi.mock 的工厂被提到所有 import 之前执行，工厂里引用模块顶层 const 会撞 TDZ，
//    桩函数必须用 vi.hoisted 一起提上去。
const stubs = vi.hoisted(() => ({
    getWaitingAgentSessions: vi.fn(),
    getMySessions: vi.fn(),
    acceptSession: vi.fn(),
    getOnlineAgents: vi.fn(),
    markRead: vi.fn(),
    getSessionMessages: vi.fn(),
}));

// AgentPage 的 SSE 走内部 fetch + ReadableStream（EventSource 不能带鉴权头，见 lib/sse.ts
// 顶部注释），测试环境没有真实可读流可消费。桩成"记住最近一次 onEvent 回调"，
// 测试里直接调用它模拟事件到达——这就是 brief 里说的"直接桩那条流"这条路。
const sse = vi.hoisted(() => ({
    onEvent: null as ((event: string, data: string) => void) | null,
}));

vi.mock('@/api/agent', () => ({ agentService: stubs }));
vi.mock('@/api/chat', () => ({ chatService: { getSessionMessages: stubs.getSessionMessages } }));
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }));
vi.mock('@/lib/sse', () => ({
    connectSse: (_url: string, handlers: { onEvent: (event: string, data: string) => void }) => {
        sse.onEvent = handlers.onEvent;
        return { close: vi.fn() };
    },
}));

const ok = <T,>(data: T) => ({ code: 200, message: 'ok', data });
const page = <T,>(content: T[]) => ok({ content, totalElements: content.length });

const mySession = (sessionId: string, unreadCount: number) => ({
    id: 1, sessionId, userId: 'u1', status: 'AGENT_PROCESSING',
    lastMessageAt: '2026-08-20T10:00:00', unreadCount,
});

beforeEach(() => {
    Object.values(stubs).forEach((s) => s.mockReset());
    sse.onEvent = null;
    stubs.getWaitingAgentSessions.mockResolvedValue(page([]));
    stubs.getMySessions.mockResolvedValue(ok([]));
    stubs.getOnlineAgents.mockResolvedValue(ok([]));
    // ⚠️ 与 brief 模板的一处适配：GET .../messages 的响应从裸数组改成了
    // { messages, visitorReadUpToMessageId } 包装（Task 7 的破坏性契约变更），
    // 桩的返回形态必须跟着改，否则这份 mock 本身就是假的。
    stubs.getSessionMessages.mockResolvedValue(ok({ messages: [], visitorReadUpToMessageId: null }));
    stubs.markRead.mockResolvedValue(ok(0));
});

afterEach(() => {
    vi.useRealTimers();
});

describe('坐席台我的会话列表', () => {
    it('渲染未读徽章', async () => {
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 3)]));

        render(<AgentPage />);

        expect(await screen.findByText('3')).toBeInTheDocument();
    });

    it('未读徽章挂在我的会话上，不在待接队列上', async () => {
        // ⚠️ 这条守的正是那个死代码缺陷：原实现把 unread 渲染在 waitingSessions.map 里，
        //    而 unread 的 key 来自 toAgentMessage 推送（AGENT_PROCESSING 会话），
        //    两个集合按定义不相交，徽章永远不显示。
        stubs.getWaitingAgentSessions.mockResolvedValue(page([
            { id: 9, sessionId: 'waiting-1', status: 'WAITING_AGENT' },
        ]));
        stubs.getMySessions.mockResolvedValue(ok([mySession('mine-1', 5)]));

        render(<AgentPage />);

        const mineRow = await screen.findByTestId('my-session-mine-1');
        expect(mineRow).toHaveTextContent('5');
        expect(await screen.findByTestId('waiting-session-waiting-1')).not.toHaveTextContent('5');
    });

    it('列表刷新后未读数以后端值为准，不与本地累加叠加', async () => {
        // ⚠️ 守 spec §6.4：SSE 到达时的本地 +1 只是过渡态。
        //    若实现改成以本地累加为准，会漂移且不报错——只是徽章数字不对。
        stubs.getMySessions.mockResolvedValueOnce(ok([mySession('s1', 3)]));
        render(<AgentPage />);
        expect(await screen.findByText('3')).toBeInTheDocument();

        // 后端聚合出的新值是 7（不是 3 + 本地增量）
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 7)]));
        await act(async () => {
            sse.onEvent?.('message', JSON.stringify({ sessionId: 's1' }));
        });

        expect(await screen.findByText('7')).toBeInTheDocument();
        expect(screen.queryByText('11')).not.toBeInTheDocument();
    });

    // 直接回归缺陷①：handleAcceptSession 曾经只 setSelectedSession(res.data)，
    // 而该会话同时因状态变更从待接队列消失——此前又没有「我的会话」查询接口，
    // 接管第二个会话时界面上只剩最新那一个，第一个再也回不去。
    it('接管新会话后仍保留此前已接管的会话', async () => {
        stubs.getWaitingAgentSessions.mockResolvedValue(page([
            {
                id: 9, sessionId: 'waiting-2', userId: 'u2', status: 'WAITING_AGENT',
                createdAt: '2026-08-20T09:00:00',
            },
        ]));
        // 挂载时：坐席手头已有一个进行中会话 mine-1；接管 waiting-2 之后，
        // 后端重查返回的列表须同时含 mine-1 与刚接管的 waiting-2。
        stubs.getMySessions
            .mockResolvedValueOnce(ok([mySession('mine-1', 0)]))
            .mockResolvedValue(ok([mySession('mine-1', 0), { ...mySession('waiting-2', 0), id: 9, userId: 'u2' }]));
        stubs.acceptSession.mockResolvedValue(ok({
            id: 9, sessionId: 'waiting-2', userId: 'u2', status: 'AGENT_PROCESSING',
            createdAt: '2026-08-20T09:00:00', updatedAt: '2026-08-20T09:05:00',
        }));

        render(<AgentPage />);
        expect(await screen.findByTestId('my-session-mine-1')).toBeInTheDocument();

        fireEvent.click(await screen.findByTestId('waiting-session-waiting-2'));

        expect(await screen.findByTestId('my-session-waiting-2')).toBeInTheDocument();
        // 判别性断言：新会话出现了，但旧会话没有被挤掉
        expect(screen.getByTestId('my-session-mine-1')).toBeInTheDocument();
    });
});

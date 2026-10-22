import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// ⚠️ frontend/ 故意不开 globals：测试文件必须显式 import。
// ⚠️ src/test/setup.ts 里那行 afterEach(cleanup) 不能删——RTL 只在 afterEach 是全局函数时
//    才自己注册 cleanup，漏掉的症状是「单个测试跑绿、整套一起跑红」。
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
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
    agentReply: vi.fn(),
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
    stubs.agentReply.mockResolvedValue(ok(0));
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

/**
 * 坐席台已读回执上报（功能 A 坐席侧）+ 渲染访客已读（功能 B 坐席侧）。
 *
 * 上报条件是「选中了该会话」且「标签页当前可见」两者同时成立——只判选中挡不住
 * "工作台开着但人去开会了"这类假已读。三个触发点：切换会话、收到该会话新消息、
 * 标签页从后台切回前台。
 *
 * 渲染侧：SSE read 事件必须按载荷里的 sessionId 归位，不能默认标在当前选中会话上——
 * 工作台是多会话的，坐席可能正看着甲而乙的访客读了消息，这是本轮引入的新失效模式。
 */

/** 桩标签页可见性。jsdom 下 visibilityState 只读，必须用 defineProperty 覆盖。 */
function stubVisibility(state: 'visible' | 'hidden') {
    Object.defineProperty(document, 'visibilityState', {
        configurable: true,
        get: () => state,
    });
}

describe('坐席台已读回执', () => {
    it('选中会话且页面可见时上报已读', async () => {
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 2)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [
                { id: 41, role: 'USER', content: '你好', createdAt: '2026-08-20T10:00:00' },
                { id: 42, role: 'USER', content: '在吗', createdAt: '2026-08-20T10:01:00' },
            ],
            visitorReadUpToMessageId: null,
        }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));

        // upToMessageId 传「已渲染的最新一条」，不是服务端库里最新的，也不区分角色
        await waitFor(() => {
            expect(stubs.markRead).toHaveBeenCalledWith('s1', 42);
        });
    });

    it('页面在后台时不上报', async () => {
        // ⚠️ 守的是「工作台开着但人去开会了」那类假已读。
        //    只判「选中会话」挡不住它——变异验证时把 visibilityState 条件去掉，本条必须转红。
        stubVisibility('hidden');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 2)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [
                { id: 42, role: 'USER', content: '在吗', createdAt: '2026-08-20T10:01:00' },
            ],
            visitorReadUpToMessageId: null,
        }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));
        await screen.findByText('在吗'); // 等异步链跑完（消息已渲染）再断言，而非提前判定

        expect(stubs.markRead).not.toHaveBeenCalled();
    });

    it('标签页从后台切回前台时补报已读', async () => {
        // 触发点三：visibilitychange 转为 visible。选中会话时页面在后台不上报，
        // 切回前台那一刻应补报一次——不是等下一次切换会话或来新消息才报。
        stubVisibility('hidden');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [{ id: 42, role: 'USER', content: '在吗', createdAt: '2026-08-20T10:01:00' }],
            visitorReadUpToMessageId: null,
        }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));
        await screen.findByText('在吗');
        expect(stubs.markRead).not.toHaveBeenCalled();

        stubVisibility('visible');
        await act(async () => {
            document.dispatchEvent(new Event('visibilitychange'));
        });

        await waitFor(() => {
            expect(stubs.markRead).toHaveBeenCalledWith('s1', 42);
        });
    });

    it('收到当前会话的新消息时上报已读', async () => {
        // 触发点二：SSE message 事件命中当前选中会话。
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({ messages: [], visitorReadUpToMessageId: null }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));
        await waitFor(() => expect(stubs.getSessionMessages).toHaveBeenCalled());
        stubs.markRead.mockClear(); // 清掉「切换会话」触发点可能已产生的调用记录，本条只看新消息触发点

        await act(async () => {
            sse.onEvent?.('message', JSON.stringify({
                sessionId: 's1', messageId: 99, role: 'USER', content: '新消息', createdAt: '2026-08-20T10:05:00',
            }));
        });

        await waitFor(() => {
            expect(stubs.markRead).toHaveBeenCalledWith('s1', 99);
        });
    });

    it('坐席发送回复后再触发已读上报，用的是后端返回的真实消息id而非本地占位id', async () => {
        // 回归本特性合并前审查抓到的必修缺陷：坐席回复一条消息 → 前端曾用 Date.now() 占位 id
        // 乐观追加 → 坐席切到别的标签页再切回来 → visibilitychange 处理器取"已渲染的最后一条"
        // 上报 → 后端不校验就把这个占位 id（约 1.7e12 量级）当水位线落库。水位线语义只增
        // 不减，此后该会话的未读数永久归零、没有任何报错。
        // ⚠️ 真实 id（500）与占位 id 数量级悬殊，特意这样取值让断言具备判别力——
        // 若两者接近，即便实现退化回 Date.now() 断言也可能凑巧碰对，判别力等于零。
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [{ id: 42, role: 'USER', content: '在吗', createdAt: '2026-08-20T10:01:00' }],
            visitorReadUpToMessageId: null,
        }));
        stubs.agentReply.mockResolvedValue(ok(500)); // 后端落库后的真实消息 id

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));
        await screen.findByText('在吗');
        stubs.markRead.mockClear(); // 清掉「切换会话」触发点已产生的调用记录，本条只看后续的

        const input = screen.getByPlaceholderText('输入回复内容...');
        fireEvent.change(input, { target: { value: '已处理，请查收' } });
        await act(async () => {
            fireEvent.keyDown(input, { key: 'Enter' });
        });
        await screen.findByText('已处理，请查收'); // 等 agentReply 的 await 完成、乐观追加渲染出来

        // 触发点三：坐席切到别的标签页再切回来
        stubVisibility('hidden');
        await act(async () => {
            document.dispatchEvent(new Event('visibilitychange'));
        });
        stubVisibility('visible');
        await act(async () => {
            document.dispatchEvent(new Event('visibilitychange'));
        });

        await waitFor(() => {
            expect(stubs.markRead).toHaveBeenCalledWith('s1', 500);
        });
        // 判别性断言的另一半：上报值不能是 Date.now() 量级（远大于 100 万）的占位值
        const reportedIds = stubs.markRead.mock.calls.map((call) => call[1] as number);
        expect(reportedIds.every((id) => id < 1_000_000)).toBe(true);
    });

    it('上报已读成功后刷新我的会话列表，未读徽章不必等轮询就清零', async () => {
        // 顺带解决 Task 8 遗留问题：选中会话后本地不清零 unreadCount，此前要等 10 秒轮询才消失。
        stubVisibility('visible');
        stubs.getMySessions
            .mockResolvedValueOnce(ok([mySession('s1', 3)])) // 挂载时的初次拉取
            .mockResolvedValue(ok([mySession('s1', 0)])); // 上报成功后的刷新值
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [{ id: 42, role: 'USER', content: '在吗', createdAt: '2026-08-20T10:01:00' }],
            visitorReadUpToMessageId: null,
        }));

        render(<AgentPage />);
        expect(await screen.findByText('3')).toBeInTheDocument();

        fireEvent.click(await screen.findByTestId('my-session-s1'));

        await waitFor(() => {
            expect(screen.queryByText('3')).not.toBeInTheDocument();
        });
    });

    it('上报失败时只 console.debug，不抛出也不阻断界面', async () => {
        // fail-soft：坐席正在服务客户，不该被「已读上报失败」打断，故不弹 toast。
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [{ id: 42, role: 'USER', content: '在吗', createdAt: '2026-08-20T10:01:00' }],
            visitorReadUpToMessageId: null,
        }));
        stubs.markRead.mockRejectedValue(new Error('网络错误'));
        const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {});

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));

        await waitFor(() => {
            expect(debugSpy).toHaveBeenCalled();
        });
        debugSpy.mockRestore();
    });

    it('切换会话时从历史响应恢复访客已读水位线，刷新后仍显示已读', async () => {
        stubVisibility('hidden'); // 与上报动作解耦：恢复逻辑读的是历史响应，不依赖是否上报
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [
                { id: 50, role: 'AGENT', content: '您好，有什么可以帮您', createdAt: '2026-08-20T10:00:00' },
            ],
            visitorReadUpToMessageId: 50,
        }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));

        expect(await screen.findByTestId('read-mark-50')).toBeInTheDocument();
    });

    it('read 事件命中当前会话时渲染已读标记', async () => {
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [
                { id: 50, role: 'AGENT', content: '您好，有什么可以帮您', createdAt: '2026-08-20T10:00:00' },
            ],
            visitorReadUpToMessageId: null,
        }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));
        await screen.findByText('您好，有什么可以帮您');

        await act(async () => {
            sse.onEvent?.('read', JSON.stringify({ sessionId: 's1', upToMessageId: 50 }));
        });

        expect(await screen.findByTestId('read-mark-50')).toBeInTheDocument();
    });

    it('收到非当前会话的 read 事件时，标记落在正确的会话上', async () => {
        // ⚠️ 守 spec §6.6：最容易写成「直接标在当前会话」，在多会话工作台下是错的。
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('jia', 0), { ...mySession('yi', 0), id: 2 }]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [
                { id: 50, role: 'AGENT', content: '您好，有什么可以帮您', createdAt: '2026-08-20T10:00:00' },
            ],
            visitorReadUpToMessageId: null,
        }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-jia')); // 正在看「甲」
        await screen.findByText('您好，有什么可以帮您');

        await act(async () => {
            sse.onEvent?.('read', JSON.stringify({ sessionId: 'yi', upToMessageId: 50 }));
        });

        // 甲的消息区不该出现已读——那条回执不是它的
        expect(screen.queryByTestId('read-mark-50')).not.toBeInTheDocument();
    });

    it('坐席刚发出的消息（本地乐观渲染）也能被标已读', async () => {
        // ⚠️ 这条守的是 AgentPage 里硬编码的那个 role：它不来自后端响应，
        // 判据改成 AGENT 后若这里仍写 ASSISTANT，坐席自己刚发的消息匹配不上判据，
        // 已读标记要等整页刷新（重新从后端拿到真实 role）才出现。
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({ messages: [], visitorReadUpToMessageId: null }));
        stubs.agentReply.mockResolvedValue(ok(77));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));

        const input = await screen.findByPlaceholderText('输入回复内容...');
        fireEvent.change(input, { target: { value: '这就为您处理' } });
        fireEvent.keyDown(input, { key: 'Enter' });

        await screen.findByText('这就为您处理');

        await act(async () => {
            sse.onEvent?.('read', JSON.stringify({ sessionId: 's1', upToMessageId: 77 }));
        });

        expect(await screen.findByTestId('read-mark-77')).toBeInTheDocument();
    });
});

/**
 * 工作台里 AI 回复与坐席自己的消息同在右侧：只有 AI 回复渲染 Markdown，坐席消息保持纯文本
 * （坐席打字时可能用到 `*`，被当成格式吃掉反而是错的）。
 */
describe('坐席台消息 Markdown', () => {
    it('AI 回复渲染 Markdown，坐席与访客消息保持原文', async () => {
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [
                { id: 51, role: 'USER', content: '我要**退货**', createdAt: '2026-09-29T10:00:00' },
                { id: 52, role: 'ASSISTANT', content: '流程：**先申请**', createdAt: '2026-09-29T10:00:05' },
                { id: 53, role: 'AGENT', content: '单价 3*4 元，**请确认**', createdAt: '2026-09-29T10:01:00' },
            ],
            visitorReadUpToMessageId: null,
        }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));

        // Markdown 渲染器是懒加载的，整套并行时首次加载会超过默认 1 秒，放宽等待（同 SessionMessages.test.tsx）
        expect((await screen.findByText('先申请', {}, { timeout: 10000 })).tagName).toBe('STRONG');
        expect(screen.getByText('单价 3*4 元，**请确认**')).toBeInTheDocument();
        expect(screen.getByText('我要**退货**')).toBeInTheDocument();
    });
});

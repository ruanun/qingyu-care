import type { ReactNode } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { McpServer, McpTool } from '@/api/mcp';
import McpServerPage from './McpServerPage';

/**
 * McpServerPage 回归测试。
 *
 * 桩在 api service 模块层（`@/api/mcp`）：`api/client.ts` 的响应拦截器把整个后端
 * `ApiResponse`（`{code, message, data}`）原样 resolve 给调用方，这一层的返回值形态
 * 正是「HTTP 200 + body.code」这条全局约定发生的位置（业务异常 axios 不会 reject）。
 *
 * ⚠️ Dialog 不桩：同 CrawlSiteList.test.tsx 的做法——本页面的创建/编辑/删除确认
 * 表单都直接写在页面组件里（没有可整体替换的独立子组件），真实 Radix Dialog 在
 * jsdom 下能正常渲染进 document.body，用 `within(screen.getByRole('dialog'))`
 * 即可消歧「卡片上的删除图标按钮」与「弹窗里的确认删除按钮」这类同名元素。
 *
 * Select 单独桩：本文件不需要验证「选完值真的写回 state」这条 Radix 内部受控行为，
 * 只需要验证「SSE 选项渲染为禁用项」，桩成保留 disabled 语义的透传即可。
 *
 * Switch 与 Button 保持真实实现（未桩）——只读开关与批量按钮正是本文件要验的回调路径。
 */

const { mockApi, mockToast } = vi.hoisted(() => ({
    mockApi: {
        listServers: vi.fn(),
        createServer: vi.fn(),
        updateServer: vi.fn(),
        deleteServer: vi.fn(),
        usage: vi.fn(),
        syncTools: vi.fn(),
        listTools: vi.fn(),
        markReadOnly: vi.fn(),
    },
    mockToast: vi.fn(),
}));

vi.mock('@/api/mcp', () => ({ mcpApi: mockApi }));
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: mockToast }) }));

vi.mock('@/components/ui/select', () => ({
    Select: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectTrigger: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectItem: ({ children, disabled }: { children: ReactNode; disabled?: boolean }) => (
        <div role="option" aria-disabled={disabled ? 'true' : 'false'}>{children}</div>
    ),
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-21T00:00:00',
});

const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400,
    message,
    data: null as unknown as T,
    timestamp: '2026-08-21T00:00:00',
});

const server1: McpServer = {
    id: 1,
    name: '订单系统',
    code: 'order',
    description: '订单查询系统',
    transport: 'STREAMABLE_HTTP',
    url: 'https://x/mcp',
    timeoutMs: 10000,
    maxResponseChars: 4000,
    enabled: true,
    lastSyncedAt: null,
    lastSyncError: null,
};

const toolWithHint: McpTool = {
    id: 10, mcpServerId: 1, name: 'query_order', description: '查订单',
    status: 'ACTIVE', readOnly: false, readOnlyHint: true,
};
const toolWithoutHint: McpTool = {
    id: 11, mcpServerId: 1, name: 'cancel_order', description: '取消订单',
    status: 'ACTIVE', readOnly: false, readOnlyHint: false,
};
const toolDeprecated: McpTool = {
    id: 12, mcpServerId: 1, name: 'legacy_query', description: '旧接口，远端已下线',
    status: 'DEPRECATED', readOnly: false, readOnlyHint: null,
};

const renderPage = async () => {
    const result = render(<McpServerPage />);
    // 服务名在侧边列表与详情卡标题里各出现一次，用 getAllByText 而非 getByText
    await waitFor(() => expect(screen.getAllByText('订单系统').length).toBeGreaterThan(0));
    return result;
};

beforeEach(() => {
    vi.clearAllMocks();
    mockApi.listServers.mockResolvedValue(ok([server1]));
    mockApi.listTools.mockResolvedValue(ok([]));
    mockApi.usage.mockResolvedValue(ok({ skillCount: 0 }));
});

describe('McpServerPage 同步工具', () => {
    // 业务异常是 HTTP 200 + body.code，axios 不会 reject——不判 code 就会把
    // 「连接超时」这类后端报错静默吞掉，用户点按钮毫无反应。
    it('同步失败时弹 destructive toast，不静默吞掉业务错误', async () => {
        mockApi.syncTools.mockResolvedValue(fail('同步失败：连接超时'));
        await renderPage();

        fireEvent.click(screen.getByRole('button', { name: /同步工具/ }));

        await waitFor(() =>
            expect(mockToast).toHaveBeenCalledWith(
                expect.objectContaining({ variant: 'destructive', description: '同步失败：连接超时' }),
            ),
        );
    });

    it('同步成功后重新拉取该服务的工具清单', async () => {
        mockApi.syncTools.mockResolvedValue(ok([]));
        await renderPage();

        fireEvent.click(screen.getByRole('button', { name: /同步工具/ }));

        await waitFor(() => expect(mockApi.listTools).toHaveBeenCalledWith(1));
    });

    /**
     * 钉住自查时发现并修复的一个活缺陷：fetchServers 最初写成「仅当 !selected
     * 才自动选中」，导致 sync/toggle/编辑之后 selected 一直指向调用前的旧对象——
     * 同步明明成功了，详情卡头部却因为 selected 没跟着换成刷新后的新对象，
     * 继续显示旧的 lastSyncError、lastSyncedAt 也不会更新。
     *
     * 变异实验：把 fetchServers 里的 `setSelected((prev) => {...})` 改回
     * `if (res.data.length > 0 && !selected) { setSelected(res.data[0]); }`
     * （旧写法），本条必须转红——警示条不会消失，"上次同步"文案也不会更新。
     */
    it('同步成功后刷新详情卡的 lastSyncedAt，并清除旧的失败提示', async () => {
        const stale: McpServer = { ...server1, lastSyncError: '上次连接超时' };
        const refreshed: McpServer = { ...server1, lastSyncedAt: '2026-08-21T10:00:00', lastSyncError: null };
        mockApi.listServers
            .mockResolvedValueOnce(ok([stale]))
            .mockResolvedValueOnce(ok([refreshed]));
        mockApi.syncTools.mockResolvedValue(ok([]));
        await renderPage();
        await waitFor(() => expect(screen.getByText(/上次同步失败：上次连接超时/)).toBeInTheDocument());

        fireEvent.click(screen.getByRole('button', { name: /同步工具/ }));

        await waitFor(() => expect(screen.getByText('上次同步：2026-08-21 10:00:00')).toBeInTheDocument());
        expect(screen.queryByText(/上次同步失败/)).not.toBeInTheDocument();
    });
});

describe('McpServerPage 工具只读标注', () => {
    /**
     * 约束 2（CLAUDE.md）：没有批量按钮，接一个 20 工具的 server 要点 20 次，
     * 用户会转而要求"自动信任远端"——而 readOnlyHint 是远端自报值，不可作安全判据。
     * 变异实验：把 McpToolList.tsx 里 `tools.filter((t) => t.readOnlyHint === true)`
     * 改成 `tools`（全选而非只选建议项），本条必须转红——会连 id=11 也一起传进去，
     * 下面 `not.toHaveBeenCalledWith(expect.arrayContaining([11]), ...)` 会失败。
     */
    it('"按远端建议全选只读"只标注 readOnlyHint 为 true 的工具，不误伤其余工具', async () => {
        mockApi.listTools.mockResolvedValue(ok([toolWithHint, toolWithoutHint]));
        mockApi.markReadOnly.mockResolvedValue(ok(1));
        await renderPage();
        await waitFor(() => expect(screen.getByText('query_order')).toBeInTheDocument());

        fireEvent.click(screen.getByRole('button', { name: '按远端建议全选只读' }));

        await waitFor(() => expect(mockApi.markReadOnly).toHaveBeenCalledWith([10], true));
        expect(mockApi.markReadOnly).not.toHaveBeenCalledWith(expect.arrayContaining([11]), expect.anything());
    });

    it('没有任何 readOnlyHint 建议时，批量按钮禁用', async () => {
        mockApi.listTools.mockResolvedValue(ok([toolWithoutHint]));
        await renderPage();
        await waitFor(() => expect(screen.getByText('cancel_order')).toBeInTheDocument());

        expect(screen.getByRole('button', { name: '按远端建议全选只读' })).toBeDisabled();
    });

    it('单个工具的只读开关只携带这一个 id 调用 markReadOnly', async () => {
        mockApi.listTools.mockResolvedValue(ok([toolWithoutHint]));
        mockApi.markReadOnly.mockResolvedValue(ok(1));
        await renderPage();
        await waitFor(() => expect(screen.getByText('cancel_order')).toBeInTheDocument());

        fireEvent.click(screen.getByRole('switch', { name: '只读-cancel_order' }));

        await waitFor(() => expect(mockApi.markReadOnly).toHaveBeenCalledWith([11], true));
    });

    // 变异实验：把 McpServerPage.tsx handleMarkReadOnly 里的 throwIfFailed(res) 删掉，
    // 本条必须转红——toast 一次都不会被调用，业务错误被静默吞掉。
    it('标注只读返回业务错误时报错而非静默', async () => {
        mockApi.listTools.mockResolvedValue(ok([toolWithoutHint]));
        mockApi.markReadOnly.mockResolvedValue(fail('工具已下线，无法标注'));
        await renderPage();
        await waitFor(() => expect(screen.getByText('cancel_order')).toBeInTheDocument());

        fireEvent.click(screen.getByRole('switch', { name: '只读-cancel_order' }));

        await waitFor(() =>
            expect(mockToast).toHaveBeenCalledWith(
                expect.objectContaining({ variant: 'destructive', description: '工具已下线，无法标注' }),
            ),
        );
    });

    // 约束 3：DEPRECATED 的工具（远端已下线）显示徽标且禁用其只读开关——标了也没用。
    it('DEPRECATED 工具显示徽标并禁用只读开关', async () => {
        mockApi.listTools.mockResolvedValue(ok([toolDeprecated]));
        await renderPage();
        await waitFor(() => expect(screen.getByText('legacy_query')).toBeInTheDocument());

        expect(screen.getByText('DEPRECATED')).toBeInTheDocument();
        expect(screen.getByRole('switch', { name: '只读-legacy_query' })).toBeDisabled();
    });
});

describe('McpServerPage 删除', () => {
    // 约束 4：删除确认弹窗先调 usage(id) 显示「正被 N 个技能使用」
    it('点击删除先查使用情况，弹窗里显示技能使用数', async () => {
        mockApi.usage.mockResolvedValue(ok({ skillCount: 3 }));
        await renderPage();

        fireEvent.click(screen.getByRole('button', { name: '删除' }));

        await waitFor(() => expect(mockApi.usage).toHaveBeenCalledWith(1));
        await waitFor(() => expect(screen.getByText('该服务的工具正被 3 个技能使用。')).toBeInTheDocument());
    });

    // 变异实验：把 McpServerPage.tsx handleConfirmDelete 里的 throwIfFailed(res) 删掉，
    // 本条必须转红——弹窗会被当成功关闭（deleteTarget 置空），用户毫无察觉删除其实失败了。
    it('删除返回业务错误时报错，且不静默关闭弹窗', async () => {
        mockApi.deleteServer.mockResolvedValue(fail('该服务仍被技能引用，无法删除'));
        await renderPage();

        fireEvent.click(screen.getByRole('button', { name: '删除' }));
        await waitFor(() => expect(mockApi.usage).toHaveBeenCalledWith(1));

        const dialog = screen.getByRole('dialog');
        fireEvent.click(within(dialog).getByRole('button', { name: '删除' }));

        await waitFor(() =>
            expect(mockToast).toHaveBeenCalledWith(
                expect.objectContaining({ variant: 'destructive', description: '该服务仍被技能引用，无法删除' }),
            ),
        );
        // 失败时弹窗不该被关闭：删除确认弹窗（含「正被 N 个技能使用」文案）仍在
        expect(screen.getByRole('dialog')).toBeInTheDocument();
    });
});

describe('McpServerPage 数值输入清空', () => {
    /**
     * 复核终审 Finding 4：`parseInt('', 10)` 是 `NaN`，`JSON.stringify` 会把它序列化成
     * `null`；create 路径上显式的 JSON null 会覆盖实体的 `@Builder.Default`，撞上
     * `timeoutMs`/`maxResponseChars` 的 `nullable = false` 约束，后端 500 而非字段级错误提示
     * （update 路径无害，控制器的 `!= null` 守卫会跳过，仅 create 直接收实体反序列化）。
     *
     * 变异实验：把 onChange 里的 `Number.isNaN(v) ? undefined : v` 改回裸
     * `parseInt(e.target.value, 10)`，本条必须转红——payload.timeoutMs 会是 `NaN` 而非
     * `undefined`。
     */
    it('清空超时输入框后保存，提交的是 undefined 而非 NaN', async () => {
        mockApi.createServer.mockResolvedValue(ok({ ...server1, id: 2 }));
        await renderPage();

        fireEvent.click(screen.getByRole('button', { name: /添加 MCP 服务/ }));
        const dialog = screen.getByRole('dialog');
        fireEvent.change(within(dialog).getByPlaceholderText('如：订单系统'), { target: { value: '新服务' } });
        fireEvent.change(within(dialog).getByPlaceholderText('order'), { target: { value: 'neworder' } });
        fireEvent.change(within(dialog).getByPlaceholderText('https://example.com/mcp'), {
            target: { value: 'https://x/mcp' },
        });
        // 两个 number 输入框：超时(ms) 在前，响应截断长度在后
        const [timeoutInput] = within(dialog).getAllByRole('spinbutton');
        fireEvent.change(timeoutInput, { target: { value: '' } });

        fireEvent.click(within(dialog).getByRole('button', { name: '保存' }));

        await waitFor(() => expect(mockApi.createServer).toHaveBeenCalled());
        const payload = mockApi.createServer.mock.calls[0][0] as Partial<McpServer>;
        expect(payload.timeoutMs).toBeUndefined();
        expect(Number.isNaN(payload.timeoutMs)).toBe(false);
    });
});

describe('McpServerPage 传输方式', () => {
    // R7：SSE 传输本期不可用（后端 McpConnectionFactory.create 对 SSE 直接抛
    // UnsupportedOperationException：WebFluxSseClientTransport 没有 base-URL setter，
    // 连不上），前端必须禁用该选项而非隐藏——用户需要知道协议本身合法，只是本期不支持。
    it('创建对话框里 SSE 传输选项被禁用，Streamable HTTP 可选', async () => {
        await renderPage();

        fireEvent.click(screen.getByRole('button', { name: /添加 MCP 服务/ }));

        const sseOption = screen.getByRole('option', { name: /SSE/ });
        expect(sseOption).toHaveAttribute('aria-disabled', 'true');
        const httpOption = screen.getByRole('option', { name: 'Streamable HTTP' });
        expect(httpOption).toHaveAttribute('aria-disabled', 'false');
    });
});

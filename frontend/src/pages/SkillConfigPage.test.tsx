import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { AiSkill, AiTool } from '@/api/chat-tool';
import type { McpServer, McpTool } from '@/api/mcp';
import SkillConfigPage from './SkillConfigPage';

/**
 * SkillConfigPage 回归测试：MCP 工具勾选（Task 12）。
 *
 * ⚠️ 关键约束（CLAUDE.md）：MCP 工具列表只列出 `readOnly === true 且 status === 'ACTIVE'`
 * 的，必须与后端 `ChatToolService.resolveMcpCallbacks` 的过滤条件一致——列出勾不上的
 * 工具只会让人以为是 bug。本文件额外把「所属 server 已停用」也当作排除条件，因为
 * `resolveMcpCallbacks` 同样会跳过停用 server 下的工具（同一个「列出但用不了」的问题，
 * 只是换了个轴）。
 *
 * 桩在 api service 模块层，同 ToolConfigPage.test.tsx / McpServerPage.test.tsx 的既定做法。
 * Dialog 不桩：真实 Radix Dialog 在 jsdom 下能正常渲染进 document.body。
 */

const { mockSkillApi, mockToolApi, mockMcpApi, mockToast } = vi.hoisted(() => ({
    mockSkillApi: {
        list: vi.fn(),
        getById: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
        remove: vi.fn(),
    },
    mockToolApi: {
        list: vi.fn(),
        getById: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
        remove: vi.fn(),
    },
    mockMcpApi: {
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

vi.mock('@/api/chat-tool', () => ({
    aiSkillService: mockSkillApi,
    aiToolService: mockToolApi,
}));
vi.mock('@/api/mcp', () => ({ mcpApi: mockMcpApi }));
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: mockToast }) }));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-21T00:00:00',
});

const skill: AiSkill = {
    id: 5,
    name: '退货处理',
    description: '处理退货场景',
    content: '当用户咨询退货时先查订单',
    toolIds: [],
    mcpToolIds: [],
    enabled: true,
    createdAt: '2026-08-21T00:00:00',
    updatedAt: '2026-08-21T00:00:00',
};

const orderServer: McpServer = {
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

const shippingServerDisabled: McpServer = {
    ...orderServer,
    id: 2,
    name: '物流系统',
    code: 'ship',
    enabled: false,
};

// 满足两条件：readOnly === true 且 status === 'ACTIVE'
const toolQualified: McpTool = {
    id: 10, mcpServerId: 1, name: 'query_order', description: '查订单', status: 'ACTIVE', readOnly: true,
};
// 违反 readOnly 条件
const toolNotReadOnly: McpTool = {
    id: 11, mcpServerId: 1, name: 'cancel_order', description: '取消订单', status: 'ACTIVE', readOnly: false,
};
// 违反 status 条件
const toolDeprecated: McpTool = {
    id: 12, mcpServerId: 1, name: 'legacy_query', description: '旧接口', status: 'DEPRECATED', readOnly: true,
};
// 工具本身满足两条件，但所属 server 已停用
const toolFromDisabledServer: McpTool = {
    id: 20, mcpServerId: 2, name: 'list_orders', description: '查物流单', status: 'ACTIVE', readOnly: true,
};

const renderPage = async () => {
    const result = render(<SkillConfigPage />);
    await waitFor(() => expect(screen.getByText('退货处理')).toBeInTheDocument());
    return result;
};

const openEditDialog = async () => {
    fireEvent.click(screen.getByRole('button', { name: '编辑技能' }));
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
};

beforeEach(() => {
    vi.clearAllMocks();
    mockSkillApi.list.mockResolvedValue(ok([skill]));
    mockToolApi.list.mockResolvedValue(ok<AiTool[]>([]));
    mockMcpApi.listServers.mockResolvedValue(ok([orderServer, shippingServerDisabled]));
    mockMcpApi.listTools.mockImplementation((id: number) => {
        if (id === orderServer.id) {
            return Promise.resolve(ok([toolQualified, toolNotReadOnly, toolDeprecated]));
        }
        if (id === shippingServerDisabled.id) {
            return Promise.resolve(ok([toolFromDisabledServer]));
        }
        return Promise.resolve(ok<McpTool[]>([]));
    });
});

describe('SkillConfigPage MCP 工具勾选', () => {
    it('只渲染 readOnly 且 ACTIVE 且所属 server 已启用的 MCP 工具', async () => {
        await renderPage();
        await openEditDialog();

        // 满足条件的工具：出现
        expect(screen.getByLabelText('order__query_order')).toBeInTheDocument();

        // 三类不满足条件的工具：都不出现——只断言"满足条件的出现了"是永真的（不过滤也会出现）
        expect(screen.queryByLabelText('order__cancel_order')).not.toBeInTheDocument();
        expect(screen.queryByLabelText('order__legacy_query')).not.toBeInTheDocument();
        expect(screen.queryByLabelText('ship__list_orders')).not.toBeInTheDocument();
    });

    it('保存 skill 时把勾选的 MCP 工具作为 mcpToolIds 一并提交', async () => {
        mockSkillApi.update.mockResolvedValue(ok(skill));
        await renderPage();
        await openEditDialog();

        fireEvent.click(screen.getByLabelText('order__query_order'));
        fireEvent.click(screen.getByRole('button', { name: '保存' }));

        await waitFor(() =>
            expect(mockSkillApi.update).toHaveBeenCalledWith(
                5,
                expect.objectContaining({ mcpToolIds: [10] }),
            ),
        );
    });
});

import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { AiSkill, AiTool } from '@/api/chat-tool';
import type { McpServer } from '@/api/mcp';
import { aiSkillService, aiToolService } from '@/api/chat-tool';
import { mcpApi } from '@/api/mcp';
import PackageExportDialog from './PackageExportDialog';

/**
 * 勾选列表只能列出平台管理员自己建的行（`tenantId == null`）。
 *
 * <p>这三个列表接口是**应用级**的，而 `TenantDataAspect` 对 PLATFORM_ADMIN 整体不启用
 * `@Filter`——平台管理员选中某个租户的应用时，接口返回的是**那个租户的私有配置**。真机验证
 * 时就撞上了：导出对话框把租户装出来的工具铺在列表里，勾了才被后端拒绝，而界面上完全无从
 * 区分哪个能导。判据必须与后端 `PackageManifestBuilder` 一致。
 *
 * <p>⚠️ 变异实验：把 `onlyPlatformOwned(...)` 换回 `toolRes.data ?? []`，本文件必须转红。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));

vi.mock('@/api/chat-tool', () => ({
    aiToolService: { list: vi.fn() },
    aiSkillService: { list: vi.fn() },
}));

vi.mock('@/api/mcp', () => ({
    mcpApi: { listServers: vi.fn() },
}));

vi.mock('@/api/toolPackage', () => ({
    toolPackageService: { exportPackage: vi.fn() },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200, message: 'ok', data, timestamp: '2026-09-02T00:00:00',
});

const tool = (id: number, name: string, tenantId: number | null): AiTool => ({
    id, name, description: `${name} 的描述`, inputSchema: null, httpMethod: 'GET',
    urlTemplate: 'https://example.com/x', authHeader: null, timeoutMs: 5000,
    maxResponseChars: 4000, enabled: true, tenantId,
    createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00',
});

const skill = (id: number, name: string, tenantId: number | null): AiSkill => ({
    id, name, description: null, content: null, toolIds: [], mcpToolIds: [],
    enabled: true, tenantId, createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00',
});

const server = (id: number, name: string, code: string, tenantId: number | null): McpServer => ({
    id, name, code, transport: 'STREAMABLE_HTTP', url: 'https://example.com/mcp',
    timeoutMs: 30000, maxResponseChars: 4000, enabled: true, tenantId,
});

describe('PackageExportDialog 的勾选列表', () => {
    beforeEach(() => {
        vi.mocked(aiToolService.list).mockReset().mockResolvedValue(
            ok([tool(1, '平台自己的工具', null), tool(2, '租户私有的工具', 16)]));
        vi.mocked(aiSkillService.list).mockReset().mockResolvedValue(
            ok([skill(3, '平台自己的技能', null), skill(4, '租户私有的技能', 16)]));
        vi.mocked(mcpApi.listServers).mockReset().mockResolvedValue(
            ok([server(5, '平台自己的服务', 'mine', null), server(6, '租户私有的服务', 'theirs', 16)]));
    });

    it('只列出 tenantId 为 null 的行', async () => {
        render(<PackageExportDialog open onOpenChange={() => {}} onExported={() => {}} />);

        await waitFor(() => expect(screen.getByText('平台自己的工具')).toBeInTheDocument());
        expect(screen.getByText('平台自己的技能')).toBeInTheDocument();
        expect(screen.getByText(/平台自己的服务/)).toBeInTheDocument();
    });

    it('不列出归属租户的行', async () => {
        render(<PackageExportDialog open onOpenChange={() => {}} onExported={() => {}} />);

        // 等平台那条先出现，确保三份列表都已落到 state——否则「查不到租户的行」
        // 会因为「数据还没到」而永真，断言退化成什么都没验。
        await waitFor(() => expect(screen.getByText('平台自己的工具')).toBeInTheDocument());

        expect(screen.queryByText('租户私有的工具')).not.toBeInTheDocument();
        expect(screen.queryByText('租户私有的技能')).not.toBeInTheDocument();
        expect(screen.queryByText(/租户私有的服务/)).not.toBeInTheDocument();
    });

    it('全部行都归属租户时给出「没有你自己建的」空状态', async () => {
        vi.mocked(aiToolService.list).mockResolvedValue(ok([tool(2, '租户私有的工具', 16)]));

        render(<PackageExportDialog open onOpenChange={() => {}} onExported={() => {}} />);

        await waitFor(() => expect(
            screen.getByText(/当前应用下没有你自己建的工具/)).toBeInTheDocument());
        expect(screen.queryByText('租户私有的工具')).not.toBeInTheDocument();
    });
});

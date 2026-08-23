import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { OpenApiParseResponse, OpenApiToolCandidate } from '@/api/chat-tool';
import { openApiImportService } from '@/api/chat-tool';
import OpenApiImportDialog from './OpenApiImportDialog';

/**
 * 导入对话框的两条写操作。守的是本仓库的全局约定：业务异常是「HTTP 200 + body.code」，
 * axios 不会 reject，写操作不显式判 code 就会把后端错误静默吞掉（用户点按钮毫无反应）。
 *
 * ⚠️ 不把 Dialog 桩成 () => null：本组件的解析与导入都由对话框内部的回调驱动，桩成空
 * 组件会让这两条路径静默零覆盖而测试照样全绿（CrawlSiteDialog 的前车之鉴）。这里直接用
 * 真实的 Radix Dialog——它在 jsdom 下能渲染，只是 portal 到 body。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/chat-tool', () => ({
    openApiImportService: {
        parse: vi.fn(),
        import: vi.fn(),
    },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200, message: 'success', data, timestamp: '2026-08-23T00:00:00',
});

const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400, message, data: null as unknown as T, timestamp: '2026-08-23T00:00:00',
});

const candidate = (name: string, conflict = false): OpenApiToolCandidate => ({
    name,
    description: '查订单',
    httpMethod: 'GET',
    urlTemplate: 'https://api.example.com/orders/{orderId}',
    inputSchema: '{"type":"object","properties":{}}',
    warnings: [],
    nameConflict: conflict,
});

const flush = async () => {
    await act(async () => { await Promise.resolve(); });
};

const onImported = vi.fn();

const renderDialog = () =>
    render(<OpenApiImportDialog open onOpenChange={() => {}} onImported={onImported} />);

beforeEach(() => {
    toastSpy.mockReset();
    onImported.mockReset();
    vi.mocked(openApiImportService.parse).mockReset();
    vi.mocked(openApiImportService.import).mockReset();
});

afterEach(() => {
    vi.clearAllMocks();
});

describe('OpenApiImportDialog', () => {
    it('解析失败时弹 destructive toast 且不显示候选列表', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(
            fail<OpenApiParseResponse>('仅支持 OpenAPI 3.x，检测到 Swagger 2.0'));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '解析失败',
            description: '仅支持 OpenAPI 3.x，检测到 Swagger 2.0',
        }));
        expect(screen.queryByLabelText('名称 0')).not.toBeInTheDocument();
    });

    it('解析成功后展示候选与全局 warning', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('getOrder')],
            globalWarnings: ['已跳过 DELETE /orders/{id}：本平台的工具只支持 GET 与 POST'],
            truncated: false,
        }));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();

        expect(screen.getByDisplayValue('getOrder')).toBeInTheDocument();
        // 全局 warning 必须可见：被跳过的端点没有候选行可挂，不显示就等于无声消失
        expect(screen.getByText(/已跳过 DELETE/)).toBeInTheDocument();
    });

    it('导入失败时弹 destructive toast 且不通知父组件刷新', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('getOrder')], globalWarnings: [], truncated: false,
        }));
        vi.mocked(openApiImportService.import).mockResolvedValue(
            fail('工具名「getOrder」已存在，请改名后再导入'));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();
        fireEvent.click(screen.getByRole('button', { name: '导入选中项' }));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '导入失败',
            description: '工具名「getOrder」已存在，请改名后再导入',
        }));
        expect(onImported).not.toHaveBeenCalled();
    });

    it('导入成功后通知父组件刷新列表', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('getOrder')], globalWarnings: [], truncated: false,
        }));
        vi.mocked(openApiImportService.import).mockResolvedValue(ok([]));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();
        fireEvent.click(screen.getByRole('button', { name: '导入选中项' }));
        await flush();

        expect(onImported).toHaveBeenCalledTimes(1);
        expect(toastSpy).not.toHaveBeenCalledWith(expect.objectContaining({ variant: 'destructive' }));
    });

    it('名字冲突的候选默认不勾选', async () => {
        // 默认全选会让用户一路点下去撞上整批拒绝，且看不出是哪一条的问题
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('dup', true)], globalWarnings: [], truncated: false,
        }));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();

        expect(screen.getByLabelText('选择 dup')).not.toBeChecked();
        expect(screen.getByText('名称与已有工具重复，请修改后再导入')).toBeInTheDocument();
    });

    /**
     * 守的是上一轮修掉的 Critical：「取消」按钮曾经直接调 onOpenChange prop，
     * 绕过 handleOpenChange 里的 reset()——重开对话框时旧候选残留，
     * 「导入选中项」立即可点，可能把已放弃的一批工具误提交上去。
     *
     * 用 rerender 切换 open 来模拟「关闭又重新打开」：本组件的候选列表是内部
     * state，不随 open prop 变化而自动清空，唯一能清空它的就是 reset()。
     *
     * 变异验证：把「取消」按钮的 onClick 从 `() => handleOpenChange(false)`
     * 改回直接调 `() => onOpenChange(false)`，本条必须转红——因为 reset()
     * 被跳过，rerender 之后候选行仍然在。
     */
    it('取消按钮会复位状态，重新打开对话框不残留旧候选', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('getOrder')], globalWarnings: [], truncated: false,
        }));

        const { rerender } = renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();
        expect(screen.getByDisplayValue('getOrder')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: '取消' }));
        await flush();

        // 模拟父组件把 open 收起又重新打开——真实场景下由父组件自己的 open state 驱动
        rerender(<OpenApiImportDialog open={false} onOpenChange={() => {}} onImported={onImported} />);
        rerender(<OpenApiImportDialog open onOpenChange={() => {}} onImported={onImported} />);

        expect(screen.queryByDisplayValue('getOrder')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: '导入选中项' })).toBeDisabled();
    });
});

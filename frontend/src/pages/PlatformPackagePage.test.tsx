import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { ToolPackage } from '@/api/toolPackage';
import { toolPackageService } from '@/api/toolPackage';
import PlatformPackagePage from './PlatformPackagePage';

/**
 * 写操作的业务错误不静默（「HTTP 200 + body.code」那条全局约定）。
 *
 * 挑「发布」作为代表路径：它是本页最短的一条写操作——点一下按钮直达
 * `toolPackageService.publish`，断言能精准落在「业务错误有没有被吐出来」上。
 *
 * ⚠️ 变异实验：把 `handlePublish` 里的 `throwIfFailed(res);` 删掉，本条必须转红。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));

// 导出对话框会拉三份列表，与本条要验的东西无关；桩成「只在 open 时渲染」的极简组件。
// ⚠️ 不能桩成 () => null——它是靠 onExported 回调驱动父组件逻辑的，桩空会让那条路径静默零覆盖。
vi.mock('@/components/pkg/PackageExportDialog', () => ({
    default: ({ open, onExported }: { open: boolean; onExported: (w: string[]) => void }) =>
        open ? <button onClick={() => onExported(['告警一'])}>触发导出完成</button> : null,
}));

vi.mock('@/api/toolPackage', () => ({
    toolPackageService: {
        listAll: vi.fn(),
        getById: vi.fn(),
        exportPackage: vi.fn(),
        update: vi.fn(),
        publish: vi.fn(),
        archive: vi.fn(),
        remove: vi.fn(),
    },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200, message: 'success', data, timestamp: '2026-08-25T00:00:00',
});
const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400, message, data: null as unknown as T, timestamp: '2026-08-25T00:00:00',
});

const draft: ToolPackage = {
    id: 1, code: 'weather_pack', name: '天气能力包', description: '查天气',
    status: 'DRAFT', manifest: '{"manifestVersion":1}',
};

const flush = async () => { await act(async () => { await Promise.resolve(); }); };

const renderPage = async () => {
    const result = render(<PlatformPackagePage />);
    await flush();
    return result;
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(toolPackageService.listAll).mockReset().mockResolvedValue(ok([draft]));
    vi.mocked(toolPackageService.publish).mockReset();
});

afterEach(() => { vi.clearAllMocks(); });

describe('PlatformPackagePage 写操作的业务错误不静默', () => {
    it('发布时后端返业务错误 → 弹 destructive toast 且不刷新列表', async () => {
        vi.mocked(toolPackageService.publish)
            .mockResolvedValue(fail<ToolPackage>('manifest 不是合法的 JSON'));

        await renderPage();
        expect(screen.getByText('天气能力包')).toBeInTheDocument();

        fireEvent.click(screen.getByText('发布'));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '发布失败',
            description: 'manifest 不是合法的 JSON',
        }));
        expect(toolPackageService.listAll).toHaveBeenCalledTimes(1);
    });

    it('发布成功 → 弹成功 toast 并重新拉列表', async () => {
        vi.mocked(toolPackageService.publish)
            .mockResolvedValue(ok({ ...draft, status: 'PUBLISHED' }));

        await renderPage();
        fireEvent.click(screen.getByText('发布'));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ title: '已发布' }));
        expect(toastSpy).not.toHaveBeenCalledWith(
            expect.objectContaining({ variant: 'destructive' }));
        expect(toolPackageService.listAll).toHaveBeenCalledTimes(2);
    });

    it('导出返回的 warnings 必须显眼展示，不能只在 toast 里一闪而过', async () => {
        await renderPage();
        fireEvent.click(screen.getByText('导出能力包'));
        await flush();
        fireEvent.click(screen.getByText('触发导出完成'));
        await flush();

        expect(screen.getByText('告警一')).toBeInTheDocument();
    });
});

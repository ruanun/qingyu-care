import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { PackageDetail, PackageInstallResult } from '@/api/toolPackage';
import { toolPackageService } from '@/api/toolPackage';
import PackageStorePage from './PackageStorePage';

/**
 * 安装路径上两件必须成立的事：
 * 1. 业务错误（名字冲突 / 必填项没填 / 没选应用）弹 destructive toast，不静默；
 * 2. 部分成功的 warnings **留在页面上**，不是只弹一次 toast——那条「请手动同步后再勾选工具」
 *    是要人照着做的。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));

vi.mock('@/api/toolPackage', () => ({
    toolPackageService: {
        listStore: vi.fn(),
        detail: vi.fn(),
        install: vi.fn(),
    },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200, message: 'success', data, timestamp: '2026-08-25T00:00:00',
});
const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400, message, data: null as unknown as T, timestamp: '2026-08-25T00:00:00',
});

const pkg: PackageDetail = {
    code: 'weather_pack',
    name: '天气能力包',
    description: '查天气',
    variables: [
        { name: 'WEATHER_AUTH', label: '鉴权头', required: true, secret: true, help: '形如 Authorization: Bearer xxx' },
    ],
    toolNames: ['weather_query'],
    mcpServerNames: [],
    skillNames: ['天气助手'],
};

const flush = async () => { await act(async () => { await Promise.resolve(); }); };

const renderPage = async () => {
    const result = render(<PackageStorePage />);
    await flush();
    return result;
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(toolPackageService.listStore).mockReset().mockResolvedValue(ok([pkg]));
    vi.mocked(toolPackageService.install).mockReset();
});

afterEach(() => { vi.clearAllMocks(); });

describe('PackageStorePage 安装路径', () => {
    it('安装时后端返业务错误 → 弹 destructive toast', async () => {
        vi.mocked(toolPackageService.install)
            .mockResolvedValue(fail<PackageInstallResult>('以下名称在当前应用里已存在：工具「weather_query」'));

        await renderPage();
        fireEvent.click(screen.getByText('安装'));
        await flush();

        // 对话框的确认按钮文案是「确认安装」，与列表上触发安装的「安装」按钮区分开，
        // 避免同名按钮定位歧义
        fireEvent.click(screen.getByText('确认安装'));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '安装失败',
            description: '以下名称在当前应用里已存在：工具「weather_query」',
        }));
    });

    it('secret 变量用 password 输入框，永不明文回显', async () => {
        await renderPage();
        fireEvent.click(screen.getByText('安装'));
        await flush();

        const input = screen.getByLabelText(/鉴权头/) as HTMLInputElement;
        expect(input.type).toBe('password');
    });

    it('部分成功的 warnings 留在页面上，不是只弹一次 toast', async () => {
        vi.mocked(toolPackageService.install).mockResolvedValue(ok<PackageInstallResult>({
            toolNames: ['weather_query'],
            mcpServerNames: ['Context7'],
            skillNames: ['天气助手'],
            warnings: ['MCP 服务「Context7」的工具清单同步失败，请到 MCP 服务页手动同步一次'],
        }));

        await renderPage();
        fireEvent.click(screen.getByText('安装'));
        await flush();
        fireEvent.click(screen.getByText('确认安装'));
        await flush();

        expect(screen.getByText(/手动同步一次/)).toBeInTheDocument();
    });
});

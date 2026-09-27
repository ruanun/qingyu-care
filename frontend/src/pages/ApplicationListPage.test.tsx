import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import type { ApiResponse, Application } from '@/types';
import { applicationService } from '@/api/application';
import ApplicationListPage from './ApplicationListPage';

/**
 * 应用头像此前**两头都断**：表单里没有输入框（没人设置得了），widget 也不渲染。
 * 这里守管理端这一头：编辑时回显、改了之后真的随请求提交、后端拒绝时报出来。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));
vi.mock('@/api/application', () => ({
    applicationService: { list: vi.fn(), update: vi.fn(), create: vi.fn(), delete: vi.fn(), regenerateSecret: vi.fn() },
}));
// 下拉菜单的 portal / pointer 事件在 jsdom 下难以驱动；桩成「直接渲染菜单项、保留 onClick」，
// 而不是 () => null——「编辑配置」正是经菜单项的 onClick 打开弹窗的，桩空了这条路径就零覆盖
vi.mock('@/components/ui/dropdown-menu', () => ({
    DropdownMenu: ({ children }: { children: ReactNode }) => <>{children}</>,
    DropdownMenuTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
    DropdownMenuContent: ({ children }: { children: ReactNode }) => <>{children}</>,
    DropdownMenuItem: ({ children, onClick }: { children: ReactNode; onClick?: () => void }) => (
        <button type="button" onClick={onClick}>{children}</button>
    ),
    DropdownMenuLabel: ({ children }: { children: ReactNode }) => <>{children}</>,
    DropdownMenuSeparator: () => null,
}));

const res = <T,>(code: number, data: T, message = 'success'): ApiResponse<T> =>
    ({ code, message, data, timestamp: '2026-09-25T00:00:00' });

const app = {
    id: 20, name: '云舟商城客服', description: '', appKey: 'app_x', enabled: true,
    avatar: 'https://cdn.example.com/old.png', welcomeMessage: '你好', themeColor: '#0d9488',
} as Application;

const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

const openEdit = async () => {
    render(<ApplicationListPage />);
    await flush();
    fireEvent.click(screen.getByRole('button', { name: /编辑配置/ }));
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(applicationService.list).mockReset().mockResolvedValue(res(200, [app]));
    vi.mocked(applicationService.update).mockReset();
});

describe('ApplicationListPage 应用头像', () => {
    it('编辑时回显已有头像地址，修改后随更新请求提交', async () => {
        vi.mocked(applicationService.update).mockResolvedValue(res(200, app));
        await openEdit();

        const input = screen.getByPlaceholderText('https://example.com/logo.png');
        expect(input).toHaveValue('https://cdn.example.com/old.png');

        fireEvent.change(input, { target: { value: 'https://cdn.example.com/new.png' } });
        fireEvent.click(screen.getByRole('button', { name: '保存配置' }));
        await flush();

        expect(applicationService.update).toHaveBeenCalledWith(20, expect.objectContaining({ avatar: 'https://cdn.example.com/new.png' }));
        expect(toastSpy).toHaveBeenCalledWith({ title: '更新成功' });
    });

    it('后端拒绝非法头像地址时弹 destructive toast，且弹窗不关', async () => {
        vi.mocked(applicationService.update).mockResolvedValue(res(400, null as never, '头像地址必须是 http:// 或 https:// 开头的图片链接'));
        await openEdit();

        fireEvent.click(screen.getByRole('button', { name: '保存配置' }));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive', description: '头像地址必须是 http:// 或 https:// 开头的图片链接',
        }));
        expect(screen.getByPlaceholderText('https://example.com/logo.png')).toBeInTheDocument();
    });
});

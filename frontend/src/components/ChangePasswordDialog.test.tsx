import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { userService } from '@/api/user';
import ChangePasswordDialog from './ChangePasswordDialog';

/**
 * 本人修改密码（2026-09-30 安全审查 M3）。守三件事：
 * 1. 业务错误（当前密码不对，HTTP 200 + code 400）必须弹 destructive toast，弹窗不关——
 *    判漏 `code` 时用户点了按钮毫无反应；
 * 2. 两次新密码不一致时根本不发请求；
 * 3. 成功才关弹窗，且请求体带上了当前密码。
 *
 * Radix Dialog 的 portal / focus trap 在 jsdom 下行为特殊，桩成「只在 open 时渲染子节点」的极简版本。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/user', () => ({
    userService: { changeMyPassword: vi.fn() },
}));

vi.mock('@/components/ui/dialog', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return {
        Dialog: ({ open, children }: { open: boolean; children?: ReactNode }) => (open ? <div>{children}</div> : null),
        DialogContent: Pass,
        DialogDescription: Pass,
        DialogFooter: Pass,
        DialogHeader: Pass,
        DialogTitle: Pass,
    };
});

const changeMyPassword = vi.mocked(userService.changeMyPassword);

function fill(oldPwd: string, newPwd: string, confirm: string) {
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: oldPwd } });
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: newPwd } });
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: confirm } });
}

async function submit() {
    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: '确认修改' }));
    });
}

describe('ChangePasswordDialog', () => {
    beforeEach(() => {
        toastSpy.mockReset();
        changeMyPassword.mockReset();
    });

    it('当前密码不对时弹出业务错误且不关闭弹窗', async () => {
        changeMyPassword.mockResolvedValue({ code: 400, message: '当前密码不正确', data: undefined, timestamp: '' });
        const onOpenChange = vi.fn();
        render(<ChangePasswordDialog open onOpenChange={onOpenChange} />);

        fill('guess', 'NewPass456', 'NewPass456');
        await submit();

        expect(changeMyPassword).toHaveBeenCalledWith({ oldPassword: 'guess', newPassword: 'NewPass456' });
        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive', description: '当前密码不正确',
        }));
        expect(onOpenChange).not.toHaveBeenCalled();
    });

    it('两次新密码不一致时不发请求', async () => {
        render(<ChangePasswordDialog open onOpenChange={vi.fn()} />);

        fill('OldPass123', 'NewPass456', 'NewPass789');
        await submit();

        expect(changeMyPassword).not.toHaveBeenCalled();
        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ variant: 'destructive' }));
    });

    it('成功后关闭弹窗', async () => {
        changeMyPassword.mockResolvedValue({ code: 200, message: 'ok', data: undefined, timestamp: '' });
        const onOpenChange = vi.fn();
        render(<ChangePasswordDialog open onOpenChange={onOpenChange} />);

        fill('OldPass123', 'NewPass456', 'NewPass456');
        await submit();

        expect(onOpenChange).toHaveBeenCalledWith(false);
        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ title: '密码已修改' }));
    });
});

import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { authService } from '@/api/auth';
import { AUTH_KEY } from '@/lib/authStorage';
import LoginPage from './LoginPage';

/**
 * 登录页「记住我」原先是一个没接任何逻辑的原生复选框，「忘记密码？」只是一段文字。
 * 这里走真实的 authStore + authStorage，只桩登录接口——断言 token 实际落在哪个 storage。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));
vi.mock('@/api/auth', () => ({ authService: { login: vi.fn(), register: vi.fn() } }));

const loginOk = {
    code: 200, message: 'ok', timestamp: '',
    data: { token: 'tok-login', userId: 1, username: 'demo', nickname: '演示', avatar: '', role: 'TENANT_ADMIN', tenantId: 1, tenantName: 't' },
};

// Radix Checkbox 位于 <form> 内时会渲染一个隐藏的原生 input 并用 ResizeObserver 量尺寸，jsdom 没有这个 API
globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
} as unknown as typeof ResizeObserver;

const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

const fillAndSubmit = async () => {
    fireEvent.change(screen.getAllByPlaceholderText('用户名')[0], { target: { value: 'demo' } });
    fireEvent.change(screen.getAllByPlaceholderText('密码')[0], { target: { value: 'pw' } });
    fireEvent.click(screen.getByRole('button', { name: /立即登录/ }));
    await flush();
};

beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    toastSpy.mockReset();
    vi.mocked(authService.login).mockReset().mockResolvedValue(loginOk as never);
});

describe('LoginPage 记住我', () => {
    it('取消勾选后登录 → token 只进 sessionStorage', async () => {
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        const box = screen.getByRole('checkbox', { name: '记住我' });
        expect(box).toBeChecked(); // 默认记住，保持勾选框引入前的行为

        fireEvent.click(box);
        await fillAndSubmit();

        expect(sessionStorage.getItem(AUTH_KEY)).toContain('tok-login');
        expect(localStorage.getItem(AUTH_KEY)).toBeNull();
    });

    it('保持勾选登录 → token 进 localStorage；下次打开登录页回显上次的选择', async () => {
        const first = render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await fillAndSubmit();
        expect(localStorage.getItem(AUTH_KEY)).toContain('tok-login');
        first.unmount();

        // 上一次选了「不记住」，重开登录页时应回显为未勾选
        localStorage.setItem('qingyu-remember', '0');
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        expect(screen.getByRole('checkbox', { name: '记住我' })).not.toBeChecked();
    });

    it('点「忘记密码？」提示联系管理员重置', () => {
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        fireEvent.click(screen.getByRole('button', { name: '忘记密码？' }));
        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ description: expect.stringContaining('成员管理') }));
    });
});

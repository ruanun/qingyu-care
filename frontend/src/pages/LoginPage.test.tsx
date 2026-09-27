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
vi.mock('@/api/auth', () => ({ authService: { login: vi.fn(), register: vi.fn(), getPublicConfig: vi.fn() } }));

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
    vi.mocked(authService.getPublicConfig).mockReset()
        .mockResolvedValue({ code: 200, message: 'ok', timestamp: '', data: { selfRegisterEnabled: false } } as never);
});

/**
 * 2026-09-25 安全扫描 M2：自助注册生产默认关闭。入口按后端配置显隐，
 * 拿不到配置（接口出错）时也按关闭处理——不能因为一次请求失败就把关闭的注册露出来。
 */
describe('LoginPage 注册入口', () => {
    it('后端关闭自助注册时不显示注册入口', async () => {
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        expect(screen.queryByRole('tab', { name: '免费注册' })).toBeNull();
        expect(screen.getByRole('button', { name: /立即登录/ })).toBeInTheDocument();
    });

    it('后端开放时显示注册入口', async () => {
        vi.mocked(authService.getPublicConfig).mockResolvedValue({ code: 200, message: 'ok', timestamp: '', data: { selfRegisterEnabled: true } } as never);
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        expect(screen.getByRole('tab', { name: '免费注册' })).toBeInTheDocument();
    });

    // 初始值只在「请求还没回来」这段时间起作用：前几条都等请求落地才断言，改初始值它们照样绿（变异验证过）
    it('配置还没返回时不显示注册入口', async () => {
        vi.mocked(authService.getPublicConfig).mockReturnValue(new Promise(() => {}) as never);
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        expect(screen.queryByRole('tab', { name: '免费注册' })).toBeNull();
    });

    it('拿不到配置时按关闭处理', async () => {
        vi.mocked(authService.getPublicConfig).mockRejectedValue(new Error('网络错误'));
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        expect(screen.queryByRole('tab', { name: '免费注册' })).toBeNull();
    });
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

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
vi.mock('@/api/auth', () => ({ authService: { login: vi.fn(), register: vi.fn(), getPublicConfig: vi.fn(), getCaptcha: vi.fn() } }));
// Turnstile 真组件要加载 Cloudflare 脚本；桩成「暴露 onToken 触发点 + 回显 resetSignal」，父组件那两条回调路径才有覆盖
vi.mock('@/components/TurnstileWidget', () => ({
    default: ({ onToken, resetSignal }: { onToken: (t: string | null) => void; resetSignal?: number }) => (
        <div>
            <span data-testid="turnstile-reset">{resetSignal ?? 0}</span>
            <button type="button" onClick={() => onToken('ts-token')}>完成人机验证</button>
        </div>
    ),
}));

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

describe('LoginPage 注册验证码', () => {
    const config = (captchaMode: string, turnstileSiteKey = '') => ({
        code: 200, message: 'ok', timestamp: '', data: { selfRegisterEnabled: true, captchaMode, turnstileSiteKey },
    });
    const captchaRes = (id: string) => ({ code: 200, message: 'ok', timestamp: '', data: { captchaId: id, image: `data:image/png;base64,${id}` } });
    const fail = { code: 400, message: '验证码错误或已过期', timestamp: '', data: null };

    // Radix Tabs 在 mousedown 时切换，click 不触发
    const openRegister = async () => {
        fireEvent.mouseDown(screen.getByRole('tab', { name: '免费注册' }), { button: 0 });
        await flush();
    };
    const fillRegister = () => {
        fireEvent.change(screen.getAllByPlaceholderText('用户名').at(-1)!, { target: { value: 'newbie' } });
        fireEvent.change(screen.getAllByPlaceholderText('密码').at(-1)!, { target: { value: 'pw123456' } });
    };
    const submitRegister = async () => {
        fireEvent.click(screen.getByRole('button', { name: /创建账号/ }));
        await flush();
        await flush();
    };

    beforeEach(() => {
        vi.mocked(authService.register).mockReset();
        vi.mocked(authService.getCaptcha).mockReset()
            .mockResolvedValueOnce(captchaRes('c1') as never)
            .mockResolvedValueOnce(captchaRes('c2') as never);
    });

    it('图形验证码：切到注册页才取图，只登录不占验证码', async () => {
        vi.mocked(authService.getPublicConfig).mockResolvedValue(config('IMAGE') as never);
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        expect(authService.getCaptcha).not.toHaveBeenCalled();

        await openRegister();
        expect(authService.getCaptcha).toHaveBeenCalledTimes(1);
        expect(screen.getByAltText('验证码')).toHaveAttribute('src', 'data:image/png;base64,c1');
    });

    it('图形验证码：没填验证码不提交', async () => {
        vi.mocked(authService.getPublicConfig).mockResolvedValue(config('IMAGE') as never);
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        await openRegister();
        fillRegister();
        await submitRegister();

        expect(authService.register).not.toHaveBeenCalled();
        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ title: '请输入验证码' }));
    });

    /** 答案取出即作废：失败后必须换一张并清空输入，否则用户拿旧 id 再提交必然失败 */
    it('图形验证码：带上 id 与答案提交；失败后换一张并清空输入', async () => {
        vi.mocked(authService.getPublicConfig).mockResolvedValue(config('IMAGE') as never);
        vi.mocked(authService.register).mockResolvedValue(fail as never);
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        await openRegister();
        fillRegister();
        fireEvent.change(screen.getByPlaceholderText('验证码'), { target: { value: ' k7px ' } });
        await submitRegister();

        expect(authService.register).toHaveBeenCalledWith(expect.objectContaining({ captchaId: 'c1', captchaCode: 'k7px' }));
        expect(authService.getCaptcha).toHaveBeenCalledTimes(2);
        expect(screen.getByAltText('验证码')).toHaveAttribute('src', 'data:image/png;base64,c2');
        expect(screen.getByPlaceholderText('验证码')).toHaveValue('');
    });

    it('Turnstile：未完成验证不提交；完成后带令牌提交；失败后重置组件', async () => {
        vi.mocked(authService.getPublicConfig).mockResolvedValue(config('TURNSTILE', 'site-key') as never);
        vi.mocked(authService.register).mockResolvedValue(fail as never);
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        await openRegister();
        fillRegister();

        await submitRegister();
        expect(authService.register).not.toHaveBeenCalled();
        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ title: '请先完成人机验证' }));

        fireEvent.click(screen.getByRole('button', { name: '完成人机验证' }));
        await submitRegister();
        expect(authService.register).toHaveBeenCalledWith(expect.objectContaining({ turnstileToken: 'ts-token' }));
        expect(screen.getByTestId('turnstile-reset')).toHaveTextContent('1');
        expect(authService.getCaptcha).not.toHaveBeenCalled();
    });

    it('不启用时不渲染任何验证控件，直接提交', async () => {
        vi.mocked(authService.getPublicConfig).mockResolvedValue(config('NONE') as never);
        vi.mocked(authService.register).mockResolvedValue(fail as never);
        render(<MemoryRouter><LoginPage /></MemoryRouter>);
        await flush();
        await openRegister();
        expect(screen.queryByPlaceholderText('验证码')).toBeNull();
        expect(screen.queryByRole('button', { name: '完成人机验证' })).toBeNull();

        fillRegister();
        await submitRegister();
        expect(authService.register).toHaveBeenCalledWith(expect.not.objectContaining({ captchaId: expect.anything() }));
        expect(authService.getCaptcha).not.toHaveBeenCalled();
    });
});

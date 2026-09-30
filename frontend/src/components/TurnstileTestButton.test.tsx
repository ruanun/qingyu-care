import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { platformService } from '@/api/platform';
import TurnstileTestButton from './TurnstileTestButton';

/**
 * 平台配置页「测试 Turnstile」：拿到令牌后带上表单当前的 secret 交后端校验，结果与错误码要展示给管理员。
 */

vi.mock('@/api/platform', () => ({ platformService: { testTurnstile: vi.fn() } }));
// 桩成「暴露 onToken 触发点 + 回显 siteKey/resetSignal」：真组件要加载 Cloudflare 脚本
vi.mock('@/components/TurnstileWidget', () => ({
    default: ({ siteKey, onToken, resetSignal }: { siteKey: string; onToken: (t: string | null) => void; resetSignal?: number }) => (
        <div>
            <span data-testid="widget-sitekey">{siteKey}</span>
            <span data-testid="widget-reset">{resetSignal ?? 0}</span>
            <button type="button" onClick={() => onToken('ts-token')}>完成人机验证</button>
        </div>
    ),
}));
// Radix Dialog 的 portal / focus trap 在 jsdom 下行为特殊，桩成「open 时渲染子节点」
vi.mock('@/components/ui/dialog', () => ({
    Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) => (open ? <div>{children}</div> : null),
    DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    DialogHeader: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    DialogTitle: ({ children }: { children: React.ReactNode }) => <h2>{children}</h2>,
    DialogDescription: ({ children }: { children: React.ReactNode }) => <p>{children}</p>,
}));

const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

const ok = (data: unknown) => ({ code: 200, message: 'ok', timestamp: '', data });

const openAndVerify = async () => {
    fireEvent.click(screen.getByRole('button', { name: '测试 Turnstile' }));
    fireEvent.click(screen.getByRole('button', { name: '完成人机验证' }));
    await flush();
    await flush();
};

beforeEach(() => {
    vi.mocked(platformService.testTurnstile).mockReset();
});

describe('TurnstileTestButton', () => {
    it('没填 Site Key 时不能测试', () => {
        render(<TurnstileTestButton siteKey=" " secretKey="" />);
        expect(screen.getByRole('button', { name: '测试 Turnstile' })).toBeDisabled();
    });

    it('用表单当前的 Site Key 渲染组件，令牌连同当前 Secret 交后端；通过时展示主机名', async () => {
        vi.mocked(platformService.testTurnstile).mockResolvedValue(ok({ success: true, errorCodes: [], hostname: 'care.example.com' }) as never);
        render(<TurnstileTestButton siteKey=" site-1 " secretKey=" sec-1 " />);
        await openAndVerify();

        expect(screen.getByTestId('widget-sitekey')).toHaveTextContent(/^site-1$/);
        expect(platformService.testTurnstile).toHaveBeenCalledWith({ token: 'ts-token', secretKey: 'sec-1' });
        expect(screen.getByText(/校验通过/)).toHaveTextContent('care.example.com');
    });

    /** Secret 只写不读、表单里通常是空的：此时不传，后端才会回落到已保存的那份 */
    it('Secret 留空时不传，由后端用已保存的', async () => {
        vi.mocked(platformService.testTurnstile).mockResolvedValue(ok({ success: true, errorCodes: [], hostname: null }) as never);
        render(<TurnstileTestButton siteKey="site-1" secretKey="  " />);
        await openAndVerify();

        expect(platformService.testTurnstile).toHaveBeenCalledWith({ token: 'ts-token', secretKey: undefined });
    });

    it('不通过时逐条展示错误码与说明；再测一次会重置组件', async () => {
        vi.mocked(platformService.testTurnstile).mockResolvedValue(
            ok({ success: false, errorCodes: ['invalid-input-secret'], hostname: null }) as never);
        render(<TurnstileTestButton siteKey="site-1" secretKey="bad" />);
        await openAndVerify();

        expect(screen.getByText('校验未通过')).toBeInTheDocument();
        expect(screen.getByText(/Secret Key 不正确（invalid-input-secret）/)).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: '再测一次' }));
        expect(screen.getByTestId('widget-reset')).toHaveTextContent('1');
        expect(screen.queryByText('校验未通过')).toBeNull();
    });

    /** 业务错误是「HTTP 200 + body.code」，不判 code 就会把「连不上 Cloudflare」静默当成无结果 */
    it('后端业务错误（如连不上 Cloudflare）展示错误信息', async () => {
        vi.mocked(platformService.testTurnstile).mockResolvedValue(
            { code: 503, message: '无法连接 Cloudflare 校验服务', timestamp: '', data: null } as never);
        render(<TurnstileTestButton siteKey="site-1" secretKey="sec" />);
        await openAndVerify();

        expect(screen.getByText('无法连接 Cloudflare 校验服务')).toBeInTheDocument();
    });
});

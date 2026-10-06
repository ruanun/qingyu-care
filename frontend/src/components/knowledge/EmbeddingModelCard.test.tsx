import type { ReactNode } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { embeddingService } from '@/api/embedding';
import type { EmbeddingState } from '@/api/embedding';
import { EmbeddingModelCard } from './EmbeddingModelCard';

/**
 * 向量模型卡片的三个写操作（切换 / 取消 / 继续）与迁移中的轮询。
 *
 * 写操作都守「HTTP 200 + body.code」这条约定：业务错误（如「已有切换进行中」）必须弹 destructive toast，
 * 不能被静默吞掉；成功才重新拉状态。
 *
 * @author ruan
 */
const { toastSpy, confirmSpy } = vi.hoisted(() => ({ toastSpy: vi.fn(), confirmSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));
// 确认框本身有自己的实现与测试；这里只关心「确认 / 不确认」之后的分支
vi.mock('@/hooks/use-confirm', () => ({ useConfirm: () => ({ confirm: confirmSpy, confirmDialog: null }) }));

vi.mock('@/api/embedding', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/api/embedding')>();
    return {
        ...actual,
        embeddingService: {
            getState: vi.fn(),
            switchModel: vi.fn(),
            rebuild: vi.fn(),
            cancelJob: vi.fn(),
            resumeJob: vi.fn(),
        },
    };
});

// Radix Dialog / Select 在 jsdom 下的 portal 与 focus trap 与本文件无关：Dialog 按 open 门控透传，
// Select 换成原生 <select>，保留「选中 → onValueChange」这条回调路径（桩成 null 会让切换逻辑零覆盖）
vi.mock('@/components/ui/dialog', () => ({
    Dialog: ({ open, children }: { open: boolean; children: ReactNode }) => (open ? <div>{children}</div> : null),
    DialogContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogHeader: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogFooter: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogTitle: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogDescription: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ui/select', () => ({
    Select: ({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children: ReactNode }) => (
        <select aria-label="目标向量模型" value={value} onChange={(e) => onValueChange(e.target.value)}>
            <option value="">请选择</option>
            {children}
        </select>
    ),
    SelectTrigger: () => null,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: ReactNode }) => <>{children}</>,
    SelectItem: ({ value, children }: { value: string; children: ReactNode }) => <option value={value}>{children}</option>,
}));

const ok = <T,>(data: T, message = 'ok') => ({ code: 200, message, data, timestamp: '' });
const fail = (message: string) => ({ code: 400, message, data: null as never, timestamp: '' });

const ACTIVE = { id: 1, name: '默认向量模型', provider: 'dashscope', modelId: 'qwen3.7-text-embedding', similarityThreshold: 0.45, platform: true };
const BGE = { id: 2, name: '硅基 bge', provider: 'siliconflow', modelId: 'BAAI/bge-m3', similarityThreshold: 0.5, platform: false };
const SAME = { id: 3, name: '通义备用 Key', provider: 'dashscope', modelId: 'qwen3.7-text-embedding', similarityThreshold: 0.45, platform: false };

const idle = (): EmbeddingState => ({
    active: ACTIVE, pending: null, job: null, options: [ACTIVE, BGE, SAME], dimensions: 1024,
    manualThresholdStrategies: [{ id: 9, name: '严谨投诉处理', scenario: 'complaint', similarityThreshold: 0.55, platform: true }],
});

const running = (done: number): EmbeddingState => ({
    ...idle(), pending: BGE,
    job: { id: 30, kind: 'SWITCH', status: 'RUNNING', fromTag: 'qwen3.7-text-embedding', toTag: 'BAAI/bge-m3', total: 10, done, failed: 0,
        message: null, startedAt: '', finishedAt: null },
});

describe('EmbeddingModelCard', () => {
    beforeEach(() => {
        toastSpy.mockClear();
        vi.mocked(embeddingService.getState).mockReset();
        vi.mocked(embeddingService.switchModel).mockReset();
        vi.mocked(embeddingService.cancelJob).mockReset();
        vi.mocked(embeddingService.resumeJob).mockReset();
        vi.mocked(embeddingService.rebuild).mockReset();
        confirmSpy.mockReset();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    const openAndPick = async (id: number) => {
        fireEvent.click(await screen.findByRole('button', { name: /更换/ }));
        fireEvent.change(screen.getByLabelText('目标向量模型'), { target: { value: String(id) } });
    };

    it('选不同模型：说明会后台重建、点名手动阈值策略；成功后提示并重新拉状态', async () => {
        vi.mocked(embeddingService.getState).mockResolvedValueOnce(ok(idle())).mockResolvedValueOnce(ok(running(0)));
        vi.mocked(embeddingService.switchModel).mockResolvedValue(ok({ outcome: 'MIGRATING', jobId: 30 }, '已开始重建向量，完成后自动切换'));
        render(<EmbeddingModelCard />);

        await openAndPick(2);
        expect(screen.getByText(/重建本应用全部文档与问答的向量/)).toBeInTheDocument();
        expect(screen.getByText(/严谨投诉处理/)).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '开始重建并切换' }));

        await waitFor(() => expect(embeddingService.switchModel).toHaveBeenCalledWith(2));
        await waitFor(() => expect(toastSpy).toHaveBeenCalledWith({ title: '已开始重建向量，完成后自动切换' }));
        expect(embeddingService.getState).toHaveBeenCalledTimes(2);
        expect(await screen.findByTestId('embedding-migration')).toBeInTheDocument();
    });

    it('选同一模型的另一份托管：说明直接生效，不提重建', async () => {
        vi.mocked(embeddingService.getState).mockResolvedValue(ok(idle()));
        render(<EmbeddingModelCard />);

        await openAndPick(3);

        expect(screen.getByText(/同一个模型/)).toBeInTheDocument();
        expect(screen.queryByText(/重建本应用全部文档/)).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: '立即切换' })).toBeInTheDocument();
    });

    it('切换返回业务错误：弹 destructive toast，不当成功', async () => {
        vi.mocked(embeddingService.getState).mockResolvedValue(ok(idle()));
        vi.mocked(embeddingService.switchModel).mockResolvedValue(fail('已有一次向量模型切换在进行中，请等它完成或先取消'));
        render(<EmbeddingModelCard />);

        await openAndPick(2);
        fireEvent.click(screen.getByRole('button', { name: '开始重建并切换' }));

        await waitFor(() => expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive', description: '已有一次向量模型切换在进行中，请等它完成或先取消',
        })));
        expect(embeddingService.getState).toHaveBeenCalledTimes(1);
    });

    it('迁移进行中：显示进度、禁用更换，并按间隔轮询直到停下', async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        vi.mocked(embeddingService.getState)
            .mockResolvedValueOnce(ok(running(3)))
            .mockResolvedValueOnce(ok({ ...idle(), active: BGE }));
        render(<EmbeddingModelCard />);

        expect(await screen.findByText(/正在为/)).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /更换/ })).toBeDisabled();

        await act(async () => {
            await vi.advanceTimersByTimeAsync(3000);
        });

        await waitFor(() => expect(screen.queryByTestId('embedding-migration')).not.toBeInTheDocument());
        expect(embeddingService.getState).toHaveBeenCalledTimes(2);
        // 停下之后不再轮询
        await act(async () => {
            await vi.advanceTimersByTimeAsync(10000);
        });
        expect(embeddingService.getState).toHaveBeenCalledTimes(2);
    });

    it('取消切换：业务错误弹 destructive toast', async () => {
        vi.mocked(embeddingService.getState).mockResolvedValue(ok({
            ...running(3), job: { ...running(3).job!, status: 'FAILED', message: '1 项内容未能写入新模型' },
        }));
        vi.mocked(embeddingService.cancelJob).mockResolvedValue(fail('该任务已结束，无需取消'));
        render(<EmbeddingModelCard />);

        fireEvent.click(await screen.findByRole('button', { name: '取消切换' }));

        await waitFor(() => expect(embeddingService.cancelJob).toHaveBeenCalledWith(30));
        await waitFor(() => expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ variant: 'destructive' })));
    });

    it('失败的任务可以继续：成功后重新拉状态', async () => {
        const failed = { ...running(3), job: { ...running(3).job!, status: 'FAILED' as const, message: '1 项内容未能写入新模型' } };
        vi.mocked(embeddingService.getState).mockResolvedValueOnce(ok(failed)).mockResolvedValueOnce(ok(running(3)));
        vi.mocked(embeddingService.resumeJob).mockResolvedValue(ok(undefined));
        render(<EmbeddingModelCard />);

        expect(await screen.findByText('1 项内容未能写入新模型')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '继续' }));

        await waitFor(() => expect(embeddingService.resumeJob).toHaveBeenCalledWith(30));
        await waitFor(() => expect(embeddingService.getState).toHaveBeenCalledTimes(2));
    });

    it('平台还没有任何向量模型：给出提示而不是整块报错', async () => {
        vi.mocked(embeddingService.getState).mockResolvedValue(ok({ ...idle(), active: null, options: [] }));
        render(<EmbeddingModelCard />);

        expect(await screen.findByText(/平台尚未配置可用的向量模型/)).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /更换/ })).toBeDisabled();
    });

    it('重建向量：确认后发起并重新拉状态，进度文案说明检索不受影响', async () => {
        const rebuilding: EmbeddingState = {
            ...idle(),
            job: { id: 41, kind: 'REBUILD', status: 'RUNNING', fromTag: 'qwen3.7-text-embedding', toTag: 'qwen3.7-text-embedding',
                total: 12, done: 4, failed: 0, message: null, startedAt: '', finishedAt: null },
        };
        vi.mocked(embeddingService.getState).mockResolvedValueOnce(ok(idle())).mockResolvedValueOnce(ok(rebuilding));
        vi.mocked(embeddingService.rebuild).mockResolvedValue(ok({ jobId: 41 }));
        confirmSpy.mockResolvedValue(true);
        render(<EmbeddingModelCard />);

        fireEvent.click(await screen.findByRole('button', { name: /重建向量/ }));

        await waitFor(() => expect(embeddingService.rebuild).toHaveBeenCalled());
        expect(await screen.findByText(/正在用当前模型重建向量/)).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '取消重建' })).toBeInTheDocument();
    });

    it('重建向量：不确认就不发起', async () => {
        vi.mocked(embeddingService.getState).mockResolvedValue(ok(idle()));
        confirmSpy.mockResolvedValue(false);
        render(<EmbeddingModelCard />);

        fireEvent.click(await screen.findByRole('button', { name: /重建向量/ }));

        await waitFor(() => expect(confirmSpy).toHaveBeenCalled());
        expect(embeddingService.rebuild).not.toHaveBeenCalled();
    });

    it('重建向量：业务错误弹 destructive toast', async () => {
        vi.mocked(embeddingService.getState).mockResolvedValue(ok(idle()));
        vi.mocked(embeddingService.rebuild).mockResolvedValue(fail('本应用有向量任务正在进行，请等它完成或先取消'));
        confirmSpy.mockResolvedValue(true);
        render(<EmbeddingModelCard />);

        fireEvent.click(await screen.findByRole('button', { name: /重建向量/ }));

        await waitFor(() => expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive', description: '本应用有向量任务正在进行，请等它完成或先取消',
        })));
    });
});

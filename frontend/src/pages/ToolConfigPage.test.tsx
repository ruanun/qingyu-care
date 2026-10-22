import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { AiTool } from '@/api/chat-tool';
import { aiToolService } from '@/api/chat-tool';
import ToolConfigPage from './ToolConfigPage';

/**
 * 技术债第 9 项（写操作把业务错误静默吞掉）的端到端回归测试。
 *
 * 挑 ToolConfigPage 的「启用/禁用」开关作为代表路径：它是全站 36 处里最短的一条
 * ——点一下开关就直达 `aiToolService.update`，不需要开对话框、不需要填表单，
 * 于是断言能精准落在「业务错误有没有被吐出来」上，而不是被表单交互稀释。
 *
 * ⚠️ 这条守的不是 ToolConfigPage 一个页面，而是 `throwIfFailed(res)` +
 * 复用调用点已有 catch 这个**范式**。范式被改回 `if (res.code === 200) {...}`
 * 的那一刻，本条会红。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/chat-tool', () => ({
    aiToolService: {
        list: vi.fn(),
        update: vi.fn(),
        create: vi.fn(),
        remove: vi.fn(),
    },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-11T00:00:00',
});

const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400,
    message,
    data: null as unknown as T,
    timestamp: '2026-08-11T00:00:00',
});

const tool: AiTool = {
    id: 1,
    name: 'query_order',
    description: '查订单',
    httpMethod: 'GET',
    urlTemplate: 'https://example.com/api/order/{id}',
    enabled: true,
} as AiTool;

// 组件里的取数与提交都是 async，推进一拍微任务队列即可（本页没有定时器，
// 所以不需要 fake timers；直接 act 包一层 await 让 promise 链落地）。
const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

const renderPage = async () => {
    const result = render(<ToolConfigPage />);
    await flush();
    return result;
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(aiToolService.list).mockReset().mockResolvedValue(ok([tool]));
    vi.mocked(aiToolService.update).mockReset();
});

afterEach(() => {
    vi.clearAllMocks();
});

describe('ToolConfigPage 写操作的业务错误不静默', () => {
    /**
     * 变异实验：把 ToolConfigPage.handleToggle 里的 `throwIfFailed(res);` 删掉
     * （即改回旧的静默形态），本条必须转红——toast 一次都不会被调用。
     */
    it('切换启用状态时后端返业务错误 → 弹 destructive toast 且不重新拉列表', async () => {
        vi.mocked(aiToolService.update).mockResolvedValue(fail<AiTool>('该工具正被 Skill 引用，无法停用'));

        await renderPage();
        expect(screen.getByText('query_order')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('switch'));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '操作失败',
            description: '该工具正被 Skill 引用，无法停用',
        }));
        // 失败时不该走成功分支里的 fetchTools()，所以 list 只有挂载那一次
        expect(aiToolService.list).toHaveBeenCalledTimes(1);
    });

    /**
     * 成功路径的对照。没有它，上面那条即使在「无论成败都弹 destructive」的
     * 错误实现下也会绿——两条合起来才把「只在失败时报错」钉住。
     */
    it('切换启用状态成功 → 弹成功 toast 并重新拉列表', async () => {
        vi.mocked(aiToolService.update).mockResolvedValue(ok(tool));

        await renderPage();
        fireEvent.click(screen.getByRole('switch'));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith({ title: '工具已禁用' }); // tool.enabled 为 true，点一下是禁用
        expect(toastSpy).not.toHaveBeenCalledWith(expect.objectContaining({ variant: 'destructive' }));
        expect(aiToolService.list).toHaveBeenCalledTimes(2); // 挂载 + 成功后重查
    });
});

/**
 * 删除从 `window.confirm()` 换成了 useConfirm 弹窗。原生 confirm 是同步阻塞的，
 * 换成 Promise 之后最容易出的错是忘了 await——`if (!confirm(...))` 对一个 Promise 取反恒为 false，
 * 于是弹窗照弹、删除却已经在后台直接执行了。第一条守的就是这个。
 */
describe('ToolConfigPage 删除需要二次确认', () => {
    const openDelete = async () => {
        await renderPage();
        fireEvent.click(screen.getByRole('button', { name: '删除' }));
    };

    it('弹出确认框后点取消 → 不调用删除接口', async () => {
        await openDelete();
        expect(screen.getByRole('alertdialog')).toHaveTextContent('删除该工具？');
        expect(aiToolService.remove).not.toHaveBeenCalled();

        fireEvent.click(screen.getByRole('button', { name: '取消' }));
        await flush();

        expect(aiToolService.remove).not.toHaveBeenCalled();
    });

    it('点确认 → 删除并刷新列表；业务错误时弹 destructive toast', async () => {
        vi.mocked(aiToolService.remove).mockResolvedValue(fail('该工具正被 Skill 引用'));
        await openDelete();

        fireEvent.click(screen.getByRole('button', { name: '删除' }));
        await flush();

        expect(aiToolService.remove).toHaveBeenCalledWith(1);
        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            description: '该工具正被 Skill 引用',
        }));
        expect(aiToolService.list).toHaveBeenCalledTimes(1);
    });
});

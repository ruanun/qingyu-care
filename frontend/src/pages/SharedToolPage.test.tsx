import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { AiTool } from '@/api/chat-tool';
import { platformService } from '@/api/platform';
import SharedToolPage from './SharedToolPage';

/**
 * SharedToolPage 写操作的业务错误不静默——模板抄 ToolConfigPage.test.tsx。
 *
 * 挑「新增共享工具」这条写操作：填完必填字段点保存，直达
 * `platformService.createSharedTool`，断言业务错误弹 destructive toast、
 * 成功才关闭对话框并重新拉取列表。
 *
 * ⚠️ `vi.mock` 的工厂被提到所有 import 之前执行，桩函数必须用 `vi.hoisted`
 * 一起提上去，否则引用模块顶层 const 会撞 TDZ。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/platform', () => ({
    platformService: {
        listSharedTools: vi.fn(),
        createSharedTool: vi.fn(),
        updateSharedTool: vi.fn(),
        deleteSharedTool: vi.fn(),
    },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-12T00:00:00',
});

const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400,
    message,
    data: null as unknown as T,
    timestamp: '2026-08-12T00:00:00',
});

const tool: AiTool = {
    id: 1,
    name: 'query_order',
    description: '查订单',
    inputSchema: '{}',
    httpMethod: 'GET',
    urlTemplate: 'https://example.com/api/order/{id}',
    authHeader: null,
    timeoutMs: 5000,
    maxResponseChars: 4000,
    enabled: true,
    scope: 'PLATFORM',
    createdAt: '2026-08-12T00:00:00',
    updatedAt: '2026-08-12T00:00:00',
};

// 组件里的取数与提交都是 async，推进一拍微任务队列即可（本页没有定时器）。
const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

const renderPage = async () => {
    const result = render(<SharedToolPage />);
    await flush();
    return result;
};

const openCreateDialogAndFill = () => {
    fireEvent.click(screen.getByRole('button', { name: /新增共享工具/ }));
    fireEvent.change(screen.getByPlaceholderText('query_order'), { target: { value: 'query_order' } });
    fireEvent.change(screen.getByPlaceholderText('根据订单号查询订单状态'), { target: { value: '查订单' } });
    fireEvent.change(screen.getByPlaceholderText('https://api.example.com/orders/{orderId}'), {
        target: { value: 'https://example.com/api/order/{id}' },
    });
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(platformService.listSharedTools).mockReset().mockResolvedValue(ok([tool]));
    vi.mocked(platformService.createSharedTool).mockReset();
});

afterEach(() => {
    vi.clearAllMocks();
});

describe('SharedToolPage 写操作的业务错误不静默', () => {
    /**
     * 变异实验：把 submit() 里 `if (res.code !== 200) throw new Error(res.message);`
     * 删掉，本条必须转红——toast 一次都不会被调用，且列表仍会被静默刷新。
     */
    it('创建失败时弹 destructive toast', async () => {
        vi.mocked(platformService.createSharedTool).mockResolvedValue(fail<AiTool>('重名'));

        await renderPage();
        openCreateDialogAndFill();

        fireEvent.click(screen.getByRole('button', { name: '保存配置' }));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            title: '保存失败',
            description: '重名',
            variant: 'destructive',
        }));
        // 失败时不该走成功分支，对话框仍打开、列表只在挂载时拉过一次
        expect(screen.getByRole('dialog')).toBeInTheDocument();
        expect(platformService.listSharedTools).toHaveBeenCalledTimes(1);
    });

    /**
     * 成功路径的对照。没有它，上面那条即使在「无论成败都弹 destructive」的
     * 错误实现下也会绿——两条合起来才把「只在失败时报错」钉住。
     */
    it('创建成功后关闭对话框并刷新列表', async () => {
        vi.mocked(platformService.createSharedTool).mockResolvedValue(ok(tool));

        await renderPage();
        openCreateDialogAndFill();

        fireEvent.click(screen.getByRole('button', { name: '保存配置' }));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith({ title: '已保存' });
        expect(toastSpy).not.toHaveBeenCalledWith(expect.objectContaining({ variant: 'destructive' }));
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(platformService.listSharedTools).toHaveBeenCalledTimes(2); // 挂载 + 成功后重查
    });
});

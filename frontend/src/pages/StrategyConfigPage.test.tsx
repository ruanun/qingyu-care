import type { ReactNode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { ChatStrategy } from '@/api/strategy';
import { chatStrategyService } from '@/api/strategy';
import { promptService } from '@/api/prompt';
import StrategyConfigPage from './StrategyConfigPage';

/**
 * StrategyConfigPage 两条回归测试：
 *
 * 1. 钉住技术债第 12 项同批修的那个活缺陷——`openCreateDialog` 曾把
 *    `similarityThreshold` 写死成 0.7，而该值已于 2026-08-11 被实测推翻（真实相关命中
 *    落在 0.57~0.73、噪声落在 0.25~0.30，0.7 卡在命中带顶端几乎全滤掉），平台默认早已
 *    重标定为 0.45。改回 0.7 的回归必须被这条测试挡住。
 * 2. 钉住技术债第 12 项前端半——混合检索开关是否真的接线到 `hybridEnabled`，而不只是
 *    渲染出来但不触发状态更新（回调驱动的控件最容易踩这个坑）。
 *
 * ⚠️ Dialog/Select/Slider 是 Radix 组件，portal 与 focus trap 在 jsdom 下行为特殊，
 * 与本文件要验的字段绑定逻辑无关，硬测只会引入噪声——桩掉它们。但不能桩成 `() => null`：
 * 表单的所有字段都直接写在这个页面组件里（不像 CrawlSiteList 那样拆了独立的 Dialog
 * 子组件可以整体替换），必须保留 Switch 真实实现（未桩，ToolConfigPage.test.tsx 已验证
 * 它在 jsdom 下能正常 click）才能测混合检索开关这条回调路径。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/strategy', () => ({
    chatStrategyService: {
        getAll: vi.fn(),
        getById: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
        delete: vi.fn(),
    },
}));

// 页面挂载时拉一次本应用向量模型的推荐阈值（跟随模式显示用），与本文件要测的字段绑定无关
vi.mock('@/api/embedding', () => ({
    embeddingService: {
        getState: vi.fn().mockResolvedValue({ code: 200, message: 'ok', data: { active: { similarityThreshold: 0.45 } } }),
    },
}));

vi.mock('@/api/prompt', () => ({
    promptService: {
        getAllVersions: vi.fn(),
    },
}));

// 同 UserListPage.test.tsx 的桩法：透传 + 按 open 门控，避开 Radix Dialog/Select
// 在 jsdom 下的 portal/focus-trap 噪声，同时保留「点击触发 → 表单渲染 → 改字段 →
// 提交」这条真实链路（不像 CrawlSiteDialog 那样整个组件被换成 stub-submit 按钮，
// 因为本页面的字段就直接写在这个组件里，没有可整体替换的子组件）。
vi.mock('@/components/ui/dialog', () => ({
    Dialog: ({ open, children }: { open: boolean; children: ReactNode }) => (open ? <div>{children}</div> : null),
    DialogContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogHeader: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogFooter: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogTitle: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogDescription: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

// 本文件不触碰场景下拉，Select 只作装饰性渲染，桩成不带 Radix 行为的透传即可。
vi.mock('@/components/ui/select', () => ({
    Select: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectTrigger: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    SelectItem: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

// Slider 桩成一个真实可控的 range input：两条测试都不需要拖动它，但页面挂载时
// TopK / 相似度阈值 / （混合检索开启后的）关键词条数 / RRF k 四个 Slider 区块
// 都会渲染，真实 Radix Slider 在 jsdom 下无 ResizeObserver 时行为不可靠，桩掉以
// 避免与本文件要验的逻辑无关的噪声。
vi.mock('@/components/ui/slider', () => ({
    Slider: (
        { value, min, max, onValueChange }: {
            value: number[];
            min?: number;
            max?: number;
            onValueChange: (v: number[]) => void;
        },
    ) => (
        <input
            type="range"
            min={min}
            max={max}
            value={value[0]}
            onChange={(e) => onValueChange([Number(e.target.value)])}
        />
    ),
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-14T00:00:00',
});

const strategy1: ChatStrategy = {
    id: 1,
    name: '默认策略',
    scenario: 'default',
    topK: 5,
    similarityThreshold: 0.45,
    allowNoKnowledge: true,
    fallbackMessage: '抱歉，我无法回答该问题。',
    promptTemplateId: null,
    enabled: true,
    isDefault: true,
    // 显式 false（而不是留空靠 undefined）：混合检索开关对 null/undefined 显示为「开」
    // （与后端 `!Boolean.FALSE.equals(...)` 契约对齐，见 StrategyConfigPage.tsx 里那段
    // 注释），留空会让下面点击开关这条测试变成「从已经是开的状态点一下变成关」，
    // 断言就要反过来写，判别力不如显式从「关」点到「开」这条更贴近真实编辑场景。
    hybridEnabled: false,
    createdAt: '2026-08-14T00:00:00',
    updatedAt: '2026-08-14T00:00:00',
};

const flush = async () => {
    await act(async () => {
        await Promise.resolve();
    });
};

const renderPage = async () => {
    const result = render(<StrategyConfigPage />);
    await flush();
    return result;
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(chatStrategyService.getAll).mockReset().mockResolvedValue(ok([strategy1]));
    vi.mocked(chatStrategyService.create).mockReset();
    vi.mocked(chatStrategyService.update).mockReset();
    vi.mocked(promptService.getAllVersions).mockReset().mockResolvedValue(ok([]));
});

describe('StrategyConfigPage 新建策略的默认阈值', () => {
    /**
     * 变异实验：把 openCreateDialog 里的 `similarityThreshold: 0.45` 改回 `0.7`，
     * 本条必须转红。真实终端输出见 task-3-report.md。
     */
    it('新建策略时提交的 similarityThreshold 是 0.45', async () => {
        vi.mocked(chatStrategyService.create).mockResolvedValue(ok(strategy1));

        await renderPage();
        fireEvent.click(screen.getByRole('button', { name: /新建策略/ }));
        await flush();

        fireEvent.change(screen.getByPlaceholderText('如：严谨服务策略'), {
            target: { value: '新策略' },
        });
        fireEvent.click(screen.getByRole('button', { name: /保存策略/ }));
        await flush();

        expect(chatStrategyService.create).toHaveBeenCalledTimes(1);
        const payload = vi.mocked(chatStrategyService.create).mock.calls[0][0];
        expect(payload).toHaveProperty('similarityThreshold', 0.45);
    });
});

describe('StrategyConfigPage 混合检索开关接线', () => {
    /**
     * 变异实验：把混合检索 Switch 的 `onCheckedChange` 回调改成空函数（渲染出来但
     * 不更新状态，即控件被"看得见但没接线"），本条必须转红——payload 里不会带
     * `hybridEnabled: true`。
     */
    it('编辑时改混合检索开关，提交的 payload 里带 hybridEnabled', async () => {
        vi.mocked(chatStrategyService.update).mockResolvedValue(ok(strategy1));

        await renderPage();
        fireEvent.click(screen.getByRole('button', { name: /编辑策略/ }));
        await flush();

        fireEvent.click(screen.getByRole('switch', { name: '混合检索' }));
        fireEvent.click(screen.getByRole('button', { name: /保存策略/ }));
        await flush();

        expect(chatStrategyService.update).toHaveBeenCalledTimes(1);
        const [id, payload] = vi.mocked(chatStrategyService.update).mock.calls[0];
        expect(id).toBe(strategy1.id);
        expect(payload).toHaveProperty('hybridEnabled', true);
    });

    /**
     * 钉住终审揪出的语义错位：后端 `ChatServiceImpl.searchKnowledge` 的
     * `!Boolean.FALSE.equals(strategy.getHybridEnabled())` 把 hybridEnabled 为
     * null（含全部存量行——库里目前没有一行 backfill 过这一列）视为「开启」。
     * 管理面必须显示成一致的状态，否则想关掉混合检索的管理员打开编辑框看到的已经是
     * 「关」，什么都不做，而后端其实仍在跑混合检索。
     *
     * 变异实验：把 Switch 的 `checked={editingStrategy?.hybridEnabled ?? true}`
     * 改回 `|| false`，本条必须转红（初始 aria-checked 从 true 变 false，两个调参
     * Slider 也不再可见）。上面那条「开关接线」测试因为 fixture 显式给了
     * `hybridEnabled: false`，覆盖不到这个 null/undefined 分支，需要单独一条。
     */
    it('hybridEnabled 未配置的存量策略，编辑弹窗初始显示为开、两个调参可见', async () => {
        const legacyStrategy: ChatStrategy = { ...strategy1, hybridEnabled: undefined };
        vi.mocked(chatStrategyService.getAll).mockResolvedValue(ok([legacyStrategy]));
        vi.mocked(chatStrategyService.update).mockResolvedValue(ok(legacyStrategy));

        await renderPage();
        fireEvent.click(screen.getByRole('button', { name: /编辑策略/ }));
        await flush();

        const toggle = screen.getByRole('switch', { name: '混合检索' });
        expect(toggle).toHaveAttribute('aria-checked', 'true');
        expect(screen.getByText(/关键词取回条数/)).toBeInTheDocument();
        expect(screen.getByText(/RRF 融合常数 k/)).toBeInTheDocument();

        fireEvent.click(toggle);
        fireEvent.click(screen.getByRole('button', { name: /保存策略/ }));
        await flush();

        expect(chatStrategyService.update).toHaveBeenCalledTimes(1);
        const [, payload] = vi.mocked(chatStrategyService.update).mock.calls[0];
        expect(payload).toHaveProperty('hybridEnabled', false);
    });
});

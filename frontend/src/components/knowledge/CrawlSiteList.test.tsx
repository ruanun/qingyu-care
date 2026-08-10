import { StrictMode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse, CrawlProgress, CrawlSite } from '@/types';
import { knowledgeService } from '@/api/knowledge';
import { CrawlSiteList } from './CrawlSiteList';

/**
 * CrawlSiteList 的轮询生命周期回归测试。
 *
 * 这个组件在站点爬取上线前返工了三轮，三个缺陷（终态不收尾 / 双任务互踩 /
 * StrictMode 下 isMountedRef 卡死）全靠人工代码走查发现。这里每条测试都配了
 * 一次变异实验（把实现改坏必须转红），实验方式记在各条测试的注释里。
 *
 * 桩在 api service 模块层而不是 HTTP 层：api/client.ts 的响应拦截器是
 * `response => response.data`，交给调用方的就是后端那整个 ApiResponse，
 * 所以这一层的返回值形态 `{code, message, data}` 正是「HTTP 200 + body.code」
 * 这条全局约定发生的位置。
 */

// vi.mock 的工厂会被提到所有 import 之前执行，工厂里直接引用模块顶层的 const
// 会撞 TDZ（ReferenceError: Cannot access before initialization），
// 必须用 vi.hoisted 把桩函数一起提上去。
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/knowledge', () => ({
    knowledgeService: {
        listCrawlSites: vi.fn(),
        getCrawlProgress: vi.fn(),
        addCrawlSite: vi.fn(),
        recrawlSite: vi.fn(),
        updateCrawlSite: vi.fn(),
        deleteCrawlSite: vi.fn(),
    },
}));

// Radix Dialog 的 portal 与 focus trap 在 jsdom 下的行为跟轮询链毫无关系，
// 把表单对话框桩成空组件，让测试只面对轮询逻辑。
vi.mock('./CrawlSiteDialog', () => ({
    CrawlSiteDialog: () => null,
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-10T00:00:00',
});

const site1: CrawlSite = {
    id: 1,
    entryUrl: 'https://example.com/help/',
    scopePrefix: 'https://example.com/help/',
    maxPages: 50,
    maxDepth: 3,
    contentSelector: null,
    syncInterval: 'DAILY',
    lastCrawledAt: null,
    createdAt: '2026-08-10T00:00:00',
};

const progress = (over: Partial<CrawlProgress> = {}): CrawlProgress => ({
    jobId: 'job-1',
    siteId: 1,
    status: 'RUNNING',
    crawled: 3,
    created: 3,
    updated: 0,
    failed: 0,
    maxPages: 50,
    errorMessage: null,
    startedAt: '2026-08-10T00:00:00',
    finishedAt: null,
    ...over,
});

// ⚠️ 时间推进必须走 advanceTimersByTimeAsync：poll 的 tick 内部有 await，
// 同步的 advanceTimersByTime 只会把定时器回调排出去、推不动后面的 promise 链，
// 测试会「通过」但实际什么都没验证。外面再包一层 act 是为了让 React 19 不再
// 抱怨状态更新没被 act 包裹。
const flush = async (ms = 0) => {
    await act(async () => {
        await vi.advanceTimersByTimeAsync(ms);
    });
};

// vi.fn() 不带类型实参时，`ReturnType<typeof vi.fn>` 会按类型参数的约束
// `Procedure | Constructable` 推断（而不是它的默认值 `Procedure`），产出的
// Mock 类型缺少可调用签名，赋给 Props 里 `onCrawlFinished: () => void` 会被
// tsc -b 拒绝（pnpm test 不会报，pnpm build 才会）。显式钉住泛型参数即可。
let onCrawlFinished: ReturnType<typeof vi.fn<() => void>>;

const renderList = async (opts: { strict?: boolean } = {}) => {
    const ui = <CrawlSiteList knowledgeBaseId={7} onCrawlFinished={onCrawlFinished} />;
    const result = render(opts.strict ? <StrictMode>{ui}</StrictMode> : ui);
    await flush(); // 放掉挂载时那次 loadSites
    return result;
};

const clickRecrawl = () => fireEvent.click(screen.getByRole('button', { name: '重新爬取' }));

beforeEach(() => {
    vi.useFakeTimers();
    toastSpy.mockReset();
    onCrawlFinished = vi.fn<() => void>();
    vi.mocked(knowledgeService.listCrawlSites).mockReset().mockResolvedValue(ok([site1]));
    vi.mocked(knowledgeService.getCrawlProgress).mockReset();
    vi.mocked(knowledgeService.recrawlSite).mockReset();
});

afterEach(() => {
    vi.useRealTimers();
});

describe('CrawlSiteList 轮询生命周期', () => {
    /**
     * T1｜变异实验：删掉 CrawlSiteList.tsx:118 那行
     * `timersRef.current[jobId] = window.setTimeout(tick, 2000);`（RUNNING 分支的续期），
     * 本条必须转红——第二次 tick 永远不会发生，收尾的三个断言全部落空。
     */
    it('RUNNING 时按 2 秒续期，终态时收尾且只通知父组件一次', async () => {
        vi.mocked(knowledgeService.recrawlSite).mockResolvedValue(ok('job-1'));
        vi.mocked(knowledgeService.getCrawlProgress)
            .mockResolvedValueOnce(ok(progress({ crawled: 3 })))
            .mockResolvedValueOnce(ok(progress({
                status: 'COMPLETED',
                crawled: 9,
                finishedAt: '2026-08-10T00:01:00',
            })));

        await renderList();
        clickRecrawl();
        await flush(); // 放掉 recrawlSite 那一跳，poll 挂上第一个定时器

        await flush(2000); // tick 1：RUNNING
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledTimes(1);
        expect(screen.getByText(/已爬 3/)).toBeInTheDocument();

        await flush(2000); // tick 2：COMPLETED → 收尾
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledTimes(2);
        expect(screen.queryByText(/正在爬取/)).not.toBeInTheDocument();
        expect(onCrawlFinished).toHaveBeenCalledTimes(1);
        // 初次挂载那次 + 收尾时的重查
        expect(knowledgeService.listCrawlSites).toHaveBeenCalledTimes(2);

        await flush(4000); // 终态之后不该再有任何续期
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledTimes(2);
    });
});

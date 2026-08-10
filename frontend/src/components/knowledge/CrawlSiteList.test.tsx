import { StrictMode } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse, CrawlProgress, CrawlSite, CrawlSiteRequest } from '@/types';
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
// stubSubmit 也必须走 vi.hoisted：下面 CrawlSiteDialog 的桩要在自己的 onSubmit
// 回调里记录「这次提交是被 resolve 还是被 reject 的」，而那个桩工厂同样被提到
// import 之前，引用模块顶层的 const 会撞同一个 TDZ。
const { toastSpy, stubSubmit } = vi.hoisted(() => ({
    toastSpy: vi.fn(),
    stubSubmit: { rejected: false },
}));

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

// 表单对话框仍然不渲染真实实现——它内部包着 Radix Dialog，而 portal 与 focus trap
// 在 jsdom 下的行为跟本文件要验的组件逻辑无关，硬测只会引入噪声。
//
// 但**不能桩成 `() => null`**：建站与编辑是通过这个组件的 onSubmit 回调触发的，
// 桩成空组件时 handleCreate / handleUpdate 永远执行不到，那两条路径就是零覆盖
// （全分支终审点名过这个缺口）。所以桩成一个可控 stub：只在 open 时渲染，
// 把「建站还是编辑」暴露成可断言的文本，并给一个按钮触发 onSubmit。
//
// stub 还要接住 onSubmit 返回的 promise：handleCreate / handleUpdate 在业务异常时
// 会 `throw error`，真实对话框靠它决定「提交失败就不要关闭、让用户改完重试」。
// 把 resolve / reject 记进 stubSubmit.rejected，测试才能断言这个行为还在。
vi.mock('./CrawlSiteDialog', () => ({
    CrawlSiteDialog: ({ open, initial, onSubmit }: {
        open: boolean;
        initial?: CrawlSite | null;
        onSubmit: (req: CrawlSiteRequest) => Promise<void>;
    }) => (open ? (
        <div>
            <span>{`stub-mode:${initial ? 'edit' : 'create'}`}</span>
            <button
                onClick={() => {
                    void onSubmit({ entryUrl: 'https://example.com/help/' })
                        .then(() => { stubSubmit.rejected = false; })
                        .catch(() => { stubSubmit.rejected = true; });
                }}
            >
                stub-submit
            </button>
        </div>
    ) : null),
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200,
    message: 'success',
    data,
    timestamp: '2026-08-10T00:00:00',
});

// 业务异常的形态：HTTP 200 + body.code != 200，data 为 null。
// axios 不会 reject 这种响应，调用方必须自己判 code——这正是 T5 守的那条约定。
const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400,
    message,
    data: null as unknown as T,
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
    stubSubmit.rejected = false;
    vi.mocked(knowledgeService.listCrawlSites).mockReset().mockResolvedValue(ok([site1]));
    vi.mocked(knowledgeService.getCrawlProgress).mockReset();
    vi.mocked(knowledgeService.recrawlSite).mockReset();
    // 这三个此前不在 reset 名单里。当时无害（没有一条测试调得到它们），但
    // 「桩不被 reset」是典型的测试顺序依赖来源：一旦有测试给它们设了
    // mockResolvedValueOnce 或 mockImplementation，就会漏到后面的测试里。
    // 现在建站 / 编辑 / 删除三条路径都有测试了，必须一起清。
    vi.mocked(knowledgeService.addCrawlSite).mockReset();
    vi.mocked(knowledgeService.updateCrawlSite).mockReset();
    vi.mocked(knowledgeService.deleteCrawlSite).mockReset();
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

    /**
     * T4｜变异实验：删掉 CrawlSiteList.tsx:114 那行 `if (!isMountedRef.current) return;`，
     * 本条必须转红。
     *
     * ⚠️ 卸载 effect 里的 clearTimeout **拦不住这条**——它只能取消「还没触发的」
     * 下一次调度。这里刻意让请求在卸载那一刻正挂在半空（deferred promise），
     * 唯一能挡住后续副作用的就是那行 isMountedRef 判断。
     */
    it('卸载后，在飞的请求返回终态也不再刷新列表或通知父组件', async () => {
        vi.mocked(knowledgeService.recrawlSite).mockResolvedValue(ok('job-1'));

        let settle!: (r: ApiResponse<CrawlProgress>) => void;
        vi.mocked(knowledgeService.getCrawlProgress).mockReturnValueOnce(
            new Promise<ApiResponse<CrawlProgress>>((resolve) => {
                settle = resolve;
            }),
        );

        const { unmount } = await renderList();
        clickRecrawl();
        await flush();

        await flush(2000); // tick 触发，请求挂在半空
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledTimes(1);

        unmount();

        settle(ok(progress({ status: 'COMPLETED', finishedAt: '2026-08-10T00:01:00' })));
        await flush();

        expect(onCrawlFinished).not.toHaveBeenCalled();
        // 只该有挂载时那一次；收尾分支里的 loadSites 不该被执行
        expect(knowledgeService.listCrawlSites).toHaveBeenCalledTimes(1);
    });

    /**
     * T2｜变异实验：把 poll 里 progressMap 与 timersRef 的索引键从 jobId 改成同一个写死的
     * 固定常量（共 6 处：第 117/118 行 RUNNING 分支的 setProgressMap 与续期、第 128 行
     * delete timersRef、第 130/132 行收尾的 in 判断与 delete、第 138 行首次挂表），
     * 本条必须转红——job-1 的收尾会把 job-2 刚写进去的那条进度抹掉。
     * 渲染处第 216 行的反查（`Object.values(progressMap).find((p) => p.siteId === site.id)`）
     * 按 siteId 遍历 values 匹配，与索引键名无关，**不需要跟着改**。
     */
    it('同一站点两条任务并发时，旧任务收尾不会抹掉新任务的进度', async () => {
        vi.mocked(knowledgeService.recrawlSite)
            .mockResolvedValueOnce(ok('job-1'))
            .mockResolvedValueOnce(ok('job-2'));

        // job-1 的响应按住，等 job-2 写完进度之后再放——这才是「旧任务收尾」
        // 与「新任务写入」交叠的那个窗口。若让 job-1 立刻 resolve，
        // 定时器注册顺序会让它先收尾、job-2 后写入，覆盖方向正好反过来，抓不到缺陷。
        let settleJob1!: (r: ApiResponse<CrawlProgress>) => void;
        vi.mocked(knowledgeService.getCrawlProgress).mockImplementation((jobId: string) => {
            if (jobId === 'job-1') {
                return new Promise<ApiResponse<CrawlProgress>>((resolve) => {
                    settleJob1 = resolve;
                });
            }
            return Promise.resolve(ok(progress({ jobId: 'job-2', crawled: 7 })));
        });

        await renderList();
        // 两次都是对同一个站点（site1）点「重新爬取」——按 siteId 索引的缺陷
        // 只在两条任务的 siteId 相同时才现形，换成两个不同站点这条测试就是摆设
        clickRecrawl();
        await flush();
        clickRecrawl();
        await flush();

        await flush(2000); // 两条 tick 都触发；job-2 立刻落地，job-1 仍被按住
        expect(screen.getByText(/已爬 7/)).toBeInTheDocument();

        settleJob1(ok(progress({ jobId: 'job-1', status: 'COMPLETED', crawled: 40 })));
        await flush();

        // 判别性断言：job-1 收尾之后，job-2 的进度必须还在
        expect(screen.getByText(/已爬 7/)).toBeInTheDocument();
        expect(onCrawlFinished).toHaveBeenCalledTimes(1);
    });

    /**
     * T3｜变异实验：删掉 CrawlSiteList.tsx:84 那行 `isMountedRef.current = true;`
     * （卸载 effect 的 setup 阶段），本条必须转红。
     *
     * 缺陷机理：isMountedRef 初值是 true，<StrictMode> 会把 effect 跑成
     * setup → cleanup → setup。setup 里不复位的话，中间那次 cleanup 置的 false
     * 就永久生效——组件挂载完标志位直接卡死在 false，poll 的第一次响应回来就被
     * 挡返回：进度不更新、定时器不续期、FAILED 不 toast、爬完不通知父组件。
     * 只在 `pnpm dev` 下发作，`vite build` 的生产包不受影响，React 18 起也没有
     * console 警告——所以极难靠人工发现，正是这条测试存在的理由。
     *
     * ⚠️ 判别性只在 DOM 断言上：请求本身在守卫**之前**就发出去了，
     * 所以 getCrawlProgress 的调用次数在变异前后都是 1，拿它断言抓不到东西。
     *
     * `listCrawlSites` 的调用次数断言不是为了守护上面这条缺陷（挂载 effect 在
     * StrictMode 下本来就跑两次，首次 loadSites 变成 2 次与 isMountedRef 无关），
     * 而是这条测试自身的**前提自证**：`strictModeDoubleInvoke.test.tsx` 只证明了
     * "vitest 下 StrictMode 会双调用"这个工具链事实，证不了"这条测试真的传了
     * `{ strict: true }`"——若哪天有人手滑去掉那个参数，变异前后 StrictMode 都不
     * 生效，cleanup 根本不会跑，`isMountedRef` 一直是初值 `true`，本条测试在
     * 正确实现与变异实现下会**同时绿**、隔壁的哨兵也不会红，静默退化成永真。
     * `listCrawlSites` 恰好是 2 次（StrictMode 生效）还是 1 次（不生效）能在
     * 测试内部把这件事钉住，不用依赖隔壁文件的哨兵。T3 的 mock 全程返回
     * RUNNING、不会进入 poll 收尾分支，所以不会有额外的 loadSites 调用，
     * 次数在整条测试期间恰好稳定在 2。
     */
    it('StrictMode 双调用后轮询照常工作（isMountedRef 在 setup 阶段被复位）', async () => {
        vi.mocked(knowledgeService.recrawlSite).mockResolvedValue(ok('job-1'));
        vi.mocked(knowledgeService.getCrawlProgress).mockResolvedValue(ok(progress({ crawled: 5 })));

        await renderList({ strict: true });
        expect(knowledgeService.listCrawlSites).toHaveBeenCalledTimes(2);

        clickRecrawl();
        await flush();

        await flush(2000);

        expect(screen.getByText(/已爬 5/)).toBeInTheDocument();
    });

    /**
     * T5｜变异实验：删掉 CrawlSiteList.tsx:171 那行
     * `if (res.code !== 200) throw new Error(res.message);`，本条必须转红。
     *
     * 守的是本仓库的全局约定：业务异常走「HTTP 200 + body.code」，axios 不会 reject，
     * 不显式判 code 就会把后端的业务错误静默吞掉——用户点按钮毫无反应且没有任何报错。
     * 变异后 res.data 是 null，`if (res.data) poll(...)` 自然不进，于是既不轮询也不报错，
     * 正是「静默」那个形态。
     */
    it('重新爬取返回业务错误时报错而非静默，且不启动轮询', async () => {
        vi.mocked(knowledgeService.recrawlSite).mockResolvedValue(fail<string>('站点不存在'));

        await renderList();
        clickRecrawl();
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '重新爬取失败',
            description: '站点不存在',
        }));

        await flush(4000);
        expect(knowledgeService.getCrawlProgress).not.toHaveBeenCalled();
    });
});

/**
 * 写操作与对话框路径。
 *
 * 这一组补的是上一批的覆盖缺口（全分支终审点名）：当时 CrawlSiteDialog 被桩成
 * `() => null`，于是 handleCreate / handleUpdate 一行都没执行过；删除路径走的是
 * 内联的 Radix Dialog、根本没被桩，只是没测到。
 *
 * 每条同样配了变异实验，实验方式记在各条注释里。
 */
describe('CrawlSiteList 写操作与对话框路径', () => {
    const dialog = () => screen.getByRole('dialog');
    const openDeleteDialog = () => fireEvent.click(screen.getByRole('button', { name: '删除' }));
    const clickStubSubmit = () => fireEvent.click(screen.getByRole('button', { name: 'stub-submit' }));

    /**
     * T6｜变异实验：删掉 CrawlSiteList.tsx:181 那行 `setDeleteWithDocs(false);`
     * （openDeleteDialog 里的复位），本条必须转红。
     *
     * ⚠️ 这条测试的构造是刻意的：**只断言「首次打开时未勾选」是永真的**——
     * `useState(false)` 的初值本来就是 false，把那行复位删掉照样绿。要守住它，
     * 必须走「勾上 → 关闭 → 重开」这条路：deleteWithDocs 是组件级 state、
     * 不随对话框关闭而重置，唯一让它回到 false 的就是那行复位。
     *
     * 守的行为本身：勾上表示「同时删除已抓取的文档与向量」，不可恢复。
     * 上一批评审专门把这里从「两次 confirm」改成「复选框默认不勾」，
     * 残留上次的勾选等于把不可恢复的操作变成默认动作。
     */
    it('删除对话框重开后不残留上次的勾选，未勾选时传 deleteDocuments=false', async () => {
        vi.mocked(knowledgeService.deleteCrawlSite).mockResolvedValue(ok<void>(undefined));

        await renderList();

        openDeleteDialog();
        fireEvent.click(within(dialog()).getByRole('checkbox'));
        expect(within(dialog()).getByRole('checkbox')).toBeChecked();

        // 关掉对话框（DialogContent 自带的那个 X），deleteTarget 置空、对话框卸载
        fireEvent.click(within(dialog()).getByRole('button', { name: 'Close' }));
        await flush();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

        openDeleteDialog();
        expect(within(dialog()).getByRole('checkbox')).not.toBeChecked();

        fireEvent.click(within(dialog()).getByRole('button', { name: '删除' }));
        await flush();

        expect(knowledgeService.deleteCrawlSite).toHaveBeenCalledWith(1, false);
    });

    /**
     * T7｜变异实验：把 CrawlSiteList.tsx:187 的 `deleteWithDocs` 写死成 `false`，
     * 本条必须转红——勾了也白勾，文档不会被一起删掉。
     * （反向变异「写死成 true」会让上面 T6 转红，两条合起来把这个参数钉在两头。）
     */
    it('勾选后删除传 deleteDocuments=true，并重查列表与通知父组件', async () => {
        vi.mocked(knowledgeService.deleteCrawlSite).mockResolvedValue(ok<void>(undefined));

        await renderList();
        openDeleteDialog();
        fireEvent.click(within(dialog()).getByRole('checkbox'));
        fireEvent.click(within(dialog()).getByRole('button', { name: '删除' }));
        await flush();

        expect(knowledgeService.deleteCrawlSite).toHaveBeenCalledWith(1, true);
        expect(knowledgeService.listCrawlSites).toHaveBeenCalledTimes(2); // 挂载 + 删除后重查
        expect(onCrawlFinished).toHaveBeenCalledTimes(1);
    });

    /**
     * T8｜变异实验：删掉 CrawlSiteList.tsx:146 那行 `if (res.data?.jobId) poll(res.data.jobId);`，
     * 本条必须转红——站点建好了但进度永远不出现，用户看不到爬取在跑。
     */
    it('新建站点成功后重查列表并按返回的 jobId 启动轮询', async () => {
        vi.mocked(knowledgeService.addCrawlSite).mockResolvedValue(ok({ siteId: 1, jobId: 'job-9' }));
        vi.mocked(knowledgeService.getCrawlProgress).mockResolvedValue(ok(progress({ jobId: 'job-9', crawled: 4 })));

        await renderList();
        fireEvent.click(screen.getByRole('button', { name: '爬取整个站点' }));
        expect(screen.getByText('stub-mode:create')).toBeInTheDocument();

        clickStubSubmit();
        await flush();

        expect(knowledgeService.addCrawlSite).toHaveBeenCalledWith(7, { entryUrl: 'https://example.com/help/' });
        expect(knowledgeService.listCrawlSites).toHaveBeenCalledTimes(2);

        await flush(2000);
        expect(knowledgeService.getCrawlProgress).toHaveBeenCalledWith('job-9');
        expect(screen.getByText(/已爬 4/)).toBeInTheDocument();
    });

    /**
     * T9｜变异实验有两处，任一处都必须让本条转红：
     * ① 删掉 CrawlSiteList.tsx:144 的 `if (res.code !== 200) throw new Error(res.message);`
     *    → toast 断言落空（业务错误被静默吞掉，同 T5 守的那条全局约定）；
     * ② 删掉 CrawlSiteList.tsx:150 的 `throw error;`
     *    → stubSubmit.rejected 仍为 false。那行是对话框「提交失败就别关闭、
     *    让用户改完重试」的唯一依据，删掉它对话框会当成功关掉、用户的输入全丢。
     */
    it('新建站点返回业务错误时报错，且把失败抛回对话框（不静默关闭）', async () => {
        vi.mocked(knowledgeService.addCrawlSite)
            .mockResolvedValue(fail<{ siteId: number; jobId: string }>('入口地址重复'));

        await renderList();
        fireEvent.click(screen.getByRole('button', { name: '爬取整个站点' }));
        clickStubSubmit();
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '添加爬取站点失败',
            description: '入口地址重复',
        }));
        expect(stubSubmit.rejected).toBe(true);

        await flush(4000);
        expect(knowledgeService.getCrawlProgress).not.toHaveBeenCalled();
    });

    /**
     * T10｜变异实验：把 CrawlSiteList.tsx:263 的
     * `onSubmit={editing ? handleUpdate : handleCreate}` 三元反过来，本条必须转红——
     * 点「编辑」保存会变成新建一个站点，而原站点的配置改动全丢。
     */
    it('编辑站点走 updateCrawlSite 而非 addCrawlSite', async () => {
        vi.mocked(knowledgeService.updateCrawlSite).mockResolvedValue(ok(site1));

        await renderList();
        fireEvent.click(screen.getByRole('button', { name: '编辑' }));
        expect(screen.getByText('stub-mode:edit')).toBeInTheDocument();

        clickStubSubmit();
        await flush();

        expect(knowledgeService.updateCrawlSite).toHaveBeenCalledWith(1, { entryUrl: 'https://example.com/help/' });
        expect(knowledgeService.addCrawlSite).not.toHaveBeenCalled();
        expect(knowledgeService.listCrawlSites).toHaveBeenCalledTimes(2);
    });
});

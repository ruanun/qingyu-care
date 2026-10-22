import { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { knowledgeService } from '@/api/knowledge';
import type { CrawlProgress, CrawlSite, CrawlSiteRequest } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { CrawlSiteDialog } from './CrawlSiteDialog';

/**
 * 爬取站点列表：重新爬取 / 编辑配置 / 删除（可选连带删文档）+ 进度轮询。
 *
 * 父组件 `KnowledgePage` 必须给本组件传 `key={knowledgeBaseId}`——切换知识库时
 * 靠 key 变化强制重新挂载，才能让下面的定时器与进度状态随旧知识库一起清空，
 * 不会把已经切走的知识库的轮询结果误显示在新知识库的页面上。
 *
 * @author ruan
 */
interface Props {
    knowledgeBaseId: number;
    /** 爬取结束后通知父组件刷新文档列表 */
    onCrawlFinished: () => void;
}

const INTERVAL_LABELS: Record<string, string> = {
    MANUAL: '不自动同步', HOURLY: '每小时', DAILY: '每天', WEEKLY: '每周',
};

export function CrawlSiteList({ knowledgeBaseId, onCrawlFinished }: Props) {
    const [sites, setSites] = useState<CrawlSite[]>([]);
    // 按 jobId 索引，而不是按 siteId：后端全局最多允许 2 个任务并发，站点级互斥
    // 只保证"同一站点不会同时有两条 RUNNING 记录"，但旧任务收尾（判终态→清理→
    // 刷新列表）与用户对同一站点发起的新任务之间仍有时序竞态窗口——按 jobId 索引
    // 能让两条轮询链各自持有独立的 key，旧任务的收尾清理不会误删新任务刚写入的
    // 定时器/进度记录（若改按 siteId 索引，旧任务收尾时的 delete 有极小概率覆盖
    // 新任务，虽然概率很低但没必要留这个口子）
    const [progressMap, setProgressMap] = useState<Record<string, CrawlProgress>>({});
    const [editing, setEditing] = useState<CrawlSite | null>(null);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<CrawlSite | null>(null);
    const [deleteWithDocs, setDeleteWithDocs] = useState(false);
    const timersRef = useRef<Record<string, number>>({});
    // 见下方卸载 effect：只用来在异步回调里判断"此刻组件还挂载着吗"，
    // 不驱动任何渲染，所以特意不用 state
    const isMountedRef = useRef(true);
    const { toast } = useToast();

    const loadSites = useCallback(async () => {
        try {
            const res = await knowledgeService.listCrawlSites(knowledgeBaseId);
            if (res.code !== 200) throw new Error(res.message);
            setSites(res.data ?? []);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '获取爬取站点失败', description: message });
        }
    }, [knowledgeBaseId, toast]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        void loadSites();
    }, [loadSites]);

    // 组件卸载时清掉全部轮询定时器（同一知识库下可能同时有多个任务在跑），并
    // 翻转 isMountedRef——前者只能拦住"还没发出去的"下一次调度，拦不住"卸载那一刻
    // 恰好已经在飞的请求"：那次请求的响应回来后，tick 会继续往下走（见下方 poll
    // 里的判断），若不靠 isMountedRef 挡住，它在 RUNNING 分支里重新挂的 setTimeout
    // 完全不受 React 生命周期管辖，会在这个孤儿 timersRef 对象上自续期到该任务
    // 真正到达终态为止，且终态时仍会调旧闭包的 onCrawlFinished() 用旧知识库的
    // 文档列表覆盖用户正在看的新知识库列表——不是只发生一次，链条会一直续到底。
    // 依赖数组用 `[]` 的前提是父组件已给本组件传 key={knowledgeBaseId}——切知识库
    // 会让 React 真正卸载旧实例而不是仅仅更新 props，这里才会在正确的时机触发；
    // 若哪天有人删掉那个 key，这个假设就不成立了，需要跟着重新评估
    //
    // ⚠️ setup 阶段必须把 isMountedRef 复位成 true，不能只在 cleanup 里置 false：
    // 本仓库 main.tsx 全局开着 <StrictMode>，dev 环境下每个 effect 都会额外跑一轮
    // setup→cleanup→setup（官方要求 cleanup 必须让人分不清 effect 到底被调用了
    // 一次还是三次）。如果 setup 是空的，两次 setup 都不会把标志位改回 true，
    // cleanup 那次 false 就永久生效——组件挂载完 isMountedRef.current 直接卡死在
    // false，poll() 第一次响应回来就被挡返回：进度不更新、定时器不续期、FAILED
    // 不 toast、爬完也不调 onCrawlFinished。这条只在 dev（`pnpm dev`）下发作，
    // `vite build` 的生产包不受影响（StrictMode 双调用只在开发环境生效），
    // 但 dev 正是本项目的标准开发方式，等于把开发体验坑没了。
    useEffect(() => {
        isMountedRef.current = true;
        // timersRef.current 本身是一个稳定的对象引用（只在组件生命周期内被 poll()
        // 持续增删条目，从不被重新赋值），这里在 setup 阶段捕获一次即可安全地在
        // cleanup 里复用同一个对象，顺带满足 exhaustive-deps 对"cleanup 里读
        // ref.current 到时候值可能已变"的检查（虽然本例不是 DOM ref，不存在那个
        // 场景，但按建议改成这样也不吃亏，反而更明确这个 ref 的身份）
        const timers = timersRef.current;
        return () => {
            isMountedRef.current = false;
            Object.values(timers).forEach((id) => window.clearTimeout(id));
        };
    }, []);

    const poll = useCallback((jobId: string) => {
        const tick = async () => {
            let p: CrawlProgress | null = null;
            try {
                const res = await knowledgeService.getCrawlProgress(jobId);
                // 业务异常（如应用重启后任务已从内存丢失）走本仓库既定的
                // "HTTP 200 + body.code" 约定，不会被 axios 当错误抛出；
                // 此时 res.data 为 null，下面的 p 判空分支会自然停止轮询
                p = res.code === 200 ? res.data : null;
            } catch {
                // 网络异常等意外情况：视同拿不到进度，走下面的统一收尾
            }

            // 响应回来时组件可能已经卸载（见上方卸载 effect 的注释）：不管拿到的是
            // RUNNING 还是终态，都不能再产生任何副作用——不续期定时器、不更新状态、
            // 不刷新列表、不通知父组件。直接返回，不做下面的清理记账也没关系：
            // timersRef/progressMap 是这个已卸载实例自己的，不会再被任何人读取
            if (!isMountedRef.current) return;

            if (p && p.status === 'RUNNING') {
                setProgressMap((prev) => ({ ...prev, [jobId]: p }));
                timersRef.current[jobId] = window.setTimeout(tick, 2000);
                return;
            }
            if (p && p.status === 'FAILED') {
                // 下面会把这条进度记录立即移除，失败原因只在这里露一次面——
                // 不额外 toast 的话用户完全不会知道这次爬取失败了
                toast({ variant: 'destructive', title: '爬取失败', description: p.errorMessage ?? undefined });
            }
            // 终态 / 业务错误 / 异常都在这里统一收尾：从定时器表与进度表里移除
            // 这个 jobId，避免残留展示或组件卸载后继续发请求
            delete timersRef.current[jobId];
            setProgressMap((prev) => {
                if (!(jobId in prev)) return prev;
                const next = { ...prev };
                delete next[jobId];
                return next;
            });
            await loadSites();
            onCrawlFinished();
        };
        timersRef.current[jobId] = window.setTimeout(tick, 2000);
    }, [loadSites, onCrawlFinished, toast]);

    const handleCreate = async (req: CrawlSiteRequest) => {
        try {
            const res = await knowledgeService.addCrawlSite(knowledgeBaseId, req);
            if (res.code !== 200) throw new Error(res.message);
            await loadSites();
            if (res.data?.jobId) poll(res.data.jobId);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '添加爬取站点失败', description: message });
            throw error; // 让对话框知道提交失败，不要关闭，方便用户修正后重试
        }
    };

    const handleUpdate = async (req: CrawlSiteRequest) => {
        if (!editing) return;
        try {
            const res = await knowledgeService.updateCrawlSite(editing.id, req);
            if (res.code !== 200) throw new Error(res.message);
            setEditing(null);
            await loadSites();
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '更新站点配置失败', description: message });
            throw error;
        }
    };

    const handleRecrawl = async (site: CrawlSite) => {
        try {
            const res = await knowledgeService.recrawlSite(site.id);
            if (res.code !== 200) throw new Error(res.message);
            if (res.data) poll(res.data);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '重新爬取失败', description: message });
        }
    };

    const openDeleteDialog = (site: CrawlSite) => {
        setDeleteTarget(site);
        setDeleteWithDocs(false); // 破坏性操作默认关闭，需用户主动勾选
    };

    const handleConfirmDelete = async () => {
        if (!deleteTarget) return;
        try {
            const res = await knowledgeService.deleteCrawlSite(deleteTarget.id, deleteWithDocs);
            if (res.code !== 200) throw new Error(res.message);
            setDeleteTarget(null);
            await loadSites();
            onCrawlFinished();
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '删除站点失败', description: message });
        }
    };

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between">
                <h4 className="text-sm font-medium">爬取站点</h4>
                <Button size="sm" variant="outline" onClick={() => { setEditing(null); setDialogOpen(true); }}>
                    爬取整个站点
                </Button>
            </div>

            {sites.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                    还没有爬取站点。给一个帮助中心的入口地址，就能把整个目录抓进这个知识库。
                </p>
            ) : (
                <div className="space-y-2">
                    {sites.map((site) => {
                        // progressMap 按 jobId 索引，站点级展示需要反查——后端站点级
                        // 互斥保证同一站点任意时刻至多一条 RUNNING 记录，线性扫描足够快
                        const siteProgress = Object.values(progressMap).find((p) => p.siteId === site.id) ?? null;
                        return (
                            <div key={site.id} className="rounded-md border p-3 space-y-2">
                                <div className="flex items-center justify-between">
                                    <div className="min-w-0 space-y-0.5">
                                        <p className="text-sm truncate">{site.entryUrl}</p>
                                        <p className="text-xs text-muted-foreground">
                                            范围 {site.scopePrefix} · 最多 {site.maxPages} 页 / 深度 {site.maxDepth}
                                            {site.contentSelector ? ` · 选择器 ${site.contentSelector}` : ' · 自动去噪'}
                                            {' · '}{INTERVAL_LABELS[site.syncInterval] ?? site.syncInterval}
                                            {site.lastCrawledAt ? ` · 上次爬取 ${site.lastCrawledAt.replace('T', ' ').slice(0, 16)}` : ' · 尚未爬取'}
                                        </p>
                                    </div>
                                    <div className="flex gap-2 shrink-0">
                                        <Button size="sm" variant="outline" onClick={() => void handleRecrawl(site)}>
                                            重新爬取
                                        </Button>
                                        <Button size="sm" variant="ghost"
                                                onClick={() => { setEditing(site); setDialogOpen(true); }}>
                                            编辑
                                        </Button>
                                        <Button size="sm" variant="ghost" onClick={() => openDeleteDialog(site)}>
                                            删除
                                        </Button>
                                    </div>
                                </div>
                                {siteProgress && (
                                    <div className="rounded-md bg-muted/40 p-2 text-xs space-y-1">
                                        {/* progressMap 里只会存 RUNNING 记录（终态/失败在 poll 收尾时
                                            立即被移除），所以这里不需要再按 status 分支渲染 */}
                                        <span className="font-medium">正在爬取…</span>{' '}
                                        <span className="text-muted-foreground">
                                            已爬 {siteProgress.crawled} / 上限 {siteProgress.maxPages} 页
                                            · 新建 {siteProgress.created} · 更新 {siteProgress.updated} · 失败 {siteProgress.failed}
                                        </span>
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}

            <CrawlSiteDialog
                open={dialogOpen}
                onOpenChange={(open) => { setDialogOpen(open); if (!open) setEditing(null); }}
                initial={editing}
                onSubmit={editing ? handleUpdate : handleCreate}
            />

            <Dialog open={!!deleteTarget} onOpenChange={(open) => { if (!open) setDeleteTarget(null); }}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>删除爬取站点</DialogTitle>
                        <DialogDescription>
                            即将删除站点「{deleteTarget?.entryUrl}」的爬取配置。
                        </DialogDescription>
                    </DialogHeader>
                    <div className="space-y-4">
                        <label className="flex items-start gap-2 text-sm">
                            <Checkbox
                                className="mt-0.5"
                                checked={deleteWithDocs}
                                onCheckedChange={(v) => setDeleteWithDocs(v === true)}
                            />
                            <span>
                                同时删除该站点已抓取的文档与向量（不可恢复）。不勾选时文档保留，
                                会退化成普通网页文档，各自继续按原周期定期同步。
                            </span>
                        </label>
                        <Button
                            className="w-full"
                            variant={deleteWithDocs ? 'destructive' : 'default'}
                            onClick={() => void handleConfirmDelete()}
                        >
                            删除
                        </Button>
                    </div>
                </DialogContent>
            </Dialog>
        </div>
    );
}

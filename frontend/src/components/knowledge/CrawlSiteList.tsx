import { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
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

    // 组件卸载时清掉全部轮询定时器（同一知识库下可能同时有多个任务在跑）。
    // 依赖数组用 `[]` 的前提是父组件已给本组件传 key={knowledgeBaseId}——切知识库
    // 会让 React 真正卸载旧实例而不是仅仅更新 props，这里才会在正确的时机触发；
    // 若哪天有人删掉那个 key，这个假设就不成立了，需要跟着重新评估
    useEffect(() => () => {
        Object.values(timersRef.current).forEach((id) => window.clearTimeout(id));
    }, []);

    const poll = useCallback((jobId: string) => {
        const tick = async () => {
            try {
                const res = await knowledgeService.getCrawlProgress(jobId);
                // 业务异常（如应用重启后任务已从内存丢失）走本仓库既定的
                // "HTTP 200 + body.code" 约定，不会被 axios 当错误抛出；
                // 此时 res.data 为 null，下面的 p 判空分支会自然停止轮询
                const p = res.code === 200 ? res.data : null;
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
            } catch {
                // 网络异常等意外情况：同样静默结束该任务的轮询
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
                            <input
                                type="checkbox"
                                className="mt-0.5"
                                checked={deleteWithDocs}
                                onChange={(e) => setDeleteWithDocs(e.target.checked)}
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

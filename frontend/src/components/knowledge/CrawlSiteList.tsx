import { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { knowledgeService } from '@/api/knowledge';
import type { CrawlProgress, CrawlSite, CrawlSiteRequest } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { CrawlSiteDialog } from './CrawlSiteDialog';

/**
 * 爬取站点列表：重新爬取 / 编辑配置 / 删除（可选连带删文档）+ 进度轮询。
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
    const [progress, setProgress] = useState<CrawlProgress | null>(null);
    const [editing, setEditing] = useState<CrawlSite | null>(null);
    const [dialogOpen, setDialogOpen] = useState(false);
    const timerRef = useRef<number | null>(null);
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

    // 组件卸载时清掉轮询定时器，避免切换知识库后仍在轮询已结束的任务
    useEffect(() => () => {
        if (timerRef.current) window.clearTimeout(timerRef.current);
    }, []);

    const poll = useCallback((jobId: string) => {
        const tick = async () => {
            try {
                const res = await knowledgeService.getCrawlProgress(jobId);
                // 业务异常（如应用重启后任务已从内存丢失）走本仓库既定的
                // "HTTP 200 + body.code" 约定，不会被 axios 当错误抛出；
                // 此时 res.data 为 null，下面的 p 判空分支会自然停止轮询
                const p = res.code === 200 ? res.data : null;
                setProgress(p ?? null);
                if (p && p.status === 'RUNNING') {
                    timerRef.current = window.setTimeout(tick, 2000);
                    return;
                }
            } catch {
                // 网络异常等意外情况：同样静默停止轮询，已建文档不受影响
            }
            await loadSites();
            onCrawlFinished();
        };
        timerRef.current = window.setTimeout(tick, 2000);
    }, [loadSites, onCrawlFinished]);

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

    const handleDelete = async (site: CrawlSite) => {
        const withDocs = window.confirm(
            `删除站点「${site.entryUrl}」。\n\n点「确定」= 同时删除该站点抓取的全部文档与向量（不可恢复）。\n`
            + '点「取消」后会再问一次是否只删配置、保留文档。'
        );
        if (!withDocs) {
            const keepDocs = window.confirm('只删除站点配置、保留已抓取的文档？（文档会变成普通网页文档，各自继续定期同步）');
            if (!keepDocs) return;
        }
        try {
            const res = await knowledgeService.deleteCrawlSite(site.id, withDocs);
            if (res.code !== 200) throw new Error(res.message);
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

            {progress && (
                <div className="rounded-md border p-3 text-sm space-y-1">
                    <div className="flex items-center gap-2">
                        <span className="font-medium">
                            {progress.status === 'RUNNING' ? '正在爬取…'
                                : progress.status === 'COMPLETED' ? '爬取完成' : '爬取失败'}
                        </span>
                        <span className="text-muted-foreground">
                            已爬 {progress.crawled} / 上限 {progress.maxPages} 页
                            · 新建 {progress.created} · 更新 {progress.updated} · 失败 {progress.failed}
                        </span>
                    </div>
                    {progress.errorMessage && (
                        <p className="text-xs text-destructive">{progress.errorMessage}</p>
                    )}
                </div>
            )}

            {sites.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                    还没有爬取站点。给一个帮助中心的入口地址，就能把整个目录抓进这个知识库。
                </p>
            ) : (
                <div className="space-y-2">
                    {sites.map((site) => (
                        <div key={site.id} className="flex items-center justify-between rounded-md border p-3">
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
                                <Button size="sm" variant="ghost" onClick={() => void handleDelete(site)}>
                                    删除
                                </Button>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <CrawlSiteDialog
                open={dialogOpen}
                onOpenChange={(open) => { setDialogOpen(open); if (!open) setEditing(null); }}
                initial={editing}
                onSubmit={editing ? handleUpdate : handleCreate}
            />
        </div>
    );
}

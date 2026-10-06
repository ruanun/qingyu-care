import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, ArrowRightLeft, Boxes, Loader2, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { embeddingService, isJobPending } from '@/api/embedding';
import type { EmbeddingModelSummary, EmbeddingState } from '@/api/embedding';
import { useToast } from '@/hooks/use-toast';
import { useConfirm } from '@/hooks/use-confirm';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

/** 迁移进行中时的轮询间隔 */
const POLL_MS = 3000;

const modelLabel = (m: EmbeddingModelSummary) => `${m.name}（${m.modelId}）`;

/**
 * 知识库页顶部的「向量模型」卡片：当前应用用哪个向量模型、切换、切换进度。
 *
 * 向量模型绑在应用上（检索固定是「本应用全部启用的知识库」一起查），切换走后端的影子重建：
 * 新模型的向量在后台补齐，期间检索继续用当前模型，完成后自动切过去。
 *
 * @author ruan
 */
export function EmbeddingModelCard() {
    const [state, setState] = useState<EmbeddingState | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [targetId, setTargetId] = useState<string>('');
    const [submitting, setSubmitting] = useState(false);
    const isMountedRef = useRef(true);
    const timerRef = useRef<number | null>(null);
    const { toast } = useToast();
    const { confirm, confirmDialog } = useConfirm();

    const load = useCallback(async () => {
        try {
            const res = await embeddingService.getState();
            throwIfFailed(res);
            if (!isMountedRef.current) return;
            setState(res.data);
            setLoadError(null);
        } catch (error: unknown) {
            if (isMountedRef.current) setLoadError(getErrorMessage(error));
        }
    }, []);

    useEffect(() => {
        // 必须在 setup 里复位：StrictMode 下 effect 会 setup→cleanup→setup，只在声明处置 true 的话
        // 第一次 cleanup 之后它就永远是 false，所有异步回调都被当成「已卸载」丢弃
        isMountedRef.current = true;
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 挂载即拉一次状态
        load();
        return () => {
            isMountedRef.current = false;
            if (timerRef.current !== null) window.clearTimeout(timerRef.current);
        };
    }, [load]);

    // 迁移在跑就轮询；停下（完成 / 失败 / 中断 / 取消）就停
    const jobStatus = state?.job?.status;
    useEffect(() => {
        if (jobStatus !== 'RUNNING' && jobStatus !== 'CANCELLING') return;
        timerRef.current = window.setTimeout(() => {
            load();
        }, POLL_MS);
        return () => {
            if (timerRef.current !== null) window.clearTimeout(timerRef.current);
        };
    }, [jobStatus, state, load]);

    const openDialog = () => {
        setTargetId('');
        setDialogOpen(true);
    };

    const handleSwitch = async () => {
        if (!targetId) return;
        setSubmitting(true);
        try {
            const res = await embeddingService.switchModel(Number(targetId));
            throwIfFailed(res);
            toast({ title: res.message || '已提交' });
            setDialogOpen(false);
            await load();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '切换失败', description: getErrorMessage(error) });
        } finally {
            setSubmitting(false);
        }
    };

    const handleRebuild = async () => {
        const ok = await confirm({
            title: '用当前模型重建全部向量？',
            description: '会用当前向量模型把本应用全部文档与问答重新向量化（按内容量消耗 embedding 调用）。'
                + '逐篇替换，重建期间检索不受影响；中途可以取消，已重建的部分保留。'
                + '一般只在平台升级了向量化方式后才需要。',
            confirmText: '开始重建',
        });
        if (!ok) return;
        try {
            const res = await embeddingService.rebuild();
            throwIfFailed(res);
            toast({ title: '已开始重建向量' });
            await load();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '重建失败', description: getErrorMessage(error) });
        }
    };

    const handleCancel = async (jobId: number) => {
        try {
            const res = await embeddingService.cancelJob(jobId);
            throwIfFailed(res);
            toast({ title: '已取消', description: state?.job?.kind === 'REBUILD' ? '已重建的部分保留' : '应用继续使用原来的向量模型' });
            await load();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '取消失败', description: getErrorMessage(error) });
        }
    };

    const handleResume = async (jobId: number) => {
        try {
            const res = await embeddingService.resumeJob(jobId);
            throwIfFailed(res);
            toast({ title: '已继续重建' });
            await load();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '继续失败', description: getErrorMessage(error) });
        }
    };

    if (loadError && !state) {
        return (
            <div className="rounded-xl border border-dashed p-4 text-sm text-muted-foreground">
                向量模型状态加载失败：{loadError}
            </div>
        );
    }
    if (!state) {
        return (
            <div className="rounded-xl border p-4 flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="w-4 h-4 animate-spin" /> 正在加载向量模型…
            </div>
        );
    }

    const { active, pending, job, options, dimensions, manualThresholdStrategies } = state;
    const isRebuild = job?.kind === 'REBUILD';
    const migrating = isJobPending(job);
    const choices = options.filter((o) => o.id !== active?.id);
    const target = choices.find((o) => String(o.id) === targetId) ?? null;
    const sameSpace = !!target && !!active && target.modelId.trim() === active.modelId.trim();
    const percent = job && job.total ? Math.round(((job.done ?? 0) + (job.failed ?? 0)) * 100 / job.total) : 0;

    return (
        <div className="rounded-xl border bg-card p-4 space-y-3" data-testid="embedding-model-card">
            <div className="flex items-start justify-between gap-4">
                <div className="flex items-start gap-3 min-w-0">
                    <div className="w-9 h-9 rounded-lg bg-primary/10 text-primary flex items-center justify-center shrink-0">
                        <Boxes className="w-4 h-4" />
                    </div>
                    <div className="min-w-0">
                        <div className="text-sm font-semibold">向量模型</div>
                        {active ? (
                            <div className="flex flex-wrap items-center gap-2 mt-1 text-xs text-muted-foreground">
                                <span className="text-foreground">{active.name}</span>
                                <Badge variant="secondary" className="font-mono text-[10px]">{active.modelId}</Badge>
                                <Badge variant="outline" className="text-[10px]">{active.platform ? '平台提供' : '本应用自有'}</Badge>
                                {active.similarityThreshold != null && <span>推荐阈值 {active.similarityThreshold}</span>}
                                <span>{dimensions} 维</span>
                            </div>
                        ) : (
                            <p className="mt-1 text-xs text-amber-600">平台尚未配置可用的向量模型，文档暂时无法向量化。请联系平台管理员在「共享模型」中添加。</p>
                        )}
                        <p className="mt-1 text-[11px] text-muted-foreground">本应用所有知识库共用这一个模型。换模型会在后台重建向量，期间检索不受影响。</p>
                    </div>
                </div>
                <div className="flex gap-2 shrink-0">
                    <Button variant="ghost" size="sm" className="gap-1" onClick={handleRebuild}
                            disabled={migrating || !active}
                            title="用当前模型重新向量化全部内容（平台升级向量化方式后使用）">
                        <RefreshCw className="w-3.5 h-3.5" /> 重建向量
                    </Button>
                    <Button variant="outline" size="sm" className="gap-1" onClick={openDialog}
                            disabled={migrating || choices.length === 0}
                            title={choices.length === 0 ? '没有其他可选的向量模型，可在「模型配置 → 向量模型」中添加' : undefined}>
                        <ArrowRightLeft className="w-3.5 h-3.5" /> 更换
                    </Button>
                </div>
            </div>

            {migrating && job && (
                <div className="rounded-lg bg-muted/40 p-3 space-y-2" data-testid="embedding-migration">
                    <div className="flex items-center justify-between gap-2 text-xs">
                        <span>
                            {job.status === 'RUNNING' && isRebuild && <>正在用当前模型重建向量（{(job.done ?? 0) + (job.failed ?? 0)} / {job.total ?? '…'}），检索不受影响</>}
                            {job.status === 'RUNNING' && !isRebuild && <>正在为 <b>{pending?.name ?? job.toTag}</b> 重建向量（{(job.done ?? 0) + (job.failed ?? 0)} / {job.total ?? '…'}），检索仍使用当前模型</>}
                            {job.status === 'CANCELLING' && '正在取消…'}
                            {(job.status === 'FAILED' || job.status === 'INTERRUPTED') && (job.message ?? '切换未完成')}
                        </span>
                        <div className="flex gap-2 shrink-0">
                            {(job.status === 'FAILED' || job.status === 'INTERRUPTED') && (
                                <Button size="sm" variant="outline" className="h-7 text-xs" onClick={() => handleResume(job.id)}>继续</Button>
                            )}
                            {job.status !== 'CANCELLING' && (
                                <Button size="sm" variant="ghost" className="h-7 text-xs" onClick={() => handleCancel(job.id)}>{isRebuild ? '取消重建' : '取消切换'}</Button>
                            )}
                        </div>
                    </div>
                    {job.status === 'RUNNING' && <Progress value={percent} className="h-1.5" />}
                    {(job.failed ?? 0) > 0 && (
                        <p className="text-[11px] text-amber-600">{isRebuild ? `${job.failed} 项重建失败，仍保留旧向量` : `${job.failed} 项暂未写入新模型，收尾时会自动重试`}</p>
                    )}
                </div>
            )}

            <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
                <DialogContent className="sm:max-w-[520px]">
                    <DialogHeader>
                        <DialogTitle>更换向量模型</DialogTitle>
                        <DialogDescription>
                            当前：{active ? modelLabel(active) : '未配置'}。可选的是本应用自己添加的与平台提供的向量模型。
                        </DialogDescription>
                    </DialogHeader>
                    <div className="space-y-4 py-2">
                        <Select value={targetId} onValueChange={setTargetId}>
                            <SelectTrigger aria-label="目标向量模型">
                                <SelectValue placeholder="选择要切换到的向量模型" />
                            </SelectTrigger>
                            <SelectContent>
                                {choices.map((o) => (
                                    <SelectItem key={o.id} value={String(o.id)}>
                                        {modelLabel(o)}{o.platform ? ' · 平台' : ''}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                        {target && (
                            <div className="rounded-lg border p-3 text-xs space-y-1.5 text-muted-foreground">
                                {sameSpace ? (
                                    <p>与当前是<b className="text-foreground">同一个模型</b>（只是换了托管或 Key），已有向量可以直接沿用，切换立即生效。</p>
                                ) : (
                                    <>
                                        <p>将在后台用新模型<b className="text-foreground">重建本应用全部文档与问答的向量</b>，会按内容量调用新模型的 embedding 接口。</p>
                                        <p>重建期间检索继续使用当前模型，完成后自动切换并清理旧向量；中途可以随时取消。</p>
                                    </>
                                )}
                                {!sameSpace && manualThresholdStrategies.length > 0 && (
                                    <p className="text-amber-600 flex gap-1">
                                        <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-px" />
                                        <span>
                                            以下问答策略的相似度阈值是手动设定的、不跟随向量模型：
                                            {manualThresholdStrategies.map((s) => `「${s.name}」(${s.similarityThreshold})`).join('、')}。
                                            不同模型的分数刻度不同，切换后请到「问答策略」里复核。
                                        </span>
                                    </p>
                                )}
                            </div>
                        )}
                    </div>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => setDialogOpen(false)}>取消</Button>
                        <Button onClick={handleSwitch} disabled={!target || submitting}>
                            {submitting && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                            {sameSpace ? '立即切换' : '开始重建并切换'}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
            {confirmDialog}
        </div>
    );
}

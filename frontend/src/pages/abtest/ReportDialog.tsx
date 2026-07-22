import React, { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import { Badge } from '@/components/ui/badge';
import {
    Table,
    TableBody,
    TableCell,
    TableHead,
    TableHeader,
    TableRow,
} from '@/components/ui/table';
import { abTestService, type AbTestReport, type ExperimentStatus, type VariantReport } from '@/api/abtest';
import { useToast } from '@/hooks/use-toast';

interface Props {
    experimentId: number | null;
    onClose: () => void;
}

const fmtPct = (v: number | null) => (v == null ? '—' : `${(v * 100).toFixed(1)}%`);
const fmtNum = (v: number | null) => (v == null ? '—' : v.toFixed(2));

// 对比表格的行定义，顺序固定：每行取一个指标，对每个变体渲染一个值
const METRIC_ROWS: { label: string; render: (v: VariantReport) => string }[] = [
    { label: '会话数', render: v => String(v.sessionCount) },
    { label: '提问数', render: v => String(v.questionCount) },
    { label: 'AI 回复数', render: v => String(v.aiMsgCount) },
    { label: '平均置信度', render: v => fmtNum(v.avgConfidence) },
    { label: '点赞率', render: v => fmtPct(v.likeRate) },
    { label: '赞 / 踩', render: v => `${v.feedbackUp} / ${v.feedbackDown}` },
    { label: '兜底率', render: v => fmtPct(v.fallbackRate) },
    { label: '低置信率', render: v => fmtPct(v.lowConfidenceRate) },
    { label: '知识命中率', render: v => fmtPct(v.hitRate) },
    { label: '转人工率', render: v => fmtPct(v.handoverRate) },
    { label: 'AI 解决率', render: v => fmtPct(v.resolvedRate) },
];

const formatDateTime = (value: string | null) => (value ? new Date(value).toLocaleString() : '-');

// 与 AbTestPage 列表页保持一致的状态徽标映射
const getStatusBadge = (status: ExperimentStatus) => {
    switch (status) {
        case 'DRAFT':
            return <Badge variant="secondary">草稿</Badge>;
        case 'RUNNING':
            return <Badge className="bg-green-500 hover:bg-green-600">运行中</Badge>;
        case 'ENDED':
            return <Badge variant="outline" className="text-muted-foreground">已结束</Badge>;
        default:
            return <Badge variant="outline">{status}</Badge>;
    }
};

/** A/B 实验报表对话框：行 = 指标，列 = 变体，用于查看各分组效果对比 */
const ReportDialog: React.FC<Props> = ({ experimentId, onClose }) => {
    const [report, setReport] = useState<AbTestReport | null>(null);
    const [isLoading, setIsLoading] = useState(false);
    const { toast } = useToast();

    // experimentId 变化时（含对话框关闭置 null）在渲染期同步重置报表与加载态；
    // 避免在 effect 内做首行同步 setState（react-hooks/set-state-in-effect 反模式，参考 CoverageTab 的 prevScopeKey 写法）
    const [prevExperimentId, setPrevExperimentId] = useState(experimentId);
    if (experimentId !== prevExperimentId) {
        setPrevExperimentId(experimentId);
        setReport(null);
        setIsLoading(experimentId != null);
    }

    useEffect(() => {
        if (experimentId == null) return;
        let cancelled = false;
        abTestService
            .report(experimentId)
            .then(res => {
                if (cancelled) return;
                if (res.code === 200) {
                    setReport(res.data);
                } else {
                    toast({ variant: 'destructive', title: '报表加载失败', description: res.message || '操作失败' });
                }
            })
            .catch((error: unknown) => {
                if (cancelled) return;
                const msg = error instanceof Error ? error.message : String(error);
                toast({ variant: 'destructive', title: '报表加载失败', description: msg });
            })
            .finally(() => {
                if (!cancelled) setIsLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [experimentId, toast]);

    return (
        <Dialog open={experimentId != null} onOpenChange={open => !open && onClose()}>
            <DialogContent className="sm:max-w-[850px]">
                <DialogHeader>
                    <DialogTitle className="flex items-center gap-2">
                        {report ? report.name : '实验报表'}
                        {report && getStatusBadge(report.status)}
                    </DialogTitle>
                </DialogHeader>

                {isLoading ? (
                    <div className="h-40 flex items-center justify-center">
                        <Loader2 className="w-6 h-6 animate-spin text-primary" />
                    </div>
                ) : !report ? (
                    <div className="h-40 flex items-center justify-center text-muted-foreground text-sm">
                        暂无报表数据
                    </div>
                ) : (
                    <div className="space-y-3">
                        <p className="text-xs text-muted-foreground">
                            {formatDateTime(report.startedAt)} ~ {formatDateTime(report.endedAt)}
                        </p>
                        <div className="border rounded-lg">
                            <Table>
                                <TableHeader>
                                    <TableRow>
                                        <TableHead>指标</TableHead>
                                        {report.variants.map(v => (
                                            <TableHead key={v.variantName}>{`${v.variantName} (${v.weight}%)`}</TableHead>
                                        ))}
                                    </TableRow>
                                </TableHeader>
                                <TableBody>
                                    {METRIC_ROWS.map(row => (
                                        <TableRow key={row.label}>
                                            <TableCell className="font-medium">{row.label}</TableCell>
                                            {report.variants.map(v => (
                                                <TableCell key={v.variantName}>{row.render(v)}</TableCell>
                                            ))}
                                        </TableRow>
                                    ))}
                                </TableBody>
                            </Table>
                        </div>
                    </div>
                )}
            </DialogContent>
        </Dialog>
    );
};

export default ReportDialog;

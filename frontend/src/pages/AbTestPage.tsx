import React, { useState, useEffect } from 'react';
import {
    Plus,
    Edit2,
    Trash2,
    Play,
    Square,
    BarChart3,
    FlaskConical,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
    Table,
    TableBody,
    TableCell,
    TableHead,
    TableHeader,
    TableRow,
} from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { abTestService, type AbExperiment, type AbVariant, type ExperimentStatus } from '@/api/abtest';
import { chatStrategyService, type ChatStrategy } from '@/api/strategy';
import { useToast } from '@/hooks/use-toast';
import ReportDialog from '@/pages/abtest/ReportDialog';

interface ExperimentForm {
    name: string;
    description: string;
    variants: AbVariant[];
}

// api/client.ts 响应拦截器统一 reject 为 Error 实例，此处兜底避免 no-explicit-any
const getErrorMessage = (error: unknown): string =>
    error instanceof Error ? error.message : String(error);

// api/client.ts 不对业务 code≠200 reject（后端 BusinessException 为 HTTP 200 + code 400），
// 调用方必须显式判断 code，此处统一提取避免各操作点判断口径不一致
type BasicApiResult = { code: number; message: string };

// 新建实验默认两个变体分组，各占 50% 流量
const buildEmptyForm = (): ExperimentForm => ({
    name: '',
    description: '',
    variants: [
        { name: 'A', strategyId: 0, weight: 50 },
        { name: 'B', strategyId: 0, weight: 50 },
    ],
});

const AbTestPage: React.FC = () => {
    const [experiments, setExperiments] = useState<AbExperiment[]>([]);
    const [strategies, setStrategies] = useState<ChatStrategy[]>([]);
    const [isLoading, setIsLoading] = useState(false);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [editing, setEditing] = useState<AbExperiment | null>(null);
    const [form, setForm] = useState<ExperimentForm>(buildEmptyForm());
    const [reportId, setReportId] = useState<number | null>(null);
    const { toast } = useToast();

    const fetchData = async () => {
        setIsLoading(true);
        try {
            const [expRes, stratRes] = await Promise.all([
                abTestService.list(),
                chatStrategyService.getAll(),
            ]);
            if (expRes.code === 200) {
                setExperiments(expRes.data);
            } else {
                toast({ variant: 'destructive', title: '实验列表加载失败', description: expRes.message || '操作失败' });
            }
            if (stratRes.code === 200) {
                setStrategies(stratRes.data);
            } else {
                toast({ variant: 'destructive', title: '策略列表加载失败', description: stratRes.message || '操作失败' });
            }
        } catch (error) {
            toast({ variant: 'destructive', title: '数据加载失败', description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, []);

    const enabledStrategies = strategies.filter(s => s.enabled);

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

    const getVariantSummary = (variants: AbVariant[]) =>
        variants.map(v => `${v.name} ${v.weight}%`).join(' · ');

    const formatDateTime = (value: string | null) => (value ? new Date(value).toLocaleString() : '-');

    const openCreateDialog = () => {
        setEditing(null);
        setForm(buildEmptyForm());
        setDialogOpen(true);
    };

    const openEditDialog = (experiment: AbExperiment) => {
        setEditing(experiment);
        setForm({
            name: experiment.name,
            description: experiment.description || '',
            variants: experiment.variants.map(v => ({ ...v })),
        });
        setDialogOpen(true);
    };

    const addVariantRow = () => {
        setForm(prev => ({
            ...prev,
            variants: [...prev.variants, { name: '', strategyId: 0, weight: 0 }],
        }));
    };

    const removeVariantRow = (index: number) => {
        setForm(prev => ({
            ...prev,
            variants: prev.variants.filter((_, i) => i !== index),
        }));
    };

    const updateVariantRow = (index: number, patch: Partial<AbVariant>) => {
        setForm(prev => ({
            ...prev,
            variants: prev.variants.map((v, i) => (i === index ? { ...v, ...patch } : v)),
        }));
    };

    // 提交前的前端校验；返回错误提示文案，通过则返回 null
    const validateForm = (): string | null => {
        if (!form.name.trim()) return '请填写实验名称';
        if (form.variants.length < 2) return '变体数量至少为 2 个';
        const names = form.variants.map(v => v.name.trim());
        if (names.some(n => !n)) return '变体名称不能为空';
        if (new Set(names).size !== names.length) return '变体名称不能重复';
        if (form.variants.some(v => !v.strategyId)) return '请为每个变体选择关联策略';
        const hasInvalidWeight = form.variants.some(
            v => !Number.isInteger(v.weight) || v.weight < 0 || v.weight > 100,
        );
        if (hasInvalidWeight) return '变体权重必须为 0~100 的整数';
        const weightSum = form.variants.reduce((sum, v) => sum + Number(v.weight || 0), 0);
        if (weightSum !== 100) return `变体权重之和须为 100（当前为 ${weightSum}）`;
        return null;
    };

    const handleSubmit = async () => {
        const errorMsg = validateForm();
        if (errorMsg) {
            toast({ variant: 'destructive', title: '表单校验未通过', description: errorMsg });
            return;
        }
        try {
            const payload: Partial<AbExperiment> = {
                name: form.name.trim(),
                description: form.description.trim() || null,
                variants: form.variants,
            };
            const res = editing
                ? await abTestService.update(editing.id, payload)
                : await abTestService.create(payload);
            if (res.code === 200) {
                toast({ title: editing ? '实验已更新' : '实验已创建' });
                setDialogOpen(false);
                fetchData();
            } else {
                toast({
                    variant: 'destructive',
                    title: editing ? '更新失败' : '创建失败',
                    description: res.message || '操作失败',
                });
            }
        } catch (error) {
            toast({ variant: 'destructive', title: '保存失败', description: getErrorMessage(error) });
        }
    };

    // 统一处理"启动/结束/删除"类操作：业务 code=200 才算成功并刷新列表，否则弹失败 toast；网络异常仍走 catch
    const runAction = async (
        action: () => Promise<BasicApiResult>,
        successTitle: string,
        failTitle: string,
    ) => {
        try {
            const res = await action();
            if (res.code === 200) {
                toast({ title: successTitle });
                fetchData();
            } else {
                toast({ variant: 'destructive', title: failTitle, description: res.message || '操作失败' });
            }
        } catch (error) {
            toast({ variant: 'destructive', title: failTitle, description: getErrorMessage(error) });
        }
    };

    const handleStart = (id: number) => runAction(() => abTestService.start(id), '实验已启动', '启动失败');

    const handleEnd = (id: number) => runAction(() => abTestService.end(id), '实验已结束', '结束失败');

    const handleDelete = (id: number) => {
        if (!confirm('确定删除该实验吗？')) return;
        runAction(() => abTestService.delete(id), '实验已删除', '删除失败');
    };

    return (
        <div className="p-8 h-full overflow-y-auto bg-slate-50/50">
            <div className="max-w-6xl mx-auto space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-2">
                            <FlaskConical className="w-6 h-6 text-primary" />
                            A/B 实验
                        </h1>
                        <p className="text-muted-foreground text-sm mt-1">
                            通过多套问答策略分桶对比效果，量化决策最优方案
                        </p>
                    </div>
                    <Button onClick={openCreateDialog}>
                        <Plus className="w-4 h-4 mr-2" />
                        新建实验
                    </Button>
                </div>

                <div className="border rounded-xl bg-background shadow-sm">
                    <Table>
                        <TableHeader>
                            <TableRow>
                                <TableHead>实验名</TableHead>
                                <TableHead>状态</TableHead>
                                <TableHead>变体概要</TableHead>
                                <TableHead>开始时间</TableHead>
                                <TableHead>结束时间</TableHead>
                                <TableHead className="text-right">操作</TableHead>
                            </TableRow>
                        </TableHeader>
                        <TableBody>
                            {isLoading ? (
                                <TableRow>
                                    <TableCell colSpan={6} className="h-40 text-center text-muted-foreground">
                                        加载中...
                                    </TableCell>
                                </TableRow>
                            ) : experiments.length === 0 ? (
                                <TableRow>
                                    <TableCell colSpan={6} className="h-40 text-center text-muted-foreground">
                                        暂无实验，点击“新建实验”开始创建
                                    </TableCell>
                                </TableRow>
                            ) : (
                                experiments.map(experiment => (
                                    <TableRow key={experiment.id}>
                                        <TableCell className="font-medium">{experiment.name}</TableCell>
                                        <TableCell>{getStatusBadge(experiment.status)}</TableCell>
                                        <TableCell className="text-sm text-muted-foreground">
                                            {getVariantSummary(experiment.variants)}
                                        </TableCell>
                                        <TableCell className="text-xs text-muted-foreground">
                                            {formatDateTime(experiment.startedAt)}
                                        </TableCell>
                                        <TableCell className="text-xs text-muted-foreground">
                                            {formatDateTime(experiment.endedAt)}
                                        </TableCell>
                                        <TableCell className="text-right">
                                            <div className="flex justify-end gap-2">
                                                {experiment.status === 'DRAFT' && (
                                                    <>
                                                        <Button variant="outline" size="sm" onClick={() => openEditDialog(experiment)}>
                                                            <Edit2 className="w-3 h-3 mr-1" />
                                                            编辑
                                                        </Button>
                                                        <Button variant="outline" size="sm" onClick={() => handleStart(experiment.id)}>
                                                            <Play className="w-3 h-3 mr-1" />
                                                            启动
                                                        </Button>
                                                        <Button
                                                            variant="outline"
                                                            size="sm"
                                                            className="text-red-500 hover:bg-red-50"
                                                            onClick={() => handleDelete(experiment.id)}
                                                        >
                                                            <Trash2 className="w-3 h-3" />
                                                        </Button>
                                                    </>
                                                )}
                                                {experiment.status === 'RUNNING' && (
                                                    <>
                                                        <Button variant="outline" size="sm" onClick={() => handleEnd(experiment.id)}>
                                                            <Square className="w-3 h-3 mr-1" />
                                                            结束
                                                        </Button>
                                                        <Button variant="outline" size="sm" onClick={() => setReportId(experiment.id)}>
                                                            <BarChart3 className="w-3 h-3 mr-1" />
                                                            查看报表
                                                        </Button>
                                                    </>
                                                )}
                                                {experiment.status === 'ENDED' && (
                                                    <>
                                                        <Button variant="outline" size="sm" onClick={() => setReportId(experiment.id)}>
                                                            <BarChart3 className="w-3 h-3 mr-1" />
                                                            查看报表
                                                        </Button>
                                                        <Button
                                                            variant="outline"
                                                            size="sm"
                                                            className="text-red-500 hover:bg-red-50"
                                                            onClick={() => handleDelete(experiment.id)}
                                                        >
                                                            <Trash2 className="w-3 h-3" />
                                                        </Button>
                                                    </>
                                                )}
                                            </div>
                                        </TableCell>
                                    </TableRow>
                                ))
                            )}
                        </TableBody>
                    </Table>
                </div>
            </div>

            {/* 创建/编辑 对话框 */}
            <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
                <DialogContent className="sm:max-w-[650px]">
                    <DialogHeader>
                        <DialogTitle>{editing ? '编辑 A/B 实验' : '新建 A/B 实验'}</DialogTitle>
                        <DialogDescription>配置实验分组与各分组关联的问答策略及流量权重。</DialogDescription>
                    </DialogHeader>

                    <div className="grid gap-6 py-4">
                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <label className="text-sm font-medium">实验名称</label>
                                <Input
                                    value={form.name}
                                    onChange={e => setForm(prev => ({ ...prev, name: e.target.value }))}
                                    placeholder="如：客服话术风格对比"
                                />
                            </div>
                            <div className="space-y-2">
                                <label className="text-sm font-medium">描述（可选）</label>
                                <Input
                                    value={form.description}
                                    onChange={e => setForm(prev => ({ ...prev, description: e.target.value }))}
                                    placeholder="简述本次实验目的"
                                />
                            </div>
                        </div>

                        <div className="space-y-3">
                            <div className="flex items-center justify-between">
                                <label className="text-sm font-bold">变体分组</label>
                                <Button variant="outline" size="sm" onClick={addVariantRow}>
                                    <Plus className="w-3 h-3 mr-1" />
                                    添加变体
                                </Button>
                            </div>
                            <div className="space-y-2">
                                {form.variants.map((variant, index) => (
                                    <div
                                        key={index}
                                        className="flex items-center gap-2 p-3 bg-slate-50 rounded-lg border border-slate-100"
                                    >
                                        <Input
                                            className="w-24"
                                            value={variant.name}
                                            onChange={e => updateVariantRow(index, { name: e.target.value })}
                                            placeholder="变体名"
                                        />
                                        <Select
                                            value={variant.strategyId ? String(variant.strategyId) : ''}
                                            onValueChange={v => updateVariantRow(index, { strategyId: Number(v) })}
                                        >
                                            <SelectTrigger className="flex-1">
                                                <SelectValue placeholder="选择关联策略" />
                                            </SelectTrigger>
                                            <SelectContent>
                                                {enabledStrategies.map(s => (
                                                    <SelectItem key={s.id} value={String(s.id)}>
                                                        {s.name}
                                                    </SelectItem>
                                                ))}
                                            </SelectContent>
                                        </Select>
                                        <Input
                                            type="number"
                                            className="w-20"
                                            min={0}
                                            max={100}
                                            value={variant.weight}
                                            onChange={e => updateVariantRow(index, { weight: Number(e.target.value) })}
                                        />
                                        <span className="text-xs text-muted-foreground">%</span>
                                        <Button
                                            variant="ghost"
                                            size="icon"
                                            className="text-red-500 hover:bg-red-50 shrink-0"
                                            disabled={form.variants.length <= 2}
                                            onClick={() => removeVariantRow(index)}
                                        >
                                            <Trash2 className="w-4 h-4" />
                                        </Button>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>

                    <DialogFooter>
                        <Button variant="outline" onClick={() => setDialogOpen(false)}>取消</Button>
                        <Button onClick={handleSubmit}>保存实验</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>

            <ReportDialog experimentId={reportId} onClose={() => setReportId(null)} />
        </div>
    );
};

export default AbTestPage;

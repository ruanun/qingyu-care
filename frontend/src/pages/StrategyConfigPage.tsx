import React, { useState, useEffect } from 'react';
import {
    Plus,
    Edit2,
    Trash2,
    BadgeCheck,
    Target,
    Sliders,
    FileText,
    Save,
    HelpCircle,
    Layers
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import { Slider } from "@/components/ui/slider";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { chatStrategyService, type ChatStrategy } from '@/api/strategy';
import { promptService, type PromptTemplate } from '@/api/prompt';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import { getErrorMessage } from '@/lib/apiResult';

const StrategyConfigPage: React.FC = () => {
    const [strategies, setStrategies] = useState<ChatStrategy[]>([]);
    const [prompts, setPrompts] = useState<PromptTemplate[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editingStrategy, setEditingStrategy] = useState<Partial<ChatStrategy> | null>(null);
    const { toast } = useToast();

    const fetchData = async () => {
        try {
            setIsLoading(true);
            const [stratRes, promptRes] = await Promise.all([
                chatStrategyService.getAll(),
                promptService.getAllVersions()
            ]);
            if (stratRes.code === 200) setStrategies(stratRes.data);
            if (promptRes.code === 200) setPrompts(promptRes.data);
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "数据加载失败", description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const handleSaveStrategy = async () => {
        if (!editingStrategy?.name || !editingStrategy?.scenario) {
            toast({ variant: "destructive", title: "请填写名称和场景" });
            return;
        }

        try {
            const res = editingStrategy.id
                ? await chatStrategyService.update(editingStrategy.id, editingStrategy)
                : await chatStrategyService.create(editingStrategy);

            if (res.code === 200) {
                toast({ title: "策略保存成功" });
                setIsDialogOpen(false);
                fetchData();
            }
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "保存失败", description: getErrorMessage(error) });
        }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('确定删除该策略吗？')) return;
        try {
            await chatStrategyService.delete(id);
            toast({ title: "策略已删除" });
            fetchData();
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "删除失败", description: getErrorMessage(error) });
        }
    };

    const openCreateDialog = () => {
        setEditingStrategy({
            name: '',
            scenario: 'default',
            topK: 5,
            similarityThreshold: 0.7,
            allowNoKnowledge: true,
            fallbackMessage: '抱歉，我无法回答该问题。',
            enabled: true,
            isDefault: false
        });
        setIsDialogOpen(true);
    };

    const getScenarioLabel = (scenario: string) => {
        switch (scenario) {
            case 'default': return '默认场景';
            case 'complaint': return '投诉处理';
            case 'faq': return '基础问答';
            default: return scenario;
        }
    };

    return (
        <div className="p-8 h-full overflow-y-auto bg-slate-50/50">
            <div className="max-w-6xl mx-auto space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-2">
                            <Layers className="w-6 h-6 text-primary" />
                            问答策略中心
                        </h1>
                        <p className="text-muted-foreground text-sm mt-1">控制机器人检索参数、匹配阈值及 Prompt 路由方案</p>
                    </div>
                    <Button onClick={openCreateDialog}>
                        <Plus className="w-4 h-4 mr-2" />
                        新建策略
                    </Button>
                </div>

                {isLoading ? (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                        {[1, 2].map(i => <Card key={i} className="h-48 animate-pulse bg-muted" />)}
                    </div>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                        {strategies.map((strat) => (
                            <Card key={strat.id} className={cn("relative overflow-hidden border-2 transition-all", strat.isDefault ? "border-primary/50 shadow-xs" : "border-slate-200")}>
                                {strat.isDefault && (
                                    <div className="absolute top-0 right-0 p-1 px-3 bg-primary text-primary-foreground text-[10px] font-bold rounded-bl-lg flex items-center gap-1">
                                        <BadgeCheck className="w-3 h-3" />
                                        当前默认
                                    </div>
                                )}
                                <CardHeader className="pb-4">
                                    <div className="flex items-center gap-2 mb-2">
                                        <Badge variant="outline" className="bg-slate-100 text-slate-600">{getScenarioLabel(strat.scenario)}</Badge>
                                        {!strat.enabled && <Badge variant="destructive">已禁用</Badge>}
                                    </div>
                                    <CardTitle className="text-xl font-bold">{strat.name}</CardTitle>
                                </CardHeader>

                                <CardContent className="space-y-4">
                                    <div className="grid grid-cols-2 gap-4">
                                        <div className="p-3 bg-slate-50 rounded-lg border border-slate-100">
                                            <div className="flex items-center gap-2 text-xs text-slate-500 mb-1">
                                                <Target className="w-3 h-3" />
                                                检索文档数 (TopK)
                                            </div>
                                            <div className="text-2xl font-bold text-slate-700">{strat.topK}</div>
                                        </div>
                                        <div className="p-3 bg-slate-50 rounded-lg border border-slate-100">
                                            <div className="flex items-center gap-2 text-xs text-slate-500 mb-1">
                                                <Sliders className="w-3 h-3" />
                                                相似度阈值
                                            </div>
                                            <div className="text-2xl font-bold text-slate-700">{(strat.similarityThreshold * 100).toFixed(0)}%</div>
                                        </div>
                                    </div>

                                    <div className="flex items-center justify-between text-sm py-2 border-y border-slate-100">
                                        <div className="flex items-center gap-2 text-slate-600">
                                            <HelpCircle className="w-4 h-4" />
                                            <span>无命中处理: <strong>{strat.allowNoKnowledge ? 'AI 自由发挥' : '兜底回复'}</strong></span>
                                        </div>
                                        <div className="flex items-center gap-2 text-slate-600">
                                            <FileText className="w-4 h-4" />
                                            <span>Prompt版本: <strong>{strat.promptTemplateId ? `V${prompts.find(p => p.id === strat.promptTemplateId)?.version}` : '自动选择'}</strong></span>
                                        </div>
                                    </div>
                                </CardContent>

                                <CardFooter className="flex gap-2 bg-slate-50/50 pt-4">
                                    <Button variant="outline" size="sm" className="flex-1" onClick={() => {
                                        setEditingStrategy(strat);
                                        setIsDialogOpen(true);
                                    }}>
                                        <Edit2 className="w-3 h-3 mr-2" />
                                        编辑策略
                                    </Button>
                                    <Button variant="outline" size="sm" className="text-red-500 hover:bg-red-50" onClick={() => handleDelete(strat.id)}>
                                        <Trash2 className="w-3 h-3" />
                                    </Button>
                                </CardFooter>
                            </Card>
                        ))}
                    </div>
                )}
            </div>

            {/* 编辑/创建 对话框 */}
            <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                <DialogContent className="sm:max-w-[550px]">
                    <DialogHeader>
                        <DialogTitle>{editingStrategy?.id ? '编辑问答策略' : '新建问答策略'}</DialogTitle>
                        <DialogDescription>调整 RAG 检索参数、阈值以及 AI 的回答边界。</DialogDescription>
                    </DialogHeader>

                    <div className="grid gap-6 py-4">
                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <label className="text-sm font-medium">策略名称</label>
                                <Input
                                    value={editingStrategy?.name || ''}
                                    onChange={e => setEditingStrategy({ ...editingStrategy, name: e.target.value })}
                                    placeholder="如：严谨服务策略"
                                />
                            </div>
                            <div className="space-y-2">
                                <label className="text-sm font-medium">适用场景</label>
                                <Select
                                    value={editingStrategy?.scenario || 'default'}
                                    onValueChange={v => setEditingStrategy({ ...editingStrategy, scenario: v })}
                                >
                                    <SelectTrigger><SelectValue /></SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="default">默认场景</SelectItem>
                                        <SelectItem value="complaint">投诉处理</SelectItem>
                                        <SelectItem value="faq">基础问答</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                        </div>

                        <div className="space-y-4 p-4 bg-slate-50 rounded-lg border border-slate-100">
                            <div className="flex items-center justify-between">
                                <label className="text-sm font-bold flex items-center gap-2">
                                    <Target className="w-4 h-4 text-primary" />
                                    TopK (检索文档数): {editingStrategy?.topK}
                                </label>
                                <span className="text-xs text-muted-foreground">推荐 3-10</span>
                            </div>
                            <Slider
                                value={[editingStrategy?.topK || 5]}
                                min={1} max={20} step={1}
                                onValueChange={([val]) => setEditingStrategy({ ...editingStrategy, topK: val })}
                            />
                        </div>

                        <div className="space-y-4 p-4 bg-slate-50 rounded-lg border border-slate-100">
                            <div className="flex items-center justify-between">
                                <label className="text-sm font-bold flex items-center gap-2">
                                    <Sliders className="w-4 h-4 text-primary" />
                                    相似度阈值: {((editingStrategy?.similarityThreshold || 0.7) * 100).toFixed(0)}%
                                </label>
                                <span className="text-xs text-muted-foreground">调高可减少误报，调低可扩大回答范围</span>
                            </div>
                            <Slider
                                value={[(editingStrategy?.similarityThreshold || 0.7) * 100]}
                                min={0} max={100} step={1}
                                onValueChange={([val]) => setEditingStrategy({ ...editingStrategy, similarityThreshold: val / 100 })}
                            />
                        </div>

                        <div className="flex items-center justify-between p-4 border rounded-lg">
                            <div className="space-y-0.5">
                                <label className="text-sm font-bold">允许无知识回答</label>
                                <p className="text-xs text-muted-foreground">当未命中知识库时，是否允许 AI 基于自身预训练知识回答</p>
                            </div>
                            <Switch
                                checked={editingStrategy?.allowNoKnowledge || false}
                                onCheckedChange={c => setEditingStrategy({ ...editingStrategy, allowNoKnowledge: c })}
                            />
                        </div>

                        {!editingStrategy?.allowNoKnowledge && (
                            <div className="space-y-2">
                                <label className="text-sm font-medium">兜底回复文本</label>
                                <Input
                                    value={editingStrategy?.fallbackMessage || ''}
                                    onChange={e => setEditingStrategy({ ...editingStrategy, fallbackMessage: e.target.value })}
                                />
                            </div>
                        )}

                        <div className="grid grid-cols-2 gap-4">
                            <div className="flex items-center justify-between p-4 border rounded-lg">
                                <label className="text-sm font-bold">设为默认策略</label>
                                <Switch
                                    checked={editingStrategy?.isDefault || false}
                                    onCheckedChange={c => setEditingStrategy({ ...editingStrategy, isDefault: c })}
                                />
                            </div>
                            <div className="flex items-center justify-between p-4 border rounded-lg">
                                <label className="text-sm font-bold">启用策略</label>
                                <Switch
                                    checked={editingStrategy?.enabled || false}
                                    onCheckedChange={c => setEditingStrategy({ ...editingStrategy, enabled: c })}
                                />
                            </div>
                        </div>
                    </div>

                    <DialogFooter>
                        <Button variant="outline" onClick={() => setIsDialogOpen(false)}>取消</Button>
                        <Button onClick={handleSaveStrategy} className="gap-2">
                            <Save className="w-4 h-4" />
                            保存策略
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default StrategyConfigPage;

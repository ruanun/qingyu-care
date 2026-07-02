import React, { useState, useEffect } from 'react';
import { Bot, Plus, Edit2, Trash2, Play, Settings2, ShieldCheck, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
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
import { aiModelService } from '@/api/ai-model';
import type { AiModelConfig } from '@/api/ai-model';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const ModelConfigPage: React.FC = () => {
    const [models, setModels] = useState<AiModelConfig[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editingModel, setEditingModel] = useState<Partial<AiModelConfig> | null>(null);
    const [isTesting, setIsTesting] = useState<number | null>(null);
    const { toast } = useToast();

    const fetchModels = async () => {
        try {
            setIsLoading(true);
            const response = await aiModelService.getAllModels();
            if (response.code === 200) {
                setModels(response.data);
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "获取模型列表失败", description: error.message });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchModels();
    }, []);

    const handleToggleEnabled = async (model: AiModelConfig) => {
        try {
            const response = await aiModelService.updateModel(model.id, { enabled: !model.enabled });
            if (response.code === 200) {
                toast({ title: model.enabled ? "模型已禁用" : "模型已启用" });
                fetchModels();
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "操作失败", description: error.message });
        }
    };

    const handleTestModel = async (id: number) => {
        try {
            setIsTesting(id);
            const response = await aiModelService.testModel(id);
            if (response.code === 200) {
                toast({ title: "连接测试成功", description: response.data });
            } else {
                toast({ variant: "destructive", title: "连接测试失败", description: response.message });
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "测试过程中出错", description: error.message });
        } finally {
            setIsTesting(null);
        }
    };

    const handleSaveModel = async () => {
        if (!editingModel?.name || !editingModel?.provider || !editingModel?.modelId) {
            toast({ variant: "destructive", title: "请填写完整信息" });
            return;
        }

        try {
            let response;
            if (editingModel.id) {
                response = await aiModelService.updateModel(editingModel.id, editingModel);
            } else {
                response = await aiModelService.createModel(editingModel);
            }

            if (response.code === 200) {
                toast({ title: editingModel.id ? "模型已更新" : "模型已创建" });
                setIsDialogOpen(false);
                fetchModels();
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "保存失败", description: error.message });
        }
    };

    const handleDeleteModel = async (id: number) => {
        if (!confirm('确定要删除该模型配置吗？')) return;
        try {
            const response = await aiModelService.deleteModel(id);
            if (response.code === 200) {
                toast({ title: "模型配置已删除" });
                fetchModels();
            }
        } catch (error: any) {
            toast({ variant: "destructive", title: "删除失败", description: error.message });
        }
    };

    const openCreateDialog = () => {
        setEditingModel({
            name: '',
            provider: 'openai',
            modelId: '',
            endpoint: '',
            apiKey: '',
            scenario: 'default',
            priority: 100,
            enabled: true,
            remark: ''
        });
        setIsDialogOpen(true);
    };

    const openEditDialog = (model: AiModelConfig) => {
        setEditingModel({ ...model, apiKey: '' }); // 不回显 API Key
        setIsDialogOpen(true);
    };

    const getProviderBadge = (provider: string) => {
        const p = provider.toLowerCase();
        if (p === 'openai') return <Badge variant="secondary" className="bg-emerald-100 text-emerald-700 border-emerald-200">OpenAI</Badge>;
        if (p === 'dashscope') return <Badge variant="secondary" className="bg-blue-100 text-blue-700 border-blue-200">通义千问</Badge>;
        if (p === 'moonshot') return <Badge variant="secondary" className="bg-orange-100 text-orange-700 border-orange-200">Kimi</Badge>;
        if (p === 'deepseek') return <Badge variant="secondary" className="bg-indigo-100 text-indigo-700 border-indigo-200">DeepSeek</Badge>;
        return <Badge variant="outline">{provider}</Badge>;
    };

    const getScenarioBadge = (scenario: string) => {
        switch (scenario) {
            case 'default': return <Badge variant="outline">默认</Badge>;
            case 'complaint': return <Badge variant="outline" className="border-red-200 text-red-600">投诉</Badge>;
            case 'faq': return <Badge variant="outline" className="border-green-200 text-green-600">问答</Badge>;
            default: return <Badge variant="outline">{scenario}</Badge>;
        }
    };

    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-6xl mx-auto space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-2">
                            <Bot className="w-6 h-6" />
                            多模型与模型治理
                        </h1>
                        <p className="text-muted-foreground text-sm mt-1">配置多厂商 AI 模型，支持场景路由与自动降级</p>
                    </div>
                    <Button onClick={openCreateDialog}>
                        <Plus className="w-4 h-4 mr-2" />
                        添加模型
                    </Button>
                </div>

                {isLoading ? (
                    <div className="flex items-center justify-center py-20">
                        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
                    </div>
                ) : models.length === 0 ? (
                    <Card className="border-dashed py-12">
                        <CardContent className="flex flex-col items-center justify-center text-muted-foreground">
                            <Settings2 className="w-12 h-12 mb-4 opacity-20" />
                            <p>尚未配置任何模型，请点击右上角添加</p>
                        </CardContent>
                    </Card>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {models.map((model) => (
                            <Card key={model.id} className={cn("flex flex-col transition-all hover:shadow-md", !model.enabled && "opacity-60")}>
                                <CardHeader className="pb-3">
                                    <div className="flex items-center justify-between mb-2">
                                        {getProviderBadge(model.provider)}
                                        <div className="flex items-center gap-2">
                                            <Switch
                                                checked={model.enabled}
                                                onCheckedChange={() => handleToggleEnabled(model)}
                                            />
                                        </div>
                                    </div>
                                    <CardTitle className="text-lg flex items-center gap-2">
                                        {model.name}
                                        {model.priority === 1 && model.scenario === 'default' && (
                                            <ShieldCheck className="w-4 h-4 text-blue-500" />
                                        )}
                                    </CardTitle>
                                    <div className="flex flex-wrap gap-2 mt-2">
                                        <Badge variant="secondary" className="text-[10px] font-mono">{model.modelId}</Badge>
                                        {getScenarioBadge(model.scenario)}
                                        <Badge variant="outline" className="text-[10px]">P{model.priority}</Badge>
                                    </div>
                                </CardHeader>
                                <CardContent className="flex-1 py-2">
                                    <p className="text-xs text-muted-foreground line-clamp-2">
                                        {model.remark || "暂无备注"}
                                    </p>
                                    <div className="mt-3 text-[10px] text-muted-foreground/60 flex items-center gap-1 font-mono truncate">
                                        <AlertCircle className="w-3 h-3" />
                                        {model.endpoint || "使用官方端点"}
                                    </div>
                                </CardContent>
                                <CardFooter className="pt-2 flex justify-between gap-2 border-t mt-auto">
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        className="flex-1 text-xs"
                                        onClick={() => handleTestModel(model.id)}
                                        disabled={isTesting === model.id}
                                    >
                                        <Play className={cn("w-3 h-3 mr-1", isTesting === model.id && "animate-spin")} />
                                        测试
                                    </Button>
                                    <Button variant="outline" size="sm" onClick={() => openEditDialog(model)}>
                                        <Edit2 className="w-3 h-3" />
                                    </Button>
                                    <Button variant="outline" size="sm" className="text-red-500 hover:text-red-600 hover:bg-red-50" onClick={() => handleDeleteModel(model.id)}>
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
                <DialogContent className="sm:max-w-[500px]">
                    <DialogHeader>
                        <DialogTitle>{editingModel?.id ? '编辑模型配置' : '添加 AI 模型'}</DialogTitle>
                        <DialogDescription>
                            配置 AI 厂商参数，API Key 在保存后将被安全存储且不可回显。
                        </DialogDescription>
                    </DialogHeader>
                    <div className="grid gap-4 py-4">
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">名称</label>
                            <Input
                                className="col-span-3"
                                value={editingModel?.name || ''}
                                onChange={e => setEditingModel({ ...editingModel, name: e.target.value })}
                                placeholder="如：GPT-4o Mini"
                            />
                        </div>
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">提供商</label>
                            <Select
                                value={editingModel?.provider || 'openai'}
                                onValueChange={val => setEditingModel({ ...editingModel, provider: val })}
                            >
                                <SelectTrigger className="col-span-3">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="openai">OpenAI</SelectItem>
                                    <SelectItem value="dashscope">通义千问 (OpenAI 兼容)</SelectItem>
                                    <SelectItem value="moonshot">Kimi (OpenAI 兼容)</SelectItem>
                                    <SelectItem value="deepseek">DeepSeek (OpenAI 兼容)</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">模型 ID</label>
                            <Input
                                className="col-span-3"
                                value={editingModel?.modelId || ''}
                                onChange={e => setEditingModel({ ...editingModel, modelId: e.target.value })}
                                placeholder="如：gpt-4o-mini"
                            />
                        </div>
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">端点</label>
                            <Input
                                className="col-span-3"
                                value={editingModel?.endpoint || ''}
                                onChange={e => setEditingModel({ ...editingModel, endpoint: e.target.value })}
                                placeholder="选填，默认使用厂商官方端点"
                            />
                        </div>
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">API Key</label>
                            <Input
                                type="password"
                                className="col-span-3"
                                value={editingModel?.apiKey || ''}
                                onChange={e => setEditingModel({ ...editingModel, apiKey: e.target.value })}
                                placeholder={editingModel?.id ? "留空表示不修改" : "请输入 API Key"}
                            />
                        </div>
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">适用场景</label>
                            <Select
                                value={editingModel?.scenario || 'default'}
                                onValueChange={val => setEditingModel({ ...editingModel, scenario: val })}
                            >
                                <SelectTrigger className="col-span-3">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="default">默认 (所有场景)</SelectItem>
                                    <SelectItem value="complaint">投诉处理 (推荐强模型)</SelectItem>
                                    <SelectItem value="faq">基础问答 (推荐小模型)</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">优先级</label>
                            <Input
                                type="number"
                                className="col-span-3"
                                value={editingModel?.priority || 100}
                                onChange={e => setEditingModel({ ...editingModel, priority: parseInt(e.target.value) })}
                                placeholder="1 为最高优先级"
                            />
                        </div>
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">备注</label>
                            <Input
                                className="col-span-3"
                                value={editingModel?.remark || ''}
                                onChange={e => setEditingModel({ ...editingModel, remark: e.target.value })}
                            />
                        </div>
                    </div>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => setIsDialogOpen(false)}>取消</Button>
                        <Button onClick={handleSaveModel}>保存配置</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default ModelConfigPage;

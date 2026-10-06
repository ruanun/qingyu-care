import React, { useState, useEffect } from 'react';
import { Bot, Plus, Edit2, Trash2, Play, Settings2, ShieldCheck, AlertCircle, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
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
import { isEmbeddingModel } from '@/lib/modelType';
import type { AiModelConfig, ModelType, ProviderPreset } from '@/api/ai-model';
import { ProviderField } from '@/components/model/ProviderField';
import { ProviderBadge } from '@/components/model/ProviderBadge';
import { useToast } from '@/hooks/use-toast';
import { useConfirm } from '@/hooks/use-confirm';
import { cn } from '@/lib/utils';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

const ModelConfigPage: React.FC = () => {
    const [models, setModels] = useState<AiModelConfig[]>([]);
    // 厂商预设清单。唯一事实源在后端 ModelProviderCatalog，前端只渲染，不另存一份。
    const [providers, setProviders] = useState<ProviderPreset[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editingModel, setEditingModel] = useState<Partial<AiModelConfig> | null>(null);
    const [isTesting, setIsTesting] = useState<number | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    // 对话模型与向量模型同表存放，按类型分两个标签页管理
    const [tab, setTab] = useState<ModelType>('CHAT');
    const { toast } = useToast();
    const { confirm, confirmDialog } = useConfirm();

    const fetchModels = async () => {
        try {
            setIsLoading(true);
            const response = await aiModelService.getAllModels();
            throwIfFailed(response);
            setModels(response.data);
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "获取模型列表失败", description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    // 预设清单是锦上添花：拉不到时降级成「提供商纯文本框 + 端点自己填」，
    // 与改造前的平台共享模型页同形态，不该让整页报错。
    const fetchProviders = async () => {
        try {
            const response = await aiModelService.listProviders();
            throwIfFailed(response);
            setProviders(response.data);
        } catch {
            setProviders([]);
        }
    };

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- effect 里调的取数函数开头会 setIsLoading(true)，加载态本就要触发一次重渲染；正解是改用数据请求库替掉 effect 取数，见技术债 7
        fetchModels();
        fetchProviders();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const handleToggleEnabled = async (model: AiModelConfig) => {
        try {
            const response = await aiModelService.updateModel(model.id, { enabled: !model.enabled });
            throwIfFailed(response);
            toast({ title: model.enabled ? "模型已禁用" : "模型已启用" });
            fetchModels();
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "操作失败", description: getErrorMessage(error) });
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
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "测试过程中出错", description: getErrorMessage(error) });
        } finally {
            setIsTesting(null);
        }
    };

    const handleSaveModel = async () => {
        if (!editingModel?.name || !editingModel?.provider || !editingModel?.modelId) {
            toast({ variant: "destructive", title: "请填写完整信息" });
            return;
        }

        // 向量模型保存时后端会真实调用一次 embeddings 校验连通性与维度，可能要几秒
        setIsSaving(true);
        try {
            let response;
            if (editingModel.id) {
                response = await aiModelService.updateModel(editingModel.id, editingModel);
            } else {
                response = await aiModelService.createModel(editingModel);
            }

            throwIfFailed(response);
            toast({ title: editingModel.id ? "模型已更新" : "模型已创建" });
            setIsDialogOpen(false);
            fetchModels();
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "保存失败", description: getErrorMessage(error) });
        } finally {
            setIsSaving(false);
        }
    };

    const handleDeleteModel = async (id: number) => {
        if (!(await confirm({ title: '删除该模型配置？', confirmText: '删除', destructive: true }))) return;
        try {
            const response = await aiModelService.deleteModel(id);
            throwIfFailed(response);
            toast({ title: "模型配置已删除" });
            fetchModels();
        } catch (error: unknown) {
            toast({ variant: "destructive", title: "删除失败", description: getErrorMessage(error) });
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
            remark: '',
            modelType: tab,
            // 留空由后端填推荐值
            similarityThreshold: null,
        });
        setIsDialogOpen(true);
    };

    const editingIsEmbedding = editingModel?.modelType === 'EMBEDDING';
    const visibleModels = models.filter((m) => (tab === 'EMBEDDING') === isEmbeddingModel(m));
    const selectedPreset = providers.find((p) => p.key === editingModel?.provider);

    const openEditDialog = (model: AiModelConfig) => {
        setEditingModel({ ...model, apiKey: '' }); // 不回显 API Key
        setIsDialogOpen(true);
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
                        {tab === 'EMBEDDING' ? '添加向量模型' : '添加模型'}
                    </Button>
                </div>

                <Tabs value={tab} onValueChange={(v) => setTab(v as ModelType)}>
                    <TabsList>
                        <TabsTrigger value="CHAT">对话模型</TabsTrigger>
                        <TabsTrigger value="EMBEDDING">向量模型</TabsTrigger>
                    </TabsList>
                </Tabs>
                {tab === 'EMBEDDING' && (
                    <p className="text-xs text-muted-foreground -mt-2">
                        向量模型用于知识库的向量化与检索，平台已提供默认模型。在这里添加自己的向量模型后，可到「知识库」页把本应用切换过去；
                        保存时会真实调用一次接口，校验连通性与输出维度。
                    </p>
                )}

                {isLoading ? (
                    <div className="flex items-center justify-center py-20">
                        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
                    </div>
                ) : visibleModels.length === 0 ? (
                    <Card className="border-dashed py-12">
                        <CardContent className="flex flex-col items-center justify-center text-muted-foreground">
                            <Settings2 className="w-12 h-12 mb-4 opacity-20" />
                            <p>{tab === 'EMBEDDING' ? '本应用还没有自己的向量模型，正在使用平台提供的默认模型' : '尚未配置任何模型，请点击右上角添加'}</p>
                        </CardContent>
                    </Card>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {visibleModels.map((model) => (
                            <Card key={model.id} className={cn("flex flex-col transition-all hover:shadow-md", !model.enabled && "opacity-60")}>
                                <CardHeader className="pb-3">
                                    <div className="flex items-center justify-between mb-2">
                                        <ProviderBadge provider={model.provider} presets={providers} />
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
                                        {isEmbeddingModel(model) ? (
                                            model.similarityThreshold != null && (
                                                <Badge variant="outline" className="text-[10px]">阈值 {model.similarityThreshold}</Badge>
                                            )
                                        ) : (
                                            <>
                                                {getScenarioBadge(model.scenario)}
                                                <Badge variant="outline" className="text-[10px]">P{model.priority}</Badge>
                                            </>
                                        )}
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
                        <DialogTitle>{editingModel?.id ? '编辑模型配置' : editingIsEmbedding ? '添加向量模型' : '添加 AI 模型'}</DialogTitle>
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
                        <ProviderField
                            provider={editingModel?.provider || ''}
                            presets={providers}
                            onProviderChange={val => setEditingModel({ ...editingModel, provider: val })}
                            onPresetSelect={preset => setEditingModel({
                                ...editingModel,
                                provider: preset.key,
                                // 端点一并填上：各家后缀并不统一（智谱 /api/paas/v4、火山 /api/v3、
                                // Groq /openai/v1、Gemini /v1beta/openai），让人自己猜就是本仓库
                                // 最难排查的那类静默 404。
                                endpoint: preset.defaultEndpoint,
                            })}
                        />
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">模型 ID</label>
                            <Input
                                className="col-span-3"
                                value={editingModel?.modelId || ''}
                                onChange={e => setEditingModel({ ...editingModel, modelId: e.target.value })}
                                placeholder={editingIsEmbedding
                                    ? (selectedPreset?.sampleEmbeddingModelId ? `如：${selectedPreset.sampleEmbeddingModelId}` : '该厂商暂无已核实的向量模型，可自行填写')
                                    : (selectedPreset?.sampleModelId ? `如：${selectedPreset.sampleModelId}` : '如：gpt-4o-mini')}
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
                        {editingIsEmbedding ? (
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label className="text-right text-sm font-medium">推荐阈值</label>
                            <Input
                                type="number"
                                step="0.01"
                                min="0"
                                max="1"
                                className="col-span-3"
                                value={editingModel?.similarityThreshold ?? ''}
                                onChange={e => setEditingModel({ ...editingModel, similarityThreshold: e.target.value === '' ? null : Number(e.target.value) })}
                                placeholder="留空用默认 0.45；不同模型分数刻度不同，建议用真实问题测一遍再调"
                            />
                        </div>
                        ) : (<>
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
                        </>)}
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
                        <Button onClick={handleSaveModel} disabled={isSaving}>
                            {isSaving && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                            {isSaving && editingIsEmbedding ? '正在校验…' : '保存配置'}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
            {confirmDialog}
        </div>
    );
};

export default ModelConfigPage;

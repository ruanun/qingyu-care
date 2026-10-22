import { useCallback, useEffect, useMemo, useState } from 'react';
import { Edit2, KeyRound, Loader2, Play, Plus, PlugZap, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Switch } from '@/components/ui/switch';
import { ProviderBadge } from '@/components/model/ProviderBadge';
import { ProviderDialog } from '@/components/model/ProviderDialog';
import { AddModelsDialog } from '@/components/model/AddModelsDialog';
import { ModelEditDialog } from '@/components/model/ModelEditDialog';
import { aiModelService } from '@/api/ai-model';
import type { AiModelConfig, ProviderPreset } from '@/api/ai-model';
import { providerApi } from '@/api/modelProvider';
import type { ModelProvider, ProviderForm, ProviderScope, ProviderWithModels } from '@/api/modelProvider';
import { useToast } from '@/hooks/use-toast';
import { useConfirm } from '@/hooks/use-confirm';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';
import { isEmbeddingModel } from '@/lib/modelType';
import { cn } from '@/lib/utils';

interface Props {
    scope: ProviderScope;
    /** 租户侧：当前应用名，用于提示「模型添加到哪个应用」 */
    appName?: string;
}

const SCENARIO_LABEL: Record<string, string> = { default: '默认', complaint: '投诉', faq: '问答' };

/**
 * 供应商连接 + 其下模型的管理面板，租户侧（模型配置页）与平台侧（共享模型页）共用。
 *
 * 结构学 Dify / RAGFlow：先建连接（填一次 Key），再在连接下勾选对话模型与向量模型。
 * 租户侧的连接对本租户所有应用通用，模型添加到当前应用；页面底部另列出平台提供的模型（只读）。
 *
 * @author ruan
 */
export function ProviderModelManager({ scope, appName }: Props) {
    const api = useMemo(() => providerApi(scope), [scope]);
    const [items, setItems] = useState<ProviderWithModels[]>([]);
    const [shared, setShared] = useState<AiModelConfig[]>([]);
    const [presets, setPresets] = useState<ProviderPreset[]>([]);
    const [loading, setLoading] = useState(true);
    const [providerDialog, setProviderDialog] = useState<{ open: boolean; editing: ModelProvider | null }>({ open: false, editing: null });
    const [addingTo, setAddingTo] = useState<ProviderWithModels | null>(null);
    const [editingModel, setEditingModel] = useState<AiModelConfig | null>(null);
    const [testing, setTesting] = useState<string | null>(null);
    const { toast } = useToast();
    const { confirm, confirmDialog } = useConfirm();

    const load = useCallback(async () => {
        try {
            const res = await api.list();
            throwIfFailed(res);
            setItems(res.data);
            if (scope === 'TENANT') {
                // 平台共享模型照常可用，只是不归租户管：单独只读列出
                const all = await aiModelService.getAllModels();
                if (all.code === 200) setShared(all.data.filter((m) => m.tenantId == null));
            }
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '加载失败', description: getErrorMessage(error) });
        } finally {
            setLoading(false);
        }
    }, [api, scope, toast]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 挂载即拉一次
        load();
        aiModelService.listProviders()
            .then((res) => setPresets(res.code === 200 ? res.data : []))
            .catch(() => setPresets([]));   // 预设只是锦上添花，拉不到就退化成纯文本框
    }, [load]);

    // ===== 连接 =====

    const saveProvider = async (form: ProviderForm) => {
        const editing = providerDialog.editing;
        try {
            const res = editing ? await api.update(editing.id, form) : await api.create(form);
            throwIfFailed(res);
            toast({ title: editing ? '连接已更新' : '连接已创建' });
            setProviderDialog({ open: false, editing: null });
            await load();
            // 新建后直接进入「添加模型」：建连接本来就是为了挂模型，少点一次
            if (!editing) setAddingTo({ provider: res.data, models: [] });
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '保存失败', description: getErrorMessage(error) });
        }
    };

    const removeProvider = async (p: ModelProvider) => {
        if (!(await confirm({ title: `删除连接「${p.name}」？`, description: '连接下还有模型时不能删除。', confirmText: '删除', destructive: true }))) return;
        try {
            const res = await api.remove(p.id);
            throwIfFailed(res);
            toast({ title: '连接已删除' });
            await load();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '删除失败', description: getErrorMessage(error) });
        }
    };

    const testProvider = async (p: ModelProvider) => {
        setTesting(`p${p.id}`);
        try {
            const res = await api.remoteModels(p.id);
            throwIfFailed(res);
            toast({ title: '连接正常', description: `该厂商提供 ${res.data.length} 个可用模型` });
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '连接测试失败', description: getErrorMessage(error) });
        } finally {
            setTesting(null);
        }
    };

    // ===== 模型 =====

    const toggleModel = async (m: AiModelConfig) => {
        try {
            const res = await api.updateModel(m.id, { enabled: !m.enabled });
            throwIfFailed(res);
            await load();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '操作失败', description: getErrorMessage(error) });
        }
    };

    const saveModel = async (patch: Partial<AiModelConfig>) => {
        if (!editingModel) return;
        try {
            const res = await api.updateModel(editingModel.id, patch);
            throwIfFailed(res);
            toast({ title: '模型已更新' });
            setEditingModel(null);
            await load();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '保存失败', description: getErrorMessage(error) });
        }
    };

    const removeModel = async (m: AiModelConfig) => {
        if (!(await confirm({ title: `删除模型「${m.name}」？`, confirmText: '删除', destructive: true }))) return;
        try {
            const res = await api.deleteModel(m.id);
            throwIfFailed(res);
            toast({ title: '模型已删除' });
            await load();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '删除失败', description: getErrorMessage(error) });
        }
    };

    const testModel = async (m: AiModelConfig) => {
        setTesting(`m${m.id}`);
        try {
            const res = await api.testModel(m.id);
            throwIfFailed(res);
            toast({ title: '连接测试成功', description: res.data });
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '连接测试失败', description: getErrorMessage(error) });
        } finally {
            setTesting(null);
        }
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center py-20">
                <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
            </div>
        );
    }

    return (
        <div className="space-y-6">
            <div className="flex items-center justify-between gap-4">
                <p className="text-xs text-muted-foreground">
                    {scope === 'PLATFORM'
                        ? '平台连接下的模型即平台共享模型：所有租户可用，Key 对租户不可见。向量模型里优先级数字最小的一个是平台默认。'
                        : `连接对本租户的所有应用通用（Key 只填一次）；模型添加到当前应用${appName ? `「${appName}」` : ''}。`}
                </p>
                <Button onClick={() => setProviderDialog({ open: true, editing: null })} className="shrink-0">
                    <Plus className="w-4 h-4 mr-2" /> 添加供应商
                </Button>
            </div>

            {items.length === 0 && (
                <div className="border-2 border-dashed rounded-xl py-12 text-center text-sm text-muted-foreground">
                    <PlugZap className="w-10 h-10 mx-auto mb-3 opacity-30" />
                    还没有供应商连接。添加一个，填一次 Key，就能在下面勾选对话模型与向量模型。
                </div>
            )}

            {items.map(({ provider: p, models }) => (
                <div key={p.id} className="rounded-xl border bg-card" data-testid={`provider-${p.id}`}>
                    <div className="flex items-start justify-between gap-4 p-4 border-b">
                        <div className="min-w-0 space-y-1">
                            <div className="flex items-center gap-2">
                                <ProviderBadge provider={p.providerKey} presets={presets} />
                                <span className="font-semibold">{p.name}</span>
                                <Badge variant="outline" className={cn('text-[10px] gap-1', !p.hasApiKey && 'text-amber-600 border-amber-200')}>
                                    <KeyRound className="w-3 h-3" />{p.hasApiKey ? 'Key 已配置' : '未配置 Key'}
                                </Badge>
                            </div>
                            <p className="text-[11px] text-muted-foreground font-mono truncate">{p.endpoint || '使用厂商官方端点'}</p>
                        </div>
                        <div className="flex gap-2 shrink-0">
                            <Button variant="outline" size="sm" onClick={() => testProvider(p)} disabled={testing === `p${p.id}`}>
                                <Play className={cn('w-3 h-3 mr-1', testing === `p${p.id}` && 'animate-spin')} /> 测试连接
                            </Button>
                            <Button size="sm" onClick={() => setAddingTo({ provider: p, models })}>
                                <Plus className="w-3 h-3 mr-1" /> 添加模型
                            </Button>
                            <Button variant="outline" size="sm" aria-label={`编辑连接 ${p.name}`} onClick={() => setProviderDialog({ open: true, editing: p })}>
                                <Edit2 className="w-3 h-3" />
                            </Button>
                            <Button variant="outline" size="sm" aria-label={`删除连接 ${p.name}`} className="text-red-500 hover:text-red-600" onClick={() => removeProvider(p)}>
                                <Trash2 className="w-3 h-3" />
                            </Button>
                        </div>
                    </div>
                    {models.length === 0 ? (
                        <p className="p-4 text-xs text-muted-foreground">还没有模型，点「添加模型」从列表里勾选。</p>
                    ) : (
                        <div className="divide-y">
                            {models.map((m) => (
                                <div key={m.id} className={cn('flex items-center gap-3 px-4 py-2.5 text-sm', !m.enabled && 'opacity-60')}>
                                    <Badge variant={isEmbeddingModel(m) ? 'secondary' : 'outline'} className="text-[10px] w-12 shrink-0 justify-center whitespace-nowrap">
                                        {isEmbeddingModel(m) ? '向量' : '对话'}
                                    </Badge>
                                    <span className="font-medium min-w-[120px]">{m.name}</span>
                                    <span className="font-mono text-xs text-muted-foreground flex-1 truncate">{m.modelId}</span>
                                    <span className="text-[11px] text-muted-foreground w-28 text-right">
                                        {isEmbeddingModel(m)
                                            ? (m.similarityThreshold != null ? `阈值 ${m.similarityThreshold}` : '')
                                            : `${SCENARIO_LABEL[m.scenario] ?? m.scenario} · P${m.priority}`}
                                    </span>
                                    <Switch aria-label={`启用 ${m.name}`} checked={m.enabled} onCheckedChange={() => toggleModel(m)} />
                                    <Button variant="ghost" size="sm" aria-label={`测试 ${m.name}`} onClick={() => testModel(m)} disabled={testing === `m${m.id}`}>
                                        <Play className={cn('w-3 h-3', testing === `m${m.id}` && 'animate-spin')} />
                                    </Button>
                                    <Button variant="ghost" size="sm" aria-label={`编辑 ${m.name}`} onClick={() => setEditingModel(m)}>
                                        <Edit2 className="w-3 h-3" />
                                    </Button>
                                    <Button variant="ghost" size="sm" aria-label={`删除 ${m.name}`} className="text-red-500" onClick={() => removeModel(m)}>
                                        <Trash2 className="w-3 h-3" />
                                    </Button>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            ))}

            {scope === 'TENANT' && shared.length > 0 && (
                <div className="rounded-xl border bg-muted/30" data-testid="shared-models">
                    <div className="p-4 border-b">
                        <span className="font-semibold text-sm">平台提供的模型</span>
                        <p className="text-[11px] text-muted-foreground mt-0.5">由平台统一配置与计费，可直接使用；本应用没有同场景的对话模型时自动用它们兜底。</p>
                    </div>
                    <div className="divide-y">
                        {shared.map((m) => (
                            <div key={m.id} className="flex items-center gap-3 px-4 py-2.5 text-sm">
                                <Badge variant={isEmbeddingModel(m) ? 'secondary' : 'outline'} className="text-[10px] w-12 shrink-0 justify-center whitespace-nowrap">
                                    {isEmbeddingModel(m) ? '向量' : '对话'}
                                </Badge>
                                <span className="font-medium min-w-[120px]">{m.name}</span>
                                <span className="font-mono text-xs text-muted-foreground flex-1 truncate">{m.modelId}</span>
                                <ProviderBadge provider={m.provider} presets={presets} />
                            </div>
                        ))}
                    </div>
                </div>
            )}

            <ProviderDialog
                open={providerDialog.open}
                onOpenChange={(open) => setProviderDialog((d) => ({ ...d, open }))}
                editing={providerDialog.editing}
                presets={presets}
                onSubmit={saveProvider}
            />
            <AddModelsDialog
                open={addingTo !== null}
                onOpenChange={(open) => !open && setAddingTo(null)}
                provider={addingTo?.provider ?? null}
                api={api}
                presets={presets}
                existingIds={(items.find((i) => i.provider.id === addingTo?.provider.id)?.models ?? []).map((m) => m.modelId)}
                onAdded={() => {
                    toast({ title: '模型已添加' });
                    load();
                }}
            />
            <ModelEditDialog
                open={editingModel !== null}
                onOpenChange={(open) => !open && setEditingModel(null)}
                model={editingModel}
                onSubmit={saveModel}
            />
            {confirmDialog}
        </div>
    );
}

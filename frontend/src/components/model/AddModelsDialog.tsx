import { useEffect, useMemo, useState } from 'react';
import { Loader2, Plus, RefreshCw, Search } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import type { ModelType, ProviderPreset } from '@/api/ai-model';
import type { AddResult, ModelProvider, NewModel, RemoteModel, providerApi } from '@/api/modelProvider';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    provider: ModelProvider | null;
    api: ReturnType<typeof providerApi>;
    presets: ProviderPreset[];
    /** 该连接下已有的模型 ID（租户侧为当前应用的），列表里标成「已添加」 */
    existingIds: string[];
    /** 添加完成（至少成功一个）后通知父组件刷新 */
    onAdded: () => void;
}

interface Row {
    id: string;
    type: ModelType;
    checked: boolean;
    /** 手动加入的行（不在远端列表里） */
    manual?: boolean;
}

const TYPE_LABEL: Record<ModelType, string> = { CHAT: '对话', EMBEDDING: '向量' };

/**
 * 在供应商连接下添加模型：拉取该厂商的模型列表 → 勾选（按名称预判了对话 / 向量，可改）→ 批量添加。
 * 拉不到列表（厂商没有 /models 接口、Key 权限不够等）就退化成手动填模型 ID，不挡路。
 *
 * 向量模型添加时后端会逐个真实探测维度，单项失败只影响那一项，结果逐项显示在对话框里。
 *
 * @author ruan
 */
export function AddModelsDialog({ open, onOpenChange, provider, api, presets, existingIds, onAdded }: Props) {
    const [rows, setRows] = useState<Row[]>([]);
    const [loading, setLoading] = useState(false);
    const [listError, setListError] = useState<string | null>(null);
    const [keyword, setKeyword] = useState('');
    const [manualId, setManualId] = useState('');
    const [manualType, setManualType] = useState<ModelType>('CHAT');
    const [submitting, setSubmitting] = useState(false);
    const [results, setResults] = useState<AddResult[] | null>(null);

    const preset = presets.find((p) => p.key === provider?.providerKey);

    const fetchList = async () => {
        if (!provider) return;
        setLoading(true);
        setListError(null);
        try {
            const res = await api.remoteModels(provider.id);
            throwIfFailed(res);
            setRows((prev) => {
                const manual = prev.filter((r) => r.manual);
                const remote = res.data.map((m: RemoteModel) => ({ id: m.id, type: m.suggestedType, checked: false }));
                return [...manual, ...remote.filter((r) => !manual.some((m) => m.id === r.id))];
            });
        } catch (error: unknown) {
            setListError(getErrorMessage(error));
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (!open) return;
        // 每次打开都重来：上一次的勾选与结果不该残留到另一个连接上
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 打开时复位并拉列表
        setRows([]);
        setResults(null);
        setKeyword('');
        setManualId('');
        fetchList();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, provider?.id]);

    const visible = useMemo(() => {
        const k = keyword.trim().toLowerCase();
        return k ? rows.filter((r) => r.id.toLowerCase().includes(k)) : rows;
    }, [rows, keyword]);

    const checked = rows.filter((r) => r.checked && !existingIds.includes(r.id));

    const toggle = (id: string, value: boolean) =>
        setRows((prev) => prev.map((r) => (r.id === id ? { ...r, checked: value } : r)));
    const setType = (id: string, type: ModelType) =>
        setRows((prev) => prev.map((r) => (r.id === id ? { ...r, type } : r)));

    const addManual = () => {
        const id = manualId.trim();
        if (!id) return;
        setRows((prev) => [{ id, type: manualType, checked: true, manual: true }, ...prev.filter((r) => r.id !== id)]);
        setManualId('');
    };

    const submit = async () => {
        if (!provider || checked.length === 0) return;
        setSubmitting(true);
        try {
            const items: NewModel[] = checked.map((r) => ({ modelId: r.id, modelType: r.type }));
            const res = await api.addModels(provider.id, items);
            throwIfFailed(res);
            const okCount = res.data.filter((r) => r.ok).length;
            if (okCount > 0) onAdded();
            if (res.data.every((r) => r.ok)) {
                onOpenChange(false);
            } else {
                // 有失败项就留在对话框里逐项给原因（多半是向量模型维度不符）
                setResults(res.data);
                setRows((prev) => prev.map((r) => ({ ...r, checked: res.data.some((x) => x.modelId === r.id && !x.ok) && r.checked })));
            }
        } catch (error: unknown) {
            setResults([{ modelId: '', ok: false, message: getErrorMessage(error), id: null }]);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="sm:max-w-[640px]">
                <DialogHeader>
                    <DialogTitle>添加模型 · {provider?.name}</DialogTitle>
                    <DialogDescription>
                        从该厂商的模型列表里勾选要用的模型，类型已按名称预判，可以改。向量模型添加时会真实调用一次，校验维度。
                    </DialogDescription>
                </DialogHeader>

                <div className="space-y-3">
                    <div className="flex gap-2">
                        <div className="relative flex-1">
                            <Search className="w-4 h-4 absolute left-2.5 top-2.5 text-muted-foreground" />
                            <Input className="pl-8" placeholder="搜索模型 ID" value={keyword} onChange={(e) => setKeyword(e.target.value)} />
                        </div>
                        <Button variant="outline" size="icon" onClick={fetchList} disabled={loading} title="重新拉取">
                            <RefreshCw className={loading ? 'w-4 h-4 animate-spin' : 'w-4 h-4'} />
                        </Button>
                    </div>

                    {listError && (
                        <p className="text-xs text-amber-600" role="alert">没能拉到模型列表：{listError}。可以在下面手动填写模型 ID。</p>
                    )}

                    <div className="border rounded-md max-h-[300px] overflow-y-auto divide-y" data-testid="remote-model-list">
                        {loading && rows.length === 0 ? (
                            <div className="p-6 flex items-center justify-center text-sm text-muted-foreground gap-2">
                                <Loader2 className="w-4 h-4 animate-spin" /> 正在拉取模型列表…
                            </div>
                        ) : visible.length === 0 ? (
                            <div className="p-6 text-center text-sm text-muted-foreground">没有可选的模型</div>
                        ) : visible.map((r) => {
                            const added = existingIds.includes(r.id);
                            return (
                                <label key={r.id} className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-muted/40 cursor-pointer">
                                    <Checkbox
                                        aria-label={r.id}
                                        checked={added || r.checked}
                                        disabled={added}
                                        onCheckedChange={(v) => toggle(r.id, v === true)}
                                    />
                                    <span className="font-mono text-xs flex-1 truncate">{r.id}</span>
                                    {r.manual && <Badge variant="outline" className="text-[10px]">手动</Badge>}
                                    {added ? (
                                        <span className="text-[11px] text-muted-foreground">已添加</span>
                                    ) : (
                                        <select
                                            aria-label={`${r.id} 的类型`}
                                            className="text-xs border rounded px-1.5 py-0.5 bg-background"
                                            value={r.type}
                                            onChange={(e) => setType(r.id, e.target.value as ModelType)}
                                            onClick={(e) => e.stopPropagation()}
                                        >
                                            <option value="CHAT">{TYPE_LABEL.CHAT}</option>
                                            <option value="EMBEDDING">{TYPE_LABEL.EMBEDDING}</option>
                                        </select>
                                    )}
                                </label>
                            );
                        })}
                    </div>

                    <div className="flex gap-2">
                        <Input
                            placeholder={manualType === 'EMBEDDING'
                                ? (preset?.sampleEmbeddingModelId ? `手动添加，如：${preset.sampleEmbeddingModelId}` : '手动添加向量模型 ID')
                                : (preset?.sampleModelId ? `手动添加，如：${preset.sampleModelId}` : '手动添加模型 ID')}
                            value={manualId}
                            onChange={(e) => setManualId(e.target.value)}
                            onKeyDown={(e) => e.key === 'Enter' && addManual()}
                        />
                        <select
                            aria-label="手动添加的类型"
                            className="text-sm border rounded-md px-2 bg-background"
                            value={manualType}
                            onChange={(e) => setManualType(e.target.value as ModelType)}
                        >
                            <option value="CHAT">对话</option>
                            <option value="EMBEDDING">向量</option>
                        </select>
                        <Button variant="outline" onClick={addManual} disabled={!manualId.trim()}>
                            <Plus className="w-4 h-4 mr-1" /> 加入
                        </Button>
                    </div>

                    {results && (
                        <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-xs space-y-1" data-testid="add-results">
                            {results.map((r, i) => (
                                <p key={i} className={r.ok ? 'text-green-700' : 'text-amber-700'}>
                                    {r.modelId && <b className="font-mono">{r.modelId}</b>} {r.ok ? '已添加' : r.message}
                                </p>
                            ))}
                        </div>
                    )}
                </div>

                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>关闭</Button>
                    <Button onClick={submit} disabled={checked.length === 0 || submitting}>
                        {submitting && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                        {submitting ? '正在添加并校验…' : `添加 ${checked.length} 个模型`}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}

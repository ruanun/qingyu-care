import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import type { AiModelConfig } from '@/api/ai-model';
import { isEmbeddingModel } from '@/lib/modelType';

interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    model: AiModelConfig | null;
    onSubmit: (patch: Partial<AiModelConfig>) => Promise<void>;
}

/**
 * 编辑模型：只改模型自己的属性。端点与 Key 属于供应商连接，在连接上改。
 * 对话模型可改场景与优先级（路由与降级用），向量模型可改推荐阈值。
 *
 * @author ruan
 */
export function ModelEditDialog({ open, onOpenChange, model, onSubmit }: Props) {
    const [form, setForm] = useState<Partial<AiModelConfig>>({});
    const [saving, setSaving] = useState(false);
    const embedding = model ? isEmbeddingModel(model) : false;

    useEffect(() => {
        if (!open || !model) return;
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 打开时用当前模型复位表单
        setForm({
            name: model.name, modelId: model.modelId, scenario: model.scenario, priority: model.priority,
            similarityThreshold: model.similarityThreshold ?? null, remark: model.remark ?? '',
        });
    }, [open, model]);

    const submit = async () => {
        setSaving(true);
        try {
            await onSubmit(form);
        } finally {
            setSaving(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="sm:max-w-[480px]">
                <DialogHeader>
                    <DialogTitle>编辑{embedding ? '向量' : '对话'}模型</DialogTitle>
                    <DialogDescription>端点与 API Key 属于供应商连接，在连接上修改。</DialogDescription>
                </DialogHeader>
                <div className="grid gap-4 py-2">
                    <div className="grid grid-cols-4 items-center gap-4">
                        <label htmlFor="model-name" className="text-right text-sm font-medium">名称</label>
                        <Input id="model-name" className="col-span-3" value={form.name ?? ''}
                               onChange={(e) => setForm({ ...form, name: e.target.value })} />
                    </div>
                    <div className="grid grid-cols-4 items-center gap-4">
                        <label htmlFor="model-id" className="text-right text-sm font-medium">模型 ID</label>
                        <Input id="model-id" className="col-span-3 font-mono text-xs" value={form.modelId ?? ''}
                               onChange={(e) => setForm({ ...form, modelId: e.target.value })} />
                    </div>
                    {embedding ? (
                        <div className="grid grid-cols-4 items-center gap-4">
                            <label htmlFor="model-threshold" className="text-right text-sm font-medium">推荐阈值</label>
                            <Input id="model-threshold" type="number" step="0.01" min="0" max="1" className="col-span-3"
                                   value={form.similarityThreshold ?? ''}
                                   onChange={(e) => setForm({ ...form, similarityThreshold: e.target.value === '' ? null : Number(e.target.value) })}
                                   placeholder="不同模型分数刻度不同，建议用真实问题测一遍再调" />
                        </div>
                    ) : (
                        <>
                            <div className="grid grid-cols-4 items-center gap-4">
                                <label htmlFor="model-scenario" className="text-right text-sm font-medium">适用场景</label>
                                <select id="model-scenario" className="col-span-3 h-9 border rounded-md px-2 text-sm bg-background"
                                        value={form.scenario ?? 'default'}
                                        onChange={(e) => setForm({ ...form, scenario: e.target.value })}>
                                    <option value="default">默认（所有场景）</option>
                                    <option value="complaint">投诉处理（推荐强模型）</option>
                                    <option value="faq">基础问答（推荐小模型）</option>
                                </select>
                            </div>
                            <div className="grid grid-cols-4 items-center gap-4">
                                <label htmlFor="model-priority" className="text-right text-sm font-medium">优先级</label>
                                <Input id="model-priority" type="number" className="col-span-3" value={form.priority ?? ''}
                                       onChange={(e) => setForm({ ...form, priority: e.target.value === '' ? undefined : Number(e.target.value) })}
                                       placeholder="数字越小越优先，主模型失败时按序降级" />
                            </div>
                        </>
                    )}
                    <div className="grid grid-cols-4 items-center gap-4">
                        <label htmlFor="model-remark" className="text-right text-sm font-medium">备注</label>
                        <Input id="model-remark" className="col-span-3" value={form.remark ?? ''}
                               onChange={(e) => setForm({ ...form, remark: e.target.value })} />
                    </div>
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
                    <Button onClick={submit} disabled={saving}>
                        {saving && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}保存
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}

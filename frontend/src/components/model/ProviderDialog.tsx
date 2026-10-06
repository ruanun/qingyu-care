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
import { ProviderField } from '@/components/model/ProviderField';
import type { ProviderPreset } from '@/api/ai-model';
import type { ModelProvider, ProviderForm } from '@/api/modelProvider';

interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    /** 为 null 表示新建 */
    editing: ModelProvider | null;
    presets: ProviderPreset[];
    onSubmit: (form: ProviderForm) => Promise<void>;
}

const EMPTY: ProviderForm = { name: '', providerKey: '', endpoint: '', apiKey: '', remark: '' };

/**
 * 新建 / 编辑供应商连接。选预设厂商会一并填好名称与端点（各家后缀不统一，让人自己猜就是静默 404）。
 * 编辑时 Key 不回显，留空表示不修改；改了端点或 Key 会同步到连接下全部模型。
 *
 * @author ruan
 */
export function ProviderDialog({ open, onOpenChange, editing, presets, onSubmit }: Props) {
    const [form, setForm] = useState<ProviderForm>(EMPTY);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (!open) return;
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 打开时按「新建 / 编辑」复位表单
        setForm(editing
            ? { name: editing.name, providerKey: editing.providerKey, endpoint: editing.endpoint ?? '', apiKey: '', remark: editing.remark ?? '' }
            : EMPTY);
    }, [open, editing]);

    const set = (k: keyof ProviderForm, v: string) => setForm((f) => ({ ...f, [k]: v }));

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
            <DialogContent className="sm:max-w-[520px]">
                <DialogHeader>
                    <DialogTitle>{editing ? '编辑供应商连接' : '添加供应商'}</DialogTitle>
                    <DialogDescription>
                        {editing
                            ? '修改端点或 Key 会同步到这个连接下的全部模型；其中的向量模型会先用新凭据校验一遍。'
                            : '填一次 Key，之后在这个连接下添加对话模型与向量模型。'}
                    </DialogDescription>
                </DialogHeader>
                <div className="grid gap-4 py-2">
                    <ProviderField
                        provider={form.providerKey}
                        presets={presets}
                        onProviderChange={(v) => set('providerKey', v)}
                        onPresetSelect={(p) => setForm((f) => ({
                            ...f,
                            providerKey: p.key,
                            endpoint: p.defaultEndpoint,
                            name: f.name.trim() ? f.name : p.label,
                        }))}
                    />
                    <div className="grid grid-cols-4 items-center gap-4">
                        <label htmlFor="provider-name" className="text-right text-sm font-medium">名称</label>
                        <Input id="provider-name" className="col-span-3" value={form.name}
                               onChange={(e) => set('name', e.target.value)} placeholder="如：通义千问 · 生产" />
                    </div>
                    <div className="grid grid-cols-4 items-center gap-4">
                        <label htmlFor="provider-endpoint" className="text-right text-sm font-medium">端点</label>
                        <Input id="provider-endpoint" className="col-span-3 font-mono text-xs" value={form.endpoint}
                               onChange={(e) => set('endpoint', e.target.value)} placeholder="选填，默认使用厂商官方端点" />
                    </div>
                    <div className="grid grid-cols-4 items-center gap-4">
                        <label htmlFor="provider-key" className="text-right text-sm font-medium">API Key</label>
                        <Input id="provider-key" type="password" className="col-span-3" value={form.apiKey}
                               onChange={(e) => set('apiKey', e.target.value)}
                               placeholder={editing ? (editing.hasApiKey ? '已配置，留空表示不修改' : '未配置') : '请输入 API Key'} />
                    </div>
                    <div className="grid grid-cols-4 items-center gap-4">
                        <label htmlFor="provider-remark" className="text-right text-sm font-medium">备注</label>
                        <Input id="provider-remark" className="col-span-3" value={form.remark}
                               onChange={(e) => set('remark', e.target.value)} />
                    </div>
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
                    <Button onClick={submit} disabled={saving || !form.name.trim() || !form.providerKey.trim()}>
                        {saving && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                        {editing ? '保存' : '保存并添加模型'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}

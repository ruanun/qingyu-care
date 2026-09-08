import React, { useEffect, useState } from 'react';
import { Plus, Edit2, Trash2 } from 'lucide-react';
import { platformService } from '@/api/platform';
import { aiModelService } from '@/api/ai-model';
import type { AiModelConfig, ProviderPreset } from '@/api/ai-model';
import { ProviderField } from '@/components/model/ProviderField';
import { ProviderBadge } from '@/components/model/ProviderBadge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage } from '@/lib/apiResult';

const empty: Partial<AiModelConfig> = {
  name: '',
  provider: 'dashscope',
  modelId: '',
  endpoint: '',
  apiKey: '',
  scenario: 'default',
  priority: 100,
  enabled: true,
  remark: '',
};

const SharedModelPage: React.FC = () => {
  const { toast } = useToast();
  const [list, setList] = useState<AiModelConfig[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<Partial<AiModelConfig>>(empty);
  const [isSaving, setIsSaving] = useState(false);
  // 厂商预设清单。唯一事实源在后端 ModelProviderCatalog，两个模型页共用同一份。
  const [providers, setProviders] = useState<ProviderPreset[]>([]);

  const reload = async () => {
    try {
      setIsLoading(true);
      const res = await platformService.listSharedModels();
      if (res.code !== 200) throw new Error(res.message);
      setList(res.data);
    } catch (e: unknown) {
      toast({ title: '加载失败', description: getErrorMessage(e), variant: 'destructive' });
    } finally {
      setIsLoading(false);
    }
  };

  // 预设拉不到就降级成纯文本框（本页改造前就是这个形态），不挡住整页
  const loadProviders = async () => {
    try {
      const res = await aiModelService.listProviders();
      if (res.code !== 200) throw new Error(res.message);
      setProviders(res.data);
    } catch {
      setProviders([]);
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- effect 里调的取数函数开头会 setIsLoading(true)，加载态本就要触发一次重渲染；正解是改用数据请求库替掉 effect 取数，见技术债 7
    reload();
    loadProviders();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const setField = (k: keyof AiModelConfig, v: unknown) =>
    setForm((p) => ({ ...p, [k]: v }));

  // 提供商字段要插在「名称」和「模型 ID」之间，单个 map 表达不了这个位置，故拆成小函数复用
  const renderTextFields = (fields: [keyof AiModelConfig, string, string][]) =>
    fields.map(([k, label, placeholder]) => (
      <div key={k} className="grid grid-cols-4 items-center gap-4">
        <label htmlFor={k} className="text-right text-sm font-medium">{label}</label>
        <Input
          id={k}
          className="col-span-3"
          value={(form[k] as string) ?? ''}
          onChange={(e) => setField(k, e.target.value)}
          placeholder={placeholder}
        />
      </div>
    ));

  const openCreate = () => {
    setForm(empty);
    setOpen(true);
  };

  const openEdit = (m: AiModelConfig) => {
    // API Key 不回显
    setForm({ ...m, apiKey: '' });
    setOpen(true);
  };

  const handleClose = (o: boolean) => {
    setOpen(o);
    if (!o) setForm(empty);
  };

  const submit = async () => {
    if (!form.name || !form.provider || !form.modelId) {
      toast({ title: '请填写完整信息（名称、提供商、模型ID 为必填）', variant: 'destructive' });
      return;
    }
    setIsSaving(true);
    try {
      if (form.id) {
        const res = await platformService.updateSharedModel(form.id, form);
        if (res.code !== 200) throw new Error(res.message);
      } else {
        const res = await platformService.createSharedModel(form);
        if (res.code !== 200) throw new Error(res.message);
      }
      toast({ title: '已保存' });
      setOpen(false);
      setForm(empty);
      reload();
    } catch (e: unknown) {
      toast({ title: '保存失败', description: getErrorMessage(e), variant: 'destructive' });
    } finally {
      setIsSaving(false);
    }
  };

  const remove = async (id: number) => {
    if (!confirm('确定要删除该共享模型吗？')) return;
    try {
      const res = await platformService.deleteSharedModel(id);
      if (res.code !== 200) throw new Error(res.message);
      toast({ title: '共享模型已删除' });
      reload();
    } catch (e: unknown) {
      toast({ title: '删除失败', description: getErrorMessage(e), variant: 'destructive' });
    }
  };

  return (
    <div className="p-8 h-full overflow-y-auto">
      <div className="max-w-6xl mx-auto space-y-6">
        {/* 标题栏 */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">共享模型池</h1>
            <p className="text-muted-foreground text-sm mt-1">
              平台级共享 AI 模型，所有租户均可使用，优先级低于租户自有模型
            </p>
          </div>
          <Button onClick={openCreate}>
            <Plus className="w-4 h-4 mr-2" />
            新增共享模型
          </Button>
        </div>

        {/* 表格 */}
        {isLoading ? (
          <div className="flex items-center justify-center py-20">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        ) : (
          <div className="border rounded-md overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted/50">
                <tr>
                  <th className="text-left p-3">名称</th>
                  <th className="text-left p-3">提供商</th>
                  <th className="text-left p-3">模型 ID</th>
                  <th className="text-left p-3">场景</th>
                  <th className="text-left p-3">优先级</th>
                  <th className="text-left p-3">状态</th>
                  <th className="text-right p-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {list.map((m) => (
                  <tr key={m.id} className="border-t hover:bg-muted/20 transition-colors">
                    <td className="p-3 font-medium">{m.name}</td>
                    <td className="p-3"><ProviderBadge provider={m.provider} presets={providers} /></td>
                    <td className="p-3 font-mono text-xs">{m.modelId}</td>
                    <td className="p-3">{m.scenario}</td>
                    <td className="p-3">{m.priority}</td>
                    <td className="p-3">
                      {m.enabled
                        ? <Badge variant="secondary" className="bg-green-100 text-green-700">启用</Badge>
                        : <Badge variant="outline" className="text-muted-foreground">停用</Badge>}
                    </td>
                    <td className="p-3 text-right space-x-2">
                      <Button variant="outline" size="sm" onClick={() => openEdit(m)}>
                        <Edit2 className="w-3 h-3 mr-1" />
                        编辑
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        className="text-red-500 hover:text-red-600 hover:bg-red-50"
                        onClick={() => remove(m.id)}
                      >
                        <Trash2 className="w-3 h-3 mr-1" />
                        删除
                      </Button>
                    </td>
                  </tr>
                ))}
                {list.length === 0 && (
                  <tr>
                    <td colSpan={7} className="p-8 text-center text-muted-foreground">
                      暂无共享模型，请点击右上角新增
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* 新增/编辑 对话框 */}
      <Dialog open={open} onOpenChange={handleClose}>
        <DialogContent className="sm:max-w-[500px]">
          <DialogHeader>
            <DialogTitle>{form.id ? '编辑共享模型' : '新增共享模型'}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            {renderTextFields([['name', '名称', '如：通义千问默认']])}
            <ProviderField
              provider={form.provider || ''}
              presets={providers}
              onProviderChange={(val) => setField('provider', val)}
              onPresetSelect={(preset) => {
                setField('provider', preset.key);
                // 端点一并填上：各家后缀并不统一，让人自己猜就是最难排查的那类静默 404
                setField('endpoint', preset.defaultEndpoint);
              }}
            />
            {renderTextFields([
              ['modelId', '模型 ID', '如：qwen-plus'],
              ['endpoint', '端点（选填）', '留空使用厂商官方端点'],
              ['scenario', '场景', 'default / complaint / faq'],
            ])}
            <div className="grid grid-cols-4 items-center gap-4">
              <label htmlFor="apiKey" className="text-right text-sm font-medium">API Key</label>
              <Input
                id="apiKey"
                type="password"
                className="col-span-3"
                value={(form.apiKey as string) ?? ''}
                onChange={(e) => setField('apiKey', e.target.value)}
                placeholder={form.id ? '留空表示不修改' : '请输入 API Key'}
              />
            </div>
            <div className="grid grid-cols-4 items-center gap-4">
              <label htmlFor="priority" className="text-right text-sm font-medium">优先级</label>
              <Input
                id="priority"
                type="number"
                className="col-span-3"
                value={form.priority ?? ''}
                onChange={(e) => setField('priority', e.target.value === '' ? undefined : Number(e.target.value))}
                placeholder="1 为最高优先级"
              />
            </div>
            <div className="grid grid-cols-4 items-center gap-4">
              <label htmlFor="remark" className="text-right text-sm font-medium">备注</label>
              <Input
                id="remark"
                className="col-span-3"
                value={(form.remark as string) ?? ''}
                onChange={(e) => setField('remark', e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>取消</Button>
            <Button onClick={submit} disabled={isSaving}>
              {isSaving ? '保存中…' : '保存配置'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
};

export default SharedModelPage;

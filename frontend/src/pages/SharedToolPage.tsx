import React, { useEffect, useState } from 'react';
import { Plus, Edit2, Trash2, Wrench } from 'lucide-react';
import { platformService } from '@/api/platform';
import type { AiTool } from '@/api/chat-tool';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Switch } from '@/components/ui/switch';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { useToast } from '@/hooks/use-toast';
import { useConfirm } from '@/hooks/use-confirm';
import { getErrorMessage } from '@/lib/apiResult';

const empty: Partial<AiTool> = {
  name: '',
  description: '',
  inputSchema: '{\n  "type": "object",\n  "properties": {}\n}',
  httpMethod: 'GET',
  urlTemplate: '',
  authHeader: '',
  timeoutMs: 5000,
  maxResponseChars: 4000,
  enabled: true,
};

const SharedToolPage: React.FC = () => {
  const { toast } = useToast();
  const { confirm, confirmDialog } = useConfirm();
  const [list, setList] = useState<AiTool[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<Partial<AiTool>>(empty);
  const [isSaving, setIsSaving] = useState(false);

  const reload = async () => {
    try {
      setIsLoading(true);
      const res = await platformService.listSharedTools();
      if (res.code !== 200) throw new Error(res.message);
      setList(res.data);
    } catch (e: unknown) {
      toast({ title: '加载失败', description: getErrorMessage(e), variant: 'destructive' });
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- effect 里调的取数函数开头会 setIsLoading(true)，加载态本就要触发一次重渲染；正解是改用数据请求库替掉 effect 取数，见技术债 7
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const setField = (k: keyof AiTool, v: unknown) =>
    setForm((p) => ({ ...p, [k]: v }));

  const openCreate = () => {
    setForm(empty);
    setOpen(true);
  };

  const openEdit = (t: AiTool) => {
    // 鉴权头不回显
    setForm({ ...t, authHeader: '' });
    setOpen(true);
  };

  const handleClose = (o: boolean) => {
    setOpen(o);
    if (!o) setForm(empty);
  };

  const submit = async () => {
    if (!form.name || !form.description || !form.urlTemplate) {
      toast({ title: '请填写完整信息（名称、描述、URL 模板为必填）', variant: 'destructive' });
      return;
    }
    setIsSaving(true);
    try {
      if (form.id) {
        const res = await platformService.updateSharedTool(form.id, form);
        if (res.code !== 200) throw new Error(res.message);
      } else {
        const res = await platformService.createSharedTool(form);
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
    if (!(await confirm({ title: '删除该共享工具？', confirmText: '删除', destructive: true }))) return;
    try {
      const res = await platformService.deleteSharedTool(id);
      if (res.code !== 200) throw new Error(res.message);
      toast({ title: '共享工具已删除' });
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
            <h1 className="text-2xl font-bold flex items-center gap-2"><Wrench className="w-6 h-6" />共享工具</h1>
            <p className="text-muted-foreground text-sm mt-1">
              平台级共享 HTTP 工具，所有租户均可勾选使用，凭据由平台持有、租户不可见
            </p>
          </div>
          <Button onClick={openCreate}>
            <Plus className="w-4 h-4 mr-2" />
            新增共享工具
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
                  <th className="text-left p-3">方法</th>
                  <th className="text-left p-3">URL 模板</th>
                  <th className="text-left p-3">状态</th>
                  <th className="text-right p-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {list.map((t) => (
                  <tr key={t.id} className="border-t hover:bg-muted/20 transition-colors">
                    <td className="p-3 font-medium font-mono">{t.name}</td>
                    <td className="p-3"><Badge variant="secondary" className="font-mono text-[10px]">{t.httpMethod}</Badge></td>
                    <td className="p-3 font-mono text-xs truncate max-w-[280px]">{t.urlTemplate}</td>
                    <td className="p-3">
                      {t.enabled
                        ? <Badge variant="secondary" className="bg-green-100 text-green-700">启用</Badge>
                        : <Badge variant="outline" className="text-muted-foreground">停用</Badge>}
                    </td>
                    <td className="p-3 text-right space-x-2">
                      <Button variant="outline" size="sm" onClick={() => openEdit(t)}>
                        <Edit2 className="w-3 h-3 mr-1" />
                        编辑
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        className="text-red-500 hover:text-red-600 hover:bg-red-50"
                        onClick={() => remove(t.id)}
                      >
                        <Trash2 className="w-3 h-3 mr-1" />
                        删除
                      </Button>
                    </td>
                  </tr>
                ))}
                {list.length === 0 && (
                  <tr>
                    <td colSpan={5} className="p-8 text-center text-muted-foreground">
                      暂无共享工具，请点击右上角新增
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
        <DialogContent className="sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>{form.id ? '编辑共享工具' : '新增共享工具'}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-3 py-2 max-h-[60vh] overflow-y-auto">
            <label className="text-sm font-medium">工具名（英文标识符）</label>
            <Input
              value={form.name ?? ''}
              onChange={(e) => setField('name', e.target.value)}
              placeholder="query_order"
            />
            <label className="text-sm font-medium">描述（给模型判断何时调用）</label>
            <Input
              value={form.description ?? ''}
              onChange={(e) => setField('description', e.target.value)}
              placeholder="根据订单号查询订单状态"
            />
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-sm font-medium">方法</label>
                <Select value={form.httpMethod ?? 'GET'} onValueChange={(v) => setField('httpMethod', v)}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="GET">GET</SelectItem>
                    <SelectItem value="POST">POST</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div>
                <label className="text-sm font-medium">超时(ms)</label>
                <Input
                  type="number"
                  value={form.timeoutMs ?? 5000}
                  onChange={(e) => setField('timeoutMs', e.target.value === '' ? undefined : Number(e.target.value))}
                />
              </div>
            </div>
            <label className="text-sm font-medium">URL 模板</label>
            <Input
              value={form.urlTemplate ?? ''}
              onChange={(e) => setField('urlTemplate', e.target.value)}
              placeholder="https://api.example.com/orders/{orderId}"
            />
            <label className="text-sm font-medium">参数 JSON Schema</label>
            <Textarea
              className="min-h-[120px] font-mono"
              value={form.inputSchema ?? ''}
              onChange={(e) => setField('inputSchema', e.target.value)}
            />
            <label className="text-sm font-medium">鉴权头（可选，如 Authorization: Bearer xxx）</label>
            <Input
              type="password"
              value={form.authHeader ?? ''}
              onChange={(e) => setField('authHeader', e.target.value)}
              placeholder={form.id ? '留空表示不修改' : 'Authorization: Bearer ...'}
            />
            <p className="text-xs text-muted-foreground">
              留空表示不修改。该凭据由平台持有，租户可以使用这个工具但看不到凭据内容。
            </p>
            <label className="text-sm font-medium">响应截断长度</label>
            <Input
              type="number"
              value={form.maxResponseChars ?? 4000}
              onChange={(e) => setField('maxResponseChars', e.target.value === '' ? undefined : Number(e.target.value))}
            />
            <div className="flex items-center justify-between pt-1">
              <label className="text-sm font-medium">启用</label>
              <Switch
                checked={form.enabled ?? true}
                onCheckedChange={(v) => setField('enabled', v)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => handleClose(false)}>取消</Button>
            <Button onClick={submit} disabled={isSaving}>
              {isSaving ? '保存中…' : '保存配置'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
        {confirmDialog}
    </div>
  );
};

export default SharedToolPage;

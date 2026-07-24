import React, { useState, useEffect } from 'react';
import { Wrench, Plus, Edit2, Trash2, Globe } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
    Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
    Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { aiToolService } from '@/api/chat-tool';
import type { AiTool } from '@/api/chat-tool';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const ToolConfigPage: React.FC = () => {
    const [tools, setTools] = useState<AiTool[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editing, setEditing] = useState<Partial<AiTool> | null>(null);
    const { toast } = useToast();

    const fetchTools = async () => {
        try {
            setIsLoading(true);
            const res = await aiToolService.list();
            if (res.code === 200) setTools(res.data);
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '获取工具列表失败', description: msg });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        fetchTools();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const handleToggle = async (t: AiTool) => {
        try {
            const res = await aiToolService.update(t.id, { enabled: !t.enabled });
            if (res.code === 200) { toast({ title: t.enabled ? '工具已禁用' : '工具已启用' }); fetchTools(); }
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '操作失败', description: msg });
        }
    };

    const handleSave = async () => {
        if (!editing?.name || !editing?.description || !editing?.urlTemplate) {
            toast({ variant: 'destructive', title: '请填写名称、描述与 URL 模板' });
            return;
        }
        try {
            const res = editing.id
                ? await aiToolService.update(editing.id, editing)
                : await aiToolService.create(editing);
            if (res.code === 200) {
                toast({ title: editing.id ? '工具已更新' : '工具已创建' });
                setIsDialogOpen(false); fetchTools();
            }
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '保存失败', description: msg });
        }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('确定要删除该工具吗？绑定它的技能将失去该工具。')) return;
        try {
            const res = await aiToolService.remove(id);
            if (res.code === 200) { toast({ title: '工具已删除' }); fetchTools(); }
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '删除失败', description: msg });
        }
    };

    const openCreate = () => {
        setEditing({
            name: '', description: '', inputSchema: '{\n  "type": "object",\n  "properties": {}\n}',
            httpMethod: 'GET', urlTemplate: '', authHeader: '', timeoutMs: 5000, maxResponseChars: 4000, enabled: true,
        });
        setIsDialogOpen(true);
    };

    const openEdit = (t: AiTool) => { setEditing({ ...t, authHeader: '' }); setIsDialogOpen(true); };

    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-6xl mx-auto space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-2"><Wrench className="w-6 h-6" />工具（HTTP）</h1>
                        <p className="text-muted-foreground text-sm mt-1">配置 AI 可调用的业务查询接口，模型只填参数不选 URL</p>
                    </div>
                    <Button onClick={openCreate}><Plus className="w-4 h-4 mr-2" />添加工具</Button>
                </div>

                {isLoading ? (
                    <div className="flex items-center justify-center py-20">
                        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
                    </div>
                ) : tools.length === 0 ? (
                    <Card className="border-dashed py-12">
                        <CardContent className="flex flex-col items-center justify-center text-muted-foreground">
                            <Wrench className="w-12 h-12 mb-4 opacity-20" /><p>尚未配置任何工具</p>
                        </CardContent>
                    </Card>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {tools.map((t) => (
                            <Card key={t.id} className={cn('flex flex-col hover:shadow-md', !t.enabled && 'opacity-60')}>
                                <CardHeader className="pb-3">
                                    <div className="flex items-center justify-between mb-2">
                                        <Badge variant="secondary" className="font-mono text-[10px]">{t.httpMethod}</Badge>
                                        <Switch checked={t.enabled} onCheckedChange={() => handleToggle(t)} />
                                    </div>
                                    <CardTitle className="text-lg font-mono">{t.name}</CardTitle>
                                    <p className="text-xs text-muted-foreground line-clamp-2 mt-1">{t.description}</p>
                                </CardHeader>
                                <CardContent className="flex-1 py-2">
                                    <div className="text-[10px] text-muted-foreground/60 flex items-center gap-1 font-mono truncate">
                                        <Globe className="w-3 h-3" />{t.urlTemplate}
                                    </div>
                                </CardContent>
                                <CardFooter className="pt-2 flex justify-end gap-2 border-t mt-auto">
                                    <Button variant="outline" size="sm" onClick={() => openEdit(t)}><Edit2 className="w-3 h-3" /></Button>
                                    <Button variant="outline" size="sm" className="text-red-500 hover:bg-red-50" onClick={() => handleDelete(t.id)}><Trash2 className="w-3 h-3" /></Button>
                                </CardFooter>
                            </Card>
                        ))}
                    </div>
                )}
            </div>

            <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                <DialogContent className="sm:max-w-[560px]">
                    <DialogHeader>
                        <DialogTitle>{editing?.id ? '编辑工具' : '添加工具'}</DialogTitle>
                        <DialogDescription>URL 模板中的 {'{参数名}'} 会被模型填入的参数替换；鉴权头保存后不回显。</DialogDescription>
                    </DialogHeader>
                    <div className="grid gap-3 py-2 max-h-[60vh] overflow-y-auto">
                        <label className="text-sm font-medium">工具名（英文标识符）</label>
                        <Input value={editing?.name || ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder="query_order" />
                        <label className="text-sm font-medium">描述（给模型判断何时调用）</label>
                        <Input value={editing?.description || ''} onChange={(e) => setEditing({ ...editing, description: e.target.value })} placeholder="根据订单号查询订单状态" />
                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <label className="text-sm font-medium">方法</label>
                                <Select value={editing?.httpMethod || 'GET'} onValueChange={(v) => setEditing({ ...editing, httpMethod: v })}>
                                    <SelectTrigger><SelectValue /></SelectTrigger>
                                    <SelectContent><SelectItem value="GET">GET</SelectItem><SelectItem value="POST">POST</SelectItem></SelectContent>
                                </Select>
                            </div>
                            <div>
                                <label className="text-sm font-medium">超时(ms)</label>
                                <Input type="number" value={editing?.timeoutMs ?? 5000} onChange={(e) => setEditing({ ...editing, timeoutMs: parseInt(e.target.value) })} />
                            </div>
                        </div>
                        <label className="text-sm font-medium">URL 模板</label>
                        <Input value={editing?.urlTemplate || ''} onChange={(e) => setEditing({ ...editing, urlTemplate: e.target.value })} placeholder="https://api.example.com/orders/{orderId}" />
                        <label className="text-sm font-medium">参数 JSON Schema</label>
                        <textarea className="w-full min-h-[120px] rounded-md border px-3 py-2 text-sm font-mono" value={editing?.inputSchema || ''} onChange={(e) => setEditing({ ...editing, inputSchema: e.target.value })} />
                        <label className="text-sm font-medium">鉴权头（可选，如 Authorization: Bearer xxx）</label>
                        <Input type="password" value={editing?.authHeader || ''} onChange={(e) => setEditing({ ...editing, authHeader: e.target.value })} placeholder={editing?.id ? '留空表示不修改' : 'Authorization: Bearer ...'} />
                        <label className="text-sm font-medium">响应截断长度</label>
                        <Input type="number" value={editing?.maxResponseChars ?? 4000} onChange={(e) => setEditing({ ...editing, maxResponseChars: parseInt(e.target.value) })} />
                    </div>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => setIsDialogOpen(false)}>取消</Button>
                        <Button onClick={handleSave}>保存</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default ToolConfigPage;

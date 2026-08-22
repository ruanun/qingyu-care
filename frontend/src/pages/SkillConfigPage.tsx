import React, { useState, useEffect, useMemo } from 'react';
import { throwIfFailed } from '@/lib/apiResult';
import { Sparkles, Plus, Edit2, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
    Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { aiSkillService, aiToolService } from '@/api/chat-tool';
import type { AiSkill, AiTool } from '@/api/chat-tool';
import { mcpApi } from '@/api/mcp';
import type { McpServer, McpTool } from '@/api/mcp';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const SkillConfigPage: React.FC = () => {
    const [skills, setSkills] = useState<AiSkill[]>([]);
    const [tools, setTools] = useState<AiTool[]>([]);
    const [mcpServers, setMcpServers] = useState<McpServer[]>([]);
    const [mcpToolsByServer, setMcpToolsByServer] = useState<Record<number, McpTool[]>>({});
    const [isLoading, setIsLoading] = useState(true);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editing, setEditing] = useState<Partial<AiSkill> | null>(null);
    const { toast } = useToast();

    const fetchAll = async () => {
        try {
            setIsLoading(true);
            const [s, t, ms] = await Promise.all([aiSkillService.list(), aiToolService.list(), mcpApi.listServers()]);
            throwIfFailed(s); setSkills(s.data);
            throwIfFailed(t); setTools(t.data);
            throwIfFailed(ms); setMcpServers(ms.data);

            const toolLists = await Promise.all(ms.data.map((srv) => mcpApi.listTools(srv.id)));
            const map: Record<number, McpTool[]> = {};
            ms.data.forEach((srv, i) => {
                const res = toolLists[i];
                throwIfFailed(res);
                // 与后端 ChatToolService.resolveMcpCallbacks 的过滤条件保持一致：
                // 只读 + ACTIVE + 所属 server 未停用——列出勾不上的工具只会让人以为是 bug。
                map[srv.id] = res.data.filter((mt) => mt.readOnly === true && mt.status === 'ACTIVE' && srv.enabled);
            });
            setMcpToolsByServer(map);
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '加载失败', description: msg });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- effect 里调的取数函数开头会 setIsLoading(true)，加载态本就要触发一次重渲染；正解是改用数据请求库替掉 effect 取数，见技术债 7
        fetchAll();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    /**
     * 真正会挂给 LLM 的 MCP 工具 id 集合。
     *
     * <p>mcpToolsByServer 已按「只读 + ACTIVE + server 未停用」过滤过（与后端
     * ChatToolService.resolveMcpCallbacks 同条件），这里直接摊平复用，不再重写一遍判据。
     */
    const usableMcpToolIds = useMemo(
        () => new Set(Object.values(mcpToolsByServer).flat().map((mt) => mt.id)),
        [mcpToolsByServer],
    );

    /**
     * 卡片计数只算真正生效的工具，并把失效的绑定数显式摆出来。
     *
     * <p>⚠️ <b>不能拿 mcpToolIds.length 直接当数</b>：远端下线工具时同步只标 DEPRECATED、
     * 刻意不删行（删了会让绑定悬空），所以 skill 上的绑定必然残留。直接计数的后果是
     * 「卡片显示绑着 N 个，实际 LLM 一个都拿不到」，且无任何提示——真机冒烟实拍过。
     */
    const countTools = (s: AiSkill) => {
        const boundMcpIds = s.mcpToolIds || [];
        const usable = boundMcpIds.filter((id) => usableMcpToolIds.has(id)).length;
        return {
            effective: (s.toolIds?.length || 0) + usable,
            stale: boundMcpIds.length - usable,
        };
    };

    const handleToggle = async (s: AiSkill) => {
        try {
            const res = await aiSkillService.update(s.id, { enabled: !s.enabled });
            throwIfFailed(res); toast({ title: s.enabled ? '技能已禁用' : '技能已启用' }); fetchAll();
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '操作失败', description: msg });
        }
    };

    const handleSave = async () => {
        if (!editing?.name || !editing?.content) {
            toast({ variant: 'destructive', title: '请填写名称与正文' });
            return;
        }
        try {
            const res = editing.id
                ? await aiSkillService.update(editing.id, editing)
                : await aiSkillService.create(editing);
            throwIfFailed(res);
            toast({ title: editing.id ? '技能已更新' : '技能已创建' });
            setIsDialogOpen(false); fetchAll();
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '保存失败', description: msg });
        }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('确定要删除该技能吗？')) return;
        try {
            const res = await aiSkillService.remove(id);
            throwIfFailed(res); toast({ title: '技能已删除' }); fetchAll();
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '删除失败', description: msg });
        }
    };

    const openCreate = () => { setEditing({ name: '', description: '', content: '', toolIds: [], mcpToolIds: [], enabled: true }); setIsDialogOpen(true); };
    const openEdit = (s: AiSkill) => { setEditing({ ...s }); setIsDialogOpen(true); };

    const toggleTool = (toolId: number) => {
        const cur = editing?.toolIds || [];
        const next = cur.includes(toolId) ? cur.filter((x) => x !== toolId) : [...cur, toolId];
        setEditing({ ...editing, toolIds: next });
    };

    const toggleMcpTool = (toolId: number) => {
        const cur = editing?.mcpToolIds || [];
        const next = cur.includes(toolId) ? cur.filter((x) => x !== toolId) : [...cur, toolId];
        setEditing({ ...editing, mcpToolIds: next });
    };

    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-6xl mx-auto space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-2"><Sparkles className="w-6 h-6" />技能（Skill）</h1>
                        <p className="text-muted-foreground text-sm mt-1">Markdown 能力包，正文注入系统提示并激活其绑定的工具（全量注入）</p>
                    </div>
                    <Button onClick={openCreate}><Plus className="w-4 h-4 mr-2" />添加技能</Button>
                </div>

                {isLoading ? (
                    <div className="flex items-center justify-center py-20">
                        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
                    </div>
                ) : skills.length === 0 ? (
                    <Card className="border-dashed py-12">
                        <CardContent className="flex flex-col items-center justify-center text-muted-foreground">
                            <Sparkles className="w-12 h-12 mb-4 opacity-20" /><p>尚未配置任何技能</p>
                        </CardContent>
                    </Card>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {skills.map((s) => {
                            const { effective, stale } = countTools(s);
                            return (
                            <Card key={s.id} className={cn('flex flex-col hover:shadow-md', !s.enabled && 'opacity-60')}>
                                <CardHeader className="pb-3">
                                    <div className="flex items-center justify-between mb-2">
                                        <div className="flex items-center gap-1.5">
                                            <Badge variant="secondary" className="text-[10px]">{effective} 个工具</Badge>
                                            {stale > 0 && (
                                                <Badge variant="destructive" className="text-[10px]" title="这些 MCP 工具已被远端下线或取消只读标注，不会挂给 AI；请到「MCP 服务」页处理后重新编辑本技能">
                                                    {stale} 个已失效
                                                </Badge>
                                            )}
                                        </div>
                                        <Switch checked={s.enabled} onCheckedChange={() => handleToggle(s)} />
                                    </div>
                                    <CardTitle className="text-lg">{s.name}</CardTitle>
                                    <p className="text-xs text-muted-foreground line-clamp-2 mt-1">{s.description || '无描述'}</p>
                                </CardHeader>
                                <CardContent className="flex-1 py-2">
                                    <p className="text-[11px] text-muted-foreground/70 line-clamp-3 whitespace-pre-wrap">{s.content}</p>
                                </CardContent>
                                <CardFooter className="pt-2 flex justify-end gap-2 border-t mt-auto">
                                    <Button variant="outline" size="sm" aria-label="编辑技能" onClick={() => openEdit(s)}><Edit2 className="w-3 h-3" /></Button>
                                    <Button variant="outline" size="sm" aria-label="删除技能" className="text-red-500 hover:bg-red-50" onClick={() => handleDelete(s.id)}><Trash2 className="w-3 h-3" /></Button>
                                </CardFooter>
                            </Card>
                            );
                        })}
                    </div>
                )}
            </div>

            <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                <DialogContent className="sm:max-w-[560px]">
                    <DialogHeader>
                        <DialogTitle>{editing?.id ? '编辑技能' : '添加技能'}</DialogTitle>
                        <DialogDescription>正文用 Markdown 描述处理某类场景的步骤；勾选本技能激活的工具。</DialogDescription>
                    </DialogHeader>
                    <div className="grid gap-3 py-2 max-h-[60vh] overflow-y-auto">
                        <label className="text-sm font-medium">名称</label>
                        <Input value={editing?.name || ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder="退货处理" />
                        <label className="text-sm font-medium">描述（可选）</label>
                        <Input value={editing?.description || ''} onChange={(e) => setEditing({ ...editing, description: e.target.value })} />
                        <label className="text-sm font-medium">正文（Markdown）</label>
                        <textarea className="w-full min-h-[160px] rounded-md border px-3 py-2 text-sm" value={editing?.content || ''} onChange={(e) => setEditing({ ...editing, content: e.target.value })} placeholder="当用户咨询退货时：\n1. 先用 query_order 查询订单状态\n2. ..." />
                        <label className="text-sm font-medium">HTTP 工具</label>
                        <div className="border rounded-md p-3 space-y-2 max-h-[140px] overflow-y-auto">
                            {tools.length === 0 ? (
                                <p className="text-xs text-muted-foreground">尚无工具，请先到「工具」页创建</p>
                            ) : tools.map((t) => (
                                <label key={t.id} className="flex items-center gap-2 text-sm cursor-pointer">
                                    <input type="checkbox" checked={(editing?.toolIds || []).includes(t.id)} onChange={() => toggleTool(t.id)} />
                                    <span className="font-mono">{t.name}</span>
                                    {t.scope === 'PLATFORM' && (
                                        <Badge variant="secondary" className="text-[10px] ml-1">平台共享</Badge>
                                    )}
                                    <span className="text-muted-foreground text-xs truncate">{t.description}</span>
                                </label>
                            ))}
                        </div>

                        <label className="text-sm font-medium">MCP 工具</label>
                        <p className="text-xs text-muted-foreground/70">
                            只列出已在「MCP 服务」页标注为只读、状态为已启用的工具；需要更多工具，请先去该页标注只读。
                        </p>
                        <div className="border rounded-md p-3 space-y-3 max-h-[160px] overflow-y-auto">
                            {mcpServers.every((srv) => (mcpToolsByServer[srv.id] || []).length === 0) ? (
                                <p className="text-xs text-muted-foreground">尚无可勾选的 MCP 工具</p>
                            ) : mcpServers.map((srv) => {
                                const toolsForServer = mcpToolsByServer[srv.id] || [];
                                if (toolsForServer.length === 0) return null;
                                return (
                                    <div key={srv.id} className="space-y-1">
                                        <p className="text-[11px] font-medium text-muted-foreground">{srv.name}</p>
                                        {toolsForServer.map((mt) => {
                                            const exposedName = `${srv.code}__${mt.name}`;
                                            return (
                                                <label key={mt.id} className="flex items-center gap-2 text-sm cursor-pointer pl-2">
                                                    <input
                                                        type="checkbox"
                                                        checked={(editing?.mcpToolIds || []).includes(mt.id)}
                                                        onChange={() => toggleMcpTool(mt.id)}
                                                    />
                                                    <span className="font-mono">{exposedName}</span>
                                                </label>
                                            );
                                        })}
                                    </div>
                                );
                            })}
                        </div>
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

export default SkillConfigPage;

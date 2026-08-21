import React, { useEffect, useState } from 'react';
import { AlertTriangle, Edit2, Loader2, Plus, RefreshCw, Server as ServerIcon, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
    Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
    Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { mcpApi } from '@/api/mcp';
import type { McpServer, McpTool } from '@/api/mcp';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';
import { cn } from '@/lib/utils';
import { McpToolList } from '@/components/mcp/McpToolList';

/**
 * MCP 服务管理页：接入远端 MCP server、同步工具清单、标注只读位。
 *
 * 左列服务列表 + 右侧详情（同 KnowledgePage 的知识库/文档主从结构）。
 *
 * ⚠️ 传输方式选择器里 SSE 选项禁用且不可选：Spring AI 的 WebFluxSseClientTransport
 * 没有 base-URL setter（javap 证实 URL 从未被应用，连不上）且已 @Deprecated(forRemoval=true)，
 * 后端 McpConnectionFactory.create() 对 SSE 直接抛 UnsupportedOperationException。
 * 保留可见只是让用户知道"以后可能支持"，此刻选它只会配出一个保存成功、一同步就报错的 server。
 *
 * @author ruan
 */
const McpServerPage: React.FC = () => {
    const [servers, setServers] = useState<McpServer[]>([]);
    const [selected, setSelected] = useState<McpServer | null>(null);
    const [tools, setTools] = useState<McpTool[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [syncingId, setSyncingId] = useState<number | null>(null);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editing, setEditing] = useState<Partial<McpServer> | null>(null);
    const [deleteTarget, setDeleteTarget] = useState<McpServer | null>(null);
    const [deleteUsage, setDeleteUsage] = useState<number | null>(null);
    const { toast } = useToast();

    const loadTools = async (serverId: number) => {
        try {
            const res = await mcpApi.listTools(serverId);
            throwIfFailed(res);
            setTools(res.data);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '获取工具清单失败', description: getErrorMessage(error) });
        }
    };

    // ⚠️ 用函数式更新而非「仅当 !selected 时才选中第一个」：后者会让 selected 在
    // sync/toggle/编辑/删除之后一直指向本次调用之前的旧对象——详情卡头部的
    // lastSyncedAt/lastSyncError/enabled 就会静默停在刷新前的值，同步成功了界面却
    // 照样显示"尚未同步"或旧的失败原因。这里改成：仍存在则换成刷新后的新对象，
    // 不存在了（被删）则退回列表第一个或 null；配合下面按 selected?.id 变化才
    // 拉工具清单的 effect，写操作各自显式 loadTools 时不会被这里重复触发。
    const fetchServers = async () => {
        setIsLoading(true);
        try {
            const res = await mcpApi.listServers();
            throwIfFailed(res);
            setServers(res.data);
            setSelected((prev) => {
                if (prev) return res.data.find((s) => s.id === prev.id) ?? res.data[0] ?? null;
                return res.data[0] ?? null;
            });
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '获取 MCP 服务列表失败', description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 挂载取数，同 KnowledgePage/ToolConfigPage 既有写法
        fetchServers();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // 选中项的 id 变化时（首次自动选中 / 用户点击列表切换 / 删除后回退到下一个）
    // 才拉一次工具清单；同一个 id 上 servers 数据被 fetchServers 刷新（sync/toggle/
    // 编辑后）不会重新触发本 effect，那些场景各自已经显式刷新过 tools。
    useEffect(() => {
        if (selected) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            loadTools(selected.id);
        } else {
            setTools([]);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selected?.id]);

    const handleSelect = (server: McpServer) => {
        setSelected(server);
    };

    const handleToggleEnabled = async (server: McpServer) => {
        try {
            const res = await mcpApi.updateServer(server.id, { enabled: !server.enabled });
            throwIfFailed(res);
            toast({ title: server.enabled ? '服务已禁用' : '服务已启用' });
            await fetchServers();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '操作失败', description: getErrorMessage(error) });
        }
    };

    const handleSync = async (server: McpServer) => {
        setSyncingId(server.id);
        try {
            const res = await mcpApi.syncTools(server.id);
            throwIfFailed(res);
            toast({ title: '同步成功', description: `已同步 ${res.data.length} 个工具` });
            // 刻意重新拉一次工具清单而不是直接用 res.data：保持"工具清单只有一个来源"，
            // 也顺带带回本地未持有的 readOnly 状态（sync 不会覆盖它，见后端注释）。
            await loadTools(server.id);
            // 顺带刷新 lastSyncedAt / lastSyncError（同步成功后旧的失败原因需要被清空）
            await fetchServers();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '同步失败', description: getErrorMessage(error) });
        } finally {
            setSyncingId(null);
        }
    };

    const handleMarkReadOnly = async (toolIds: number[], readOnly: boolean) => {
        if (toolIds.length === 0 || !selected) return;
        try {
            const res = await mcpApi.markReadOnly(toolIds, readOnly);
            throwIfFailed(res);
            toast({ title: readOnly ? '已标注为只读' : '已取消只读' });
            await loadTools(selected.id);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '标注失败', description: getErrorMessage(error) });
        }
    };

    const openCreate = () => {
        setEditing({
            name: '', code: '', description: '', transport: 'STREAMABLE_HTTP', url: '',
            authHeader: '', timeoutMs: 10000, maxResponseChars: 4000, enabled: true,
        });
        setIsDialogOpen(true);
    };

    const openEdit = (server: McpServer) => {
        setEditing({ ...server, authHeader: '' }); // 不回显凭据
        setIsDialogOpen(true);
    };

    const handleSave = async () => {
        if (!editing?.name || !editing?.code || !editing?.url) {
            toast({ variant: 'destructive', title: '请填写名称、标识符与 URL' });
            return;
        }
        try {
            const res = editing.id
                ? await mcpApi.updateServer(editing.id, editing)
                : await mcpApi.createServer(editing);
            throwIfFailed(res);
            toast({ title: editing.id ? '服务已更新' : '服务已创建' });
            setIsDialogOpen(false);
            await fetchServers();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '保存失败', description: getErrorMessage(error) });
        }
    };

    const openDeleteDialog = async (server: McpServer) => {
        setDeleteTarget(server);
        setDeleteUsage(null);
        try {
            const res = await mcpApi.usage(server.id);
            throwIfFailed(res);
            setDeleteUsage(res.data.skillCount);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '获取使用情况失败', description: getErrorMessage(error) });
        }
    };

    const handleConfirmDelete = async () => {
        if (!deleteTarget) return;
        try {
            const res = await mcpApi.deleteServer(deleteTarget.id);
            throwIfFailed(res);
            toast({ title: '服务已删除' });
            setDeleteTarget(null);
            // fetchServers 的 setSelected 更新器会发现被删的 id 已不在新列表里，
            // 自动回退到列表第一个（或 null），上面的 effect 会跟着刷新/清空工具清单
            await fetchServers();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '删除失败', description: getErrorMessage(error) });
        }
    };

    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-6xl mx-auto space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-2">
                            <ServerIcon className="w-6 h-6" />MCP 服务
                        </h1>
                        <p className="text-muted-foreground text-sm mt-1">
                            接入远端 MCP server 并同步其工具清单；工具默认视为可写、不挂载，需标注只读后才对 AI 生效
                        </p>
                    </div>
                    <Button onClick={openCreate}><Plus className="w-4 h-4 mr-2" />添加 MCP 服务</Button>
                </div>

                {isLoading ? (
                    <div className="flex items-center justify-center py-20">
                        <Loader2 className="w-8 h-8 animate-spin text-muted-foreground" />
                    </div>
                ) : servers.length === 0 ? (
                    <Card className="border-dashed py-12">
                        <CardContent className="flex flex-col items-center justify-center text-muted-foreground">
                            <ServerIcon className="w-12 h-12 mb-4 opacity-20" />
                            <p>尚未接入任何 MCP 服务</p>
                        </CardContent>
                    </Card>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
                        <div className="md:col-span-1 space-y-3">
                            <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground px-2">服务列表</h3>
                            {servers.map((server) => (
                                <div
                                    key={server.id}
                                    onClick={() => handleSelect(server)}
                                    className={cn(
                                        'p-3 rounded-xl border flex items-center gap-3 cursor-pointer transition-all',
                                        selected?.id === server.id
                                            ? 'bg-primary/5 border-primary/20 ring-1 ring-primary/20'
                                            : 'hover:bg-muted/50 text-muted-foreground',
                                        !server.enabled && 'opacity-60',
                                    )}
                                >
                                    <ServerIcon className={cn('w-4 h-4 shrink-0', selected?.id === server.id ? 'text-primary' : '')} />
                                    <div className="flex-1 overflow-hidden">
                                        <p className="text-sm font-semibold truncate">{server.name}</p>
                                        <p className="text-[10px] truncate opacity-60 font-mono">{server.code}</p>
                                    </div>
                                    {server.lastSyncError && (
                                        <AlertTriangle className="w-3.5 h-3.5 text-amber-500 shrink-0" />
                                    )}
                                </div>
                            ))}
                        </div>

                        <div className="md:col-span-3 space-y-6">
                            {selected ? (
                                <Card className="shadow-xs">
                                    <CardHeader className="space-y-3">
                                        <div className="flex items-center justify-between">
                                            <div>
                                                <CardTitle className="flex items-center gap-2">
                                                    {selected.name}
                                                    <Badge variant="secondary" className="font-mono text-[10px]">{selected.code}</Badge>
                                                    <Badge variant="outline" className="text-[10px]">
                                                        {selected.transport === 'SSE' ? 'SSE' : 'Streamable HTTP'}
                                                    </Badge>
                                                </CardTitle>
                                                <CardDescription>{selected.description || '暂无描述'}</CardDescription>
                                            </div>
                                            <div className="flex items-center gap-2">
                                                <Switch
                                                    aria-label="启用服务"
                                                    checked={selected.enabled}
                                                    onCheckedChange={() => handleToggleEnabled(selected)}
                                                />
                                                <Button
                                                    size="sm"
                                                    variant="outline"
                                                    disabled={syncingId === selected.id}
                                                    onClick={() => handleSync(selected)}
                                                >
                                                    <RefreshCw className={cn('w-3.5 h-3.5 mr-1', syncingId === selected.id && 'animate-spin')} />
                                                    同步工具
                                                </Button>
                                                <Button size="sm" variant="ghost" aria-label="编辑" onClick={() => openEdit(selected)}>
                                                    <Edit2 className="w-3.5 h-3.5" />
                                                </Button>
                                                <Button
                                                    size="sm"
                                                    variant="ghost"
                                                    aria-label="删除"
                                                    className="text-red-500 hover:bg-red-50"
                                                    onClick={() => openDeleteDialog(selected)}
                                                >
                                                    <Trash2 className="w-3.5 h-3.5" />
                                                </Button>
                                            </div>
                                        </div>
                                        <p className="text-xs text-muted-foreground">
                                            {selected.lastSyncedAt
                                                ? `上次同步：${selected.lastSyncedAt.replace('T', ' ').slice(0, 19)}`
                                                : '尚未同步'}
                                        </p>
                                        {selected.lastSyncError && (
                                            <div className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">
                                                <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                                                <span>上次同步失败：{selected.lastSyncError}</span>
                                            </div>
                                        )}
                                    </CardHeader>
                                    <CardContent>
                                        <McpToolList tools={tools} onMarkReadOnly={handleMarkReadOnly} />
                                    </CardContent>
                                </Card>
                            ) : (
                                <div className="h-64 flex flex-col items-center justify-center text-center border-2 border-dashed rounded-3xl opacity-40">
                                    <ServerIcon className="w-12 h-12 mb-4" />
                                    <p>请选择一个 MCP 服务</p>
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </div>

            {/* 创建/编辑对话框 */}
            <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                <DialogContent className="sm:max-w-[560px]">
                    <DialogHeader>
                        <DialogTitle>{editing?.id ? '编辑 MCP 服务' : '添加 MCP 服务'}</DialogTitle>
                        <DialogDescription>鉴权头保存后不回显；保存后需点击「同步工具」从远端拉取工具清单。</DialogDescription>
                    </DialogHeader>
                    <div className="grid gap-3 py-2 max-h-[60vh] overflow-y-auto">
                        <label className="text-sm font-medium">名称</label>
                        <Input
                            value={editing?.name || ''}
                            onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                            placeholder="如：订单系统"
                        />
                        <label className="text-sm font-medium">标识符（英文，参与 LLM 可见的工具名）</label>
                        <Input
                            value={editing?.code || ''}
                            onChange={(e) => setEditing({ ...editing, code: e.target.value })}
                            placeholder="order"
                        />
                        <label className="text-sm font-medium">描述</label>
                        <Input
                            value={editing?.description || ''}
                            onChange={(e) => setEditing({ ...editing, description: e.target.value })}
                        />
                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <label className="text-sm font-medium">传输方式</label>
                                <Select
                                    value={editing?.transport || 'STREAMABLE_HTTP'}
                                    onValueChange={(v) => setEditing({ ...editing, transport: v as McpServer['transport'] })}
                                >
                                    <SelectTrigger><SelectValue /></SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="STREAMABLE_HTTP">Streamable HTTP</SelectItem>
                                        {/* ⚠️ 禁用而非隐藏：让用户知道协议本身合法，只是本期后端不支持
                                            （WebFluxSseClientTransport 无 base-URL setter，选它会配出一个
                                            保存成功、一同步就报错的 server） */}
                                        <SelectItem value="SSE" disabled>SSE（暂不支持，同步会报错）</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                            <div>
                                <label className="text-sm font-medium">超时(ms)</label>
                                <Input
                                    type="number"
                                    value={editing?.timeoutMs ?? 10000}
                                    onChange={(e) => setEditing({ ...editing, timeoutMs: parseInt(e.target.value, 10) })}
                                />
                            </div>
                        </div>
                        <label className="text-sm font-medium">URL</label>
                        <Input
                            value={editing?.url || ''}
                            onChange={(e) => setEditing({ ...editing, url: e.target.value })}
                            placeholder="https://example.com/mcp"
                        />
                        <label className="text-sm font-medium">鉴权头（可选，如 Authorization: Bearer xxx）</label>
                        <Input
                            type="password"
                            value={editing?.authHeader || ''}
                            onChange={(e) => setEditing({ ...editing, authHeader: e.target.value })}
                            placeholder={editing?.id ? '留空表示不修改' : 'Authorization: Bearer ...'}
                        />
                        <label className="text-sm font-medium">响应截断长度</label>
                        <Input
                            type="number"
                            value={editing?.maxResponseChars ?? 4000}
                            onChange={(e) => setEditing({ ...editing, maxResponseChars: parseInt(e.target.value, 10) })}
                        />
                    </div>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => setIsDialogOpen(false)}>取消</Button>
                        <Button onClick={handleSave}>保存</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>

            {/* 删除确认弹窗：先查使用情况再让用户决定 */}
            <Dialog open={!!deleteTarget} onOpenChange={(open) => { if (!open) setDeleteTarget(null); }}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>删除 MCP 服务</DialogTitle>
                        <DialogDescription>
                            即将删除服务「{deleteTarget?.name}」及其全部工具行，并摘掉相关技能的绑定。
                        </DialogDescription>
                    </DialogHeader>
                    <div className="space-y-4">
                        <p className="text-sm">
                            {deleteUsage === null
                                ? '正在查询使用情况…'
                                : `该服务的工具正被 ${deleteUsage} 个技能使用。`}
                        </p>
                        <Button className="w-full" variant="destructive" onClick={handleConfirmDelete}>
                            删除
                        </Button>
                    </div>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default McpServerPage;

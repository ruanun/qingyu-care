import React, { useState, useEffect } from 'react';
import {
    Search,
    Filter,
    Tag as TagIcon,
    Clock,
    CheckCircle2,
    AlertCircle,
    User,
    Bot,
    ExternalLink,
    Loader2,
    MoreVertical,
    XCircle,
    History,
    RefreshCw,
    UserPen
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
    Table,
    TableBody,
    TableCell,
    TableHead,
    TableHeader,
    TableRow
} from '@/components/ui/table';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from "@/components/ui/dialog";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { chatService } from '@/api/chat';
import { agentService } from '@/api/agent';
import type { ChatSession, SessionStatus, SessionTag } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import { ScrollArea } from '@/components/ui/scroll-area';
import SessionMessages from '@/components/SessionMessages';

const SessionPage: React.FC = () => {
    const [sessions, setSessions] = useState<ChatSession[]>([]);
    const [isLoading, setIsLoading] = useState(false);
    const [statusFilter, setStatusFilter] = useState<SessionStatus | 'ALL'>('ALL');
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(0);
    const { toast } = useToast();

    useEffect(() => {
        fetchSessions();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [statusFilter, page]);

    async function fetchSessions() {
        setIsLoading(true);
        try {
            const status = statusFilter === 'ALL' ? undefined : statusFilter;
            const response = await chatService.getAllSessions(status, page, 10);
            if (response.code === 200) {
                setSessions(response.data.content);
                setTotalPages(response.data.totalPages);
            }
        } catch (error: any) {
            toast({
                variant: "destructive",
                title: "获取会话失败",
                description: error.message,
            });
        } finally {
            setIsLoading(false);
        }
    };

    const getStatusBadge = (status: SessionStatus) => {
        switch (status) {
            case 'AI_PROCESSING':
                return <Badge variant="secondary" className="gap-1"><Bot className="w-3 h-3" /> AI 回复中</Badge>;
            case 'AGENT_PROCESSING':
                return <Badge className="bg-blue-500 hover:bg-blue-600 gap-1"><User className="w-3 h-3" /> 人工处理中</Badge>;
            case 'WAITING_AGENT':
                return <Badge variant="destructive" className="animate-pulse gap-1"><AlertCircle className="w-3 h-3" /> 等待接管</Badge>;
            case 'ENDED':
                return <Badge variant="outline" className="text-muted-foreground gap-1"><CheckCircle2 className="w-3 h-3" /> 已结束</Badge>;
            case 'TIMEOUT':
                return <Badge variant="outline" className="text-orange-500 border-orange-200 gap-1"><Clock className="w-3 h-3" /> 已超时</Badge>;
            default:
                return <Badge variant="outline">{status}</Badge>;
        }
    };

    const getTagBadge = (tag?: SessionTag) => {
        if (!tag) return null;
        switch (tag) {
            case 'PRE_SALES':
                return <Badge variant="outline" className="border-blue-200 text-blue-600 bg-blue-50">售前咨询</Badge>;
            case 'AFTER_SALES':
                return <Badge variant="outline" className="border-green-200 text-green-600 bg-green-50">售后服务</Badge>;
            case 'COMPLAINT':
                return <Badge variant="outline" className="border-red-200 text-red-600 bg-red-50">投诉</Badge>;
            case 'TECH_SUPPORT':
                return <Badge variant="outline" className="border-purple-200 text-purple-600 bg-purple-50">技术支持</Badge>;
            case 'OTHER':
                return <Badge variant="outline" className="border-gray-200 text-gray-600 bg-gray-50">其他</Badge>;
            default:
                return <Badge variant="outline">{tag}</Badge>;
        }
    };

    const handleToggleTag = async (sessionId: string, tag: SessionTag, currentTags?: SessionTag[]) => {
        try {
            const hasTags = currentTags?.includes(tag);
            if (hasTags) {
                await chatService.removeSessionTag(sessionId, tag);
                toast({ title: "标签已移除" });
            } else {
                await chatService.addSessionTag(sessionId, tag);
                toast({ title: "标签已添加" });
            }
            fetchSessions();
        } catch (error: any) {
            toast({ variant: "destructive", title: "操作失败", description: error.message });
        }
    };

    const handleEndSession = async (sessionId: string) => {
        try {
            await chatService.endSession(sessionId);
            toast({ title: '会话已结束' });
            fetchSessions();
        } catch (error: any) {
            toast({ variant: 'destructive', title: '操作失败', description: error.message });
        }
    };

    const handleManualTakeover = async (sessionId: string) => {
        try {
            await agentService.acceptSession(sessionId, 1); // Mock agent ID = 1
            toast({ title: '已接管会话', description: '可到人工工作台回复用户' });
            fetchSessions();
        } catch (error: any) {
            toast({ variant: 'destructive', title: '接管失败', description: error.message });
        }
    };

    return (
        <div className="p-8 max-w-7xl mx-auto space-y-8 h-full overflow-hidden flex flex-col">
            <div className="flex justify-between items-end shrink-0">
                <div>
                    <h1 className="text-3xl font-bold tracking-tight">会话管理</h1>
                    <p className="text-muted-foreground mt-2">监控系统内的所有对话，进行分类、回顾和人工干预。</p>
                </div>
            </div>

            <div className="flex items-center gap-4 shrink-0 bg-muted/30 p-4 rounded-xl border">
                <div className="flex-1 relative">
                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
                    <Input placeholder="搜索用户ID 或 会话ID..." className="pl-10 bg-background" />
                </div>
                <div className="flex items-center gap-2">
                    <Filter className="w-4 h-4 text-muted-foreground" />
                    <Select value={statusFilter} onValueChange={(val) => setStatusFilter(val as any)}>
                        <SelectTrigger className="w-40 bg-background">
                            <SelectValue placeholder="状态过滤" />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="ALL">全部状态</SelectItem>
                            <SelectItem value="AI_PROCESSING">AI 回复中</SelectItem>
                            <SelectItem value="WAITING_AGENT">等待接管</SelectItem>
                            <SelectItem value="AGENT_PROCESSING">人工处理中</SelectItem>
                            <SelectItem value="ENDED">已结束</SelectItem>
                            <SelectItem value="TIMEOUT">已超时</SelectItem>
                        </SelectContent>
                    </Select>
                </div>
                <Button variant="outline" size="icon" onClick={() => fetchSessions()} disabled={isLoading}>
                    <RefreshCw className={cn("w-4 h-4", isLoading && "animate-spin")} />
                </Button>
            </div>

            <div className="flex-1 overflow-hidden border rounded-xl bg-background shadow-sm">
                <ScrollArea className="h-full">
                    <Table>
                        <TableHeader className="sticky top-0 bg-background z-10 shadow-sm">
                            <TableRow>
                                <TableHead className="w-32">创建时间</TableHead>
                                <TableHead>用户 ID</TableHead>
                                <TableHead>状态</TableHead>
                                <TableHead>标签</TableHead>
                                <TableHead className="max-w-xs">摘要</TableHead>
                                <TableHead className="text-right">操作</TableHead>
                            </TableRow>
                        </TableHeader>
                        <TableBody>
                            {isLoading ? (
                                <TableRow>
                                    <TableCell colSpan={6} className="h-64 text-center">
                                        <div className="flex flex-col items-center gap-2">
                                            <Loader2 className="w-8 h-8 animate-spin text-primary" />
                                            <p className="text-sm text-muted-foreground">加载会话中...</p>
                                        </div>
                                    </TableCell>
                                </TableRow>
                            ) : sessions.length === 0 ? (
                                <TableRow>
                                    <TableCell colSpan={6} className="h-64 text-center">
                                        <div className="flex flex-col items-center gap-2 opacity-40">
                                            <History className="w-12 h-12" />
                                            <p className="text-sm">暂无符合条件的会话</p>
                                        </div>
                                    </TableCell>
                                </TableRow>
                            ) : (
                                sessions.map((session) => (
                                    <TableRow key={session.id} className="group transition-colors hover:bg-muted/30">
                                        <TableCell className="text-xs text-muted-foreground font-mono">
                                            {new Date(session.createdAt).toLocaleString([], {
                                                month: 'numeric',
                                                day: 'numeric',
                                                hour: '2-digit',
                                                minute: '2-digit'
                                            })}
                                        </TableCell>
                                        <TableCell>
                                            <div className="flex items-center gap-2">
                                                <div className="w-6 h-6 rounded-full bg-muted flex items-center justify-center">
                                                    <User className="w-3 h-3 text-muted-foreground" />
                                                </div>
                                                <span className="text-sm font-medium">{session.userId}</span>
                                            </div>
                                        </TableCell>
                                        <TableCell>{getStatusBadge(session.status)}</TableCell>
                                        <TableCell>
                                            {session.tags && session.tags.length > 0 ? (
                                                <div className="flex gap-1 flex-wrap">
                                                    {session.tags.map(tag => getTagBadge(tag))}
                                                </div>
                                            ) : (
                                                <span className="text-[10px] text-muted-foreground opacity-30">未标记</span>
                                            )}
                                        </TableCell>
                                        <TableCell className="max-w-xs">
                                            <p className="text-xs truncate text-muted-foreground">
                                                {session.summary || '暂无摘要'}
                                            </p>
                                        </TableCell>
                                        <TableCell className="text-right">
                                            <div className="flex justify-end gap-2">
                                                <Dialog>
                                                    <DialogTrigger asChild>
                                                        <Button variant="ghost" size="sm" className="h-8 gap-1">
                                                            查看详情 <ExternalLink className="w-3 h-3" />
                                                        </Button>
                                                    </DialogTrigger>
                                                    <DialogContent className="max-w-2xl h-[80vh] flex flex-col p-0">
                                                        <DialogHeader className="p-6 pb-0">
                                                            <DialogTitle>会话详情</DialogTitle>
                                                            <DialogDescription className="flex items-center gap-4 mt-2">
                                                                <span className="flex items-center gap-1 font-mono text-[10px]"><Clock className="w-3 h-3" /> {new Date(session.createdAt).toLocaleString()}</span>
                                                                {getStatusBadge(session.status)}
                                                            </DialogDescription>
                                                        </DialogHeader>
                                                        <div className="flex-1 overflow-hidden p-6 pt-2">
                                                            <SessionMessages sessionId={session.sessionId} />
                                                        </div>
                                                    </DialogContent>
                                                </Dialog>

                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <Button variant="ghost" size="icon" className="h-8 w-8">
                                                            <MoreVertical className="w-4 h-4" />
                                                        </Button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end" className="w-40">
                                                        <DropdownMenuLabel>打标签（点击切换）</DropdownMenuLabel>
                                                        <DropdownMenuItem onClick={() => handleToggleTag(session.sessionId, 'PRE_SALES', session.tags)}>
                                                            <TagIcon className="mr-2 h-4 w-4" /> 售前咨询 {session.tags?.includes('PRE_SALES') && '✓'}
                                                        </DropdownMenuItem>
                                                        <DropdownMenuItem onClick={() => handleToggleTag(session.sessionId, 'AFTER_SALES', session.tags)}>
                                                            <TagIcon className="mr-2 h-4 w-4" /> 售后服务 {session.tags?.includes('AFTER_SALES') && '✓'}
                                                        </DropdownMenuItem>
                                                        <DropdownMenuItem onClick={() => handleToggleTag(session.sessionId, 'COMPLAINT', session.tags)}>
                                                            <TagIcon className="mr-2 h-4 w-4" /> 投诉 {session.tags?.includes('COMPLAINT') && '✓'}
                                                        </DropdownMenuItem>
                                                        <DropdownMenuItem onClick={() => handleToggleTag(session.sessionId, 'TECH_SUPPORT', session.tags)}>
                                                            <TagIcon className="mr-2 h-4 w-4" /> 技术支持 {session.tags?.includes('TECH_SUPPORT') && '✓'}
                                                        </DropdownMenuItem>
                                                        <DropdownMenuItem onClick={() => handleToggleTag(session.sessionId, 'OTHER', session.tags)}>
                                                            <TagIcon className="mr-2 h-4 w-4" /> 其他 {session.tags?.includes('OTHER') && '✓'}
                                                        </DropdownMenuItem>
                                                        <DropdownMenuSeparator />
                                                        <DropdownMenuItem
                                                            disabled={session.status !== 'AI_PROCESSING' && session.status !== 'WAITING_AGENT'}
                                                            onClick={() => handleManualTakeover(session.sessionId)}
                                                        >
                                                            <UserPen className="mr-2 h-4 w-4" /> 手动接管
                                                        </DropdownMenuItem>
                                                        <DropdownMenuSeparator />
                                                        <DropdownMenuItem
                                                            className="text-destructive focus:text-destructive"
                                                            disabled={session.status === 'ENDED' || session.status === 'TIMEOUT'}
                                                            onClick={() => handleEndSession(session.sessionId)}
                                                        >
                                                            <XCircle className="mr-2 h-4 w-4" /> 结束会话
                                                        </DropdownMenuItem>
                                                    </DropdownMenuContent>
                                                </DropdownMenu>
                                            </div>
                                        </TableCell>
                                    </TableRow>
                                ))
                            )}
                        </TableBody>
                    </Table>
                </ScrollArea>
            </div>

            <div className="shrink-0 flex items-center justify-between pt-4">
                <p className="text-xs text-muted-foreground">
                    共 {totalPages} 页
                </p>
                <div className="flex gap-2">
                    <Button
                        variant="outline"
                        size="sm"
                        disabled={page === 0}
                        onClick={() => setPage(p => p - 1)}
                    >
                        上一页
                    </Button>
                    <Button
                        variant="outline"
                        size="sm"
                        disabled={page >= totalPages - 1}
                        onClick={() => setPage(p => p + 1)}
                    >
                        下一页
                    </Button>
                </div>
            </div>
        </div>
    );
};

export default SessionPage;

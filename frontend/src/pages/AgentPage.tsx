import React, { useState, useEffect, useRef } from 'react';
import { UserPen, AlertCircle, Send, User, RefreshCw, Loader2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import { agentService } from '@/api/agent';
import { chatService } from '@/api/chat';
import { connectSse } from '@/lib/sse';
import type { ChatSession, ChatMessage, MessageRole } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const AgentPage: React.FC = () => {
    const [waitingSessions, setWaitingSessions] = useState<ChatSession[]>([]);
    const [selectedSession, setSelectedSession] = useState<ChatSession | null>(null);
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [replyInput, setReplyInput] = useState('');
    const [isSending, setIsSending] = useState(false);
    const [unread, setUnread] = useState<Record<string, number>>({});
    const [onlineCount, setOnlineCount] = useState(0);
    const { toast } = useToast();

    const selectedRef = useRef<ChatSession | null>(null);
    useEffect(() => {
        selectedRef.current = selectedSession;
    }, [selectedSession]);

    useEffect(() => {
        fetchWaitingSessions();
        const interval = setInterval(fetchWaitingSessions, 10000); // 每10秒刷新
        return () => clearInterval(interval);
    }, []);

    // 坐席 SSE：挂载即连，卸载 close；当前会话用 ref 读取以避免频繁重连
    useEffect(() => {
        const conn = connectSse('/api/v1/chat/agent/stream', {
            onEvent: (event, data) => {
                let payload: Record<string, unknown>;
                try {
                    payload = JSON.parse(data) as Record<string, unknown>;
                } catch {
                    return;
                }
                if (event === 'message') {
                    const sid = payload.sessionId as string | undefined;
                    if (selectedRef.current?.sessionId === sid) {
                        setMessages((prev) => [...prev, {
                            id: (payload.messageId as number | undefined) ?? Date.now(),
                            role: payload.role as MessageRole,
                            content: payload.content as string,
                            createdAt: payload.createdAt as string,
                        }]);
                    } else if (sid) {
                        setUnread((prev) => ({ ...prev, [sid]: (prev[sid] ?? 0) + 1 }));
                    }
                } else if (event === 'session') {
                    // 新待接/结束等 → 刷新待接队列
                    fetchWaitingSessions();
                } else if (event === 'presence') {
                    agentService.getOnlineAgents().then((r) => {
                        if (r.code === 200) setOnlineCount(r.data.length);
                    }).catch(() => {});
                }
                // typing 事件：MVP 从简未接入展示（后端能力已就绪）
            },
        });
        // 首次拉一次在线坐席数
        agentService.getOnlineAgents().then((r) => {
            if (r.code === 200) setOnlineCount(r.data.length);
        }).catch(() => {});
        return () => conn.close();
    }, []); // 只在挂载时建一次；用 ref 读当前会话

    async function fetchWaitingSessions() {
        try {
            const response = await agentService.getWaitingAgentSessions(0, 50);
            if (response.code === 200) {
                setWaitingSessions(response.data.content);
            }
        } catch (error: any) {
            console.error('获取待接管会话失败:', error.message);
        }
    };

    const clearUnread = (sessionId: string) => {
        setUnread((prev) => ({ ...prev, [sessionId]: 0 }));
    };

    const handleAcceptSession = async (session: ChatSession) => {
        try {
            const res = await agentService.acceptSession(session.sessionId);
            if (res.code === 200) {
                setSelectedSession(res.data);
                const msgs = await chatService.getSessionMessages(session.sessionId);
                setMessages(msgs.code === 200 ? msgs.data : []);
                clearUnread(session.sessionId);
                fetchWaitingSessions();
            }
        } catch {
            toast({ title: '接管失败', variant: 'destructive' });
        }
    };

    const handleSendReply = async () => {
        if (!replyInput.trim() || !selectedSession) return;

        const content = replyInput;
        setReplyInput('');
        setIsSending(true);
        try {
            await agentService.agentReply({
                sessionId: selectedSession.sessionId,
                message: content,
            });

            // 本地乐观追加（坐席不订阅会话消息流，用临时 id 即可）
            setMessages(prev => [...prev, {
                id: Date.now(),
                role: 'ASSISTANT',
                content,
                createdAt: new Date().toISOString(),
            }]);
            toast({ title: '消息已发送' });
        } catch (error: any) {
            setReplyInput(content); // 发送失败回滚输入
            toast({ variant: 'destructive', title: '发送失败', description: error.message });
        } finally {
            setIsSending(false);
        }
    };

    const handleEndSession = async () => {
        if (!selectedSession) return;
        try {
            await chatService.endSession(selectedSession.sessionId);
            toast({ title: '会话已结束' });
            setSelectedSession(null);
            setMessages([]);
        } catch (error: any) {
            toast({ variant: 'destructive', title: '操作失败', description: error.message });
        }
    };

    const handleReturnToAI = async () => {
        if (!selectedSession) return;
        try {
            const res = await agentService.returnToAI(selectedSession.sessionId);
            if (res.code === 200) {
                toast({ title: '已交还 AI' });
                setSelectedSession(null);
                setMessages([]);
                fetchWaitingSessions();
            }
        } catch (error: any) {
            toast({ variant: 'destructive', title: '操作失败', description: error.message });
        }
    };

    return (
        <div className="flex h-full w-full overflow-hidden">
            {/* 左侧：待接管队列 */}
            <div className="w-80 border-r flex flex-col bg-muted/10">
                <div className="p-4 border-b flex items-center justify-between">
                    <h3 className="font-semibold flex items-center gap-2">
                        <AlertCircle className="w-4 h-4 text-red-500" />
                        待接管队列
                        <Badge variant="destructive" className="ml-1">{waitingSessions.length}</Badge>
                    </h3>
                    <div className="flex items-center gap-2">
                        <span className="text-xs text-muted-foreground">在线坐席 {onlineCount}</span>
                        <Button variant="ghost" size="icon" onClick={fetchWaitingSessions}>
                            <RefreshCw className="w-4 h-4" />
                        </Button>
                    </div>
                </div>
                <ScrollArea className="flex-1">
                    <div className="p-3 space-y-2">
                        {waitingSessions.length === 0 ? (
                            <div className="text-center py-12 text-muted-foreground text-sm">
                                暂无等待接管的会话
                            </div>
                        ) : (
                            waitingSessions.map((session) => (
                                <div
                                    key={session.id}
                                    className="p-3 rounded-lg border bg-background hover:border-primary/50 cursor-pointer transition-colors"
                                    onClick={() => handleAcceptSession(session)}
                                >
                                    <div className="flex items-center justify-between">
                                        <div className="flex items-center gap-2">
                                            <User className="w-4 h-4 text-muted-foreground" />
                                            <span className="text-sm font-medium">{session.userId}</span>
                                        </div>
                                        <div className="flex items-center gap-1">
                                            {unread[session.sessionId] > 0 && (
                                                <Badge variant="destructive" className="text-[10px]">
                                                    {unread[session.sessionId]}
                                                </Badge>
                                            )}
                                            <Badge variant="destructive" className="text-[10px] animate-pulse">等待中</Badge>
                                        </div>
                                    </div>
                                    <p className="text-[10px] text-muted-foreground mt-1">
                                        {new Date(session.createdAt).toLocaleString()}
                                    </p>
                                </div>
                            ))
                        )}
                    </div>
                </ScrollArea>
            </div>

            {/* 右侧：对话区域 */}
            <div className="flex-1 flex flex-col">
                {selectedSession ? (
                    <>
                        {/* 顶部信息栏 */}
                        <div className="p-4 border-b flex items-center justify-between bg-background">
                            <div className="flex items-center gap-3">
                                <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                                    <User className="w-5 h-5 text-primary" />
                                </div>
                                <div>
                                    <p className="font-semibold">{selectedSession.userId}</p>
                                    <p className="text-xs text-muted-foreground">会话ID: {selectedSession.sessionId.slice(0, 8)}...</p>
                                </div>
                            </div>
                            <div className="flex items-center gap-2">
                                <Badge className="bg-blue-500">人工处理中</Badge>
                                <Button variant="outline" size="sm" onClick={handleReturnToAI}>回归 AI</Button>
                                <Button variant="outline" size="sm" onClick={handleEndSession}>结束会话</Button>
                            </div>
                        </div>

                        {/* 消息列表 */}
                        <ScrollArea className="flex-1 p-4">
                            <div className="max-w-2xl mx-auto space-y-4">
                                {messages.map((msg) => (
                                    <div
                                        key={msg.id}
                                        className={cn(
                                            "flex gap-3",
                                            msg.role === 'USER' ? "flex-row" : "flex-row-reverse"
                                        )}
                                    >
                                        <div className={cn(
                                            "w-8 h-8 rounded-full flex items-center justify-center shrink-0",
                                            msg.role === 'USER' ? "bg-muted" : "bg-blue-500 text-white"
                                        )}>
                                            {msg.role === 'USER' ? <User className="w-4 h-4" /> : <UserPen className="w-4 h-4" />}
                                        </div>
                                        <div className={cn(
                                            "px-4 py-2 rounded-2xl text-sm max-w-[70%]",
                                            msg.role === 'USER'
                                                ? "bg-muted rounded-tl-none"
                                                : "bg-blue-500 text-white rounded-tr-none"
                                        )}>
                                            {msg.content}
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </ScrollArea>

                        {/* 输入区域 */}
                        <div className="p-4 border-t bg-muted/20">
                            <div className="max-w-2xl mx-auto flex gap-3">
                                <Input
                                    placeholder="输入回复内容..."
                                    value={replyInput}
                                    onChange={(e) => setReplyInput(e.target.value)}
                                    onKeyDown={(e) => e.key === 'Enter' && handleSendReply()}
                                    disabled={isSending}
                                    className="flex-1"
                                />
                                <Button onClick={handleSendReply} disabled={isSending || !replyInput.trim()}>
                                    {isSending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
                                </Button>
                            </div>
                        </div>
                    </>
                ) : (
                    /* 空状态 */
                    <div className="flex-1 flex flex-col items-center justify-center text-center opacity-40">
                        <UserPen className="w-16 h-16 mb-4" />
                        <h3 className="text-lg font-medium">选择一个会话开始处理</h3>
                        <p className="max-w-xs mt-2 text-sm">点击左侧待接管队列中的会话，即可接管并开始回复。</p>
                    </div>
                )}
            </div>
        </div>
    );
};

export default AgentPage;

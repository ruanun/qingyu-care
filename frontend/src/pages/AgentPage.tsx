import React, { useState, useEffect } from 'react';
import { UserPen, AlertCircle, Send, User, RefreshCw, Loader2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import { agentService } from '@/api/agent';
import { chatService } from '@/api/chat';
import type { ChatSession, ChatMessage } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const MOCK_AGENT_ID = 1; // 模拟当前客服 ID

const AgentPage: React.FC = () => {
    const [waitingSessions, setWaitingSessions] = useState<ChatSession[]>([]);
    const [selectedSession, setSelectedSession] = useState<ChatSession | null>(null);
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [replyInput, setReplyInput] = useState('');
    const [, setIsLoading] = useState(false);
    const [isSending, setIsSending] = useState(false);
    const { toast } = useToast();

    useEffect(() => {
        fetchWaitingSessions();
        const interval = setInterval(fetchWaitingSessions, 10000); // 每10秒刷新
        return () => clearInterval(interval);
    }, []);

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

    const handleAcceptSession = async (session: ChatSession) => {
        setIsLoading(true);
        try {
            const response = await agentService.acceptSession(session.sessionId, MOCK_AGENT_ID);
            if (response.code === 200) {
                setSelectedSession(response.data);
                toast({ title: '成功接管会话' });
                fetchWaitingSessions();
                // 加载会话消息（这里暂时使用模拟数据，实际需要添加获取消息 API）
                setMessages([
                    { id: 1, role: 'USER', content: '我需要人工帮助', createdAt: new Date().toISOString() },
                ]);
            }
        } catch (error: any) {
            toast({ variant: 'destructive', title: '接管失败', description: error.message });
        } finally {
            setIsLoading(false);
        }
    };

    const handleSendReply = async () => {
        if (!replyInput.trim() || !selectedSession) return;

        setIsSending(true);
        try {
            await agentService.agentReply({
                sessionId: selectedSession.sessionId,
                message: replyInput,
                agentId: MOCK_AGENT_ID,
            });

            // 添加到本地消息列表
            setMessages(prev => [...prev, {
                id: Date.now(),
                role: 'ASSISTANT',
                content: replyInput,
                createdAt: new Date().toISOString(),
            }]);
            setReplyInput('');
            toast({ title: '消息已发送' });
        } catch (error: any) {
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
                    <Button variant="ghost" size="icon" onClick={fetchWaitingSessions}>
                        <RefreshCw className="w-4 h-4" />
                    </Button>
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
                                        <Badge variant="destructive" className="text-[10px] animate-pulse">等待中</Badge>
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

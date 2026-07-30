import React, { useState, useEffect, useRef } from 'react';
import { Send, User, Bot, Loader2, Info, Database, UserPen, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Card } from '@/components/ui/card';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { chatService } from '@/api/chat';
import type { ChatMessage, ChatMessageEntity, KnowledgeHit, UiPart } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import { JsonRender } from '@/components/chat/JsonRender';
import { CardErrorBoundary } from '@/components/chat/CardErrorBoundary';
import { loadChatSession, saveChatSession, clearChatSession } from '@/lib/chatSession';

/** 解析历史消息的 uiSpec JSON 字符串为 UiPart[]；空/损坏时返回 undefined（fail-soft，退纯文本渲染）。 */
function parseUiSpec(uiSpec?: string | null): UiPart[] | undefined {
    if (!uiSpec) return undefined;
    try {
        return JSON.parse(uiSpec) as UiPart[];
    } catch {
        return undefined;
    }
}

const ChatPage: React.FC = () => {
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [input, setInput] = useState('');
    const [isLoading, setIsLoading] = useState(false);
    const [sessionId, setSessionId] = useState<string | undefined>(undefined);
    const [isWaitingAgent, setIsWaitingAgent] = useState(false);
    const [suggestHandover, setSuggestHandover] = useState(false);
    const [knowledgeHitsMap, setKnowledgeHitsMap] = useState<Record<number, KnowledgeHit[]>>({});
    const scrollRef = useRef<HTMLDivElement>(null);
    const { toast } = useToast();

    const userId = 'user_001'; // Mock user ID

    useEffect(() => {
        // Scroll to bottom on new messages
        if (scrollRef.current) {
            scrollRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    }, [messages]);

    // 刷新恢复：从 localStorage 取上次持久化的 sessionId，加载历史消息（含结构化卡片）。
    // 任何失败（403/会话过期/网络异常）都静默降级为空态，不阻断使用。
    useEffect(() => {
        const sid = loadChatSession(userId);
        if (!sid) return;

        (async () => {
            setSessionId(sid);
            try {
                const res = await chatService.getSessionMessages(sid);
                if (res.code === 200) {
                    const history: ChatMessage[] = (res.data || []).map((m: ChatMessageEntity) => ({
                        id: m.id,
                        role: m.role,
                        content: m.content,
                        createdAt: m.createdAt,
                        uiParts: parseUiSpec(m.uiSpec),
                    }));
                    // 仅当用户尚未开始对话(messages 仍为空)时才采纳历史快照，
                    // 避免历史 GET 晚于用户已发的新消息返回、整体覆盖掉刚发的乐观消息(竞态)。
                    setMessages((prev) => (prev.length === 0 ? history : prev));
                } else {
                    clearChatSession(userId);
                }
            } catch {
                clearChatSession(userId);
            }
        })();
    }, []);

    const send = async (text: string) => {
        if (!text.trim() || isLoading) return;

        const userMessage: ChatMessage = {
            id: Date.now(),
            role: 'USER',
            content: text,
            createdAt: new Date().toISOString(),
        };

        setMessages((prev) => [...prev, userMessage]);
        setInput('');
        setIsLoading(true);

        try {
            const response = await chatService.sendMessage({
                userId,
                sessionId,
                message: text,
            });

            if (response.code === 200) {
                const aiResponse: ChatMessage = {
                    id: Date.now() + 1,
                    role: 'ASSISTANT',
                    content: response.data.content,
                    createdAt: new Date().toISOString(),
                    uiParts: response.data.uiParts,
                };
                setMessages((prev) => [...prev, aiResponse]);
                setSessionId(response.data.sessionId);
                saveChatSession(userId, response.data.sessionId);
                setSuggestHandover(response.data.suggestHandover || false);

                // 存储知识命中信息
                if (response.data.knowledgeHits && response.data.knowledgeHits.length > 0) {
                    setKnowledgeHitsMap(prev => ({
                        ...prev,
                        [aiResponse.id]: response.data.knowledgeHits
                    }));
                }
            }
        } catch (error: any) {
            toast({
                variant: "destructive",
                title: "发送失败",
                description: error.message,
            });
        } finally {
            setIsLoading(false);
        }
    };

    const handleSend = () => send(input);

    // 卡片按钮回填：把按钮 value/label 作为用户消息重新发送
    const handleAction = (value: string) => send(value);

    const handleRequestHandover = async () => {
        if (!sessionId) return;
        try {
            await chatService.requestHandover(sessionId);
            setIsWaitingAgent(true);
            setSuggestHandover(false);
            toast({ title: '已请求人工客服', description: '请稍等，人工客服将尽快接入...' });
        } catch (error: any) {
            toast({ variant: 'destructive', title: '请求失败', description: error.message });
        }
    };

    return (
        <div className="flex h-full w-full overflow-hidden">
            {/* Chat Area */}
            <div className="flex-1 flex flex-col h-full border-r relative">
                <ScrollArea className="flex-1 p-6">
                    <div className="max-w-3xl mx-auto space-y-6">
                        {messages.length === 0 && (
                            <div className="h-64 flex flex-col items-center justify-center text-center space-y-4">
                                <div className="w-16 h-16 rounded-2xl bg-primary/10 flex items-center justify-center text-primary">
                                    <Bot className="w-8 h-8" />
                                </div>
                                <div>
                                    <h3 className="text-xl font-semibold">你好！我是轻语智能助手</h3>
                                    <p className="text-muted-foreground mt-1">我可以回答关于公司政策、算法导论等相关问题。</p>
                                </div>
                                <div className="flex gap-2">
                                    <Badge variant="secondary" className="cursor-pointer hover:bg-muted" onClick={() => setInput("什么是算法？")}>什么是算法？</Badge>
                                    <Badge variant="secondary" className="cursor-pointer hover:bg-muted" onClick={() => setInput("如何重置密码？")}>如何重置密码？</Badge>
                                </div>
                            </div>
                        )}

                        {messages.map((msg) => (
                            <div
                                key={msg.id}
                                className={cn(
                                    "flex gap-4",
                                    msg.role === 'USER' ? "flex-row-reverse" : "flex-row"
                                )}
                            >
                                <Avatar className="w-9 h-9 border shadow-sm shrink-0">
                                    {msg.role === 'USER' ? (
                                        <>
                                            <AvatarImage src="" />
                                            <AvatarFallback className="bg-primary/5 text-primary"><User className="w-5 h-5" /></AvatarFallback>
                                        </>
                                    ) : (
                                        <>
                                            <AvatarImage src="" />
                                            <AvatarFallback className="bg-primary text-primary-foreground"><Bot className="w-5 h-5" /></AvatarFallback>
                                        </>
                                    )}
                                </Avatar>

                                <div className={cn(
                                    "flex flex-col gap-1 max-w-[80%]",
                                    msg.role === 'USER' ? "items-end" : "items-start"
                                )}>
                                    <div className={cn(
                                        "px-4 py-2.5 rounded-2xl text-sm leading-relaxed",
                                        msg.role === 'USER'
                                            ? "bg-primary text-primary-foreground rounded-tr-none shadow-md"
                                            : "bg-muted rounded-tl-none border shadow-sm"
                                    )}>
                                        {msg.role === 'ASSISTANT' && msg.uiParts?.length
                                            ? (
                                                <CardErrorBoundary fallback={msg.content}>
                                                    <JsonRender parts={msg.uiParts} onAction={handleAction} />
                                                </CardErrorBoundary>
                                            )
                                            : msg.content}
                                    </div>
                                    <span className="text-[10px] text-muted-foreground px-1">
                                        {new Date(msg.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                                    </span>
                                    {/* 知识命中展示 */}
                                    {msg.role === 'ASSISTANT' && knowledgeHitsMap[msg.id] && knowledgeHitsMap[msg.id].length > 0 && (
                                        <div className="mt-2 p-2 bg-blue-50 border border-blue-100 rounded-lg max-w-md">
                                            <div className="flex items-center gap-1 text-[10px] text-blue-600 font-medium mb-1">
                                                <Database className="w-3 h-3" />
                                                知识库命中
                                            </div>
                                            {knowledgeHitsMap[msg.id].slice(0, 2).map((hit, idx) => (
                                                <div key={idx} className="text-[11px] text-muted-foreground mt-1 pl-4 border-l-2 border-blue-200">
                                                    <span className="line-clamp-2">{hit.content}</span>
                                                    <div className="flex items-center gap-2 mt-0.5 text-[10px]">
                                                        <Badge variant="outline" className="px-1 py-0 text-[9px]">
                                                            相似度: {(hit.score * 100).toFixed(0)}%
                                                        </Badge>
                                                        <span className="text-muted-foreground/60">{hit.source}</span>
                                                    </div>
                                                </div>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            </div>
                        ))}
                        <div ref={scrollRef} />
                    </div>
                </ScrollArea>

                {/* Input Area */}
                <div className="p-4 border-t bg-background/50 backdrop-blur-sm shrink-0">
                    {isWaitingAgent ? (
                        /* Waiting for Agent State */
                        <div className="max-w-3xl mx-auto text-center py-4">
                            <div className="flex items-center justify-center gap-3 text-blue-600">
                                <UserPen className="w-5 h-5" />
                                <span className="font-medium">正在等待人工客服接入...</span>
                                <Loader2 className="w-4 h-4 animate-spin" />
                            </div>
                            <p className="text-xs text-muted-foreground mt-2">人工客服将尽快为您服务，请稍候</p>
                        </div>
                    ) : (
                        /* Normal Input State */
                        <>
                            {suggestHandover && (
                                <div className="max-w-3xl mx-auto mb-3 p-3 rounded-lg bg-amber-50 border border-amber-200 flex items-center justify-between">
                                    <div className="flex items-center gap-2 text-amber-700 text-sm">
                                        <AlertCircle className="w-4 h-4" />
                                        <span>AI 对此问题的回答可能不够准确，建议转接人工客服</span>
                                    </div>
                                    <Button size="sm" variant="outline" className="text-amber-700 border-amber-300 hover:bg-amber-100" onClick={handleRequestHandover}>
                                        <UserPen className="w-4 h-4 mr-1" /> 转人工
                                    </Button>
                                </div>
                            )}
                            <div className="max-w-3xl mx-auto flex gap-3">
                                <div className="relative flex-1">
                                    <Input
                                        placeholder="键入消息以开始对话..."
                                        value={input}
                                        onChange={(e) => setInput(e.target.value)}
                                        onKeyDown={(e) => e.key === 'Enter' && handleSend()}
                                        className="pr-12 py-6 rounded-xl shadow-inner bg-muted/50 border-none focus-visible:ring-primary/20"
                                        disabled={isLoading}
                                    />
                                    <div className="absolute right-3 top-1/2 -translate-y-1/2 flex gap-1">
                                        <Button
                                            size="icon"
                                            variant="ghost"
                                            className="h-8 w-8 rounded-full text-muted-foreground hover:text-primary transition-colors"
                                            disabled={isLoading}
                                        >
                                            <Info className="w-4 h-4" />
                                        </Button>
                                    </div>
                                </div>
                                <Button
                                    onClick={handleSend}
                                    disabled={isLoading || !input.trim()}
                                    className="h-12 w-12 rounded-xl shadow-lg transition-transform active:scale-95"
                                >
                                    {isLoading ? <Loader2 className="w-5 h-5 animate-spin" /> : <Send className="w-5 h-5" />}
                                </Button>
                                {sessionId && (
                                    <Button
                                        variant="outline"
                                        onClick={handleRequestHandover}
                                        className="h-12 px-4 rounded-xl"
                                        disabled={isLoading}
                                    >
                                        <UserPen className="w-4 h-4 mr-2" /> 转人工
                                    </Button>
                                )}
                            </div>
                            <p className="text-[10px] text-center text-muted-foreground mt-2 uppercase tracking-widest opacity-50">
                                Powered by AI & QingYu Care Team
                            </p>
                        </>
                    )}
                </div>
            </div>

            {/* Side Info Area (Optional) */}
            <div className="w-80 h-full p-6 flex flex-col gap-6 bg-muted/10">
                <div className="space-y-4">
                    <h3 className="text-sm font-semibold flex items-center gap-2">
                        <Info className="w-4 h-4 text-primary" />
                        会话信息
                    </h3>
                    <Card className="p-4 space-y-3 shadow-none border bg-background/50">
                        <div className="flex justify-between text-xs">
                            <span className="text-muted-foreground">会话ID</span>
                            <span className="font-mono text-[10px]">{sessionId || '尚未开始'}</span>
                        </div>
                        <div className="flex justify-between text-xs">
                            <span className="text-muted-foreground">用户ID</span>
                            <span className="font-medium">{userId}</span>
                        </div>
                        <div className="flex justify-between text-xs">
                            <span className="text-muted-foreground">状态</span>
                            <Badge variant="outline" className="text-[10px] h-4 py-0 green-500">
                                {sessionId ? '活跃中' : '等待中'}
                            </Badge>
                        </div>
                        <Separator />
                        <div className="space-y-2">
                            <p className="text-[10px] font-semibold text-muted-foreground uppercase">当前知识库</p>
                            <div className="flex items-center gap-2">
                                <Database className="w-3 h-3 text-primary" />
                                <span className="text-xs font-medium">默认知识库 (ID: 1)</span>
                            </div>
                        </div>
                    </Card>
                </div>

                <div className="space-y-4">
                    <h3 className="text-sm font-semibold flex items-center gap-2">
                        <Bot className="w-4 h-4 text-primary" />
                        能力概览
                    </h3>
                    <ul className="text-xs space-y-2 text-muted-foreground">
                        <li className="flex items-start gap-2">
                            <div className="w-1 h-1 rounded-full bg-primary mt-1.5 shrink-0" />
                            RAG 知识库问答检索
                        </li>
                        <li className="flex items-start gap-2">
                            <div className="w-1 h-1 rounded-full bg-primary mt-1.5 shrink-0" />
                            流式响应 (即将推出)
                        </li>
                        <li className="flex items-start gap-2">
                            <div className="w-1 h-1 rounded-full bg-primary mt-1.5 shrink-0" />
                            人工接管无缝切换
                        </li>
                    </ul>
                </div>
            </div>
        </div>
    );
};

export default ChatPage;

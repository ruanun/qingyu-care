import React, { useState, useEffect } from 'react';
import { User, Bot, Loader2, ThumbsUp, ThumbsDown } from 'lucide-react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { chatService } from '@/api/chat';
import { cn } from '@/lib/utils';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage } from '@/lib/apiResult';
import { Markdown } from '@/components/chat/Markdown';

interface Message {
    id: number;
    role: string;
    content: string;
    createdAt: string;
    feedback?: number | null;
}

interface SessionMessagesProps {
    sessionId: string;
}

const SessionMessages: React.FC<SessionMessagesProps> = ({ sessionId }) => {
    const [messages, setMessages] = useState<Message[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const { toast } = useToast();

    const handleFeedback = async (msg: Message, value: 1 | -1) => {
        try {
            await chatService.submitFeedback(sessionId, msg.id, value);
            setMessages((prev) => prev.map((m) => (m.id === msg.id ? { ...m, feedback: value } : m)));
        } catch (err) {
            toast({ variant: 'destructive', title: '评价失败', description: getErrorMessage(err) });
        }
    };

    useEffect(() => {
        const fetchMessages = async () => {
            setIsLoading(true);
            setError(null);
            try {
                const response = await chatService.getSessionMessages(sessionId);
                if (response.code === 200) {
                    // ⚠️ response.data 是 { messages, visitorReadUpToMessageId } 包装（不再是裸数组），
                    // 见 types/index.ts 的 SessionMessagesResponse 注释。
                    setMessages(response.data.messages || []);
                } else {
                    setError(response.message || '加载失败');
                }
            } catch (err: unknown) {
                setError(getErrorMessage(err) || '加载消息失败');
            } finally {
                setIsLoading(false);
            }
        };

        if (sessionId) {
            fetchMessages();
        }
    }, [sessionId]);

    if (isLoading) {
        return (
            <div className="h-full flex items-center justify-center">
                <Loader2 className="w-6 h-6 animate-spin text-primary" />
                <span className="ml-2 text-muted-foreground">加载中...</span>
            </div>
        );
    }

    if (error) {
        return (
            <div className="h-full flex items-center justify-center text-destructive">
                加载失败: {error}
            </div>
        );
    }

    if (messages.length === 0) {
        return (
            <div className="h-full flex items-center justify-center text-muted-foreground opacity-50">
                暂无消息记录
            </div>
        );
    }

    return (
        <ScrollArea className="h-full pr-4">
            <div className="space-y-4 py-2">
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
                            msg.role === 'USER'
                                ? "bg-muted"
                                : "bg-primary text-primary-foreground"
                        )}>
                            {msg.role === 'USER' ? (
                                <User className="w-4 h-4" />
                            ) : (
                                <Bot className="w-4 h-4" />
                            )}
                        </div>
                        <div className={cn(
                            "px-4 py-2 rounded-2xl text-sm max-w-[75%]",
                            msg.role === 'USER'
                                ? "bg-muted rounded-tl-none"
                                : "bg-primary text-primary-foreground rounded-tr-none"
                        )}>
                            {/* 只有 AI 回复渲染 Markdown；坐席消息同在这一侧，保持纯文本 */}
                            {msg.role === 'ASSISTANT'
                                ? <Markdown text={msg.content} />
                                : <p className="whitespace-pre-wrap">{msg.content}</p>}
                            <p className={cn(
                                "text-[10px] mt-1 opacity-60",
                                msg.role === 'USER' ? "text-left" : "text-right"
                            )}>
                                {new Date(msg.createdAt).toLocaleTimeString()}
                            </p>
                            {msg.role !== 'USER' && (
                                <div className="flex gap-1 justify-end mt-1">
                                    <button
                                        onClick={() => handleFeedback(msg, 1)}
                                        className={cn('p-0.5 rounded hover:opacity-100',
                                            msg.feedback === 1 ? 'opacity-100' : 'opacity-40')}
                                        aria-label="赞"
                                    >
                                        <ThumbsUp className="w-3 h-3" />
                                    </button>
                                    <button
                                        onClick={() => handleFeedback(msg, -1)}
                                        className={cn('p-0.5 rounded hover:opacity-100',
                                            msg.feedback === -1 ? 'opacity-100' : 'opacity-40')}
                                        aria-label="踩"
                                    >
                                        <ThumbsDown className="w-3 h-3" />
                                    </button>
                                </div>
                            )}
                        </div>
                    </div>
                ))}
            </div>
        </ScrollArea>
    );
};

export default SessionMessages;

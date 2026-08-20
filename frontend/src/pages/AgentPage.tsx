import React, { useState, useEffect, useRef } from 'react';
import { UserPen, AlertCircle, Send, User, RefreshCw, Loader2, Paperclip } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import { agentService } from '@/api/agent';
import { chatService } from '@/api/chat';
import { connectSse } from '@/lib/sse';
import type { ChatSession, ChatMessage, ChatMessageEntity, MessageRole, Attachment, AgentSession } from '@/types';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import { JsonRender } from '@/components/chat/JsonRender';
import { CardErrorBoundary } from '@/components/chat/CardErrorBoundary';
import { Attachments } from '@/components/chat/Attachments';
import { parseUiSpec } from '@/lib/uiSpec';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

const AgentPage: React.FC = () => {
    const [waitingSessions, setWaitingSessions] = useState<ChatSession[]>([]);
    // 坐席已接管、仍在进行中的会话（GET /chat/sessions/mine）。左栏「我的会话」分段的数据源，
    // 也是未读徽章的唯一事实源（session.unreadCount 由后端聚合，见 fetchMySessions 注释）。
    const [mySessions, setMySessions] = useState<AgentSession[]>([]);
    const [selectedSession, setSelectedSession] = useState<ChatSession | null>(null);
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    // 访客已读水位线，按 sessionId 归位：{ sessionId: 访客已读到的消息 id }。
    // 来源有二：切换会话时从 GET .../messages 的 visitorReadUpToMessageId 恢复（刷新后稳定），
    // 以及 SSE read 事件实时推进。⚠️ SSE read 事件必须按载荷里的 sessionId 写入，不能默认写
    // 当前选中会话——工作台多会话并存，收到时坐席可能正看着别的会话。
    const [visitorReadMap, setVisitorReadMap] = useState<Record<string, number>>({});
    const [replyInput, setReplyInput] = useState('');
    const [isSending, setIsSending] = useState(false);
    const [onlineCount, setOnlineCount] = useState(0);
    const [pendingAttachments, setPendingAttachments] = useState<Attachment[]>([]);
    const [isUploading, setIsUploading] = useState(false);
    const fileInputRef = useRef<HTMLInputElement>(null);
    const { toast } = useToast();

    const selectedRef = useRef<ChatSession | null>(null);
    useEffect(() => {
        selectedRef.current = selectedSession;
    }, [selectedSession]);

    // messagesRef 供 visibilitychange 监听器读取「当前已渲染的最新一条消息 id」——
    // 该监听器注册在挂载时的 useEffect 里，不能直接闭包住会变化的 messages state。
    const messagesRef = useRef<ChatMessage[]>([]);
    useEffect(() => {
        messagesRef.current = messages;
    }, [messages]);

    useEffect(() => {
        fetchWaitingSessions();
        fetchMySessions();
        const interval = setInterval(() => {
            fetchWaitingSessions();
            fetchMySessions();
        }, 10000); // 每10秒刷新
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
                        const messageId = payload.messageId as number | undefined;
                        setMessages((prev) => [...prev, {
                            id: messageId ?? Date.now(),
                            role: payload.role as MessageRole,
                            content: payload.content as string,
                            createdAt: payload.createdAt as string,
                            uiParts: parseUiSpec(payload.uiSpec as string | null | undefined),
                            attachments: payload.attachments as Attachment[] | undefined,
                        }]);
                        // 触发点二：收到当前会话的新消息。messageId 缺失时不上报——
                        // 那是 Date.now() 兜底的本地临时 id，不是真实水位线。
                        if (sid && messageId != null) maybeReportRead(sid, messageId);
                    } else if (sid) {
                        // 本地 +1 只是即时反馈的过渡态：真正的未读数以后端聚合值为准
                        // （AgentSessionDTO.unreadCount，按已读水位线现算）。这里立刻发起
                        // 一次 fetchMySessions() 用服务端结果覆盖，不依赖本地累加——
                        // ⚠️ 别改成「只累加、不再刷新」：本地计数在消息重复推送/漏推、
                        // 多端同时在线等场景下会漂移，且漂移不报错，只是徽章数字看着不对。
                        setMySessions((prev) => prev.map((s) => (
                            s.sessionId === sid ? { ...s, unreadCount: s.unreadCount + 1 } : s
                        )));
                        fetchMySessions();
                    }
                } else if (event === 'session') {
                    // 新待接/结束等 → 刷新待接队列
                    fetchWaitingSessions();
                } else if (event === 'read') {
                    // 访客已读回执。⚠️ 必须按载荷里的 sessionId 归位，不能默认写当前选中
                    // 会话——工作台是多会话的，坐席可能正看着别的会话。
                    const sid = payload.sessionId as string | undefined;
                    const upTo = payload.upToMessageId as number | undefined;
                    if (sid && upTo != null) {
                        setVisitorReadMap((prev) => ({ ...prev, [sid]: upTo }));
                    }
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
        // maybeReportRead 只读 ref/document，不依赖任何会随渲染变化的值，可安全放心挂载期一次建立
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []); // 只在挂载时建一次；用 ref 读当前会话

    // 触发点三：标签页从后台切回前台。只判「选中会话」挡不住「工作台开着但人去开会了」
    // 这类假已读，必须叠加 visibilitychange——切回前台那一刻才真的补报一次。
    useEffect(() => {
        function onVisibilityChange() {
            if (document.visibilityState !== 'visible') return;
            const sid = selectedRef.current?.sessionId;
            if (!sid) return;
            const list = messagesRef.current;
            const last = list[list.length - 1];
            if (last) maybeReportRead(sid, last.id);
        }
        document.addEventListener('visibilitychange', onVisibilityChange);
        return () => document.removeEventListener('visibilitychange', onVisibilityChange);
        // maybeReportRead 只读 ref/document，不依赖任何会随渲染变化的值，可安全放心挂载期一次建立
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // 已读上报：fail-soft，失败只 console.debug、不弹 toast——坐席正在服务客户，不该被
    // 「已读上报失败」打断。成功后顺带刷新一次「我的会话」列表：后端未读数此时已归零，
    // 正在看的会话不用等下一轮 10 秒轮询，徽章立刻消失（Task 8 遗留问题的收口）。
    async function reportRead(sessionId: string, upToMessageId: number) {
        try {
            const res = await agentService.markRead(sessionId, upToMessageId);
            if (res.code !== 200) throw new Error(res.message);
            fetchMySessions();
        } catch (error: unknown) {
            console.debug('上报已读失败:', sessionId, upToMessageId, getErrorMessage(error));
        }
    }

    // 上报条件是「选中了该会话」且「标签页当前可见」两者同时成立——只判选中挡不住
    // "工作台开着但人去开会了"这类假已读。selectedRef 在此处重新核实（而非信任调用方），
    // 因为切换会话的触发点里，报告发生在一次 await 之后，期间用户可能已经点了别的会话。
    function maybeReportRead(sessionId: string, upToMessageId: number) {
        if (selectedRef.current?.sessionId !== sessionId) return;
        if (document.visibilityState !== 'visible') return;
        void reportRead(sessionId, upToMessageId);
    }

    async function fetchWaitingSessions() {
        try {
            const response = await agentService.getWaitingAgentSessions(0, 50);
            throwIfFailed(response);
            setWaitingSessions(response.data.content);
        } catch (error: unknown) {
            console.error('获取待接管会话失败:', getErrorMessage(error));
        }
    };

    // 「我的会话」：坐席已接管、仍在进行中的会话，含后端聚合的未读数。
    // ⚠️ 事实源在后端——本函数每次调用都用 response.data 整体覆盖 mySessions，
    // 绝不与本地状态合并/累加。这是修复"未读徽章会漂移"的关键：SSE 到达时的本地
    // +1（见上面 onEvent 的 message 分支）只是没有网络往返延迟的即时反馈，
    // 任何一次这里的刷新都必须把它冲掉，改成合并会让计数越攒越多且没有报错。
    async function fetchMySessions() {
        try {
            const response = await agentService.getMySessions(0, 50);
            throwIfFailed(response);
            setMySessions(response.data);
        } catch (error: unknown) {
            console.error('获取我的会话列表失败:', getErrorMessage(error));
        }
    };

    const handleAcceptSession = async (session: ChatSession) => {
        try {
            const res = await agentService.acceptSession(session.sessionId);
            throwIfFailed(res);
            setSelectedSession(res.data);
            setPendingAttachments([]); // 切会话时清掉上一个会话残留的待发附件
            const msgs = await chatService.getSessionMessages(session.sessionId);
            // 历史消息是 ChatMessageDTO：uiSpec 是待解析的 JSON 字符串，attachments 已由后端组装好。
            // ⚠️ msgs.data 是 { messages, visitorReadUpToMessageId } 包装，不再是裸数组。
            const list = msgs.code === 200 ? msgs.data.messages : [];
            setMessages(list.map((m: ChatMessageEntity) => ({
                id: m.id,
                role: m.role,
                content: m.content,
                createdAt: m.createdAt,
                uiParts: parseUiSpec(m.uiSpec),
                attachments: m.attachments,
            })));
            if (msgs.code === 200) {
                // 刷新后已读状态要稳定：恢复该会话此前的访客已读水位线，而不是等下一条
                // read 事件才补上。首次尚无回执时是 null，不写入（视同尚无已读）。
                if (msgs.data.visitorReadUpToMessageId != null) {
                    const upTo = msgs.data.visitorReadUpToMessageId;
                    setVisitorReadMap((prev) => ({ ...prev, [session.sessionId]: upTo }));
                }
                // 触发点一：切换到该会话。upToMessageId 传「已渲染的最新一条」，不区分角色。
                const last = list[list.length - 1];
                if (last) maybeReportRead(session.sessionId, last.id);
            }
            fetchWaitingSessions();
            // 该会话此时从"待接队列"进入"我的会话"；同时这条刷新会带回此前已接管、
            // 仍在进行中的其它会话——不会因为上面那行 setSelectedSession 只认最新一个
            // 而把它们从界面上弄丢（这正是本次要修的缺陷：接管第二个会话曾经会让
            // 第一个会话再也回不去，刷新也回不去）。
            fetchMySessions();
        } catch (error: unknown) {
            // 必须把原因带出来：接管失败最常见的就是「已被别的坐席接管」这类
            // 后端业务错误（HTTP 200 + code≠200），无参 catch 会把 throwIfFailed
            // 抛出的那条 message 丢掉，坐席只看到「接管失败」不知道为什么
            toast({ title: '接管失败', variant: 'destructive', description: getErrorMessage(error) });
        }
    };

    // 切回「我的会话」列表里此前已接管的某个会话（不是新接管，会话早已是 AGENT_PROCESSING）。
    // 这是修复"接管第二个会话丢掉第一个"的另一半：光有列表不够，还得能点回去。
    // AgentSession 没带 tags/summary/endedAt 等字段（列表查询没选它们），这里用能拿到的字段
    // 拼一个最小可用的 ChatSession；这些缺失字段本页不会渲染，不影响展示。
    const handleSelectMySession = async (session: AgentSession) => {
        setSelectedSession({
            id: session.id,
            sessionId: session.sessionId,
            userId: session.userId,
            status: session.status,
            createdAt: session.lastMessageAt,
            updatedAt: session.lastMessageAt,
        });
        setPendingAttachments([]);
        try {
            const msgs = await chatService.getSessionMessages(session.sessionId);
            const list = msgs.code === 200 ? msgs.data.messages : [];
            setMessages(list.map((m: ChatMessageEntity) => ({
                id: m.id,
                role: m.role,
                content: m.content,
                createdAt: m.createdAt,
                uiParts: parseUiSpec(m.uiSpec),
                attachments: m.attachments,
            })));
            if (msgs.code === 200) {
                // 刷新后已读状态要稳定：恢复该会话此前的访客已读水位线。首次尚无回执时为 null，不写入。
                if (msgs.data.visitorReadUpToMessageId != null) {
                    const upTo = msgs.data.visitorReadUpToMessageId;
                    setVisitorReadMap((prev) => ({ ...prev, [session.sessionId]: upTo }));
                }
                // 触发点一：切换到该会话。upToMessageId 传「已渲染的最新一条」，不区分角色。
                const last = list[list.length - 1];
                if (last) maybeReportRead(session.sessionId, last.id);
            }
        } catch (error: unknown) {
            toast({ title: '加载会话消息失败', variant: 'destructive', description: getErrorMessage(error) });
        }
    };

    // 卡片按钮回填：坐席不是对话的发起方，点击历史卡片按钮不代表要替用户发消息，
    // 而是把内容填进回复框供坐席确认/编辑后再手动发送（与 ChatPage 的自动直发语义刻意不同）。
    const handleAction = (value: string) => {
        setReplyInput((prev) => (prev ? `${prev} ${value}` : value));
    };

    // 附件选中即上传，拿到 id 存进 pendingAttachments 供回复时一并提交；须已接管会话才可上传。
    const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        e.target.value = '';
        if (!file || !selectedSession) return;
        setIsUploading(true);
        try {
            const res = await chatService.uploadChatFile(selectedSession.sessionId, file);
            if (res.code === 200) {
                setPendingAttachments((prev) => [...prev, res.data]);
            } else {
                toast({ variant: 'destructive', title: '上传失败', description: res.message });
            }
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '上传失败', description: message });
        } finally {
            setIsUploading(false);
        }
    };

    const removePendingAttachment = (id: number) => {
        setPendingAttachments((prev) => prev.filter((a) => a.id !== id));
    };

    const handleSendReply = async () => {
        if (!replyInput.trim() || !selectedSession) return;

        const content = replyInput;
        const attachments = pendingAttachments;
        const attachmentIds = attachments.map((a) => a.id);
        setReplyInput('');
        setPendingAttachments([]);
        setIsSending(true);
        try {
            await agentService.agentReply({
                sessionId: selectedSession.sessionId,
                message: content,
                attachmentIds: attachmentIds.length ? attachmentIds : undefined,
            });

            // 本地乐观追加（坐席不订阅会话消息流，用临时 id 即可）
            setMessages(prev => [...prev, {
                id: Date.now(),
                role: 'ASSISTANT',
                content,
                createdAt: new Date().toISOString(),
                attachments: attachments.length ? attachments : undefined,
            }]);
            toast({ title: '消息已发送' });
        } catch (error: unknown) {
            setReplyInput(content); // 发送失败回滚输入
            setPendingAttachments(attachments); // 附件也回滚，避免用户重新上传
            toast({ variant: 'destructive', title: '发送失败', description: getErrorMessage(error) });
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
            setPendingAttachments([]);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '操作失败', description: getErrorMessage(error) });
        }
    };

    const handleReturnToAI = async () => {
        if (!selectedSession) return;
        try {
            const res = await agentService.returnToAI(selectedSession.sessionId);
            throwIfFailed(res);
            toast({ title: '已交还 AI' });
            setSelectedSession(null);
            setMessages([]);
            setPendingAttachments([]);
            fetchWaitingSessions();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '操作失败', description: getErrorMessage(error) });
        }
    };

    // 访客已读渲染：当前会话中 id ≤ 已读水位线的最后一条坐席消息标「已读」——只标最后一条
    // （聊天软件通行的已读语义），不是给每条已读消息都打标。selectedSession 为空或该会话
    // 尚无回执（visitorReadMap 里没有该 key）时为 null，不渲染。
    //
    // ⚠️ 判据用 role === 'ASSISTANT'，不是更符合字面意思的 'AGENT'——这不是写错，原因记录如下：
    // 已读水位线只在坐席接管期间推进（设计文档 §2.1：AI 对话是「发一句秒回一句」，不存在等
    // 对方看到的焦虑，AI 阶段不做已读）。而坐席接管后 AI 不再回复，该期间落库的 ASSISTANT
    // 消息就是坐席消息。所以「id ≤ 水位线的最后一条 ASSISTANT 消息」天然落在坐席消息上，
    // 语义是正确的。
    // 之所以不用 AGENT：后端 agentReply 落库写的是 ASSISTANT（ChatServiceImpl:1397），
    // MessageRole.AGENT 从未被写入过（全仓唯一引用在 ChatController 里标注附件上传者角色，
    // 与 ChatMessage.role 无关）。那是一个独立缺陷，修它要连带改 LLM 上下文构建
    // （ChatServiceImpl:1142 按 ASSISTANT 分支拼历史）与反馈校验（ChatFeedbackService:55
    // 只允许对 ASSISTANT 消息反馈），不在本特性范围。
    // ⚠️ 若将来 AI 与坐席在同一会话里交替回复，这个判据会失准，届时正解是修根因让 role
    // 真正区分，而不是在这里加更复杂的启发式。
    const visitorReadUpTo = selectedSession ? visitorReadMap[selectedSession.sessionId] : undefined;
    const lastReadAgentMessageId = visitorReadUpTo == null ? null : messages.reduce<number | null>(
        (acc, m) => (m.role === 'ASSISTANT' && m.id <= visitorReadUpTo ? m.id : acc),
        null,
    );

    return (
        <div className="flex h-full w-full overflow-hidden">
            {/* 左侧：两段——待接管队列（上）+ 我的会话（下）。两个集合按定义不相交
                （WAITING_AGENT vs AGENT_PROCESSING），未读徽章只属于「我的会话」。 */}
            <div className="w-80 border-r flex flex-col bg-muted/10 overflow-hidden">
                {/* 待接管队列 */}
                <div className="flex flex-col flex-1 min-h-0 border-b">
                    <div className="p-3 border-b flex items-center justify-between shrink-0">
                        <h3 className="font-semibold flex items-center gap-2 text-sm">
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
                                <div className="text-center py-8 text-muted-foreground text-sm">
                                    暂无等待接管的会话
                                </div>
                            ) : (
                                waitingSessions.map((session) => (
                                    <div
                                        key={session.id}
                                        data-testid={`waiting-session-${session.sessionId}`}
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

                {/* 我的会话：坐席已接管、仍在进行中的会话。徽章绑 session.unreadCount——
                    这是修掉"未读徽章是死代码"的关键：旧实现的 unread 状态 key 来自
                    AGENT_PROCESSING 会话的 SSE 推送，却渲染在上面 WAITING_AGENT 的队列里，
                    两个集合永不相交，徽章从未显示过。 */}
                <div className="flex flex-col flex-1 min-h-0">
                    <div className="p-3 border-b flex items-center justify-between shrink-0">
                        <h3 className="font-semibold flex items-center gap-2 text-sm">
                            <UserPen className="w-4 h-4 text-blue-500" />
                            我的会话
                            <Badge variant="secondary" className="ml-1">{mySessions.length}</Badge>
                        </h3>
                    </div>
                    <ScrollArea className="flex-1">
                        <div className="p-3 space-y-2">
                            {mySessions.length === 0 ? (
                                <div className="text-center py-8 text-muted-foreground text-sm">
                                    暂无接管中的会话
                                </div>
                            ) : (
                                mySessions.map((session) => (
                                    <div
                                        key={session.id}
                                        data-testid={`my-session-${session.sessionId}`}
                                        className={cn(
                                            "p-3 rounded-lg border cursor-pointer transition-colors",
                                            selectedSession?.sessionId === session.sessionId
                                                ? "border-primary bg-primary/5"
                                                : "bg-background hover:border-primary/50"
                                        )}
                                        onClick={() => handleSelectMySession(session)}
                                    >
                                        <div className="flex items-center justify-between">
                                            <div className="flex items-center gap-2">
                                                <User className="w-4 h-4 text-muted-foreground" />
                                                <span className="text-sm font-medium">{session.userId}</span>
                                            </div>
                                            {session.unreadCount > 0 && (
                                                <Badge variant="destructive" className="text-[10px]">
                                                    {session.unreadCount}
                                                </Badge>
                                            )}
                                        </div>
                                        <p className="text-[10px] text-muted-foreground mt-1">
                                            {new Date(session.lastMessageAt).toLocaleString()}
                                        </p>
                                    </div>
                                ))
                            )}
                        </div>
                    </ScrollArea>
                </div>
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
                                            "flex flex-col gap-1 max-w-[70%]",
                                            msg.role === 'USER' ? "items-start" : "items-end"
                                        )}>
                                            <div className={cn(
                                                "px-4 py-2 rounded-2xl text-sm",
                                                msg.role === 'USER'
                                                    ? "bg-muted rounded-tl-none"
                                                    : "bg-blue-500 text-white rounded-tr-none"
                                            )}>
                                                {msg.uiParts?.length ? (
                                                    <CardErrorBoundary fallback={msg.content}>
                                                        <JsonRender parts={msg.uiParts} onAction={handleAction} />
                                                    </CardErrorBoundary>
                                                ) : (
                                                    <span>{msg.content}</span>
                                                )}
                                            </div>
                                            <Attachments items={msg.attachments} />
                                            {msg.id === lastReadAgentMessageId && (
                                                <span
                                                    data-testid={`read-mark-${msg.id}`}
                                                    className="text-[10px] text-muted-foreground"
                                                >
                                                    已读
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </ScrollArea>

                        {/* 输入区域 */}
                        <div className="p-4 border-t bg-muted/20">
                            {pendingAttachments.length > 0 && (
                                <div className="max-w-2xl mx-auto mb-2">
                                    <Attachments
                                        items={pendingAttachments}
                                        onRemove={removePendingAttachment}
                                        compact
                                    />
                                </div>
                            )}
                            <div className="max-w-2xl mx-auto flex gap-3">
                                <input
                                    ref={fileInputRef}
                                    type="file"
                                    hidden
                                    onChange={handleFileSelect}
                                />
                                <Button
                                    variant="outline"
                                    size="icon"
                                    className="shrink-0"
                                    onClick={() => fileInputRef.current?.click()}
                                    disabled={isSending || isUploading}
                                    title="添加附件"
                                >
                                    {isUploading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Paperclip className="w-4 h-4" />}
                                </Button>
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

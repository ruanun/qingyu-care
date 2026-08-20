export interface ApiResponse<T> {
    code: number;
    message: string;
    data: T;
    timestamp: string;
}

export interface PageResponse<T> {
    content: T[];
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
}

export type SessionStatus = 'AI_PROCESSING' | 'AGENT_PROCESSING' | 'WAITING_AGENT' | 'ENDED' | 'TIMEOUT';
export type SessionTag = 'PRE_SALES' | 'AFTER_SALES' | 'COMPLAINT' | 'TECH_SUPPORT' | 'OTHER';
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM' | 'AGENT';
export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'DISABLED';

export interface ChatSession {
    id: number;
    sessionId: string;
    userId: string;
    status: SessionStatus;
    tags?: SessionTag[];
    summary?: string;
    createdAt: string;
    updatedAt: string;
    endedAt?: string;
}

// 坐席台「我接管中的会话」列表项，含未读消息数（GET /chat/sessions/mine，按最后消息时间倒序，
// offset 分页无 total）。status 恒为 AGENT_PROCESSING——后端查询已按此过滤
// （见 AgentSessionDao.findMySessions），前端不需要也不应再自行过滤。
// unreadCount 只统计角色为 USER（访客）且晚于坐席已读水位线的消息，坐席自己发的不计入。
export interface AgentSession {
    id: number;
    sessionId: string;
    userId: string;
    status: SessionStatus;
    lastMessageAt: string;
    unreadCount: number;
}

export interface ChatMessage {
    id: number;
    role: MessageRole;
    content: string;
    createdAt: string;
    uiParts?: UiPart[];
    attachments?: Attachment[];
    // id 是本地占位值（尚未拿到服务端落库后的真实 id）时为 true。⚠️ 这类消息绝不能参与
    // 已读水位线的上报或比对——占位值一旦被当成真实水位线写库，该会话的未读数会被永久
    // 污染（水位线语义只增不减）。见 AgentPage.tsx 的 lastKnownMessageIdRef 注释。
    pending?: boolean;
}

// 会话历史消息：GET /chat/session/{id}/messages 返回的 ChatMessageDTO（MM-6 起不再是裸实体，
// attachments 由后端按 messageId 批量组装好一并返回）。
// uiSpec 是结构化卡片 uiParts 的 JSON 原始字符串（未解析，可空），需前端自行 JSON.parse（见 lib/uiSpec.ts）。
export interface ChatMessageEntity {
    id: number;
    role: MessageRole;
    content: string;
    createdAt: string;
    uiSpec?: string | null;
    attachments?: Attachment[];
}

// GET /chat/session/{id}/messages 的响应包装（不再是裸 List<ChatMessageDTO>，见后端
// SessionMessagesResponse 类注释：水位线字段挂不到 List 上，故引入本包装类型）。
// visitorReadUpToMessageId 是对方（访客）的已读水位线，不带坐席自己的；访客尚未产生任何
// 已读回执时为 null。消费方是 AgentPage：切换会话时用它恢复 visitorReadMap，见该文件
// handleAcceptSession/handleSelectMySession 的调用点注释。
export interface SessionMessagesResponse {
    messages: ChatMessageEntity[];
    visitorReadUpToMessageId: number | null;
}

// 消息附件（图片/文件）。⚠️ 不含 url：下载端点是请求头鉴权（satoken/X-Api-Key），
// 浏览器原生 <img src>/<a href> 不会带自定义头，客户端须自行 fetch blob 再 URL.createObjectURL
// （见 components/chat/Attachments.tsx）。width/height 对 webp 恒为 null（JDK ImageIO 无解码器）。
export interface Attachment {
    id: number;
    fileName: string;
    contentType: string;
    fileSize: number;
    width?: number | null;
    height?: number | null;
}

// 结构化卡片：AI 回复的有序 text/ui 片段（Task 3 渲染引擎消费）
export interface UiPart {
    kind: 'text' | 'ui';
    text?: string;
    node?: UiNode;
}

export interface UiNode {
    type: string;
    props?: Record<string, unknown>;
    children?: UiNode[];
}

export interface KnowledgeBase {
    id: number;
    name: string;
    description?: string;
    enabled: boolean;
    createdAt: string;
    updatedAt: string;
}

export interface Document {
    id: number;
    name: string;
    status: DocumentStatus;
    fileSize?: number;  // 文件大小（字节）
    chunkCount?: number;
    errorMessage?: string;
    createdAt: string;
    visibility?: 'PUBLIC' | 'INTERNAL';   // 缺省视为 PUBLIC
    enabled?: boolean;                     // 缺省视为启用
    sourceUrl?: string | null;
    syncInterval?: 'MANUAL' | 'HOURLY' | 'DAILY' | 'WEEKLY' | null;
    lastSyncedAt?: string | null;
    syncFailureCount?: number | null;
}

export interface DocumentStatusDTO {
    id: number;
    name: string;
    status: DocumentStatus;
    chunkCount?: number;
    errorMessage?: string;
    progress: number;
}

/** 爬取站点配置 */
export interface CrawlSite {
    id: number;
    entryUrl: string;
    scopePrefix: string;
    maxPages: number;
    maxDepth: number;
    contentSelector: string | null;
    syncInterval: 'MANUAL' | 'HOURLY' | 'DAILY' | 'WEEKLY';
    lastCrawledAt: string | null;
    createdAt: string;
}

/** 爬取进度。刻意没有 total——BFS 边爬边发现，总数是动态的 */
export interface CrawlProgress {
    jobId: string;
    siteId: number;
    status: 'RUNNING' | 'COMPLETED' | 'FAILED';
    crawled: number;
    created: number;
    updated: number;
    failed: number;
    maxPages: number;
    errorMessage: string | null;
    startedAt: string;
    finishedAt: string | null;
}

/** 建站 / 改配置的入参 */
export interface CrawlSiteRequest {
    entryUrl: string;
    scopePrefix?: string;
    maxPages?: number;
    maxDepth?: number;
    contentSelector?: string;
    syncInterval?: string;
}

export interface KnowledgeHit {
    content: string;
    score: number;
    source: string;
}

// 多租户管理相关类型
export type UserRole = 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'TENANT_MEMBER';
export type TenantStatus = 'ACTIVE' | 'FROZEN' | 'EXPIRED';
export type TenantPlan = 'FREE' | 'BASIC' | 'PRO' | 'ENTERPRISE';

export interface Tenant {
    id: number;
    name: string;
    code: string;
    status: TenantStatus;
    plan: TenantPlan;
    contactName?: string;
    contactEmail?: string;
    contactPhone?: string;
    expireAt?: string;
    createdAt: string;
    updatedAt: string;
}

export interface Application {
    id: number;
    name: string;
    appKey: string;
    appSecret?: string;
    description?: string;
    avatar?: string;
    welcomeMessage?: string;
    themeColor: string;
    enabled: boolean;
    rateLimitPerMinute?: number | null;
    allowedOrigins?: string | null;
    webhookUrl?: string | null;
    webhookSecret?: string | null;
    webhookEvents?: string | null;
    createdAt: string;
}

export interface User {
    id: number;
    username: string;
    nickname?: string;
    avatar?: string;
    email?: string;
    phone?: string;
    role: UserRole;
    enabled: boolean;
    lastLoginTime?: string;
    createdAt: string;
}

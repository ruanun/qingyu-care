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
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM';
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

export interface ChatMessage {
    id: number;
    role: MessageRole;
    content: string;
    createdAt: string;
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
}

export interface DocumentStatusDTO {
    id: number;
    name: string;
    status: DocumentStatus;
    chunkCount?: number;
    errorMessage?: string;
    progress: number;
}

export interface ChatRequest {
    userId: string;
    sessionId?: string;
    message: string;
}

export interface KnowledgeHit {
    content: string;
    score: number;
    source: string;
}

export interface ChatResponse {
    sessionId: string;
    content: string;
    confidenceScore: number;
    knowledgeHits: KnowledgeHit[];
    suggestHandover: boolean;
    tokenUsage?: number;
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

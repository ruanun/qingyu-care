import api from './client';
import type { ChatSession, AgentSession, ApiResponse, PageResponse } from '../types';

export interface AgentReplyRequest {
    sessionId: string;
    message: string;
    // 本条回复携带的附件 id（先经 uploadChatFile 上传拿到）。可空。
    attachmentIds?: number[];
}

export const agentService = {
    // 获取待接管会话列表
    getWaitingAgentSessions: (page = 0, size = 20): Promise<ApiResponse<PageResponse<ChatSession>>> =>
        api.get('/chat/sessions/waiting', { params: { page, size } }),

    // 接管会话（去 agentId：后端从登录态取）
    acceptSession: (sessionId: string): Promise<ApiResponse<ChatSession>> =>
        api.post(`/chat/session/${sessionId}/accept`),

    // 客服发送消息（去 agentId：后端从登录态取）
    agentReply: (request: AgentReplyRequest): Promise<ApiResponse<void>> =>
        api.post('/chat/agent/reply', request),

    // 坐席把会话交还 AI
    returnToAI: (sessionId: string): Promise<ApiResponse<void>> =>
        api.post(`/chat/session/${sessionId}/return-to-ai`),

    // 本租户在线坐席 ID 列表
    getOnlineAgents: (): Promise<ApiResponse<number[]>> =>
        api.get('/chat/agents/online'),

    // 坐席「我接管中的会话」列表，按最后消息时间倒序，含每条会话的未读消息数
    // （offset 分页，无 total 字段）。用于左栏「我的会话」分段与未读徽章——
    // 后端聚合值是事实源，见 AgentPage.tsx 里 fetchMySessions 的调用点注释。
    getMySessions: (page = 0, size = 20): Promise<ApiResponse<AgentSession[]>> =>
        api.get('/chat/sessions/mine', { params: { page, size } }),
};

import api from './client';
import type { ChatSession, ApiResponse, PageResponse } from '../types';

export interface AgentReplyRequest {
    sessionId: string;
    message: string;
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
};

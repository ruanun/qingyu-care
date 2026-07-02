import api from './client';
import type { ChatSession, ApiResponse, PageResponse } from '../types';

export interface AgentReplyRequest {
    sessionId: string;
    message: string;
    agentId: number;
}

export const agentService = {
    // 获取待接管会话列表
    getWaitingAgentSessions: (page = 0, size = 20): Promise<ApiResponse<PageResponse<ChatSession>>> =>
        api.get('/chat/sessions/waiting', { params: { page, size } }),

    // 接管会话
    acceptSession: (sessionId: string, agentId: number): Promise<ApiResponse<ChatSession>> =>
        api.post(`/chat/session/${sessionId}/accept`, null, { params: { agentId } }),

    // 客服发送消息
    agentReply: (request: AgentReplyRequest): Promise<ApiResponse<void>> =>
        api.post('/chat/agent/reply', request),
};

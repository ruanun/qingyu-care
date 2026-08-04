import api from './client';
import type { ChatSession, ChatMessageEntity, ApiResponse, PageResponse, SessionStatus, SessionTag } from '../types';

export const chatService = {
    // 获取会话详情
    getSession: (sessionId: string): Promise<ApiResponse<ChatSession>> =>
        api.get(`/chat/session/${sessionId}`),

    // 结束会话
    endSession: (sessionId: string): Promise<ApiResponse<void>> =>
        api.post(`/chat/session/${sessionId}/end`),

    // 请求人工接管
    requestHandover: (sessionId: string): Promise<ApiResponse<void>> =>
        api.post(`/chat/session/${sessionId}/handover`),

    // 获取所有会话列表
    getAllSessions: (status?: SessionStatus, page = 0, size = 20): Promise<ApiResponse<PageResponse<ChatSession>>> =>
        api.get('/chat/sessions', { params: { status, page, size } }),

    // 获取用户历史会话
    getUserSessions: (userId: string, page = 0, size = 20): Promise<ApiResponse<PageResponse<ChatSession>>> =>
        api.get(`/chat/sessions/user/${userId}`, { params: { page, size } }),

    // 添加会话标签
    addSessionTag: (sessionId: string, tag: SessionTag): Promise<ApiResponse<ChatSession>> =>
        api.post(`/chat/session/${sessionId}/tag`, null, { params: { tag } }),

    // 移除会话标签
    removeSessionTag: (sessionId: string, tag: SessionTag): Promise<ApiResponse<ChatSession>> =>
        api.delete(`/chat/session/${sessionId}/tag`, { params: { tag } }),

    // 更新会话摘要
    updateSessionSummary: (sessionId: string, summary: string): Promise<ApiResponse<ChatSession>> =>
        api.put(`/chat/session/${sessionId}/summary`, summary),

    // 获取会话消息历史（管理端坐席专用，requireAgent；返回原始实体，uiSpec 未解析）
    getSessionMessages: (sessionId: string): Promise<ApiResponse<ChatMessageEntity[]>> =>
        api.get(`/chat/session/${sessionId}/messages`),

    // 消息反馈（1=赞 / -1=踩，重复提交覆盖）
    submitFeedback: (sessionId: string, messageId: number, feedback: 1 | -1): Promise<ApiResponse<void>> =>
        api.post(`/chat/session/${sessionId}/message/${messageId}/feedback`, null, { params: { feedback } }),
};

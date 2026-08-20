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

    // 客服发送消息（去 agentId：后端从登录态取）。data 是落库后的真实消息 id——调用方须用它
    // 替代本地占位 id 做乐观渲染，绝不能自造 id 再拿去做已读上报，见 AgentPage.tsx 的
    // handleSendReply 与 lastKnownMessageIdRef 注释。
    agentReply: (request: AgentReplyRequest): Promise<ApiResponse<number>> =>
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

    // 坐席上报「我已经看到这条了」。upToMessageId 必须是坐席端已渲染的最新一条消息 id
    // （不区分角色），服务端不替客户端推断这个。返回生效后的水位线（单调不回退）。
    // 调用方须 fail-soft：失败只 console.debug，不弹 toast——见 AgentPage.tsx 的 reportRead。
    markRead: (sessionId: string, upToMessageId: number): Promise<ApiResponse<number>> =>
        api.post(`/chat/session/${sessionId}/read`, { upToMessageId }),
};

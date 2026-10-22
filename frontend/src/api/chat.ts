import api from './client';
import type { ChatSession, SessionMessagesResponse, Attachment, ApiResponse, PageResponse, SessionStatus, SessionTag } from '../types';

export const chatService = {
    // 上传消息附件（图片/文件），仅落库拿 id，不关联到具体消息；发消息时把返回的 id 塞进 attachmentIds
    //
    // ⚠️ 必须显式设 Content-Type: multipart/form-data——client.ts 的 axios 实例在
    // create() 时写死了默认头 application/json，axios 的 transformRequest 一看到
    // JSON 头就会先对 body 走 formDataToJSON() 再序列化；FormData 里的 File 对象没有
    // 可枚举属性，序列化结果是 {}，文件内容整个丢失（请求体变成 {"sessionId":"...","file":{}}，
    // 服务端收到的不是 multipart 请求，报 500 "Current request is not a multipart request"）。
    // 这里显式覆盖成 multipart/form-data 后，XHR adapter 检测到 body 是 FormData 会
    // setContentType(false)，改由浏览器自己补上带正确 boundary 的头——同 knowledgeService.uploadDocument
    // 的既有写法。
    uploadChatFile: (sessionId: string, file: File): Promise<ApiResponse<Attachment>> => {
        const form = new FormData();
        form.append('sessionId', sessionId);
        form.append('file', file);
        return api.post('/chat/files', form, {
            headers: { 'Content-Type': 'multipart/form-data' },
        });
    },

    // 下载附件二进制。私有桶 + 请求头鉴权，<img src>/<a href> 拿不到；
    // 这里用 axios 实例（自动带 satoken/X-App-Id）取 blob，调用方自行 URL.createObjectURL。
    downloadAttachment: (id: number): Promise<Blob> =>
        api.get(`/chat/files/${id}`, { responseType: 'blob' }),

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

    // 获取会话消息历史（管理端坐席专用，requireAgent；消息本身仍是原始实体，uiSpec 未解析）。
    // ⚠️ 响应形态：不再是裸 List<ChatMessageDTO>，改为 { messages, visitorReadUpToMessageId }
    // 包装（已读回执特性引入，见 types/index.ts 的 SessionMessagesResponse 注释）——
    // 调用方必须取 .data.messages，直接把 .data 当数组用会在运行时炸。
    getSessionMessages: (sessionId: string): Promise<ApiResponse<SessionMessagesResponse>> =>
        api.get(`/chat/session/${sessionId}/messages`),

    // 消息反馈（1=赞 / -1=踩，重复提交覆盖）
    submitFeedback: (sessionId: string, messageId: number, feedback: 1 | -1): Promise<ApiResponse<void>> =>
        api.post(`/chat/session/${sessionId}/message/${messageId}/feedback`, null, { params: { feedback } }),
};

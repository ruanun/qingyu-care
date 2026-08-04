import api from './client';
import type { KnowledgeBase, Document, ApiResponse, DocumentStatusDTO } from '../types';

export const knowledgeService = {
    // 获取知识库列表
    getKnowledgeBases: (): Promise<ApiResponse<KnowledgeBase[]>> =>
        api.get('/knowledge'),

    // 获取知识库详情
    getKnowledgeBase: (id: number): Promise<ApiResponse<KnowledgeBase>> =>
        api.get(`/knowledge/${id}`),

    // 获取知识库文档列表
    getDocuments: (kbId: number): Promise<ApiResponse<Document[]>> =>
        api.get(`/knowledge/${kbId}/documents`),

    // 切换知识库状态
    toggleKnowledgeBase: (id: number): Promise<ApiResponse<KnowledgeBase>> =>
        api.put(`/knowledge/${id}/toggle`),

    // 上传文档
    uploadDocument: (kbId: number, file: File): Promise<ApiResponse<Document>> => {
        const formData = new FormData();
        formData.append('file', file);
        return api.post(`/knowledge/${kbId}/documents`, formData, {
            headers: {
                'Content-Type': 'multipart/form-data',
            },
        });
    },

    // 获取文档详情
    getDocument: (docId: number): Promise<ApiResponse<Document>> =>
        api.get(`/knowledge/documents/${docId}`),

    // 获取文档状态
    getDocumentStatus: (docId: number): Promise<ApiResponse<DocumentStatusDTO>> =>
        api.get(`/knowledge/documents/${docId}/status`),

    // 批量获取文档状态
    getDocumentsStatusBatch: (docIds: number[]): Promise<ApiResponse<DocumentStatusDTO[]>> =>
        api.post('/knowledge/documents/status/batch', docIds),

    // 删除文档
    deleteDocument: (docId: number): Promise<ApiResponse<void>> =>
        api.delete(`/knowledge/documents/${docId}`),

    // 重新处理文档
    reprocessDocument: (docId: number): Promise<ApiResponse<void>> =>
        api.post(`/knowledge/documents/${docId}/reprocess`),

    // 更新文档可见性
    updateDocumentVisibility: (docId: number, visibility: 'PUBLIC' | 'INTERNAL'): Promise<ApiResponse<Document>> =>
        api.put(`/knowledge/documents/${docId}/visibility?visibility=${visibility}`),

    // 设置文档启用状态
    setDocumentEnabled: (docId: number, enabled: boolean): Promise<ApiResponse<Document>> =>
        api.put(`/knowledge/documents/${docId}/enabled?enabled=${enabled}`),

    // 添加网页文档（抓取 URL 正文入库）
    addWebPage: (knowledgeBaseId: number, url: string, syncInterval: string): Promise<ApiResponse<Document>> =>
        api.post(`/knowledge/${knowledgeBaseId}/web-pages`, { url, syncInterval }),

    // 手动触发网页重新抓取
    syncWebPage: (documentId: number): Promise<ApiResponse<void>> =>
        api.post(`/knowledge/documents/${documentId}/sync`),
};

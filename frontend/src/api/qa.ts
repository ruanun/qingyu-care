import api from './client';
import type { ApiResponse } from '../types';

export interface QaPair {
    id: number;
    question: string;
    answer: string;
    source: 'MANUAL' | 'GENERATED';
    status: 'DRAFT' | 'ACTIVE' | 'DISABLED';
    sourceDocumentId: number | null;
    createdAt: string;
    updatedAt: string;
}

export const qaService = {
    list: (kbId: number): Promise<ApiResponse<QaPair[]>> =>
        api.get(`/knowledge/${kbId}/qa-pairs`),
    create: (kbId: number, data: { question: string; answer: string }): Promise<ApiResponse<QaPair>> =>
        api.post(`/knowledge/${kbId}/qa-pairs`, data),
    update: (id: number, data: { question: string; answer: string }): Promise<ApiResponse<QaPair>> =>
        api.put(`/qa-pairs/${id}`, data),
    approve: (id: number): Promise<ApiResponse<QaPair>> =>
        api.post(`/qa-pairs/${id}/approve`),
    setEnabled: (id: number, enabled: boolean): Promise<ApiResponse<QaPair>> =>
        api.put(`/qa-pairs/${id}/enabled?enabled=${enabled}`),
    remove: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/qa-pairs/${id}`),
    generate: (documentId: number): Promise<ApiResponse<void>> =>
        api.post(`/qa-pairs/generate?documentId=${documentId}`),
};

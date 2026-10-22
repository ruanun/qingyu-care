import api from './client';
import type { ApiResponse } from '../types';

export type ExperimentStatus = 'DRAFT' | 'RUNNING' | 'ENDED';

export interface AbVariant {
    name: string;
    strategyId: number;
    weight: number;
}

export interface AbExperiment {
    id: number;
    name: string;
    description: string | null;
    status: ExperimentStatus;
    variants: AbVariant[];
    startedAt: string | null;
    endedAt: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface VariantReport {
    variantName: string;
    weight: number;
    sessionCount: number;
    questionCount: number;
    aiMsgCount: number;
    avgConfidence: number | null;
    feedbackUp: number;
    feedbackDown: number;
    likeRate: number | null;
    fallbackRate: number | null;
    lowConfidenceRate: number | null;
    hitRate: number | null;
    handoverRate: number | null;
    resolvedRate: number | null;
}

export interface AbTestReport {
    experimentId: number;
    name: string;
    status: ExperimentStatus;
    startedAt: string | null;
    endedAt: string | null;
    variants: VariantReport[];
}

export const abTestService = {
    list: (): Promise<ApiResponse<AbExperiment[]>> => api.get('/ab-experiments'),
    create: (data: Partial<AbExperiment>): Promise<ApiResponse<AbExperiment>> =>
        api.post('/ab-experiments', data),
    update: (id: number, data: Partial<AbExperiment>): Promise<ApiResponse<AbExperiment>> =>
        api.put(`/ab-experiments/${id}`, data),
    start: (id: number): Promise<ApiResponse<AbExperiment>> =>
        api.post(`/ab-experiments/${id}/start`),
    end: (id: number): Promise<ApiResponse<AbExperiment>> =>
        api.post(`/ab-experiments/${id}/end`),
    delete: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/ab-experiments/${id}`),
    report: (id: number): Promise<ApiResponse<AbTestReport>> =>
        api.get(`/ab-experiments/${id}/report`),
};

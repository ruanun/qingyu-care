import api from './client';
import type { ApiResponse } from '../types';

/** 向量模型摘要（不含端点与凭据）。platform = 平台共享模型 */
export interface EmbeddingModelSummary {
    id: number;
    name: string;
    provider: string;
    modelId: string;
    similarityThreshold: number | null;
    platform: boolean;
}

export type EmbeddingMigrationStatus =
    | 'RUNNING' | 'CANCELLING' | 'COMPLETED' | 'CANCELLED' | 'FAILED' | 'INTERRUPTED';

export interface EmbeddingMigrationJob {
    id: number;
    status: EmbeddingMigrationStatus;
    fromTag: string | null;
    toTag: string;
    total: number | null;
    done: number | null;
    failed: number | null;
    message: string | null;
    startedAt: string;
    finishedAt: string | null;
}

export interface StrategyThreshold {
    id: number;
    name: string;
    scenario: string;
    similarityThreshold: number | null;
    platform: boolean;
}

/** 知识库页「向量模型」卡片的全部状态 */
export interface EmbeddingState {
    /** 平台还没有任何向量模型时为 null */
    active: EmbeddingModelSummary | null;
    /** 切换进行中的目标模型 */
    pending: EmbeddingModelSummary | null;
    /** 最近一次迁移任务 */
    job: EmbeddingMigrationJob | null;
    options: EmbeddingModelSummary[];
    dimensions: number;
    /** 没有跟随向量模型阈值的问答策略，切换模型时提醒去调 */
    manualThresholdStrategies: StrategyThreshold[];
}

export type SwitchOutcome = 'SWITCHED' | 'MIGRATING' | 'UNCHANGED';

export const embeddingService = {
    getState: (): Promise<ApiResponse<EmbeddingState>> =>
        api.get('/embedding/state'),

    switchModel: (modelId: number): Promise<ApiResponse<{ outcome: SwitchOutcome; jobId: number | null }>> =>
        api.post('/embedding/switch', { modelId }),

    cancelJob: (jobId: number): Promise<ApiResponse<void>> =>
        api.post(`/embedding/jobs/${jobId}/cancel`),

    resumeJob: (jobId: number): Promise<ApiResponse<void>> =>
        api.post(`/embedding/jobs/${jobId}/resume`),
};

/** 仍占着「切换进行中」的任务状态（含失败 / 中断：pending 还在，需要继续或取消） */
export const isJobPending = (job: EmbeddingMigrationJob | null): boolean =>
    !!job && ['RUNNING', 'CANCELLING', 'FAILED', 'INTERRUPTED'].includes(job.status);

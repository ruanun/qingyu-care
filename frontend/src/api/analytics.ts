import api from './client';
import type { ApiResponse } from '../types';

export interface UsageSummary {
    totalCalls: number;
    totalTokens: number;
    totalMessages: number;
    totalSessions: number;
    aiResolutionRate: number;
    handoverRate: number;
}

export interface UsageTrendPoint {
    date: string;
    calls: number;
    tokens: number;
    sessions: number;
}

export interface UsageRankingItem {
    id: number;
    name: string;
    calls: number;
    tokens: number;
}

export interface UsageAnalytics {
    summary: UsageSummary;
    trend: UsageTrendPoint[];
    ranking: UsageRankingItem[];
    scope: 'PLATFORM' | 'TENANT';
}

// ===== Phase 4.4 分析三件套 =====

export interface QualitySummary {
    aiMsgCount: number;
    avgConfidence: number;
    lowConfidenceRate: number;
    fallbackRate: number;
    feedbackUp: number;
    feedbackDown: number;
    aiResolutionRate: number;
    handoverRate: number;
}

export interface QualityTrendPoint {
    date: string;
    aiMsgCount: number;
    avgConfidence: number;
    feedbackUp: number;
    feedbackDown: number;
}

export interface QualityAnalytics {
    summary: QualitySummary;
    trend: QualityTrendPoint[];
    scope: 'PLATFORM' | 'TENANT';
    statCutoff: string;
}

export interface KeywordItem { keyword: string; freq: number }
export interface QuestionItem { content: string; count: number }
export interface HotTopics {
    keywords: KeywordItem[];
    questions: QuestionItem[];
    scope: 'PLATFORM' | 'TENANT';
    statCutoff: string;
}

export interface CoverageSummary { answeredCount: number; hitCount: number; hitRate: number }
export interface CoverageTrendPoint { date: string; answeredCount: number; hitCount: number; hitRate: number }
export interface DocHitItem { documentId: number; name: string; hits: number }
export interface DocItem { documentId: number; name: string }
export interface CoverageAnalytics {
    summary: CoverageSummary;
    trend: CoverageTrendPoint[];
    topDocs: DocHitItem[];
    zeroHitDocs: DocItem[];
    scope: 'PLATFORM' | 'TENANT';
    statCutoff: string;
}

export interface MissedQuestion { content: string; createdAt: string; sessionId: string }
export interface MissPage {
    content: MissedQuestion[];
    totalPages: number;
    totalElements: number;
}

export const analyticsService = {
    // 获取用量统计（days ∈ 7/30/90；appId 仅租户视角有效）
    getUsage: (days: number, appId?: number): Promise<ApiResponse<UsageAnalytics>> =>
        api.get('/analytics/usage', { params: { days, ...(appId ? { appId } : {}) } }),

    // 对话质量看板
    getQuality: (days: number, appId?: number): Promise<ApiResponse<QualityAnalytics>> =>
        api.get('/analytics/quality', { params: { days, ...(appId ? { appId } : {}) } }),

    // 热点问题统计
    getHotTopics: (days: number, appId?: number): Promise<ApiResponse<HotTopics>> =>
        api.get('/analytics/hot-topics', { params: { days, ...(appId ? { appId } : {}) } }),

    // 知识覆盖率
    getCoverage: (days: number, appId?: number): Promise<ApiResponse<CoverageAnalytics>> =>
        api.get('/analytics/coverage', { params: { days, ...(appId ? { appId } : {}) } }),

    // 未命中提问明细（分页）
    getMisses: (days: number, page: number, appId?: number): Promise<ApiResponse<MissPage>> =>
        api.get('/analytics/coverage/misses', {
            params: { days, page, size: 20, ...(appId ? { appId } : {}) },
        }),
};

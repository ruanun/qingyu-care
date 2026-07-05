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

export const analyticsService = {
    // 获取用量统计（days ∈ 7/30/90；appId 仅租户视角有效）
    getUsage: (days: number, appId?: number): Promise<ApiResponse<UsageAnalytics>> =>
        api.get('/analytics/usage', { params: { days, ...(appId ? { appId } : {}) } }),
};

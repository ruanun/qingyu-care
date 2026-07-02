import api from './client';
import type { ApiResponse, ChatSession } from '../types';

export interface DashboardStats {
    totalSessions: number;
    todaySessions: number;
    activeSessions: number;
    waitingAgentSessions: number;
    totalKnowledgeBases: number;
    totalDocuments: number;
    aiResolutionRate: number;
}

export const dashboardService = {
    // 获取仪表盘统计数据
    getStats: (): Promise<ApiResponse<DashboardStats>> =>
        api.get('/dashboard/stats'),

    // 获取最近会话
    getRecentSessions: (limit = 5): Promise<ApiResponse<ChatSession[]>> =>
        api.get('/dashboard/recent-sessions', { params: { limit } }),
};

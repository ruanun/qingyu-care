import api from './client';
import type { ApiResponse } from '../types';

export interface QuotaView {
    plan: string | null;
    callsUsed: number;
    callsLimit: number;
    tokensUsed: number;
    tokensLimit: number;
    warnThreshold: number;
    resetDate: string | null;
}

export const billingService = {
    // 查看本租户本月额度（平台管理员返回 plan=null 空视图）
    getQuota: (): Promise<ApiResponse<QuotaView>> => api.get('/billing/quota'),
};

import api from './client';
import type { ApiResponse } from '../types';

export interface QuotaView {
    plan: string | null;
    callsUsed: number;
    callsLimit: number;
    tokensUsed: number;
    tokensLimit: number;
    /** 对象存储：知识库文件 + 聊天附件之和，字节。limit 为 0 表示不限 */
    storageUsedBytes: number;
    storageLimitBytes: number;
    warnThreshold: number;
    resetDate: string | null;
}

export const billingService = {
    // 查看本租户本月额度（平台管理员返回 plan=null 空视图）
    getQuota: (): Promise<ApiResponse<QuotaView>> => api.get('/billing/quota'),
};

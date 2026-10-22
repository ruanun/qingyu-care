import api from './client';
import type { ApiResponse, PageResponse } from '../types';

export interface AuditLog {
  id: number;
  tenantId: number | null;
  userId: number | null;
  username: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  detail: string | null;
  ip: string | null;
  createdAt: string;
}

export const auditService = {
  query: (params: { action?: string; username?: string; page?: number; size?: number }):
    Promise<ApiResponse<PageResponse<AuditLog>>> =>
    api.get('/audit-logs', { params }),
};

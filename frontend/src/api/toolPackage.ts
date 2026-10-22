import api from './client';
import type { ApiResponse } from '../types';

/** 能力包（对应后端 ToolPackage 实体，仅平台侧接口会返回它——含 manifest 全文） */
export interface ToolPackage {
    id: number;
    code: string;
    name: string;
    description?: string | null;
    status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
    manifest: string;
    createdAt?: string | null;
    updatedAt?: string | null;
}

/** 安装时要租户填的一个变量 */
export interface PackageVariable {
    name: string;
    label: string;
    required: boolean;
    /** true 时前端用 password 输入框，且永不回显 */
    secret: boolean;
    help?: string | null;
}

/** 租户侧看到的包详情（刻意不含 manifest 全文，见后端 PackageDetailDTO 的注释） */
export interface PackageDetail {
    code: string;
    name: string;
    description?: string | null;
    variables: PackageVariable[];
    toolNames: string[];
    mcpServerNames: string[];
    skillNames: string[];
}

export interface PackageExportResult {
    toolPackage: ToolPackage;
    /** 不阻断导出的告警，界面必须显眼展示——它们是发布前逐字核对的线索 */
    warnings: string[];
}

export interface PackageInstallResult {
    toolNames: string[];
    mcpServerNames: string[];
    skillNames: string[];
    /** 部分成功的说明（如某个 MCP 服务同步失败，需要手动同步后再勾选工具） */
    warnings: string[];
}

export interface PackageExportPayload {
    code: string;
    name: string;
    description?: string;
    toolIds?: number[];
    mcpServerIds?: number[];
    skillIds?: number[];
}

/**
 * 能力包接口。
 *
 * ⚠️ 路径不带 `/v1`——axios 实例的 baseURL 已经是 `/api/v1`（见 `api/client.ts`），
 * 写成 `/v1/platform/packages` 会拼成 `/api/v1/v1/...`（同 `api/platform.ts` 里那条注释）。
 */
export const toolPackageService = {
    // ===== 平台侧 =====
    listAll: (): Promise<ApiResponse<ToolPackage[]>> =>
        api.get('/platform/packages'),

    getById: (id: number): Promise<ApiResponse<ToolPackage>> =>
        api.get(`/platform/packages/${id}`),

    exportPackage: (body: PackageExportPayload): Promise<ApiResponse<PackageExportResult>> =>
        api.post('/platform/packages/export', body),

    update: (id: number, body: Partial<Pick<ToolPackage, 'name' | 'description' | 'manifest'>>)
        : Promise<ApiResponse<ToolPackage>> =>
        api.put(`/platform/packages/${id}`, body),

    publish: (id: number): Promise<ApiResponse<ToolPackage>> =>
        api.post(`/platform/packages/${id}/publish`),

    archive: (id: number): Promise<ApiResponse<ToolPackage>> =>
        api.post(`/platform/packages/${id}/archive`),

    remove: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/platform/packages/${id}`),

    // ===== 租户侧 =====
    listStore: (): Promise<ApiResponse<PackageDetail[]>> =>
        api.get('/packages'),

    detail: (code: string): Promise<ApiResponse<PackageDetail>> =>
        api.get(`/packages/${code}`),

    install: (code: string, variables: Record<string, string>)
        : Promise<ApiResponse<PackageInstallResult>> =>
        api.post(`/packages/${code}/install`, { variables }),
};

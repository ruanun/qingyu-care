import api from './client';
import type { ApiResponse } from '../types';
import type { AiModelConfig, ModelType } from './ai-model';
import { aiModelService } from './ai-model';
import { platformService } from './platform';

/** 供应商连接：一家厂商的一套端点 + Key。Key 永不下发，只告诉你配没配 */
export interface ModelProvider {
    id: number;
    name: string;
    /** 厂商标识（ModelProviderCatalog 的 key 或自定义） */
    providerKey: string;
    endpoint: string | null;
    hasApiKey: boolean;
    remark: string | null;
    scope: 'PLATFORM' | 'TENANT';
    tenantId: number | null;
}

export interface ProviderWithModels {
    provider: ModelProvider;
    /** 租户侧：当前应用在该连接下的模型；平台侧：该连接下的全部共享模型 */
    models: AiModelConfig[];
}

export interface RemoteModel {
    id: string;
    /** 按名称预判的类型，只决定默认分组，界面可改 */
    suggestedType: ModelType;
}

export interface NewModel {
    modelId: string;
    modelType: ModelType;
    name?: string;
}

export interface AddResult {
    modelId: string;
    ok: boolean;
    message: string | null;
    id: number | null;
}

export interface ProviderForm {
    name: string;
    providerKey: string;
    endpoint: string;
    /** 编辑时留空表示不修改 */
    apiKey: string;
    remark: string;
}

export type ProviderScope = 'TENANT' | 'PLATFORM';

/**
 * 租户侧与平台侧接口形状完全一致，只差前缀；模型本身的改删仍走各自原有的接口。
 */
export const providerApi = (scope: ProviderScope) => {
    const base = scope === 'PLATFORM' ? '/platform/model-providers' : '/model-providers';
    return {
        list: (): Promise<ApiResponse<ProviderWithModels[]>> => api.get(base),
        create: (data: ProviderForm): Promise<ApiResponse<ModelProvider>> => api.post(base, data),
        update: (id: number, data: Partial<ProviderForm>): Promise<ApiResponse<ModelProvider>> => api.put(`${base}/${id}`, data),
        remove: (id: number): Promise<ApiResponse<void>> => api.delete(`${base}/${id}`),
        /** 拉远端模型列表，兼作测试连接 */
        remoteModels: (id: number): Promise<ApiResponse<RemoteModel[]>> => api.post(`${base}/${id}/remote-models`),
        addModels: (id: number, models: NewModel[]): Promise<ApiResponse<AddResult[]>> => api.post(`${base}/${id}/models`, models),
        updateModel: (id: number, data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
            scope === 'PLATFORM' ? platformService.updateSharedModel(id, data) : aiModelService.updateModel(id, data),
        deleteModel: (id: number): Promise<ApiResponse<void>> =>
            scope === 'PLATFORM' ? platformService.deleteSharedModel(id) : aiModelService.deleteModel(id),
        testModel: (id: number): Promise<ApiResponse<string>> => aiModelService.testModel(id),
    };
};

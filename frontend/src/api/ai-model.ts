import api from './client';
import type { ApiResponse } from '../types';

export interface AiModelConfig {
    id: number;
    name: string;
    provider: string;
    modelId: string;
    endpoint: string | null;
    apiKey: string | null;
    scenario: string;
    priority: number;
    enabled: boolean;
    remark: string | null;
    scope?: 'PLATFORM' | 'TENANT';
    createdAt: string;
    updatedAt: string;
}

/**
 * 厂商预设。**唯一事实源在后端** `ModelProviderCatalog`，这里只是它的 TS 形状。
 * 别在前端另起一份清单——本仓库已经栽过两次「同一份清单前后端各写一份然后各自漂移」。
 */
export interface ProviderPreset {
    /** 程序内标识，小写，落库进 ai_model_config.provider */
    key: string;
    /** 界面显示名 */
    label: string;
    /** OpenAI 兼容 base URL，无尾斜杠 */
    defaultEndpoint: string;
    /** 界面占位示例，不是校验白名单——各家模型 id 漂移很快 */
    sampleModelId: string;
}

export const aiModelService = {
    // 厂商预设清单（静态元数据，不含凭据）
    listProviders: (): Promise<ApiResponse<ProviderPreset[]>> =>
        api.get('/ai-models/providers'),

    // 获取所有模型配置
    getAllModels: (): Promise<ApiResponse<AiModelConfig[]>> =>
        api.get('/ai-models'),

    // 获取指定模型配置
    getById: (id: number): Promise<ApiResponse<AiModelConfig>> =>
        api.get(`/ai-models/${id}`),

    // 创建模型配置
    createModel: (data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
        api.post('/ai-models', data),

    // 更新模型配置
    updateModel: (id: number, data: Partial<AiModelConfig>): Promise<ApiResponse<AiModelConfig>> =>
        api.put(`/ai-models/${id}`, data),

    // 删除模型配置
    deleteModel: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/ai-models/${id}`),

    // 测试连接
    testModel: (id: number): Promise<ApiResponse<string>> =>
        api.post(`/ai-models/${id}/test`),
};

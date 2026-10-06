import type { AiModelConfig } from '@/api/ai-model';

/**
 * 存量行 modelType 为空，一律按对话模型算（与后端 ModelType.orDefault 同一口径）。
 *
 * 放在 lib 而不是 api/ai-model：页面测试会把整个 api 模块桩掉，放在那里的纯函数会跟着变成 undefined。
 *
 * @author ruan
 */
export const isEmbeddingModel = (m: Pick<AiModelConfig, 'modelType'>): boolean => m.modelType === 'EMBEDDING';

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { AiModelConfig, ProviderPreset } from '@/api/ai-model';
import { aiModelService } from '@/api/ai-model';
import ModelConfigPage from './ModelConfigPage';

/**
 * 守「厂商预设清单真的被拉下来并用上了」这条接线。
 *
 * 它值得一条测试是因为断掉之后完全静默：providers 停在空数组，徽章退化成显示原始
 * provider 标识（zhipu 而不是「智谱 GLM」），页面照常渲染、不报错、控制台干净，
 * 只是界面看起来「没那么友好」——没有人会把它当成缺陷去查。
 *
 * 桩在 api service 模块层，返回 {code, message, data}：这一层正是「HTTP 200 +
 * body.code」这条全局约定发生的位置。
 */
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));

vi.mock('@/api/ai-model', () => ({
    aiModelService: {
        getAllModels: vi.fn(),
        listProviders: vi.fn(),
        updateModel: vi.fn(),
        createModel: vi.fn(),
        deleteModel: vi.fn(),
        testModel: vi.fn(),
    },
}));

const MODEL: AiModelConfig = {
    id: 1,
    name: '智谱主力',
    provider: 'zhipu',
    modelId: 'glm-4.7',
    endpoint: 'https://open.bigmodel.cn/api/paas/v4',
    apiKey: null,
    scenario: 'default',
    priority: 1,
    enabled: true,
    remark: null,
    createdAt: '2026-09-08T00:00:00',
    updatedAt: '2026-09-08T00:00:00',
};

const PRESETS: ProviderPreset[] = [
    { key: 'zhipu', label: '智谱 GLM', defaultEndpoint: 'https://open.bigmodel.cn/api/paas/v4', sampleModelId: 'glm-4.7', sampleEmbeddingModelId: null },
];

const ok = <T,>(data: T) => ({ code: 200, message: 'ok', data, timestamp: '2026-09-08T00:00:00' });

describe('ModelConfigPage 的厂商预设接线', () => {
    beforeEach(() => {
        vi.mocked(aiModelService.getAllModels).mockResolvedValue(ok([MODEL]));
        toastSpy.mockClear();
    });

    it('拉到预设后，列表里的厂商显示中文名', async () => {
        vi.mocked(aiModelService.listProviders).mockResolvedValue(ok(PRESETS));

        render(<ModelConfigPage />);

        expect(await screen.findByText('智谱 GLM')).toBeInTheDocument();
        expect(aiModelService.listProviders).toHaveBeenCalled();
    });

    it('预设接口失败时降级成显示原始标识，页面照常渲染且不弹错误提示', async () => {
        // 预设只是锦上添花，拉不到不该把整页变成错误态——提供商退化成纯文本框仍然可用
        vi.mocked(aiModelService.listProviders).mockRejectedValue(new Error('network down'));

        render(<ModelConfigPage />);

        expect(await screen.findByText('智谱主力')).toBeInTheDocument();
        await waitFor(() => expect(screen.getByText('zhipu')).toBeInTheDocument());
        expect(toastSpy).not.toHaveBeenCalled();
    });
});

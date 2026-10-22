import type { ReactNode } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiModelConfig, ProviderPreset } from '@/api/ai-model';
import { aiModelService } from '@/api/ai-model';
import { providerApi } from '@/api/modelProvider';
import type { ModelProvider, ProviderWithModels } from '@/api/modelProvider';
import { ProviderModelManager } from './ProviderModelManager';

/**
 * 供应商连接面板：预设接线、建连接后直接进入添加模型、拉列表勾选批量添加、拉不到列表时手动添加、业务错误不被吞。
 *
 * 桩在 api 层（返回 {code, message, data}），「HTTP 200 + body.code」约定就在这一层。
 *
 * @author ruan
 */
const { toastSpy, api } = vi.hoisted(() => ({
    toastSpy: vi.fn(),
    api: {
        list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(),
        remoteModels: vi.fn(), addModels: vi.fn(), updateModel: vi.fn(), deleteModel: vi.fn(), testModel: vi.fn(),
    },
}));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));
vi.mock('@/hooks/use-confirm', () => ({ useConfirm: () => ({ confirm: vi.fn().mockResolvedValue(true), confirmDialog: null }) }));
vi.mock('@/api/modelProvider', () => ({ providerApi: vi.fn(() => api) }));
vi.mock('@/api/ai-model', () => ({
    aiModelService: { listProviders: vi.fn(), getAllModels: vi.fn() },
}));

// Radix 组件在 jsdom 下的 portal / focus trap 与本文件无关：Dialog 按 open 门控透传、Select 换原生 select、
// Checkbox 换原生 checkbox——都保留回调路径（桩成 null 会让勾选与提交零覆盖）
vi.mock('@/components/ui/dialog', () => ({
    Dialog: ({ open, children }: { open: boolean; children: ReactNode }) => (open ? <div role="dialog">{children}</div> : null),
    DialogContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogHeader: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogFooter: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogTitle: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DialogDescription: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ui/select', () => ({
    Select: ({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children: ReactNode }) => (
        <select aria-label="选择厂商预设" value={value} onChange={(e) => onValueChange(e.target.value)}>
            <option value="">请选择</option>{children}
        </select>
    ),
    SelectTrigger: () => null,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: ReactNode }) => <>{children}</>,
    SelectItem: ({ value, children }: { value: string; children: ReactNode }) => <option value={value}>{children}</option>,
}));
vi.mock('@/components/ui/checkbox', () => ({
    Checkbox: ({ checked, disabled, onCheckedChange, ...rest }: { checked: boolean; disabled?: boolean; onCheckedChange: (v: boolean) => void }) => (
        <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onCheckedChange(e.target.checked)} {...rest} />
    ),
}));

const ok = <T,>(data: T) => ({ code: 200, message: 'ok', data, timestamp: '' });
const fail = (message: string) => ({ code: 400, message, data: null as never, timestamp: '' });

const PRESETS: ProviderPreset[] = [
    { key: 'zhipu', label: '智谱 GLM', defaultEndpoint: 'https://open.bigmodel.cn/api/paas/v4', sampleModelId: 'glm-4.7', sampleEmbeddingModelId: 'embedding-3' },
    { key: 'dashscope', label: '通义千问 百炼', defaultEndpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1', sampleModelId: 'qwen', sampleEmbeddingModelId: 'qwen3.7-text-embedding' },
];

const PROVIDER: ModelProvider = {
    id: 5, name: '智谱主力', providerKey: 'zhipu', endpoint: 'https://open.bigmodel.cn/api/paas/v4',
    hasApiKey: true, remark: null, scope: 'TENANT', tenantId: 1,
};

const model = (over: Partial<AiModelConfig>): AiModelConfig => ({
    id: 1, name: 'm', provider: 'zhipu', modelId: 'glm-4.7', endpoint: null, apiKey: null, scenario: 'default',
    priority: 1, enabled: true, remark: null, createdAt: '', updatedAt: '', ...over,
});

const withModels = (models: AiModelConfig[]): ProviderWithModels[] => [{ provider: PROVIDER, models }];

describe('ProviderModelManager', () => {
    beforeEach(() => {
        Object.values(api).forEach((f) => f.mockReset());
        toastSpy.mockClear();
        vi.mocked(aiModelService.listProviders).mockResolvedValue(ok(PRESETS));
        vi.mocked(aiModelService.getAllModels).mockReset().mockResolvedValue(ok([]));
        vi.mocked(providerApi).mockClear();
    });

    it('拉到预设后，连接的厂商显示中文名；租户侧另列平台提供的模型', async () => {
        api.list.mockResolvedValue(ok(withModels([model({ name: '主力对话' })])));
        vi.mocked(aiModelService.getAllModels).mockResolvedValue(ok([
            model({ id: 9, name: '平台默认向量', modelId: 'qwen3.7-text-embedding', modelType: 'EMBEDDING', tenantId: null, provider: 'dashscope' }),
        ]));

        render(<ProviderModelManager scope="TENANT" appName="云舟商城客服" />);

        expect(await screen.findByText('智谱 GLM')).toBeInTheDocument();
        expect(screen.getByText('主力对话')).toBeInTheDocument();
        expect(within(screen.getByTestId('shared-models')).getByText('平台默认向量')).toBeInTheDocument();
        expect(screen.getByText(/云舟商城客服/)).toBeInTheDocument();
    });

    it('平台侧走平台接口，不列「平台提供的模型」', async () => {
        api.list.mockResolvedValue(ok([]));

        render(<ProviderModelManager scope="PLATFORM" />);

        await screen.findByText(/还没有供应商连接/);
        expect(providerApi).toHaveBeenCalledWith('PLATFORM');
        expect(aiModelService.getAllModels).not.toHaveBeenCalled();
    });

    it('建连接：选预设带出名称与端点；成功后直接打开「添加模型」并拉列表', async () => {
        api.list.mockResolvedValue(ok([]));
        api.create.mockResolvedValue(ok(PROVIDER));
        api.remoteModels.mockResolvedValue(ok([{ id: 'glm-4.7', suggestedType: 'CHAT' }]));
        render(<ProviderModelManager scope="TENANT" />);

        fireEvent.click(await screen.findByRole('button', { name: /添加供应商/ }));
        fireEvent.change(screen.getByLabelText('选择厂商预设'), { target: { value: 'zhipu' } });
        fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'sk-x' } });
        fireEvent.click(screen.getByRole('button', { name: '保存并添加模型' }));

        await waitFor(() => expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
            name: '智谱 GLM', providerKey: 'zhipu', endpoint: 'https://open.bigmodel.cn/api/paas/v4', apiKey: 'sk-x',
        })));
        await waitFor(() => expect(api.remoteModels).toHaveBeenCalledWith(5));
        expect(await screen.findByText('glm-4.7')).toBeInTheDocument();
    });

    it('建连接返回业务错误：弹 destructive toast，不进入添加模型', async () => {
        api.list.mockResolvedValue(ok([]));
        api.create.mockResolvedValue(fail('模型端点无效：拒绝访问内网地址'));
        render(<ProviderModelManager scope="TENANT" />);

        fireEvent.click(await screen.findByRole('button', { name: /添加供应商/ }));
        fireEvent.change(screen.getByLabelText('选择厂商预设'), { target: { value: 'zhipu' } });
        fireEvent.click(screen.getByRole('button', { name: '保存并添加模型' }));

        await waitFor(() => expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive', description: '模型端点无效：拒绝访问内网地址',
        })));
        expect(api.remoteModels).not.toHaveBeenCalled();
    });

    it('添加模型：按预判类型勾选、可改类型，已添加的不能再勾；部分失败留在对话框逐项给原因', async () => {
        api.list.mockResolvedValue(ok(withModels([model({ modelId: 'glm-4.7' })])));
        api.remoteModels.mockResolvedValue(ok([
            { id: 'glm-4.7', suggestedType: 'CHAT' },
            { id: 'glm-4-flash', suggestedType: 'CHAT' },
            { id: 'embedding-3', suggestedType: 'EMBEDDING' },
        ]));
        api.addModels.mockResolvedValue(ok([
            { modelId: 'glm-4-flash', ok: true, message: null, id: 11 },
            { modelId: 'embedding-3', ok: false, message: '向量维度不符：该模型返回 2048 维', id: null },
        ]));
        render(<ProviderModelManager scope="TENANT" />);

        fireEvent.click(await screen.findByRole('button', { name: /添加模型/ }));
        expect(await screen.findByText('已添加')).toBeInTheDocument();          // glm-4.7 已在当前应用
        expect(screen.getByLabelText('glm-4.7')).toBeDisabled();
        fireEvent.click(screen.getByLabelText('glm-4-flash'));
        fireEvent.click(screen.getByLabelText('embedding-3'));
        expect(screen.getByLabelText('embedding-3 的类型')).toHaveValue('EMBEDDING');
        fireEvent.click(screen.getByRole('button', { name: '添加 2 个模型' }));

        await waitFor(() => expect(api.addModels).toHaveBeenCalledWith(5, [
            { modelId: 'glm-4-flash', modelType: 'CHAT' },
            { modelId: 'embedding-3', modelType: 'EMBEDDING' },
        ]));
        expect(await screen.findByTestId('add-results')).toHaveTextContent('向量维度不符');
        expect(screen.getByRole('dialog')).toBeInTheDocument();
        expect(api.list).toHaveBeenCalledTimes(2);   // 有成功项就刷新列表
    });

    it('拉不到列表：给出原因，仍可手动填模型 ID 添加', async () => {
        api.list.mockResolvedValue(ok(withModels([])));
        api.remoteModels.mockResolvedValue(fail('上游返回 HTTP 404（该厂商可能不提供模型列表接口，可手动填写模型 ID）'));
        api.addModels.mockResolvedValue(ok([{ modelId: 'my-embed', ok: true, message: null, id: 12 }]));
        render(<ProviderModelManager scope="TENANT" />);

        fireEvent.click(await screen.findByRole('button', { name: /添加模型/ }));
        expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 404');
        fireEvent.change(screen.getByLabelText('手动添加的类型'), { target: { value: 'EMBEDDING' } });
        fireEvent.change(screen.getByPlaceholderText(/手动添加/), { target: { value: 'my-embed' } });
        fireEvent.click(screen.getByRole('button', { name: /加入/ }));
        fireEvent.click(screen.getByRole('button', { name: '添加 1 个模型' }));

        await waitFor(() => expect(api.addModels).toHaveBeenCalledWith(5, [{ modelId: 'my-embed', modelType: 'EMBEDDING' }]));
        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());   // 全部成功就关闭
    });

    it('改连接：Key 留空不提交新值语义由后端判；成功后刷新', async () => {
        api.list.mockResolvedValue(ok(withModels([])));
        api.update.mockResolvedValue(ok(PROVIDER));
        render(<ProviderModelManager scope="TENANT" />);

        fireEvent.click(await screen.findByRole('button', { name: '编辑连接 智谱主力' }));
        expect(screen.getByLabelText('API Key')).toHaveValue('');   // Key 不回显
        fireEvent.change(screen.getByLabelText('名称'), { target: { value: '智谱备用' } });
        fireEvent.click(screen.getByRole('button', { name: '保存' }));

        await waitFor(() => expect(api.update).toHaveBeenCalledWith(5, expect.objectContaining({ name: '智谱备用', apiKey: '' })));
        await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    });

    it('删连接返回业务错误（下面还有模型）：弹 destructive toast', async () => {
        api.list.mockResolvedValue(ok(withModels([model({})])));
        api.remove.mockResolvedValue(fail('该连接下还有 1 个模型（可能包括其他应用的），请先删除这些模型'));
        render(<ProviderModelManager scope="TENANT" />);

        fireEvent.click(await screen.findByRole('button', { name: '删除连接 智谱主力' }));

        await waitFor(() => expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive', description: expect.stringContaining('请先删除'),
        })));
    });
});

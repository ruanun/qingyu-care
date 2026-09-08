import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ProviderPreset } from '@/api/ai-model';
import { ProviderField } from './ProviderField';

/**
 * ProviderField 的回归测试。
 *
 * 守的核心是「租户能填进预设之外的厂商」这条本次改造的目的本身：改造前租户页是个
 * 闭合下拉，只有四家可选，而后端对 provider 从不做白名单校验——也就是说界面是唯一
 * 的门槛，它一旦退化回闭合下拉，能力就没了，而不会有任何报错。
 */

// Radix Select 的 portal 与 focus trap 在 jsdom 下行为特殊，桩掉。
// ⚠️ 但不能桩成 `() => null`：本组件的 onPresetSelect 正是由 Select 的回调驱动的，
// 桩成空组件会让那条路径静默零覆盖（CLAUDE.md 点名过的坑）。
// 故桩成「把 onValueChange 暴露出来、每个 SelectItem 渲染成可点按钮」的极简 stub。
const { selectStub } = vi.hoisted(() => ({
    selectStub: {} as { onValueChange?: (value: string) => void },
}));

vi.mock('@/components/ui/select', () => ({
    Select: ({ value, onValueChange, children }: {
        value?: string;
        onValueChange?: (value: string) => void;
        children?: React.ReactNode;
    }) => {
        selectStub.onValueChange = onValueChange;
        return <div data-testid="provider-select" data-value={value ?? ''}>{children}</div>;
    },
    SelectTrigger: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
    SelectValue: ({ placeholder }: { placeholder?: string }) => <span>{placeholder}</span>,
    SelectContent: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value, children }: { value: string; children?: React.ReactNode }) => (
        <button type="button" onClick={() => selectStub.onValueChange?.(value)}>{children}</button>
    ),
}));

const PRESETS: ProviderPreset[] = [
    { key: 'openai', label: 'OpenAI', defaultEndpoint: 'https://api.openai.com/v1', sampleModelId: 'gpt-4o-mini' },
    { key: 'zhipu', label: '智谱 GLM', defaultEndpoint: 'https://open.bigmodel.cn/api/paas/v4', sampleModelId: 'glm-4.7' },
];

const renderField = (provider: string, presets = PRESETS) => {
    const onProviderChange = vi.fn();
    const onPresetSelect = vi.fn();
    render(
        <ProviderField
            provider={provider}
            presets={presets}
            onProviderChange={onProviderChange}
            onPresetSelect={onPresetSelect}
        />,
    );
    return { onProviderChange, onPresetSelect };
};

describe('ProviderField', () => {
    it('选中预设时把整条预设交给父组件，端点带在里面', () => {
        const { onPresetSelect } = renderField('openai');

        fireEvent.click(screen.getByRole('button', { name: '智谱 GLM' }));

        expect(onPresetSelect).toHaveBeenCalledTimes(1);
        // 端点必须原样带出去：各家后缀不统一（智谱是 /api/paas/v4 而非 /v1），
        // 父组件靠它自动填端点，丢了就要用户自己猜
        expect(onPresetSelect).toHaveBeenCalledWith(
            expect.objectContaining({ key: 'zhipu', defaultEndpoint: 'https://open.bigmodel.cn/api/paas/v4' }),
        );
    });

    it('手输 provider 会原样回调，预设之外的厂商填得进来', () => {
        const { onProviderChange } = renderField('openai');

        const input = screen.getByPlaceholderText(/厂商标识/);
        fireEvent.change(input, { target: { value: 'my-private-vendor' } });

        expect(onProviderChange).toHaveBeenCalledWith('my-private-vendor');
    });

    it('provider 不在预设里时文本框照常显示它，下拉不强行选中任何项', () => {
        renderField('my-private-vendor');

        expect(screen.getByPlaceholderText(/厂商标识/)).toHaveValue('my-private-vendor');
        // 下拉选中态为空 = 显示占位文案。若这里被写成「找不到就退回第一项」，
        // 用户的自定义厂商会被界面悄悄改掉
        expect(screen.getByTestId('provider-select')).toHaveAttribute('data-value', '');
    });

    it('provider 命中预设时下拉同步选中，并给出该厂商的模型 ID 示例', () => {
        renderField('zhipu');

        expect(screen.getByTestId('provider-select')).toHaveAttribute('data-value', 'zhipu');
        expect(screen.getByText(/glm-4\.7/)).toBeInTheDocument();
    });

    it('预设清单为空时仍可用，退化成纯文本框', () => {
        const { onProviderChange } = renderField('anything', []);

        const input = screen.getByPlaceholderText(/厂商标识/);
        expect(input).toHaveValue('anything');
        fireEvent.change(input, { target: { value: 'zzz' } });
        expect(onProviderChange).toHaveBeenCalledWith('zzz');
    });
});

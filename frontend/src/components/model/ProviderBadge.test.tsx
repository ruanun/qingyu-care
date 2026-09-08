import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ProviderPreset } from '@/api/ai-model';
import { ProviderBadge } from './ProviderBadge';

/**
 * 徽章此前在 ModelConfigPage 与 SharedModelPage 里各硬编码了一份四分支映射。
 * 抽成组件之后这里守两件事：认识的厂商显示中文名、不认识的原样显示。
 * 后者是本次改造放开自定义厂商之后的新常态，退化成显示空白就等于列表里那一列没了。
 */
const PRESETS: ProviderPreset[] = [
    { key: 'zhipu', label: '智谱 GLM', defaultEndpoint: 'https://open.bigmodel.cn/api/paas/v4', sampleModelId: 'glm-4.7' },
];

describe('ProviderBadge', () => {
    it('预设里的厂商显示中文名而非原始标识', () => {
        render(<ProviderBadge provider="zhipu" presets={PRESETS} />);

        expect(screen.getByText('智谱 GLM')).toBeInTheDocument();
        expect(screen.queryByText('zhipu')).not.toBeInTheDocument();
    });

    it('大小写不影响匹配', () => {
        render(<ProviderBadge provider="ZhiPu" presets={PRESETS} />);

        expect(screen.getByText('智谱 GLM')).toBeInTheDocument();
    });

    it('预设之外的自定义厂商原样显示', () => {
        render(<ProviderBadge provider="my-private-vendor" presets={PRESETS} />);

        expect(screen.getByText('my-private-vendor')).toBeInTheDocument();
    });

    it('预设清单还没拉回来时也原样显示，不显示空白', () => {
        render(<ProviderBadge provider="zhipu" presets={[]} />);

        expect(screen.getByText('zhipu')).toBeInTheDocument();
    });
});

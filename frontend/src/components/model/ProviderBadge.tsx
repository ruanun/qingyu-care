import React from 'react';
import { Badge } from '@/components/ui/badge';
import type { ProviderPreset } from '@/api/ai-model';

/**
 * 厂商徽章。显示名从后端预设清单查，查不到就原样显示 provider 字符串。
 *
 * 抽出来是因为这段逻辑此前在 ModelConfigPage 与 SharedModelPage 里各写了一遍，
 * 四个厂商各硬编码两次；厂商扩到 12 家之后再抄一遍必然漂移。
 *
 * ⚠️ 配色表刻意只覆盖最初那四家，为的是不改变既有界面观感。新增厂商一律用中性样式，
 * 缺色只是不好看、不会出错——所以这张表不必跟着 ModelProviderCatalog 同步扩，
 * 它不是第二份「厂商清单」。
 */
const LEGACY_COLORS: Record<string, string> = {
    openai: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    dashscope: 'bg-blue-100 text-blue-700 border-blue-200',
    moonshot: 'bg-orange-100 text-orange-700 border-orange-200',
    deepseek: 'bg-indigo-100 text-indigo-700 border-indigo-200',
};

interface Props {
    provider: string;
    presets: ProviderPreset[];
}

export const ProviderBadge: React.FC<Props> = ({ provider, presets }) => {
    const key = (provider || '').toLowerCase();
    const preset = presets.find(p => p.key === key);

    if (!preset) {
        // 未登记的自定义厂商：原样显示，与改造前的 default 分支行为一致
        return <Badge variant="outline">{provider}</Badge>;
    }

    const color = LEGACY_COLORS[key] ?? 'bg-slate-100 text-slate-700 border-slate-200';
    return <Badge variant="secondary" className={color}>{preset.label}</Badge>;
};

export default ProviderBadge;

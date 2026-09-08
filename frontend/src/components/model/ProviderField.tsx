import React from 'react';
import { Input } from '@/components/ui/input';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import type { ProviderPreset } from '@/api/ai-model';

interface Props {
    /** 当前 provider 值，就是要落库的那个字符串 */
    provider: string;
    /** 后端 GET /ai-models/providers 拉来的预设清单 */
    presets: ProviderPreset[];
    /** 用户手输 provider 时回调 */
    onProviderChange: (provider: string) => void;
    /** 用户选中某个预设时回调，父组件据此同时填 provider 与端点 */
    onPresetSelect: (preset: ProviderPreset) => void;
}

/**
 * 提供商输入。下拉是「快填」，下面那个文本框才是事实源。
 *
 * <p><b>为什么不做「下拉里加一个『自定义』项、选中后切成文本框」</b>：那个交互要靠一个
 * 组件级的模式 state，而「组件级 state + 每次打开对话框时复位」正是本仓库 CLAUDE.md
 * 点名过的坑——漏掉复位的症状是「上次选了自定义，这次打开别的模型还停在自定义」，
 * 而针对默认值的断言在这种场景下极易退化成永真、测不出来。两个控件常驻则完全没有
 * 模式 state：下拉的选中态纯粹从 provider 值推导，选不中就显示占位文案。
 *
 * <p>后端对 provider <b>从不做白名单校验</b>，预设清单只影响默认端点与界面展示，
 * 所以不在清单里的厂商直接往文本框里打即可。
 */
export const ProviderField: React.FC<Props> = ({
    provider,
    presets,
    onProviderChange,
    onPresetSelect,
}) => {
    const key = (provider || '').toLowerCase();
    const matched = presets.find(p => p.key === key);

    const handlePick = (value: string) => {
        const preset = presets.find(p => p.key === value);
        if (preset) onPresetSelect(preset);
    };

    return (
        <div className="grid grid-cols-4 items-start gap-4">
            <label htmlFor="provider" className="pt-2 text-right text-sm font-medium">
                提供商
            </label>
            <div className="col-span-3 space-y-2">
                <Select value={matched ? matched.key : ''} onValueChange={handlePick}>
                    <SelectTrigger aria-label="选择厂商预设">
                        <SelectValue placeholder="从预设厂商快速填入（可选）" />
                    </SelectTrigger>
                    <SelectContent>
                        {presets.map(p => (
                            <SelectItem key={p.key} value={p.key}>
                                {p.label}
                            </SelectItem>
                        ))}
                    </SelectContent>
                </Select>
                <Input
                    id="provider"
                    value={provider ?? ''}
                    onChange={e => onProviderChange(e.target.value)}
                    placeholder="厂商标识，如 zhipu；不在预设里可自行填写"
                />
                <p className="text-xs text-muted-foreground">
                    {matched
                        ? `选中预设会自动填入官方端点。模型 ID 示例：${matched.sampleModelId}`
                        : '选一个预设会自动填入它的官方端点；预设之外的厂商直接填标识并自行填写端点。'}
                </p>
            </div>
        </div>
    );
};

export default ProviderField;

// frontend/src/lib/uiSpec.ts
// 历史消息 uiSpec（JSON 字符串）→ UiPart[] 的共享解析。ChatPage（历史加载）与
// AgentPage（历史加载 + 坐席 SSE message 事件）都要把它转成 JsonRender 能吃的结构，
// 抽到这里避免两处各写一份、静默漂移。
//
// @author ruan

import type { UiPart } from '@/types';

/** 解析历史消息的 uiSpec JSON 字符串为 UiPart[]；空/损坏时返回 undefined（fail-soft，退纯文本渲染）。 */
export function parseUiSpec(uiSpec?: string | null): UiPart[] | undefined {
    if (!uiSpec) return undefined;
    try {
        return JSON.parse(uiSpec) as UiPart[];
    } catch {
        return undefined;
    }
}

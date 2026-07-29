import React from 'react';
import type { UiPart } from '@/types';
import { renderNode } from './registry';

/** 渲染一条 AI 回复的有序 uiParts：text 片段纯文本、ui 片段过组件注册表（registry.renderNode）。 */
export function JsonRender({ parts, onAction }: { parts: UiPart[]; onAction: (value: string) => void }) {
  return (
    <div className="space-y-1">
      {parts.map((part, i) =>
        part.kind === 'text'
          ? (part.text ? <div key={i} className="whitespace-pre-wrap">{part.text}</div> : null)
          : (part.node ? <React.Fragment key={i}>{renderNode(part.node, onAction, i)}</React.Fragment> : null),
      )}
    </div>
  );
}

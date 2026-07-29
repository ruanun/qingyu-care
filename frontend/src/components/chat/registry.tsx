import React from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import type { UiNode } from '@/types';

/** 仅允 http/https 且长度受限，其余返回空（防 javascript:/data: 注入）。 */
export function safeUrl(u: unknown): string {
  if (typeof u !== 'string' || u.length > 2048) return '';
  try {
    const p = new URL(u);
    return (p.protocol === 'http:' || p.protocol === 'https:') ? u : '';
  } catch {
    return '';
  }
}

function str(v: unknown): string {
  return v == null ? '' : String(v);
}

/**
 * type → 组件 注册表：递归渲染单个 UiNode。
 * 未知 type 一律返回 null（静默忽略，不抛错、不阻断其余卡片渲染）。
 * 所有文本均以 React 文本节点渲染，不使用 dangerouslySetInnerHTML；image/link 类 URL 经 safeUrl 白名单收窄。
 */
export function renderNode(node: UiNode, onAction: (value: string) => void, key: React.Key): React.ReactNode {
  const p = node.props ?? {};
  const kids = (node.children ?? []).map((c, i) => renderNode(c, onAction, i));
  switch (node.type) {
    case 'card':
      return (
        <Card key={key} className="my-2">
          {p.title ? <CardHeader><CardTitle>{str(p.title)}</CardTitle></CardHeader> : null}
          <CardContent className="space-y-1">{kids}</CardContent>
        </Card>
      );
    case 'text':
      return <div key={key} className={p.emphasis ? 'font-semibold' : ''}>{str(p.value ?? p.text)}</div>;
    case 'kv':
      return (
        <div key={key} className="flex justify-between text-sm">
          <span className="text-muted-foreground">{str(p.label)}</span>
          <span>{str(p.value)}</span>
        </div>
      );
    case 'badge':
      return <Badge key={key} variant="secondary">{str(p.text ?? p.value)}</Badge>;
    case 'divider':
      return <Separator key={key} className="my-2" />;
    case 'image': {
      const src = safeUrl(p.src ?? p.url);
      return src ? <img key={key} src={src} alt={str(p.alt)} className="max-w-full rounded" /> : null;
    }
    case 'button':
      return (
        <Button key={key} size="sm" variant="outline" className="mr-2 mt-1" onClick={() => onAction(str(p.value ?? p.label))}>
          {str(p.label)}
        </Button>
      );
    case 'actions':
      return <div key={key} className="flex flex-wrap">{kids}</div>;
    case 'list':
      return <ul key={key} className="list-disc pl-5">{kids}</ul>;
    case 'row':
      return <div key={key} className="flex gap-2">{kids}</div>;
    case 'column':
      return <div key={key} className="flex flex-col gap-1">{kids}</div>;
    default:
      return null; // 未知 type 忽略，不报错
  }
}

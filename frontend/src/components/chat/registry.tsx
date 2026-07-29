import React, { useState } from 'react';
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

interface Field { type: string; name: string; label?: string; placeholder?: string; options?: string[]; }

/** 从 form 节点的 children 里提取字段定义（input/select/checkbox/datetime），忽略其余子节点。 */
function collectFields(node: UiNode): Field[] {
  const out: Field[] = [];
  for (const c of Array.isArray(node.children) ? node.children : []) {
    if (!c || typeof c !== 'object') continue;
    if (['input', 'select', 'checkbox', 'datetime'].includes(c.type)) {
      const p = c.props ?? {};
      out.push({ type: c.type, name: str(p.name), label: p.label ? str(p.label) : undefined,
                 placeholder: p.placeholder ? str(p.placeholder) : undefined,
                 options: Array.isArray(p.options) ? p.options.map(str) : undefined });
    }
  }
  return out;
}

/**
 * 表单节点：唯一的有状态组件。受控收集字段值（useState），点击提交按钮时把各字段
 * 组装为 "字段名: 值"（checkbox → 是/否）的多行文本，经 onAction 当作一条用户消息发出。
 * 所有 label/option/placeholder 经 str 走 React 文本节点/受控属性渲染，不使用 dangerouslySetInnerHTML，无 XSS。
 */
// 本文件本就导出 safeUrl/renderNode 等非组件工具函数，FormNode 是未导出的内部组件，
// 混合导出不影响运行时正确性，仅 Fast Refresh 退化为整文件重载，故下行显式关闭该规则。
// eslint-disable-next-line react-refresh/only-export-components
function FormNode({ node, onAction }: { node: UiNode; onAction: (v: string) => void }) {
  const fields = collectFields(node);
  const [values, setValues] = useState<Record<string, string | boolean>>({});
  const submitLabel = (() => {
    const s = (Array.isArray(node.children) ? node.children : []).find((c) => c?.type === 'submit');
    return s?.props?.label ? str(s.props.label) : '提交';
  })();
  const set = (name: string, v: string | boolean) => setValues((prev) => ({ ...prev, [name]: v }));
  const submit = () => {
    const parts = fields.map((f) => {
      const v = values[f.name];
      const shown = f.type === 'checkbox' ? (v ? '是' : '否') : str(v ?? '');
      return `${f.name}: ${shown}`;
    });
    onAction(parts.join('\n'));
  };
  return (
    <div className="my-2 flex flex-col gap-2">
      {fields.map((f, i) => (
        <div key={i} className="flex flex-col gap-1">
          {f.label ? <span className="text-xs text-muted-foreground">{f.label}</span> : null}
          {f.type === 'checkbox' ? (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={!!values[f.name]} onChange={(e) => set(f.name, e.target.checked)} />
              <span>{f.label ?? f.name}</span>
            </label>
          ) : f.type === 'select' ? (
            <select className="border rounded px-2 py-1 text-sm" value={str(values[f.name] ?? '')} onChange={(e) => set(f.name, e.target.value)}>
              <option value=""></option>
              {(f.options ?? []).map((o, j) => <option key={j} value={o}>{o}</option>)}
            </select>
          ) : (
            <input type={f.type === 'datetime' ? 'date' : 'text'} className="border rounded px-2 py-1 text-sm"
                   placeholder={f.placeholder} value={str(values[f.name] ?? '')} onChange={(e) => set(f.name, e.target.value)} />
          )}
        </div>
      ))}
      <Button size="sm" className="self-start" onClick={submit}>{submitLabel}</Button>
    </div>
  );
}

/**
 * type → 组件 注册表：递归渲染单个 UiNode。
 * 未知 type 一律返回 null（静默忽略，不抛错、不阻断其余卡片渲染）。
 * 所有文本均以 React 文本节点渲染，不使用 dangerouslySetInnerHTML；image/link 类 URL 经 safeUrl 白名单收窄。
 */
export function renderNode(node: UiNode, onAction: (value: string) => void, key: React.Key): React.ReactNode {
  // 结构守卫：模型吐出的畸形节点（null/非对象）一律静默忽略，不抛错、不阻断其余卡片渲染（fail-soft）。
  if (!node || typeof node !== 'object') return null;
  const p = node.props ?? {};
  const kids = (Array.isArray(node.children) ? node.children : []).map((c, i) => renderNode(c, onAction, i));
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
    case 'list': {
      const items = Array.isArray(node.children) ? node.children : [];
      return (
        <ul key={key} className="list-disc pl-5 space-y-1">
          {items.map((c, i) => <li key={i}>{renderNode(c, onAction, i)}</li>)}
        </ul>
      );
    }
    case 'row':
      return <div key={key} className="flex gap-2">{kids}</div>;
    case 'column':
      return <div key={key} className="flex flex-col gap-1">{kids}</div>;
    case 'form':
      return <FormNode key={key} node={node} onAction={onAction} />;
    case 'input': case 'select': case 'checkbox': case 'datetime': case 'submit':
      return null; // 字段/提交由所属 FormNode 统一渲染，孤立出现时忽略
    default:
      return null; // 未知 type 忽略，不报错
  }
}

import React, { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import type { UiNode } from '@/types';
import { safeUrl } from '@/lib/safeUrl';
import { executeActionUri } from '@/lib/actionUri';
import { useToast } from '@/hooks/use-toast';

// 保持既有导出路径不变
export { safeUrl };

function str(v: unknown): string {
  return v == null ? '' : String(v);
}

interface Field { type: string; name: string; label?: string; placeholder?: string; options?: string[]; required: boolean; }

/** 从 form 节点的 children 里提取字段定义（input/select/checkbox/datetime），忽略其余子节点。 */
function collectFields(node: UiNode): Field[] {
  const out: Field[] = [];
  for (const c of Array.isArray(node.children) ? node.children : []) {
    if (!c || typeof c !== 'object') continue;
    if (['input', 'select', 'checkbox', 'datetime'].includes(c.type)) {
      const p = c.props ?? {};
      const nm = str(p.name);
      if (nm === '') continue; // 空 name 字段无法在提交文本里定位，直接跳过不收集
      out.push({ type: c.type, name: nm, label: p.label ? str(p.label) : undefined,
                 placeholder: p.placeholder ? str(p.placeholder) : undefined,
                 options: Array.isArray(p.options) ? p.options.map(str) : undefined,
                 required: p.required === true });
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
  // state key 用字段索引而非 f.name：同名字段（模型偶发吐出重复 name）用 name 作 key 会共享一个
  // state 槽位、互相镜像输入值，改用索引彻底隔离；提交组装文本时仍用 f.name（对外语义不变）。
  const [values, setValues] = useState<Record<number, string | boolean>>({});
  // 必填校验的错误标记：key 为 fields 索引，true 表示该字段未通过必填校验（submit 时计算，输入时不清除单个字段，整体重新校验后刷新）。
  const [errors, setErrors] = useState<Record<number, boolean>>({});
  const submitLabel = (() => {
    const s = (Array.isArray(node.children) ? node.children : []).find((c) => c?.type === 'submit');
    return s?.props?.label ? str(s.props.label) : '提交';
  })();
  const set = (i: number, v: string | boolean) => setValues((prev) => ({ ...prev, [i]: v }));
  const submit = () => {
    // 必填校验：text/select/datetime 按 trim 后是否为空判断，checkbox 按是否勾选判断；未通过的字段索引记入 errors 并中止提交。
    const nextErrors: Record<number, boolean> = {};
    let hasError = false;
    fields.forEach((f, i) => {
      if (!f.required) return;
      const v = values[i];
      const invalid = f.type === 'checkbox' ? !v : str(v ?? '').trim() === '';
      if (invalid) {
        nextErrors[i] = true;
        hasError = true;
      }
    });
    if (hasError) {
      setErrors(nextErrors);
      return;
    }
    setErrors({});
    const parts = fields.map((f, i) => {
      const v = values[i];
      const shown = f.type === 'checkbox' ? (v ? '是' : '否') : str(v ?? '');
      return `${f.name}: ${shown}`;
    });
    onAction(parts.join('\n'));
  };
  return (
    <div className="my-2 flex flex-col gap-2">
      {fields.map((f, i) => {
        const invalid = !!errors[i];
        return (
          <div key={i} className="flex flex-col gap-1">
            {f.label && f.type !== 'checkbox' ? (
              <span className="text-xs text-muted-foreground">{f.label}{f.required ? ' *' : ''}</span>
            ) : null}
            {f.type === 'checkbox' ? (
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={!!values[i]} onChange={(e) => set(i, e.target.checked)} />
                <span>{f.label ?? f.name}{f.required ? ' *' : ''}</span>
              </label>
            ) : f.type === 'select' ? (
              <select className={`border rounded px-2 py-1 text-sm ${invalid ? 'border-red-500' : ''}`}
                      value={str(values[i] ?? '')} onChange={(e) => set(i, e.target.value)}>
                <option value=""></option>
                {(f.options ?? []).map((o, j) => <option key={j} value={o}>{o}</option>)}
              </select>
            ) : (
              <input type={f.type === 'datetime' ? 'date' : 'text'} className={`border rounded px-2 py-1 text-sm ${invalid ? 'border-red-500' : ''}`}
                     placeholder={f.placeholder} value={str(values[i] ?? '')} onChange={(e) => set(i, e.target.value)} />
            )}
            {invalid ? <span className="text-xs text-red-500">必填</span> : null}
          </div>
        );
      })}
      <Button size="sm" className="self-start" onClick={submit}>{submitLabel}</Button>
    </div>
  );
}

/** 按钮样式 → shadcn variant；非法/缺失落到默认。 */
function btnVariant(style: unknown): 'outline' | 'default' | 'destructive' {
  if (style === 'primary') return 'default';
  if (style === 'danger') return 'destructive';
  return 'outline';
}

/**
 * 卡片按钮：props.action 走 Action URI（url/msg/copy），协议无效时回退 props.value 发消息。
 * 单独抽成组件是因为复制反馈要用 useToast，而 renderNode 是普通函数不能用 hook。
 */
// 同上：本文件混合导出组件与工具函数是既有约定，ActionButton 是未导出的内部组件，
// 混合导出不影响运行时正确性，仅 Fast Refresh 退化为整文件重载，故下行显式关闭该规则。
// eslint-disable-next-line react-refresh/only-export-components
function ActionButton({ node, onAction }: { node: UiNode; onAction: (v: string) => void }) {
  const { toast } = useToast();
  const p = node.props ?? {};
  const label = str(p.label);

  const handleClick = () => {
    const handled = executeActionUri(p.action, {
      onMessage: onAction,
      onCopied: () => toast({ title: '已复制' }),
      onCopyFailed: () =>
        toast({ variant: 'destructive', title: '复制失败', description: '当前页面不允许访问剪贴板' }),
    });
    if (!handled) {
      const fallback = str(p.value ?? p.label);
      if (fallback) onAction(fallback);
    }
  };

  return (
    <Button size="sm" variant={btnVariant(p.style)} className="mr-2 mt-1" onClick={handleClick}>
      {label}
    </Button>
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
          {p.title != null && str(p.title) !== '' ? <CardHeader><CardTitle>{str(p.title)}</CardTitle></CardHeader> : null}
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
      return <ActionButton key={key} node={node} onAction={onAction} />;
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

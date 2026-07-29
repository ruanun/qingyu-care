import type { UiNode } from './api';

/** 仅允 http/https 且长度受限，其余返回空串（防 javascript:/data: 注入）。 */
export function safeUrl(u: unknown): string {
  if (typeof u !== 'string' || u.length > 2048) return '';
  try {
    const p = new URL(u);
    return p.protocol === 'http:' || p.protocol === 'https:' ? u : '';
  } catch {
    return '';
  }
}

function str(v: unknown): string {
  return v == null ? '' : String(v);
}

function el(tag: string, className?: string): HTMLElement {
  const e = document.createElement(tag);
  if (className) e.className = className;
  return e;
}

/**
 * 纯 DOM 递归渲染单个 UiNode（SP1 registry.tsx 的 DOM 移植）。
 * 结构守卫：node 非对象/null、未知 type 一律返回 null（静默忽略、不抛、不阻断其余卡片）——fail-soft。
 * 全部 textContent 渲染，绝不 innerHTML；image URL 经 safeUrl 白名单。
 */
export function renderNode(
  node: UiNode | null | undefined,
  onAction: (value: string) => void,
): HTMLElement | null {
  if (!node || typeof node !== 'object') return null;
  const p = node.props ?? {};
  const childNodes = Array.isArray(node.children) ? node.children : [];
  const renderKids = (parent: HTMLElement) => {
    for (const c of childNodes) {
      const child = renderNode(c, onAction);
      if (child) parent.appendChild(child);
    }
  };

  switch (node.type) {
    case 'card': {
      const card = el('div', 'qy-card');
      if (p.title != null && str(p.title) !== '') {
        const title = el('div', 'qy-card-title');
        title.textContent = str(p.title);
        card.appendChild(title);
      }
      const body = el('div', 'qy-card-body');
      renderKids(body);
      card.appendChild(body);
      return card;
    }
    case 'text': {
      const t = el('div', p.emphasis ? 'qy-node-text qy-emph' : 'qy-node-text');
      t.textContent = str(p.value ?? p.text);
      return t;
    }
    case 'kv': {
      const kv = el('div', 'qy-kv');
      const label = el('span', 'qy-kv-label');
      label.textContent = str(p.label);
      const value = el('span', 'qy-kv-value');
      value.textContent = str(p.value);
      kv.appendChild(label);
      kv.appendChild(value);
      return kv;
    }
    case 'badge': {
      const b = el('span', 'qy-badge');
      b.textContent = str(p.text ?? p.value);
      return b;
    }
    case 'divider':
      return el('div', 'qy-divider');
    case 'image': {
      const src = safeUrl(p.src ?? p.url);
      if (!src) return null;
      const img = el('img', 'qy-node-img') as HTMLImageElement;
      img.src = src;
      img.alt = str(p.alt);
      return img;
    }
    case 'button': {
      const btn = el('button', 'qy-card-btn');
      (btn as HTMLButtonElement).type = 'button';
      btn.textContent = str(p.label);
      btn.addEventListener('click', () => onAction(str(p.value ?? p.label)));
      return btn;
    }
    case 'actions': {
      const a = el('div', 'qy-actions');
      renderKids(a);
      return a;
    }
    case 'list': {
      const ul = el('ul', 'qy-list');
      for (const c of childNodes) {
        const child = renderNode(c, onAction);
        if (child) {
          const li = document.createElement('li');
          li.appendChild(child);
          ul.appendChild(li);
        }
      }
      return ul;
    }
    case 'row': {
      const r = el('div', 'qy-row');
      renderKids(r);
      return r;
    }
    case 'column': {
      const col = el('div', 'qy-col');
      renderKids(col);
      return col;
    }
    default:
      return null; // 未知 type 忽略，不报错
  }
}

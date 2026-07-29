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

/** 字段带标签的行容器：有 label 则「标签 + 控件」竖排，否则只返回控件；required 时标签追加必填星号。 */
function wrapField(label: string, field: HTMLElement, required?: boolean): HTMLElement {
  if (!label) return field;
  const wrap = document.createElement('div');
  wrap.className = 'qy-field-row';
  const lab = document.createElement('div');
  lab.className = 'qy-field-label';
  lab.textContent = required ? `${label} *` : label;
  wrap.appendChild(lab);
  wrap.appendChild(field);
  return wrap;
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
    case 'input': {
      const input = document.createElement('input');
      input.className = 'qy-field qy-form-input';
      input.type = 'text';
      if (p.placeholder != null) input.placeholder = str(p.placeholder);
      input.setAttribute('data-qy-field', str(p.name));
      const required = p.required === true;
      if (required) input.setAttribute('data-qy-required', 'true');
      return wrapField(str(p.label), input, required);
    }
    case 'datetime': {
      const input = document.createElement('input');
      input.className = 'qy-field qy-form-input';
      input.type = 'date';
      input.setAttribute('data-qy-field', str(p.name));
      const required = p.required === true;
      if (required) input.setAttribute('data-qy-required', 'true');
      return wrapField(str(p.label), input, required);
    }
    case 'checkbox': {
      const input = document.createElement('input');
      input.className = 'qy-field';
      input.type = 'checkbox';
      input.setAttribute('data-qy-field', str(p.name));
      const required = p.required === true;
      if (required) input.setAttribute('data-qy-required', 'true');
      const label = el('label', 'qy-checkbox');
      label.appendChild(input);
      const span = document.createElement('span');
      span.textContent = required ? `${str(p.label ?? p.name)} *` : str(p.label ?? p.name);
      label.appendChild(span);
      return label;
    }
    case 'select': {
      const sel = document.createElement('select');
      sel.className = 'qy-field qy-select';
      sel.setAttribute('data-qy-field', str(p.name));
      const required = p.required === true;
      if (required) sel.setAttribute('data-qy-required', 'true');
      // 空首项：未选择时提交为空值，与前端 FormNode 一致（不默认替用户选中首项）
      const placeholder = document.createElement('option');
      placeholder.value = '';
      sel.appendChild(placeholder);
      const opts = Array.isArray(p.options) ? p.options : [];
      for (const o of opts) {
        const opt = document.createElement('option');
        opt.value = str(o);
        opt.textContent = str(o);
        sel.appendChild(opt);
      }
      return wrapField(str(p.label), sel, required);
    }
    case 'form': {
      const f = el('div', 'qy-form');
      renderKids(f);
      return f;
    }
    case 'submit': {
      const btn = el('button', 'qy-form-submit');
      (btn as HTMLButtonElement).type = 'button';
      btn.textContent = str(p.label ?? '提交');
      btn.addEventListener('click', () => {
        const form = btn.closest('.qy-form');
        if (!form) return;
        const fieldEls = Array.from(form.querySelectorAll('[data-qy-field]'));
        // 必填校验先行：text/datetime/select 按 trim 后是否为空判断，checkbox 按是否勾选判断；
        // 每次点击先清掉上一轮的 qy-field-invalid 标记再重新判定，任一未通过则中止、不组装提交。
        let hasError = false;
        for (const f2 of fieldEls) {
          f2.classList.remove('qy-field-invalid');
          if (f2.getAttribute('data-qy-required') !== 'true') continue;
          let invalid = false;
          if (f2 instanceof HTMLInputElement && f2.type === 'checkbox') {
            invalid = !f2.checked;
          } else if (f2 instanceof HTMLInputElement || f2 instanceof HTMLSelectElement) {
            invalid = f2.value.trim() === '';
          }
          if (invalid) {
            f2.classList.add('qy-field-invalid');
            hasError = true;
          }
        }
        if (hasError) return;
        const parts: string[] = [];
        for (const f2 of fieldEls) {
          const name = f2.getAttribute('data-qy-field') ?? '';
          if (!name) continue; // 空 name 字段无法在提交文本里定位，跳过不收集
          if (f2 instanceof HTMLInputElement && f2.type === 'checkbox') {
            parts.push(`${name}: ${f2.checked ? '是' : '否'}`);
          } else if (f2 instanceof HTMLInputElement || f2 instanceof HTMLSelectElement) {
            parts.push(`${name}: ${(f2 as HTMLInputElement | HTMLSelectElement).value}`);
          }
        }
        onAction(parts.join('\n'));
      });
      return btn;
    }
    default:
      return null; // 未知 type 忽略，不报错
  }
}

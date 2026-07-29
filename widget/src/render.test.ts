import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderNode, safeUrl } from './render';
import type { UiNode } from './api';

afterEach(() => {
  document.body.innerHTML = '';
});

describe('safeUrl', () => {
  it('放行 http/https', () => {
    expect(safeUrl('http://a.com/x.png')).toBe('http://a.com/x.png');
    expect(safeUrl('https://a.com/x.png')).toBe('https://a.com/x.png');
  });
  it('拒绝 javascript:/data:/相对/超长/非字符串', () => {
    expect(safeUrl('javascript:alert(1)')).toBe('');
    expect(safeUrl('data:text/html,<script>')).toBe('');
    expect(safeUrl('/relative/x.png')).toBe('');
    expect(safeUrl('http://a.com/' + 'x'.repeat(3000))).toBe('');
    expect(safeUrl(123 as unknown)).toBe('');
    expect(safeUrl(null)).toBe('');
  });
});

describe('renderNode 结构守卫（fail-soft）', () => {
  const noop = () => {};
  it('null/非对象节点返回 null', () => {
    expect(renderNode(null, noop)).toBeNull();
    expect(renderNode(undefined, noop)).toBeNull();
    expect(renderNode('x' as unknown as UiNode, noop)).toBeNull();
  });
  it('未知 type 返回 null', () => {
    expect(renderNode({ type: 'unknown-xyz' }, noop)).toBeNull();
  });
  it('children 非数组不崩（当空处理）', () => {
    const el = renderNode({ type: 'card', props: { title: 'T' }, children: 'x' as unknown as UiNode[] }, noop);
    expect(el).not.toBeNull();
    expect(el!.textContent).toContain('T');
  });
});

describe('renderNode 渲染与 XSS', () => {
  const noop = () => {};
  it('text 用 textContent，不产生 innerHTML 注入', () => {
    const el = renderNode({ type: 'text', props: { value: '<img src=x onerror=alert(1)>' } }, noop)!;
    expect(el.textContent).toBe('<img src=x onerror=alert(1)>');
    expect(el.querySelector('img')).toBeNull(); // 未被当 HTML 解析
  });
  it('kv 渲染 label/value', () => {
    const el = renderNode({ type: 'kv', props: { label: '金额', value: '¥199' } }, noop)!;
    expect(el.textContent).toContain('金额');
    expect(el.textContent).toContain('¥199');
  });
  it('image 非法 src 不渲染（返回 null）', () => {
    expect(renderNode({ type: 'image', props: { src: 'javascript:x' } }, noop)).toBeNull();
  });
  it('image 合法 src 渲染 img 且 src 正确', () => {
    const el = renderNode({ type: 'image', props: { src: 'https://a.com/x.png', alt: 'a' } }, noop)!;
    expect(el.tagName).toBe('IMG');
    expect((el as HTMLImageElement).getAttribute('src')).toBe('https://a.com/x.png');
  });
  it('card 递归渲染子节点', () => {
    const node: UiNode = { type: 'card', props: { title: '订单' }, children: [
      { type: 'kv', props: { label: '状态', value: '已发货' } },
    ]};
    const el = renderNode(node, noop)!;
    expect(el.textContent).toContain('订单');
    expect(el.textContent).toContain('已发货');
  });
  it('button 点击回填 value（无 value 用 label）', () => {
    const onAction = vi.fn();
    const el = renderNode({ type: 'button', props: { label: '查看物流', value: '查询物流' } }, onAction)!;
    el.dispatchEvent(new Event('click'));
    expect(onAction).toHaveBeenCalledWith('查询物流');
    const el2 = renderNode({ type: 'button', props: { label: '仅label' } }, onAction)!;
    el2.dispatchEvent(new Event('click'));
    expect(onAction).toHaveBeenLastCalledWith('仅label');
  });
});

describe('表单组件', () => {
  it('form 提交组装字段值为文本走 onAction', () => {
    const onAction = vi.fn();
    const form = renderNode({ type: 'form', children: [
      { type: 'input', props: { name: '姓名' } },
      { type: 'select', props: { name: '日期', options: ['今天', '明天'] } },
      { type: 'submit', props: { label: '提交' } },
    ]}, onAction)!;
    document.body.appendChild(form);
    (form.querySelector('[data-qy-field="姓名"]') as HTMLInputElement).value = '张三';
    const sel = form.querySelector('[data-qy-field="日期"]') as HTMLSelectElement;
    sel.value = '明天';
    (form.querySelector('.qy-form-submit') as HTMLButtonElement).dispatchEvent(new Event('click'));
    expect(onAction).toHaveBeenCalledTimes(1);
    const sent = onAction.mock.calls[0][0] as string;
    expect(sent).toContain('姓名');
    expect(sent).toContain('张三');
    expect(sent).toContain('日期');
    expect(sent).toContain('明天');
    document.body.removeChild(form);
  });

  it('checkbox 勾选反映在组装文本', () => {
    const onAction = vi.fn();
    const form = renderNode({ type: 'form', children: [
      { type: 'checkbox', props: { name: '同意', label: '同意条款' } },
      { type: 'submit', props: { label: 'ok' } },
    ]}, onAction)!;
    document.body.appendChild(form);
    (form.querySelector('[data-qy-field="同意"]') as HTMLInputElement).checked = true;
    (form.querySelector('.qy-form-submit') as HTMLButtonElement).dispatchEvent(new Event('click'));
    expect(onAction.mock.calls[0][0]).toContain('同意');
    document.body.removeChild(form);
  });

  it('孤立 input（无 form）渲染不崩', () => {
    expect(renderNode({ type: 'input', props: { name: 'x' } }, () => {})).not.toBeNull();
  });
});

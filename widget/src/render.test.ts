import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderNode, safeUrl } from './render';
import type { UiNode } from './api';

afterEach(() => {
  document.body.innerHTML = '';
  vi.useRealTimers();
  vi.unstubAllGlobals();
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

describe('表单必填校验', () => {
  it('required 字段为空时 submit 不触发 onAction，且该字段被标记 qy-field-invalid', () => {
    const onAction = vi.fn();
    const form = renderNode({ type: 'form', children: [
      { type: 'input', props: { name: '姓名', label: '姓名', required: true } },
      { type: 'submit', props: { label: '提交' } },
    ]}, onAction)!;
    document.body.appendChild(form);
    (form.querySelector('.qy-form-submit') as HTMLButtonElement).dispatchEvent(new Event('click'));
    expect(onAction).not.toHaveBeenCalled();
    const input = form.querySelector('[data-qy-field="姓名"]') as HTMLInputElement;
    expect(input.classList.contains('qy-field-invalid')).toBe(true);
    document.body.removeChild(form);
  });

  it('required 字段仅空白字符也视为未填', () => {
    const onAction = vi.fn();
    const form = renderNode({ type: 'form', children: [
      { type: 'input', props: { name: '姓名', required: true } },
      { type: 'submit', props: { label: '提交' } },
    ]}, onAction)!;
    document.body.appendChild(form);
    (form.querySelector('[data-qy-field="姓名"]') as HTMLInputElement).value = '   ';
    (form.querySelector('.qy-form-submit') as HTMLButtonElement).dispatchEvent(new Event('click'));
    expect(onAction).not.toHaveBeenCalled();
    document.body.removeChild(form);
  });

  it('required checkbox 未勾选时 submit 不触发 onAction', () => {
    const onAction = vi.fn();
    const form = renderNode({ type: 'form', children: [
      { type: 'checkbox', props: { name: '同意', label: '同意条款', required: true } },
      { type: 'submit', props: { label: '提交' } },
    ]}, onAction)!;
    document.body.appendChild(form);
    (form.querySelector('.qy-form-submit') as HTMLButtonElement).dispatchEvent(new Event('click'));
    expect(onAction).not.toHaveBeenCalled();
    const checkbox = form.querySelector('[data-qy-field="同意"]') as HTMLInputElement;
    expect(checkbox.classList.contains('qy-field-invalid')).toBe(true);
    document.body.removeChild(form);
  });

  it('required 字段填写/勾选后 submit 正常触发 onAction，且清除 invalid 标记', () => {
    const onAction = vi.fn();
    const form = renderNode({ type: 'form', children: [
      { type: 'input', props: { name: '姓名', required: true } },
      { type: 'checkbox', props: { name: '同意', required: true } },
      { type: 'submit', props: { label: '提交' } },
    ]}, onAction)!;
    document.body.appendChild(form);
    const submitBtn = form.querySelector('.qy-form-submit') as HTMLButtonElement;
    // 先触发一次失败，产生 invalid 标记
    submitBtn.dispatchEvent(new Event('click'));
    expect(onAction).not.toHaveBeenCalled();
    // 补全必填项后重试
    (form.querySelector('[data-qy-field="姓名"]') as HTMLInputElement).value = '张三';
    (form.querySelector('[data-qy-field="同意"]') as HTMLInputElement).checked = true;
    submitBtn.dispatchEvent(new Event('click'));
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(onAction.mock.calls[0][0]).toContain('姓名: 张三');
    expect(onAction.mock.calls[0][0]).toContain('同意: 是');
    const input = form.querySelector('[data-qy-field="姓名"]') as HTMLInputElement;
    expect(input.classList.contains('qy-field-invalid')).toBe(false);
    document.body.removeChild(form);
  });

  it('非必填字段为空不阻止 submit', () => {
    const onAction = vi.fn();
    const form = renderNode({ type: 'form', children: [
      { type: 'input', props: { name: '备注' } },
      { type: 'submit', props: { label: '提交' } },
    ]}, onAction)!;
    document.body.appendChild(form);
    (form.querySelector('.qy-form-submit') as HTMLButtonElement).dispatchEvent(new Event('click'));
    expect(onAction).toHaveBeenCalledTimes(1);
    document.body.removeChild(form);
  });

  it('label 渲染必填星号', () => {
    const form = renderNode({ type: 'form', children: [
      { type: 'input', props: { name: '姓名', label: '姓名', required: true } },
    ]}, () => {})!;
    expect(form.textContent).toContain('姓名 *');
  });
});

describe('button 的 Action URI', () => {
  it('action=msg:// 时把内容当消息发出，不发 value', () => {
    const onAction = vi.fn();
    const el = renderNode(
      { type: 'button', props: { label: '查物流', value: '旧文本', action: 'msg://新文本' } } as UiNode,
      onAction,
    )!;
    el.dispatchEvent(new MouseEvent('click'));
    expect(onAction).toHaveBeenCalledWith('新文本');
    expect(onAction).not.toHaveBeenCalledWith('旧文本');
  });

  it('action 无效时回退 value', () => {
    const onAction = vi.fn();
    const el = renderNode(
      { type: 'button', props: { label: '查物流', value: '旧文本', action: '没有协议前缀' } } as UiNode,
      onAction,
    )!;
    el.dispatchEvent(new MouseEvent('click'));
    expect(onAction).toHaveBeenCalledWith('旧文本');
  });

  it('无 action 时行为与改造前一致', () => {
    const onAction = vi.fn();
    const el = renderNode(
      { type: 'button', props: { label: '查物流', value: '查询订单' } } as UiNode,
      onAction,
    )!;
    el.dispatchEvent(new MouseEvent('click'));
    expect(onAction).toHaveBeenCalledWith('查询订单');
  });

  it('action=url:// 被 safeUrl 拒绝时不打开也不回退 value', () => {
    const open = vi.fn();
    vi.stubGlobal('open', open);
    const onAction = vi.fn();
    const el = renderNode(
      { type: 'button', props: { label: '详情', value: '回退文本', action: 'url://javascript:alert(1)' } } as UiNode,
      onAction,
    )!;
    el.dispatchEvent(new MouseEvent('click'));
    expect(open).not.toHaveBeenCalled();
    expect(onAction).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it('copy 成功后按钮文案短暂变「已复制」再恢复', async () => {
    vi.useFakeTimers();
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });
    const el = renderNode(
      { type: 'button', props: { label: '复制单号', action: 'copy://SF123' } } as UiNode,
      () => {},
    )!;

    el.dispatchEvent(new MouseEvent('click'));
    await Promise.resolve();
    await Promise.resolve();
    expect(el.textContent).toBe('已复制');

    vi.advanceTimersByTime(2000);
    expect(el.textContent).toBe('复制单号');
  });

  it('style 映射到 class，非法值落 default', () => {
    const noop = () => {};
    const primary = renderNode({ type: 'button', props: { label: 'A', style: 'primary' } } as UiNode, noop)!;
    const danger = renderNode({ type: 'button', props: { label: 'B', style: 'danger' } } as UiNode, noop)!;
    const weird = renderNode({ type: 'button', props: { label: 'C', style: '不存在' } } as UiNode, noop)!;

    expect(primary.className).toContain('qy-card-btn-primary');
    expect(danger.className).toContain('qy-card-btn-danger');
    expect(weird.className).toBe('qy-card-btn');
  });
});

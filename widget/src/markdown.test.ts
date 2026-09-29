/**
 * AI 回复的 Markdown 渲染。安全相关的用例放最前：这是「不走 innerHTML」这条规矩的直接证据。
 *
 * @author ruan
 */
import { describe, it, expect } from 'vitest';
import { renderMarkdown } from './markdown';

function render(src: string): HTMLDivElement {
  const div = document.createElement('div');
  div.appendChild(renderMarkdown(src));
  return div;
}

describe('安全：不解析 HTML、链接 fail-closed', () => {
  it('原文里的 HTML 标签只作为文字出现，不生成任何元素', () => {
    const el = render('请看 <img src=x onerror=alert(1)> 和 <script>alert(2)</script>');
    expect(el.querySelector('img, script')).toBeNull();
    expect(el.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(el.textContent).toContain('<script>alert(2)</script>');
  });

  it('javascript: / data: 链接不生成 <a>，只留文字', () => {
    const el = render('[点我](javascript:alert(1)) 和 [图](data:text/html,<b>x</b>)');
    expect(el.querySelector('a')).toBeNull();
    expect(el.textContent).toContain('点我');
  });

  it('http(s) 链接新窗口打开且不带 opener', () => {
    const a = render('[帮助中心](https://help.example.com/refund)').querySelector('a')!;
    expect(a.getAttribute('href')).toBe('https://help.example.com/refund');
    expect(a.target).toBe('_blank');
    expect(a.rel).toBe('noopener noreferrer');
    expect(a.textContent).toBe('帮助中心');
  });

  it('链接文字里的 HTML 同样只是文字', () => {
    const a = render('[<img src=x onerror=alert(1)>](https://a.com)').querySelector('a')!;
    expect(a.querySelector('img')).toBeNull();
    expect(a.textContent).toBe('<img src=x onerror=alert(1)>');
  });
});

describe('行内语法', () => {
  it('加粗、斜体、删除线、行内代码', () => {
    const el = render('**加粗** *斜体* ~~删除~~ `code`');
    expect(el.querySelector('strong')!.textContent).toBe('加粗');
    expect(el.querySelector('em')!.textContent).toBe('斜体');
    expect(el.querySelector('del')!.textContent).toBe('删除');
    expect(el.querySelector('code')!.textContent).toBe('code');
  });

  it('行内代码里的标记不再解析', () => {
    const el = render('字段 `**not_bold**`');
    expect(el.querySelector('strong')).toBeNull();
    expect(el.querySelector('code')!.textContent).toBe('**not_bold**');
  });

  /** 客服回复里常见订单字段名，下划线斜体会把它们切碎 */
  it('snake_case 不被当成斜体或加粗', () => {
    const el = render('请提供 order_id 与 refund_apply_no');
    expect(el.querySelector('em, strong')).toBeNull();
    expect(el.textContent).toBe('请提供 order_id 与 refund_apply_no');
  });

  it('裸 URL 自动成链，末尾的中文标点不算进网址', () => {
    const el = render('详见 https://help.example.com/a?b=1。谢谢');
    expect(el.querySelector('a')!.getAttribute('href')).toBe('https://help.example.com/a?b=1');
    expect(el.textContent).toBe('详见 https://help.example.com/a?b=1。谢谢');
  });

  it('未闭合的标记原样显示（流式进行中常见）', () => {
    const el = render('**运费标');
    expect(el.querySelector('strong')).toBeNull();
    expect(el.textContent).toBe('**运费标');
  });
});

describe('块级语法', () => {
  it('段落之间空行分隔，段内换行保留为 <br>', () => {
    const el = render('第一行\n第二行\n\n第二段');
    const ps = el.querySelectorAll('p');
    expect(ps).toHaveLength(2);
    expect(ps[0].querySelectorAll('br')).toHaveLength(1);
    expect(ps[1].textContent).toBe('第二段');
  });

  it('标题渲染成加粗块，不放大字号', () => {
    const h = render('### 退换货政策').querySelector('.qy-md-h')!;
    expect(h.textContent).toBe('退换货政策');
  });

  /** 截图里 AI 的真实回复形态：有序列表下挂缩进的无序子列表 */
  it('有序列表下的缩进子列表挂在对应条目里', () => {
    const el = render('1. **运费标准**\n   - 满 99 包邮\n   - 不满收 10 元\n2. **会员福利**\n   - PLUS 全场包邮');
    const ol = el.querySelector(':scope > ol')!;
    const items = ol.querySelectorAll(':scope > li');
    expect(items).toHaveLength(2);
    expect(items[0].querySelector('strong')!.textContent).toBe('运费标准');
    expect(items[0].querySelectorAll(':scope > ul > li')).toHaveLength(2);
    expect(items[1].querySelectorAll(':scope > ul > li')).toHaveLength(1);
  });

  it('有序列表保留起始序号', () => {
    const ol = render('3. 第三步\n4. 第四步').querySelector('ol')!;
    expect(ol.start).toBe(3);
  });

  it('代码块内容原样保留、不解析标记', () => {
    const code = render('```json\n{"a": "**x**"}\n```').querySelector('pre > code')!;
    expect(code.textContent).toBe('{"a": "**x**"}');
  });

  it('代码块还没收到结尾时，剩余内容都当代码显示（流式进行中）', () => {
    const el = render('示例：\n```\nline1\nline2');
    expect(el.querySelector('pre > code')!.textContent).toBe('line1\nline2');
    expect(el.querySelector('p')!.textContent).toBe('示例：');
  });

  it('表格', () => {
    const el = render('| 套餐 | 价格 |\n|---|---|\n| 基础 | **99** |\n| 专业 | 199 |');
    expect([...el.querySelectorAll('th')].map((t) => t.textContent)).toEqual(['套餐', '价格']);
    expect(el.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(el.querySelector('td strong')!.textContent).toBe('99');
  });

  it('引用与分隔线', () => {
    const el = render('> 温馨提示\n\n---\n结束');
    expect(el.querySelector('blockquote')!.textContent).toBe('温馨提示');
    expect(el.querySelector('hr')).not.toBeNull();
  });

  it('深层嵌套引用不会把栈打爆', () => {
    expect(() => render('>'.repeat(5000) + ' x')).not.toThrow();
  });
});

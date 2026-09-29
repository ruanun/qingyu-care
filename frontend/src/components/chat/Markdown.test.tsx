import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Markdown } from './Markdown';

/**
 * 管理后台的 AI 回复渲染。安全两条放最前：它们依赖 react-markdown 的默认行为（不接 rehype-raw、默认 urlTransform），
 * 哪天有人为了「支持 HTML」加上 rehype-raw，或传了个宽松的 urlTransform，这里转红。
 */
describe('Markdown 安全', () => {
    it('原文里的 HTML 不生成元素', () => {
        const { container } = render(<Markdown text={'看 <img src=x onerror=alert(1)> 和 <script>alert(2)</script>'} />);
        expect(container.querySelector('img, script')).toBeNull();
    });

    it('javascript: 链接的 href 被清掉', () => {
        const { container } = render(<Markdown text="[点我](javascript:alert(1))" />);
        const a = container.querySelector('a');
        expect(a?.getAttribute('href') ?? '').not.toMatch(/javascript:/i);
    });

    it('http(s) 链接新窗口打开且不带 opener', () => {
        const { container } = render(<Markdown text="[帮助](https://help.example.com)" />);
        const a = container.querySelector('a')!;
        expect(a.getAttribute('href')).toBe('https://help.example.com');
        expect(a.getAttribute('target')).toBe('_blank');
        expect(a.getAttribute('rel')).toBe('noopener noreferrer');
    });
});

describe('Markdown 渲染', () => {
    it('加粗、嵌套列表、表格', () => {
        const { container } = render(
            <Markdown text={'1. **运费标准**\n   - 满 99 包邮\n2. 会员\n\n| 套餐 | 价格 |\n|---|---|\n| 基础 | 99 |'} />,
        );
        expect(container.querySelector('strong')!.textContent).toBe('运费标准');
        expect(container.querySelector('ol > li > ul > li')!.textContent).toBe('满 99 包邮');
        expect(container.querySelectorAll('th')).toHaveLength(2);
        expect(container.textContent).not.toContain('**');
    });
});

import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Markdown } from './Markdown';
import MarkdownRenderer from './MarkdownRenderer';

/**
 * 管理后台的 AI 回复渲染。安全两条放最前：它们依赖 react-markdown 的默认行为（不接 rehype-raw、默认 urlTransform），
 * 哪天有人为了「支持 HTML」加上 rehype-raw，或传了个宽松的 urlTransform，这里转红。
 */
describe('Markdown 安全', () => {
    it('原文里的 HTML 不生成元素', () => {
        const { container } = render(<MarkdownRenderer text={'看 <img src=x onerror=alert(1)> 和 <script>alert(2)</script>'} />);
        expect(container.querySelector('img, script')).toBeNull();
    });

    it('javascript: 链接的 href 被清掉', () => {
        const { container } = render(<MarkdownRenderer text="[点我](javascript:alert(1))" />);
        const a = container.querySelector('a');
        expect(a?.getAttribute('href') ?? '').not.toMatch(/javascript:/i);
    });

    it('http(s) 链接新窗口打开且不带 opener', () => {
        const { container } = render(<MarkdownRenderer text="[帮助](https://help.example.com)" />);
        const a = container.querySelector('a')!;
        expect(a.getAttribute('href')).toBe('https://help.example.com');
        expect(a.getAttribute('target')).toBe('_blank');
        expect(a.getAttribute('rel')).toBe('noopener noreferrer');
    });
});

describe('Markdown 渲染', () => {
    it('加粗、嵌套列表、表格', () => {
        const { container } = render(
            <MarkdownRenderer text={'1. **运费标准**\n   - 满 99 包邮\n2. 会员\n\n| 套餐 | 价格 |\n|---|---|\n| 基础 | 99 |'} />,
        );
        expect(container.querySelector('strong')!.textContent).toBe('运费标准');
        expect(container.querySelector('ol > li > ul > li')!.textContent).toBe('满 99 包邮');
        expect(container.querySelectorAll('th')).toHaveLength(2);
        expect(container.textContent).not.toContain('**');
    });
});

/**
 * 懒加载包装：首屏包里没有 react-markdown，第一次渲染时才下载。
 * 下载完成前按纯文本显示——回复始终可读，不会留白。
 */
describe('Markdown 懒加载包装', () => {
    it('加载完成后换成渲染结果', async () => {
        render(<Markdown text="请提供**订单号**" />);
        // 首次懒加载在整套并行时可能超过默认 1 秒，放宽等待
        expect((await screen.findByText('订单号', {}, { timeout: 10000 })).tagName).toBe('STRONG');
    });

    /**
     * React.lazy 会缓存加载结果：同文件里前面的用例一旦加载过，后面直接渲染最终结果、看不到兜底，
     * 这条就会随执行顺序时红时绿。重新导入模块拿一个全新的 lazy 对象，保证每次都真的经历「加载中」。
     */
    it('加载完成前先显示原文', async () => {
        vi.resetModules();
        const { Markdown: Fresh } = await import('./Markdown');
        const { container } = render(<Fresh text="请提供**订单号**" />);
        // 第一帧是 Suspense 的兜底：原文可读，而不是空白
        expect(container.textContent).toBe('请提供**订单号**');
        expect(container.querySelector('strong')).toBeNull();
    });
});

import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import type { UiNode } from '@/types';
import { renderNode } from './registry';
import { useAppStore } from '@/stores/appStore';
import type { Application } from '@/types';

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }));

/**
 * 卡片表单的 select 换成 Radix Select 后多了一个原生 <select> 没有的崩溃点：
 * `<SelectItem value="">` 会直接抛异常。而选项来自模型输出，空串、重复项都可能出现——
 * 不过滤的话一条畸形卡片会让整个消息气泡渲染失败（fail-soft 必须覆盖渲染层）。
 *
 * ⚠️ 必须把下拉**展开**再断言：Radix 关闭态不渲染选项，空串选项要到展开那一刻才炸。
 * 只 render 不展开的版本经变异验证是永真的（删掉过滤照样绿）。
 */
beforeAll(() => {
    // jsdom 缺这几个 API，Radix Select 展开时会调用
    Element.prototype.scrollIntoView ??= vi.fn();
    Element.prototype.hasPointerCapture ??= vi.fn(() => false);
    Element.prototype.releasePointerCapture ??= vi.fn();
});

describe('卡片表单 select 对畸形选项 fail-soft', () => {
    it('选项含空串与重复项时照常渲染，展开后只列出去重后的非空选项', () => {
        const node: UiNode = {
            type: 'form',
            children: [
                { type: 'select', props: { name: 'size', label: '尺码', options: ['', 'M', 'M', 'L'] } },
                { type: 'submit', props: { label: '确定' } },
            ],
        };

        render(<>{renderNode(node, vi.fn(), 0)}</>);

        expect(screen.getByRole('button', { name: '确定' })).toBeInTheDocument();

        fireEvent.keyDown(screen.getByRole('combobox'), { key: 'Enter' });
        expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['M', 'L']);
    });
});

/**
 * 安全审查 M7：卡片 image 的地址由模型生成，可被提示注入操纵成外带地址。
 * 只有命中当前应用配置的图片域名才自动加载，否则只给链接。
 */
describe('卡片图片按应用的域名白名单放行', () => {
    const setApp = (imageAllowedDomains: string) => {
        useAppStore.setState({
            currentApp: { id: 20, name: '商城' },
            apps: [{ id: 20, name: '商城', imageAllowedDomains } as Application],
        });
    };

    it('命中白名单：自动加载且不带 Referer', () => {
        setApp('*.shop.com');
        const { container } = render(<>{renderNode({ type: 'image', props: { src: 'https://img.shop.com/a.png' } }, vi.fn(), 0)}</>);
        const img = container.querySelector('img')!;
        expect(img.getAttribute('src')).toBe('https://img.shop.com/a.png');
        expect(img.getAttribute('referrerpolicy')).toBe('no-referrer');
    });

    it('不在白名单：不加载，只给可点击链接', () => {
        setApp('img.shop.com');
        const { container } = render(<>{renderNode({ type: 'image', props: { src: 'https://evil.com/x.png?q=1', alt: '物流' } }, vi.fn(), 0)}</>);
        expect(container.querySelector('img')).toBeNull();
        expect(container.querySelector('a')!.textContent).toBe('查看图片：物流');
    });
});

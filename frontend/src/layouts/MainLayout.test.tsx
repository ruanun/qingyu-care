import { act, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { useAppStore } from '@/stores/appStore';
import MainLayout from './MainLayout';

vi.mock('@/api/platform', () => ({
    platformService: { getAnnouncement: vi.fn().mockResolvedValue({ code: 200, message: 'ok', data: '' }) },
}));
vi.mock('@/components/AppSwitcher', () => ({ default: () => <div /> }));

/**
 * 侧边栏：有子标题的子组，条目会 ml-2 缩进；「接入配置」曾单独放在没有子标题的组里，
 * 于是比同属「应用配置」的其他菜单靠左 8px。守的是不变量「同一大类下所有菜单项缩进一致」，
 * 而不只是这一个菜单——以后再加一个没写子标题的组，同样会被抓到。
 */
describe('MainLayout 侧边栏缩进', () => {
    beforeEach(() => {
        useAuthStore.setState({
            token: 't', isAuthenticated: true,
            user: { token: 't', userId: 1, username: 'a', nickname: '管理员', avatar: '', role: 'TENANT_ADMIN', tenantId: 1, tenantName: '云舟' },
        });
        useAppStore.setState({ currentApp: { id: 20, name: '云舟商城客服' } });
    });

    it('同一大类下所有菜单项的缩进一致', async () => {
        render(<MemoryRouter initialEntries={['/chat']}><MainLayout /></MemoryRouter>);
        await act(async () => { await Promise.resolve(); });

        // 标题后面会拼当前应用名（如「应用配置 · 云舟商城客服」），按前缀匹配
        const section = screen.getByText(/^应用配置/).parentElement!;
        const links = Array.from(section.querySelectorAll('a'));
        expect(links.map((a) => a.textContent)).toContain('接入配置');

        const indents = new Set(links.map((a) => a.classList.contains('ml-2')));
        expect(indents.size, `缩进不一致：${links.map((a) => `${a.textContent}=${a.classList.contains('ml-2')}`).join(', ')}`).toBe(1);
    });
});

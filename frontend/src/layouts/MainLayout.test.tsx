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

/**
 * 平台菜单与租户菜单必须能一眼分开。
 *
 * <p>平台管理员在同一个侧栏里同时看两种东西：「运营平台本身」与「管当前租户的应用」。
 * 此前两者只有一条同样的灰字小标题区分、选中态也是同一个 chip，切视角时界面毫无提示。
 * 这条测试钉的是「平台区有独立标识 + 能力与运营分两组」，不是某一个菜单项的文案。
 */
describe('MainLayout 平台区', () => {
    beforeEach(() => {
        useAuthStore.setState({
            token: 't', isAuthenticated: true,
            user: { token: 't', userId: 1, username: 'admin', nickname: '超级管理员', avatar: '', role: 'PLATFORM_ADMIN', tenantId: null, tenantName: '' },
        });
        useAppStore.setState({ currentApp: { id: 20, name: '云舟商城客服' } });
    });

    it('平台区带「平台」徽标，且分成平台运营与共享能力两组', async () => {
        render(<MemoryRouter initialEntries={['/tenants']}><MainLayout /></MemoryRouter>);
        await act(async () => { await Promise.resolve(); });

        // 徽标是平台区独有的标识：没有它，下方应用菜单滚动上来后就分不出哪些是全局的了
        expect(screen.getByText('平台')).toBeTruthy();
        expect(screen.getByText('平台运营')).toBeTruthy();
        expect(screen.getByText('共享能力')).toBeTruthy();

        // 「共享模型池」属于「共享能力」，不能又回到了「平台运营」里
        const sharedGroup = screen.getByText('共享能力').parentElement!;
        expect(Array.from(sharedGroup.querySelectorAll('a')).map((a) => a.textContent))
            .toEqual(expect.arrayContaining(['共享模型池', '共享工具', '能力包']));
    });

    it('租户管理员看不到平台区', async () => {
        useAuthStore.setState({
            token: 't', isAuthenticated: true,
            user: { token: 't', userId: 2, username: 'a', nickname: '租户', avatar: '', role: 'TENANT_ADMIN', tenantId: 1, tenantName: '云舟' },
        });
        render(<MemoryRouter initialEntries={['/chat']}><MainLayout /></MemoryRouter>);
        await act(async () => { await Promise.resolve(); });

        expect(screen.queryByText('平台')).toBeNull();
        expect(screen.queryByText('共享能力')).toBeNull();
    });
});

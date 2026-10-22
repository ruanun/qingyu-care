import React, { useEffect, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
    MessageSquare, Database, UserPen, LayoutDashboard,
    History, FileText, Layers, Settings, Bot,
    LogOut, User as UserIcon, KeyRound, Users, Smartphone, Building,
    Activity, Boxes, SlidersHorizontal, ScrollText, Building2, Plug, BarChart3,
    FlaskConical, Wrench, Sparkles, Server, Package, Store, ShieldCheck
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/authStore';
import { performLogout } from '@/lib/session';
import { useAppStore } from '@/stores/appStore';
import { platformService } from '@/api/platform';
import AppSwitcher from '@/components/AppSwitcher';
import Logo from '@/components/Logo';
import ChangePasswordDialog from '@/components/ChangePasswordDialog';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";

interface NavItem {
    label: string;
    path: string;
    icon: React.ComponentType<{ className?: string }>;
    roles?: string[];
}

/** 子分组：有 title 时渲染子组小标题并缩进条目（如「应用配置」下的 RAG 知识库 / AI 模型） */
interface NavGroup {
    title?: string;
    items: NavItem[];
}

interface NavSection {
    title: string;
    groups: NavGroup[];
    requireApp?: boolean;
    roles?: string[];
    /**
     * 平台区：整块渲染成带底色的卡片，标题换成「平台」徽标。
     *
     * <p>平台管理员要在同一个侧栏里同时看两种东西——「运营平台本身」（租户/概览/配置）与
     * 「管当前租户的应用」。此前两者只有一条同样的灰字小标题在区分，选中态也是同一个 chip，
     * 在两个视角之间切换时界面上没有任何提示。
     */
    scope?: 'platform';
}

// 菜单深度分组（SaaS平台规划.md 3.4）：平台管理 → 应用管理 → 应用配置 → 客服中心 → 数据统计
const sections: NavSection[] = [
    {
        title: '平台管理',
        scope: 'platform',
        roles: ['PLATFORM_ADMIN'],
        // 两组是两种心智模型：「运营平台」是管理员自己用的，「共享能力」是平台供给租户的。
        // 合成一组时界面没有任何东西告诉人哪一条会影响到所有租户。
        groups: [
            {
                title: '平台运营',
                items: [
                    { label: '租户列表', path: '/tenants', icon: Building },
                    { label: '系统概览', path: '/platform/overview', icon: Activity },
                    { label: '平台配置', path: '/platform/config', icon: SlidersHorizontal },
                ],
            },
            {
                title: '共享能力',
                items: [
                    { label: '共享模型池', path: '/platform/models', icon: Boxes },
                    { label: '共享工具', path: '/platform/tools', icon: Wrench },
                    { label: '能力包', path: '/platform/packages', icon: Package },
                ],
            },
        ],
    },
    {
        // 叫「组织管理」而不是「应用管理」：下面四条里有三条是租户级（成员 / 租户配置 /审计日志），
        // 真正的应用列表叫「我的应用」，用「应用管理」会让人以为这里是应用列表页
        title: '组织管理',
        groups: [{
            items: [
                { label: '我的应用', path: '/applications', icon: Smartphone, roles: ['TENANT_ADMIN', 'PLATFORM_ADMIN'] },
                { label: '成员管理', path: '/users', icon: Users, roles: ['TENANT_ADMIN', 'TENANT_MEMBER', 'PLATFORM_ADMIN'] },
                { label: '租户配置', path: '/tenant/config', icon: Building2, roles: ['TENANT_ADMIN'] },
                { label: '审计日志', path: '/audit-logs', icon: ScrollText, roles: ['PLATFORM_ADMIN', 'TENANT_ADMIN'] },
            ],
        }],
    },
    {
        title: '应用配置',
        requireApp: true,
        groups: [
            {
                title: 'RAG 知识库',
                items: [
                    { label: '知识库管理', path: '/knowledge', icon: Database },
                    { label: 'Prompt 管理', path: '/prompts', icon: FileText },
                    { label: '问答策略', path: '/strategies', icon: Layers },
                    { label: 'A/B 实验', path: '/ab-tests', icon: FlaskConical },
                ],
            },
            {
                title: 'AI 模型',
                items: [
                    { label: '模型配置', path: '/ai-models', icon: Bot },
                    { label: '转人工规则', path: '/handover-rules', icon: Settings },
                    { label: '工具', path: '/ai-tools', icon: Wrench },
                    { label: 'MCP 服务', path: '/mcp', icon: Server },
                    { label: '技能', path: '/ai-skills', icon: Sparkles },
                    { label: '工具商店', path: '/packages', icon: Store, roles: ['TENANT_ADMIN', 'PLATFORM_ADMIN'] },
                ],
            },
            {
                // 必须带 title：同一 section 里有 title 的子组条目会 ml-2 缩进，没 title 的这一组曾因此比兄弟菜单靠左
                title: '对外接入',
                items: [
                    // 页面展示 appSecret / webhookSecret，后端也只对管理员返回密钥（安全审查 H4）
                    { label: '接入配置', path: '/integration', icon: Plug, roles: ['TENANT_ADMIN', 'PLATFORM_ADMIN'] },
                ],
            },
        ],
    },
    {
        title: '客服中心',
        groups: [{
            items: [
                { label: '智能问答', path: '/chat', icon: MessageSquare },
                // 与后端 SecurityUtil.requireAgent 对齐：坐席端点只放行 TENANT_ADMIN/TENANT_MEMBER，平台管理员非坐席
                { label: '人工工作台', path: '/agent', icon: UserPen, roles: ['TENANT_ADMIN', 'TENANT_MEMBER'] },
                { label: '会话管理', path: '/sessions', icon: History },
            ],
        }],
    },
    {
        title: '数据统计',
        groups: [{
            items: [
                { label: '效果分析', path: '/dashboard', icon: LayoutDashboard },
                { label: '用量统计', path: '/usage', icon: BarChart3, roles: ['PLATFORM_ADMIN', 'TENANT_ADMIN'] },
            ],
        }],
    },
];

const allItems = sections.flatMap((s) => s.groups).flatMap((g) => g.items);

const MainLayout: React.FC = () => {
    const location = useLocation();
    const navigate = useNavigate();
    const { user } = useAuthStore();
    const { currentApp } = useAppStore();
    const [announcement, setAnnouncement] = useState('');

    // 系统公告（平台配置 platform.announcement）：获取失败静默降级为无公告
    useEffect(() => {
        platformService.getAnnouncement()
            .then((res) => setAnnouncement((res.data ?? '').trim()))
            .catch(() => setAnnouncement(''));
    }, []);

    const canSee = (item: NavItem) =>
        !item.roles || (!!user?.role && item.roles.includes(user.role));

    const [passwordDialogOpen, setPasswordDialogOpen] = useState(false);

    const handleLogout = async () => {
        // 先让服务端作废 token 再清本地（安全审查 M7），见 lib/session.ts
        await performLogout();
        navigate('/login');
    };

    const userInitial = user?.nickname?.charAt(0) || user?.username?.charAt(0) || 'U';
    const currentTitle = allItems.find((i) => i.path === location.pathname)?.label || '欢迎使用';

    return (
        <div className="flex h-screen w-screen bg-background text-foreground overflow-hidden">
            {/* Sidebar */}
            <aside className="w-64 border-r bg-muted/30 flex flex-col">
                <div className="p-6 border-b flex items-center gap-2.5">
                    <Logo className="h-8 w-8 shrink-0" />
                    <h1 className="text-xl font-bold bg-linear-to-r from-primary to-primary/60 bg-clip-text text-transparent">
                        轻语 - QingYu
                    </h1>
                </div>

                {/* 应用切换器 */}
                <div className="px-4 pt-4">
                    <AppSwitcher />
                </div>

                <nav className="flex-1 p-4 space-y-6 overflow-y-auto font-sans">
                    {sections.map((section) => {
                        if (section.roles && !(user?.role && section.roles.includes(user.role))) return null;
                        const visibleGroups = section.groups
                            .map((group) => ({ ...group, items: group.items.filter(canSee) }))
                            .filter((group) => group.items.length > 0);
                        if (visibleGroups.length === 0) return null;
                        const disabled = !!section.requireApp && !currentApp;
                        const isPlatform = section.scope === 'platform';
                        return (
                            <div
                                key={section.title}
                                className={cn(
                                    "space-y-1",
                                    // dark: 两个变体是为将来接入主题切换备的（目前全仓没有任何地方给 <html> 挂 .dark，
                                    // 所以它们今天不会命中）。深色下 primary 反转为近白（210 40% 98%），
                                    // 淡底和描边需要各自调一个量级：浅色的 4% / 25% 到深色下要么看不见要么太亮。
                                    isPlatform && "rounded-xl border border-dashed border-primary/25 bg-primary/[0.04] p-2 "
                                        + "dark:border-primary/20 dark:bg-primary/[0.07]"
                                )}
                            >
                                {isPlatform ? (
                                    // 平台区标题换成徽标：滚动到下方应用菜单时，这个色块是唯一能告诉人
                                    // 「上面那几条是全局的，会影响到所有租户」的东西
                                    <span className="mx-1 inline-flex items-center gap-1.5 rounded-md bg-primary/10 px-2 py-0.5 text-[11px] font-semibold tracking-wider text-primary dark:bg-primary/15">
                                        <ShieldCheck className="w-3.5 h-3.5" />
                                        平台
                                    </span>
                                ) : (
                                    <p className="px-3 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60">
                                        {section.title}
                                        {section.requireApp && currentApp ? ` · ${currentApp.name}` : ''}
                                    </p>
                                )}
                                {visibleGroups.map((group, groupIndex) => (
                                    <div key={group.title ?? groupIndex} className="space-y-1">
                                        {group.title && (
                                            <p className="px-3 pt-2 text-[10px] font-medium tracking-wide text-muted-foreground/45">
                                                {group.title}
                                            </p>
                                        )}
                                        {group.items.map((item) =>
                                            disabled ? (
                                                <div
                                                    key={item.path}
                                                    title="请先在上方选择应用"
                                                    className={cn(
                                                        "flex items-center gap-3 px-3 py-2 rounded-lg text-muted-foreground/40 cursor-not-allowed",
                                                        group.title && "ml-2"
                                                    )}
                                                >
                                                    <item.icon className="w-5 h-5 shrink-0" />
                                                    <span className="font-medium text-sm">{item.label}</span>
                                                </div>
                                            ) : (
                                                <Link
                                                    key={item.path}
                                                    to={item.path}
                                                    className={cn(
                                                        "flex items-center gap-3 px-3 py-2 rounded-lg transition-all duration-200",
                                                        group.title && "ml-2",
                                                        location.pathname === item.path
                                                            ? "bg-primary text-primary-foreground shadow-md shadow-primary/20"
                                                            : "hover:bg-muted/80 text-muted-foreground hover:text-foreground hover:translate-x-1"
                                                    )}
                                                >
                                                    <item.icon className="w-5 h-5 shrink-0" />
                                                    <span className="font-medium text-sm">{item.label}</span>
                                                </Link>
                                            )
                                        )}
                                    </div>
                                ))}
                            </div>
                        );
                    })}
                </nav>

                <div className="p-4 border-t mt-auto">
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <div className="flex items-center gap-3 px-3 py-2 cursor-pointer hover:bg-muted rounded-lg transition-colors group">
                                <Avatar className="w-9 h-9 border-2 border-primary/10 group-hover:border-primary/30 transition-colors">
                                    <AvatarImage src={user?.avatar} />
                                    <AvatarFallback className="bg-primary/10 text-primary font-bold">
                                        {userInitial}
                                    </AvatarFallback>
                                </Avatar>
                                <div className="flex-1 overflow-hidden">
                                    <p className="text-sm font-semibold truncate">{user?.nickname || user?.username}</p>
                                    <p className="text-[10px] text-muted-foreground truncate uppercase tracking-wider">
                                        {user?.role?.replace('_', ' ')}
                                    </p>
                                </div>
                            </div>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent className="w-56" align="end" side="right">
                            <DropdownMenuLabel>我的账号</DropdownMenuLabel>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem className="cursor-pointer">
                                <UserIcon className="mr-2 h-4 w-4" />
                                <span>个人信息</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem onClick={() => setPasswordDialogOpen(true)} className="cursor-pointer">
                                <KeyRound className="mr-2 h-4 w-4" />
                                <span>修改密码</span>
                            </DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem onClick={handleLogout} className="cursor-pointer text-destructive focus:text-destructive">
                                <LogOut className="mr-2 h-4 w-4" />
                                <span>退出登录</span>
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenu>
                </div>
            </aside>
            <ChangePasswordDialog open={passwordDialogOpen} onOpenChange={setPasswordDialogOpen} />

            {/* Main Content */}
            <main className="flex-1 flex flex-col relative overflow-hidden bg-background">
                <header className="h-16 border-b flex items-center justify-between px-6 bg-background/80 backdrop-blur-xs z-10 shrink-0">
                    <h2 className="text-lg font-semibold">{currentTitle}</h2>
                    <div className="flex items-center gap-4">
                        <span className="text-xs px-2 py-1 rounded bg-green-100 text-green-700 font-medium">服务运行中</span>
                    </div>
                </header>
                {announcement && (
                    <div className="px-6 py-1.5 text-sm bg-primary/10 text-primary border-b shrink-0 truncate" title={announcement}>
                        📢 {announcement}
                    </div>
                )}
                {/* key=currentApp.id：切换应用时强制重挂载页面，触发重新拉取数据 */}
                <div key={currentApp?.id ?? 'none'} className="flex-1 overflow-hidden relative">
                    <Outlet />
                </div>
            </main>
        </div>
    );
};

export default MainLayout;

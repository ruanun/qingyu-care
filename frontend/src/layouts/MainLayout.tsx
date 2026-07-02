import React from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
    MessageSquare, Database, UserPen, LayoutDashboard,
    History, FileText, Layers, Settings, Bot,
    LogOut, User as UserIcon, Shield, Users, Smartphone, Building
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/authStore';
import { useAppStore } from '@/stores/appStore';
import AppSwitcher from '@/components/AppSwitcher';
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

interface NavSection {
    title: string;
    items: NavItem[];
    requireApp?: boolean;
}

const sections: NavSection[] = [
    {
        title: '客服中心',
        items: [
            { label: '智能问答', path: '/chat', icon: MessageSquare },
            { label: '人工工作台', path: '/agent', icon: UserPen },
            { label: '会话管理', path: '/sessions', icon: History },
        ],
    },
    {
        title: '应用配置',
        requireApp: true,
        items: [
            { label: '知识库', path: '/knowledge', icon: Database },
            { label: 'Prompt 管理', path: '/prompts', icon: FileText },
            { label: '问答策略', path: '/strategies', icon: Layers },
            { label: 'AI 模型', path: '/ai-models', icon: Bot },
            { label: '转人工规则', path: '/handover-rules', icon: Settings },
        ],
    },
    {
        title: '管理',
        items: [
            { label: '应用管理', path: '/applications', icon: Smartphone, roles: ['TENANT_ADMIN', 'PLATFORM_ADMIN'] },
            { label: '成员管理', path: '/users', icon: Users, roles: ['TENANT_ADMIN', 'TENANT_MEMBER', 'PLATFORM_ADMIN'] },
            { label: '租户管理', path: '/tenants', icon: Building, roles: ['PLATFORM_ADMIN'] },
            { label: '数据统计', path: '/dashboard', icon: LayoutDashboard },
        ],
    },
];

const allItems = sections.flatMap((s) => s.items);

const MainLayout: React.FC = () => {
    const location = useLocation();
    const navigate = useNavigate();
    const { user, logout } = useAuthStore();
    const { currentApp } = useAppStore();

    const canSee = (item: NavItem) =>
        !item.roles || (!!user?.role && item.roles.includes(user.role));

    const handleLogout = () => {
        logout();
        navigate('/login');
    };

    const userInitial = user?.nickname?.charAt(0) || user?.username?.charAt(0) || 'U';
    const currentTitle = allItems.find((i) => i.path === location.pathname)?.label || '欢迎使用';

    return (
        <div className="flex h-screen w-screen bg-background text-foreground overflow-hidden">
            {/* Sidebar */}
            <aside className="w-64 border-r bg-muted/30 flex flex-col">
                <div className="p-6 border-b">
                    <h1 className="text-xl font-bold bg-gradient-to-r from-primary to-primary/60 bg-clip-text text-transparent">
                        轻语客服 - QingYu
                    </h1>
                </div>

                {/* 应用切换器 */}
                <div className="px-4 pt-4">
                    <AppSwitcher />
                </div>

                <nav className="flex-1 p-4 space-y-6 overflow-y-auto font-sans">
                    {sections.map((section) => {
                        const visibleItems = section.items.filter(canSee);
                        if (visibleItems.length === 0) return null;
                        const disabled = !!section.requireApp && !currentApp;
                        return (
                            <div key={section.title} className="space-y-1">
                                <p className="px-3 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60">
                                    {section.title}
                                    {section.requireApp && currentApp ? ` · ${currentApp.name}` : ''}
                                </p>
                                {visibleItems.map((item) =>
                                    disabled ? (
                                        <div
                                            key={item.path}
                                            title="请先在上方选择应用"
                                            className="flex items-center gap-3 px-3 py-2 rounded-lg text-muted-foreground/40 cursor-not-allowed"
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
                            <DropdownMenuItem className="cursor-pointer">
                                <Shield className="mr-2 h-4 w-4" />
                                <span>安全中心</span>
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

            {/* Main Content */}
            <main className="flex-1 flex flex-col relative overflow-hidden bg-background">
                <header className="h-16 border-b flex items-center justify-between px-6 bg-background/80 backdrop-blur-sm z-10 shrink-0">
                    <h2 className="text-lg font-semibold">{currentTitle}</h2>
                    <div className="flex items-center gap-4">
                        <span className="text-xs px-2 py-1 rounded bg-green-100 text-green-700 font-medium">服务运行中</span>
                    </div>
                </header>
                {/* key=currentApp.id：切换应用时强制重挂载页面，触发重新拉取数据 */}
                <div key={currentApp?.id ?? 'none'} className="flex-1 overflow-hidden relative">
                    <Outlet />
                </div>
            </main>
        </div>
    );
};

export default MainLayout;

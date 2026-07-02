# 前端应用中心化菜单重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 前端引入"当前应用"上下文，侧边栏按「客服中心 / 应用配置(当前应用) / 管理」分区，并把历史数据绑定到默认应用。

**Architecture:** 纯前端新增 `appStore`（Zustand 持久化当前应用）+ `AppSwitcher`（侧栏顶部切换器）+ `RequireApp`（未选应用守卫）；`client.ts` 请求携带 `X-App-Id`；`MainLayout` 重构为分区菜单并以 `currentApp.id` 作为 Outlet key 触发刷新。后端已具备 `X-App-Id → appFilter` 闭环，无需改动。历史数据由一次性幂等 SQL 迁移到每租户最早应用。

**Tech Stack:** React 19 + TypeScript + Vite + Zustand + shadcn/Radix Select；PostgreSQL（迁移 SQL）。

> **项目约束（重要，覆盖本 skill 默认模板）：**
> 1. **不含 `git commit` 步骤** —— 项目全局规则禁止提交，代码提交由用户统一执行。每个任务以"类型检查通过 + 手动验证"为完成标志。
> 2. **不引入前端测试框架** —— 按 spec，验证以类型检查（`tsc`）与浏览器手动核对为准，不写单元测试。
> 3. 参考 spec：`docs/superpowers/specs/2026-07-01-frontend-app-context-menu-design.md`

**通用类型检查命令**（每个前端任务后运行）：
```bash
pnpm -C frontend exec tsc -b --noEmit
```
预期：无输出（类型检查通过）。

---

## 文件结构

| 文件 | 职责 | 动作 |
|------|------|------|
| `frontend/src/stores/appStore.ts` | 当前应用状态 + 应用列表（持久化 currentApp） | 新增 |
| `frontend/src/api/client.ts` | 请求拦截器注入 `X-App-Id` | 修改 |
| `frontend/src/components/AppSwitcher.tsx` | 侧栏顶部应用切换器 + 拉列表 + 默认选中 | 新增 |
| `frontend/src/components/RequireApp.tsx` | 未选应用守卫（空状态） | 新增 |
| `frontend/src/layouts/MainLayout.tsx` | 分区菜单 + 嵌入切换器 + Outlet key + 配置区置灰 | 修改 |
| `frontend/src/App.tsx` | 应用配置类路由包裹 `RequireApp` | 修改 |
| `backend/src/main/resources/migration/2026-07-01-bind-legacy-app-id.sql` | 历史数据绑定默认应用 | 新增 |

---

## Task 1: appStore（当前应用状态）

**Files:**
- Create: `frontend/src/stores/appStore.ts`

- [ ] **Step 1: 创建 appStore**

```typescript
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { Application } from '../types';

/** 当前选中应用的精简信息 */
export interface CurrentApp {
    id: number;
    name: string;
}

interface AppState {
    // 当前操作的应用（持久化到 localStorage）
    currentApp: CurrentApp | null;
    // 当前用户可见的应用列表（不持久化，登录后重新拉取）
    apps: Application[];

    setCurrentApp: (app: CurrentApp | null) => void;
    setApps: (apps: Application[]) => void;
    clearCurrentApp: () => void;
}

export const useAppStore = create<AppState>()(
    persist(
        (set) => ({
            currentApp: null,
            apps: [],
            setCurrentApp: (app) => set({ currentApp: app }),
            setApps: (apps) => set({ apps }),
            clearCurrentApp: () => set({ currentApp: null }),
        }),
        {
            name: 'qingyu-current-app',
            // 仅持久化 currentApp；apps 每次会话重新拉取，避免陈旧
            partialize: (state) => ({ currentApp: state.currentApp }),
        }
    )
);
```

- [ ] **Step 2: 类型检查**

```bash
pnpm -C frontend exec tsc -b --noEmit
```
预期：无输出（通过）。

---

## Task 2: client.ts 注入 X-App-Id

**Files:**
- Modify: `frontend/src/api/client.ts`（请求拦截器）

- [ ] **Step 1: 在请求拦截器中追加 X-App-Id 注入**

将现有 `api.interceptors.request.use(...)` 整块替换为：

```typescript
// Request interceptor for adding token + current app
api.interceptors.request.use(
    (config) => {
        // 从 localStorage 读取持久化的 auth state
        const authState = localStorage.getItem('qingyu-auth');
        if (authState) {
            try {
                const { state } = JSON.parse(authState);
                if (state?.token) {
                    config.headers['satoken'] = state.token;
                }
            } catch (e) {
                console.error('Failed to parse auth state:', e);
            }
        }

        // 从 localStorage 读取当前应用，注入 X-App-Id（后端据此按应用隔离）
        const appState = localStorage.getItem('qingyu-current-app');
        if (appState) {
            try {
                const { state } = JSON.parse(appState);
                if (state?.currentApp?.id != null) {
                    config.headers['X-App-Id'] = String(state.currentApp.id);
                }
            } catch (e) {
                console.error('Failed to parse app state:', e);
            }
        }

        return config;
    },
    (error) => Promise.reject(error)
);
```

- [ ] **Step 2: 类型检查**

```bash
pnpm -C frontend exec tsc -b --noEmit
```
预期：无输出（通过）。

---

## Task 3: AppSwitcher 组件

**Files:**
- Create: `frontend/src/components/AppSwitcher.tsx`
- 依赖：Task 1 的 `appStore`、现有 `applicationService`、现有 `ui/select`

- [ ] **Step 1: 创建 AppSwitcher**

```tsx
import React, { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Smartphone, Plus } from 'lucide-react';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { applicationService } from '@/api/application';
import { useAppStore } from '@/stores/appStore';

/**
 * 应用切换器
 * 位于侧栏顶部：拉取当前用户可见应用，选择后写入 appStore，
 * 请求随即携带 X-App-Id，实现按应用隔离。
 */
const AppSwitcher: React.FC = () => {
    const navigate = useNavigate();
    const { currentApp, apps, setApps, setCurrentApp } = useAppStore();

    useEffect(() => {
        let mounted = true;
        applicationService.list()
            .then((res) => {
                if (!mounted) return;
                const list = res.data ?? [];
                setApps(list);
                // 默认选中：保留仍存在的已选应用，否则选 id 最小（最早创建）者
                const stillExists = currentApp && list.some((a) => a.id === currentApp.id);
                if (!stillExists) {
                    if (list.length > 0) {
                        const first = [...list].sort((a, b) => a.id - b.id)[0];
                        setCurrentApp({ id: first.id, name: first.name });
                    } else {
                        setCurrentApp(null);
                    }
                }
            })
            .catch(() => {
                // 拉取失败保持现状，不阻断页面
            });
        return () => { mounted = false; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (apps.length === 0) {
        return (
            <button
                onClick={() => navigate('/applications')}
                className="flex w-full items-center gap-2 rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground hover:bg-muted/50"
            >
                <Plus className="h-4 w-4" />
                暂无应用，去创建
            </button>
        );
    }

    return (
        <Select
            value={currentApp ? String(currentApp.id) : undefined}
            onValueChange={(val) => {
                const app = apps.find((a) => String(a.id) === val);
                if (app) setCurrentApp({ id: app.id, name: app.name });
            }}
        >
            <SelectTrigger className="w-full">
                <div className="flex items-center gap-2 overflow-hidden">
                    <Smartphone className="h-4 w-4 shrink-0" />
                    <SelectValue placeholder="选择应用" />
                </div>
            </SelectTrigger>
            <SelectContent>
                {apps.map((app) => (
                    <SelectItem key={app.id} value={String(app.id)}>
                        {app.name}
                    </SelectItem>
                ))}
            </SelectContent>
        </Select>
    );
};

export default AppSwitcher;
```

- [ ] **Step 2: 类型检查**

```bash
pnpm -C frontend exec tsc -b --noEmit
```
预期：无输出（通过）。

---

## Task 4: RequireApp 守卫组件

**Files:**
- Create: `frontend/src/components/RequireApp.tsx`
- 依赖：Task 1 的 `appStore`

- [ ] **Step 1: 创建 RequireApp**

```tsx
import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Smartphone } from 'lucide-react';
import { useAppStore } from '@/stores/appStore';

/**
 * 应用上下文守卫
 * 包裹"应用配置"类页面：未选择当前应用时展示空状态，引导先选/创建应用。
 */
const RequireApp: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const navigate = useNavigate();
    const { currentApp, apps } = useAppStore();

    if (!currentApp) {
        return (
            <div className="flex h-full flex-col items-center justify-center gap-4 text-center text-muted-foreground">
                <Smartphone className="h-12 w-12 opacity-40" />
                <div>
                    <p className="text-base font-medium">请先选择一个应用</p>
                    <p className="text-sm">
                        {apps.length === 0
                            ? '当前还没有应用，请先创建。'
                            : '在左上角切换器中选择要配置的应用。'}
                    </p>
                </div>
                {apps.length === 0 && (
                    <button
                        onClick={() => navigate('/applications')}
                        className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground"
                    >
                        去创建应用
                    </button>
                )}
            </div>
        );
    }

    return <>{children}</>;
};

export default RequireApp;
```

- [ ] **Step 2: 类型检查**

```bash
pnpm -C frontend exec tsc -b --noEmit
```
预期：无输出（通过）。

---

## Task 5: MainLayout 分区重构

**Files:**
- Modify: `frontend/src/layouts/MainLayout.tsx`（整体替换）
- 依赖：Task 1 `appStore`、Task 3 `AppSwitcher`

- [ ] **Step 1: 整体替换 MainLayout.tsx**

```tsx
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
```

- [ ] **Step 2: 类型检查**

```bash
pnpm -C frontend exec tsc -b --noEmit
```
预期：无输出（通过）。

- [ ] **Step 3: 手动验证**

启动 `pnpm -C frontend dev`，登录后确认：侧栏顶部出现应用切换器，菜单呈「客服中心 / 应用配置 / 管理」三分区；应用配置分区标题带当前应用名。

---

## Task 6: App.tsx 路由守卫

**Files:**
- Modify: `frontend/src/App.tsx`
- 依赖：Task 4 `RequireApp`

- [ ] **Step 1: 增加 import**

在现有 import 区加入：

```tsx
import RequireApp from './components/RequireApp';
```

- [ ] **Step 2: 用 RequireApp 包裹 5 个应用配置路由**

将以下 5 行路由：

```tsx
          <Route path="knowledge" element={<KnowledgePage />} />
          <Route path="prompts" element={<PromptPage />} />
          <Route path="strategies" element={<StrategyConfigPage />} />
          <Route path="ai-models" element={<ModelConfigPage />} />
          <Route path="handover-rules" element={<HandoverRulePage />} />
```

替换为：

```tsx
          <Route path="knowledge" element={<RequireApp><KnowledgePage /></RequireApp>} />
          <Route path="prompts" element={<RequireApp><PromptPage /></RequireApp>} />
          <Route path="strategies" element={<RequireApp><StrategyConfigPage /></RequireApp>} />
          <Route path="ai-models" element={<RequireApp><ModelConfigPage /></RequireApp>} />
          <Route path="handover-rules" element={<RequireApp><HandoverRulePage /></RequireApp>} />
```

> 说明：`/chat`、`/agent`、`/sessions`（客服中心）与管理类路由不包裹 —— 会话/工作台本身已按 app_id 过滤，未选应用时展示租户级数据可接受。

- [ ] **Step 3: 类型检查**

```bash
pnpm -C frontend exec tsc -b --noEmit
```
预期：无输出（通过）。

---

## Task 7: 历史数据迁移 SQL

**Files:**
- Create: `backend/src/main/resources/migration/2026-07-01-bind-legacy-app-id.sql`

- [ ] **Step 1: 创建迁移脚本**

```sql
-- ============================================================
-- 历史数据迁移：将 app_id IS NULL 的业务数据绑定到每个租户的"默认应用"
-- 默认应用 = 该租户下 id 最小（最早创建）的应用
-- 幂等：仅更新 app_id IS NULL 的行；tenant_id 为空或该租户无应用时跳过
-- 一次性手动执行（项目未接入 Flyway/Liquibase）
-- ============================================================

DO $$
DECLARE
    tbl TEXT;
    tables TEXT[] := ARRAY[
        'knowledge_base', 'document', 'chat_session',
        'chat_strategy', 'handover_rules', 'prompt_templates', 'ai_model_config'
    ];
BEGIN
    FOREACH tbl IN ARRAY tables LOOP
        EXECUTE format($f$
            UPDATE %I t
            SET app_id = (
                SELECT MIN(a.id) FROM sys_application a WHERE a.tenant_id = t.tenant_id
            )
            WHERE t.app_id IS NULL
              AND t.tenant_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM sys_application a WHERE a.tenant_id = t.tenant_id)
        $f$, tbl);
        RAISE NOTICE '已处理表: %', tbl;
    END LOOP;
END $$;
```

- [ ] **Step 2: 执行迁移**

```bash
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care < backend/src/main/resources/migration/2026-07-01-bind-legacy-app-id.sql
```
预期：每张表输出 `NOTICE: 已处理表: xxx`。

- [ ] **Step 3: 验证幂等与结果**

```bash
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care -c "SELECT count(*) AS remaining FROM knowledge_base t WHERE t.app_id IS NULL AND t.tenant_id IS NOT NULL AND EXISTS (SELECT 1 FROM sys_application a WHERE a.tenant_id = t.tenant_id);"
```
预期：`remaining = 0`（有应用的租户下已无未绑定的知识库）。再次执行 Step 2 应无副作用。

---

## Task 8: 端到端手动验证（对照 spec 第 5 节）

**Files:** 无（仅验证）

- [ ] **Step 1: 构建通过**

```bash
pnpm -C frontend build
```
预期：`tsc -b` 与 `vite build` 均成功，无类型/构建错误。

- [ ] **Step 2: 逐项核对（浏览器 + DevTools）**

启动 `pnpm -C frontend dev`（后端与基础设施已运行），依次确认：

1. 登录后侧栏出现应用切换器，默认选中最早应用；三分区正确。
2. Network 面板中，应用级接口请求头包含 `X-App-Id`。
3. 在应用 A 下创建知识库；切到应用 B → 该知识库不可见；切回 A → 可见。
4. 无应用（或新租户）时「应用配置」菜单置灰；直接访问 `/knowledge` 显示"请先选择应用"空状态。
5. 迁移后历史数据在默认应用下可见。
6. 以非超管账号登录，看不到「租户管理」。

---

## Self-Review 记录

- **Spec coverage**：菜单信息架构(Task 5)、应用上下文机制(Task 1/2/3)、无应用兜底(Task 4/6)、历史数据迁移方案 A(Task 7)、角色可见性(Task 5)、验证清单(Task 8) —— 均有对应任务。
- **Placeholder scan**：无 TBD/TODO，代码步骤均给出完整实现。
- **Type consistency**：`useAppStore` 的 `currentApp/apps/setCurrentApp/setApps`、`CurrentApp{id,name}`、`applicationService.list()→res.data:Application[]` 在各任务间一致。

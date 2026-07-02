# 前端应用中心化菜单重构 — 设计规格

- 作者：ruan
- 日期：2026-07-01
- 状态：待评审

## 1. 背景与目标

### 现状
- 前端侧边栏为扁平菜单（`MainLayout.tsx`），无"当前应用"概念。
- 所有应用级配置（知识库 / Prompt / 策略 / 模型 / 转人工）实际作用于**租户级全部数据**，未按应用隔离。
- `authStore` 仅含 token/user，`client.ts` 请求只带 `satoken`，未带应用标识。

### 后端已就绪（已核实）
- `TenantInterceptor`：从请求头 `X-App-Id` 读取应用 ID 写入 `TenantContext.appId`。
- `TenantDataAspect`：当 `appId != null` 时启用 Hibernate `appFilter`，查询按 `app_id` 过滤。
- `TenantEntityListener#prePersist`：保存 `BaseTenantEntity` 时自动填充 `app_id`。

结论：**前端选定应用并携带 `X-App-Id` 后，读写自动锁定到该应用，后端无需改动。**

### 目标
引入"当前应用"上下文，侧边栏按「客服中心 / 应用配置(当前应用) / 管理」分区，真正落地"每个应用独立配置"。

## 2. 范围

### In scope
- `MainLayout.tsx` 三分区菜单 + 侧栏顶部应用切换器。
- 新增 `stores/appStore.ts`（当前应用状态，持久化）。
- 新增 `components/AppSwitcher.tsx`。
- `client.ts` 请求拦截器注入 `X-App-Id`。
- 无应用 / 未选应用的兜底交互。
- 历史数据一次性迁移脚本（方案 A）。

### Out of scope
- 路由 URL 结构变更（不引入 `/apps/:appId/...`）。
- 未实现页面：接入配置(API/Widget)、用量统计、效果分析、系统配置。
- 前端测试框架（本次以手动验证清单为准）。
- "被授权应用"的细粒度成员权限控制。

## 3. 详细设计

### 3.1 菜单信息架构

| 分区 | 菜单项（现有路由） | 可见角色 |
|------|------|------|
| **客服中心** | 智能问答 `/chat`、人工工作台 `/agent`、会话管理 `/sessions` | 全部已登录角色 |
| **应用配置（当前应用）** | 知识库 `/knowledge`、Prompt `/prompts`、问答策略 `/strategies`、AI 模型 `/ai-models`、转人工规则 `/handover-rules` | 全部已登录角色 |
| **管理** | 应用管理 `/applications`、成员管理 `/users`、租户管理 `/tenants`、数据统计 `/dashboard` | 见 3.5 角色规则 |

未实现页面不放占位，避免死链。数据统计沿用现有 `DashboardPage`。

### 3.2 应用上下文机制（纯前端）

**`stores/appStore.ts`**（Zustand + persist，key `qingyu-current-app`）：
- 状态：`currentApp: {id: number, name: string} | null`、`apps: Application[]`
- 操作：`setCurrentApp(app)`、`setApps(list)`、`clearCurrentApp()`
- 持久化仅存 `currentApp`（`apps` 每次登录后重新拉取，避免陈旧）。

**`components/AppSwitcher.tsx`**：
- 位于侧栏顶部（标题下方）。
- 挂载时经 `applicationService.list()` 拉取当前用户可见应用写入 `appStore.apps`。
- 下拉展示应用列表；选择即 `setCurrentApp`。
- 无应用时显示"暂无应用，去创建"，点击跳 `/applications`。

**`client.ts` 请求拦截器**：在现有注入 `satoken` 之后，读取 `qingyu-current-app` 持久化的 `currentApp.id`，非空则设置 `config.headers['X-App-Id']`。

**默认选中逻辑**（`AppSwitcher` 拉取列表后）：
1. 若持久化的 `currentApp` 仍存在于最新列表中 → 保留。
2. 否则默认选中列表中 `id` 最小（最早创建）的应用。
3. 列表为空 → `currentApp = null`。

**切换应用后刷新**：`MainLayout` 中包裹 `<Outlet />` 的容器使用 `key={currentApp?.id ?? 'none'}`，切换应用时强制重挂载页面，触发各页面重新拉取数据。无需逐页改造。

### 3.3 无应用 / 未选应用处理
- `currentApp == null` 时：「应用配置」分区菜单项**置灰不可点**。
- 直接输入 URL 访问应用配置页且未选应用：在 `MainLayout` 路由层用统一守卫组件 `RequireApp` 包裹"应用配置"类路由，未选应用时渲染"请先在左上角选择应用"空状态。
- 租户下无任何应用：切换器与空状态均引导跳转 `/applications` 创建。

### 3.4 历史数据迁移（方案 A）

**目的**：引入 app 概念前创建的数据 `app_id IS NULL`，选中应用后会被 `appFilter` 过滤而不可见。将其绑定到每租户"默认应用"。

**默认应用定义**：某租户下 `id` 最小（最早创建）的应用。

**迁移逻辑**（幂等，仅更新 `app_id IS NULL` 的行）：
对每个租户 `T`，取其默认应用 `A`（`sys_application` 中 `tenant_id=T` 且 `id` 最小者）；若 `T` 无应用则跳过（其历史数据保持 `app_id=null`）。将以下表中 `tenant_id=T AND app_id IS NULL` 的行 `app_id` 更新为 `A`：
`knowledge_base`、`document`、`chat_session`、`chat_strategy`、`handover_rules`、`prompt_templates`、`ai_model_config`。

**多应用租户**：历史数据统一归入最早应用，后续可手动再分配。

**形式**：一次性、可幂等重跑的迁移脚本（放置位置与执行方式——独立 SQL 脚本 vs 一次性 `CommandLineRunner`——在实现计划阶段确定）。不接入 Flyway/Liquibase（当前项目未使用迁移框架，保持现状）。

### 3.5 角色可见性规则（沿用现状）
- **租户管理** `/tenants`：仅 `PLATFORM_ADMIN`。
- **应用管理** `/applications`：`TENANT_ADMIN` / `PLATFORM_ADMIN`。
- **成员管理** `/users`：`TENANT_ADMIN` / `TENANT_MEMBER` / `PLATFORM_ADMIN`（沿用现有 `MainLayout` 配置）。
- **客服中心 / 应用配置 / 数据统计**：全部已登录角色可见。

## 4. 改动清单
- 修改：`frontend/src/layouts/MainLayout.tsx`（分区 + 切换器 + Outlet key + 配置区置灰）。
- 修改：`frontend/src/api/client.ts`（注入 `X-App-Id`）。
- 新增：`frontend/src/stores/appStore.ts`。
- 新增：`frontend/src/components/AppSwitcher.tsx`。
- 新增：`frontend/src/components/RequireApp.tsx`（未选应用守卫，包裹应用配置类路由）。
- 新增：历史数据迁移脚本（位置待定）。
- 后端：无改动。

## 5. 验证清单（手动）
1. 登录后侧栏出现应用切换器，默认选中最早应用；「客服中心/应用配置/管理」三分区正确。
2. 网络请求头包含 `X-App-Id`（DevTools 核对）。
3. 应用 A 下创建知识库，切到应用 B 后该知识库不可见；切回 A 可见。
4. 未选应用（或租户无应用）时「应用配置」菜单置灰，直接访问配置页显示空状态引导。
5. 运行迁移脚本后，历史 `app_id IS NULL` 数据归属到默认应用并在该应用下可见；重复运行不产生副作用（幂等）。
6. 角色可见性：以非超管账号登录看不到租户管理。
7. `PLATFORM_ADMIN` 行为符合预期（未指定租户时的全局查看不受 `X-App-Id` 影响的边界确认）。

## 6. 风险与边界
- **PLATFORM_ADMIN 全局视图**：`TenantDataAspect` 在超管且 `tenantId==null` 时不启用过滤器。需确认平台管理员携带 `X-App-Id` 时的预期行为（本次以现有后端行为为准，不改动）。
- **切换刷新用 key 重挂载**：实现简单但会丢失页面内临时状态（如未提交表单）。对当前以列表/配置为主的页面可接受。
- **迁移的默认应用选择**：多应用租户历史数据统一归最早应用，可能与用户预期不完全一致，已在设计中说明并允许后续手动调整。

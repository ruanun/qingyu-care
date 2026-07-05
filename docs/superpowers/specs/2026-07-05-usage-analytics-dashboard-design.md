---
title: 用量统计看板 设计
author: ruan
date: 2026-07-05
status: 已实现
---

# 用量统计看板 设计

## 1. 背景与范围

Phase 3「管理后台」唯一未落地的收尾项：路线图菜单 `📊 数据统计 → 用量统计 / 效果分析`。此前该项延后，因为核心数据（开放 API 的 token 用量）尚未就绪——`sys_api_usage.token_count` 恒为 0。随「开放 API Token 记账」（2026-07-05 合入，见 `2026-07-04-token-accounting-design.md`）修复，`sys_api_usage` 现有真实的 `call_count` / `message_count` / `token_count`，看板的前置依赖已满足。

**范围**：新增一个按角色自适应的用量看板页，展示**开放 API 用量**（调用 / 消息 / token）与**轻量会话效果**（会话量 / AI 解决率 / 转人工率）的汇总、趋势与排行。数据全部来自现有两张表，不新建数据采集管道。

**非目标（YAGNI，明确不做）**：
- 深度效果分析（热点问题聚类、知识库覆盖率、对话质量评估、A/B）——属 Phase 4.4，需要新建数据管道。
- 自定义日期区间、周/月粒度切换、数据导出。
- 计费 / 套餐 / 账单——属 Phase 4.1，本看板只做「看」，是计费的前置可视化。

**决策已定**（brainstorming 2026-07-05）：
- **数据范围**：用量为主 + 复用 `chat_session` 的轻量效果指标。
- **视角**：平台 + 租户两级自适应（同一页，后端按角色决定聚合范围）。
- **时间**：近 7 / 30 / 90 天快捷，按天粒度，默认近 30 天。
- **聚合**：实时 SQL 聚合 + 单 overview 接口，不做预聚合表 / 缓存。
- **前端图表**：引入 recharts 3.x + shadcn Chart 封装。

## 2. 目标与非目标

**目标**
- 新增 `GET /v1/analytics/usage`，按当前登录用户角色自适应返回汇总 + 趋势 + 排行。
- 平台管理员看全平台（排行按租户）；租户管理员看本租户（排行按应用，可下钻单应用）。租户范围由后端强制，不信任前端传参。
- 新增前端 `UsagePage`，时间快捷筛选 + 汇总卡片 + 趋势折线图 + 排行榜，按角色显隐导航项。
- 新增单测 + 集成 IT（延续现有回归网）守护角色隔离与跨应用聚合。

**非目标（YAGNI）**
- 不改 `sys_api_usage` / `chat_session` 表结构。
- 不做深度效果分析、导出、自定义区间、周/月粒度。
- 不动计费。

## 3. 数据来源与口径

| 数据源 | 覆盖范围 | 提供指标 | 时间字段 | 租户过滤 |
|---|---|---|---|---|
| `sys_api_usage` | **仅开放 API**（按 app+天已聚合） | 调用数 / 消息数 / token | `usage_date` | 不继承 `BaseTenantEntity`，**手写** `tenant_id`/`app_id` 过滤 |
| `chat_session` | **全渠道**（含管理后台测试对话、Widget、开放 API） | 会话量、AI 解决率、转人工率 | `created_at` | 继承 `BaseTenantEntity`，聚合查询**显式按 `tenant_id` 参数过滤**（见 §4.3） |

**口径定义**：
- **token**：记 `total_tokens`（含 reasoning tokens），与记账口径一致。
- **AI 解决率** = 区间内 `status = ENDED 且 agent_id 为空` 的会话数 / 区间内 `ENDED` 会话数（沿用现有 `ChatSessionRepository.countAiResolvedSessions` 的「无人工介入结束占比」口径，判定字段为 `agent_id`）。
- **转人工率** = 区间内 `handover_time 非空` 的会话数 / 区间内总会话数。

> ⚠️ **口径差异**：`sys_api_usage`（用量）只计开放 API，`chat_session`（会话）是全渠道，两者天然对不齐（如后台测试对话增加会话数但不增加调用数）。**界面须明确标注**「用量 = 开放 API 接入，会话 = 全部渠道」，避免被当成数据 bug。

## 4. 后端设计

### 4.1 接口

`GET /v1/analytics/usage`
- 查询参数：
  - `days`：整数，∈ {7, 30, 90}，默认 30；非法值回落 30。区间为 `[today - (days-1), today]`，按天。
  - `appId`：可选 Long，仅租户视角有效，用于下钻单应用；平台视角忽略。
- 需登录。返回统一 `ApiResponse<UsageAnalyticsDTO>`。

### 4.2 角色自适应聚合范围 —— `AnalyticsService`

按项目约定，`AnalyticsService` 为单个 `@Service` 具体类（简单聚合服务，不拆 `service/impl`）。从当前登录态取角色（复用现有角色判定，`UserRole.PLATFORM_ADMIN` / `TENANT_ADMIN` / `TENANT_MEMBER`）：

| 角色 | 聚合范围 | 排行维度 |
|---|---|---|
| `PLATFORM_ADMIN` | 全平台（不加租户过滤） | 按**租户** Top 10（`tenant.name`） |
| `TENANT_ADMIN` / `TENANT_MEMBER` | 强制限本租户 `tenantId`（从 `TenantContext` 取） | 按**应用** Top 10（`application.name`）；传 `appId` 则下钻单应用 |

> 注：`TENANT_MEMBER` 能调接口，但**导航项不对成员显示**（见 §5）；接口层仍按本租户范围返回，避免越权。

### 4.3 聚合查询

- **`SysApiUsageRepository`** 新增聚合查询（该实体不吃 Hibernate 过滤，`where` 手写）：
  - 按天 `group by usage_date`：`sum(call_count)`, `sum(token_count)`, `sum(message_count)`，`where usage_date >= :from [and tenant_id = :tid] [and app_id = :aid]`。
  - 排行：平台 `group by tenant_id`、租户 `group by app_id`，`sum(call_count)`, `sum(token_count)`，`order by sum(call_count) desc limit 10`。
- **`ChatSessionRepository`** 新增区间聚合查询：
  - 按天 `group by date(created_at)`：会话数、`ENDED` 数、`ENDED 且 agent_id is null` 数（AI 解决）、`handover_time is not null` 数（转人工），`where created_at >= :from [and tenant_id = :tid]`。
  - **关键**：跨应用聚合**显式传 `tenantId` 过滤**，不依赖 Hibernate `appFilter`——否则请求头 `X-App-Id` 会把 `chat_session` 限到单应用，导致租户级汇总只剩当前应用。实现用 `@Query`（JPQL/native）显式带 `tenant_id` 条件。
- 名称回填：排行结果的 `tenant_id`/`app_id` 二次查 `Tenant`/`Application` 取 `name`（Top 10 数量小，无 N+1 顾虑）。

### 4.4 返回 DTO

`UsageAnalyticsDTO`（`dto/` 下，嵌套 Java record）：
```
UsageAnalyticsDTO {
  Summary summary;            // 区间汇总
  List<TrendPoint> trend;     // 按天，已补零
  List<RankingItem> ranking;  // Top 10
  String scope;               // "PLATFORM" | "TENANT"，供前端切换文案/排行标题
}
Summary     { long totalCalls; long totalTokens; long totalMessages;
              long totalSessions; double aiResolutionRate; double handoverRate; }
TrendPoint  { LocalDate date; long calls; long tokens; long sessions; }
RankingItem { Long id; String name; long calls; long tokens; }
```

### 4.5 补零

`trend` 对区间内**无数据的天补 0**（后端补齐，前端直接绘制）。做法：以 `[from, today]` 生成完整日期序列，左连接两个聚合结果的 `Map<LocalDate, ...>`，缺失填 0。放后端避免前端各自补零逻辑分散。

## 5. 前端设计

- 新增 `pages/UsagePage.tsx`，路由 `/usage`（`App.tsx` 加 `<ProtectedRoute>`，**不包 `<RequireApp>`**——租户级跨应用，不要求先选应用）。
- 新增 `api/analytics.ts`（service + TS 类型，走现有 axios 单例）。
- 引入 **recharts 3.x**（`package.json`）+ `components/ui/chart.tsx`（shadcn Chart 封装，CVA + `cn()` 风格）。
- 页面结构：
  1. 时间快捷 `Tabs`（近 7 / 30 / 90 天，默认 30）；租户视角额外一个应用下钻 `Select`（含「全部应用」）。
  2. 汇总卡片行：复用现有 `Card`——总调用、总 token、总会话、AI 解决率、转人工率。卡片区带口径说明脚注（§3 的差异标注）。
  3. 趋势折线图：一张多系列折线，**默认叠加调用数 / token / 会话三条线**，图例可切换显隐。
  4. 排行榜：柱状图 + 表格并列，标题按 `scope` 显示「租户用量 Top 10」或「应用用量 Top 10」。
- `layouts/MainLayout.tsx` 按角色新增「数据统计 / 用量统计」导航项（`PLATFORM_ADMIN` + `TENANT_ADMIN` 可见，`TENANT_MEMBER` 不显示）。
- loading（`Loader2`）、空状态、错误 `toast` 复用现有 `DashboardPage` 模式。

## 6. 影响面

| 文件 | 改动 |
|---|---|
| `controller/AnalyticsController.java` | **新增**：`GET /v1/analytics/usage` |
| `service/AnalyticsService.java` | **新增**：角色自适应聚合、补零、名称回填 |
| `dto/UsageAnalyticsDTO.java` | **新增**：含嵌套 `Summary`/`TrendPoint`/`RankingItem` record |
| `repository/SysApiUsageRepository.java` | 新增按天聚合 + 排行查询（手写 `tenant_id`/`app_id` 过滤） |
| `repository/ChatSessionRepository.java` | 新增区间按天聚合查询（显式 `tenant_id` 过滤，绕开 `appFilter`） |
| `frontend/src/pages/UsagePage.tsx` | **新增**：看板页 |
| `frontend/src/api/analytics.ts` | **新增**：service + 类型 |
| `frontend/src/components/ui/chart.tsx` | **新增**：shadcn Chart 封装 |
| `frontend/src/App.tsx` | 加 `/usage` 路由 |
| `frontend/src/layouts/MainLayout.tsx` | 按角色加导航项 |
| `frontend/package.json` | 加 `recharts` 依赖 |

## 7. 测试

- **后端单测（Mockito，`AnalyticsServiceTest`）**：
  - 角色自适应：平台管理员不加租户过滤 / 租户强制本租户过滤（mock repository，验证传入的过滤参数与排行维度）。
  - 补零：区间含空缺天时 `trend` 长度 = `days`，缺失天各指标为 0。
  - 汇总口径：AI 解决率、转人工率按定义计算正确（含分母为 0 时返回 0，不除零）。
- **集成测试（Testcontainers，`AnalyticsUsageIT`，继承 `AbstractPostgresIT`）**：
  - 播种两租户各若干 `sys_api_usage` + `chat_session`（跨多应用、跨多天）。
  - 平台管理员请求 → 看到**跨租户**汇总、排行按租户。
  - 租户管理员请求 → **只看到本租户**（隔离，不含另一租户数据）。
  - **跨应用聚合不被 `appFilter` 挡**：请求带 `X-App-Id` 时，租户级汇总仍包含该租户下其它应用的会话（固化 §4.3 的坑）。
  - `trend` 补零、`ranking` 按调用数降序且 ≤ 10。
- **前端**：`UsagePage` 渲染 smoke（mock service，可选，轻量）；`pnpm build` 验证 recharts 3.x 与 React 19 兼容。
- `mvn test` 仍免 Docker、`mvn verify` 含新 IT 全绿。

## 8. 风险与考量

- **React 19 × recharts**：用 recharts 3.x（已支持 React 19）。实现首步先装依赖跑 `pnpm build` 确认无 peer/编译问题；若不兼容（概率低），图表方案需回 brainstorming 重议（如改零依赖手绘 SVG），不在本 spec 直接展开。
- **`chat_session` 的 `appFilter` 干扰**：聚合查询必须显式 `tenant_id` 过滤 + IT 固化（§4.3、§7），这是最容易踩的坑。
- **平台视角性能**：全平台 `group by` 在数据量大时可能变慢；当前数据量小（按天已聚合），实时够用。若未来慢，再引预聚合表（本 spec 明确不做）。
- **角色获取一致性**：`AnalyticsService` 的角色判定须复用现有平台管理员判定逻辑（与 `TenantDataAspect` 同源），不另造一套，避免口径漂移。

## 9. 验收标准

- 平台管理员访问 `/usage` → 看到全平台用量汇总、按天趋势、按租户 Top 10 排行。
- 租户管理员访问 `/usage` → 只看到本租户用量与会话效果、按应用 Top 10；可下钻单应用；**看不到其它租户任何数据**。
- 趋势图区间内每天有点（无数据补零）；切换 7/30/90 天正常刷新。
- 界面标注用量与会话的口径差异。
- 单测 + IT 全绿；`mvn test` 免 Docker、`mvn verify` 含 `AnalyticsUsageIT` 全绿；`pnpm build` 通过。

## 10. 实现结论（2026-07-05）

已按实现计划 `docs/superpowers/plans/2026-07-05-usage-analytics-dashboard.md` 落地，合入特性分支 `feat/usage-analytics-dashboard`（9 个功能提交，子代理驱动开发 + 两阶段审查）。关键实现要点：

- **后端**：`AnalyticsController`（瘦）→ `AnalyticsService`（注入现有 `AuthService.isPlatformAdmin()`/`getCurrentTenantId()` 判角色，未另造判定）。`sys_api_usage` 用 JPQL 聚合（非 filter 实体）；`chat_session` 用 **native query** 绕过 Hibernate `appFilter`——`AnalyticsUsageIT` 固化「租户管理员带 `X-App-Id` 时会话汇总仍跨应用」这一坑。AI 解决率口径用 `agent_id IS NULL`（与既有 `DashboardController` 同源），转人工率用 `handover_time IS NOT NULL`。趋势后端补零。
- **前端**：引入 **recharts 3.9.2**（React 19 兼容，`pnpm build` 验证通过），新增 `UsagePage`，直接用 recharts 原生组件（未建 `components/ui/chart.tsx` 全套封装，属对 §5 的 YAGNI 简化）；`fetchData` 用 `useCallback` 规范 effect 依赖；导航「用量统计」对平台/租户管理员可见。
- **验证**：后端 `mvn verify` BUILD SUCCESS（单测 69 + 集成 23，含 `AnalyticsRepositoryIT` 4、`AnalyticsUsageIT` 1，全绿、零回归）；前端 `pnpm build` 通过。

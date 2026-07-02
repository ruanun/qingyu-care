---
title: Phase 3 剩余管理能力设计
author: ruan
date: 2026-07-02
status: 待评审
---

# Phase 3 剩余管理能力设计

## 1. 背景与范围

`SaaS平台规划.md` 中 Phase 3（管理后台重构）尚未完成，剩余 6 项。经与需求方确认，本轮聚焦**能独立落地、不依赖 Phase 2 的 4 项**：

| 编号 | 功能 | 层级 |
|------|------|------|
| ① | 平台配置 | 平台级（超管） |
| ② | 租户配置 | 租户级（租户管理员） |
| ③ | 共享模型池 | 平台级（超管） |
| ④ | 系统监控与日志（审计日志 + 健康概览） | 平台级（超管）+ 租户级只读 |

**本轮不做（明确排除）：**

- 用量统计与账单 → 延后（后续做「用量统计 + 套餐额度/超额告警」，不接在线支付）。
- 接入配置（API Key / Widget 代码生成）→ 本质属于 Phase 2，依赖开放 API 与 `widget.js`，在 Phase 2 一并做。
- 系统监控**不接入** Prometheus / Grafana / ELK 等外部可观测性栈，仅做应用层的审计日志 + 健康概览。

4 项同属 Phase 3 管理能力、彼此关联，**合并为一份设计、一个实现计划**推进。

## 2. 现状与关键约束（基于代码核实）

- 分层约定：`entity → repository → service(→impl) → controller → dto`，控制器统一 `/api` 上下文 + `/v1/...` 路径前缀。
- 认证：Sa-Token；角色枚举 `UserRole = { PLATFORM_ADMIN, TENANT_ADMIN, TENANT_MEMBER }`；Session 中存 `role`。
- 多租户隔离：`BaseTenantEntity` 定义 `@Filter(condition = "tenant_id = :tenantId")`，由 `TenantDataAspect` 在 `repository..*` 方法执行前启用。
  - **关键约束**：过滤条件 `tenant_id = :tenantId` 会**排除 `tenant_id IS NULL` 的行**。
  - `AiModelConfig extends BaseTenantEntity`；`ModelRouterService.init()` 在启动时（无租户上下文）创建"默认模型"，其 `tenant_id = null`。因此对已登录的**租户用户**，现有 `findByScenarioAndEnabledTrueOrderByPriorityAsc` 会因租户过滤而**看不到平台默认模型**——这是既有隐患，本轮借共享模型池一并修复。
- 无任何全局配置表；`Tenant`（`sys_tenant`）实体仅含 name/code/contact/status/plan/expireTime，无配置字段。
- `TenantContext` 提供 `tenantId / appId / userId` 三个 ThreadLocal。
- `DashboardController`（`/v1/dashboard`）已有会话/知识库统计与最近会话，健康概览可与之并列或复用。
- `management.endpoints.web.exposure.include: health,info,metrics` 已开放 Actuator health。
- 表命名：系统级表用 `sys_` 前缀（如 `sys_tenant`）；业务表无前缀（`ai_model_config` 等）。新增系统级表沿用 `sys_` 前缀。
- 建表：`spring.jpa.hibernate.ddl-auto: update`，新实体启动时自动建表；新增字段自动补列。无需手写迁移脚本。

## 3. 总体设计原则

1. **不引入新框架/新依赖**，沿用现有技术栈与分层。
2. 审计采集复用项目已有的 AOP 风格（`TenantDataAspect` 即 AOP 切面）。
3. 权限用 Sa-Token 现有能力做接口级校验（超管专属接口校验 `role == PLATFORM_ADMIN`）。
4. 前端沿用现有页面模式：`pages/*Page.tsx` + `api/*.ts`（axios 封装 `client.ts`）+ 现有 shadcn/ui 组件；菜单在 `MainLayout.tsx` 的"管理"分组内追加，用 `roles` 字段控制可见性。
5. YAGNI：配置项先做默认小集，键值表结构支持后续零成本扩展。

## 4. 功能设计

### 4.1 统一配置中心（① 平台配置 + ② 租户配置）

一张键值表同时承载平台级与租户级配置，用 `scope` 区分。

**实体 `SysConfig`（表 `sys_config`）**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long PK | |
| scope | String(20) | `PLATFORM` / `TENANT` |
| tenantId | Long, nullable | scope=PLATFORM 时为 null；scope=TENANT 时为租户 id |
| configKey | String(100) | 配置键 |
| configValue | String(2000) | 配置值（统一按字符串存，读取时按类型解析） |
| description | String(500) | 说明 |
| updatedAt | LocalDateTime | |

- 唯一约束：`(scope, tenantId, configKey)`。
- **不继承** `BaseTenantEntity`（避免自动租户过滤干扰平台级读取）；租户隔离由 Service 层显式按 `scope + tenantId` 查询保证。

**`ConfigService`**

- `String get(scope, tenantId, key, default)` / `getBoolean / getInt / getLong`（带默认值兜底）。
- `void set(scope, tenantId, key, value, description)`（upsert）。
- `Map<String,String> getAll(scope, tenantId)`：供配置页整表读取。
- 平台级读取忽略 tenantId（传 null）。

**默认配置项（首批，可后续扩展）**

- 平台（scope=PLATFORM）：
  - `platform.registration.enabled`（是否开放注册，bool）
  - `platform.default.plan`（新租户默认套餐，枚举名）
  - `platform.announcement`（系统公告，文本）
- 租户（scope=TENANT）：
  - `tenant.display.name`（对外显示名/品牌）
  - `tenant.session.timeout.minutes`（会话超时覆盖，int；为空则用全局 `qingyu.care.session.timeout-minutes`）
  - `tenant.default.model.scenario`（默认模型场景偏好）

> 消费方接入（如注册开关真正拦截注册、租户超时覆盖真正生效）在实现计划中作为独立小步，避免"存了不生效"。

### 4.2 共享模型池（③）

**`AiModelConfig` 增加字段 `scope`**（String(20)，默认 `TENANT`）：

- `PLATFORM`：平台共享模型，由超管维护，`tenant_id = null`。
- `TENANT`：租户/应用自有模型（现有行为）。
- 历史数据兼容：现有"默认模型"（tenant_id=null）在数据迁移时（或 `init()` 逻辑）标记为 `PLATFORM`。`init()` 改为创建 `scope=PLATFORM` 的默认共享模型。

**仓库层**：新增绕过租户过滤器的查询（原生 SQL，Hibernate `@Filter` 不作用于原生查询）：

```java
@Query(value = "SELECT * FROM ai_model_config " +
       "WHERE scope = 'PLATFORM' AND enabled = true AND scenario = :scenario " +
       "ORDER BY priority ASC", nativeQuery = true)
List<AiModelConfig> findSharedModelsByScenario(@Param("scenario") String scenario);
```

**路由改造 `ModelRouterService.getModelsForScenario(scenario)`**：

1. 取租户自有启用模型（现有方法，经租户过滤）。
2. 取平台共享启用模型（新原生方法，绕过过滤）。
3. 合并：**租户自有优先**，其后接平台共享；各自按 priority 升序；按 `(provider, modelId, endpoint)` 去重。
4. 若合并结果为空，降级到 `default` 场景重复上述逻辑。
- `callWithFallback` 沿用合并后的列表做降级，无需改动其循环逻辑。
- 修复效果：租户用户即使未自建模型，也能命中平台共享默认模型。

**平台共享模型的 CRUD**：新增超管专属接口（见 §6），仅操作 `scope=PLATFORM` 的行；Service 在保存时强制 `scope=PLATFORM`、`tenantId=null`。

### 4.3 系统监控与日志（④）

#### 审计日志

**实体 `SysAuditLog`（表 `sys_audit_log`）**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long PK | |
| tenantId | Long, nullable | 操作发生时的租户（平台级操作为 null） |
| userId | Long, nullable | 操作者 |
| username | String(100) | 操作者用户名（冗余，便于展示） |
| action | String(100) | 动作，如 `LOGIN` / `TENANT_CREATE` / `MODEL_UPDATE` |
| targetType | String(50) | 目标类型，如 `Tenant` / `AiModelConfig` |
| targetId | String(50) | 目标 id（字符串以兼容各类型） |
| detail | String(1000) | 摘要/参数（避免记录敏感明文，如 apiKey/password） |
| ip | String(64) | 请求 IP |
| createdAt | LocalDateTime | |

- **不继承** `BaseTenantEntity`（日志按需跨租户查询，隔离由 Service 显式控制）。查询页对租户管理员按 `tenantId` 过滤，对超管不过滤。

**采集：自定义注解 `@AuditLog` + AOP 切面 `AuditLogAspect`**

- 注解：`@AuditLog(action = "TENANT_CREATE", targetType = "Tenant")`，标注在关键写操作的 Controller 方法上。
- 切面 `@AfterReturning`：从 `TenantContext`/Sa-Token 取 userId/username/tenantId、从请求取 IP，异步落库（复用现有 `AsyncConfig` 的线程池，`@Async`）。
- `detail` 只记录非敏感摘要；对 password / apiKey 字段脱敏或不记录。
- 覆盖范围（首批）：登录、租户增删改、应用增删改、用户增删改、模型（含共享）增删改、配置修改。

**查询**：`GET /v1/audit-logs` 分页 + 过滤（action / 时间范围 / username）。超管全量；租户管理员限本租户。

#### 健康概览

- `GET /v1/platform/overview`（超管专属）：
  - 系统健康：内部调用 Actuator `HealthEndpoint`（注入 `org.springframework.boot.actuate.health.HealthEndpoint`）取聚合状态（UP/DOWN）。
  - 关键计数：租户总数、应用总数、用户总数、会话总数、今日对话数（复用/扩展现有 repository count 方法）。
- 前端"系统概览"页展示健康状态卡片 + 计数卡片。

### 4.4 菜单与权限

`MainLayout.tsx` 现有分组：`客服中心 / 应用配置 / 管理`。在**管理**组内追加：

| 菜单项 | 路由 | 可见角色 |
|--------|------|----------|
| 系统概览 | `/platform/overview` | PLATFORM_ADMIN |
| 共享模型池 | `/platform/models` | PLATFORM_ADMIN |
| 平台配置 | `/platform/config` | PLATFORM_ADMIN |
| 审计日志 | `/audit-logs` | PLATFORM_ADMIN、TENANT_ADMIN |
| 租户配置 | `/tenant/config` | TENANT_ADMIN |

- 后端超管接口统一做 `role == PLATFORM_ADMIN` 校验（可用一个小工具方法或 Sa-Token 注解）。
- 前端菜单 `roles` 字段控制显隐，后端仍需独立鉴权（不依赖前端隐藏）。

## 5. 数据模型汇总

新增表：`sys_config`、`sys_audit_log`。
修改表：`ai_model_config` 增加列 `scope VARCHAR(20) DEFAULT 'TENANT'`。
全部由 JPA `ddl-auto: update` 自动创建/补列；无需手写 DDL。历史 `ai_model_config` 行 `scope` 为空的按 `TENANT` 处理，tenant_id=null 的默认模型在 `init()`/一次性修正中置为 `PLATFORM`。

## 6. API 设计（`/api` 上下文下）

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/v1/platform/config` | 读平台配置全集 | 超管 |
| PUT | `/v1/platform/config` | 批量更新平台配置 | 超管 |
| GET | `/v1/platform/overview` | 健康 + 计数概览 | 超管 |
| GET | `/v1/platform/models` | 共享模型列表 | 超管 |
| POST | `/v1/platform/models` | 新增共享模型 | 超管 |
| PUT | `/v1/platform/models/{id}` | 更新共享模型 | 超管 |
| DELETE | `/v1/platform/models/{id}` | 删除共享模型 | 超管 |
| GET | `/v1/tenant/config` | 读当前租户配置 | 租户管理员 |
| PUT | `/v1/tenant/config` | 更新当前租户配置 | 租户管理员 |
| GET | `/v1/audit-logs` | 审计日志分页查询 | 超管/租户管理员 |

## 7. 前端设计

- 新增页面：`PlatformOverviewPage`、`SharedModelPage`、`PlatformConfigPage`、`TenantConfigPage`、`AuditLogPage`。
- 新增 api 模块：`platform.ts`（config/overview/models）、`audit.ts`、`tenant-config.ts`（或并入现有 `tenant.ts`）。
- 复用现有 `ui/` 组件（table、dialog、form、tabs、card、toast）；沿用 `client.ts` 拦截器与 `ApiResponse` 约定。
- `App.tsx` 追加对应路由；`MainLayout.tsx` 追加菜单项与 `roles`。

## 8. 测试策略

项目当前几乎无测试（仅默认 `QingyuCareApplicationTests`）。本轮**不回填旧代码测试**，但为新增的非平凡逻辑补聚焦单测：

- `ConfigServiceTest`：get/set upsert、类型化读取默认值兜底、平台/租户 scope 隔离。
- 共享模型路由：`getModelsForScenario` 的「租户自有 ∪ 平台共享」合并、优先级排序、去重、空结果降级到 default。
- `AuditLogAspect`：注解方法触发落库、敏感字段脱敏。

纯 CRUD 页面与直通接口不强制写测试。

## 9. 兼容性与迁移

- `ai_model_config.scope` 新列有默认值 `TENANT`，旧行不受影响；`init()` 调整后创建/修正 `PLATFORM` 默认模型，保证租户用户可用。
- 配置消费点（注册开关、租户超时覆盖）改为读 `ConfigService`，无配置时回落到现有 `application.yml` 默认，行为向后兼容。
- 审计切面 `@Async` 落库，异常不影响主流程（切面内 try-catch 兜底）。

## 10. 风险与非目标

- **非目标**：账单/计费、接入配置、外部可观测性栈、Widget、开放 API。
- **风险**：
  - 共享模型原生查询绕过过滤器，需确保仅查 `scope=PLATFORM`，避免越权读到租户模型（查询条件已硬编码 scope）。
  - 审计 `detail` 需严格脱敏，防止 apiKey/password 落库。
  - `ddl-auto: update` 仅补列不删列，字段变更需谨慎（本轮均为新增，风险低）。

## 11. 交付物清单

**后端**：`SysConfig`、`SysAuditLog` 实体 + 仓库；`AiModelConfig` 加 `scope`；`ConfigService`、`AuditLogService`、`PlatformService`（概览）；改造 `ModelRouterService`；`@AuditLog` 注解 + `AuditLogAspect`；`PlatformController`、`TenantConfigController`、`AuditLogController`；相关 DTO；`@AuditLog` 标注到既有关键写接口。

**前端**：5 个新页面 + 对应 api 模块；`App.tsx` 路由；`MainLayout.tsx` 菜单。

**测试**：上述 3 组聚焦单测。

---
title: 计费与套餐额度 设计
author: ruan
date: 2026-07-05
status: 草案
---

# 计费与套餐额度 设计

## 1. 背景与范围

Phase 4.1 的第一步：给开放 API 加**租户级月度用量额度**（调用次数 + token 双维度），超额拦截、接近阈值告警，套餐额度平台可配。

现状：`TenantPlan` 枚举已有 FREE/PRO/ENTERPRISE 三档，但**只是枚举、无额度定义**；`Tenant` 有 `plan` 字段但无额度字段；已有 QPM 频率限流（`RateLimitService`）但**无周期性用量额度**；`sys_api_usage`（应用+天 的 call/message/token）已就绪，是额度计量的数据基础；配置中心 `sys_config`（平台/租户两级）可存额度。

**范围**：套餐→额度映射（平台可配 + 内置默认）、月度用量累计、开放 API 请求前额度检查与超额拦截、接近阈值软告警、租户查看自身额度。

**非目标（YAGNI）**：
- 在线支付、账单出账（用户明确不接支付）。
- 资源配额（应用数/知识库文档数/存储量）——另一类静态配额，后续。
- 应用级子额度（本期只做租户级总额）。
- Webhook 额度事件推送——Webhook 按 app 订阅、额度是租户级，层级错位，后续再做。
- 租户 `expireTime` 到期联动拦截（属租户生命周期，非本期）。

**决策已定**（brainstorming 2026-07-05）：
- 维度：**调用次数 + token 双额度**，任一超额即拦截。
- 层级：**租户级总额**（该租户所有应用用量合计）。
- 配置：**平台可配**（`sys_config` PLATFORM 存套餐额度），缺失回落**内置默认**。
- 超额：返回 **403 + `QUOTA_EXCEEDED`**（与限流的 429 区分）。
- ENTERPRISE 用**额度 0 = 不限**；月度**自然重置**（按当月 `usage_date` 累计，跨月自动归零，无需定时任务）。
- 检查在**请求前**（截至上次的累计），本次放行后异步计量——末次可能轻微超额，可接受。

## 2. 目标与非目标

**目标**
- 平台可配各套餐月度额度（调用/token），缺失回落内置默认；`0` 表示不限。
- 开放 API 请求前按租户当月累计检查额度，任一维度超额返回 403 `QUOTA_EXCEEDED`。
- 接近阈值（默认 80%，可配）放行但加响应头软提示 + 审计日志。
- 租户可查自身本月额度使用（管理端接口 + 用量看板卡片）。
- 单测 + 集成 IT 守护（延续现有回归网）。

**非目标（YAGNI）**：见 §1。

## 3. 套餐额度定义（平台可配 + 内置默认）

存平台配置中心（`ConfigService`，`OwnerScope.PLATFORM`，`tenantId=null`），键约定：

| 键 | 含义 |
|---|---|
| `billing.quota.FREE.calls` / `.tokens` | 免费版 月度调用/token 额度 |
| `billing.quota.PRO.calls` / `.tokens` | 专业版 |
| `billing.quota.ENTERPRISE.calls` / `.tokens` | 企业版（默认 0 = 不限） |
| `billing.quota.warn-threshold` | 告警阈值百分比，默认 80 |

**内置默认**（配置缺失时回落，实现时作为常量占位、平台管理员后续在配置页调）：
- FREE：calls=1000，tokens=100000
- PRO：calls=50000，tokens=5000000
- ENTERPRISE：calls=0，tokens=0（不限）

`0` 或负值 = 该维度不限。平台管理员在**现有平台配置页**（键值配置中心）直接改，v1 不做专门套餐编辑 UI。

## 4. 额度解析与用量累计 — `QuotaService`

新增 `service/QuotaService.java`（单个 `@Service`，注入 `ConfigService` + `TenantRepository` + `SysApiUsageRepository`）。

- **解析额度** `resolveQuota(Long tenantId)`：查租户 `plan` → 从平台配置读 `billing.quota.{plan}.calls`/`.tokens`（`ConfigService.getLong`（新增，`getInt` 的 long 版），缺失回落内置默认）。返回 `{callsLimit, tokensLimit}`（`long`，0=不限）。**token 额度可达十亿级，须用 long 读取**：`getInt` 在值超过 21 亿时会 `NumberFormatException` 静默回落默认，行为诡异且难排查。
- **累计用量**：`SysApiUsageRepository` 新增
  ```
  SELECT COALESCE(SUM(call_count),0) AS calls, COALESCE(SUM(token_count),0) AS tokens
  FROM sys_api_usage
  WHERE tenant_id = :tenantId AND usage_date >= :monthStart
  ```
  （`monthStart` = 当月 1 日；native 或 JPQL 均可，该实体非 filter 实体、手写 where。用投影 `QuotaUsageRow{getCalls();getTokens();}`。）
- **检查** `check(Long tenantId) -> QuotaCheckResult`：
  ```
  QuotaCheckResult {
    boolean exceeded;       // 任一维度用量 >= 额度（额度>0 时）
    boolean nearThreshold;  // 任一维度用量 >= 额度 * 阈值%（额度>0 时）
    long callsUsed, callsLimit, tokensUsed, tokensLimit;
  }
  ```
  额度=0 的维度视为不限（不参与 exceeded/nearThreshold 判定）。两维度都不限 → 直接返回全 false（快路径）。

## 5. 额度检查与拦截

在 `OpenApiAuthInterceptor.preHandle` 的**限流检查通过之后**插入：
```
QuotaCheckResult q = quotaService.check(app.getTenant().getId());
if (q.exceeded()) {
    TenantContext.clear();
    writeError(response, 403, "本月额度已用尽，请升级套餐", "QUOTA_EXCEEDED"); // reason=QUOTA_EXCEEDED
    return false;
}
if (q.nearThreshold()) {
    response.setHeader("X-Quota-Usage",
        "calls=" + q.callsUsed() + "/" + q.callsLimit()
        + ";tokens=" + q.tokensUsed() + "/" + q.tokensLimit());
    auditLogService.saveAsync(...QUOTA_WARN...); // 复用现有审计，异步
}
```
- **开放 API 专属**：内部管理端不经此拦截器，测试对话不计费、不拦（与 token 计量口径一致）。
- 超额响应：403 + `reason=QUOTA_EXCEEDED`。`OpenErrorResponse.Error` 已有 `int code`（HTTP 状态码），故**新增可空 `reason` 字段**承载业务标识（限流 429 不带 reason，额度 403 带 `reason=QUOTA_EXCEEDED`，供客户端区分同状态码下的不同原因）。
- 检查发生在计量之前（异步 `ApiUsageService.record` 在对话完成后），故为"截至上次累计"的宽松检查。

## 6. 展示

- **租户查额度**：新增 `GET /v1/billing/quota`（管理端，面向租户用户 `TENANT_ADMIN`/`TENANT_MEMBER`，从会话取 `tenantId`）。平台管理员无租户上下文——接口返回空视图（`plan=null`），前端对平台管理员不展示该卡片。返回
  ```
  { plan, calls:{used,limit}, tokens:{used,limit}, warnThreshold, resetDate }
  ```
  `resetDate` = 下月 1 日。`BillingController` → `QuotaService`。
- **前端**：在**用量看板**（`UsagePage`，租户视角）加一个"本月额度"卡片区，展示 calls/tokens 的 已用/额度/百分比进度条；额度=0 显示"不限"。复用现有页面，轻量。
- **平台配额度**：复用现有平台配置页（键值），无新 UI。

## 7. 影响面

| 文件 | 改动 |
|---|---|
| `service/ConfigService.java` | 加 `getLong`（token 额度用 long 读取，避免 int 截断） |
| `service/QuotaService.java` | **新增**：额度解析、月度累计、检查 |
| `dto/QuotaCheckResult.java`（或 record 内嵌） | **新增**：检查结果 |
| `repository/projection/QuotaUsageRow.java` | **新增**：累计投影 |
| `repository/SysApiUsageRepository.java` | 新增按租户当月累计查询 |
| `config/OpenApiAuthInterceptor.java` | 限流后插额度检查（注入 `QuotaService` + `AuditLogService`） |
| `dto/open/OpenErrorResponse.java` | 增可空 `reason` 字段（用于 `QUOTA_EXCEEDED`；`code` 已表 HTTP 状态） |
| `controller/BillingController.java` | **新增**：`GET /v1/billing/quota` |
| `dto/QuotaViewDTO.java` | **新增**：额度展示返回体 |
| `frontend/src/api/billing.ts` | **新增**：额度查询 service |
| `frontend/src/pages/UsagePage.tsx` | 加"本月额度"卡片 |

## 8. 测试

- **单测（Mockito，`QuotaServiceTest`）**：
  - 额度解析：按 plan 取平台配置 / 配置缺失回落内置默认 / `0`=不限。
  - 超额判定：calls 超、tokens 超、都不超、都不限（快路径）。
  - 阈值告警：用量跨过 80% 边界。
- **集成测试（Testcontainers，`BillingQuotaIT`）**：播种租户（不同 plan）+ 平台额度配置 + `sys_api_usage` 当月用量，经开放 API 端点（如 `/open/v1/chat/completions`，`@MockitoBean ModelRouterService` 打桩模型）验证：未超额放行、超额 403 `QUOTA_EXCEEDED`、ENTERPRISE(0) 不限放行、跨租户隔离（A 租户超额不影响 B）、近阈值放行带 `X-Quota-Usage` 头。
- **`GET /v1/billing/quota` IT**：登录租户返回正确 used/limit/resetDate。
- `mvn test` 免 Docker、`mvn verify` 含新 IT 全绿；`pnpm build` 通过。

## 9. 关键决策与风险

- **超额用 403 + `QUOTA_EXCEEDED`**（区分限流 429）；ENTERPRISE **额度 0=不限**；**月度自然重置**（免定时任务）。
- **检查在请求前**：末次调用可能轻微超额（异步计量滞后），SaaS 常见、可接受；不引入同步计量以免拖慢主链路。
- **性能**：每次开放 API 请求多一次「当月累计」SQL。当月按天已聚合、行数小；若未来量大可加 Redis 计数缓存（本期不做）。fail 策略：`QuotaService.check` 内部异常时 **fail-open 放行**（与限流一致，不因额度组件故障拦截正常业务）+ log.warn。
- **口径一致**：额度计量与 `sys_api_usage`（开放 API 专属）同源，内部管理端对话不计入、不受额度限制。

## 10. 验收标准

- 平台在配置页设 `billing.quota.FREE.calls=N` 后，FREE 租户当月调用达 N 即被 403 `QUOTA_EXCEEDED` 拦截；PRO/ENTERPRISE 按各自额度；ENTERPRISE(0) 不受限。
- 用量达阈值（默认 80%）时请求放行但带 `X-Quota-Usage` 头 + 审计告警。
- 跨月后额度自动重置（新月用量从 0 起算）。
- 租户 `GET /v1/billing/quota` 返回正确的已用/额度/重置日；用量看板展示额度卡片。
- 额度限制**仅作用于开放 API**，内部管理端对话不受限。
- 单测 + IT 全绿；`mvn verify` 通过；`pnpm build` 通过。

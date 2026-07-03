---
title: Phase 2 · 开放 REST API 设计（含限流 + 用量计量）
author: ruan
date: 2026-07-03
status: 待评审
---

# Phase 2 · 开放 REST API 设计

## 1. 背景与范围

Phase 2「对外接入能力」含三个独立子系统，经确认**全部要做**，但按依赖关系**拆成 3 个顺序子项**，各自 设计→计划→实现 一轮：

1. **开放 REST API**（本文，地基）— 外部系统凭 appKey/appSecret 调用对话能力
2. **JS Widget SDK**（后续，依赖本子项）
3. **Webhook 事件推送**（后续）

**本子项范围**：开放 REST API 的 **认证 + 核心接口 + 限流 + 用量计量**，并顺带交付 Phase 3 遗留的**接入配置管理页**（展示 key、示例、限流设置、Widget 代码占位）。

**决策已定**：
- 限流：**Redis 版 Bucket4j**（分布式，按 appKey）
- 计量：**按 (appId, 日期) 聚合**
- 对外错误：**标准 HTTP 状态码**（401/403/404/429/500 + JSON body）

**非目标（本子项不做）**：Widget SDK、Webhook、账单结算逻辑本身（仅产出计量数据）、appSecret 加密存储改造（沿用现状，见风险）。

## 2. 现状与关键约束（基于代码核实）

- `Application`（表 `sys_application`）已有 `appKey`（`app_`+16 位，唯一）、`appSecret`（48 位十六进制，**明文存储**）、`enabled`、以及 Widget 用的 `avatar/welcomeMessage/themeColor`。`ApplicationRepository.findByAppKey` 已存在。密钥由 `ApplicationController` 用 UUID 生成，`/{id}/regenerate-secret` 可重置。
- `ChatService`（chat/streamChat/getSession/requestHandover/getSessionMessages）与 `ChatRequest`（sessionId?/userId/message/knowledgeBaseId?/stream）**可直接复用**，无需重写对话逻辑。
- **多租户隔离依赖 `TenantContext`**：内部请求由 `TenantInterceptor` 从 Sa-Token 会话写入 tenantId/appId。开放 API 不走 Sa-Token，**认证拦截器必须从 appKey 解析出 Application 的 tenant + id 写入 `TenantContext`**，下游 `ChatService` 的 Hibernate 租户过滤才生效。
- **Sa-Token 全局登录校验**：`SaTokenConfig` 对 `/**` 强制 `checkLogin`，仅排除 login/register/swagger/actuator。**必须把 `/open/v1/**` 加入排除列表**，否则开放 API 被 401。
- **错误约定冲突**：`GlobalExceptionHandler`（`@RestControllerAdvice`）把 `BusinessException` 映射为 HTTP 200 + body.code。开放 API 需**独立异常处理**产出标准 HTTP 状态码。
- **Redis 依赖未引入**：`pom.xml` 无 spring-data-redis / bucket4j；docker-compose 已预留 Redis 7。本子项需新增依赖并启用 Redis。
- 建表：`ddl-auto: update` 自动建新表 / 加新列，无需手写 DDL。

## 3. 总体设计原则

1. 开放 API 与内部 API **物理隔离**：路径前缀 `/open/v1/**`、独立包 `controller.open`、独立认证与异常处理。内部 `/v1/**`（Sa-Token）完全不受影响。
2. **复用而非重写**：对话/会话逻辑全部委托现有 `ChatService`；认证只负责鉴权 + 注入 `TenantContext`。
3. 限流/计量作为**横切关注点**：限流在拦截器（controller 之前），计量在对话完成后异步累加，均不侵入 `ChatService`。
4. YAGNI：限流额度先做「全局默认 + 每应用可选覆盖」；计量先做聚合三计数（调用/消息/token），token 取不到则记 0。

## 4. 功能设计

### 4.1 认证（appKey/appSecret）

**`OpenApiAuthService`（可单测的纯鉴权逻辑）**
- `Application authenticate(String appId, String apiKey)`：
  - appId/apiKey 任一为空 → 抛 `OpenApiException(401, "缺少 X-App-Id / X-Api-Key")`
  - `findByAppKey(appId)` 不存在 或 `appSecret` 不等（明文 `equals`）→ 抛 `OpenApiException(401, "无效的凭据")`
  - `enabled == false` → 抛 `OpenApiException(403, "应用已停用")`
  - 返回 Application

**`OpenApiAuthInterceptor`（HandlerInterceptor，注册于 `/open/v1/**`）**
- `preHandle`：读 `X-App-Id`/`X-Api-Key` 头 → `OpenApiAuthService.authenticate` → 成功则
  - `TenantContext.setTenantId(app.getTenant().getId())`、`TenantContext.setAppId(app.getId())`
  - 将 Application 暂存到 request attribute（供限流/计量/controller 取用）
  - 执行限流检查（见 4.3），超限抛 `OpenApiException(429, ...)`
- 认证/限流失败：捕获 `OpenApiException`，直接写出对应 HTTP 状态码 + JSON body（不进入 controller）
- `afterCompletion`：`TenantContext.clear()`

**Sa-Token 排除**：`SaTokenConfig` 的 `notMatch(...)` 增加 `/open/v1/**`。

### 4.2 端点（前缀 `/open/v1`，包 `controller.open`）

| 方法 | 路径 | 委托 | 说明 |
|------|------|------|------|
| POST | `/open/v1/chat/completions` | `ChatService.chat` | 非流式对话 |
| POST | `/open/v1/chat/stream` | `ChatService.streamChat` | SSE 流式（`text/event-stream`） |
| GET | `/open/v1/sessions/{sessionId}` | `getSession` + `getSessionMessages` | 会话信息 + 消息历史 |
| POST | `/open/v1/sessions/{sessionId}/handover` | `requestHandover` | 请求转人工 |

**请求/响应（对外 DTO，置于 `dto.open`，不直接暴露内部实体）**
- `OpenChatRequest`：`{ sessionId?, userId?, message(必填), knowledgeBaseId? }`（`stream` 由端点决定，不放 body）
- `OpenChatResponse`：`{ sessionId, reply, handoverSuggested? }`（由内部 `ChatResponse` 映射，最终字段以 `ChatResponse` 实际内容为准）
- `OpenSessionResponse`：`{ sessionId, status, messages:[{role, content, createdAt}] }`
- 端点从 request attribute 取当前 Application，用 `app.getId()` 等，不信任 body 里的 appId。

> 会话/终端用户模型：沿用现有语义——`sessionId` 为空则新建会话并在响应回传；`userId` 为调用方提供的外部终端用户标识（可空，Widget 侧可生成匿名 id）。

### 4.3 限流（Redis + Bucket4j，按 appKey）

- 新增依赖：`spring-boot-starter-data-redis`（Lettuce）+ Bucket4j 8.x（`bucket4j-core` + Redis/Lettuce 集成）。
- 配置 Redis 连接（`application.yml`：host/port，默认 localhost:6379，对齐 docker-compose）。
- `RateLimitService`：基于 `LettuceBasedProxyManager` 为每个 appKey 维护一个分布式令牌桶。
  - 额度解析：`Application.rateLimitPerMinute`（新增字段，Integer 可空）优先；为空取全局 `qingyu.care.openapi.default-rate-limit-per-minute`（默认 60）。
  - `boolean tryConsume(String appKey, int limitPerMinute)`：桶容量=limit、每分钟填满。
- 拦截器在认证后调用；`tryConsume` 返回 false → `OpenApiException(429, "请求过于频繁")`，响应头带 `Retry-After`。
- **降级**：Redis 不可用时记 WARN 并**放行**（fail-open，避免限流组件故障拖垮主链路）。

### 4.4 用量计量（sys_api_usage，按 appId+日期聚合）

**实体 `SysApiUsage`（表 `sys_api_usage`）**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long PK | |
| tenantId | Long | 冗余，便于按租户汇总 |
| appId | Long | |
| usageDate | LocalDate | 统计日 |
| callCount | long | 调用次数 |
| messageCount | long | 对话消息数 |
| tokenCount | long | token 数（best-effort，取不到记 0） |
| updatedAt | LocalDateTime | |

- 唯一约束 `(app_id, usage_date)`。不继承 `BaseTenantEntity`（避免自动过滤干扰汇总）。
- **`ApiUsageService.record(tenantId, appId, messages, tokens)`（`@Async`）**：原子累加。
  - 先 `@Modifying` 原生 UPDATE：`... SET call_count=call_count+1, message_count=message_count+:m, token_count=token_count+:t WHERE app_id=:a AND usage_date=:d`。
  - 影响行数为 0（当天首次）→ insert 新行；insert 遇唯一约束冲突则重试一次 UPDATE（并发兜底）。
  - 异常只记 WARN，不影响主流程。
- 调用点：open chat 端点在对话成功返回后调用 `record(...)`（messages=1；token 从内部 `ChatResponse` 的 usage 取，若 DTO 无该字段则本子项记 0 并在计划中列为"token 计量待接入"）。

### 4.5 接入配置管理页（Phase 3 遗留，并入本子项）

- 前端新增「接入配置」页（应用级，需先选应用）：
  - 展示 `appKey`；`appSecret` 脱敏 + 一键显示/复制 + **重置**（调既有 `POST /v1/applications/{id}/regenerate-secret`）
  - 限流额度设置（编辑 `rateLimitPerMinute`，走应用更新接口）
  - 示例 `curl`（自动填入当前 appKey/appSecret 与 `/open/v1/chat/completions`）
  - Widget 嵌入代码区（占位，标注"Widget 子项完成后可用"）
- 后端：`Application` 增加 `rateLimitPerMinute`（Integer 可空）；应用更新接口透传该字段（复用既有 `ApplicationController.update`，补字段拷贝）。
- 菜单/路由：「应用配置」组新增「接入配置」`/integration`（`RequireApp`）。

### 4.6 错误处理（标准 HTTP 状态码）

- `OpenApiException(int status, String message)`：承载 HTTP 状态码。
- 拦截器内的认证/限流错误：直接 `response.setStatus(...)` + 写 JSON（`{ "error": { "code", "message" } }`）。
- controller 内错误：`OpenApiExceptionHandler`（`@RestControllerAdvice(basePackages="com.qingyu.care.controller.open")` + `@Order(Ordered.HIGHEST_PRECEDENCE)`）：
  - `OpenApiException` → 对应 status
  - `ResourceNotFoundException` → 404
  - `MethodArgumentNotValidException` / `IllegalArgumentException` → 400
  - 其它 → 500
  - 统一返回 `ResponseEntity<{error:{code,message}}>`，**不影响** 内部 `GlobalExceptionHandler`（作用域隔离 + 优先级）。

## 5. 数据模型汇总

- 新增表 `sys_api_usage`。
- `sys_application` 加列 `rate_limit_per_minute INT NULL`。
- 均由 `ddl-auto: update` 自动生成。历史应用该列为 NULL → 用全局默认额度，向后兼容。

## 6. 依赖与配置新增

- `spring-boot-starter-data-redis`
- Bucket4j 8.x（core + Lettuce/Redis 集成）
- `application.yml`：
  - `spring.data.redis.host/port`
  - `qingyu.care.openapi.default-rate-limit-per-minute: 60`

## 7. API 契约示例

```
POST /api/open/v1/chat/completions
Headers: X-App-Id: app_xxx   X-Api-Key: <secret>   Content-Type: application/json
Body: { "message": "你们几点营业？", "userId": "visitor-123" }
200:  { "sessionId": "s_abc", "reply": "我们每天 9:00-21:00 营业。" }
401:  { "error": { "code": 401, "message": "无效的凭据" } }
429:  { "error": { "code": 429, "message": "请求过于频繁" } }   (Retry-After: 60)
```

## 8. 测试策略

新增聚焦单测（Mockito，不连库/Redis）：
- `OpenApiAuthServiceTest`：有效凭据返回 app；缺头/错 secret → 401；停用应用 → 403。
- `RateLimitService` 额度解析：应用级覆盖优先、为空回落全局默认。
- `ApiUsageServiceTest`：首次 insert、后续 UPDATE 累加、异常不外抛（mock repository）。
- `OpenApiExceptionHandler`：各异常 → 对应 HTTP status。
限流的 Redis 实际行为、端到端鉴权走人工联调（需本地 Redis）。

## 9. 兼容性与风险

- **appSecret 明文存储**：沿用现状（对外鉴权需可比对）。风险提示：DB 泄露即凭据泄露；后续可改为「仅展示一次 + 存哈希」，本子项不改，列入技术债。
- 限流 fail-open：Redis 故障时不拦截，优先保对话可用；需在监控中关注 Redis 健康。
- token 计量 best-effort：若内部 `ChatResponse` 未透出 usage，本子项 tokenCount 记 0，计划中标注后续接入。
- 开放 API CORS：现有全局 CORS 允许任意来源，满足 Widget 跨域调用；无需额外改动。

## 10. 交付物清单

**后端**：`controller.open` 包（Chat/Session 开放端点）、`dto.open`（Open 请求/响应）、`OpenApiAuthService`、`OpenApiAuthInterceptor`、`OpenApiException` + `OpenApiExceptionHandler`、`RateLimitService`（+Redis/Bucket4j 配置）、`SysApiUsage` + 仓库 + `ApiUsageService`；改 `SaTokenConfig`（排除 `/open/v1/**`）、`WebConfig`（注册拦截器）、`Application`（加 `rateLimitPerMinute`）、`ApplicationController.update`（透传字段）、`pom.xml` + `application.yml`。

**前端**：接入配置页 + api + 菜单/路由。

**测试**：上述 4 组聚焦单测。

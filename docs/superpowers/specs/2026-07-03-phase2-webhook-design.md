---
title: Phase 2 · Webhook 事件推送 设计
author: ruan
date: 2026-07-03
status: 待评审
---

# Phase 2 · Webhook 事件推送 设计

## 1. 背景与范围

Phase 2 第三（末）子项：当系统内发生关键业务事件时，向租户/应用预先登记的回调 URL 推送 JSON 事件，供外部工单/客服系统响应（如转人工请求后自动派单、会话结束后归档）。

**决策已定**（brainstorming 2026-07-03）：
- **用途聚焦**：只推**会话状态类**关键事件（转人工请求、会话结束），不做全量对话消息镜像。
- **投递可靠性**：**落库 + 自动重试**（至少一次语义），非尽力投递。
- **发射机制**：**Spring 事务事件**（`@TransactionalEventListener(AFTER_COMMIT)`）—— 领域代码与 Webhook 解耦，只推已提交状态。
- **配置界面**：后端字段 + `IntegrationPage` 基本配置区（URL/密钥/事件勾选/发送测试事件）；**不做**独立投递日志页。
- **签名**：请求头带 **HMAC-SHA256** 签名，接收方用应用 webhook 密钥校验真伪。

**本子项范围**：
1. 后端：`Application` 加 `webhookUrl/webhookSecret/webhookEvents` 三字段；新表 `sys_webhook_delivery`；领域事件发射（`ChatServiceImpl` 状态变更点）；事务事件监听器 → 落库 → 异步投递 → 定时重试；HMAC 签名；`RestClient` 投递；`@ConfigurationProperties` 配置；应用更新透传 + webhook 密钥重置端点 + 测试事件端点。
2. 管理后台 `IntegrationPage`：Webhook 配置区（回调 URL、密钥展示/重置、事件多选、发送测试事件）。

**非目标（本子项不做）**：全量对话消息镜像（每条 user/AI 回复推送）；独立投递日志/重放 UI；消息队列（Kafka/RabbitMQ/Redis Queue）；单应用多回调端点；事件顺序强保证；`agent_accepted`（客服接管）等其余事件类型（架构预留，需要时增量加）；appSecret/webhookSecret 加密存储（沿用明文现状）。

## 2. 现状与约束（基于代码核实）

- **事件源**：`ChatServiceImpl`（`service/impl/ChatServiceImpl.java`）的状态转换是天然触发点：
  - 转人工-关键词触发：`chat()` 内 `session.setStatus(WAITING_AGENT)`（约 L81，经 `handoverRuleService.checkKeywordTrigger()`）。
  - 转人工-主动请求：`requestHandover()` 内 `session.setStatus(WAITING_AGENT)`（约 L241），由 `ChatController` L64 调用。
  - 会话结束：`endSession()` 内 `session.setStatus(ENDED)`（约 L191）。
  - `SessionStatus` 枚举：`AI_PROCESSING/WAITING_AGENT/AGENT_PROCESSING/ENDED`（`enums/SessionStatus.java`）。
- **Application 实体**（`entity/Application.java`，表 `sys_application`）：已有 `appKey/appSecret/enabled/rateLimitPerMinute/allowedOrigins/tenant(ManyToOne,LAZY)` 等，**无 webhook 相关字段**（需新增）。`ApplicationRepository.findByAppKey`、`findByTenantIdOrderByCreatedAtDesc` 已存在。
- **多租户上下文**：`TenantContext`（`config/TenantContext.java`）是 ThreadLocal（tenantId/appId/userId），由拦截器 `preHandle` 设置、`afterCompletion` `clear()`。**异步线程读不到**——但 `@TransactionalEventListener(AFTER_COMMIT)` 在**提交后同线程**执行，此时 `TenantContext` 仍可读，可在此捕获 tenantId/appId 写入投递行；真正的 `@Async`/`@Scheduled` 投递线程只读投递行、不依赖 `TenantContext`。
- **异步机制**：`@EnableAsync` 已启用（`config/AsyncConfig.java`，默认线程池）。参考范式 `ApiUsageService.record()`（`@Async`、不加方法级 `@Transactional`、各仓库操作独立事务、冲突重试）。**无 `@EnableScheduling`**（需新增）。**未使用** Spring 事件（`ApplicationEventPublisher`/`@EventListener`）——本子项首次引入，正是适用场景。
- **HTTP 客户端**：项目**未**使用 `RestTemplate/WebClient/OkHttp`（仅 Spring AI 的 `ChatClient` 调模型）。需新增 HTTP 客户端投递 Webhook——选 **Spring Boot 3 内置 `RestClient`**（同步，契合 `@Async`/`@Scheduled` 线程，支持超时配置）。
- **持久层**：JPA `ddl-auto: update`（`application.yml` L30，PostgreSQL）。基类 `BaseTenantEntity`（`@MappedSuperclass`，带 tenantId/appId + `@Filter` 租户隔离 + `TenantEntityListener` 自动填充）；但**系统级表**（如 `SysApiUsage`）**不继承** BaseTenantEntity，显式存字段——`WebhookDelivery` 同理（投递发生在异步/定时线程，无租户过滤上下文，显式存 tenantId/appId 更稳）。`@CreationTimestamp`/`@UpdateTimestamp` 由 Hibernate 管理。
- **响应/异常约定**：内部管理 API 用 `ApiResponse{code,message,data,timestamp}`（`dto/ApiResponse.java`），全局 `GlobalExceptionHandler`（`@RestControllerAdvice`）；开放 API 用 `OpenApiException` + `OpenApiExceptionHandler`。Controller 包：`controller`（内部）vs `controller.open`（开放）。Webhook 配置端点属**内部管理 API**（`ApplicationController`，须登录 + 租户上下文），非开放 API。
- **配置约定**：`application.yml` 自定义前缀 `qingyu.care.*`（如 `openapi.default-rate-limit-per-minute`）；现有多用 `@Value` 注入，无 `@ConfigurationProperties` 先例——本子项参数较多（重试/超时/退避/扫描间隔），引入一个小的 `@ConfigurationProperties(prefix="qingyu.care.webhook")` 类作合理改进。

## 3. 总体设计原则

1. **解耦**：领域代码（`ChatServiceImpl`）只 `publishEvent(领域事件)`，不认识 Webhook；Webhook 全部逻辑在监听器/服务侧。
2. **提交安全**：监听用 `@TransactionalEventListener(phase=AFTER_COMMIT)`，只在事务真正提交后才落投递行——不会推送随后回滚的状态。
3. **至少一次 + 幂等**：投递落库、失败自动重试；每条投递带唯一 `Delivery-Id`，约定接收方按此幂等去重。
4. **安全默认**：`webhookUrl` 为空 = 未启用，不产生任何投递；只投递**已订阅**的事件类型。
5. **可独立测试**：签名、退避排期、并发认领、订阅过滤、状态流转均为纯逻辑，单测（Mockito）覆盖；HTTP 投递用 stub/mock 校验请求。

## 4. 后端设计

### 4.1 Application 新增字段

`entity/Application.java` 新增（`ddl-auto:update` 自动加列，旧行 NULL 向后兼容）：
- `webhookUrl`（`@Column(name="webhook_url", columnDefinition="TEXT")`）—— 回调地址，为空表示未启用 Webhook。
- `webhookSecret`（`@Column(name="webhook_secret", length=128)`）—— HMAC 签名密钥；启用/重置时由服务端生成（复用 appSecret 的 UUID/十六进制生成方式）。
- `webhookEvents`（`@Column(name="webhook_events", columnDefinition="TEXT")`）—— 逗号分隔的订阅事件类型，如 `session.handover_requested,session.ended`。

### 4.2 事件目录（v1）

| 事件类型 | 触发点 | data 负载字段 |
|---|---|---|
| `session.handover_requested` | `chat()` 关键词触发 + `requestHandover()` 主动请求 | `sessionId, appId, trigger:"keyword"\|"user_request", occurredAt` |
| `session.ended` | `endSession()` | `sessionId, appId, occurredAt` |
| `webhook.test` | 管理后台「发送测试事件」按钮（不由领域触发） | `appId, occurredAt, message:"测试事件"` |

> 架构对新增事件类型是 O(1)：领域多发一个事件 + 事件目录多一项即可。

### 4.3 领域事件与发射

- 定义内存领域事件（普通 POJO，如 `SessionHandoverRequestedEvent`、`SessionEndedEvent`，或统一 `WebhookDomainEvent{eventType, sessionId, appId, tenantId, trigger, occurredAt}`）。首版用**统一事件类**携带 `eventType` 判别，减少类爆炸。
- `ChatServiceImpl` 注入 `ApplicationEventPublisher`，在三处状态变更后 `publisher.publishEvent(...)`：关键词转人工、主动 `requestHandover`、`endSession`。发布在各自 `@Transactional` 方法内（事件先入内存队列，提交后才触发监听）。
- 捕获租户/应用：发布事件时把 `session` 的 appId/tenantId 一并带上（session 实体已有），避免监听器再依赖 `TenantContext`。

### 4.4 事务事件监听 → 落库（WebhookDispatcher）

- `WebhookDispatcher` 上的 `@TransactionalEventListener(phase=AFTER_COMMIT)` 方法接收领域事件：
  1. 按 appId 查 `Application`；`webhookUrl` 为空或事件类型不在 `webhookEvents` → 直接返回（不落库）。
  2. 构造 payload JSON 信封（§4.6），创建 `WebhookDelivery` 行（`status=PENDING, attempts=0, nextAttemptAt=now, targetUrl=快照`）保存。
  3. 触发 `@Async webhookSender.deliver(deliveryId)` 首次即时投递（定时扫描器作兜底）。
- 监听器本身不做 HTTP（保持快、不阻塞提交后线程）。

### 4.5 投递、重试与并发（WebhookSender）

- `@Async deliver(Long deliveryId)`：
  1. **认领**：`UPDATE sys_webhook_delivery SET status='PROCESSING' WHERE id=? AND status='PENDING'`；受影响行数 ≠ 1 则放弃（已被即时投递或其它实例/扫描器领走），防重复投递。
  2. 读投递行 + 按 appId 查 `Application` 取当前 `webhookSecret`（密钥即时查、URL 用行内快照，便于日志留痕）。
  3. `RestClient` POST：body=payload JSON，超时 5s，请求头见 §4.6。
  4. **2xx** → `status=SUCCESS, responseStatus=码`；**非 2xx / 超时 / 异常** → `attempts++`：若 `attempts < maxAttempts` → `status=PENDING, nextAttemptAt=now+backoff[attempts], lastError=摘要`；否则 `status=FAILED`。
- **定时重试**：`@Scheduled(fixedDelayString=...)` 每 30s 扫 `status='PENDING' AND nextAttemptAt<=now`（限量，如每次 100 条），逐条走同一 `deliver` 认领+投递路径。需 `@EnableScheduling`。
- **退避**：`maxAttempts=5`，`backoff-minutes=[1,5,15,30,60]`（`attempts` 从 0 起，第 n 次失败后等 `backoff[min(n,len-1)]` 分钟）。
- **多实例**：认领的条件更新（`AND status='PENDING'`）+ 至少一次语义 + 接收方按 `Delivery-Id` 幂等，容忍偶发重复；不引入分布式锁（YAGNI）。

### 4.6 Payload 信封与签名

- **信封**（`application/json`，UTF-8）：
  ```json
  {
    "id": "<deliveryId 或 UUID>",
    "event": "session.handover_requested",
    "createdAt": "2026-07-03T08:00:00Z",
    "data": { "sessionId": "...", "appId": 12, "trigger": "user_request" }
  }
  ```
- **请求头**：
  - `Content-Type: application/json`
  - `X-Qingyu-Event: <事件类型>`
  - `X-Qingyu-Delivery-Id: <投递唯一 id>`（幂等键）
  - `X-Qingyu-Timestamp: <毫秒时间戳>`（防重放，接收方可校验时间窗）
  - `X-Qingyu-Signature: sha256=<hex(HMAC_SHA256(webhookSecret, rawBody))>`
- **签名算法**：对**原始请求体字节**做 HMAC-SHA256（对齐 GitHub/Stripe 惯例），密钥为应用 `webhookSecret`。接收方以同法计算比对。

### 4.7 管理端点（ApplicationController，内部 API）

- `update` 透传 `webhookUrl`、`webhookEvents`（非空才覆盖，与现有 `rateLimitPerMinute` 透传风格一致）；**`webhookSecret` 不随普通更新覆盖**。
- 新增 `POST /v1/applications/{id}/regenerate-webhook-secret` → 重新生成并返回新 `webhookSecret`（复用 regenerate-secret 模式，租户校验归属）。
- 新增 `POST /v1/applications/{id}/webhook/test` → 构造 `webhook.test` 事件，**同步投递一次并返回结果**（`{delivered:boolean, responseStatus, error}`），供 UI 显示成功/失败。测试仅验连通性：**不受 `webhookEvents` 订阅过滤限制**、**不落 `sys_webhook_delivery` 表**（避免污染投递记录），只需已配置 `webhookUrl`（否则 → 400）。

### 4.8 配置（WebhookProperties）

`@ConfigurationProperties(prefix="qingyu.care.webhook")`：`maxAttempts=5`、`timeoutMs=5000`、`retryScanIntervalMs=30000`、`backoffMinutes=[1,5,15,30,60]`、`testEnabled=true`。`@EnableScheduling` 加在配置类或启动类。

## 5. 数据模型

- `sys_application` 加列：`webhook_url TEXT NULL`、`webhook_secret VARCHAR(128) NULL`、`webhook_events TEXT NULL`（`ddl-auto:update` 自动加，旧行 NULL = 未启用）。
- 新表 `sys_webhook_delivery`（实体 `WebhookDelivery`，不继承 `BaseTenantEntity`）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK | 投递记录 id（兼作 Delivery-Id） |
| `tenant_id` | Long | 冗余存，便于按租户查/清理 |
| `app_id` | Long | 所属应用 |
| `event` | VARCHAR | 事件类型 |
| `target_url` | TEXT | 投递目标 URL 快照 |
| `payload` | TEXT | 信封 JSON |
| `status` | VARCHAR | PENDING/PROCESSING/SUCCESS/FAILED |
| `attempts` | int | 已尝试次数 |
| `next_attempt_at` | timestamp | 下次可投递时间 |
| `response_status` | Integer | 末次响应码（可空） |
| `last_error` | TEXT | 末次错误摘要（可空） |
| `created_at`/`updated_at` | timestamp | Hibernate 管理 |

- `WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long>`：`findByStatusAndNextAttemptAtLessThanEqual(status, now, Pageable)` 供扫描器；条件认领用 `@Modifying @Query` 的 `claim(id)`。

## 6. 管理后台（IntegrationPage 增强）

在现有 allowedOrigins / 嵌入片段区块后，新增 **Webhook 配置区**：
- 回调 URL 输入 + 保存（走 `updateWebhook`）。
- Webhook 密钥：掩码展示 + 「重置密钥」按钮（调 regenerate-webhook-secret，复用 appSecret 重置交互）。
- 订阅事件：多选（`session.handover_requested` / `session.ended`），保存进 `webhookEvents`（逗号分隔，与后端一致）。
- 「发送测试事件」按钮 → 调 `webhook/test`，toast 显示成功（含响应码）或失败原因。
- `frontend/src/api/integration.ts` 加 `updateWebhook(id,{webhookUrl,webhookEvents})`、`regenerateWebhookSecret(id)`、`sendWebhookTest(id)`；`Application` 类型加 `webhookUrl?/webhookSecret?/webhookEvents?`。

## 7. 测试策略

- **单测（Mockito，无 Spring context，快）**：
  - 签名：给定 secret + body，HMAC-SHA256 输出稳定、与预期十六进制一致。
  - 退避排期：`attempts` → `nextAttemptAt` 增量正确；耗尽转 FAILED。
  - Dispatcher 订阅过滤：URL 空 / 事件未订阅 / 正常，三分支落库与否正确。
  - Sender 状态流转：2xx→SUCCESS；4xx/5xx/超时→PENDING+attempts++/最终 FAILED。
  - 认领并发：`claim` 受影响行数语义（模拟已被领走返回 0 则放弃）。
  - HTTP 用 `MockRestServiceServer`（RestClient 可 stub）校验 URL/头/签名/body。
- **事件链**：用 `@RecordApplicationEvents` 或直接单测 Dispatcher 方法验证「领域事件→落库」。
- **手工联调**：配置一个 webhook.site 之类的回调地址，触发转人工/结束/测试事件，观察签名与重试。

## 8. 安全考量

- **签名验证**：接收方**必须**用 `webhookSecret` 校验 `X-Qingyu-Signature`，拒绝伪造/篡改请求；`X-Qingyu-Timestamp` 可用于拒绝过期重放。
- **密钥保护**：`webhookSecret` 与 `appSecret` 同等对待——`getById` 随实体返回给**本租户管理员**（其自有密钥），前端 `IntegrationPage` 掩码展示、可重置；租户隔离保证不泄露给其它租户。不新增加密存储（沿用 appSecret 明文现状，见 §9 技术债）。
- **SSRF/内网访问**：回调 URL 由**租户自行登记**（可信输入），首版不做出站地址过滤；如需防打内网，后续可加 URL 白名单/私网段拦截（列后续，非本子项）。
- **至少一次**：接收方需按 `X-Qingyu-Delivery-Id` 幂等去重（文档明示）。
- **失败隔离**：投递异常只落 `last_error` + 重试，绝不影响触发它的业务事务（AFTER_COMMIT + 独立线程）。

## 9. 风险与非目标

- **非目标**：全量消息镜像、投递日志/重放 UI、消息队列、多回调端点、事件顺序强保证、`agent_accepted` 等额外事件、SSRF 出站过滤、密钥加密存储（均列后续）。
- **风险**：
  - **多实例定时器重复投递**：认领条件更新 + 幂等约定兜底；真多副本部署再考虑 `ShedLock`/分布式锁（后续）。
  - **投递表增长**：SUCCESS/FAILED 记录长期累积；首版不做归档清理，列后续（可加定时清理 N 天前终态记录）。
  - **`webhookSecret` 明文存储**：沿用 appSecret 现状，作为已知技术债，非本子项范围。
  - **同步 RestClient 占用 @Async 线程**：超时封顶 5s + 线程池；量大时可调池或转异步客户端（后续）。

## 10. 交付物清单

**后端**：`Application` 加 `webhookUrl/webhookSecret/webhookEvents`；`WebhookDelivery` 实体 + `WebhookDeliveryRepository`（含 `claim` 与扫描查询）；领域事件类 + `ChatServiceImpl` 三处 `publishEvent`；`WebhookDispatcher`（`@TransactionalEventListener(AFTER_COMMIT)` 落库）；`WebhookSender`（`@Async` 投递 + 认领 + 退避）；`@Scheduled` 重试扫描 + `@EnableScheduling`；`WebhookSignature`（HMAC-SHA256）；`RestClient` 配置（超时）；`WebhookProperties`（`@ConfigurationProperties`）；`ApplicationController` 透传 webhook 字段 + `regenerate-webhook-secret` + `webhook/test` 端点；对应单测。

**管理后台**：`IntegrationPage` Webhook 配置区（URL/密钥重置/事件多选/发送测试）；`integration.ts` 加方法；`Application` 类型加字段。

**文档**：接收方验签说明（签名头、算法、幂等键）可写入 `IntegrationPage` 说明文案或 README。

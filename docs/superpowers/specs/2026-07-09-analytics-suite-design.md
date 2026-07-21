---
title: 分析三件套（对话质量评估 + 热点问题统计 + 知识覆盖率）设计
author: ruan
date: 2026-07-09
status: 已实现
---

# 分析三件套（对话质量评估 + 热点问题统计 + 知识覆盖率）设计

Phase 4.4「分析与优化」的前三个子项，一个 spec 闭环（共享埋点与 rollup 基建）。第四子项 A/B 测试体量独立（分流、实验配置、指标对比），另立 spec 下轮做。

## 1. 背景与范围

现状：Phase 3 已有用量看板（`AnalyticsController` + `AnalyticsService`，实时 native SQL 聚合、角色自适应视角），但只回答「用了多少」，回答不了「答得好不好」「用户在问什么」「知识库够不够」。原始数据侧已有基础：`ChatMessage` 存每条 AI 回答的 `confidenceScore`，`ChatSession` 有状态/标签可推导转人工与 AI 解决，chunk metadata 里有 `documentId` 可做文档归因。

**范围**：
- **对话质量评估**：规则指标（置信度分布、兜底回复率、转人工率、AI 解决率）+ 消息级用户反馈（赞/踩，Widget/开放 API/管理后台三入口）。
- **热点问题统计**：zhparser 关键词词频 TopN + 高频原始问句 TopN。
- **知识覆盖率**：问题侧（提问命中率趋势 + 未命中问题清单=知识缺口）+ 文档侧（文档命中排行 + 零命中文档=死知识）。
- **配套改进（前置）**：流式对话（`streamChat`）补消息落库——开放 API SSE（`/open/v1/chat/stream`）与后台流式端点均不落消息，不补则流式对话缺席统计（注：Widget 当前走同步 `completions`，不受此项影响）；AI 消息落 `hit_document_ids` 与 `tokenUsage` 埋点。

**非目标（YAGNI）**：
- A/B 测试（独立 spec）。
- LLM-as-judge 质量评分、向量聚类/LLM 归类热点（词频方案先跑，语义化留演进）。
- 混合检索关键词路命中的置信度语义（既有已知局限，本轮不解）。
- 实时（当日）统计——rollup 为日粒度 T+1，看板明示统计截止日。

## 2. 需求决策（已与用户确认）

| 决策点 | 结论 |
|---|---|
| 本轮范围 | **分析三件套**（质量/热点/覆盖率），A/B 测试下轮独立 spec |
| 质量评分来源 | **规则指标 + 用户反馈**（消息级赞/踩）；不做 LLM 评分 |
| 热点聚合方式 | **zhparser 关键词词频**（与混合检索基建同源，配置名 `chinese_zh`）+ 高频问句 TopN |
| 覆盖率口径 | **两侧都做**：问题侧命中率+缺口清单、文档侧命中排行+死知识清单 |
| 实现架构 | **方案 B：日粒度 rollup 预聚合表 + 定时任务**（查询恒快、可长期留存） |
| 流式落库 | **本轮一并补齐**（分析数据完整性的前提） |

## 3. 关键设计约束

### 3.1 rollup 实体不继承 BaseTenantEntity
定时任务线程没有 `TenantContext`，Hibernate Filter 与 `TenantEntityListener` 自动回填都不可用。三张 stat 表沿用 `WebhookDelivery`/`SysApiUsage` 模式：**显式 `tenant_id`/`app_id` 字段 + 查询侧显式 where 条件（native/@Query）**。

### 3.2 流式落库运行在 Reactor 线程
`doFinally` 回调不在 HTTP 线程，无 `TenantContext`。`ChatMessage` 不继承 `BaseTenantEntity`（靠 `session` 关联定位租户），persist 不受过滤器影响；但 **session 引用必须在订阅前（请求线程）取好**，回调内不得再做依赖上下文的查询。与 `OpenChatController.stream` 里「订阅前捕获 tid/aid」是同一惯例。

### 3.3 rollup 幂等
调度可能重跑、回填可能重叠：聚合以「(tenant_id, app_id, stat_date [, keyword/document_id]) 唯一键，先删后插」保证幂等，单日失败只记日志不阻断后续日期。

### 3.4 反馈的 messageId 传递（流式）
SSE 目前是 `Flux<String>` 纯 data 事件，客户端拿不到落库后的 messageId，无从提交反馈。方案：`/open/v1/chat/stream` 改为 `Flux<ServerSentEvent<String>>`——正文 chunk 仍为默认（message）事件、payload 不变，**流末尾追加一个 `event: meta` 事件**，data 为 `{"messageId": ..., "sessionId": ...}`。不识别 meta 事件的旧客户端自动忽略，向后兼容；Widget 解析器同步适配并渲染赞/踩按钮。

## 4. 架构

### 4.1 埋点与原始数据（前置改造）
- `ChatMessage` 新增：
  - `feedback`（SMALLINT：1=赞 / -1=踩 / NULL=未评价）；
  - `hit_document_ids`（VARCHAR，JSON 数组存命中文档 ID，从检索命中 metadata `documentId` 去重提取）。不动既有 `hitChunkIds`（语义是 chunk，文档统计要的是 documentId）；
  - `fallback`（BOOLEAN，兜底回复标记）。**兜底早退路径目前只存用户消息、不存 AI 回复**，本轮补落库并置位此标记，`fallback_count` 才有数据来源。
- `saveMessages` 扩展：AI 消息落 `hit_document_ids` 与 `tokenUsage`（当前两者都未落）。
- `streamChat` 补落库：流结束（`doFinally`）后将用户提问 + 聚合的完整 AI 回复落库，含置信度、命中文档；内容累积用与 `meterContentStream` 同惯例的 static 纯方法，便于免容器单测。
- `ChatResponse` / `OpenChatResponse` 增加 `messageId`（落库后的 AI 消息 ID）——非流式客户端提交反馈的前提；流式经 meta 事件传递（见 3.4）。

### 4.2 反馈链路
- 开放 API：`POST /open/v1/chat/feedback`，body `{sessionId, messageId, feedback}`（feedback ∈ {1, -1}；可重复提交，覆盖语义）。校验 messageId 归属该 sessionId 且 session 归属当前 app（防跨会话/跨租户投毒）。
- 管理后台：`POST /v1/chat/session/{sessionId}/message/{messageId}/feedback`（沿用 ChatController 既有路径惯例，会话详情页人工标注）。
- Widget：当前走同步 `completions`，直接用响应新增的 `messageId` 在每条 AI 回复下渲染赞/踩按钮 → 调开放反馈端点（meta 事件仅服务第三方 SSE 接入方）。**改动 `widget/src/` 后必须重新 build 并提交 `widget.js`**。

### 4.3 rollup 预聚合（三张日粒度表）

| 表 | 唯一键 | 关键列 |
|---|---|---|
| `stat_quality_daily` | tenant_id + app_id + stat_date | ai_msg_count、avg_confidence、low_confidence_count、fallback_count、handover_count、resolved_count、ended_count、feedback_up、feedback_down、question_count、hit_question_count |
| `stat_hot_keyword_daily` | tenant_id + app_id + stat_date + keyword | freq（当日用户提问经 `to_tsvector('chinese_zh', ...)` 分词聚合，仅存每租户+应用当日 TopN，N 默认 50 可配） |
| `stat_doc_hit_daily` | tenant_id + app_id + stat_date + document_id | hit_count（AI 消息 `hit_document_ids` 展开计数） |

- 问题侧命中判定：AI 消息 `hit_document_ids` 非空即「命中」；`hit_question_count / question_count` 即命中率。
- 口径固定：`low_confidence_count` 按 `confidenceScore < 0.5` 固定阈值统计（不随租户转人工规则漂移，保证历史可比）；`fallback_count` 按 `fallback = true` 计数。
- `AnalyticsRollupScheduler`（`scheduler/`）：每日凌晨聚合前一日（cron 可配，配置前缀 `qingyu.care.analytics`），按 3.3 幂等。
- 手动回填端点（平台管理员）：`POST /v1/analytics/rollup/backfill?from=&to=`，上线回填历史 + 故障补数，逐日调用同一幂等聚合。
- **不做 rollup 的两类明细**（实时查）：
  - 未命中问题清单：分页查 `ChatMessage`（AI 消息无命中所对应的用户提问，时间窗过滤）；
  - 零命中文档：`document` 表左连 `stat_doc_hit_daily`（窗口内无命中记录且已启用的文档）。
- 高频问句 TopN：实时按 `content` 归组计数（时间窗内 user 消息，`trim` 后等值归组），量级由窗口限制，不入 rollup。

### 4.4 查询 API（扩展 AnalyticsController）
角色自适应复用 `getUsage` 套路：平台管理员跨租户聚合、租户用户限本租户、`appId` 下钻仅租户视角有效；`days ∈ {7,30,90}` 非法回落 30。

| 端点 | 返回 |
|---|---|
| `GET /v1/analytics/quality?days=&appId=` | 质量汇总（各率值 + 反馈数）+ 按日趋势 |
| `GET /v1/analytics/hot-topics?days=&appId=` | 关键词 TopN（窗口内按词求和）+ 高频问句 TopN |
| `GET /v1/analytics/coverage?days=&appId=` | 命中率趋势 + 文档命中排行 TopN + 零命中文档清单 |
| `GET /v1/analytics/coverage/misses?days=&page=&size=` | 未命中提问明细（分页） |
| `POST /v1/analytics/rollup/backfill?from=&to=` | 手动回填（仅平台管理员，`@AuditLog`） |

### 4.5 前端
`UsagePage` 扩展为分析页 Tabs：**用量（现有）/ 质量 / 热点 / 覆盖率**。recharts 复用现有图表模式；热点关键词用横向条形图（不引词云依赖）；覆盖率页含未命中清单表格（分页）与零命中文档表格；质量页 AI 回复旁已有数据即可，会话详情页（`SessionPage`）加人工赞/踩标注按钮。

## 5. 测试策略

- **单测（Surefire，优先）**：
  - rollup 聚合服务的计算逻辑（Mockito mock repository 行数据）；
  - 流式落库纯方法（内容累积 + 命中文档提取，static 免容器）；
  - feedback 参数校验与归属校验分支；
  - 各查询 API 的角色视角分支（mock AuthService）。
- **IT（Failsafe，`mvn verify`）**：
  - `AnalyticsRollupIT`：播种两租户消息 → 跑聚合 → 断言隔离与数值正确 → 重跑断言幂等（种子数据 tenantId+appId 双设，appFilter 无 NULL 兜底）；
  - `ChatFeedbackIT`：开放端点双路径鉴权（API Key / Origin）+ 跨会话 messageId 拒绝；
  - `HotKeywordRollupIT`：真实 zhparser 分词聚合（自定义镜像已带 zhparser）；
  - 流式落库 IT：MockMvc 打 SSE 端点后断言消息落库、末尾 meta 事件存在。

## 6. 影响面

| 位置 | 改动 |
|---|---|
| `entity/ChatMessage` | 加 `feedback`、`hitDocumentIds`、`fallback` 列（ddl-auto 自动加列，存量行 NULL 安全） |
| `dto/ChatResponse`、`dto/open/OpenChatResponse` | 加 `messageId` 字段 |
| `entity/`（新） | `StatQualityDaily`、`StatHotKeywordDaily`、`StatDocHitDaily`（均不继承 BaseTenantEntity） |
| `service/impl/ChatServiceImpl` | `saveMessages` 埋点扩展；`streamChat` 落库；SSE meta 事件所需 messageId 回传 |
| `controller/open/OpenChatController` | `/stream` 改 `ServerSentEvent` + meta 事件；新增 `/feedback` |
| `controller/AnalyticsController` | 新增 4 个查询端点 + 回填端点 |
| `service/` | `AnalyticsRollupService`（聚合）、`AnalyticsService` 扩展（查询）、feedback 处理 |
| `scheduler/` | `AnalyticsRollupScheduler`（新） |
| `frontend/` | `UsagePage` → Tabs 分析页；`SessionPage` 加标注；`api/analytics.ts` 扩展 |
| `widget/` | 赞/踩按钮 + meta 事件解析；**重新 build 提交 widget.js** |

## 7. 已知取舍

- 热点粒度是「词 + 原句」而非语义主题；同义不同表述的问句不会归并。
- rollup 为 T+1：看板不含当日数据，页面明示统计截止日期。
- 高频问句按内容精确归组，改一个字就算两句——配合关键词词频互补使用。
- 反馈可被端用户重复覆盖（同一 messageId 最新值生效），不做防刷（消息归属校验已挡跨会话投毒，单会话内刷自己消息的动机弱）。

## 8. 实施偏差与口径澄清记录

Task 1-8 实施与验收过程中发现的、与本 spec 字面描述有出入或需要补充澄清的四点，逐条记录以便后续维护对齐：

1. **错误语义澄清**：反馈接口（开放 `POST /open/v1/chat/feedback`、后台 `POST /v1/chat/session/{sessionId}/message/{messageId}/feedback`）的错误分两类——
   - **404**：归属越权，覆盖「消息不存在」「messageId 与 sessionId 不匹配」「跨应用」三种情况，统一按 404 处理、不区分具体原因，避免向调用方泄露消息是否存在（`ChatFeedbackService.submitFeedback` 三处 `ResourceNotFoundException` 均传入相同的「消息」+ messageId 措辞）；
   - **400**：业务非法，覆盖「`feedback` 不在 `{1, -1}` 值域」「目标消息非 `ASSISTANT` 角色（不可评价用户自己的提问）」两种情况，均抛 `BusinessException`（默认 code=400）。

   两类异常在管理端与开放 API 两条链路上的 HTTP 语义不同：`BusinessException` 经 `GlobalExceptionHandler.handleBusinessException` 处理时不带 `@ResponseStatus`，返回 **HTTP 200 + body `{code:400,...}`**（管理后台前端按 body code 判断）；经 `OpenApiExceptionHandler.handleBusiness` 处理时直接 `ResponseEntity.status(e.getCode())`，返回**真实 HTTP 400**。`ResourceNotFoundException` 在两条链路下均返回真实 HTTP 404（`GlobalExceptionHandler` 用了 `@ResponseStatus(NOT_FOUND)`，`OpenApiExceptionHandler` 显式 `status(404)`）。详见第 3 条对 `OpenApiExceptionHandler` 缺口的记录——此差异正是该缺口暴露的诱因。

2. **质量指标归因口径**：`stat_quality_daily` 的 `session_count`/`handover_count`/`resolved_count`/`ended_count` 四列由 `AnalyticsRollupDao.insertQuality` 的会话侧子查询按 **`chat_session.created_at`（会话创建日）**归因，而非会话状态变化（转人工/结束）实际发生的日期——即一个会话若创建于 D 日、次日才转人工或结束，`handover_count`/`ended_count` 仍计入 D 日而非次日。这一口径带来两个连带影响：
   - `AnalyticsRollupScheduler` 每日定时任务固定重跑「昨日 + 前日」两天（近 2 天），用于吸收创建日与状态变化日相邻的短期漂移；但会话若创建后超过 2 天才终态化（长尾），对应 D 日的 rollup 行不会被定时任务自动补算，需依赖管理员手动调用 `POST /v1/analytics/rollup/backfill?from=&to=` 重跑该日；
   - `AnalyticsRollupService.backfill` 未对 `to` 做「不得晚于今日」的上界校验（仅校验 `from<=to` 与区间跨度 ≤366 天），因此理论上可以传入 `to=今日` 把当日尚未走完生命周期的会话提前计入 rollup——这是管理员主动触发才会发生的有意行为，非缺陷，但使用回填端点时需注意当日数据尚不完整。

3. **实施中发现并修复的生产缺陷**：`OpenApiExceptionHandler`（`controller/open/OpenApiExceptionHandler.java`，`@RestControllerAdvice(basePackages="com.qingyu.care.controller.open")` + `@Order(HIGHEST_PRECEDENCE)`，仅作用于开放 API 包、优先级高于全局的 `GlobalExceptionHandler`）在 Task 6 集成测试补齐前**未显式处理 `BusinessException`**，导致该异常落入其兜底的 `Exception.class` 处理器，被误判并返回 HTTP 500——具体表现为开放反馈端点 `feedback` 值非法（不在 `{1,-1}`）或对非 `ASSISTANT` 消息评价时，本应返回 400 却返回 500。已在 Task 6（commit `c5816e0`）补 `handleBusiness(BusinessException)` handler，按 `e.getCode()` 映射真实 HTTP 状态码（该 code 本身即合法 HTTP 状态码，`BusinessException` 默认构造为 400），并配 `OpenApiExceptionHandlerTest` 单测覆盖回归。

4. **流式已知取舍（Task 2 落地时确认）**：
   - 流式 AI 消息落库时 `tokenUsage` 固定为 `null`——Spring AI 流式响应逐 chunk 到达，不像同步响应那样在末尾附带完整 usage 统计，本轮未额外接入流式 token 计数管线，故流式对话不贡献 token 用量到分析指标（不影响开放 API 的计量口径，计量走独立的 `onTokensUsed` 回调，与本 spec 的落库统计是两条链路）；
   - 流式响应过程中若客户端主动取消（cancel）或链路中途出错（error），已产生的半截 AI 回复内容**不落库**——`withStreamPersistence`（`ChatServiceImpl`）用 `doOnComplete` 而非 `doFinally` 挂落库回调，只有流正常完成才触发，异常/取消场景不会执行，避免半截、误导性的回复污染统计与会话历史；
   - 流式落库使用 `TransactionTemplate` 显式管理事务边界（而非 `@Transactional`，因回调运行在 Reactor 线程、脱离原始请求线程的代理调用链），保证「用户提问 + AI 完整回复」两条消息落库的原子性——要么都成功、要么都不写，不出现只落用户提问缺 AI 回复的半截会话。

5. **覆盖率命中率分母口径变更**：spec 4.3 原写「`hit_question_count / question_count`」；实现改为 `hit_question_count / ai_msg_count`（DTO 字段名 `answeredCount`）——存在转人工/AI 静默时用户提问无对应 AI 回复，分子分母同取「AI 回复事件」口径才自洽。`question_count` 列仍聚合入库但暂无消费方，留作后续展示扩展。

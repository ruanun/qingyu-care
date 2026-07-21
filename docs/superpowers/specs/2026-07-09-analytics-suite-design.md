---
title: 分析三件套（对话质量评估 + 热点问题统计 + 知识覆盖率）设计
author: ruan
date: 2026-07-09
status: 设计完成，待实现
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

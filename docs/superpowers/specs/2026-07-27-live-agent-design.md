# 真人坐席（Live Human Agent）MVP 设计

- 日期：2026-07-27
- 分支：`feat/live-agent`
- 作者：ruan
- 来源：第一档高价值能力缺口 #3（#1 对话内工具调用、#2 QA 问答对知识库已合入 master）。

## 1. 背景与目标

现有代码已有真人坐席的**骨架**：会话状态机（`AI_PROCESSING`/`WAITING_AGENT`/`AGENT_PROCESSING`/`ENDED`/`TIMEOUT`）、转人工触发（关键词 `checkKeywordTrigger` / 主动请求 `requestHandover` → 置 `WAITING_AGENT` + 发 `session.handover_requested`）、坐席 API（`acceptSession`/`agentReply`/`getWaitingAgentSessions`/`getSessionMessages`）、`chat()` 的 AI 静默、前端 `AgentPage` 工作台、租户级隔离。

但**最后一公里全断**，端到端不可用：
- **实时消息通道整体缺失**：`agentReply` 只落库、不推送；widget 无轮询/无长连；全库无任何 WebSocket/SSE 下行实现。坐席回复送不到用户；坐席端接管后用 mock 数据、收不到用户新消息。
- **SSE 渠道下接管形同虚设**：widget 实际走 `/open/v1/chat/stream`（SSE），而 `streamChat()` 既不做关键词转人工、也不做 AI 静默 → 接管后 AI 仍抢答。
- **坐席身份/权限空白**：无 AGENT 角色，`agentId` 是无约束裸 Long、前端写死 `1`、不绑登录态；坐席端点只校验「已登录」不校验角色 → 任何用户可任意接管/冒名回复。
- 小缺口：待接队列只按 `tenantId`（未按 `appId`）收窄；缺 `session.accepted`/`agent.replied` 事件。

**目标**：在既有骨架上打通双向实时通道、收口身份、对齐 streamChat，把真人坐席做成端到端可用能力。

## 2. 范围

### IN（核心，端到端可用必需）
1. 双向实时：**SSE 下行推送 + POST 上行**（用户端 inbox SSE、坐席端 SSE）。
2. 身份收口：`agentId` 绑登录态 + 坐席端点角色门禁。
3. `streamChat` 对齐：AI 静默 + 关键词转人工，与 `chat()` 共享同一套判定。
4. 双向消息投递：坐席回复→用户 inbox；用户新消息→坐席（接管态）。
5. 待接队列按 `tenantId`+`appId` 收窄。
6. webhook 事件：`session.accepted`、`agent.replied`。
7. 前端坐席台接真实消息+实时化；widget 接 inbox SSE 渲染坐席消息；重建并提交 `widget.js`。

### IN（增强，本次一并做）
8. **回归 AI**：`AGENT_PROCESSING → AI_PROCESSING`（坐席把会话交还 AI 续答）。
9. **待接/新消息提醒**：坐席台队列新待接、已接会话用户新消息未读红点/计数。
10. **presence**：坐席在线状态（在线 = SSE 连接存活）+ 在线坐席列表 + 上下线事件。
11. **typing**：用户/坐席「正在输入」实时指示（不落库、客户端节流）。

### OUT（YAGNI，后续）
- 技能组路由 / 坐席自动分派（当前保持「坐席从待接队列手动认领」）。
- **多实例 SSE 广播**（Redis pub/sub）：MVP 用**单实例内存注册表**，多实例部署时坐席/用户可能连在不同实例上收不到跨实例推送——留后续，本设计只保证单实例正确 + DB 事实源兜底（重连补拉不丢消息）。
- 离线消息推送补偿、坐席工作量统计/绩效、会话主动分配轮转。

## 3. 实时通道架构 · `RealtimeHub`（新增，内存注册表）

### 3.1 组件
`RealtimeHub`（`service/realtime/` 或 `config/`）——`SseEmitter` 注册表，管理两类订阅者：

| 订阅者 | 端点 | 鉴权 | 订阅键 | 收到的事件 |
|---|---|---|---|---|
| 用户端（widget） | `GET /open/v1/sessions/{sessionId}/inbox` | 开放 API（appId + Origin/appKey，经 `OpenApiAuthInterceptor`） | `sessionId` | 坐席 `message`、`session`（接入/结束/回归）、坐席 `typing` |
| 坐席端（后台） | `GET /v1/chat/agent/stream` | Sa-Token 登录 + 属本租户 | `tenantId:agentUserId` | 新待接/用户新 `message`+未读、用户 `typing`、其他坐席 `presence` |

- 数据结构：`ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>`（一个订阅键可有多标签页/多连接）。
- 生命周期：注册时挂 `onCompletion`/`onTimeout`/`onError` 回调**摘除对应 emitter**；周期心跳（comment/ping，`@Scheduled` 或每 emitter 定时）保活并探测死连接、清理防泄漏。
- 发布 API：`publishToSession(sessionId, eventName, payload)`、`publishToAgent(tenantId, agentUserId, ...)`、`publishToTenantAgents(tenantId, appId, ...)`（新待接会话广播给本租户+应用在线坐席）。
- **SSE 是加速分发，DB 消息是事实源**（同 webhook 队列语义）：推送 best-effort，发送失败即摘除该 emitter、**绝不影响业务落库**；断连/丢事件后客户端重连并按「最后消息 id 之后」补拉 `getSessionMessages`，不丢消息。

### 3.2 事件模型（SSE named events，payload JSON）
- `message`：一条新聊天消息（含 messageId/role/content/createdAt/sessionId）。
- `session`：会话状态变更（`accepted`/`ended`/`returned_to_ai`，含提示文案供直接渲染）。
- `typing`：`{sessionId, from: user|agent}`，短事件、不落库。
- `presence`：`{agentUserId, online: true|false}`（仅坐席端）。

> AI 流式回复仍走既有 `/open/v1/chat/stream` 的**每请求 `Flux<String>` 短流**（回复完即关），与本处**持久 `SseEmitter` inbox 流**是两条不同生命周期的流，不合并。

## 4. 身份与权限收口

- 坐席端点（`accept`/`agentReply`/`agent/stream`/`return-to-ai`/`typing`/在线列表）统一加门禁：**已登录 + 角色 ∈ {TENANT_ADMIN, TENANT_MEMBER}**（复用既有 `SecurityUtil` 或在 `SaTokenConfig` 路由规则里加 `checkRole`；沿用项目现有校验风格）。
- `agentId` 一律取 `StpUtil.getLoginId()`（Long），**从 `AgentReplyRequest` 与 accept 的请求参数中删除**（`AgentReplyRequest` 去掉 `agentId` 字段）。
- 归属校验保留：`getSession` + `TenantOwnershipGuard.belongsToCurrentContext`。
- 前端 `AgentPage` 删除写死的 `MOCK_AGENT_ID`。

## 5. 双向消息流与 `streamChat` 对齐（必修）

抽取共享「接管前置处理」逻辑（关键词转人工触发 + 人工接管态 AI 静默），供 `chat()` 与 `streamChat()` 复用（当前仅 `chat()` 有，`streamChat()` 两样皆无）：

- 用户上行（POST，含 widget 的 `chat/completions` 与 `chat/stream`）：
  - 命中转人工关键词 → 置 `WAITING_AGENT` + 发 `session.handover_requested` + `publishToTenantAgents`（新待接）。
  - 会话为 `AGENT_PROCESSING`/`WAITING_AGENT`：**静默**——只落用户消息 + `publishToAgent`（该会话 `agentId` 对应坐席，`message`+未读），不调 AI；流式路径返回一条 `session`/提示事件而非 AI 流。
- 坐席下行：`agentReply` → 落 ASSISTANT 消息 + `publishToSession(sessionId, message)` + 发 `agent.replied` 事件。

## 6. 会话生命周期 + 回归 AI

- `acceptSession`：`WAITING_AGENT`/`AI_PROCESSING → AGENT_PROCESSING`，`agentId = 登录用户`、`handoverTime`；`publishToSession(session, accepted "客服已接入")` + 发 `session.accepted` 事件；从其他坐席待接队列移除（推 `session` 事件）。
- `agentReply`：见 §5。
- **`returnToAI`（新增）**：`POST /v1/chat/session/{id}/return-to-ai`，`AGENT_PROCESSING → AI_PROCESSING`，清 `agentId`；`publishToSession(session, returned_to_ai "已切回智能助手")`。
- `endSession`（保留）：`→ ENDED` + 异步摘要 + 既有 `session.ended` 事件 + `publishToSession(ended)`。
- 待接队列 `getWaitingAgentSessions` 按 `TenantContext` 的 `tenantId` + `appId` 双重收窄（现仅 tenantId）。

## 7. presence / typing（轻量）

- **presence**：坐席在线 ≡ 其 `/v1/chat/agent/stream` SSE 连接存活（无独立心跳 POST）。`RealtimeHub` 维护在线坐席集合（注册键含 `agentUserId`）；上/下线时 `publishToTenantAgents(presence)`。`GET /v1/chat/agents/online` 返回本租户在线坐席列表。
- **typing**：`POST /v1/chat/session/{id}/typing`（坐席端）与 `POST /open/v1/sessions/{id}/typing`（用户端）→ 向对端 `publish` 一条 `typing` 事件；**不落库**、客户端节流（如 3s 内至多一次）。

## 8. 前端

- **坐席台 `AgentPage`**：删 mock；接管后 `getSessionMessages` 拉真实历史；开 `/v1/chat/agent/stream`（EventSource）接收 新待接/用户新消息（未读红点+计数）/typing/presence；回复走 `agentReply`（不再传 agentId）；「回归 AI」「结束会话」按钮；在线坐席指示（最小）。
- **Widget（`widget/`）**：会话建立（拿到 sessionId）后开 `/open/v1/sessions/{id}/inbox`（EventSource）；渲染坐席 `message`、「客服已接入/已切回智能助手/已结束」状态、坐席 `typing` 气泡；上行/typing 节流。**重建并提交 `backend/src/main/resources/static/widget.js`**（提交进仓库的构建产物，`.gitattributes` 强制 LF）。

## 9. 多租户 / 隔离

- 用户 inbox 订阅经开放 API 鉴权（appId + Origin）；坐席 stream 经 Sa-Token + 租户门禁。发布按 `sessionId` / `tenantId(+appId)` 定键，坐席只收本租户（本应用）会话。`ChatSession` 继承 `BaseTenantEntity`，写路径归属校验保留。

## 10. 错误处理 / 边界

- SSE 发送失败 → 摘除 emitter、不抛给业务（fail-open，同 webhook）。
- 单实例内存注册表：多实例下跨实例推送失效（见 §2 OUT），MVP 明确单实例；DB 为事实源，重连补拉兜底不丢消息。
- 心跳/超时清理防 emitter 泄漏。typing/presence 短事件丢失无害（非事实源）。

## 11. 测试

- **单测（纯 Mockito）**：`RealtimeHub` 注册/发布/清理/并发安全（多 emitter、断连摘除）；`agentId` 取自登录态（回复不再信任入参）；`streamChat` 静默+关键词判定的抽取逻辑；`returnToAI` 状态转换；`getWaitingAgentSessions` appId 收窄；typing/presence 发布路由到正确订阅键。
- **IT（继承 `AbstractPostgresIT`）**：坐席端点角色门禁（未登录/非本租户 → 401/403）；`accept→agentReply→returnToAI/end` 状态流转与 webhook 事件发布；待接队列 appId 隔离；SSE 端点建立连接并收到一条 `message` 事件（MockMvc async + `SseEmitter`）。
- 前端/widget：`pnpm build` 通过、`pnpm lint` 无新增；widget `pnpm test` 绿。全链路真实浏览器实时联调作为收尾验证（可留真机 smoke）。

## 12. 分期（一分支 `feat/live-agent`）

- **A · 实时基建**：`RealtimeHub` + 用户 inbox 端点 + 坐席 stream 端点 + 鉴权 + 心跳/清理。
- **B · 后端核心**：身份收口（agentId 绑登录+门禁）+ `streamChat` 对齐（静默+关键词抽取共享）+ appId 队列收窄 + 双向消息推送 + `session.accepted`/`agent.replied` 事件。
- **C · 增强**：回归 AI（returnToAI）+ presence（在线=连接存活+在线列表）+ typing + 未读提醒（后端事件侧）。
- **D · 前端**：坐席台接真实数据+实时化 + widget inbox 渲染 + 重建 `widget.js`。
- **E · 收尾**：补齐单测/IT + `mvn verify` + 前端/widget build/lint/test。

## 13. 待核实（实现前）
- Spring MVC（`spring-boot-starter-web`）下 `SseEmitter` 与既有 `Flux<String>` AI 流式端点共存无冲突（两条独立流，预期无影响）。
- 开放 API 鉴权拦截器（`OpenApiAuthInterceptor`）对长连 `GET .../inbox` 的鉴权与限流行为（长连不应被按普通请求限流误伤——如需在 inbox 端点豁免/放宽限流，实现时确认）。
- `SaTokenConfig` 里对 `/v1/chat/**` 加角色校验的最小改法（现仅 `checkLogin`），与既有 `SecurityUtil` 手动校验风格取其一保持一致。

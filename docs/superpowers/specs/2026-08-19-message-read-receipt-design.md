# 消息已读回执与坐席多会话设计

> Phase 5.2 中期「消息已读 / 送达状态」的设计。
> ⚠️ **原立项理由已消失**：这条待办的原描述是「`ChatMessage.isRead` 字段已建但全库无人读写，
> 顺手补上用法即可」，而那个死字段已作为技术债第 4 项**删除**（`cfb74da`，2026-08-11）。
> 本文因此是从零设计，不是「把旧字段用起来」。
>
> 作者：ruan　日期：2026-08-19

## 1. 目标与非目标

**目标**：

- **A** 访客能看到自己发出的消息被人工坐席读过（只在人工接管阶段）。
- **C1** 坐席工作台能同时持有多个已接管会话并自由切换。
- **C2** 每个已接管会话显示未读消息数，跨刷新、跨设备、跨下线期间都准确。

**非目标**（均为本轮明确砍掉，不是遗漏）：

- **送达（delivered）状态** —— 见 §2.2。
- **访客侧已读回执**（让坐席知道访客读没读） —— 见 §2.3。数据模型为它留了结构位，但**不预置枚举值、不写任何代码**，理由见 §3.2。
- **AI 对话阶段的已读** —— 见 §2.1。
- **坐席并发会话数上限** —— 现阶段坐席连多会话都持有不了，先让它能用。过载属运营问题，真需要时加一个配置项即可，不影响本设计的任何结构。
- **已结束会话进入「我的会话」列表** —— 那属于历史，去会话管理页看。

## 2. 关键决策

### 2.1 已读只在人工坐席阶段有意义

AI 对话是「发一句、秒回一句」，不存在「等对方看到」的焦虑，给 AI 消息标已读是纯噪音。
已读语义只在 `AGENT_PROCESSING`（坐席接管后）成立。

自然结果：坐席接管**前**（排队等待期间）访客发的消息，在接管那一刻集体变为已读（§5.2），
之前则不显示任何标记。这个时序本身就是给访客的正向反馈——「有人接手了」。

### 2.2 不做「送达」

它在本场景下几乎不携带信息：

- 访客消息只要 HTTP 没报错就一定入库，「送达服务器」是必然事件，做成状态是噪音；
- 唯一有信息量的是「送到坐席那儿了吗」，但那本质是**坐席在不在线**，不是消息的属性。
  同一条消息会因坐席上线而从「未送达」变「已送达」，语义别扭；
- 且会制造一处不对称：坐席接管前访客发的消息永远显示「未送达」，而那段时间恰恰是访客
  最焦虑的时候，等于放大焦虑。

访客真正想知道的「我的消息有没有到人工那边」，由既有的会话级状态提示回答
（`accepted` → 「客服已接入，正在为您服务」），比消息级的送达标记更直接也更诚实。

### 2.3 不做访客侧回执（坐席看访客已读）

坐席通常不关心访客读没读；而访客一关页面就永远停在「未读」，反倒给坐席制造无意义的焦虑。
价值最薄的一项，砍掉。

### 2.4 访客侧只显示「已读」，不显示「未读」，且只标最后一条

- **不显示「未读」**：客服场景下访客的默认预期本就是「可能要等」，**沉默比「未读」温和**。
  一旦标上「已读」才是正向信号。显示「未读」等于把系统每一次繁忙都对访客明示。
- **只标最后一条**：水位线语义下，最后一条已读蕴含它之前全部已读，逐条标记是视觉冗余。

### 2.5 水位线推进条件：坐席选中该会话 **且** 标签页前台可见

坐席台是「左列表 + 右当前会话」，同时只能看一个会话，**选中**本身已是较强信号；再叠加
`document.visibilityState === 'visible'`，只多几行代码，却挡掉最常见的一类假已读——
工作台开着但人去开会了。

**不采用「坐席发出回复才算已读」**：那会把「看到了，正在查资料」误报成未读，对访客更不准，
且把「已读」和「已回复」两件产品上应分开的事混为一谈。

### 2.6 采用独立回执表，而非在 `ChatSession` 上加两个字段

会话级水位线（`ChatSession.agent_read_up_to_message_id`）能满足当前全部需求且更简单，但选择
独立表，为多坐席协作与将来可能的访客侧回执留出结构位——扩展时只加枚举值与数据行，
不动表结构与索引。

⚠️ **代价必须写明**：多一张表、一次 JOIN、以及 upsert 的并发处理（§3.3）。这是有意识付出的
复杂度，换的是扩展时不必改表。

## 3. 数据模型

### 3.1 新增实体 `MessageReadReceipt`

```java
@Entity
@Table(name = "message_read_receipt",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_receipt_session_reader",
           columnNames = {"session_id", "reader_type", "reader_id"}))
public class MessageReadReceipt extends BaseTenantEntity {
    private Long id;
    private Long sessionId;              // → chat_session.id
    private ReaderType readerType;       // 目前只有 AGENT
    private Long readerId;               // 坐席 user id
    private Long upToMessageId;          // 水位线：读到这条为止（含）
    private LocalDateTime readAt;
}
```

**继承 `BaseTenantEntity`**：回执在请求线程写入（坐席调接口），有 `TenantContext`。
CLAUDE.md 中「异步落库的实体不得继承」那条例外（`WebhookDelivery`/`SysAuditLog`/`SysApiUsage`）
不适用于本实体。

**唯一约束 `(session_id, reader_type, reader_id)`**：一个读者在一个会话只有一条水位线。
它既是 upsert 的依据，也是 §2.6 那个扩展性承诺的兑现点——多坐席只是多几行。

### 3.2 `ReaderType` 枚举当前只有 `AGENT` 一个值

⚠️ **刻意不预置 `VISITOR`**。预置一个永远不写入的枚举值，就是技术债第 4 项 `isRead` 死字段的
同一个错误——那次的结论原话是「留一个永远为 false 的列会持续误导读代码的人」。
本设计的扩展性来自**表结构**（session + reader + 水位线三元组），不来自预置值：将来做访客侧
回执，是加一个枚举值加一条回执路径，不动表、不动索引、不动唯一约束。

### 3.3 水位线只增不减，用带条件的原子 UPDATE

```sql
UPDATE message_read_receipt
   SET up_to_message_id = :new, read_at = :now
 WHERE session_id = :sid AND reader_type = 'AGENT' AND reader_id = :uid
   AND up_to_message_id < :new
```

首次插入走 upsert（`ON CONFLICT (session_id, reader_type, reader_id) DO UPDATE ... WHERE
message_read_receipt.up_to_message_id < EXCLUDED.up_to_message_id`）。

**不能先读后写**：坐席开两个标签页并发回执、或 SSE 事件乱序到达时，先读后写会让水位线回退，
访客界面上「已读」会闪一下消失。带条件的原子 UPDATE 让并发自然收敛到最大值。

### 3.4 迁移脚本

⚠️ `ddl-auto: update` **只加不删**：新表能自动建，但**唯一约束在存量库上不保证跟着建**
（Hibernate 对已存在表的约束变更处理不一致）。附一份幂等迁移 SQL，位置与风格同
`migration/2026-08-11-drop-chat-message-is-read.sql`：

```sql
CREATE TABLE IF NOT EXISTS message_read_receipt (...);
CREATE UNIQUE INDEX IF NOT EXISTS uk_receipt_session_reader
    ON message_read_receipt (session_id, reader_type, reader_id);
```

## 4. 接口

### 4.1 回执上报（坐席端）

`POST /v1/chat/session/{sessionId}/read`，body `{"upToMessageId": 123}`

`upToMessageId` 由坐席端传入**当前会话已渲染的最新一条消息的 id**（不区分角色——坐席看到的
是整个会话）。服务端不替客户端推断「最新是哪条」：坐席实际看到的截止位置只有客户端知道，
服务端若自作主张取库里最新的一条，会把「消息刚到、还没渲染出来」也算成已读。

- `SecurityUtil.requireAgent()`；
- **校验 `session.agentId == 当前坐席**——非本人接管的会话直接拒。缺这道校验就能靠遍历
  sessionId 给别人的会话写水位线，进而伪造发给访客的已读；
- 幂等，按 §3.3 收敛；
- 会话已结束或已转走时，该校验自然拒绝。

### 4.2 我的进行中会话（坐席端，新增）

`GET /v1/chat/sessions/mine` → `PageDTO<AgentSessionDTO>`

条件 `status = AGENT_PROCESSING AND agent_id = 当前坐席`，每条带 `unreadCount`，
按**该会话最后一条消息的 `created_at`** 倒序。

⚠️ 排序键**不用 `ChatSession.updatedAt`**：那个字段会被与消息无关的更新（打标签、改摘要、
转接）带动，排序会莫名跳动。用聚合出的 `MAX(chat_message.created_at)`，与「有新消息的会话
浮到顶部」这个语义严格一致，也和未读徽章是同一套口径。该聚合与 `unreadCount` 在同一条
原生 SQL 里一次算出。

未读数用一条原生 SQL 聚合出整页：`chat_session LEFT JOIN message_read_receipt`（限定当前坐席）
后统计 `role = 'USER' AND chat_message.id > COALESCE(up_to_message_id, 0)` 的条数。

⚠️ **必须原生 SQL 且显式带 `tenant_id`/`app_id` 条件**：`ChatSession` 的 `@Filter` 保护不到
JOIN 聚合查询，且平台管理员整体绕过 `@Filter`。同 `getWaitingAgentSessions` 已有的手工收窄，
以及 `AnalyticsRollupDao` / `findDueWebPageIds` 的既有模式。

### 4.3 访客侧历史需带上水位线

访客刷新页面后必须能恢复已读标记——已读状态恰恰是刷新后最该稳定的东西。
widget 拉取会话历史的响应增加 `agentReadUpToMessageId` 字段。

### 4.4 契约新增：访客要知道自己那条消息的 id

现有 `OpenChatResponse.messageId` 是 **AI 回复**的 id（供反馈按钮使用），访客发出的那条消息
自身的 id 没有透出，无法与 `upToMessageId` 比对。需在两处各增一个 `userMessageId`
（加字段，向后兼容）：

- 非流式 `OpenChatResponse`；
- 流式 SSE 的 `START` 事件。

⚠️ **不能省成「收到 read 事件就把最后一条标已读」**：访客发了新消息、而坐席正在看**别的**
会话时，会收到针对更早消息的回执，那样会把并未被读的最新消息错标成已读——正好在最需要
准确的场景下骗人。

## 5. 推送与时序

### 5.1 SSE 用独立的 `read` 事件，不复用 `session`

```java
public void readReceiptToUser(String sessionId, Long upToMessageId) {
    hub.publish(RealtimeKeys.session(sessionId), "read",
                json(Map.of("sessionId", sessionId, "upToMessageId", upToMessageId)));
}
```

⚠️ **不能复用 `toUserSessionEvent`**：`widget/src/main.ts:67` 对 `session` 事件是
**无条件当文案展示**（`ui.showSessionState(p.text || sessionStateText(p.kind))`）。复用它会让
访客每次被读都看到一条莫名的状态提示。走独立事件类型（与既有 `typing` 平级）天然免疫，
且不依赖「将来加 kind 时记得加分支」这种约定。

### 5.2 接管瞬间把水位线置为当时最新消息 id

`acceptSession` 内完成。坐席接管时界面就加载了全部历史，等于已读；访客侧同时看到排队期间
发的消息集体变已读，时序正确（§2.1）。

### 5.3 先落库、再推送

先执行 §3.3 的 UPDATE 并拿到**实际生效**的水位线，再推 `read` 事件。顺序颠倒的话，访客收到
已读后若立刻重连拉历史，会读到旧水位线，界面上「已读」闪一下又消失。

这与 CLAUDE.md 记载的「attach 必须早于 notify」是同一类顺序约束（那条的后果是坐席收到的
`attachments` 恒为空数组）。

### 5.4 前端触发时机

`选中会话 && document.visibilityState === 'visible'` 时上报，三个触发点：

1. 切换到该会话；
2. 收到该会话的新消息；
3. 标签页从后台切回前台（`visibilitychange`）。

**不做防抖**：人工对话频率下每条消息一次请求完全可接受，而防抖会把「已读」延迟到访客能
察觉的程度，损害这个特性唯一的价值。

## 6. 前端与 widget

### 6.1 坐席台左栏改为两段

```
┌─ 待接队列  [3]        ← 现有，不动；徽章语义是队列长度
│    会话 A / B / C
├─ 我的会话             ← 新增
│    会话 D  [2]        ← 未读徽章，数据来自后端
│    会话 E
└─
```

上下分段而非 tab：坐席需要同时盯住「有没有人排队」和「我手上的会话有没有新消息」，
tab 会把其中一半藏起来。

### 6.2 本轮顺带修掉的两个现存缺陷

1. **接管第二个会话会丢掉第一个**。`handleAcceptSession` 做的是
   `setSelectedSession(res.data)`，而该会话同时因状态变更从待接队列消失。坐席接管第二个会话
   后，第一个在界面上再也回不去，刷新也回不去（此前没有「我的会话」查询接口）。
   由 §4.2 + §6.1 修复。

2. **未读徽章是死代码，永远不显示**。`unread` 的 key 来自 `toAgentMessage` 推送，即
   `AGENT_PROCESSING` 且 `agentId == 我` 的会话（`ChatServiceImpl:1017`）；而徽章渲染在
   `waitingSessions.map(...)` 里，那是 `WAITING_AGENT` 状态的会话
   （`ChatServiceImpl:1303`）。**两个集合按定义不相交**，`unread[session.sessionId]` 恒为
   undefined。徽章移到「我的会话」段后，key 集合才对得上。

### 6.3 会话切换重新拉取消息，不做本地多会话缓存

人工客服的单会话消息量很小，缓存换来的那点速度不值得引入「缓存与 SSE 增量谁是准」的一致性
问题。

### 6.4 未读数以后端为准，本地计数只是过渡态

SSE 收到非当前会话的消息时本地 `+1`（即时反馈，不必等下次刷新），但**任何一次列表刷新都以
后端聚合值覆盖本地值**。

⚠️ 这条要写进代码注释：将来若有人「优化」成以本地累加为准，就会漂移，而漂移不报错——
只是徽章上的数字不对，没有任何现象提醒。

### 6.5 widget 侧渲染

监听 `read` 事件，取 `upToMessageId`，把 id ≤ 水位线的**最后一条访客消息**标为「已读」。
需要在消息 DOM 上带 `data-message-id`（当前 widget 只在流式完成时用过一次 messageId，
未保留在 DOM 上）。

## 7. 测试策略

**原则**：已读是增强功能，任何失败都不得反过来伤主流程。

### 7.1 单测（Mockito，免 Docker）

- 水位线只增不减（传入更小的 `upToMessageId` 不回退）；
- 非本人接管的会话拒绝回执；
- 未读数只统计 `role = USER` 且 id 超过水位线的消息；
- `acceptSession` 把水位线置为当时最新消息 id；
- 会话结束 / 已转走后拒绝回执。

### 7.2 集成测试（Testcontainers）

- **唯一约束真的生效**：并发 upsert 不产生两行；
- **原生 SQL 的租户隔离**：跨租户查不到别人的未读数。这条必须有——原生 SQL 绕开了
  `@Filter`，正是本仓库反复出事的地方。

### 7.3 前端 vitest

- `visibilitychange` 触发回执；切换会话触发回执；
- **本地 `+1` 被后端值覆盖**（守 §6.4 那条约定）。

### 7.4 widget vitest

- **`read` 事件不产生任何文案节点**（不走 `showSessionState`）。这条是变异测试的好目标：
  把实现改回复用 `session` 事件，它必须转红；
- 按 `upToMessageId` 标记正确的那一条。

### 7.5 错误处理

| 失败点 | 处理 |
|---|---|
| 回执接口调用失败 | fail-soft，坐席端只记日志不弹错——坐席正在服务客户，不该被「已读上报失败」打断 |
| `read` 事件推送失败 | 水位线已落库，访客下次拉历史即恢复（§4.3） |
| 并发回执 / SSE 乱序 | §3.3 的原子 UPDATE 收敛到最大值 |
| 坐席掉线 / 会话转走 | 水位线保留不动；`agentId` 校验挡住后续越权回执 |

## 8. 必须手工验证（自动化覆盖不到）

1. **多标签页并发**：坐席开两个标签页同看一个会话，回执不打架、水位线不回退。jsdom 造不出
   真实并发。
2. **Page Visibility 的真实行为**：切到别的标签页不上报、切回来立即上报。jsdom 里
   `visibilityState` 是测试自己 stub 的，等于在测桩。
3. **访客刷新后已读保持**：真机刷新 widget，已读标记仍在。
4. **多实例下 `read` 事件到达**：`RealtimeHub` 走 Redis 桥接，单实例测不出跨实例路径
   （同 `WebhookQueueIT` 面对的问题）。
5. **访客界面无视觉噪音**：坐席反复读，访客侧只出现「已读」二字，不冒任何状态提示条
   （§5.1 那个坑的真机确认）。

配套：CLAUDE.md 增加一节「改动已读回执链路后必须手工验证」，并在
`scripts/manual-smoke-hints.sh` 增加路径 → 章节映射。⚠️ 该脚本会校验引用的章节标题在
CLAUDE.md 中存在，改标题忘同步会 `exit 1`。

## 9. 风险与后续

- **本设计付出的主要复杂度是独立表**（§2.6）。若一年内仍未出现多坐席协作或访客侧回执的需求，
  它相对「`ChatSession` 加两个字段」就是净亏损。记在这里，便于将来回看这个判断对不对。
- **坐席并发会话上限**未做（§1）。「我的会话」列表能持有的会话数无限，极端情况下列表很长。
  加上限只需一个配置项 + `acceptSession` 处一道校验，不影响本设计任何结构。
- **访客侧回执（B）**若将来要做：加 `ReaderType.VISITOR`、一条访客回执路径、坐席台渲染，
  表结构与索引不动（§3.2）。

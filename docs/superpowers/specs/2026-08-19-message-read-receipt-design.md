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
- **B** 坐席能看到自己发出的消息被访客读过。与 A 对称，复用同一套水位线机制。
- **C1** 坐席工作台能同时持有多个已接管会话并自由切换。
- **C2** 每个已接管会话显示未读消息数，跨刷新、跨设备、跨下线期间都准确。

**非目标**（均为本轮明确砍掉，不是遗漏）：

- **送达（delivered）状态** —— 见 §2.2。
- **多坐席协作**（同一会话多个坐席同时接待） —— 单独立项，理由见 §2.7。本设计的回执表能
  容纳多个读者，但那只是**回执**这一层；系统层面支持多坐席是会话模型的改动，与已读无关。
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

### 2.3 访客侧回执（B）与 A 严格对称

判定条件、展示规则、水位线机制全部照搬 A，只是方向相反：

| | A（坐席读访客） | B（访客读坐席） |
|---|---|---|
| 谁上报 | 坐席台 | widget |
| 判定条件 | 选中该会话 **且** 标签页前台可见 | widget **展开**（非收起）**且** 页面前台可见 |
| 推给谁 | 访客（`read` 事件） | 该会话的坐席 |
| 展示 | 只标最后一条已读的访客消息 | 只标最后一条已读的坐席消息 |

**widget 收起时不算已读**：沿用 A 的原则——「人真的看到了才算」。widget 收成一个悬浮按钮时
内容不可见，此时标已读就是骗坐席。

⚠️ **B 的固有局限，产品上要接受**：访客关掉页面后永远停在未读，坐席会看到自己最后一条消息
一直没有已读标记。这是真实情况的忠实反映（访客确实没看到），不做任何美化——**不要**因为
「看着不舒服」就在会话结束时补一个已读。

⚠️ **「坐席消息」的判据是 `role === 'AGENT'`**。本特性实施时它曾经是 `'ASSISTANT'`——
那时 `agentReply` 落库写的就是 `MessageRole.ASSISTANT`，`MessageRole.AGENT` 从未被写入，
判据只能将错就错，也因此埋着「AI 与坐席在同一会话交替回复时会失准」的隐患。该根因已于
2026-08-20 单独立项修复，见 `2026-08-20-agent-message-role-design.md`，隐患随之关闭，
本节连同 §9 的记录一并订正。

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

会话级水位线（`ChatSession.agent_read_up_to_message_id`）只能表达**一个**读者，而本轮同时要做
A 与 B，即一个会话至少有两个读者（坐席、访客），各有各的水位线。独立表是这个需求的直接结果，
不是为将来预留。

⚠️ **代价**：多一张表、一次 JOIN、以及 upsert 的并发处理（§3.3）。在两个读者的前提下这个代价
是必须付的——用会话字段做就得写成 `agent_read_up_to` + `visitor_read_up_to` 两组字段，
再来第三个读者就得改表。

### 2.7 多坐席协作单独立项，不并入本轮

回执表的 `reader_id` 那一维确实能容纳多个坐席，但**那只是回执这一层**。系统层面支持多坐席是
会话模型的改动，与已读没有交集：

| 缺口 | 现状 | 要做什么 |
|---|---|---|
| 会话↔坐席关系 | `ChatSession.agentId` 单字段 | 改关联表，1:1 → 1:N |
| 接管语义 | `acceptSession` 排他（`ChatServiceImpl:1246` 直接覆盖 `setAgentId`） | 重新定义：多人同时接管，还是主坐席 + 协作者？谁能结束会话？ |
| **消息归属** | **`ChatMessage` 没有 `agentId`**（全类 0 处引用） | AGENT 消息无法区分是谁发的，两端都看不出「张三说」还是「李四说」。要加字段并改所有 AGENT 消息落库路径 |
| 实时推送 | `toAgentMessage(tenantId, agentUserId, msg)` 单播 | 改成推给该会话的全部坐席 |
| 权限 | 无概念 | 谁能回复 / 结束 / 转接 / 移出协作者 |
| 坐席台 | 单人视角 | 同事在场提示、避免两人重复回复同一条 |

「消息归属」那条是硬缺口：**多坐席在消息层面根本没有立足点**，必须先给 `ChatMessage` 加发送者。

两者的验收标准也没有交集——本轮验的是「双向已读准确」，多坐席验的是「两个坐席同时服务一个
客户且不打架」。捆在一起做，任何一半出问题都会卡住另一半上线。

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
    private ReaderType readerType;       // AGENT | VISITOR
    private Long readerId;               // 见 §3.2.1，不可为 null
    private Long upToMessageId;          // 水位线：读到这条为止（含）
    private LocalDateTime readAt;
}
```

**继承 `BaseTenantEntity`**：回执在请求线程写入（坐席调接口），有 `TenantContext`。
CLAUDE.md 中「异步落库的实体不得继承」那条例外（`WebhookDelivery`/`SysAuditLog`/`SysApiUsage`）
不适用于本实体。

**唯一约束 `(session_id, reader_type, reader_id)`**：一个读者在一个会话只有一条水位线。
它既是 upsert 的依据，也是 A 与 B 两条水位线得以共存于一张表的依据：同一会话下
`(AGENT, 坐席id)` 与 `(VISITOR, 会话id)` 是两行，互不干扰。

### 3.2 `ReaderType` 两个值，都有真实写入路径

`AGENT`（§4.1 坐席回执）与 `VISITOR`（§4.3 访客回执）本轮都实现，不存在只建不用的值。

⚠️ 若将来因故砍掉其中一侧，**枚举值要跟着删**，别留一个永不写入的值——那正是技术债第 4 项
`isRead` 死字段的形态，那次的结论原话是「留一个永远为 false 的列会持续误导读代码的人」。

### 3.2.1 `reader_id` 是「在 `reader_type` 命名空间内标识读者」

- `AGENT` → 坐席的 user id；
- `VISITOR` → 该会话的 `chat_session.id`（一个会话只有一个访客，用会话 id 即可唯一标识它）。

⚠️ **`reader_id` 绝不能对访客留 NULL**：PostgreSQL 的唯一约束里 `NULL` 不等于 `NULL`，
`(session_id, 'VISITOR', NULL)` 可以插入任意多行，唯一约束**形同虚设**，水位线会变成多行并存、
读出来哪一行全看运气。这个坑不报错，只会让已读状态间歇性错乱。

### 3.3 水位线只增不减，用带条件的原子 UPDATE

```sql
UPDATE message_read_receipt
   SET up_to_message_id = :new, read_at = :now
 WHERE session_id = :sid AND reader_type = :readerType AND reader_id = :readerId
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

### 4.3 回执上报（访客端，B）

`POST /open/v1/sessions/{sessionId}/read`，body `{"upToMessageId": 123}`

- 走开放 API 鉴权（`X-App-Id` + Origin 白名单 / ApiKey），sessionId 本身是第二重能力凭证，
  与附件下载端点 `?sessionId=` 的定级一致；
- 写入 `readerType = VISITOR`、`readerId = chat_session.id`（§3.2.1）；
- 幂等与并发收敛同 §3.3；
- 会话不存在或不属于当前 app 时拒绝。

⚠️ **只统计坐席消息**：访客的水位线用于判断「坐席发的消息被读了没」，与 A 的口径
（只数 `role = 'USER'`）方向相反，展示时只考虑 `role IN ('AGENT')` 的消息。

### 4.4 两端历史都要带上对方的水位线

刷新后必须能恢复已读标记——已读状态恰恰是刷新后最该稳定的东西。两个方向都要：

- widget 拉取会话历史的响应增加 `agentReadUpToMessageId`（坐席读到哪了）；
- 坐席台拉取会话消息的响应增加 `visitorReadUpToMessageId`（访客读到哪了）。

⚠️ 两端各只需要**对方**的水位线。把自己的水位线也返回没有用处，反而会诱使前端拿它当本地
状态的事实源，而本地水位线的推进条件（可见性）只有客户端自己知道。

### 4.5 契约新增：访客要知道自己那条消息的 id

现有 `OpenChatResponse.messageId` 是 **AI 回复**的 id（供反馈按钮使用），访客发出的那条消息
自身的 id 没有透出，无法与 `upToMessageId` 比对。需在两处各增一个 `userMessageId`
（加字段，向后兼容）：

- 非流式 `OpenChatResponse`；
- 流式 SSE 的事件（见下方 ⚠️，实际落在 `DONE` 而非本节最初设想的 `START`）。

⚠️ **实施订正（与最初设计不同）：`userMessageId` 实际由 `DONE` 事件承载，而非 `START`**。
原因是 `ChatMessage` 用 `IDENTITY` 生成策略，真实 id 只在 INSERT 那一刻产生，而主问答的
流式路径把整轮消息（用户消息 + AI 回复）延迟到流结束才落库——这是「停止生成不落库」契约
的一部分（客户端在 AI 尚未产出任何内容前取消/模型报错时，连用户自己这条消息也不落库）。
构造 `START` 事件时用户消息尚未落库，此时**没有**真实 id 可用。

最终契约是**有意的不对称**：
- `START.userMessageId` 只有「人工接管静默」「关键词触发转人工」「知识库未命中兜底」三个
  早退分支在构造 `START` 前用户消息已经落库，能带真实 id；主问答路径（检索命中、走完整
  流式生成）恒为 `null`。
- `DONE.userMessageId` 排在 `persistStreamedMessages` 落库之后构造，只要本轮确实落了库
  （`DONE.messageId` 非 null）就必然一并拿到真实的用户消息 id；两者都没落库时（流被取消/
  模型报错）同样为 `null`。
- widget 侧策略：**`START` 有就先用，没有就等 `DONE`**——不是「主问答路径永远拿不到」，
  只是比早退分支晚一点拿到。

详见 `ChatStreamEvent` 的 javadoc（`Start.userMessageId` / `Done.userMessageId`）与
`ChatServiceImpl.streamChat` 里紧邻 `ChatStreamEvent.start(...)` 调用处的注释。

**为什么不做落库时机重构去换取 `START` 时的真实 id**：把用户消息提前单独落库（与 AI 回复
落库解耦）能让 `START` 也拿到真实 id，但会连带打破三处已测试的既有行为——(1)「停止生成
不落库」契约本身（`StreamChatEventPipelineTest` 的「客户端取消订阅」「模型流失败」两条用例
钉住的正是"两者要么都落库、要么都不落"这条边界）；(2) 附件归属校验
（`attachToMessage → mutableInCurrentContext`）依赖用户消息与 AI 回复在同一次落库里携带同一份
`TenantContext` 搬运结果，提前落库会让这套搬运逻辑要多走一趟；(3) 会牵连一条需要 Docker 的
集成测试（落库时序变化影响 IT 里对消息行数/顺序的断言）。这些代价换来的只是「早退分支之外
的路径也能在 `START` 时就拿到 id」，而 widget 的「`START` 没有就等 `DONE`」策略已经把这个
时间差吸收掉了，不值得为此重构落库时机。

⚠️ **不能省成「收到 read 事件就把最后一条标已读」**：访客发了新消息、而坐席正在看**别的**
会话时，会收到针对更早消息的回执，那样会把并未被读的最新消息错标成已读——正好在最需要
准确的场景下骗人。

## 5. 推送与时序

### 5.1 SSE 用独立的 `read` 事件，不复用 `session`

```java
/** A：坐席读了 → 推给访客 inbox */
public void readReceiptToUser(String sessionId, Long upToMessageId) {
    hub.publish(RealtimeKeys.session(sessionId), "read",
                json(Map.of("sessionId", sessionId, "upToMessageId", upToMessageId)));
}

/** B：访客读了 → 推给该会话的坐席 */
public void readReceiptToAgent(Long tenantId, Long agentUserId, String sessionId, Long upToMessageId) {
    hub.publish(RealtimeKeys.agent(tenantId, agentUserId), "read",
                json(Map.of("sessionId", sessionId, "upToMessageId", upToMessageId)));
}
```

两条用**同一个事件名 `read`**，靠订阅的 key 区分方向（访客只订阅自己会话的 inbox，坐席只订阅
自己的单播键），载荷结构也相同。坐席端收到时按 `sessionId` 归入对应会话——这一点在多会话
列表下是必需的，坐席可能正看着别的会话。

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

### 5.4 两端的触发时机

**坐席台（A）**：`选中会话 && document.visibilityState === 'visible'` 时上报，三个触发点：

1. 切换到该会话；
2. 收到该会话的新消息；
3. 标签页从后台切回前台（`visibilitychange`）。

**widget（B）**：`widget 展开 && document.visibilityState === 'visible'` 时上报，三个触发点：

1. widget 从收起变为展开；
2. 收到坐席的新消息（仅当已展开且可见）；
3. 标签页从后台切回前台。

⚠️ widget 的收起/展开是它自己的内部状态，不等于页面可见性——两个条件都要判，缺一都会产生
假已读（收起时收到消息、或展开着但标签页在后台）。

**两端都不做防抖**：人工对话频率下每条消息一次请求完全可接受，而防抖会把「已读」延迟到对方
能察觉的程度，损害这个特性唯一的价值。

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

### 6.5 widget 侧：渲染 A + 上报 B

**渲染**：监听 `read` 事件，取 `upToMessageId`，把 id ≤ 水位线的**最后一条访客消息**标为
「已读」。需要在消息 DOM 上带 `data-message-id`（当前 widget 只在流式完成时用过一次
messageId，未保留在 DOM 上）。

**上报**：按 §5.4 的三个触发点调 `POST /open/v1/sessions/{id}/read`，`upToMessageId` 传当前已
渲染的最新一条消息 id。fail-soft：失败只 `console.debug`，绝不打断访客的对话。

### 6.6 坐席台侧：渲染 B

监听 `read` 事件（走坐席单播键），按载荷里的 `sessionId` 归入对应会话——**不能假设它就是当前
选中的会话**，坐席可能正在看别的会话，而那个会话的访客同时读了消息。

在该会话的消息区，把 id ≤ 水位线的**最后一条坐席消息**标为「已读」。切换会话时从 §4.4 的
`visitorReadUpToMessageId` 恢复。

⚠️ **实施订正：代码里判据是 `role === 'ASSISTANT'`，不是 `'AGENT'`**——原因与取舍同 §2.3
那条订正，此处不重复。

## 7. 测试策略

**原则**：已读是增强功能，任何失败都不得反过来伤主流程。

### 7.1 单测（Mockito，免 Docker）

- 水位线只增不减（传入更小的 `upToMessageId` 不回退）；
- 非本人接管的会话拒绝坐席回执；
- 访客回执写入的是 `(VISITOR, chat_session.id)` 这一行，**不影响** `(AGENT, ...)` 那行；
- 未读数只统计 `role = USER` 且 id 超过水位线的消息；
- `acceptSession` 把坐席水位线置为当时最新消息 id；
- 会话结束 / 已转走后拒绝坐席回执；
- 访客回执落在不属于当前 app 的会话上时被拒。

### 7.2 集成测试（Testcontainers）

- **唯一约束真的生效**：并发 upsert 不产生两行；
- **A 与 B 两条水位线互不干扰**：同一会话下写 `AGENT` 与 `VISITOR` 两行，各自推进互不覆盖。
  这条守的是 §3.2.1 那个 `reader_id` 设计——若有人把访客的 `reader_id` 写成 NULL，
  PostgreSQL 的唯一约束会形同虚设，本条会转红；
- **原生 SQL 的租户隔离**：跨租户查不到别人的未读数。这条必须有——原生 SQL 绕开了
  `@Filter`，正是本仓库反复出事的地方。

### 7.3 前端 vitest

- `visibilitychange` 触发回执；切换会话触发回执；
- **本地 `+1` 被后端值覆盖**（守 §6.4 那条约定）；
- **收到非当前会话的 `read` 事件时，标记落在正确的会话上**（守 §6.6——最容易写成「直接标在
  当前会话」，而那在多会话列表下是错的）。

### 7.4 widget vitest

- **`read` 事件不产生任何文案节点**（不走 `showSessionState`）。这条是变异测试的好目标：
  把实现改回复用 `session` 事件，它必须转红；
- 按 `upToMessageId` 标记正确的那一条；
- **widget 收起时不上报**、展开且可见时才上报（守 §5.4 那两个必须同时成立的条件）。

### 7.5 错误处理

| 失败点 | 处理 |
|---|---|
| 回执接口调用失败 | fail-soft，坐席端只记日志不弹错——坐席正在服务客户，不该被「已读上报失败」打断 |
| `read` 事件推送失败 | 水位线已落库，访客下次拉历史即恢复（§4.4） |
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
6. **B 的双向真机联调**：坐席发消息 → 访客 widget 展开着 → 坐席端出现「已读」；把 widget
   收起再让坐席发一条 → 坐席端**不该**出现已读。收起状态是 widget 自己的内部状态，
   jsdom 里测的是我们自己的桩。
7. **坐席正看着别的会话时收到 B 回执**：开两个已接管会话，看着会话甲，让会话乙的访客读消息
   → 已读标记必须落在乙上，不能落在甲上。这条在单会话时代不存在，是多会话列表引入的新
   失效模式，而它只在真机多开时才现形。

配套：CLAUDE.md 增加一节「改动已读回执链路后必须手工验证」，并在
`scripts/manual-smoke-hints.sh` 增加路径 → 章节映射。⚠️ 该脚本会校验引用的章节标题在
CLAUDE.md 中存在，改标题忘同步会 `exit 1`。

## 9. 风险与后续

- **~~既有缺陷：坐席消息与 AI 消息在 `ChatMessage.role` 上不可区分~~（已于 2026-08-20 修复）**。
  `agentReply` 曾落库写 `ASSISTANT` 而 `MessageRole.AGENT` 从未被写入，后果是访客刷新页面后
  坐席说过的话显示成 AI 气泡（`widget/src/history.ts` 按 role 分派）。本特性的已读判据
  （§2.3、§6.6）当时依赖这个不区分性才能成立，但那只是绕过了它。修复设计见
  `2026-08-20-agent-message-role-design.md`，连带改了三处：LLM 历史上下文新增 `AGENT` 分支、
  坐席台已读判据与其本地乐观渲染、存量数据迁移。
  ⚠️ **保留这条记录而不是删掉**：不然下一个人会重新踩「为什么已读判据要用 ASSISTANT」这个坑。
- **独立表的复杂度已被 B 兑现**（§2.6）：两个读者各一条水位线，会话字段方案做不到。这个判断
  不再是「为将来预留」，而是当轮需求的直接结果。
- **坐席并发会话上限**未做（§1）。「我的会话」列表能持有的会话数无限，极端情况下列表很长。
  加上限只需一个配置项 + `acceptSession` 处一道校验，不影响本设计任何结构。
- **多坐席协作**（§2.7）单独立项。届时本设计只需在 `reader_id` 维度多写几行，**真正的工作量
  在会话模型**：`ChatSession.agentId` 改关联表、`ChatMessage` 补发送者字段、单播推送改多播、
  接管与权限语义重定义。⚠️ **别被「回执表已支持多读者」误导成这件事很轻**。
- **B 的固有局限**（§2.3）：访客关页面后坐席永远看不到已读。这是真实情况的忠实反映，
  若将来产品上觉得不可接受，正确的解法是给坐席显示「访客已离开」这类**会话级**状态，
  而不是伪造一个已读。

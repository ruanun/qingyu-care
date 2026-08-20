# 坐席消息与 AI 消息在 `ChatMessage.role` 上不可区分 —— 修复设计

> 作者：ruan　日期：2026-08-20
> 前置：本缺陷由「消息已读回执」实施过程中发现并记录在
> `2026-08-19-message-read-receipt-design.md` §9，判断为**先于该特性存在、且该特性没有修**，
> 建议单独立项。本文即那个立项。

## 1. 症状

**访客刷新页面后，坐席说过的话会显示成 AI 气泡**，丢掉「客服」标识。

真机复现（2026-08-20，dev+demo，会话 `ed4a5c9a`）：

| 消息 | 来源 | 渲染结果 |
|---|---|---|
| 273 / 274 / 281（坐席发） | 刷新后走历史恢复 | `qy-msg bot`，**无「客服」标识** |
| 282（坐席发） | 实时 SSE | `qy-msg bot` + **「客服」标识** |

同一批消息，刷新前后显示不一致。

## 2. 根因

两条渲染路径的判据不同：

- **实时路径**（`widget/src/main.ts:80`）：凡是 inbox 通道来的 `message` 事件一律当坐席消息渲染，
  不看 role。这是对的——AI 回复走流式通道，根本不进 inbox，所以「来自 inbox」本身就等价于
  「坐席发的」。
- **历史路径**（`widget/src/history.ts:24`）：严格按 role 分派，`ASSISTANT` → `addBotMessage`
  （AI 气泡），`AGENT` → `addAgentMessage`（客服气泡）。

而 `agentReply` 落库写的是 `MessageRole.ASSISTANT`（`ChatServiceImpl:1415`），
`MessageRole.AGENT` **从未被任何代码写入过**。

即：实时路径靠「消息来自哪个通道」区分，历史路径靠 role 区分，而 role 不携带这个信息。
枚举值、widget 的两条分支、坐席台的渲染都早已为 `AGENT` 准备好，唯独后端没写入——
典型的半截脚手架。

## 3. 方案选择

| 方案 | 做法 | 结论 |
|---|---|---|
| **A. 正本清源** | `agentReply` 落 `MessageRole.AGENT` | **采用** |
| B. 加旁路字段 | 不动 role，历史响应另带 `fromAgent` 布尔 | 否。留下两个真相源，且 `MessageRole.AGENT` 继续是永不写入的死值——正是已读回执 spec §3.2 点名批评的形态（「留一个永远为 false 的列会持续误导读代码的人」） |
| C. 最小改动 | 只改落库与渲染判据，不动 LLM 上下文 | 否。退回 AI 后 AI 看不到坐席说过的话 |

## 4. 改动清单

| # | 位置 | 改动 |
|---|---|---|
| 1 | `ChatServiceImpl.agentReply:1415` | `ASSISTANT` → `AGENT`。根因就这一行 |
| 2 | `ChatServiceImpl.buildMessages:1142` | 增加 `AGENT` 分支，与 `ASSISTANT` 一样转 `AssistantMessage` |
| 3 | `AgentPage.tsx:341` | 乐观渲染的占位消息 `role: 'ASSISTANT'` → `'AGENT'` |
| 4 | `AgentPage.tsx:410` 及其上方 393-403 注释 | 已读判据改 `AGENT`，注释整段重写 |
| 5 | `backend/src/main/resources/migration/2026-08-20-fix-agent-message-role.sql` | 新建，见 §6 |
| 6 | 文档 | 见 §8 |

### 4.1 为什么 `buildMessages` 必须加 `AGENT` 分支

该方法拼 LLM 的历史上下文，当前只认 `USER` 与 `ASSISTANT`。改完 role 之后若不加分支，
坐席消息会从历史里整段消失——**会话从人工退回 AI 之后，AI 看不到坐席刚刚说过的话**，
会重复提问或给出与坐席矛盾的答复。坐席消息对 LLM 而言就是助手方发言，转成
`AssistantMessage` 即可，不需要新的消息类型。

### 4.2 ⚠️ 第 3 项是任务接缝，漏了它的症状会被误判成「后端没生效」

`AgentPage.tsx:341` 是坐席点发送后**本地乐观插入**的那条消息，它的 role 是前端硬编码的，
不来自后端响应。已读判据（第 4 项）改成 `AGENT` 之后，若这里仍写 `ASSISTANT`，坐席自己
刚发的那条消息在本地 state 里匹配不上判据——**已读标记要等整页刷新（从后端重新拉到真实
role）才出现**。

这类「同一个语义分散在前后端各写一次」的接缝，单独看任一处代码都正确。

## 5. 明确不改的部分

以下四处都逐一核查过，列在这里是为了让后续读者不必重新查一遍。

### 5.1 `ChatFeedbackService:55` 保持只允许 `ASSISTANT`

改完之后坐席消息自然不可评价。这是**修正而非回归**：此前坐席消息因 role 落成 `ASSISTANT`
而可以被当成 AI 回复点赞/点踩，反馈数据会污染 AI 质量指标。

存量核查：`role='ASSISTANT' AND confidence_score IS NULL AND feedback IS NOT NULL` 有 **0 行**，
迁移不会留下「已有反馈但今后不可评价」的孤儿数据。

### 5.2 13 处分析 SQL 不动

`AbTestReportDao`（5 处）、`AnalyticsRollupDao`（6 处）、`ChatMessageRepository`（2 处）按
`role = 'ASSISTANT'` 统计 AI 指标，但**每一处都额外带 `confidence_score IS NOT NULL` 或
`hit_document_ids IS NOT NULL` 这类代理条件**，坐席消息本就被排除在外——`StatQualityDaily`
的字段注释「AI 回复数（role=ASSISTANT 且置信度非空，排除人工客服消息）」把这个意图写明了。

也就是说，这些 SQL 早就在用代理条件绕过「role 不可区分」这个缺陷。改完 role 之后它们
**更准确**（代理条件从必需退化为冗余），但仍然正确，故本轮不动。清理这些冗余条件是独立的
可选整理，不在本次范围。

### 5.3 widget 不改、也不需要 rebuild

`history.ts` 的 `AGENT` 分支已存在，`main.ts:80` 的判据是
`p.role === 'ASSISTANT' || p.role === 'AGENT'`，两条路径都已经能吃 `AGENT`。
`widget/src/**` 一个字节不动，故不触发 `buildFreshness.test.ts` 的重建要求。

### 5.4 坐席台消息气泡渲染不改

`AgentPage.tsx:548-563` 只区分 `USER` 与非 `USER`，不枚举具体的助手类角色。

## 6. 存量数据迁移

### 6.1 判据是精确的，不是启发式

`role = 'ASSISTANT' AND confidence_score IS NULL` 精确等价于「坐席发的消息」。
理由是写入 `ASSISTANT` 的路径只有三条，已逐一核实：

| 写入路径 | `confidence_score` |
|---|---|
| `ChatServiceImpl.saveMessages:1199`（AI 回复） | 恒非 null——参数是基本类型 `double`，语法上不可能为 null |
| `DemoDataSeeder:310`（演示数据） | 显式 `.confidenceScore(confidence)` |
| `ChatServiceImpl.agentReply:1415`（坐席回复） | 从不设置 → null |

本机 dev 库验证：全库 63 条 `ASSISTANT`，其中 6 条 `confidence_score IS NULL`，
逐条人工核对确认全部是坐席消息（5 条为本轮真机验证所发，1 条 id=132 为 2026-08-05 的旧坐席消息）。

### 6.2 脚本形态

先 `SELECT` 输出待改行供人工核对，再 `UPDATE`；幂等，可重复执行。项目未接入
Flyway/Liquibase，沿用 `migration/` 目录下手工执行的既有惯例（同
`2026-08-20-add-message-read-receipt.sql`）。

⚠️ **每套部署都要各跑一次**。不跑的后果不是报错，而是该库的历史坐席消息继续显示成 AI 气泡，
与新消息表现不一致。

## 7. 测试

**后端单测**（Surefire，纯 Mockito）：

1. `agentReply` 落库的 role 是 `AGENT`——直接钉根因。
2. `buildMessages` 把历史里的 `AGENT` 消息转成 `AssistantMessage`——钉 §4.1，
   防止日后有人「清理」掉那个看起来多余的分支。
3. `ChatFeedbackService` 对 `AGENT` 消息抛 `BusinessException`——钉 §5.1 的语义。

**前端单测**（vitest）：已读判据认 `AGENT`，且**覆盖乐观渲染那条路径**（§4.2 的接缝）。
⚠️ 只断言「后端返回的 AGENT 消息能被标已读」会漏掉接缝——本地乐观插入的那条 role 是前端
自己写的，必须单独走一次「发送 → 本地插入 → 收到访客回执 → 标记落在这条上」。

**手工验证**（自动化测不到）：访客整页刷新后，坐席消息显示为**客服气泡**而非 AI 气泡。
修复前的证据已于 2026-08-20 真机抓取（见 §1 表格），修复后按同一路径复验即可。

## 8. 文档同步

以下三处现在记载的是「判据用 `ASSISTANT` 是对的、原因是 role 不可区分」，修完全部反转：

1. `2026-08-19-message-read-receipt-design.md` §2.3 的实施订正段、§9 的既有缺陷条目。
2. `CLAUDE.md`「改动已读回执链路后必须手工验证」一节中相关表述。
3. `AgentPage.tsx:393-403` 的注释（即 §4 第 4 项）。

⚠️ 不要删掉 §9 那条记录，改成「已由本设计修复」并留指针。它记录的是一个真实缺陷的墓碑，
删掉之后下一个人会重新踩「为什么判据要用 ASSISTANT」这个坑。

## 9. 风险与后续

- **`MessageRole.AGENT` 本轮首次真正被写入**。任何按 role 分支又缺 `AGENT` 分支的消费者会
  掉进 default。全部消费者已在 §4、§5 过了一遍：widget 两条路径、坐席台渲染与已读判据、
  13 处分析 SQL、反馈校验。新增按 role 分支的代码时记得 `AGENT` 已是活跃值。
- **已读回执 B 侧判据的失准隐患随之消解**。已读回执 spec §2.3 曾记录：判据用 `ASSISTANT`
  在「AI 与坐席在同一会话交替回复」时会失准，正解是修根因。本设计就是那个正解，该隐患关闭。
- **多坐席协作仍未解决**：`ChatMessage` 没有 `agentId`，改完 role 也只能区分「坐席 vs AI」，
  区分不了「张三 vs 李四」。那是已读回执 spec §2.7 记录的独立议题，本轮不碰。

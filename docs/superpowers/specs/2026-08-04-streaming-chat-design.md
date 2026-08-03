# 流式聊天完善（前端/widget 主聊天 SSE 增量渲染 + 流式卡片）设计

- 日期：2026-08-04
- 作者：ruan
- 状态：设计已确认，待实现
- 来源：结构化卡片 SP1（2831dba）/ SP2（bff60cf）/ SP3（7abcb0b）的遗留项。SP3 当时判定「流式卡片无第一方消费者（前端/widget 主聊天都不流式）」为 YAGNI 暂缓；本设计把两端主聊天改成流式，同时补齐流式与非流式之间积累的全部语义差异。

## 1. 背景：流式路径长期是「二等公民」

后端两个流式端点早已存在，但两端客户端都走非流式（`ChatPage` → `POST /v1/chat`，widget → `/open/v1/chat/completions`）。因为没有第一方消费者，`streamChat()` 相对 `chat()` 逐步积累了大量能力缺口：

| 能力 | 非流式 `chat()` | 流式 `streamChat()`（改造前） |
|---|---|---|
| 回传 sessionId | 有 | **无**（裸 `Flux<String>` 只有正文） |
| messageId | 有 | 仅开放 API 末尾 `event:meta` 有 |
| suggestHandover（低置信/连续未命中） | 有 | **无**（根本没计算） |
| knowledgeHits（管理端在展示） | 有 | **无** |
| 未命中且 `allowNoKnowledge=false` → fallbackMessage | 有 | **无**（分支缺失） |
| `no_hit_streak` 计数更新 | 有 | **无**（不更新） |
| 结构化卡片 uiParts | 有 | **无**（`withCards=false`，落库 uiSpec 恒 null） |
| 工具调用 toolCallbacks | 有 | **无**（从不挂载） |
| Skill markdown 注入 | 有 | **无**（传 `List.of()`） |
| 模型故障回退 | `callWithFallback` 多模型轮替 | **无**（`getChatClientForScenario` 只取第一个） |
| 消息级 tokenUsage 落库 | 有 | 无（既有取舍，保持；用量计量仍走 `onTokensUsed`） |
| A/B 测试变体 | 有 | 有（`resolveStrategy`/`resolveModelScenario` 两路都走，非缺口） |

若只把客户端切到流式而不补齐，上述能力会**静默失效**——管理端知识命中面板永远空、转人工建议永不出现、未命中兜底策略不生效、工具调用无声消失。因此本次的核心不是"加个打字机效果"，而是**让流式成为与非流式完全同语义的一等路径**。

## 2. 目标与决策记录

用户拍板项：

1. **能力对齐**：全部补齐，流式与非流式同语义。
2. **卡片增量解析位置**：后端解析，发结构化 SSE 事件；客户端零解析器。
3. **流式中断处理**：保留半截文本 + 错误提示，**不自动重发**（重发会造成重复消息与重复计费）。
4. **停止生成按钮**：只管理后台加，widget 不加。
5. **兼容性**：项目尚未上线、无外部集成，**不背向后兼容包袱**，协议一次做干净。
6. **新发现的两处差异（工具/Skill、模型故障回退）**：一并补齐。

## 3. SSE 事件协议

两个端点（`/v1/chat/stream` 管理端、`/open/v1/chat/stream` 开放端）使用**同一协议**，客户端分派逻辑完全一致。所有事件都是命名事件，payload **一律为 JSON 对象**，不存在"默认事件 + 裸文本"的特例。

```
event: start                                    ← 检索完成、LLM 开口前
data: {"sessionId":"s_abc","suggestHandover":false,
       "knowledgeHits":[{"content":"…","score":0.82,"source":"手册.pdf"}]}

event: delta                                    ← 文本增量
data: {"text":"好的，已为你查到订单："}

event: card                                     ← 围栏闭合才发，一次性完整 UI 树
data: {"node":{"type":"card","children":[...]}}

event: delta
data: {"text":"还有别的需要吗？"}

event: done                                     ← 落库完成
data: {"messageId":42}
```

错误事件（改造前流中途抛错只表现为连接断开，客户端无从知道原因）：

```
event: error
data: {"message":"模型调用失败，请稍后重试"}
```

设计要点：

- **`delta` 包 JSON 而非裸文本**：文本 chunk 含换行时，裸文本要靠 SSE 多行 `data:` 拆分 + 客户端 `join('\n')` 还原，还要处理 `data: ` 前导空格剥离规则——三处独立实现（Spring 编码、React、widget）都必须踩准同一套边界。JSON 转义后 SSE 层永远单行，这类边界消失。
- **`start` 放流首而非塞进末尾**：`sessionId` / `knowledgeHits` / `suggestHandover` 在检索完成、LLM 尚未开口时就已全部算出。放流首让 widget 能立刻 `ensureInbox` 建坐席通道（改造前要等整条回复结束），管理端命中面板与转人工提示在开始打字时即出现。只有 `messageId` 必须等落库，留在 `done`。
- **客户端须识别两种中断**：收到 `error` 事件，或流已断但从未收到 `done`。
- **`error` 的 message 是固定文案，不回传原始异常信息**（异常详情只记日志），避免把内部堆栈/SQL/端点地址泄露到浏览器——widget 运行在客户自己的网页里。
- 两个非流式端点保留：`/open/v1/chat/completions` 是第三方服务端集成的 REST 契约（并非所有调用方都想处理 SSE），`/v1/chat` 供 Swagger 调试。

## 4. 后端设计

### 4.1 接口签名收敛

改造前 `ChatService` 有三个 `streamChat` 重载，messageId 靠 `Sinks.one` + `filter(id > 0)` + `Flux.concat` 拼出 meta 事件（`OpenChatController` 中大段注释在解释"为什么这样不会悬挂"）。元数据变多后该模式撑不住，收敛为：

```java
Flux<ChatStreamEvent> streamChat(ChatRequest request);                           // 内部调用，不计量
Flux<ChatStreamEvent> streamChat(ChatRequest request, IntConsumer onTokensUsed); // 开放 API，计量
```

- `ChatStreamEvent`：`START | DELTA | CARD | DONE | ERROR` + 对应 payload record（新增于 `dto/`）。
- messageId 不再需要 `Sinks`——`DONE` 是流末尾的一个正常元素。
- `onAiMessageSaved` 回调随之删除。
- token 计量保持「开放 API 专属」语义不变：只有 `OpenChatController` 传入 `onTokensUsed`。

### 4.2 增量围栏切分器 `StreamingSpecSplitter`

新类（`service/` 下，与 `UiSpecService` 同包），有状态，逐块喂入吐事件：

```java
List<ChatStreamEvent> accept(String chunk);   // 逐块喂入
List<ChatStreamEvent> flush();                // 流结束，吐残余
```

识别规则必须与 `UiSpecService.FENCE` 正则严格同源：起始标记是 `` ```spec `` 后跟可选空白 + 换行，结束标记是 `` ``` ``。两者规则一旦漂移，会出现"流式渲染成卡片、落库却是文本"（或反之）的分裂。

三个必须处理的边界：

- **起始标记被 chunk 切断**（chunk 结尾恰好是 `` ` `` 或 `` ```spe ``）：TEXT 模式下未找到围栏时不能把整个 buffer 吐光，须**留住末尾 6 个字符**（`` ```spec `` 长度 − 1）等待下一块。
- **未闭合围栏**（LLM 被截断）：`flush()` 时残余整段当文本吐出，与 `UiSpecService` 处理 `after` 同语义。
- **围栏内 JSON 非法**：fail-soft 当文本吐出，绝不抛异常阻断回复（延续 `UiSpecService` 铁律）。

**落库不走 splitter**：流末尾仍用 `UiSpecService.parse(完整原文)` 一次性产出 `plainText` + `uiSpec` 落库。两条路径共用同一解析实现，永不漂移；splitter 只决定"何时发 delta、何时发 card"，不承担落库正确性。

一致性由性质测试守护：同一批样本，整块喂 vs 逐字符喂 vs `UiSpecService.parse`，文本结果一致（断言在 trim 归一化后比较——`UiSpecService.parse` 会丢弃 `isBlank` 片段并对 plainText 做 trim，逐块流式不可能做全局 trim，故不要求逐字节相等）。

### 4.3 `streamChat` 语义补齐

| 补什么 | 怎么补 |
|---|---|
| `no_hit_streak` 更新 | 检索后按命中与否更新并 `save`（同 `chat()` 现有实现） |
| fallback 分支 | 未命中 + `allowNoKnowledge=false` → 落库 fallbackMsg，发 `start` + `delta` + `done` |
| suggestHandover | `checkLowConfidenceTrigger(maxScore) \|\| noHitStreakHandover` → 写入 `start` |
| knowledgeHits | 同 `chat()` 组装（含 `resolveConfidenceScore` 换算） → 写入 `start` |
| 工具 + Skill | `chatToolService.resolve()` → `skillMarkdowns` 进 `buildMessages`，`toolCallbacks` 挂到 stream 链 |
| 卡片 Prompt | `buildMessages(..., withCards=true)`，`CARD_PROMPT` 开始对流式注入 |
| 模型故障回退 | 新增 `ModelRouterService.streamWithFallback` |

**人工接管静默、关键词转人工两个早退分支同样要发完整的 `start` + `delta` + `done`**，否则客户端拿不到 sessionId。

`streamWithFallback` 的关键约束：**只在首个 chunk 发出前才能换模型**（已经开吐的流无法重放）。

```java
AtomicBoolean emitted = new AtomicBoolean();
return runner.apply(client(models.get(i)))
    .doOnNext(x -> emitted.set(true))
    .onErrorResume(e -> (emitted.get() || i + 1 >= models.size())
        ? Flux.error(e) : attempt(models, i + 1, runner));
```

### 4.4 管道组装

```java
StringBuilder acc = new StringBuilder();                    // 累积原始全文（含围栏）
Flux<String> raw  = meterContentStream(source, onTokensUsed);
Flux<Ev> body = raw.doOnNext(acc::append)
                   .concatMap(c -> Flux.fromIterable(splitter.accept(c)))
                   .concatWith(Flux.defer(() -> Flux.fromIterable(splitter.flush())));
Mono<Ev> done = Mono.fromCallable(() -> persistAndBuildDone(acc.toString()));
return Flux.concat(Mono.just(start), body, done)
           .onErrorResume(e -> Flux.just(Ev.error(...)));
```

两个语义由 `concat` 天然保证：

- **落库排在正文之后** → `DONE` 里的 messageId 必然有效；
- **客户端中断时 `done` 那个 Mono 根本不会被订阅** → 半截回复不落库，与改造前 `doOnComplete` 语义一致。

其他约束：

- `splitter` 有状态，用 `Flux.defer` 包住创建，避免重复订阅串状态。
- `persistAndBuildDone` 内部 fail-soft（落库失败返回 `messageId = -1` 的 `DONE`），故外层 `onErrorResume` 只捕获 LLM/流错误。
- 落库仍在 Reactor 线程同步执行（`TransactionTemplate` 包裹，同现状），不引入 `boundedElastic` 调度以免牵动事务/上下文语义。

### 4.5 控制器

```java
// ChatController：管理端，不计量
return chatService.streamChat(request).map(sseMapper::toSse);

// OpenChatController：开放端，计量（请求线程内捕获租户上下文，同现状）
return chatService.streamChat(req, tokens -> apiUsageService.record(tid, aid, 1L, tokens))
                  .map(sseMapper::toSse);
```

`ChatStreamSseMapper`（`@Component`，注入 `ObjectMapper`）负责 `ChatStreamEvent → ServerSentEvent<String>` 的序列化，两个控制器共用。管理端 `/v1/chat/stream` 返回类型从裸 `Flux<String>` 改为 `Flux<ServerSentEvent<String>>`。

## 5. 客户端设计

### 5.1 共用：一次性 POST SSE

现有 `sse.ts`（`frontend/src/lib/`、`widget/src/` 各一份）是 GET + 自动重连，为 inbox 长连接而生。对话流必须是 **POST + body + 绝不重连**——重连等于重复发消息与重复计费。

新增 `chatStream.ts`（两端各一份，因鉴权头来源不同：localStorage `satoken`/`X-App-Id` vs `appKey`），**复用现有已测的 `parseSseChunk`**，不重写解析。支持 `AbortSignal`。

### 5.2 管理端 `ChatPage`

不需要写任何新渲染代码：`ChatMessage` 已有 `uiParts`，`JsonRender` 已按序渲染 text/ui 片段。流式只是把 uiParts 从"一次性到达"变为"逐步追加"：

```
delta → 末尾是 text 片段就追加字符，否则 push 新 text 片段
card  → push 一个 ui 片段
```

文本与卡片天然交替成块，SP1 渲染器原样复用；`content` 同步累积作为回退。

事件接线：

- `start` → `setSessionId` + `saveChatSession` + `setSuggestHandover` + 知识命中入 `knowledgeHitsMap`
- `delta` / `card` → 追加到占位 ASSISTANT 消息
- `done` → 占位消息 id 换成真实 messageId；**`knowledgeHitsMap` 以消息 id 为 key，须一并迁移**，否则命中面板会在 id 替换后消失
- `error` → 追加错误提示，保留半截文本

另加**停止生成**（管理端专属）：`AbortController`，流式期间发送按钮变"停止"。顺手把侧栏"流式响应 (即将推出)"文案改掉。

React 状态更新用函数式 `setMessages` 更新最后一条消息（React 19 自动批处理）；不预先做 rAF 节流，真机观察后再定是否需要。

### 5.3 widget

`ui.ts` 新增流式消息句柄，取代一次性的 `addBotMessage`：

```ts
beginBotMessage() → { appendText(t), appendCard(node), finish(feedback?) }
```

- `appendText` 全程 `textContent`；`appendCard` 复用 SP2 的 `renderNode`。XSS 三防（无 innerHTML / image 走 safeUrl fail-closed / 畸形节点返 null）原样延续，不新增攻击面。
- `start` 一到就 `ensureInbox`（改造前要等整条回复结束才建坐席通道）。
- 首个 `delta` 到达即撤掉"输入中…"。
- 会话失效兜底不变：404 在 HTTP 响应阶段判定（尚未开始吐字），现有"清会话重发一次"逻辑照常。
- 改完必须重建 `widget.js` 并提交（LF 行尾）。

**不动**：inbox 通道、`restoreHistory`、`AgentPage`、`addBotMessage`（历史恢复仍在用）。

## 6. 测试与验证

**后端单测（Surefire，免 Docker）**

- `StreamingSpecSplitter`：跨 chunk 切断起始标记（逐字符喂）、多围栏、未闭合围栏、非法 JSON fail-soft、无围栏零回归、**性质测试**（整块喂 vs 逐字符喂 vs `UiSpecService.parse` 三者一致）。
- `streamWithFallback`：首 chunk 前失败 → 切换下一模型；已 emit 后失败 → 透传错误；模型耗尽 → 错误。
- `streamChat` 语义：fallback 分支、`no_hit_streak` 更新、`start` 字段完整性、工具挂载（Mockito 验证 `toolCallbacks` 被调用）。
- 管道语义：cancel 不落库、error 不落库、`done` 排在正文之后。

**后端 IT（Failsafe，需 Docker）**：两个端点 SSE 端到端，断言事件顺序与字段。

**widget vitest**：`postSse` 解析、流式渲染顺序（text/card 交替）、XSS（卡片文本走 textContent）、404 重发。

**前端**：仓库现状无 vitest，靠 `tsc -b` + `pnpm lint` + 真机冒烟兜底。

**真机冒烟（自动化永远抓不到，按 CLAUDE.md 规矩必做）**

1. 逐字出现而非一次性刷出。
2. `sys_api_usage.token_count` 非零 —— `streamUsage` 失效是静默的，记 0 不抛异常。
3. 卡片在围栏闭合瞬间渲染，前后文本位置正确。
4. 知识命中面板 + 转人工建议在流式下出现。
5. **工具调用在流式下真的执行**（Spring AI 2.0 流式 + `toolCallbacks` 是本次最大未知数）。
6. 刷新后历史重放顺序与流式渲染一致（验证落库 uiSpec 正确）。
7. 停止生成后半截回复刷新即消失（验证 cancel 不落库）。

## 7. 风险

- **Spring AI 2.0 流式 + toolCallbacks**：工具执行期间流的中断/续接行为需真机验证，是本次最大未知数。若实测不可用，退化方案为「流式检测到 tool call 时整轮转非流式重跑」，但须先实测再决定，不预先实现。
- **token 计量在带工具时只反映末轮**：既有已知局限（见 `ChatServiceImpl` 注释），沿用不解决。
- **落库仍在 Reactor 线程同步阻塞**：沿用现状，本次不改调度。

## 8. 范围外（YAGNI）

- widget 的停止生成按钮（面向终端客户，保持简洁）。
- 流式中断后自动回退非流式重发（会造成重复消息与重复计费）。
- 卡片专用变体（订单/物流/发票）——通用组件已能表达，SP3 已判定。
- 前端引入 vitest（属独立技术债，不在本次范围）。
- 消息级 tokenUsage 在流式下落库（既有取舍，用量计量已由 `SysApiUsage` 覆盖）。

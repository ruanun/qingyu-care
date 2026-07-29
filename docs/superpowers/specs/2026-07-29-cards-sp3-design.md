# 结构化卡片 SP3（widget 会话恢复+历史卡片 · 表单类交互组件）设计

- 日期：2026-07-29
- 分支：`feat/cards-sp3`
- 作者：ruan
- 来源：结构化卡片 SP1（合入 2831dba）/ SP2（合入 bff60cf）的续作。经勘查，**原 SP3 设想里「流式卡片」当前无第一方消费者（前端/widget 主聊天都不流式）判为 YAGNI 暂缓；「专用变体」通用组件已能表达跳过**。本 SP3 只做两块真价值、自洽的能力（用户拍板）：

## 1. 范围（两块独立能力，一分支）

- **(A) widget 会话恢复 + 历史卡片**：widget 刷新/断线时恢复历史消息（含卡片），闭合 SP1「`getSessionMessages` 未反序列化 ui_spec」留债。
- **(B) 表单类交互组件**：`input`/`select`/`checkbox`/`datetime` + `form`/`submit`，提交=组装文本发出（B1 模型，零新增后端交互）。

## 2. (A) widget 会话恢复 + 历史卡片

### 2.1 现状（已勘查）
- `OpenSessionResponse.of(session, messages)`（`dto/open`，静态工厂，唯一调用点 `OpenSessionController:43`）把 `ChatMessage` 映射为 `Msg{role, content, createdAt}`——**未带 uiParts**（连 uiSpec 都没映射）。
- widget `api.ts` 的 `getSession(sessionId)` **是生产死代码**（仅 `api.ts` 定义 + `api.test.ts` 测试引用，`main.ts`/`ui.ts` 从不调用）；`OpenSessionResponse.messages` 的 Msg 无 uiParts。
- widget `main.ts init`：有 localStorage sessionId 时只 `ensureInbox(sid)` 重连 SSE，**不恢复历史消息**——刷新后消息区空。
- `ChatMessage.uiSpec`（SP1 加的 TEXT 列）存 `List<UiPart>` 的 JSON（`writeUiSpec` 序列化）。

### 2.2 后端：`OpenSessionResponse.Msg` 带 uiParts
- `Msg` 加 `private List<UiPart> uiParts;`（`com.qingyu.care.dto.UiPart`）。
- `of(session, messages)` → `of(session, messages, ObjectMapper objectMapper)`（唯一调用点 `OpenSessionController` 注入 ObjectMapper 传入）：每条 msg 若 `uiSpec` 非空，`objectMapper.readValue(uiSpec, new TypeReference<List<UiPart>>(){})` 反序列化为 uiParts；**fail-soft**：null/空/解析异常 → uiParts 为 null（不抛，历史卡片解析失败只丢卡片、不阻断整个会话恢复）。
- 闭合 SP1 留债。

### 2.3 widget：接通 getSession 恢复历史
- `api.ts`：`OpenSessionResponse.messages` 的 Msg 加 `uiParts?: UiPart[]`（复用 SP2 定义的 `UiPart`）。
- `ui.ts`：加 `addBotMessage` 已支持 uiParts（SP2）；恢复历史需能渲染各角色——复用现有 `addUserMessage`/`addBotMessage(text,feedback?,uiParts?)`/`addAgentMessage`/`showSessionState`。
- `main.ts init`：若 `sessionId`（loadSession）非空 → `await api.getSession(sid)`；成功则**按 createdAt 顺序**渲染历史消息：`USER`→`addUserMessage(content)`、`ASSISTANT`→`addBotMessage(content, undefined, uiParts)`、`AGENT`→`addAgentMessage(content)`、`SYSTEM`→`showSessionState(content)`；随后 `ensureInbox(sid)`。getSession 返回 null（会话过期/失败）则静默跳过恢复、照常 ensureInbox（现有退避重连兜底）。
- 恢复历史时**不重复触发欢迎语**（firstOpen 欢迎逻辑：有历史则视为已开场；实现时确保恢复的历史优先、welcome 不与历史叠加）。
- 反馈按钮：历史 ASSISTANT 消息恢复时**不带反馈**（feedback=undefined，历史消息不再赞踩，避免重复计量/messageId 缺失）。

## 3. (B) 表单类交互组件

### 3.1 节点与提交模型（B1 组装文本）
新增 UI-spec 节点：
- `form`（容器，children 为字段 + submit）；`input`（`props.name` 字段名、`props.placeholder`、`props.label`）；`select`（`props.name`、`props.options: string[]`，MultipleChoice/下拉）；`checkbox`（`props.name`、`props.label`）；`datetime`（`props.name`、`props.label`，`<input type=date>`）；`submit`（`props.label`，提交按钮）。
- **提交模型 B1**：`submit` 点击 → 读取所属 `form` 内所有字段（按 `name`）当前值 → **组装成一条文本消息**（每字段 `name: value` 换行拼接，checkbox 用 是/否 或勾选项）→ 走 `onAction(composed)`（= 发合成用户消息，同 SP1/SP2 按钮）。**零新增后端交互**、容错。未填字段以空值参与或跳过（实现择一，以可读为准）。

### 3.2 渲染（两引擎）
- **widget `render.ts`**：`form`→`<div class=qy-form>`；字段→原生元素（`<input>`/`<select><option>`/`<input type=checkbox>`/`<input type=date>`）带 `data-qy-field=<name>`（DOM 自带状态）；`submit`→按钮，点击时 `closest('.qy-form')` 内 `querySelectorAll('[data-qy-field]')` 收值组装→`onAction`。纯 DOM 直接。
- **前端 `registry.tsx`**：`form`→**有状态 `FormNode` React 组件**（`useState` 收集受控字段值，submit 组装→onAction）——引入首个有状态卡片组件；其余字段节点在 FormNode 内渲染受控 input/select/checkbox/date。`renderNode` 的 `form` case 返回 `<FormNode node={node} onAction={onAction} key=.. />`。
- 单个 `input`/`select` 等**脱离 form 单独出现**时：可渲染为无提交的孤立控件（值无处可提交），或按 form 内才生效——实现以「form 内才有提交语义」为准，孤立字段仍渲染但提交依赖 form。

### 3.3 后端 CARD_PROMPT 追加表单 schema
`ChatServiceImpl.CARD_PROMPT` 追加 form 系列组件说明 + 一个表单示例（如「收集姓名+日期」），让 LLM 会吐 form。仅非流式 chat() 注入（同 SP1）。

### 3.4 XSS / 安全
- 用户输入值（input/select 等）是**用户键入**、作为文本发出——安全；模型给的 label/placeholder/options/name 一律 `textContent`/受控属性，**不** innerHTML。
- 沿用结构守卫（畸形节点返 null）、未知 type 忽略。
- submit 组装的文本经现有发送流（onSend），后端按普通消息处理。

## 4. 测试
- **后端单测（Mockito）**：`OpenSessionResponse.of` 带 ObjectMapper——uiSpec 非空→uiParts 反序列化；uiSpec 空/非法 JSON→uiParts 为 null（fail-soft）；role/content/createdAt 映射不变。
- **widget vitest**：`render.test.ts` 补——form 渲染字段控件；submit 点击组装文本（含多字段）→ onAction 收到；孤立字段不崩。`main`/会话恢复：`getSession` 成功 → 按序渲染各角色历史（含 ASSISTANT uiParts 卡片）、不重复欢迎；返回 null → 静默跳过。（会话恢复接线可用 vitest + jsdom mock api。）
- **前端**：`pnpm build`（tsc）+ `pnpm lint`（无新增）；FormNode 受控输入 + 提交组装靠构建 + 真机；渲染纯函数/组件尽量可测。
- **收尾**：重建 `widget.js`；后端 `mvn verify`；前端 `pnpm build/lint`；widget `pnpm test`。

## 5. 明确不做（YAGNI / 留后续）
- 流式卡片（无第一方流式客户端）；专用变体（订单/物流/发票，通用组件已表达）；B2 结构化提交协议（组装文本够）；前端 ChatPage 历史加载（它本不加载历史、无需求）；表单校验/必填强约束（MVP 组装现值即可）。

## 6. 分期（一分支 `feat/cards-sp3`，任务分组 A→B）
- **T1**：后端 `OpenSessionResponse.Msg.uiParts` + `of(..,ObjectMapper)` 反序列化 fail-soft + `OpenSessionController` 注入 + 单测。
- **T2**：widget 会话恢复——`api.ts` Msg.uiParts + `main.ts` getSession 恢复历史（各角色 + 历史卡片 + 不重复欢迎）+ 单测。
- **T3**：表单 widget——`render.ts` form/input/select/checkbox/datetime/submit + 提交组装 + `styles.ts` 表单样式 + `render.test.ts`。
- **T4**：表单前端——`registry.tsx` FormNode 有状态组件 + 字段控件 + 提交组装 + `types` 无需改（props 已 Record）+ `CARD_PROMPT` 追加 form schema。
- **T5**：收尾——重建 `widget.js` + 后端 `mvn verify` + 前端 `pnpm build/lint` + widget `pnpm test` + 留债登记。

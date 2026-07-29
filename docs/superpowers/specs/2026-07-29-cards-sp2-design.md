# 结构化卡片 SP2（widget 纯 DOM 渲染引擎）设计

- 日期：2026-07-29
- 分支：`feat/cards-sp2`
- 作者：ruan
- 来源：结构化卡片 SP1（协议+后端+管理端渲染，已合入 master 2831dba）的续作。SP2 = 把渲染引擎移植到可嵌网页的独立 Widget（纯 DOM），客户端核心价值。

## 1. 背景与目标

SP1 已打通「LLM 内联 ```spec 围栏 → 后端 UiSpecService 解析为有序 uiParts + 落库 → 管理端 ChatPage(React) 渲染」。**后端 `OpenChatResponse.from` 在 SP1 已带 `uiParts`**，widget 走的开放 API `/open/v1/chat/completions` 经 `chat()`（注入 CARD_PROMPT + 解析）返回的响应**已含 uiParts**——SP2 只需在 widget 侧读取并渲染。

Widget 是独立子项目（`widget/`，纯手写 DOM + Shadow DOM 隔离，构建产物 `widget.js` 提交进仓、由后端作静态资源对外）。目标：在 Shadow DOM 内用**纯 DOM 引擎**渲染卡片（不引 React），XSS 安全，按钮点击回填，构建产物重建并提交。

## 2. 现状勘查（已完成）

- `widget/src/api.ts`：`send(message, sessionId) -> ChatReply {sessionId, reply, handoverSuggested, messageId?}`，走 `/open/v1/chat/completions`。**`ChatReply` 尚无 uiParts**。
- `widget/src/ui.ts`：`mountUi(brand, handlers) -> WidgetUi`；`appendMsg(text, who)` 建 `.qy-text` div、`textContent=text`（XSS 安全）；`addBotMessage(text, feedback?)` 加 bot 消息 + 可选赞踩按钮。样式由 `styles.ts buildStyles(themeColor)` 注入 shadow root。
- `widget/src/main.ts`：`handleSend(message)` → `sendWithRetry` → `ui.addBotMessage(reply.reply, feedback?)`；`handlers.onSend` 是 widget 的发送入口。
- SP1 渲染源 `frontend/src/components/chat/registry.tsx`：`renderNode(node,onAction,key)` + `safeUrl` + `str` + 结构守卫（已含 null/非对象节点→null、children 非数组→空）——本次的移植蓝本。

## 3. 组件设计（widget/，纯 DOM）

### 3.1 类型（`api.ts`）
镜像后端 `UiPart`/`UiNode`：
```ts
export interface UiNode { type: string; props?: Record<string, unknown>; children?: UiNode[]; }
export interface UiPart { kind: string; text?: string | null; node?: UiNode | null; }
```
`ChatReply` 加 `uiParts?: UiPart[]`（后端旧版本可能缺省，故可选）。

### 3.2 DOM 渲染引擎（新 `render.ts`）
`renderNode(node: UiNode | null | undefined, onAction: (value: string) => void): HTMLElement | null`——SP1 `registry.tsx` 的纯 DOM 移植：
- 全部 `document.createElement` + `textContent`（**绝不** `innerHTML` 渲染模型内容）。
- **结构守卫（fail-soft，客户面最关键）**：`node` 非对象/null → 返回 null；`children` 非数组 → 当空数组；未知 `type` → 返回 null（静默忽略、不抛、不阻断其余）。
- `safeUrl(u)`：仅 http/https + 长度上限（≤2048），否则空串（挡 `javascript:`/`data:`/相对 URL）；`image` 的 src 走它、空则不渲染。
- `str(v)`：`v==null?'':String(v)` 收窄。
- 11 组件与 SP1 一一对应：`card`(props.title + children)、`text`(props.value/text，emphasis 加粗)、`kv`(label/value)、`badge`(text/value)、`divider`、`image`(src 经 safeUrl)、`button`(label/value，`addEventListener('click', ()=>onAction(str(value ?? label)))`)、`actions`(按钮组)、`list`(每子节点包一层 li)、`row`、`column`。
- 递归渲染子节点，追加为子元素。

### 3.3 卡片样式（`styles.ts`）
在 `buildStyles(themeColor)` 现有 CSS 追加卡片类（`.qy-card`/`.qy-kv`/`.qy-card-btn`/`.qy-badge`/`.qy-divider`/`.qy-list`/`.qy-row`/`.qy-col` 等），风格与挂件一致、按钮用 themeColor。全部在 shadow root 内，不污染宿主页。

### 3.4 UI 接线（`ui.ts`）
- `addBotMessage(text, feedback?, uiParts?)` 增可选 `uiParts` 形参：**有 ui 片段**（`uiParts` 存在且含至少一个 `kind==='ui'` 且 node 非空）→ 在 bot 气泡内按序渲染（text 片段→文本节点、ui 片段→`renderNode(node, onAction)` 追加）；**否则**回退渲染纯文本 `text`（=`reply`，与现状逐字节一致，零回归）。赞踩反馈按钮逻辑不变、仍附着该 bot 行。
- `onAction` 经 `UiHandlers` 传入：新增 `onAction?: (value: string) => void`（或复用 `onSend`）——按钮点击 = 发合成用户消息，即调 `handlers.onSend(value)`（点击=发文本，同 SP1，零新增后端接口）。

### 3.5 编排（`main.ts`）
`handleSend` 里 `ui.addBotMessage(reply.reply, feedback?, reply.uiParts)`；`mountUi` 的 handlers 里 `onAction` 直接复用 `onSend`（`(v)=>void handleSend(v)`）。坐席消息(`addAgentMessage`)/错误/转人工提示仍纯文本（无 uiParts），不受影响。

### 3.6 重建产物
`cd widget && pnpm build`（tsc && vite build）→ 产出 `backend/src/main/resources/static/widget.js`（提交进仓的构建产物，`.gitattributes` 强制 LF）。改动 `widget/src` 后必须重建并提交 `widget.js`。

## 4. 错误处理 / 安全 / 零回归
- **XSS（客户面，最高优先）**：纯 DOM + textContent，无 innerHTML 渲染模型内容；image URL 走 safeUrl 白名单；未知 type 忽略；结构守卫防畸形节点崩渲染（opus 在 SP1 终审明指此守卫是 SP2 移植到客户面 widget 的硬前置，后果升级为客户事故）。
- **fail-soft**：单个畸形节点只丢该节点，不阻断整条消息/其余卡片；无 uiParts 或全 text → 回退纯文本，与现状逐字节一致（零回归）。
- **无历史卡片**：`getSession` 返回的 messages 仍纯文本 content（SP1 后端 `getSessionMessages` 未反序列化 ui_spec）→ 断线补拉/坐席消息不渲染卡片，属既有留债，SP2 不动（见 §6）。

## 5. 测试（widget vitest + jsdom）
- **`render.test.ts`（新）**：结构守卫（null/undefined 节点→null、children 非数组不崩）；`safeUrl` 拒 `javascript:`/`data:`/相对、准 http/https；未知 type→null；`image` src 非法→不渲染；`button` 点击 → `onAction` 收到 `value`（无 value 用 label）；`card`/`kv`/`text`/`list` 生成预期 DOM 结构与 textContent；**断言无 innerHTML 注入**（如喂 `{"type":"text","props":{"value":"<img src=x onerror=alert(1)>"}}` → 输出 textContent 含该字面串、DOM 里无 `<img>` 子节点）。
- **`ui`/`main` 侧**（补现有测试或新增）：`addBotMessage` 带 uiParts → 渲染卡片；无 uiParts → 纯文本回退；按钮点击 → 触发 onSend(value)。
- 验证：`cd widget && pnpm test`（vitest）+ `pnpm build`（tsc 类型 + vite 构建）。widget **无 lint 脚本**，靠 build 的 tsc 把类型关。

## 6. 明确不做（YAGNI / 留后续）
- 流式卡片（widget 侧增量解析）→ SP3。
- 订单/物流/发票专用变体、表单类（Input/Checkbox/DateTime/提交）→ SP3。
- 历史消息卡片：需后端 `getSessionMessages` / `OpenSessionResponse.messages` 带 ui_spec 反序列化（SP1 既有留债）→ 后续（SP3 或独立）。
- 坐席(真人)消息卡片：坐席回纯文本，无 uiParts → 不适用。

## 7. 分期（一分支 `feat/cards-sp2`）
- **Task 1**：`api.ts` 类型（UiPart/UiNode + ChatReply.uiParts）+ 新 `render.ts` DOM 渲染引擎（11 组件 + safeUrl + str + 结构守卫）+ `render.test.ts`（结构守卫/XSS/safeUrl/未知 type/按钮回填/DOM 结构）。
- **Task 2**：`styles.ts` 卡片 CSS + `ui.ts` `addBotMessage(uiParts)` 接线 + `onAction` 线 + `main.ts` 传 uiParts + 接线单测。
- **Task 3**：收尾——`pnpm build` 重建 `widget.js` 并提交 + `pnpm test` 全绿 + 留债登记（历史卡片/流式=SP3）。真机可延后。

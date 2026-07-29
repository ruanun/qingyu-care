# 结构化卡片 SP1（协议 + 后端 + 管理端渲染，非流式）设计

- 日期：2026-07-29
- 分支：`feat/cards-sp1`
- 作者：ruan
- 来源：第一档能力缺口 #4 结构化卡片（源于 tgo 借鉴；#1 工具调用 / #2 QA 知识库 / #3 真人坐席 已合入 master）。

## 1. 背景与目标

AI 客服目前只能返回纯文本（`ChatResponse.content` / widget `appendMsg` 均纯文本、无 markdown）。#4 目标：AI 除文本外能返回**结构化富卡片**（信息卡/按钮/图文/列表等），渲染成可交互 UI，按钮点击回填。用户选定**对齐 tgo generative-UI**（LLM 产出 UI 结构、前端组件注册表渲染），因体量大拆为 SP1/SP2/SP3：
- **SP1（本设计）**：UI-spec 协议 + 后端产出/解析/契约/落库 + **前端管理端 `ChatPage` React 渲染引擎**，非流式。打通生成式卡片闭环、管理端可见可测。
- SP2：把渲染引擎移植到 widget（纯 DOM）+ 重建 widget.js（客户端核心价值）。
- SP3：流式增量解析（MixedStreamParser）+ 组件扩展（订单/物流/发票变体、表单类）。

## 2. UI-spec 协议

LLM 在回复文本里内联 ` ```spec ` 围栏，围栏内是**一棵完整 JSON UI 树**（**非** tgo 的 JSONL patch——patch 为流式增量而设；SP1 非流式用完整树更简单、LLM 吐得更稳、前向兼容：SP3 流式时每个围栏闭合即整体渲染，仍无需 patch）。

- 节点：`{ "type": "<组件名>", ...props, "children": [<子节点>...] }`。
- 一条回复 = 文字与零或多个 ` ```spec ` 围栏**交错**。
- 后端解析成**有序 `uiParts`**：`[{"kind":"text","text":"..."}, {"kind":"ui","node":{...}}, ...]`。

示例回复：
```
好的，这是您的订单：
```spec
{"type":"card","props":{"title":"订单 #10086"},"children":[
  {"type":"kv","props":{"label":"金额","value":"¥199"}},
  {"type":"kv","props":{"label":"状态","value":"已发货"}},
  {"type":"actions","children":[{"type":"button","props":{"label":"查看物流","value":"查询订单10086物流"}}]}
]}
```
还需要别的帮助吗？
```
→ uiParts = [text("好的，这是您的订单："), ui(card{...}), text("还需要别的帮助吗？")]。

## 3. 后端：产出 + 解析 + 契约 + 落库

### 3.1 产出（Prompt 注入，仅非流式 chat()）
给系统 Prompt 追加一段「卡片能力」指令片段（含组件 schema 摘要 + 一个示例 + 「不需要卡片时正常回复」），复用 #1 工具调用的「向系统提示末尾追加片段」范式（`ChatServiceImpl.appendSkillMarkdowns` 同款）。config 开关 `qingyu.care.cards.enabled`（默认 true）控制是否注入。**`streamChat` 不动**（流式卡片留 SP3）。

### 3.2 解析（后端独占，新 `UiSpecService` / `UiSpecParser`）
把 LLM 原始回复文本解析为有序 `uiParts`：
- 扫描 ` ```spec ... ``` ` 围栏，围栏外文本 → text 片段，围栏内 → tolerant JSON 解析为 ui 节点。
- **fail-soft**：围栏内非法 JSON / 未闭合围栏 → 丢弃该卡片（或并入文本），**绝不抛异常阻断回复**（同 QA 生成的宽松解析范式）。
- 无围栏 → uiParts 只含一个 text 片段（与现状逐字节一致，零回归）。
- 前端/widget **只渲染 uiParts、不含解析器**（干净分层；SP3 流式才在前端加增量解析器）。

### 3.3 契约
- `ChatResponse` 加 `List<UiPart> uiParts`；`content` = **围栏剥离后的纯文本**（无卡片能力的老客户端可读回退）。
- `OpenChatResponse.from(ChatResponse)` 加 `uiParts`（供 widget SP2）。
- `UiPart` DTO：`{ String kind; String text; Object node; }`（node 为解析后的 JSON 树，序列化回前端）。

### 3.4 落库
- `ChatMessage` 加 `ui_spec` 列（`@Column(columnDefinition="TEXT")`，uiParts 的 JSON，可空）；`content` 存**纯文本**（剥离围栏，供摘要/分析/覆盖率等既有消费者，不含围栏噪声）。
- `chat()` 保存 AI 消息时：`content=纯文本`、`ui_spec=uiParts JSON`。
- `getSessionMessages` 回传 content + ui_spec；映射为响应时把 ui_spec 反序列化为 uiParts，历史消息同样能渲染卡片。

### 3.5 chat() 接线
`chat()` 拿到 `aiResp...getText()`（原始回复）后：`UiSpecService.parse(raw)` → `uiParts` + 剥离文本 `plainText`；`saveMessages` 存 plainText + uiParts JSON；`ChatResponse.builder().content(plainText).uiParts(uiParts)...`。多条提前返回路径（静默/转人工/兜底）uiParts 为空/单文本，行为不变。

## 4. 前端管理端渲染引擎（`ChatPage`）

- 新增 `components/chat/JsonRender.tsx`：递归渲染一个 ui 节点；`components/chat/registry.tsx`：`type → React 组件` 注册表，用 shadcn 积木（`card`→Card/CardHeader/CardContent、`button`→Button、`badge`→Badge、`divider`→Separator，`kv`/`text`/`image`/`list`/`row`/`column` 手写小组件）。
- `ChatPage` 渲染 AI 消息：有 `uiParts` → 按序渲染（text 片段纯文本、ui 片段过 JsonRender）；无 → 回退现有 `{msg.content}`。
- **按钮回填**：`button` 点击 → 取 `props.value` → 调 `ChatPage` 的发送逻辑 `chatService.sendMessage(value)`（沿用 tgo「点击=发合成文本」，零新增后端接口）。
- `api/chat.ts` 的响应类型 + `ChatMessage` 前端类型加 `uiParts`。

## 5. MVP 组件集（核心注册表，可扩展）
`card`（title + children，可选边框变体）/ `text`（可选 emphasis 高亮）/ `button` / `actions`(按钮组) / `kv`（标签:值）/ `image` / `list` / `row` / `column` / `divider` / `badge`。订单/物流/发票变体 + 表单类（Input/Checkbox/DateTime/MultipleChoice）留 SP3。

## 6. 错误处理 / 安全 / fail-soft
- LLM 未必吐合法 JSON → 容错解析：单卡片失败只丢该卡、整体回退纯文本，回复不阻断。
- **XSS**：前端用 React 文本节点渲染（**不** `dangerouslySetInnerHTML` 模型内容）；`image`/`link` 的 URL 做协议白名单（仅 http/https）+ 长度上限；未知 `type` 忽略渲染（不报错）。
- 未启用卡片 / 无围栏：uiParts 单 text 片段，与现状逐字节一致（零回归）。
- 多租户：卡片是回复内容的一部分，不新增数据面；ui_spec 随 ChatMessage 受既有租户隔离。

## 7. 测试
- **单测（纯 Mockito）**：`UiSpecService.parse`——纯文本无围栏（单 text）/单围栏交错前后文本/多围栏/围栏内非法 JSON 容错回退/未闭合围栏 fail-soft；`chat()` 组装 uiParts + content 剥离（mock modelRouter 返回带围栏文本）；`OpenChatResponse.from` 带 uiParts；`getSessionMessages` 从 ui_spec 反序列化 uiParts。
- **IT（继承 `AbstractPostgresIT`）**：`ChatMessage.ui_spec` 列持久化往返（saveAndFlush + 读回）。真 LLM 全链 chat IT 沿用既定跳过（依赖真模型端点）；契约层用单测覆盖。
- **前端**：`pnpm build`（tsc）+ `pnpm lint`（无 vitest）；JsonRender 渲染靠构建 + 真机验证；把 JsonRender 的节点→组件映射写成纯函数便于后续可测。

## 8. 分期（一分支 `feat/cards-sp1`）
- **A 协议+解析**：`UiPart` DTO + `UiSpecService.parse`（围栏扫描 + tolerant JSON + fail-soft）+ 单测。
- **B 后端契约+产出+落库**：`ChatResponse`/`OpenChatResponse.from` 加 uiParts + `ChatMessage.ui_spec` 列 + Prompt 卡片片段(config 开关) + `chat()` 接线（parse + 存 + 组装）+ `getSessionMessages` 还原 + 单测 + 持久化 IT。
- **C 前端渲染引擎**：`JsonRender` + 组件注册表(shadcn) + `ChatPage` 接线 + 按钮回填 + api/类型加 uiParts + build/lint。
- **D 收尾**：全量 verify + 前端 build/lint + 留债登记（widget=SP2、流式+组件扩展=SP3）。

## 9. 明确不做（YAGNI / 留后续）
- 流式卡片（streamChat 结构化通道 / 增量解析状态机）→ SP3。
- widget 渲染 → SP2。
- JSONL patch 协议（完整树够 SP1/SP2；SP3 流式再评估是否需 patch）。
- Spring AI 强制 structured output（`BeanOutputConverter`）→ 用 Prompt 注入 + 容错解析（更省改造、pure-text 回复更自然、fail-soft 更简）。
- 工具返回结构化卡片 / QA 挂卡片 → 后续增强（现工具只回文本、QA 只文本答案）。
- 表单类组件与提交、订单/物流/发票专用变体 → SP3。

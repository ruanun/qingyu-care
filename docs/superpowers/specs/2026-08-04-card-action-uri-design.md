# 卡片 Action URI 协议设计

- 日期：2026-08-04
- 作者：ruan
- 状态：设计已确认，待实现
- 来源：`SaaS平台规划.md` Phase 5.1 第一项（tgo 对标近期三项之首）。参考实现见 `references/tgo/repos/tgo-widget-js/src/utils/actionUri.ts` 与 `references/tgo/repos/tgo-web/docs/ui-widgets.md`。

## 1. 背景

结构化卡片能力（SP1/SP2/SP3 + 2026-08-04 流式改造）已收官，但卡片按钮只有**一种**动作：

```java
// frontend/src/components/chat/registry.tsx 的 case 'button'
onClick={() => onAction(str(p.value ?? p.label))}   // 把 value 当用户消息发出
```

widget 侧（`widget/src/render.ts`）同款。CARD_PROMPT 也是这么教 LLM 的：「button（按钮，props.label/props.value，点击会把 value 作为用户消息发出）」。

结果是卡片只能"问下一句"，不能"跳转订单详情"或"复制运单号"——而这两个恰恰是客服场景最自然的动作。tgo 用一套 Action URI 协议解决了这个问题，本设计对齐它。

## 2. 目标与决策记录

用户拍板项：

1. **字段方式**：新增独立的 `props.action` 承载 Action URI，`props.value` 保留为纯文本（对齐 tgo 的 `ActionButton.action`）。
2. **链接安全**：`url://` 沿用现有 `safeUrl`（http/https + 长度 2048）白名单即可，不引入应用级域名白名单。

控制者补充的默认（非用户显式拍板，实现时若有异议可推翻）：

3. **优先级**：`action` 有效则执行动作，否则回退 `value`。
4. **`msg://` 与 `value` 并存**：保留 `msg://`，成本为零（同一回调），让 `action` 单字段能表达全部三种意图。

## 3. 协议

`button.props.action` 格式为 `{protocol}://{content}`，白名单三种协议：

| 协议 | 含义 | 示例 |
|---|---|---|
| `url` | 新标签打开链接 | `url://https://shop.com/o/10086` |
| `msg` | 作为用户消息发出（等价于现有 `value`） | `msg://查询订单10086物流` |
| `copy` | 复制到剪贴板 | `copy://SF13142026` |

### 3.1 解析

新增纯函数 `parseActionUri(raw)`，两端各一份（`frontend/src/lib/actionUri.ts`、`widget/src/actionUri.ts`），返回：

```ts
{ protocol: string; content: string; isValid: boolean }
```

- 正则与 tgo 同源：`/^([a-z]+):\/\/(.*)$/i`，protocol 归一化为小写
- **只有白名单内的三个协议判 `isValid=true`**，其余（含格式不匹配）一律 false
- `content` 走一次 `decodeURIComponent`（`try/catch`，解码失败用原文）——LLM 可能吐编码过的中文

### 3.2 回退链

```
action 有效        → 执行对应动作
action 缺失/无效   → 回退 value，走原来的「发合成消息」
两者都没有         → 按钮正常渲染，点击无反应（fail-soft，不抛不崩）
```

回退是有意设计：LLM 很可能写出 `action: "查询物流"`（漏掉协议前缀），此时按老路发消息比变成死按钮好。tgo 在这种情况下只调一个 `onUnknownProtocol` 回调、默认什么都不做。

**边界（必须按此实现，否则有安全歧义）**：

- **「协议有效但内容被安全策略拒绝」不算无效，不回退 `value`**。例如 `action: "url://javascript:alert(1)"` —— 协议 `url` 在白名单内，`isValid=true`，但 `safeUrl` 拒绝该链接 → **点击无反应**（fail-closed）。此时若回退 `value` 去发消息，等于让一个被安全策略拦下的按钮改做别的事，语义错乱。回退只在**协议本身无效**（不匹配格式 / 不在白名单）时发生。
- **`content` 为空时视为无动作**：`msg://`、`copy://` 空内容一律点击无反应，不发空消息、不复制空串。`url://` 空内容同理（`safeUrl('')` 本就返回空串）。

### 3.3 分派

```
url  → safeUrl(content) 通过才 window.open(url, '_blank', 'noopener,noreferrer')
msg  → onAction(content)                 // 复用现有回填逻辑，零改动
copy → navigator.clipboard.writeText(content)
```

## 4. 安全

- **`url` 必须过现有 `safeUrl`**（http/https + 长度 ≤2048）。这是我们**比 tgo 严格**的地方——tgo 是裸 `window.open(url)`，没有协议校验，靠可选的 `onBeforeOpenUrl` 回调拦截且默认不拦。`safeUrl` 拒绝后按钮点击无反应（fail-closed，与现有 `image` 组件同款语义）。
- **`window.open` 必须带 `noopener,noreferrer`**，防止被打开页通过 `window.opener` 反向操作宿主页。
- **`copy` 的内容只进剪贴板、不进 DOM**，无 XSS 面。
- widget 侧 XSS 三防（全 `textContent`、无 `innerHTML`、畸形节点守卫）原样延续，本设计不新增任何 HTML 注入点。

## 5. 复制的用户反馈

`copy://` 必须给出可见确认，否则用户不知道成没成：

- **前端**：复用已有 `useToast` → 弹「已复制」
- **widget**：无 toast 系统，用按钮自身反馈——文案短暂变「已复制」，2 秒后恢复原 label

**已知限制**：`navigator.clipboard` 需要安全上下文（HTTPS 或 localhost）。widget 嵌在客户自己的站点上，若该站仍是 HTTP，复制会失败——此时按钮显示「复制失败」并 `console.warn`。**不做 `document.execCommand('copy')` 兜底**：该 API 已废弃，且在 Shadow DOM 内需临时插入 textarea 再 select，复杂度不抵这点覆盖率。

## 6. 按钮样式

`button.props.style` 支持三个取值：`default`（默认）/ `primary` / `danger`。

- 前端：映射 shadcn `Button` 的 `variant` —— `outline` / `default` / `destructive`
- widget：`qy-card-btn` 基础类 + `qy-card-btn-primary` / `qy-card-btn-danger`

tgo 另有 `link` / `ghost` 两种，与 `default` 视觉差异小，判 YAGNI 不做。取值非法或缺失时按 `default` 渲染（fail-soft）。

价值在于让「确认收货」与「申请退款」在视觉上分得开。

## 7. CARD_PROMPT 更新

`ChatServiceImpl.CARD_PROMPT` 中 button 的说明改为同时介绍 `value` 与 `action`，并给出三种协议的示例。要点：

- 明确「需要跳转/复制时用 `action`，只是继续对话用 `value`」
- 示例里三种协议各出现一次
- 明确 `style` 的三个取值

## 8. 测试

**widget（vitest）**：
- `parseActionUri` 纯函数：三种有效协议 / 未知协议 / 格式不匹配 / 大小写归一 / 空 content / `decodeURIComponent` 生效
- 分派：stub `window.open` 与 `navigator.clipboard`，断言三种动作各自调用正确、参数正确（含 `noopener,noreferrer`）
- **安全**：`url://javascript:alert(1)` 必须被 `safeUrl` 拒掉、不调用 `window.open`
- 回退链：无效 action 回退 value；两者都无时点击不抛
- 按钮样式：三个取值各渲染出对应 class，非法值落到 default

**前端**：仓库无测试框架（既有现状，不为此引入），靠 `pnpm build` + `pnpm lint`（基线 69 errors / 0 warnings，不得新增）+ 冒烟。

**后端**：改动仅 CARD_PROMPT 字符串。`appendCardPrompt` 已有单测；增加一条断言 Prompt 中确实含三种协议的说明，防止 Prompt 与实现脱节。

## 9. 冒烟验证

让 LLM 生成含三类按钮的卡片，逐个点击验证：

1. `url://` 新标签打开正确页面，且被 `safeUrl` 拒绝的链接点击无反应
2. `msg://` 与纯 `value` 都能正确发出消息
3. `copy://` 复制成功且两端各自的反馈可见
4. widget 侧全部重跑一遍（Shadow DOM 内、且 `window.open` 可能被浏览器弹窗拦截，需确认拦截时不报错）

## 10. 已知风险：跨语言的协议漂移

CARD_PROMPT 在后端（Java 字符串），协议白名单在两端客户端（TypeScript）。**若 Prompt 新增了第四种协议而客户端没跟上，LLM 会生成死按钮，且没有任何自动化测试能发现**——跨语言测不了。

缓解：

- 本 spec 明确「改协议 = 同步改三处」（`CARD_PROMPT` / `frontend/src/lib/actionUri.ts` / `widget/src/actionUri.ts`）
- 在 `CLAUDE.md` 的冒烟清单里补一条，把「三处协议白名单是否一致」列为改动卡片协议后的必查项

## 11. 范围外

- 应用级域名白名单（用户已拍板不做，`safeUrl` 的 http/https 足够）
- `link` / `ghost` 两种按钮样式
- `document.execCommand` 复制兜底
- 除 `button` 外其他组件的 action 支持（如整卡片可点）——目前无需求
- tgo 的专用卡片变体（订单/物流/商品/价格对比）——SP3 已判 YAGNI，通用组件足以表达

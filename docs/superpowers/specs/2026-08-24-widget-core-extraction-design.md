# Widget 协议层抽取 + CLI 调试客户端设计

> `SaaS平台规划.md` Phase 5.3「多端 Widget SDK」的**第一步**，也是它唯一不需要外部账号
> 前置的一步。本期只做协议层抽取与一个 CLI 消费者，**不做任何真实移动端**。
>
> 作者：ruan　日期：2026-08-24

## 1. 目标与非目标

**目标**：把 `widget/src` 里与浏览器无关的协议逻辑抽成 `widget/src/core/`，并写一个面向运维的
CLI 调试客户端作为**第一个非浏览器消费者**。CLI 的价值不在它自己，而在于它能把「协议层里还
粘着多少浏览器假设」逼出来——这件事只靠人工 review 看不出来。

顺带的两个实际收益：现有 widget 变薄、更好测；`actionUri` 的协议白名单从两份变成一份
（CLAUDE.md 那条「三处白名单必须逐字相等」的维护面减小）。

**非目标**（本期明确不做）：

- **任何真实移动端**（iOS / Flutter / 微信小程序）—— 它们各自需要开发者账号、发布通道与
  平台特有的渲染重写，是各自独立的工程。本期只铺路。
- **改动后端任何契约** —— 见 §2.2。CLI 只消费现有的开放 API 与现有的 SSE 事件。
- **附件（上传/下载）进 core** —— 见 §2.4。
- **抽象 HTTP 客户端** —— 见 §2.5。
- **把 core 发成 npm 包 / 建 monorepo** —— 见 §2.1。

## 2. 关键决策

### 2.1 core 放 `widget/src/core/`，不建 monorepo、不独立成子项目

本仓库的既有姿态是「子项目各自独立」：`frontend/` 与 `widget/` 各有 `package.json`、各自
`pnpm install`，根目录**没有** workspace（那两个 `pnpm-workspace.yaml` 只是 pnpm 10 的
`allowBuilds` 配置，不是 monorepo 声明）。

放在 `widget/src/core/` 的两个决定性理由：

1. **不碰 vite 的构建边界**。core 仍在 widget 的 build root 内，`vite.config.ts` 一个字不用改。
   若独立成 `widget-core/`，widget 的 lib 模式就要 import build root **之外**的源文件，
   dev server 还要配 `server.fs.allow`——这是没有先例、必须实测的风险。
2. **保住指纹守护**。`scripts/buildFreshness.test.ts` 把 `widget/src/**/*.ts`（排除 `*.test.ts`）
   的 sha256 写进产物，防「改了源码忘了 rebuild」这个完全无声的失效。core 在 `widget/src/` 下
   **天然仍被覆盖**；移出去则必须同步扩大指纹范围，而改守护本身就是风险点。

代价是语义上 core 看起来「属于 widget」。这个代价比看起来小：**TS 写的 core 本来就只能被
JS 系平台复用**（iOS 与 Flutter 无论如何都要重写一遍协议层），所以「core 是所有端共享的」
这个语义从一开始就不完全成立。真到需要独立的那天 `git mv` 即可，而那时会有第二个 JS 消费者
来证明边界确实划对了。

### 2.2 零后端改动：只用现有可观测信息

运维调试想看的四项里，**一项半是现成的**：

- 开放 API 的流式端点是 `chatService.streamChat(...).map(sseMapper::toSse)` **原样透传**内部
  事件的，而 `ChatStreamEvent.Start` 已经带 `knowledgeHits`，每条含 `content` / `score` /
  `source`——**命中内容与相似度分数本来就在**。
- `suggestHandover`、`uiParts`（卡片原始 spec）、`sessionId` / `messageId` 同样已有。

另外两项没有，本期**不加**：

- **工具调用可观测性**：`ChatStreamEvent` 只有 `START/DELTA/CARD/DONE/ERROR`，无工具事件。
  要加就得动 `ChatServiceImpl.streamChat`——那正是 CLAUDE.md 点名的**本项目最大的一处上游坑**
  （Spring AI 2.0 × Qwen 流式工具调用必炸，内置了非流式降级）。在那条链路上插新事件，风险与
  本期目标完全不成比例。
- **token 用量**：只落 `sys_api_usage`。回传给调用方等于向客户暴露计费数据，是独立的产品决策。

### 2.3 CLI 支持两种鉴权视角，默认服务端

开放 API 是**双路径鉴权**，而两条路径**看到的知识库不一样**（`OpenApiAuthInterceptor`）：

| 路径 | 请求头 | `accessLevel` |
|---|---|---|
| 服务端到服务端 | `X-App-Id` + `X-Api-Key` | `INTERNAL`（可见内部文档） |
| 浏览器 Widget | `X-App-Id` + Origin 白名单 | 匿名访客，**仅见 PUBLIC 文档** |

这对调试工具是决定性的：若 CLI 只有服务端身份，运维排查「访客为什么得到这个回答」时命中的
文档集合比访客大，**很可能复现不出问题，甚至得出误导性结论**（"我这儿明明查得到"）。

故 CLI 两种都支持：默认服务端（`--api-secret`），`--as-visitor --origin <已在白名单里的域名>`
切到访客视角（显式设置 `Origin` 请求头）。这同时给 core 的鉴权抽象提供了两个真实消费者。

### 2.4 附件不进 core

`api.uploadAttachment(sessionId, file: File)` 与 `downloadAttachment(): Promise<Blob>` 带浏览器
类型，`attachments.ts` 是纯 DOM 渲染。

附件的本质是「平台如何表示一个文件」——浏览器 `File`/`Blob`、小程序临时路径、iOS `Data`。
现在抽象它只会做出一个各端都不合用的接口，而 CLI 也不需要。**留在 widget 层**，等真有第二个
消费者时再抽。

### 2.5 `fetch` 不抽象

浏览器与 Node 18+ 都原生提供。小程序的 `wx.request` 是真实差异，但那要等到有小程序端时才有
第二个消费者来验证抽象是否正确——现在抽等于凭空设计。

## 3. core 的边界

### 3.1 进 core

| 模块 | 行数 | 说明 |
|---|---|---|
| `api`（除附件两方法） | ~193 | 请求头构造（含双路径鉴权）、各端点调用 |
| `sse` | 62 | SSE 帧解析 |
| `chatStream` | 100 | 流式事件编排 |
| `history` | 37 | 历史消息归一 |
| `safeUrl` | 10 | http/https 白名单，fail-closed |
| `session` | 46 | 会话持久化（**改造：注入 storage**） |
| `actionUri` | 80 | **拆分：解析进 core，执行留平台层** |

### 3.2 不进 core

`ui` / `render` / `styles` / `logo` / `main` / `attachments`（DOM 渲染）、`config`（从
`<script src="widget.js?appKey=x">` 发现配置，是浏览器特有机制；其他端都是显式传参）、
`api` 的两个附件方法（§2.4）。

### 3.3 平台差异的注入

core 不直接碰任何平台 API，由调用方注入：

```ts
interface Platform {
  storage: { get(key: string): string | null; set(key: string, value: string): void; remove(key: string): void };
  actions: { openUrl(url: string): void; copyText(text: string): void };
}
```

- `storage`：widget → `localStorage`；CLI → 本地文件。**接口只收发字符串**，序列化由 core 内部
  负责——这样各端的存储实现不必关心存的是什么结构，也避免了「某端存对象、某端存字符串」的分叉。
- `actions`：widget → `window.open` / `navigator.clipboard`；CLI → 打印提示（终端里"打开链接"
  的合理行为就是把 URL 显示出来）。

## 4. 两处改造的语义必须原样保留

### 4.1 `session` 的降级不能在搬家时弄丢

现有实现对 localStorage 被禁用（隐私模式）或数据损坏的情况是**降级为无会话**（返回 null），
不是抛错。这条降级要原样保留在 core 里——它有来历，搬家最容易顺手"简化"掉。

### 4.2 `actionUri` 拆分后 fail-closed 语义要跨模块成立

CLAUDE.md 为此专门立过一节：**`url://` 是 fail-closed 的**——被 `safeUrl` 白名单拒绝时
**不回退成发消息**，点击无反应是**正确行为**（协议有效、只是内容被安全策略拦下）；
`executeActionUri` 的返回值表示「是否已作为动作处理」而非「是否成功」。

拆分后这条最容易丢：解析与执行分成两个模块后，「协议无效」（应回退）与「协议有效但内容被
拦下」（不回退）变成了跨模块的区分。core 的解析结果必须把这两种情况**显式区分开**，而不是
都返回"失败"让执行方去猜。

## 5. CLI

独立子项目 `cli/`，**零运行时第三方依赖**：参数解析用 Node 内置 `util.parseArgs`，交互用
`readline`，HTTP 用原生 `fetch`。

⚠️ **「零依赖」指运行时，构建仍需 `typescript`**（devDependency，与 `widget/` 同款）。CLI 要
import core 的 **TS 源码**，所以必须有一步编译——用 `tsc` 而不是引入 tsup/esbuild 之类的打包器。
不要把这条理解成「连 devDependency 都不能有」。

```
qingyu-cli --api-base https://x.com/api --app-key <key> --api-secret <secret>
qingyu-cli --api-base ... --app-key <key> --as-visitor --origin https://shop.example.com
```

⚠️ **两种身份的参数互斥**：`--as-visitor` 时**不需要也不应该**传 `--api-secret`（访客路径不
校验密钥），此时 `--origin` 必填且必须是该应用 `allowedOrigins` 白名单里的域名，否则后端 401。
两者同时给出时以显式报错拒绝，不要静默忽略其一——那会让运维以为自己在用某个视角，实际却是
另一个，而这正是本工具最该避免的误导。

交互循环：输入即发、流式打印。斜杠命令 `/hits`（展开上一轮命中详情）、`/new`（开新会话）、
`/exit`。会话存在 `~/.qingyu-cli/sessions.json`，按 `apiBase + appKey` 分键（同一台机器上
调试多个应用/环境时互不覆盖），下次自动续上。

每轮输出默认简洁、按需展开：

```
> 订单怎么退？
⋯ 命中 3 条  0.72 / 0.68 / 0.51
退货流程是这样的……
✓ messageId=123  建议转人工=否
```

卡片直接打印原始 `ui_spec` JSON——运维要的就是它到底长什么样，不是好看的渲染。

⚠️ **CLI 的跨目录 import 是本次唯一没有先例的构建配置**：`cli/` import `../widget/src/core/*`，
其 tsconfig 需不设 `rootDir`（或设上级）并用 bundler 模块解析。实现时**先跑通一个最小 import
再往下写**，别等写完才发现解析不了。

## 6. 会踩到的既有守护（迁移清单）

1. **后端 Java 单测硬编码了 widget 的文件路径**：`CardActionProtocolSyncTest` 里
   `WIDGET_ACTION_URI = "widget/src/actionUri.ts"`。文件移到 `core/` 后它找不到——好在该测试
   对「正则失配导致集合静默变空」加了非空断言，会**转红而非永真**。必须同步改路径，改完跑一次
   确认它还在真比对。（`ChatCardWiringTest` 的注释里也提到这个路径，一并更新。）
2. **`widget.js` 必须重建并提交**：文件移动会改变指纹，不 rebuild 即红。
3. **测试文件跟着源码走**：本仓库约定测试与被测文件同目录并放。
4. **`session` 与 `actionUri` 的调用方全部要改**（签名与拆分）。

## 7. 测试策略

**core 的测试必须在 vitest 的 `node` 环境下跑通，而不是 jsdom。**

这是本次抽取唯一一条**机器可判定**的「抽干净了」证据。widget 现在整体跑在 jsdom 下，协议层里
粘着 `document` / `localStorage` 之类的东西完全看不出来；把 core 的测试环境切成 node，任何残留
的浏览器假设都会立刻炸出来。它比任何人工 review 可靠，且不必等 CLI 写完就能兑现。

其余分层：

- **CLI 单测**：文件存储实现、参数解析、终端渲染（都是纯函数）。
- **widget 回归**：既有测试全绿（`pnpm test`）+ `widget.js` 重建提交。
- **后端**：`CardActionProtocolSyncTest` 改路径后跑一次，确认三方比对仍然成立。

**手工验证（自动化覆盖不到）**：真机连一次，**同一个问题用两种身份各问一遍**，确认访客视角
命中的文档确实比服务端视角少。这一条同时验证三件事：鉴权抽象接对了、`--as-visitor` 的 Origin
真的走到了浏览器那条鉴权路径、以及文档可见性分级在开放 API 上确实生效。

## 8. 范围外（后续可能）

- iOS / Flutter / 微信小程序端（各自需要账号与发布通道，独立立项）
- 附件进 core（§2.4）
- HTTP 客户端抽象（§2.5，小程序端需要时再做）
- 工具调用可观测性与 token 回传（§2.2）
- core 独立成子项目或发包（§2.1）
- `frontend/src/lib/actionUri.ts` 与 core 的合并（本期只合 widget 那一份；管理端合并是另一条
  依赖链，`frontend` 与 `widget` 之间目前没有任何代码共享通道）

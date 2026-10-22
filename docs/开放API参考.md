# 开放 API 参考

面向**外部接入开发者**：用自己的后端（或自研前端）调用轻语的对话能力，以及接收 Webhook 事件。
界面上去哪儿拿凭据、怎么点，见[使用手册 §2](./使用手册.md#2-创建应用与接入凭据)；只想在网页里嵌一个客服气泡，直接看 [Widget 接入指南](./Widget接入指南.md)。

> 本文所有字段名、头名、状态码均以后端源码为准（`controller/open/`、`dto/open/`、`OpenApiAuthInterceptor`、`service/webhook/`）。

---

## 1. 基本约定

| 项 | 值 |
|---|---|
| 基地址 | `https://<你的域名>/api/open/v1`（后端上下文路径是 `/api`） |
| 请求体 | `application/json; charset=UTF-8`（附件上传除外，用 `multipart/form-data`） |
| 成功响应 | **直接是业务 JSON**，没有外层包装（唯一例外：`POST /sessions/{id}/read`，见 §5.9） |
| 失败响应 | **真实 HTTP 状态码** + `{"error": {...}}`，见 §4 |
| 时间字段 | ISO-8601 本地时间、不带时区，如 `2026-09-25T10:00:00.123`（服务器所在时区） |

⚠️ **开放 API 不用管理端那套「HTTP 200 + `body.code`」约定**。管理端 `/api/v1/**` 的业务错误是 HTTP 200 + `{code, message, data}`；开放 API `/api/open/v1/**` 走的是另一个异常处理器（`OpenApiExceptionHandler`），业务错误直接体现在 HTTP 状态码上。按 HTTP 状态码判断成败即可，别去解析 `code` 字段。

---

## 2. 鉴权：两种身份

每个请求都要带 `X-App-Id`。按**是否带 `X-Api-Key`** 决定走哪条路径：

| 身份 | 请求头 | 校验什么 | 能检索到的知识 | 适用 |
|---|---|---|---|---|
| **服务端** | `X-App-Id` + `X-Api-Key` | App Secret 精确匹配 | `PUBLIC` + `INTERNAL` 文档 | 你自己的后端 |
| **浏览器访客** | `X-App-Id` + `Origin`（浏览器自动带） | Origin 在应用的「Widget 允许来源域名」白名单里 | 仅 `PUBLIC` 文档 | Widget / 自研网页前端 |

- ⚠️ **`X-App-Id` 填的是 App Key**（形如 `app_xxxxxxxxxxxxxxxx`，接入配置页标为「App Id（X-App-Id）」），不是数据库里的应用数字 id。
- ⚠️ **App Secret 只能放在服务端**。它是「持密钥即受信」的凭据，能检索 `INTERNAL` 文档；放进网页、App 包或小程序等于公开。浏览器场景一律走 Origin 白名单这条路。
- ⚠️ 只要带了非空白的 `X-Api-Key` 就走服务端路径，填错直接 401，**不会**回退到 Origin 校验；想走访客路径就别带这个头。
- 缺 `Origin` 时后端会从 `Referer` 解析出 `scheme://host[:port]` 兜底，两者都没有 → 403。
- 白名单是**逗号分隔、逐项 trim 后精确匹配** `scheme://host[:port]`：不支持通配子域，协议、端口、末尾斜杠任何一处不同都不匹配（`https://a.com/` ≠ `https://a.com`）。
- 同一个知识库对两种身份返回的内容不同。排查「访客问不到」时务必用访客身份复现，见 §9 的 CLI。

鉴权通过后依次做：应用启用状态 → 租户状态（冻结 / 套餐到期）→ 限流 → 月度额度。任何一步不过都在进入业务逻辑前直接返回（§4）。

---

## 3. 限流与额度

### 3.1 两层限流

| 层 | 默认值 | 配置项 | 说明 |
|---|---|---|---|
| 应用级 | 60 次/分钟 | `qingyu.care.openapi.default-rate-limit-per-minute`；单个应用可在接入配置页覆盖 | 该应用**所有调用方共享**一个桶 |
| 应用 + 客户端 IP | 20 次/分钟 | `qingyu.care.openapi.per-client-rate-limit-per-minute`（0 = 关闭） | **只作用于浏览器访客路径**（不带 `X-Api-Key`）。IP 取反代传来的客户端地址（nginx 须用 `$remote_addr` 覆盖 `X-Forwarded-For`，见部署文档） |

超限返回 **429** + `Retry-After: 60`（固定 60 秒）。Redis 不可用时限流放行（fail-open）。

- 第二层防的是访客路径下的匿名滥用（App Key 写在网页源码里，`Origin` 头非浏览器客户端可以随便填）。服务端路径要持 App Secret 才进得来，而你的后端通常只有一个出口 IP，按 IP 分桶只会误伤，所以服务端调用只受应用级这一层约束。
- ⚠️ 两层都按**请求数**算，不只是对话：Widget 的配置拉取、inbox 长连接的每次（重）连、typing、已读上报都会消耗额度。
- `Retry-After` 与 `X-Quota-Usage` 已在 CORS 中暴露（`Access-Control-Expose-Headers`），浏览器里的 `fetch` 也读得到。

### 3.2 月度额度（租户级，按套餐）

| 情况 | 表现 |
|---|---|
| 调用次数或 token 数任一达到本月上限 | **403**，`error.reason = "QUOTA_EXCEEDED"` |
| 接近上限（默认 80%，平台配置 `billing.quota.warn-threshold`） | 照常放行，响应头带 `X-Quota-Usage: calls=<已用>/<上限>;tokens=<已用>/<上限>`，不限的维度显示 `unlimited` |
| 额度统计异常 | 放行（fail-open） |

计量只发生在 `chat/completions` 与 `chat/stream` 两个端点（每轮计 1 次调用 + 实际 token）；额度检查则对所有开放 API 请求生效——额度用尽后连 `widget/config` 也会 403。额度每月 1 日重置。

---

## 4. 错误格式与错误码

所有失败响应（鉴权、限流、额度、业务校验）都是同一个形状：

```json
{ "error": { "code": 403, "message": "本月额度已用尽，请升级套餐", "reason": "QUOTA_EXCEEDED" } }
```

`code` 与 HTTP 状态码相同；`reason` 可选，目前只有额度超限会带。`message` 是给人看的中文说明，**不要用它做程序分支**。

| HTTP | message（原文） | 触发条件 |
|---|---|---|
| 400 | `请求参数错误` | 请求体校验失败（如 `message` 为空或超 8000 字、反馈缺字段） |
| 400 | `请求体格式错误` | 请求体不是合法 JSON |
| 400 | `缺少必需参数: <参数名>` | 缺 query 参数（如下载附件漏了 `sessionId`），或 multipart 上传缺 `file` |
| 400 | `参数类型错误: <参数名>` | 参数类型不符，如附件 id 传了非数字 |
| 400 | `请求须为 multipart/form-data` | 上传附件时没用 multipart 请求 |
| 400 | 业务原因原文 | 业务校验失败，如 `feedback 仅允许 1（赞）或 -1（踩）`、`仅可评价 AI 回复消息`、`文件不能超过 20MB`、`不支持的文件类型：xxx`、`单条消息最多携带 9 个附件`、`该会话的附件数量已达上限（20 个）`、`存储空间不足：…`、`会话不属于当前应用` |
| 401 | `缺少 X-App-Id` | 访客路径没带 `X-App-Id` |
| 401 | `缺少 X-App-Id / X-Api-Key` | 服务端路径缺其一 |
| 401 | `无效的凭据` | App Key 不存在，或 App Secret 不匹配 |
| 403 | `应用已停用` | 应用被停用 |
| 403 | `租户已被冻结` / `租户套餐已到期` | 租户状态非 ACTIVE / `expireTime` 已过 |
| 403 | `该应用未开放 Widget 接入` | 访客路径，且应用白名单为空 |
| 403 | `来源域名未授权` | 访客路径，Origin（或 Referer 推出的来源）不在白名单 |
| 403 | `本月额度已用尽，请升级套餐`（`reason: QUOTA_EXCEEDED`） | 月度额度用尽 |
| 404 | `会话不存在或已结束` | 发消息、转人工、上传附件时传的 `sessionId` 不存在，或已被坐席结束（`ENDED`）/ 超时关闭（`TIMEOUT`） |
| 404 | `会话 不存在: <id>` / `附件不存在: <id>` / `消息 不存在: <id>` | 资源不存在或不属于当前应用（两者刻意同语义，防枚举） |
| 429 | `请求过于频繁` | 任一层限流触发，带 `Retry-After: 60` |
| 500 | `服务器内部错误` | 未预期异常；不会回显内部细节 |

---

## 5. 端点

| 方法 | 路径（相对 `/api`） | 用途 |
|---|---|---|
| POST | `/open/v1/chat/completions` | 非流式对话 |
| POST | `/open/v1/chat/stream` | 流式对话（SSE） |
| POST | `/open/v1/chat/feedback` | 对 AI 回复点赞 / 踩 |
| POST | `/open/v1/chat/files` | 上传附件 |
| GET | `/open/v1/chat/files/{id}?sessionId=` | 下载附件 |
| GET | `/open/v1/sessions/{sessionId}` | 会话状态 + 历史消息 |
| POST | `/open/v1/sessions/{sessionId}/handover` | 请求转人工 |
| GET | `/open/v1/sessions/{sessionId}/inbox` | 访客侧实时通道（SSE） |
| POST | `/open/v1/sessions/{sessionId}/read` | 访客上报已读 |
| POST | `/open/v1/sessions/{sessionId}/typing` | 访客「正在输入」 |
| GET | `/open/v1/widget/config` | Widget 品牌配置 |

下文示例统一用这两个变量（换成你自己的；`demo` profile 自带的演示应用就是这组值）：

```bash
BASE=http://localhost:8080/api/open/v1
AUTH=(-H "X-App-Id: demo-cloudboat-shop" -H "X-Api-Key: demo-secret-cloudboat-shop-0001")
```

### 5.1 `POST /chat/completions` 非流式对话

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `message` | string | ✅ | 非空白，≤ 8000 字 |
| `sessionId` | string | | 不传则新建会话；续聊传上一轮返回的值 |
| `userId` | string | | 你侧的用户标识，只在**新建**会话时写入会话 |
| `attachmentIds` | number[] | | 先经 `/chat/files` 上传拿到的 id，最多 9 个，必须属于同一 `sessionId` |

响应体：

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | string | 会话 id（UUID）。**始终以它为准**续聊 |
| `reply` | string | AI 回复正文 |
| `messageId` | number \| null | **AI 回复**的消息 id，提交反馈用 |
| `userMessageId` | number \| null | **你发的这条**消息的 id，与已读水位线比对用。⚠️ 别和 `messageId` 混用 |
| `handoverSuggested` | boolean | 是否建议转人工（低置信度 / 连续未命中 / 关键词触发） |
| `uiParts` | UiPart[] \| null | 带结构化卡片的回复才有：`[{kind:"text", text}, {kind:"ui", node}]` 有序交错 |

```bash
curl -s -X POST "$BASE/chat/completions" "${AUTH[@]}" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u_1001","message":"多少钱包邮？"}'
```

- **一次咨询一个会话**：会话被坐席结束（`ENDED`）或超时关闭（`TIMEOUT`）后不再接收消息。传了这样的 `sessionId`、或服务端根本没有这个会话 → 404 `会话不存在或已结束`，清掉本地记录、不带 `sessionId` 重发即可，后端会新开一个会话。转人工（§5.7）与上传附件（§5.4）同理。
- 传了一个**属于别的应用**的 `sessionId` → 不报错，静默新建会话，响应里是新的 `sessionId`（按「不存在」同语义处理，防枚举探测）。
- 会话处于人工流程（`WAITING_AGENT` / `AGENT_PROCESSING`）时不调模型，`reply` 是固定提示语，坐席的回复要从 inbox（§5.8）或会话详情（§5.6）取。

### 5.2 `POST /chat/stream` 流式对话（SSE）

请求体与 5.1 相同；请求头加 `Accept: text/event-stream`。响应是 `text/event-stream`，事件格式见 §6。

```bash
curl -N -X POST "$BASE/chat/stream" "${AUTH[@]}" \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{"userId":"u_1001","message":"退货运费谁承担？"}'
```

- 鉴权 / 限流 / 额度 / 404 / 参数错误都在**流开始之前**以普通 HTTP 状态码 + JSON 错误体返回（即使请求声明了 `Accept: text/event-stream`），先判 `status` 再读流。
- ⚠️ **流式请求不要自动重试或断线重连**：重连等于重复发一条消息、重复计费。唯一安全的重试是「响应头阶段就拿到 404 → 清会话后重发一次」。
- 客户端中途断开（或流被取消）时，本轮用户消息与半截回复**都不落库**。

### 5.3 `POST /chat/feedback` 反馈

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | ✅ | |
| `messageId` | number | ✅ | AI 回复的 id（completions 的 `messageId` / SSE `done` 的 `messageId`） |
| `feedback` | number | ✅ | `1` 赞 / `-1` 踩，重复提交覆盖 |

响应：`{"success": true}`。只能评价 `ASSISTANT` 消息，消息与会话不匹配按 404 处理。

### 5.4 `POST /chat/files` 上传附件

`multipart/form-data`，两个字段：`file`（文件）、`sessionId`（**必填，且会话必须已存在**——所以第一条消息之前没法传附件）。

| 限制 | 值 |
|---|---|
| 图片 | png / jpg / jpeg / webp / gif，≤ 5MB，≤ 8000 万像素 |
| 文件 | pdf / doc / docx / xls / xlsx / txt / zip，≤ 20MB |
| 真实类型 | 服务端用 Tika 探测内容并与扩展名比对，不信任客户端 `Content-Type`；SVG 不收 |
| 数量 | 单会话最多 20 个（含未发送的），单条消息最多 9 个 |
| 租户存储配额 | 超出返回 400 `存储空间不足：…` |
| 未发送的孤儿 | 上传后 2 小时内没挂到消息上，连同文件一起被清理 |

响应 `AttachmentDTO`：

```json
{ "id": 123, "fileName": "发票.png", "contentType": "image/png", "fileSize": 48213, "width": 800, "height": 600 }
```

`width`/`height` 只有图片才有，**webp 恒为 null**。拿到 `id` 后在下一次对话请求的 `attachmentIds` 里带上。⚠️ 附件必须搭配文字发送（`message` 不能为空）。

```bash
curl -s -X POST "$BASE/chat/files" "${AUTH[@]}" -F "sessionId=<会话id>" -F "file=@./发票.png"
```

### 5.5 `GET /chat/files/{id}?sessionId=` 下载附件

`sessionId` **必填**且必须等于附件所属会话，否则一律 404。响应是文件二进制：图片 `Content-Disposition: inline`，其余强制 `attachment`，都带 `X-Content-Type-Options: nosniff`。

⚠️ `AttachmentDTO` 故意不给 `url`：这个端点靠请求头鉴权，`<img src>` / `<a href>` 不会带自定义头，直接用必失败。浏览器里要 `fetch` 带头取回 Blob，再 `URL.createObjectURL()`。

### 5.6 `GET /sessions/{sessionId}` 会话详情

| 字段 | 说明 |
|---|---|
| `sessionId` | |
| `status` | `AI_PROCESSING` / `WAITING_AGENT` / `AGENT_PROCESSING` / `ENDED` / `TIMEOUT` |
| `agentReadUpToMessageId` | 坐席已读到的消息 id（对方水位线）；无坐席接管或坐席没读过时为 null |
| `messages[]` | `{id, role, content, createdAt, uiParts, attachments}`；`role` 为 `USER` / `ASSISTANT` / `AGENT`（真人坐席）/ `SYSTEM`；`uiParts` 仅含卡片的历史 AI 消息非空；`attachments` 无附件时为 `[]` |

不存在或不属于当前应用 → 404。

### 5.7 `POST /sessions/{sessionId}/handover` 请求转人工

无请求体。会话置为 `WAITING_AGENT`，并触发 Webhook `session.handover_requested`（`trigger: "user_request"`）。会话已结束或已超时时返回 404，状态不变。响应：

```json
{ "sessionId": "…", "status": "handover_requested" }
```

### 5.8 `GET /sessions/{sessionId}/inbox` 访客实时通道（SSE）

长连接，推送坐席消息、会话状态变化、坐席输入中、坐席已读。事件格式见 §6.2。先校验会话归属（越权 → 404）再订阅。

- 用 `fetch` + `ReadableStream` 读，**`EventSource` 不能带自定义头**，用不了。
- 服务端每 25 秒发一个 `:ping` 注释行保活；反代的读超时要大于它（参考部署文档的 nginx 配置）。
- 与对话流相反，inbox **应该**断线重连（带退避）；收到 401/403 则停止重连。每次重连都计入限流。

### 5.9 `POST /sessions/{sessionId}/read` 访客上报已读

请求体 `{"upToMessageId": <number>}`（必填）。水位线单调递增，服务端会把它钳到该会话实际存在的最大消息 id。⚠️ 这是开放 API 里**唯一**带统一响应包的端点：

```json
{ "code": 200, "message": "success", "data": 1024, "timestamp": 1758765600000 }
```

`data` 是生效后的水位线。会话属于别的应用 → 400 `会话不属于当前应用`。会话已有坐席接管时，会实时推给该坐席。

### 5.10 `POST /sessions/{sessionId}/typing` 访客正在输入

无请求体，响应 `{"success": true}`。只在会话已有坐席接管时推给坐席，否则什么都不做。调用方自行节流（Widget 是 ≥ 3 秒一次）。

### 5.11 `GET /widget/config` 品牌配置

```json
{ "appName": "云舟商城客服", "welcomeMessage": "您好…", "avatar": null, "themeColor": "#0d9488" }
```

`appName`：租户配置了对外品牌名（`tenant.display.name`）时用它，否则用应用名。不含任何密钥。

---

## 6. SSE 事件格式

每个事件是标准 SSE 帧：`event:` 行是事件名（全小写），`data:` 行是**单行 JSON 对象**（正文里的换行已在 JSON 里转义，不会出现多行 `data:`），帧之间空行分隔。解析时按 SSE 规范容忍冒号后的可选空格、忽略 `:` 开头的注释行。

### 6.1 对话流（`/chat/stream`）

正常顺序：`start` → 若干 `delta` / `card`（交错）→ `done`；出错以 `error` 收尾。

```
event:start
data:{"sessionId":"5b1e…","suggestHandover":false,"knowledgeHits":[{"content":"退货运费由…","score":0.68,"source":"售后政策.pdf"}],"userMessageId":null}

event:delta
data:{"text":"退货运费"}

event:delta
data:{"text":"由买家承担…"}

event:done
data:{"messageId":1025,"userMessageId":1024}
```

| 事件 | data 字段 | 说明 |
|---|---|---|
| `start` | `sessionId` string；`suggestHandover` boolean；`knowledgeHits` `[{content, score, source}]`；`userMessageId` number\|null | 检索完成、模型开口前发出。`content` 截断到 100 字；`score` 是相似度，调阈值时最直接的依据 |
| `delta` | `text` string | 正文增量，按序拼接 |
| `card` | `node` object | 一个完整的结构化卡片 UI 树（`{type, props, children}`），插在当前正文位置 |
| `done` | `messageId` number\|null；`userMessageId` number\|null | 落库完成后发出。`messageId` 是 AI 回复 id（反馈用），未落库时为 null |
| `error` | `message` string | 固定文案（如 `回复生成失败，请稍后重试`），不含内部异常信息 |

- ⚠️ `start.userMessageId` **在正常问答路径上恒为 null**（本轮消息要到流结束才落库），只有「人工接管中 / 关键词转人工 / 未命中知识兜底」三条提前返回的路径带真实值。需要它就等 `done`——`done` 在落库之后发出，只要落了库就一定带上。
- 流读完既没有 `done` 也没有 `error` = 连接中断，本轮不会落库。
- 会话处于人工流程时，流是 `start` → 一条固定提示的 `delta` → `done`（`messageId` 为 null）。
- 卡片节点的 `type` 集合与渲染规则见 [Widget 接入指南 §5.2](./Widget接入指南.md#52-结构化卡片)；未知 `type` 应当忽略而不是报错。

### 6.2 访客 inbox（`/sessions/{id}/inbox`）

| 事件 | data 字段 | 说明 |
|---|---|---|
| `message` | `sessionId`、`messageId`、`role`（`AGENT`）、`content`、`createdAt`、`attachments`（AttachmentDTO[]）、`uiSpec`（卡片原始 JSON 字符串，通常为 null） | 坐席发来的消息，附件在推送时已挂好 |
| `session` | `sessionId`、`kind`、`text` | 会话状态变化：`accepted`（坐席接入）、`returned_to_ai`（交还 AI）、`ended`（坐席结束或超时关闭）。`text` 是可直接展示的文案。收到 `ended` 后应丢掉本地的 `sessionId`，下一条消息不带它发出 |
| `typing` | `sessionId`、`from`（`agent`） | 坐席正在输入 |
| `read` | `sessionId`、`upToMessageId` | 坐席已读到哪条（访客自己消息上的「已读」标记用它） |
| `:ping`（注释） | — | 每 25 秒一次保活，无 data |

⚠️ `read` 是独立事件，别和 `session` 一样当提示文案展示。`ended` 只推给当时还连着 inbox 的访客；页面没开着的访客下次发消息会收到 404，按上面的规则新开会话即可。

---

## 7. 典型调用流程

**服务端代答（你的后端替终端用户提问）**

1. `POST /chat/completions`（或 `/chat/stream`），不带 `sessionId`；保存返回的 `sessionId`，与你侧用户关联。
2. 后续轮次带上 `sessionId`；收到 404 就清掉重开。
3. `handoverSuggested` 为 true 时可以引导用户转人工 → `POST /sessions/{id}/handover`。
4. 转人工后的坐席回复：订阅 Webhook `agent.replied`，收到后 `GET /sessions/{id}` 拉最新消息（Webhook 载荷里不含消息正文）。

**带附件发送**

1. 先有会话（至少发过一条消息）。
2. `POST /chat/files` 逐个上传，拿到 `id` 列表。
3. 对话请求里带 `attachmentIds` + 文字 `message`。附件属于别的会话、已被用过、或数量超限都会在**流开始前**返回 400/404。

---

## 8. Webhook

事件发生（且事务提交）后，平台向应用配置的回调地址 `POST` 一个带签名的 JSON。回调地址、订阅事件、签名密钥在接入配置页设置。

### 8.1 事件类型

| 事件 | 触发时机 | `data` 额外字段 |
|---|---|---|
| `session.handover_requested` | 访客经开放 API / 管理端请求转人工；或消息命中转人工关键词 | `trigger`：`user_request` / `keyword` |
| `session.accepted` | 坐席接管会话 | — |
| `agent.replied` | 坐席每发一条消息 | — |
| `session.returned_to_ai` | 坐席把会话交还 AI | — |
| `session.ended` | 坐席 / 管理员在管理端结束会话；或会话无活动超时被自动关闭 | `trigger`：`agent` / `timeout` |
| `webhook.test` | 接入配置页点「发送测试事件」 | `message: "测试事件"`（无 `sessionId`） |

- 订阅在接入配置页勾选；只有勾选了的事件才会投递。
- 超时关闭的会话同样发 `session.ended`，按 `trigger` 区分；超时扫描默认每 5 分钟一次，所以事件最多晚几分钟到达。开放 API 没有「结束会话」端点。
- 低置信度 / 连续未命中只让 `handoverSuggested` 为 true，**不触发** `session.handover_requested`，只有用户真的请求或命中关键词才会。

### 8.2 请求格式

请求头：

| 头 | 值 |
|---|---|
| `Content-Type` | `application/json` |
| `X-Qingyu-Event` | 事件类型 |
| `X-Qingyu-Delivery-Id` | 投递 id，与载荷 `id` 相同 |
| `X-Qingyu-Timestamp` | 发送时刻的 Unix 毫秒时间戳（**每次重试都会更新**） |
| `X-Qingyu-Signature` | `sha256=<hex>`；**应用没有签名密钥时不发这个头** |

载荷：

```json
{
  "id": "42",
  "event": "session.handover_requested",
  "createdAt": "2026-09-25T10:00:00.123",
  "data": { "sessionId": "5b1e…", "appId": 7, "trigger": "keyword" }
}
```

| 字段 | 说明 |
|---|---|
| `id` | 投递 id（字符串）。真实事件是数字串，**重试复用同一个**；`webhook.test` 是 UUID 且不入库、不重试 |
| `event` | 事件类型 |
| `createdAt` | 事件发生时间，ISO-8601 本地时间、无时区 |
| `data.sessionId` | 会话 id，可用来调 `GET /open/v1/sessions/{id}` 取详情 |
| `data.appId` | 应用的**内部数字 id**（不是 App Key） |
| `data.trigger` | 仅 `session.handover_requested` 与 `session.ended` 有，取值见 8.1 |

载荷只是通知，不含消息正文、访客信息等内容，需要的话用 `sessionId` 回查。

### 8.3 签名校验

```
X-Qingyu-Signature = "sha256=" + hex( HMAC-SHA256( webhookSecret, X-Qingyu-Timestamp + "." + 原始请求体 ) )
```

接收方要做三件事：

1. **用原始请求体字节复算签名**并常量时间比较。⚠️ 不能先 `JSON.parse` 再序列化回去——键序、空格、中文转义任何一处变化都会让签名对不上。
2. **拒绝时间戳偏差超过 5 分钟的请求**（防重放）。
3. **按载荷 `id` 去重**（见 8.4）。

新建应用时会自动生成 Webhook 签名密钥，在接入配置页查看。接收端应对缺签名的请求一律拒绝。⚠️ 2026-09-25 之前创建的应用可能没有密钥（此时请求不带 `X-Qingyu-Signature`），在接入配置页点一次「重置密钥」即可生成。

**Node.js**（仅用内置 `node:crypto`）：

```js
const crypto = require('node:crypto');

const TOLERANCE_MS = 5 * 60 * 1000;

/**
 * 校验轻语 Webhook 签名。
 * @param {string} secret      应用的 Webhook 签名密钥
 * @param {Buffer|string} rawBody  原始请求体（未经 JSON 解析/重新序列化的字节）
 * @param {string} timestamp   X-Qingyu-Timestamp 头（毫秒）
 * @param {string} signature   X-Qingyu-Signature 头（sha256=<hex>）
 */
function verifyQingyuSignature(secret, rawBody, timestamp, signature, now = Date.now()) {
  if (!timestamp || !signature) return false;
  const ts = Number(timestamp);
  if (!Number.isFinite(ts) || Math.abs(now - ts) > TOLERANCE_MS) return false; // 防重放
  const body = Buffer.isBuffer(rawBody) ? rawBody : Buffer.from(rawBody, 'utf8');
  const expected = 'sha256=' + crypto
    .createHmac('sha256', secret)
    .update(Buffer.concat([Buffer.from(timestamp + '.', 'utf8'), body]))
    .digest('hex');
  const a = Buffer.from(expected);
  const b = Buffer.from(signature);
  return a.length === b.length && crypto.timingSafeEqual(a, b); // 常量时间比较
}
```

接到 Express 上时要拿**原始字节**，别用 `express.json()`：

```js
app.post('/qingyu/webhook', express.raw({ type: 'application/json' }), (req, res) => {
  const ok = verifyQingyuSignature(process.env.QINGYU_WEBHOOK_SECRET, req.body,
    req.get('X-Qingyu-Timestamp'), req.get('X-Qingyu-Signature'));
  if (!ok) return res.status(401).end();
  const event = JSON.parse(req.body.toString('utf8'));
  // 按 event.id 去重后异步处理，先回 2xx
  res.status(204).end();
});
```

**Python**（仅用标准库，3.10+）：

```python
import hashlib
import hmac
import time

TOLERANCE_MS = 5 * 60 * 1000

def verify_qingyu_signature(secret: str, raw_body: bytes, timestamp: str, signature: str,
                            now_ms: int | None = None) -> bool:
    """校验轻语 Webhook 签名。raw_body 必须是原始请求体字节，不能是解析后再序列化的 JSON。"""
    if not timestamp or not signature:
        return False
    try:
        ts = int(timestamp)
    except ValueError:
        return False
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    if abs(now_ms - ts) > TOLERANCE_MS:  # 防重放
        return False
    mac = hmac.new(secret.encode("utf-8"), timestamp.encode("utf-8") + b"." + raw_body, hashlib.sha256)
    expected = "sha256=" + mac.hexdigest()
    return hmac.compare_digest(expected, signature)  # 常量时间比较
```

Flask 里用 `request.get_data()` 取原始字节：`verify_qingyu_signature(secret, request.get_data(), request.headers.get("X-Qingyu-Timestamp"), request.headers.get("X-Qingyu-Signature"))`。

**自测向量**（与后端 `WebhookSignature` 实际输出一致，上面两段代码均已验证）：

| secret | timestamp | body | 期望签名 |
|---|---|---|---|
| `secret` | `1700000000000` | `hello` | `sha256=34e93906d48eb7d7b2bf2fee809e41149b8a3030b5459f29c5ca880f4cc626a6` |
| `whsec_demo_0123456789abcdef` | `1758765600000` | `{"id":"9f1c2e7a-0000-4000-8000-000000000001","event":"webhook.test","createdAt":"2026-09-25T10:00:00","data":{"appId":7,"message":"测试事件"}}` | `sha256=4716b4a562712206cf893ce50df3fa54b082fa5e353e541b2b74c3aaaf19d0c4` |

第二组含中文，用来确认你按 UTF-8 字节计算。自测时把 `now` 传成向量的时间戳，否则会被 5 分钟窗口拒掉。

### 8.4 投递与重试语义

| 项 | 行为 |
|---|---|
| 成功判定 | 回调在超时内返回 **2xx**。3xx 也算失败——**不跟随重定向** |
| 超时 | 连接、读取、等待响应各 5 秒（`qingyu.care.webhook.timeout-ms`） |
| 最大尝试次数 | 5 次，含首次（`max-attempts`） |
| 退避 | 第 1~4 次失败后分别约 1 / 5 / 15 / 30 分钟再试（`backoff-minutes: [1,5,15,30,60]`，重试扫描每 30 秒一轮，实际时间会晚几十秒）；第 5 次仍失败则标记 FAILED、不再投递 |
| 投递语义 | **至少一次**：同一事件可能重复到达（网络超时但你其实已处理、多实例竞争等），重试复用同一个 `id` |
| 顺序 | 不保证。按 `createdAt` 或回查会话当前状态判断，别依赖到达顺序 |
| 回调地址 | 只接受公网可达的 http(s)，保存时校验；指向内网 / 环回 / 云元数据地址会被拒 |

接收方的正确姿势：**先验签 → 按 `id` 去重 → 立刻回 2xx → 异步处理**。处理慢于 5 秒会被判超时并重投。

---

## 9. 调试工具：CLI

仓库里的 `cli/` 是一个零依赖的命令行客户端，复用 Widget 的协议层，专门排查「这条回答为什么不对」：

```bash
cd cli && pnpm install && pnpm build
# 服务端身份（可见 INTERNAL）
node dist/cli/src/index.js --api-base http://localhost:8080/api --app-key <appKey> --api-secret <secret>
# 访客身份（仅见 PUBLIC），origin 必须在白名单里
node dist/cli/src/index.js --api-base http://localhost:8080/api --app-key <appKey> --as-visitor --origin https://www.example.com
```

`--api-secret` 与 `--as-visitor` 互斥且必须二选一。排查访客看到的回答，一定用 `--as-visitor`，否则会得出「我这儿明明查得到」的误导结论。

# 多媒体消息（图片 / 文件）设计

- 日期：2026-08-05
- 作者：ruan
- 状态：设计已确认，待实现
- 来源：`SaaS平台规划.md` Phase 5.1 第三项（tgo 对标近期三项之三，也是最后一项）。参考实现见 `references/tgo/repos/tgo-api/app/models/chat_file.py`、`references/tgo/repos/tgo-api/app/api/v1/endpoints/chat.py:642-794`、`references/tgo/repos/tgo-widget-js/src/types/chat.ts`。

## 1. 背景

客服日常刚需是「用户发截图描述问题」，但 `ChatMessage` 只有 `content` 文本与 `uiSpec` 卡片两个内容载体，**没有任何附件概念**。全仓唯一的上传端点是管理端知识库文档上传（`KnowledgeController` 的 `POST /api/knowledge/{id}/documents`），面向用户、坐席、Widget 的上传能力为零。

### 1.1 勘查订正的一处既有判断

`SaaS平台规划.md` 原本写「存储侧 RustFS 已具备」。勘查发现这个判断**高估了现状**：

- `FileService` 的上传/下载/删除骨架确实在，但只有一个写死 `documents/` 前缀的 `uploadKnowledgeFile`，且**没有任何 MIME / 扩展名校验**（纯靠后续 Tika 解析失败兜底）。
- 桶 `qingyu-care` 由 `docker/docker-compose.yml` 的 `createbuckets` 用 `mc mb` 创建，**全仓没有任何设置公开策略的代码**——是私有桶。因此 `FileInfo.getUrl()` 拿到的裸 S3 URL **浏览器直接访问会 403**，当前只能靠服务端持凭证的 `S3Client` 读取。
- **没有任何把文件 URL 暴露给客户端的端点**。这正是 tgo 有而本项目缺的那块（tgo 的 `GET /v1/chat/files/{file_id}`）。

所以本设计必须补齐：面向用户/坐席的上传端点、文件访问代理端点、类型与大小校验。

## 2. 目标与决策记录

用户拍板项：

1. **双向**：用户发（Widget）+ 坐席发（工作台）。只做单向会让坐席无法回传凭证或表单。
2. **后端代理端点**取文件，不用预签名 URL、也不改成公开桶。理由：直接复用现有多租户鉴权；URL 泄露不等于内容泄露；Widget 走 `OpenApiAuthInterceptor` 也自然成立。代价是每张图过一次后端，客服场景图片量下可接受。
3. **独立附件表 + 消息关联**，不复用 `uiSpec`、也不在 `ChatMessage` 上加列。
4. **AI 不读图**，本期只做存储与展示。不碰 `ModelRouter`、多模态模型配置、token 计量。
5. **图片 5MB / 文件 20MB**，扩展名 + 真实 MIME 双白名单。
6. **上传与发消息分两步**（先传拿 `fileId`，再发消息带 `attachmentIds`）。
7. **坐席端顺手接入卡片渲染**（`AgentPage` 目前连卡片都不渲染，是本特性会放大的既有缺口）。

### 2.1 与 tgo 的差异（有意为之）

tgo 的 `api_chat_files` 是**「文件本身」的记录表**，消息通过 `payload.url` 反向引用；其消息 payload 是 tagged union（`type=1` Text / `type=2` Image `{url,width,height}` / `type=3` File / `type=12` 图文混排）。本设计的附件表**直接与消息关联**，比 tgo 更结构化——因为 tgo 的消息持久化在 WuKongIM（第三方 IM 消息总线）里，本项目没有对应组件，消息就在自己的 `chat_message` 表里，直接建关联更自然。

从 tgo 借鉴的一个实用细节：**Image payload 带 `width`/`height`**，供渲染前预留占位、避免图片加载完成时的布局跳动。

## 3. 数据模型

新建 `ChatAttachment`，**继承 `BaseTenantEntity`**（自动获得 `tenant_id`/`app_id` 两级隔离与写入回填）。上传发生在请求线程、`TenantContext` 可用，故可安全继承（对比 `WebhookDelivery`/`SysApiUsage` 那类异步写入实体必须持显式字段）。

| 字段 | 类型 | 用途 |
|---|---|---|
| `id` | `Long` | 主键，同时是代理端点的路径参数 |
| `messageId` | `Long`，**可空** | 分两步上传，发消息时才回填 |
| `sessionId` | `String` | 上传时即绑定，用于校验「附件属于这个会话」 |
| `fileName` | `String(255)` | 原始文件名（展示用，渲染时必须转义） |
| `fileSize` | `Long` | 字节数 |
| `contentType` | `String(100)` | **服务端探测出的**真实 MIME，不是客户端传的 |
| `storageKey` | `String(500)` | S3 对象键。**存 key 不存 URL**——裸 URL 客户端用不了，存了是误导 |
| `width` / `height` | `Integer`，可空 | 仅图片。上传时用 JDK 自带 `ImageIO` 读取 |
| `uploaderRole` | `MessageRole` | `USER` / `AGENT`，谁传的 |

`ChatMessage` **完全不动**——既不加列，也不加 `@OneToMany` 映射。附件由 Service 层按 `messageId` 查出来再组装进 DTO。

这里有个容易写错的点：`ChatAttachment.messageId` 是普通 `Long` 字段而非 `@ManyToOne ChatMessage`，因此 `ChatMessage` 侧**用不了 `@OneToMany(mappedBy=...)`**（`mappedBy` 要求对方持有关联属性）。不建关联而走 Service 层查询是有意选择：消息历史一次 `findByMessageIdIn(ids)` 批量取，避免逐条 LAZY 加载的 N+1。

`content` 保持 `nullable = false`——纯附件消息存空串，不放宽约束（放宽会波及所有既有写入点）。`sessionId` 用 `ChatSession` 的业务标识（`String(64)`，与 API 层传递的一致），不是主键 `id`。

一条消息可带多个附件。

### 3.1 `messageId` 可空的两个后果（都必须处理）

**孤儿文件**：用户选了图又改主意不发，该行的 `messageId` 永远为 null。新增 `@Scheduled` 清理任务，删除 24 小时前仍未关联消息的附件，**连同 S3 对象一起删**（只删库行会留下永久占用空间的孤儿对象）。间隔外部化，仿本仓库其余 `@Scheduled` 写法。

**IDOR**：发消息时携带 `attachmentIds`，必须校验每个附件**属于当前租户、属于当前会话、且尚未被其它消息占用**。不校验就能引用别人的附件 ID 把别家的图挂到自己消息上。本仓库已在 `findById` 越权治理（`9eb6d39`）与 `addWebPage` 跨租户写（网站知识库的 Critical）上栽过两次，这里直接写进设计。

## 4. 上传与访问链路

### 4.1 上传端点（两条，业务逻辑共用一个 Service）

沿用 `ChatController` / `OpenChatController` 的既有分工：

- `POST /api/chat/files` —— 坐席用，Sa-Token 鉴权
- `POST /open/v1/chat/files` —— Widget 用户用，`OpenApiAuthInterceptor`（`X-App-Id` + Origin 白名单）+ 现有限流

存储走新增的 `FileService.uploadChatFile`，前缀 `chat/yyyy/MM/dd/`，与知识库的 `documents/` 分开。文件名复用既有的 `sanitizeFilename` + UUID 前缀生成规则。

### 4.2 校验四道，顺序不可换

1. **按类型分别限额**：图片 5MB / 文件 20MB。Spring 的 `max-file-size: 100MB` 只是兜底，业务限额在拿到 `MultipartFile` 后判 `getSize()`。
2. **扩展名白名单**：图片 `png/jpg/jpeg/webp/gif`；文件 `pdf/doc/docx/xls/xlsx/txt/zip`。
3. **真实 MIME 探测**：用项目已有的 Tika 探测字节流实际类型，**与扩展名比对，不一致即拒**。只信客户端传的 `Content-Type` 等于没校验——把 `.exe` 改名成 `.jpg` 是最基本的绕过手法。

   ⚠️ Tika 在本项目是**随 `spring-ai-tika-document-reader` 传递引入**的，不是显式声明（`pom.xml:126` 有注释说明：曾经显式 pin 过版本，反而压回了错配版本）。实现时**不要为此新增显式 Tika 依赖**去"稳固"它——那正是当年踩过的坑。
4. **SVG 不进白名单**：它是 XSS 载体，浏览器会执行其中的 `<script>`，而附件是从**我们自己的域**下发的。

### 4.3 访问端点（两条）

- `GET /api/chat/files/{id}` —— 管理端 / 坐席
- `GET /open/v1/chat/files/{id}` —— Widget

鉴权后**再校验附件归属**（租户 + 会话），不能只靠 `findById`（它不受 Hibernate `@Filter` 保护，这是本仓库的成文教训）。

两个实现细节：

- **必须流式返回**。现有 `downloadFile` 返回 `byte[]`，一个 20MB 文件就是 20MB 堆内存乘以并发数。新增流式读取方法。
- **`Content-Disposition` 按类型分**：图片 `inline`（要能内嵌预览），**其余一律 `attachment`**（强制下载，不给浏览器渲染的机会）。

附件内容不可变，响应带 `Cache-Control: private, max-age=...`，避免同一张图反复穿透到 S3。

## 5. 渲染与实时推送

### 5.1 附件 URL 由后端按调用来源生成，不存库

同一个附件，管理端拿到 `/api/chat/files/{id}`、Widget 拿到 `/open/v1/chat/files/{id}`——两条路径鉴权方式不同。这正是 tgo 的 `get_file_access_url(file_id, storage_url)` 抽象存在的理由。

消息 DTO 新增 `attachments: [{id, fileName, contentType, fileSize, width, height, url}]`。

### 5.2 ⚠️ 附件不能复用卡片的 `image` 组件

两个原因，第一个是会静默失败的陷阱：

- `safeUrl` 的白名单只放行 `http:` / `https:`，而附件 URL 是相对路径 `/api/chat/files/1`——**会被直接拒掉**。因为 `safeUrl` 是 fail-closed 设计（返回 `null` 则不渲染），图片会**静默不显示且无任何报错**。
- 语义不同：卡片 `image` 的 URL 是 **LLM 生成的不可信输入**，需要白名单；附件 URL 是**我们自己生成的**，不需要这道校验。

故两端各写独立的附件渲染，不动 `registry.tsx` / `render.ts` 里既有的 `image` 分支。

### 5.3 三处渲染

| 位置 | 现状 | 本期改动 |
|---|---|---|
| `frontend/src/pages/ChatPage.tsx` | 纯文本 + `uiParts` 两分支 | 平行加 attachments 分支 |
| `frontend/src/pages/AgentPage.tsx` | **裸 `{msg.content}`，连卡片都不渲染** | 接入 `JsonRender` + 附件渲染 |
| `widget/src/ui.ts` | 纯文本 + `uiParts` | 加附件渲染 + 输入区上传控件 |

图片渲染带上 `width`/`height` 预留占位；非图片显示图标 + 文件名 + 大小 + 下载入口。

⚠️ **文件名是用户输入**，三端一律走 `textContent` / React 默认转义。Widget 侧尤其要守住既有的「全 `textContent` 无 `innerHTML`」铁律——一个叫 `<img onerror=alert(1)>.png` 的文件，坐席端用 `innerHTML` 就中招。

### 5.4 `RealtimeNotifier.messageJson()` 必须补字段

它当前只序列化 `{sessionId, messageId, role, content, createdAt}` 五个扁平字段。不补 `attachments` 的话，**用户发的图推不到坐席端，「双向」就断了一半**。同时补 `uiSpec`，让接入 `JsonRender` 后的坐席端能收到卡片。

## 6. 安全边界

这是本项目第一次让**外部用户往自己的存储里写东西**，攻击面比前两个特性都大。

| 攻击面 | 防线 | 位置 |
|---|---|---|
| 伪装类型（`.exe` 改名 `.jpg`） | Tika 探测真实 MIME 与扩展名比对 | §4.2 |
| SVG 携带脚本 | 不进白名单 + 非图片强制 `attachment` | §4.2 / §4.3 |
| 路径穿越（文件名含 `../`） | 复用 `sanitizeFilename` + UUID 前缀，不用原始文件名做 key | §4.1 |
| 引用别人的附件（IDOR） | 发消息时校验归属 + 会话 + 未占用 | §3.1 |
| 跨租户读附件 | 代理端点鉴权后再校归属，不只靠 `findById` | §4.3 |
| 文件名 XSS | 三端 `textContent` / React 转义 | §5.3 |
| 刷上传撑爆存储 | 开放 API 侧复用现有限流 + 孤儿文件 24 小时清理 | §3.1 |

## 7. 测试

**单元测试**（覆盖校验逻辑）：
- `.exe` 改名 `.jpg` 被拒（扩展名与探测 MIME 不一致）
- 图片超 5MB 被拒、文件超 20MB 被拒、图片 6MB 但走文件通道也要被拒（限额按探测出的真实类型判定，不按客户端声明）
- SVG 被拒
- `attachmentIds` 的三种拒绝：跨租户、跨会话、已被其它消息占用
- 孤儿清理只删 24 小时前且 `messageId` 为 null 的行

**集成测试**：
- 「上传 → 发消息 → 读取」全链路
- 跨租户访问附件被拒（真实 HTTP 请求流经拦截器才生效，故必须是 IT）

## 8. 冒烟验证（自动化覆盖不到，必须人工）

1. 真机发图，坐席端看得到；坐席回图，用户端看得到（双向各验一次）
2. 图片加载时**不跳动**——验证 `width`/`height` 真的生效
3. `.exe` 改名 `.jpg` 上传 → 被拒
4. 文件名带 HTML 标签（如 `<img onerror=alert(1)>.png`）的文件 → 三端都不执行
5. 传了不发送 → 缩短清理间隔后确认被删，**且 S3 对象也没了**（只删库行会留孤儿对象）
6. 非图片点击 → 触发下载而非浏览器内渲染

## 9. 范围外

- **AI 读图**（多模态模型识别截图内容）——需要动 `ModelRouter`、消息组装、token 计量与额度，且私有桶 + 代理端点下让模型端读到图片还有额外麻烦。用户明确本期不做。
- **缩略图生成**——5MB 上限下原图直出可接受。
- **图片压缩 / 格式转换**
- **病毒扫描**——需要引入外部组件（ClamAV 之类）。
- **断点续传 / 分片上传**——20MB 上限下不必要。
- **消息内图文混排的复杂版式**（tgo 的 `type=12`）——本期一条消息可带多个附件，但不做排版控制。

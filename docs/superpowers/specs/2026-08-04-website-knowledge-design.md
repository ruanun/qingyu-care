# 网站知识库（URL 抓取 + 定期同步）设计

- 日期：2026-08-04
- 作者：ruan
- 状态：设计已确认，待实现
- 来源：`SaaS平台规划.md` Phase 5.1 第二项（tgo 对标近期三项之二，判定为「产品价值最大的一项」）。参考实现见 `references/tgo/repos/tgo-rag/src/rag_service/models/websites.py`。

## 1. 背景

客服知识的主要来源是官网 FAQ / 帮助中心，但目前只能手工上传文档。`DocumentType.URL` 枚举早已占位，却**零实现**——全库无任何抓取代码（`grep -rn "Jsoup\|crawl\|fetchUrl"` 无命中）。运营每次官网改版都要重新导出、重新上传。

tgo 侧对应的是一整套爬虫子系统（`rag_website_pages` 表：`url_hash` 去重、`content_hash` 变更检测、`depth` + `parent_page_id` 层级、`discovered_links` 供深度爬取、8 种页面状态）。本设计**不照搬**——见 §2 的范围决策。

## 2. 目标与决策记录

用户拍板项：

1. **分两期**。本期只做**单页抓取 + 定期同步**；站点爬取（从入口页发现链接、按深度爬）留第二期。理由：完整爬虫要同时解决并发、URL 去重、robots.txt、限速、无限爬取防护、跨域边界，一份 spec 装不下；而本期已能覆盖「同步一批指定的 FAQ 页面」，并把抓取基础设施（SSRF 守卫、正文提取、变更检测、调度）建好，第二期只需增加「发现 URL」这一环。
2. **同步周期按文档配置**（不是全局统一、也不是知识库级）。价格页可能每天变、"关于我们"几乎不变，粒度落在文档上最自然。
3. **正文提取引入 Jsoup**，启发式剔除噪声。理由：知识库噪声直接稀释检索相似度，而导航/页脚文字在 FAQ 页占比不小；Jsoup 轻量无传递依赖，且是第二期「发现链接」的标配工具。

控制者补充的默认（非用户显式拍板，实现时若有异议可推翻）：

4. 复用 `Document` 实体加 5 个字段，不新建表。
5. 抓取失败时**保留旧内容与旧向量**，不清空。
6. 连续失败 10 次后自动转 `MANUAL`。

## 3. 数据模型

复用 `Document`（已 `extends BaseTenantEntity`），新增 5 个字段。这些字段只对 `documentType = URL` 有意义，其他类型为 null：

| 字段 | 类型 | 用途 |
|---|---|---|
| `sourceUrl` | `String(2048)` | 源地址，长度上限与既有 `safeUrl` 一致 |
| `contentHash` | `String(64)` | 正文的 SHA-256，**变更检测的核心** |
| `syncInterval` | `SyncInterval` 枚举 | `MANUAL` / `HOURLY` / `DAILY` / `WEEKLY` |
| `lastSyncedAt` | `LocalDateTime` | 上次**成功**同步时间（失败不更新） |
| `syncFailureCount` | `Integer`，默认 0 | 连续失败次数，任何一次成功清零 |

`status` / `errorMessage` / `content` / `chunkCount` 全部复用既有字段。

**为什么不新建表**：只有 5 个字段，分表会多一次 join 却换不来什么；`ddl-auto: update` 会自动加列。代价是文件类文档带 5 个 null 列，可接受。

新增枚举 `enums/SyncInterval`，每个取值自带间隔时长（`MANUAL` 表示不参与自动调度）。

## 4. 抓取管道

拆成两个组件，边界是「碰不碰数据库」：

### 4.1 `WebPageFetcher`（不碰数据库，纯可测）

```java
record FetchResult(String title, String content, String contentHash, int httpStatus) {}
FetchResult fetch(String url);   // 失败抛 BusinessException
```

内部顺序：

1. **SSRF 守卫**（见 §6）
2. 限时限量下载（连接/读取各 10s，响应体 5MB 上限）
3. Content-Type 白名单校验（`text/html`、`text/plain`）
4. Jsoup 解析，剔除 `script` / `style` / `nav` / `header` / `footer` / `aside` / `noscript` / `iframe` / `form`
5. 取标题（`<title>`，缺失则取首个 `<h1>`）
6. 正文取剔除后的 `body.text()`（Jsoup 会规范化空白）
7. 算正文的 SHA-256

### 4.2 `WebPageSyncService`（落库与摄取编排）

```java
Document addWebPage(Long knowledgeBaseId, String url, SyncInterval interval);  // 建档 + 首次抓取
void syncDocument(Long documentId);                                            // 手动或定时触发
```

**同步/异步边界**（沿用既有 `uploadDocument` 的范式）：`addWebPage` **同步**建档并立即返回（`status = PENDING`），抓取与摄取走 `@Async`——否则用户点「添加」要干等一次网络往返加嵌入。首次抓取失败时 `status = FAILED` 且 `errorMessage` 有值，文档记录保留（运营能看到失败原因并修 URL 重试），不回滚建档。

`syncDocument` 同样是 `@Async`：手动点「立即同步」立刻返回，前端轮询既有的文档状态接口（`getDocumentStatus`）看结果。定时任务调用时本就在后台线程，异步与否不影响。

`syncDocument` 的三条分支是本设计的核心：

| 情况 | 行为 |
|---|---|
| 抓取成功 + `contentHash` **未变** | 只更新 `lastSyncedAt`，**不重嵌**——省 embedding 成本，且不扰动向量库 |
| 抓取成功 + `contentHash` **变了** | 更新 `content`/`contentHash`/`title` → 调既有 `reprocessDocument`（delete-first 重切重嵌）|
| 抓取失败 | **保留旧内容与旧向量**，只记 `errorMessage` 与 `syncFailureCount++` |

第三条是有意的：官网临时 502 不该让知识库瞬间失去这篇内容。抓取失败时旧内容继续服务，直到恢复或人工介入。

## 5. 调度与失败处理

`scheduler/WebPageSyncScheduler`，`@Scheduled` 每小时跑一次。

### 5.1 必须绕过租户过滤器（关键陷阱）

`Document extends BaseTenantEntity`，受 Hibernate `@Filter` 保护。定时任务线程**没有 `TenantContext`**，而 `TenantDataAspect` 对「非平台管理员且无上下文」会填 `-1L`（拒绝一切）。

**所以调度器的到期查询必须用原生 SQL 绕过过滤器**，否则一条文档都查不到——而且是**静默失败**：任务照常跑、日志照常打，就是什么都不同步。这与「多级配置回退」踩的是同一个坑（见 CLAUDE.md），做法沿用它。

下游是安全的：`processDocument` 已处理过 `@Async` 无上下文问题（`KnowledgeServiceImpl:253-255` 注释写明「直接读上下文会写出 appId 空串（历史债根因）」），它优先从**文档实体行**读 `tenantId`/`appId` 而非 ThreadLocal。故调度器调 `reprocessDocument` 不会写出错误归属。

### 5.2 到期判定与失败退避

到期条件：`documentType = URL AND syncInterval <> 'MANUAL' AND (lastSyncedAt IS NULL OR lastSyncedAt + interval <= now)`。

逐个同步，单个失败不影响其他（fail-soft，每个 `syncDocument` 独立 try/catch）。

**连续失败 10 次后自动把 `syncInterval` 置为 `MANUAL`**，并在 `errorMessage` 里写明原因。理由：网站下线或改版导致 URL 永久 404 是常态，让死链每小时重试、一年重试几千次没有意义。转手动后运营在列表里能看到失败状态，自行决定修 URL 还是删除。任何一次成功都把 `syncFailureCount` 清零。

## 6. 安全

抓取 URL 由**租户管理员**填写。多租户 SaaS 里租户不可信——可能填 `http://169.254.169.254/`（云元数据端点）或内网地址做探测。复用工具调用那套 SSRF 硬门槛（`service/tool/RestClientHttpToolExecutor`）：

- **钉 IP**：校验期解析出的 IP 钉入连接期（自定义 `DnsResolver`），杜绝 DNS rebinding / TOCTOU
- **禁跨 host 重定向**：否则 302 能把抓取从公网带进内网
- **限时**：连接/读取各 10s
- **限量**：响应体 5MB 上限，防大文件打爆内存
- **Content-Type 白名单**：只收 `text/html` 与 `text/plain`

正文只进 `Document.content` 与向量库，不进 DOM，无 XSS 面（管理端展示走既有的纯文本渲染）。

## 7. UI

知识库详情页的文档列表：

- 新增「添加网页」按钮 → 弹窗输入 URL + 选同步周期
- URL 类型的文档行额外显示：源地址、上次同步时间、同步状态
- 每行带「立即同步」按钮（手动触发，不受周期限制）

## 8. 测试

**`WebPageFetcher`（纯组件单测）**：
- 喂一段含 `<nav>`/`<footer>`/`<script>` 的 HTML，断言输出**不含**导航与脚本文字、**含**正文
- 标题回退：无 `<title>` 时取 `<h1>`
- SSRF 拒绝内网地址（`127.0.0.1`、`169.254.169.254`、`10.x`）
- 超过 5MB 的响应被拒
- 非白名单 Content-Type（如 `application/pdf`）被拒
- SHA-256 对同一内容稳定、对不同内容不同

**`WebPageSyncService`（单测）**：
- **内容未变时不重嵌**：同一内容同步两次，断言第二次**没有**调用 `reprocessDocument`。这条最容易退化成「每次都重嵌」，而且退化后功能照常、只是悄悄烧 embedding 的钱，没有任何现象能提醒
- 内容变了才重嵌，且 `content`/`contentHash`/`title` 都更新
- 抓取失败时旧 `content` 与 `contentHash` **未被清空**，`syncFailureCount` 递增
- 成功后 `syncFailureCount` 清零、`lastSyncedAt` 更新

**`WebPageSyncScheduler`（单测）**：到期筛选（各周期边界）、连续失败 10 次转 `MANUAL`、单个失败不中断其余。

**集成测试（必需）**：⚠️ **调度器在无 `TenantContext` 时必须真能查到文档**——这正是 §5.1 的陷阱，单测 mock 掉 repository 永远抓不到，只能靠真实 Spring 上下文 + 真实数据库（`AbstractPostgresIT`）验证。

## 9. 冒烟验证

1. 添加一个真实网页（项目文档站或任意公开 FAQ 页），确认正文干净——**人工读一遍入库内容**，看有没有混进导航栏、Cookie 提示、页脚版权
2. 立即同步一次，确认 `chunkCount` 与向量库行数没有翻倍（证明未重复嵌入）
3. 提问一个只有该网页才有答案的问题，确认能检索命中
4. 把 URL 改成死链，手动同步，确认：旧内容仍在、`errorMessage` 有值、`syncFailureCount` 递增
5. 定时任务：把某文档 `lastSyncedAt` 手工改成很久以前，等调度触发，确认它真的被同步了（验证 §5.1 的原生查询绕过过滤器确实生效）

## 10. 范围外

- **站点爬取**（链接发现、深度、`parent_page_id`、`discovered_links`）—— 第二期
- robots.txt 解析与限速 —— 随第二期一起做，单页手工添加不构成爬虫压力
- 每个 URL 自定义 CSS 选择器精确圈定正文 —— 启发式剔除先跑一段时间，不够用再加
- 抓取 JS 渲染的 SPA 页面（需要 headless 浏览器）—— 成本远超收益，FAQ 页基本都是服务端渲染
- 增量嵌入（只重嵌变化的切片）—— 现有 `reprocessDocument` 是 delete-first 全量重嵌，单页体量下够用

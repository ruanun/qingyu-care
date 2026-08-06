# 站点爬取设计（网站知识库第二期）

> 承接已合入的第一期「网站知识库·URL 抓取 + 定期同步」（`2026-08-04-website-knowledge-design.md`，master `e39cb44`）。
> 第一期 §10 把「站点爬取、robots.txt、自定义 CSS 选择器」三项明确留给第二期，本文即这三项的设计。
>
> 作者：ruan　日期：2026-08-06

## 1. 目标与非目标

**目标**：给定一个入口 URL（典型是帮助中心首页），自动发现并抓取该站点范围内的多个页面入知识库，每页一篇文档；站点配置持久化，运营可一键重新爬取以发现新页面；正文提取支持自定义 CSS 选择器，解决第一期启发式去噪对 `<div>` 导航站点无效的已知局限。

**非目标**（沿用第一期 §10 的判断，本期不做）：

- **定期自动重爬整站** —— 见 §2.1 的取舍。站点配置已持久化，将来加一个 `@Scheduled` 即可，不需要重新设计。
- **抓取 JS 渲染的 SPA** —— 需要 headless 浏览器，成本远超收益。
- **增量嵌入** —— 现有 `reprocessDocument` 是 delete-first 全量重嵌，单页体量下够用。
- **额外排除选择器（黑名单）** —— 只做正文选择器（白名单），见 §2.3。
- **爬取任务持久化与重启恢复** —— 见 §2.4。

## 2. 关键决策

### 2.1 新页面靠「手动重爬」发现，不做定期自动重爬

爬取产出 N 篇**独立文档**，每篇沿用第一期的周期同步（内容变更自动跟进、连续失败自动熔断转 `MANUAL`）。站点配置落库，运营想发现新页时点一次「重新爬取」。

**为什么不做定期自动重爬**：定期重爬要多处理「页面消失」这条语义分支（删文档？标失效？），且每轮要抓 N 页。而手动重爬已经拿到 90% 的价值——内容更新本来就由第一期的周期同步自动跟进，只有「站点新增了一篇文章」这一种情况需要人工点一下。页面消失的情况也不需要新逻辑：该文档自己的定期同步会 404 失败、累计 10 次自动转 `MANUAL`，这是第一期熔断的自然延伸。

### 2.2 爬取规模：默认 50 页 / 深度 3，硬顶 500 页 / 深度 5

每次爬取可在硬顶内自定义，表单预填默认值。服务端**必须**对入参钳制到硬顶（不能只靠前端），硬顶经配置项外部化。

粗算依据：每页正文约 2000 字 ≈ 3-4 个 chunk，加 1 秒/页限速，50 页约 2-4 分钟、约 175 次 embedding 调用。硬顶的作用是防误操作——防止一不小心把整个门户站爬进知识库。

### 2.3 自定义选择器只做「正文选择器」（白名单）

配一个容器选择器（如 `article`、`.doc-content`），只取它内部的文本；不配则走第一期的启发式噪声剔除，**行为与现在完全一致**。

**为什么是白名单而不是黑名单**：帮助中心页面结构统一，找到正文容器一次配好整站生效；白名单的效果是确定的（导航本来就不在容器内），黑名单则要为一个站点加好几条、且站点新增的噪声块不会被自动剔除。

**为什么本期必须做**：第一期真机冒烟已证明启发式不够用（用 `<div>` 做导航的站点导航文字大量入库）。爬整站会把这个问题放大 N 倍——**每页都带一份导航**，对检索相似度的稀释比单页严重得多。

### 2.4 爬取任务不持久化，用内存进度（仿 `ReembedService`）

请求线程同步做归属校验 + 建 `Progress` + 返回 `jobId`，`self.@Async` 跑批，前端轮询进度。进程重启则进度丢失，但已建文档都在（每页建档即提交），运营重新点爬取时已存在的 URL 会被跳过——**幂等重跑即是恢复**，不需要 `CrawlJob` 表和恢复逻辑。

### 2.5 删除站点默认不删文档，由用户勾选

删除对话框给一个「同时删除该站点的 N 篇文档」复选框，**默认不勾**。不勾时文档保留、`crawl_site_id` 置 null 退化成普通网页文档，各自继续定期同步。破坏性操作默认关闭但不藏起来——只删配置留 50 篇孤儿文档往往不是用户想要的，而误删 50 篇文档与向量则要重爬重嵌才能恢复。

## 3. 数据模型

### 3.1 新增 `CrawlSite`（表 `crawl_site`）

继承 `BaseTenantEntity`（受两级租户过滤器保护，写入自动回填 `tenant_id`/`app_id`）。

| 字段 | 类型 | 说明 |
|:---|:---|:---|
| `id` | `Long` IDENTITY | |
| `knowledgeBase` | `@ManyToOne(LAZY)` NOT NULL | 爬出来的页都进这个库 |
| `entryUrl` | `varchar(2048)` NOT NULL | 入口 URL，与 `document.source_url` 同宽 |
| `scopePrefix` | `varchar(2048)` NOT NULL | 范围前缀，只爬以此为前缀的 URL |
| `maxPages` | `int` NOT NULL | 本次页数上限，默认 50，服务端按硬顶钳制 |
| `maxDepth` | `int` NOT NULL | 深度上限，默认 3，服务端按硬顶钳制 |
| `contentSelector` | `varchar(500)` NULL | 正文选择器，空则走启发式 |
| `syncInterval` | `varchar(20)` NOT NULL | 爬出的每篇文档写入这个周期 |
| `lastCrawledAt` | `timestamp` NULL | 上次爬取完成时间，列表展示用 |
| `createdAt` / `updatedAt` | `timestamp` | `@CreationTimestamp` / `@UpdateTimestamp` |

⚠️ **`equals`/`hashCode` 显式按 `id` 判等**（`@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)` + `@EqualsAndHashCode.Include` 在 `id` 上）。不声明会直接继承 `BaseTenantEntity` 上 `@Data` 生成的版本——那个版本只比较 `tenantId`/`appId`，两个不同站点在尚未回填租户上下文时会被判定为"相等"。这是 `ChatAttachment` 刚踩过的同一个坑，照它的写法。

### 3.2 `Document` 加一个可空外键

```java
/** 所属爬取站点；单页手动添加的网页文档为 null。 */
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "crawl_site_id")
private CrawlSite crawlSite;
```

**刻意不把 `contentSelector` 冗余到 `Document` 上**：读站点行意味着改了选择器之后，已建文档的下一轮定期同步立刻用新值；冗余则要在改配置时额外回填 N 行。

### 3.3 范围前缀推导规则

创建站点时由入口 URL 推导 `scopePrefix` 预填，用户可改。规则（会同时写进 UI 提示，因为它不直观）：

| 入口 URL | 推导出的前缀 |
|:---|:---|
| `https://x.com/help/` | `https://x.com/help/` |
| `https://x.com/help/faq/article-1.html` | `https://x.com/help/faq/`（丢掉最后一段） |
| `https://x.com/` 或 `https://x.com` | `https://x.com/`（整站） |

⚠️ **无论用户把前缀改成什么，同 host 是硬约束**，服务端强制校验 `scopePrefix` 的 host 与 `entryUrl` 一致。这是安全边界不是配置项——否则等于给了一个"用平台身份抓任意站点"的入口。

## 4. 组件划分

四个新增/改动单元，各一个职责。

### 4.1 `LinkExtractor`（新增，`service/web/`）

纯函数，不碰网络也不碰 DB，本期最好测的一块。

```java
/** 从已解析的 DOM 抽出「同域 + 在范围内 + 已规范化」的链接集合。 */
static Set<String> extract(org.jsoup.nodes.Document dom, String scopePrefix);

/** URL 规范化：去 fragment、去 tracking 参数、统一末尾斜杠。 */
static String normalize(String url);

/** 范围判定，含边界处理——见下方警告，不能写成裸 startsWith。 */
static boolean inScope(String normalizedUrl, String scopePrefix);
```

**规范化的必要性**：不做的话 `/faq`、`/faq/`、`/faq#top`、`/faq?utm_source=x` 会被当成四页各抓一次各建一篇档，同一篇内容在知识库里重复四份、检索时互相挤占 top-K。规范化规则：

1. 去掉 fragment（`#` 之后全部）——同一页的锚点
2. 去掉 tracking 查询参数（`utm_*`、`fbclid`、`gclid`、`spm`），其余参数保留并按名排序（分页/语言参数是有意义的）
3. 路径末尾统一不带 `/`（**根路径除外**：`https://x.com/` 保持原样，否则会退化成不带路径的裸 origin）
4. host 小写；scheme 保持原样（不把 http 升级成 https，那会改变目标）

链接来源是 `a[href]`，用 Jsoup 的 `absUrl("href")` 解析相对地址（`parseHtml` 时已传入 baseUri）。只保留 `http`/`https`（滤掉 `mailto:`、`javascript:`、`tel:`）。

⚠️ **范围判定不能写成裸 `startsWith`，三个边界会同时出问题**：

- 规则 3 把 `https://x.com/help/` 规范化成 `https://x.com/help`，而 `scopePrefix` 按 §3.3 推导出来是带末尾斜杠的 —— 裸 `startsWith(prefix)` 会把**入口页自己**判为范围外，爬取一开始就空转
- 反过来，拿去掉斜杠的前缀做 `startsWith` 又会放进 `https://x.com/helpdesk`（`/help` 是 `/helpdesk` 的前缀），把隔壁目录整个爬进来
- 前缀本身是根路径（`https://x.com/`）时，简单地"补一个斜杠再比"会拼出 `https://x.com//`，一条都匹配不上

统一处理：

```java
static boolean inScope(String u, String prefix) {
    String base = prefix.endsWith("/") ? prefix : prefix + "/";
    String bareBase = base.substring(0, base.length() - 1);   // 去掉末尾斜杠的形态
    return u.equals(base) || u.equals(bareBase) || u.startsWith(base);
}
```

这三个 `return` 分支各对应上面一个边界，测试要逐个覆盖（入口页自身在范围内 / `helpdesk` 类兄弟目录被排除 / 根路径前缀能匹配子页）。

### 4.2 `RobotsChecker`（新增，`service/web/`）

```java
/** 取一次 robots.txt 并解析，结果在本次爬取内复用。 */
static RobotsRules fetch(String origin, WebPageFetcher fetcher);

record RobotsRules(List<String> disallowedPrefixes, Long crawlDelayMs) {
    boolean allows(String url);
}
```

- 只解析 `User-agent: *` 段（本项目不声明自定义 UA，通用段即适用段）
- `Disallow: /path` 按前缀匹配；`Disallow:` 空值表示允许全部
- `Crawl-delay: N`（秒）优先于默认 1 秒限速
- 取 robots.txt 走 `WebPageFetcher` 的下载路径（`text/plain` 本来就在白名单里），SSRF 钉 IP、禁重定向、超时、5MB 上限原样复用
- ⚠️ **取不到（404 / 超时 / 解析失败）按「允许」处理**。绝大多数站点没有这个文件，fail-closed 会让功能对多数站点直接不可用

### 4.3 `SiteCrawlService`（新增，`service/web/`）

BFS 编排 + 内存进度。范式照抄 `ReembedService`：构造器注入 + `@Autowired @Lazy` 自注入 `self`、`ConcurrentHashMap` 存进度、请求线程校验后 `self.@Async` 跑批。

```java
public static final class Progress {
    public final String jobId;
    public final Long siteId;
    public volatile int crawled;    // 已尝试抓取的页数
    public volatile int created;    // 新建档
    public volatile int updated;    // 已存在并触发同步
    public volatile int failed;
    public final int maxPages;
    public volatile String status;  // RUNNING / COMPLETED / FAILED
    public final LocalDateTime startedAt;
    public volatile LocalDateTime finishedAt;
}

/** 建站点 + 立即开爬。归属校验在请求线程同步做，越权直接抛。 */
public CrawlStartResult startCrawl(Long knowledgeBaseId, CrawlSiteRequest req);

/** 重新爬取已有站点，复用其配置。 */
public String recrawl(Long siteId);

public Progress getProgress(String jobId);

@Async
public void runCrawl(Progress p, Long siteId, Long tenantId, Long appId,
                     Long userId, DocumentVisibility accessLevel);
```

⚠️ 进度的 `total` 是**动态的**（BFS 边爬边发现），这与 `ReembedService` 不同（重嵌开始时就知道总数）。所以不设 `total` 字段，前端显示成「已爬 12 / 上限 50 页」。

### 4.4 对第一期的改动（两处，都很小）

**`WebPageFetcher`** 加重载与 DOM 回传：

```java
/** 现有签名保留，等价于 fetch(url, null)。 */
public FetchResult fetch(String url);

/** contentSelector 为空时行为与现在完全一致（走 NOISE_SELECTOR 启发式）。 */
public FetchResult fetch(String url, String contentSelector);

/** 记录加一个字段：未经剔噪的 DOM 副本，供 LinkExtractor 复用同一次抓取的结果。 */
public record FetchResult(String title, String content, String contentHash,
                          int httpStatus, org.jsoup.nodes.Document dom) {}
```

正文提取分支：`contentSelector` 非空时 `doc.select(selector)` 取并集文本，选不中任何元素则**回退启发式**并在日志留痕（选择器写错了不该让整站抓取全线失败）。

⚠️ **`dom` 必须是剔噪之前 `clone()` 出来的副本，不能是同一个对象引用**。现有 `extractFrom` 会**就地修改这棵树**（`doc.select(NOISE_SELECTOR).remove()`），所以若把原树直接放进 `FetchResult`，调用方拿到的已经是被剔掉 `nav`/`header`/`footer` 的残树——而帮助中心的目录导航恰恰常在 `nav` 里，链接发现会几乎抓不到东西。

实现就在 `fetch` 内加一行：解析后先 `org.jsoup.nodes.Document domForLinks = parsed.clone()`，再照旧走 `extractTitle` → `extractFrom`。**这样第一期的 `extractFrom` / `extractTitle` 完全不用改**（连"标题必须在剔噪前取"这条既有约束也原样成立），顺序陷阱被收在 `fetch` 内部一处，调用方无从写错。

代价是多一份 DOM 副本的内存。5MB 响应体上限下 DOM 峰值十几 MB、克隆再翻倍，而爬取是**串行**的（同一时刻只有一份），可接受。

**`WebPageSyncService.syncDocument`** 一行改动：`webPageFetcher.fetch(doc.getSourceUrl())` 改为读所属站点的选择器（`doc.getCrawlSite()` 为 null 时传 null，行为不变）。

## 5. 爬取执行流程

```
runCrawl(progress, siteId, tenantId, appId, userId, accessLevel):
  搬运四个 TenantContext 值           ← 陷阱 1
  try:
    site = 读 crawl_site 行
    rules = RobotsChecker.fetch(origin)
    queue = [(entryUrl, depth=0)];  visited = {normalize(entryUrl)}
    while queue 非空 且 crawled < maxPages:
      (url, depth) = queue.poll()
      if !rules.allows(url): continue        ← 不计入 crawled（没发请求）
      限速等待(rules.crawlDelayMs ?: 1000ms)
      crawled++
      try:
        result = fetcher.fetch(url, site.contentSelector)
        existing = 按 (knowledgeBase, source_url) 查已有文档
        if existing != null:
            webPageSyncService.syncDocument(existing.id);  updated++   ← §5.2
        else:
            建档 PENDING → processDocument → 成功才落 contentHash;  created++  ← §5.3
        if depth < maxDepth:
          for link in LinkExtractor.extract(result.dom, site.scopePrefix):
            if visited.add(link): queue.add((link, depth + 1))
      catch: failed++                        ← 单页 fail-soft，继续下一页
    site.lastCrawledAt = now();  progress.status = COMPLETED
  catch: progress.status = FAILED
  finally:
    TenantContext.clear()                    ← 陷阱 1
    释放该站点的内存互斥                      ← 陷阱 2
```

### 5.1 `maxPages` 计的是「已尝试抓取」而非「成功页数」

计成功数的话，一个到处 404 的站点会让队列不断展开、永远碰不到上限。每次尝试都是一个真实 HTTP 请求，成本上界只有按尝试数算才是真的上界。被 robots 拒绝的 URL 不计入——它根本没发请求。

**入口页失败是唯一的例外**：入口抓不到就没有爬取可言，整个任务标 FAILED（站点行保留，用户改完配置能直接重试）。

### 5.2 重爬遇到已存在 URL：调既有 `syncDocument`，接受抓两次

已存在的页在爬取线程内**同步**调 `webPageSyncService.syncDocument(docId)`，让第一期的三分支原样跑一遍。

⚠️ **必须是同步调用而非 `syncDocumentAsync`**：后者会让 N 篇瞬间涌进 `@Async` 池，robots 的 `Crawl-delay` 与 1 秒限速当场失效，等于对目标站点发起一次并发冲击。`syncDocument` 自带 `@Transactional`，经注入 bean 调用会正常走代理（调度器就是这么调的）。

**取舍交代**：这样做会让这些页在本轮被**抓两次**（一次为提链接、一次在 `syncDocument` 内部）。要避免就得把 `syncDocument` 的三分支从抓取里抽出来（如 `applyFetchResult(doc, result)`），但那段代码是本仓库注释最密、踩坑最多的地方之一——`contentHash` 何时落库、失败计数何时清零、`truncate` 的 flush 时机、咨询锁的位置，每一条都是一个真实缺陷的墓碑。判断：动它的期望损失高于省下几十个 HTTP 请求。

**触发重新考虑的条件**：若实测重爬耗时不可接受（如 200 页站点重爬超过 15 分钟），再单独做这个抽取，并为抽出的方法补齐守护上述四条语义的测试。

顺带的好处：走 `syncDocument` 意味着改了 `contentSelector` 后点重爬，正文立刻变干净，不用等下一轮定期同步——若改成"已存在就跳过"，`syncInterval=MANUAL` 的站点得逐篇手动点「立即同步」才能应用新选择器。

### 5.3 新建文档必须「摄取成功才落 `contentHash`」

这是第一期用一个 Critical 缺陷换来的教训：写早了会让下一轮同步误判「内容未变」而永久跳过重试，该文档 0 向量、永远检索不到、且不再有任何自动重试，**全程无异常无日志**。

爬取的建档路径是新写的代码，**不会自动继承这个教训**，故单列一条：建档时 `contentHash` 留 null，`processDocument` 返回后回读 status，`COMPLETED` 才写 `contentHash`；否则计 `failed`，交给定期同步重试。

## 6. 四个陷阱（本期最容易写错的地方）

### 6.1 爬取线程里建档，租户字段会落 null

第一期没这个问题——`addWebPage` 在**请求线程**建档，只把抓取扔进 `@Async`。而爬取要在 `@Async` 线程里建 N 篇文档，此时 `TenantEntityListener` 从 `TenantContext` 回填拿到的是 null。

做法：请求线程捕获 `tenantId` / `appId` / `userId` / `accessLevel` 四个值作为参数传进 `runCrawl`，方法开头搬进 `TenantContext`。

⚠️ **`finally` 必须 `TenantContext.clear()`**：`AsyncConfig` 用的是 Spring Boot 默认的 `applicationTaskExecutor`（core 8、**线程复用**），不清理就是把上下文泄漏给下一个复用该线程的任务。这正是流式聊天改造踩过的坑（当时实测复现过平台管理员会话落进别人租户）。

### 6.2 站点级互斥用内存 map，并设全局并发上限

同一站点已有爬取在跑就拒绝（`ConcurrentHashMap.putIfAbsent`），**全局最多 2 个并发爬取任务**（配置项）。

后者不只是对目标站点礼貌的问题：`@Async` 池 core 8 是**和审计日志异步落库、webhook 直投、文档摄取共享的**，几分钟的长任务无上界并发会挤掉那些短任务。

多实例下内存互斥失效，最坏是两个实例各爬一遍产生重复文档。**不加 `(knowledge_base_id, source_url)` 唯一约束来兜底**：第一期 `addWebPage` 没有去重校验，存量库可能已有重复 URL，`ddl-auto: update` 建唯一索引会直接让应用起不来。用「建档前查一下就跳过」+ 内存互斥，接受这个概率极小的重复。

### 6.3 站点级不能用第一期那把咨询锁

`pg_try_advisory_xact_lock` 是**事务级**的，锁随事务结束释放；而爬取是几分钟的长任务、必须逐页提交（否则一个大事务跑几分钟、中途失败全丢）。硬要用就得把整段圈进一个事务，与逐页提交直接冲突。

逐页的 `processDocument` 内部仍各自抢自己文档那把锁，**文档级互斥不受影响**——爬取与「定期同步」「重嵌批任务」「手动重新处理」之间的互斥仍由它保证。

### 6.4 提链接用的 DOM 必须是剔噪前的副本

见 §4.4。`extractFrom` 就地移除 `nav`/`header`/`footer`，而帮助中心的目录导航恰恰常在这些标签里。把原树直接交给 `LinkExtractor` 的后果是链接发现几乎抓不到东西，且**不报错**——表现为「爬了 1 页就结束」，很容易被误判成范围前缀配错（而 §4.1 那三个边界确实也会造成同样的表象，两个原因表现完全一致，排查时要分别验证）。

## 7. 端点与前端

### 7.1 后端端点（挂在现有 `KnowledgeController`）

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| POST | `/knowledge/{kbId}/crawl-sites` | 建站点并立即开爬，返回 `{siteId, jobId}` |
| GET | `/knowledge/{kbId}/crawl-sites` | 列该库的站点 |
| GET | `/knowledge/crawl-jobs/{jobId}` | 查进度 |
| POST | `/knowledge/crawl-sites/{siteId}/recrawl` | 重新爬取，返回 `jobId` |
| PUT | `/knowledge/crawl-sites/{siteId}` | 改配置（前缀/深度/页数/选择器/周期） |
| DELETE | `/knowledge/crawl-sites/{siteId}?deleteDocuments=false` | 删站点，可选连带删文档 |

- 写操作一律带 `@AuditLog`
- 知识库归属**必须**经 `knowledgeService.getKnowledgeBase(id)` 取（内含 `TenantOwnershipGuard`）——直接用 `knowledgeBaseRepository.findById` 不受 `@Filter` 保护，等于让任意租户往别人知识库里插文档。这是第一期的 Critical 越权教训
- 站点归属：`CrawlSite extends BaseTenantEntity`，普通 JPA 查询受 `@Filter` 保护；但 `findById` 不受保护（Hibernate 6 的已知行为），故取站点后仍要过 `TenantOwnershipGuard.mutableInCurrentContext`
- ⚠️ **`deleteDocuments=true` 时必须逐篇调 `knowledgeService.deleteDocument(id)`，不能 `documentRepository.deleteAll`**：前者才会把该文档的向量一并删除，后者只删 `document` 行，留下一批**再也没有库行指向、却仍参与检索**的孤儿向量——它们会继续被 `similaritySearch` 命中并拼进 Prompt，而运营在界面上已经看不到这些文档了，排查时无从下手。这是 `ChatAttachment` 孤儿清理踩过的同一类坑（只删库行不删 S3 对象）

### 7.2 前端

`KnowledgePage.tsx` 已 493 行，站点管理再堆进去会推到 700 行以上。抽两个组件到新目录 `frontend/src/components/knowledge/`：

- `CrawlSiteDialog.tsx` —— 配置表单（入口 URL、范围前缀带推导预填与说明、深度、页数、正文选择器、同步周期）
- `CrawlSiteList.tsx` —— 站点列表 + 重新爬取 + 删除（含「同时删除 N 篇文档」复选框）+ 进度轮询展示

`KnowledgePage` 只留入口按钮与状态提升。`api/knowledge.ts` 加六个方法。

进度轮询：拿到 `jobId` 后每 2 秒轮询，`status != RUNNING` 停止；展示「已爬 12 / 上限 50 页 · 新建 8 · 更新 3 · 失败 1」。

## 8. 配置项

```yaml
qingyu:
  care:
    site-crawl:
      max-pages-limit: 500        # 单次爬取页数硬顶
      max-depth-limit: 5          # 深度硬顶
      default-delay-ms: 1000      # 默认限速（robots 的 Crawl-delay 优先）
      max-concurrent-jobs: 2      # 全局并发爬取任务数上限
```

## 9. 测试策略

### 9.1 单元测试（Surefire，纯 Mockito，免 Docker）

- **`LinkExtractor`** —— 同域过滤、相对 URL 解析、规范化（fragment / tracking 参数 / 末尾斜杠 / 根路径例外 / host 大小写 / 参数排序）、非 http(s) 协议滤除；**`inScope` 的三个边界各一条**（入口页自身在范围内 / `helpdesk` 类兄弟目录被排除 / 根路径前缀能匹配子页）
- **`RobotsChecker`** —— `Disallow` 前缀匹配、`Disallow:` 空值、`Crawl-delay` 解析、只认 `User-agent: *` 段、robots 缺失按允许
- **`SiteCrawlService`** —— BFS 顺序与深度截断、`maxPages` 上限（**含被 robots 拒绝的不计数**）、visited 去重、已存在 URL 走 `syncDocument`、单页失败不中断整批、入口失败整体 FAILED、站点级互斥拒绝并发、`finally` 释放互斥
- **`WebPageFetcher`** 新增分支 —— 选择器命中 / 选择器选不中回退启发式 / 不传选择器行为不变；**`FetchResult.dom` 是剔噪前的副本**（喂一个 `nav` 里放链接、正文里没有的页面，断言那个链接仍在 `dom` 中；把 `clone()` 去掉这条必须红）

### 9.2 集成测试（Failsafe，真实 PG，`mvn verify`）

- **租户字段落库** —— `TenantEntityListener` 是 Hibernate 生命周期回调，纯 Mockito 根本触发不到；陷阱 6.1 的守护只能在 IT 里
- **`crawl_site_id` 外键与级联语义** —— 删站点不删文档时 `crawl_site_id` 置 null；勾选删除时文档与向量都没了
- **跨租户越权** —— 拿别的租户的 `siteId` 重爬 / 改配置 / 删除，一律拒绝

### 9.3 测试有效性（上一个特性抓到四类假防线，这次盯死三处）

- `maxPages` 上限的测试必须能被变异证伪：把上限改大，必须有测试红
- robots `Disallow` 的 `verify(never())` 必须确认前置 stub 存在，否则是 vacuously true
- 断言异常用具体类型，不用父类（`ResourceNotFoundException extends BusinessException`，用父类断言时改成抛父类仍绿）

### 9.4 手工验证项（自动化测试覆盖不到，会写进 CLAUDE.md）

1. **范围真的收窄** —— `/help/` 入口不该爬到 `/blog/` 或外域
2. **选择器真的生效** —— 配与不配各爬一次，`SELECT content FROM document WHERE id=?` 人工读一遍有没有导航混进来。这是启发式唯一的验收方式
3. **租户字段非 NULL** —— 用**租户管理员**登录爬，确认 `tenant_id`/`app_id` 落库正确。⚠️ 与「平台管理员建档 `tenant_id` 落 NULL 是既有行为」区分，别把既有行为误判成本特性缺陷（第一期真机冒烟已误判过一次）
4. **上下文不泄漏** —— 连续爬**两个不同租户**的站点，第二个的文档租户字段仍正确。这是陷阱 6.1 的另一半，**只有真机连做两次才暴露**，单次爬取永远是绿的
5. **重爬幂等** —— 第二次爬同一站点，`document` 行数只增新页，`vector_store` 不因重复建档暴涨
6. **robots 真被遵守** —— 找一个有 `Disallow` 的站点，确认那些路径没进库
7. **限速真的生效** —— 抓包或看日志时间戳，确认相邻请求间隔 ≥ 1 秒（或 robots 的 `Crawl-delay`）。限速失效不会报错，只会让平台被目标站点封 IP

## 10. 范围外（留第三期）

- **定期自动重爬整站** —— 站点配置已持久化，加一个 `@Scheduled` + 站点级 `syncInterval` 即可；需先定义「页面消失」的处理语义
- **额外排除选择器（黑名单）** —— 等正文选择器跑一段时间，若出现「容器内仍有相关文章/上下篇噪声」再加
- **sitemap.xml 优先** —— 有 sitemap 的站点可跳过 BFS 直接取 URL 列表，更快更全；BFS 是必须有的兜底，sitemap 是优化
- **爬取任务持久化与重启恢复** —— 见 §2.4，幂等重跑已覆盖
- **抽取 `syncDocument` 的三分支以避免重爬抓两次** —— 见 §5.2 的触发条件

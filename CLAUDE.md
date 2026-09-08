# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

轻语（QingYu Care）是面向企业的多租户 AI 智能客服 SaaS 平台。后端 Spring Boot 4.1 + Spring AI 2.0 + pgvector，前端 React 19 管理后台，另有一个可嵌入网页的独立 Widget 子项目。

## 常用命令

### 基础设施（必须先启动）
```bash
cd docker && docker compose up -d   # PostgreSQL(pgvector) + RustFS(S3) + Redis
```
`docker/init.sql` 负责 `CREATE EXTENSION vector` 与 `zhparser` + `chinese_zh` 分词配置；业务表由后端启动时通过 JPA `ddl-auto: update` 自动建立。

⚠️ **`init.sql` 只在数据目录为空时执行一次**。镜像后来加了 zhparser，而存量数据卷不会重跑它——症状是启动打 WARN `混合检索表结构初始化失败，关键词检索可能不可用`，服务照常起、只是中文关键词检索与热点分析静默失效。手工补建即可（不必重建卷），语句见 `docs/部署文档.md` 故障手册第 2 条。

生产用 `docker/docker-compose.prod.yml`（含 backend/frontend 两个应用服务）。它声明了独立项目名 `name: qingyu-care-prod`，**别删**——两个 compose 同在 `docker/` 目录，不指定项目名会共用命名卷，生产直接挂上开发库的数据。

### 后端（backend/，Maven）
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 启动（dev profile）
mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo   # 带演示数据启动（DemoDataSeeder）
mvn clean install                                    # 全量构建 + 单测（不含 IT）
mvn test                                              # 跑单元测试（Surefire，免 Docker）
mvn test -Dtest=ConfigServiceTest                     # 单个测试类
mvn test -Dtest=WebhookSignatureTest#signs_body_with_hmac_sha256   # 单个方法
mvn verify                                            # 单测 + 集成测试（Failsafe，需 Docker Desktop）
mvn verify -Dit.test=OpenApiAuthIT                     # 单个 IT（Failsafe 用 it.test 而非 test）
```
- JDK 17。后端启动需注入环境变量 `DASHSCOPE_API_KEY`（通义千问）。可选覆盖 `AI_BASE_URL` / `AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL` 切换任意 OpenAI 兼容端点。
- ⚠️ **`AI_BASE_URL` 必须自带 `/v1`**（如 `https://dashscope.aliyuncs.com/compatible-mode/v1`）。Spring AI 2.0 起改用官方 openai-java SDK，只往 baseUrl 后拼 `chat/completions` / `embeddings`，**不再补 `/v1`**（1.x 的 `OpenAiApi` 拼的是 `/v1/xxx`，故当年要求不带，契约已翻转）。该项同时喂给 ChatModel 与 EmbeddingModel，缺 `/v1` 会让对话/摄取/检索全线 404。
  ⚠️ **但「一律带 `/v1`」只对 DashScope 这类厂商成立，不是通则**：库里 `ai_model_config.endpoint` 是租户逐个模型填的，各家 OpenAI 兼容模式的 base 路径后缀并不统一（智谱 `/api/paas/v4`、火山方舟 `/api/v3`、Gemini `/v1beta/openai`），给它们补 `/v1` 就是 404。判据是「拼上 `chat/completions` 是否恰好等于该厂商的真实请求地址」，不是「有没有 `/v1`」。各家的正确值见 `ModelProviderCatalog`，那是全仓唯一一份厂商清单，前端经 `GET /v1/ai-models/providers` 拉同一份渲染下拉与徽章——**别在前端另起一份**。
- 服务上下文路径是 `/api`，Swagger 在 `http://localhost:8080/api/swagger-ui.html`。

### 演示数据与截图

`DemoDataSeeder`（`@Profile("demo")` + `ApplicationRunner`）播种「云舟科技」整套演示数据：租户/应用/用户、租户级问答策略、知识库 4 篇文档（走真实 `uploadDocument()`，S3 + Tika + 真实 embedding，向量可检索）、8 条 QA、12 组会话（铺在最近 30 天）、30 天用量、分析日表经 `backfill` 真实聚合。幂等键是租户 code `demo-cloudboat`，反复重启不重播。演示账号 `demo_admin` / `demo123456`（租户管理员）、`demo_agent` / `demo123456`（坐席）。

⚠️ 三个坑已写在类注释里，改动时别退化：

1. **`ApplicationRunner` 没有 `TenantContext`**，不手工搬运则知识库与文档的 `tenant_id` 静默落 NULL——后果不是「对所有租户可见」而是反过来「对任何租户都检索不到」（metadata 写成 `tenantId: ""`，与 `tenantId == '<真实id>'` 永远匹配不上）。`finally` 里必须 `clear()`，启动线程会被后续请求复用。
2. **`ChatSession`/`ChatMessage` 的 `createdAt` 带 `@CreationTimestamp`**，Hibernate 在 INSERT 时覆盖任何手工赋值，必须落库后用原生 UPDATE 回填，否则 12 条会话全落在今天，而 rollup 按 `cm.created_at` 分日聚合，30 天曲线退化成一根柱子。
3. **`hit_document_ids` 必须写 JSON 数组字面量**（`"[16]"`）而非裸数字：`AnalyticsRollupDao.insertDocHits` 用 `json_array_elements_text` 展开，标量直接抛，而 `rollupDay` 把三条 insert 包在同一事务里——那一天的质量与热词聚合会被一起回滚。

`docs/images/` 的 15 张截图基于这套数据实拍（1440×900）。改了界面要重拍时：起 `dev,demo` profile，用 `demo_admin` 登录并**在顶部选中「云舟商城客服」应用**（应用级配置页外包 `<RequireApp>`，未选应用截出来是空白），按编号顺序走一遍。⚠️ 每张截图都在 README 或使用手册里有引用，删改文件名要同步改引用。

### 下面几节「必须手工验证」不用全背——用脚本按改动点名

```bash
./scripts/manual-smoke-hints.sh              # 看工作区改动触发了哪几节
./scripts/manual-smoke-hints.sh master...HEAD  # 看整个分支
```

按本次改动的文件，只列出下面**对应那几节**的标题，让人展开去读。**它只存「路径 → 章节标题」的映射、不复述任何条目内容**——复述就会产生第二份会漂移的文案，正是卡片协议白名单分裂三处那个病。引用的章节标题在 CLAUDE.md 里找不到时脚本 exit 1（标题被改却忘了同步规则，会让对应提示静默消失，和「本次改动无需验证」长得一模一样）。

刻意**不判红**：这些条目自动化测试永远覆盖不到，判红只会逼人为了绿而空跑流程。

### 升级 Spring AI / Spring Boot / 更换 embedding 模型后必须手工验证的三件事
这三条自动化测试**永远覆盖不到**——IT 里 `VectorStore` 与 `ChatClient` 全被 mock，`streamUsage` 静默失效不抛异常，出站 URL 更是测试看不到的东西。Boot 4.1 + Spring AI 2.0 迁移就是靠这三条真机冒烟才闭合的（`docs/superpowers/specs/2026-07-30-boot4-springai2-migration-design.md` §8.1/§8.3）：
1. **向量检索活性**：上传文档 → 提问 → 确认命中且 `score > 0`。
2. **token 计量非零**：打一次流式对话 → 查 `sys_api_usage.token_count` 非零（`streamUsage` 一旦失效是静默的，记 0 不抛）。
3. **出站 URL 形态**：把 `AI_BASE_URL` 临时指向本地 HTTP 监听器抓包，确认实际请求路径是 `/v1/chat/completions` 与 `/v1/embeddings`。

### 改动流式对话链路后必须手工验证
同样是自动化测试永远覆盖不到的（IT 里 `ChatClient` 全被 mock，工具调用与真实 SSE 时序都看不见）：
1. **逐字增量**：回复真的逐字出现而非一次性刷出（用 `MutationObserver` 采样最直接，肉眼容易被短回复骗过）。
2. **流式 token 计量**：走 widget 发一条 → `sys_api_usage.token_count` 非零（管理端不计量，只有开放 API 计量）。
3. **流式工具调用** ⚠️ **本项目最大的一处上游坑**：挂一个 HTTP 工具并提问**触发它**。Spring AI 2.0 + Qwen 组合下，LLM 实际调用工具时 `OpenAiChatModel$ChunkMerger.chunkToChatCompletion` 会抛 `NoSuchElementException`（Qwen 分帧发 tool_call：首帧带 `function.name`、后续帧只有 `arguments`，Spring AI 未按 index 聚合；上游 [issue #4790](https://github.com/spring-projects/spring-ai/issues/4790) 仍 open、修复 PR #4794 未合并、2.0.0 已是最新稳定版**无版本可升**）。故 `ChatServiceImpl.streamChat` 内置降级：**本轮挂了工具且尚未吐出任何正文时**，失败则用非流式 `chat()` 重跑一次并把结果补发为 delta/card/done。验证点：客户端收到 delta/card + done 而非 error；DB 只落一条 AI 消息；`sessionId` 与 START 事件一致。**上游修复后可整段删除该降级**（`fallbackToNonStreaming`）。
4. **卡片增量渲染 + 历史一致**：卡片在围栏闭合瞬间整张出现；刷新后历史顺序与流式时一致（验证落库 `ui_spec` 正确）。
5. **停止生成不落库**：管理端点「停止」后半截回复留在屏上，刷新即消失（`Flux.concat` 的落库 Mono 在 cancel 时不被订阅）。

### 改动卡片 Action URI 协议后必须同步三处
卡片按钮的动作协议（`url://` 打开链接 / `msg://` 发消息 / `copy://` 复制）白名单分散在三个语言里：

1. `ChatServiceImpl.CARD_PROMPT`（Java 字符串，决定 LLM 会生成什么）
2. `frontend/src/lib/actionUri.ts` 的 `VALID_PROTOCOLS`
3. `widget/src/core/actionUri.ts` 的 `VALID_PROTOCOLS`

漏改任一处的后果：LLM 产出客户端不认的协议 → 按钮退化成发一条奇怪的消息，或彻底静默。

**`CardActionProtocolSyncTest`（Surefire 单测）现在守住了「三处白名单集合必须逐字相等」**：反射读 `CARD_PROMPT` 抽 `xxx://`（排掉嵌在示例链接里的 `https` 等通用 scheme），正则抽两份 TS 的 `VALID_PROTOCOLS`，三者两两比对。改一处忘另两处即红。四轮变异验证过：单端加协议 / 两端加而 prompt 不加 / 只 prompt 加 / 抽取正则失配，全部转红——最后一条尤其重要，正则一旦失配集合会静默变空、断言退化成「空==空」永真，故先各自断言非空。

⚠️ **它只比对白名单集合，不验证行为**。「三个协议各自实现得对不对」（fail-closed 语义、`safeUrl` 白名单、剪贴板降级）它一概不管——改协议后仍须真机点一遍三类按钮。

⚠️ **`url://` 是 fail-closed 的**：被 `safeUrl`（http/https 白名单）拒绝时**不回退** `value`，点击无反应是**正确行为**——协议有效、只是内容被安全策略拦下，此时改去发消息属语义错乱。`executeActionUri` 的返回值表示「是否已作为动作处理」而非「是否成功」，回退只在协议本身无效（格式不匹配/不在白名单）时发生。两端各有一份实现，改一处必须同步另一处。

⚠️ **widget 侧解析与执行已拆分**：`widget/src/core/actionUri.ts` 只负责解析（`resolveActionUri`），不碰任何浏览器 API；执行（`window.open`/`navigator.clipboard`）留在 `widget/src/actionUri.ts`。`ResolvedAction` 的 `none`（协议无效，调用方应回退发送原文）与 `blocked`（协议有效但内容被拦下，调用方不得回退）是这条 fail-closed 语义跨模块成立的承载点，二者语义不同，不能合并成一种「失败」。

### 改动网站知识库同步 / 站点爬取链路后必须手工验证
自动化测试覆盖不到的三条（单测里 `WebPageFetcher` 与 `VectorStore` 全被 mock，调度线程的过滤器状态更是单测造不出来）：
1. **二次同步不重嵌**：**等第一次同步跑完再点第二次**，确认 `chunk_count` 与 `vector_store` 行数**都没变**。退化成每次重嵌不会报错、只会悄悄烧 embedding 的钱。⚠️ **别连点两次**——第二次会被咨询锁挡下（日志 `网页同步跳过：同一文档已有同步在进行`），此时行数不变是「被锁挡住」而非「hash 未变跳过」，会把并发互斥的效果误当成变更检测的效果。
2. **调度器真能查到文档**：把某文档 `last_synced_at` 改到很久以前，等 `@Scheduled` 触发（临时用 `-Dqingyu.care.web-sync.interval-ms=30000` 缩短间隔），确认日志打出「网页定时同步：N 篇到期」。原生 SQL 一旦被改回 JPQL，这里会**静默地一篇都查不到**。
3. **人工读一遍入库正文**：`SELECT content FROM document WHERE id=?`，看有没有混进导航栏/Cookie 提示/页脚版权。这是启发式剔噪声唯一的验收方式。

排查网页文档问题时，**先跟文件文档做同身份对照**再归因新代码——真机冒烟中「网页文档 `tenant_id` 落成 NULL」曾被误判为本特性缺陷，实为平台管理员建档的既有行为（文件上传路径一模一样）。

**站点爬取（第二期）另有十条，每条都是自动化测试覆盖不到的：**
4. **范围真的收窄**：给一个 `/help/` 入口，确认没有爬到 `/blog/` 或外域。
5. **选择器真的生效**：配与不配各爬一次，`SELECT content FROM document WHERE id=?` **人工读一遍**有没有导航混进来。这是启发式与选择器唯一的验收方式。
6. **租户字段非 NULL**：用**租户管理员**登录爬，确认 `tenant_id`/`app_id` 落库正确。⚠️ 与「平台管理员建档 `tenant_id` 落 NULL 是既有行为」区分——第一期真机冒烟已把这条既有行为误判成缺陷一次。
7. **上下文不泄漏**：连续爬**两个不同租户**的站点，确认第二个的文档租户字段仍正确。这是第 6 条的另一半，**只有真机连做两次才暴露**，单次爬取永远是绿的。
8. **重爬幂等**：第二次爬同一站点，`document` 行数只增新页、`vector_store` 不因重复建档暴涨。
9. **robots 真被遵守**：找一个有 `Disallow` 的站点，确认那些路径没进库。
10. **限速真的生效**：看日志时间戳确认相邻请求间隔 ≥ 1 秒（或站点声明的 `Crawl-delay`）。限速失效不会报错，只会让平台被目标站点封 IP。
11. **爬取期间改配置不会被静默回滚**：开一次页数较多、耗时够长的爬取，趁它还在跑时把该站点的 `contentSelector` 改掉，等爬完确认新选择器生效、没被 `crawl()` 收尾那次 `save` 覆盖回旧值。这条已用变异测试证实**零自动化守护**（去掉「重新 `findById`」那步改回直接 `save(site)`，全部单测照样绿），只能靠这条手工验证兜底。
12. **首次「新建并开爬」连点几次不出现幽灵失败**：确认不会出现「进度立刻显示失败：站点不存在，但列表里那条明明在」——`startCrawl` 与 `@Async` 投递之间的事务可见性窗口只在特定线程调度时序下才现形，评审把 `@Transactional` 加回 `startCrawl` 后连跑 4 次 IT 全绿，即**没有任何自动化测试能保护这条**。
13. **中途故障不留僵尸 RUNNING**：`runCrawl` 的 `catch` 只抓 `Exception`、抓不住 `Error`（如整页 DOM 撑爆堆内存），这条纯 Mockito 测不出真实场景——临时在爬取路径插一行抛 `Error` 冒烟，确认 `finally` 仍把该 Progress 置 `FAILED`、互斥与并发额度都被正确释放，站点能立刻重新爬取；不清理的后果是并发额度永久少一个，只能重启进程恢复。

### 改动聊天附件链路后必须手工验证
自动化测试覆盖不到的五条，每条都是真机冒烟实际抓到过东西的地方：
1. **管理端真的能传上去**。前端 `uploadChatFile` **必须显式设 `'Content-Type': 'multipart/form-data'`**（同 `knowledge.ts` 的 `uploadDocument`）——axios 实例在 `create` 时写死了 `application/json`，而 axios 1.x 的 `transformRequest` 遇到 FormData 会看 Content-Type，是 JSON 就调 `formDataToJSON` 把它转成 JSON，`File` 没有可枚举属性于是变成 `{}`，**328KB 的图只发出 62 字节**，后端回 `Current request is not a multipart request`。这条 8 轮代码评审全部漏掉——`chat.ts` 孤立看完全正确，缺陷只存在于「实例级默认头 × axios 版本行为」的交互里。
2. **SSE 端点的错误响应形态**。用**真实 HTTP 客户端带 `Accept: text/event-stream`** 请求 `/open/v1/chat/stream` 并触发一个业务异常（如带不存在的 attachmentId），确认是 JSON 而非 Tomcat HTML 错误页、且 body 里**没有 Java 堆栈**。⚠️ **MockMvc 与不带 Accept 头的 curl 都复现不出**——异常处理器走内容协商时 JSON 转换器与该 Accept 不匹配，会抛 `HttpMediaTypeNotAcceptableException` 并连锁触发 `/error` 转发（不在 Sa-Token 豁免名单）撞 `NotLoginException`，最终 401 + HTML + 完整堆栈发给公网客户端。
3. **坐席端实时收图**（不刷新）：坐席接管后让用户发图，坐席台应立刻出现图片而不只是文本。这条守的是 attach 与 notify 的顺序（见上）。
4. **孤儿清理连 S3 一起删**：上传但不发送，临时把 `qingyu.care.attachment.orphan-retain-hours` 改成 0、间隔改短，确认库行**和 RustFS 里的对象**都没了。⚠️ 改成 0 会把「选了图还没点发送」的附件秒删，验完记得改回来。
5. **文件名 XSS 三重否定**：上传一个叫 `<img onerror=alert(1) src=x>.png` 的文件，断言**不弹窗、DOM 里没有多出 img 元素、文件名原样作为文本显示**——只看"没弹窗"抓不住 `innerHTML` 写法。

### 改动已读回执链路后必须手工验证
自动化测试覆盖不到的七条：
1. **多标签页并发**：坐席开两个标签页同看一个会话，回执不打架、水位线不回退。jsdom 造不出真实并发。
2. **Page Visibility 真实行为**：切到别的标签页不上报、切回来立即上报。jsdom 里 `visibilityState` 是测试自己 stub 的，等于在测桩。⚠️ **这条别再从头摸索——本节末尾有一套已验证通过的真机配方**（2026-08-20 按它验证通过，全程无桩）。
3. **访客刷新后已读保持**：真机整页刷新 widget，已读标记仍在（走的是历史响应里的 `agentReadUpToMessageId`）。真实浏览器整页刷新会把整个 JS 运行时重建一遍——模块级状态、DOM、SSE 连接全部归零重来；而 jsdom 里所谓的"刷新"只是重新调用一次 init 函数，`isOpen`/已渲染消息/已读水位线缓存等模块级状态原样残留，两者不等价（Task 10 里 `setPanelOpen` 要写在 `stubVisibility` 之前，否则会读到上一个测试遗留的 visibility 状态，就是这类"jsdom 里状态不会随刷新清零"的直接体现）。只有真机整页刷新才能验证「已读确实是从历史响应重建出来的」而不是模块状态凑巧还在。
4. **多实例下 `read` 事件到达**：`RealtimeHub` 走 Redis pub/sub 桥接，单实例测不出跨实例路径（同 `WebhookQueueIT` 面对的问题）。
5. **访客界面无视觉噪音**：坐席反复读，访客侧只出现「已读」二字，不冒任何状态提示条。⚠️ 复用 `session` 事件就会踩这条——widget 对 `session` 事件是无条件当文案展示的。「不产生额外文案节点」这部分已由 widget vitest 断言覆盖（守住"不复用 session 事件"这条边界），真机要验的是自动化断言不到的部分：坐席端与访客端真实串起来之后的整体观感——除了「已读」二字出现，不该有闪烁、不该有布局跳动，其余任何东西都不该跟着动。
6. **B 的双向真机联调**：坐席发消息 → widget 展开着 → 坐席端出现「已读」；把 widget **收起**再让坐席发一条 → 坐席端**不该**出现已读。收起是 widget 内部状态，jsdom 里测的是我们自己的桩。
7. **坐席正看着别的会话时收到 B 回执**：开两个已接管会话，看着甲，让乙的访客读消息 → 标记必须落在乙上。这是多会话列表引入的新失效模式，只在真机多开时现形。

⚠️ **「坐席消息」的判据是 `role === 'AGENT'`，而它在三个地方各写了一次**：`AgentPage.tsx` 的已读判据、`AgentPage.tsx` 里本地乐观插入消息时**硬编码**的 role（它不来自后端响应）、以及 widget 的 `history.ts`。三处必须一致，只改其中之一的症状是「坐席自己刚发的消息标不上已读、刷新一下又对了」。判据曾经是 `ASSISTANT`（那时 `agentReply` 落库就写 `ASSISTANT`、`MessageRole.AGENT` 从未被写入），根因已于 2026-08-20 修复，沿革见 `docs/superpowers/specs/2026-08-20-agent-message-role-design.md`。

**第 2 条（Page Visibility）的真机配方** —— 2026-08-20 摸索并验证通过，复用这套、别重走弯路：

⚠️ **别用 chrome-devtools MCP 驱动这条，那条路根本走不通**：CDP attach 会抑制页面后台化。已实测——程序化 `select_page(bringToFront)`、**人手点标签栏**、乃至 OS 级 `ShowWindow(SW_MINIMIZE)` 最小化整个窗口，只要页面被 CDP attach 着，`visibilityState` 就恒为 `visible`、零个 `visibilitychange`。用 `Emulation.setPageVisibilityOverride` 能让断言变绿，但那是在测自己造的桩。

1. **访客页跑在不接 CDP 的独立 Chrome 实例**：`chrome.exe --user-data-dir=<临时目录> --no-first-run --no-default-browser-check --new-window <url>`，不带调试端口，MCP 看不到它也就不抑制后台化。
2. **宿主页自带编排脚本**驱动 widget——`attachShadow({mode:'open'})` 所以外部脚本能操作其 DOM：`.qy-bubble` 展开、`textarea` + `.qy-input button:not(.qy-attach-btn)` 发送、`.qy-handover` 转人工。⚠️ 展开后**不能再点气泡**，否则「不上报」会是因为面板收起而非页面隐藏。
3. **`ShowWindow(hwnd, 6)` 最小化 / `9` 恢复**，并用 `IsIconic` 自证窗口状态真的变了（否则"没推进"可能只是命令没生效）。
4. **坐席侧全走 curl、不必开坐席页**：`/api/v1/auth/login` 拿 token（请求头名 `satoken`）→ `/api/v1/chat/session/{uuid}/accept` → `/api/v1/chat/agent/reply`。
5. **判据取数据库** `message_read_receipt.up_to_message_id`，不看 UI。

⚠️ **最容易得出错误结论的一步：必须能区分「消息压根没送达」与「送达了但没上报」**。若 Chrome 在最小化时挂起了 SSE，widget 根本没收到消息，那"不上报"就与可见性判定毫无关系——而这两种情况在水位线上的观测结果**完全相同**（可见时推进 / 最小化时不推进 / 恢复后推进），只看水位线必然误判。办法是给宿主页装两个探针，结果写进 `document.title`（独立实例没有 CDP，但 PowerShell 读得到 `MainWindowTitle`）：`MutationObserver` 观察 `.qy-msgs` 子节点新增 = 消息**渲染到达**时刻；包装 `window.fetch` 拦截 URL 含 `/read` 的请求 = **上报发出**时刻。最小化期间前者增加、后者不动，才坐实了「收到了但没报」。

⚠️ **探针必须事件驱动，绝不能用 `setInterval`**——后台标签的定时器被 Chrome 节流到 1 次/分钟，title 就不更新了，看起来跟"页面被冻结"一模一样。

⚠️ 也试过「摘掉判定重新 build」的变异法，**那条路不划算**：`visibilitychange` 监听器不一起摘就无法归因恢复后的上报，且要动入库产物 `widget.js`。探针法不改产品代码就能直接观测消息到没到，更直接。

### 改动 MCP 接入链路后必须手工验证
自动化测试覆盖不到的四条：
1. **真连一个 MCP server**：走完「登记 → 同步 → 标只读 → 绑进 skill → 对话触发」，确认出站请求带着配置的凭据。⚠️ 凭据若在某个环节被清掉，症状同平台共享工具那条——第三方返 401，模型拿着错误响应继续编答案，**全程无异常无日志**。
2. **`DEPRECATED` 路径**：远端下线一个工具后再同步，确认是标记而非删行、界面上有提示、且该工具从 skill 的可勾选列表消失。
3. **SSRF**：URL 指向 `169.254.169.254` 确认被拒；再用一个 TTL=0、解析结果会翻转的域名验证 rebinding 也被挡。
4. **凭据不被空串清空**：编辑 server 只改名字保存，确认 `authHeader` 仍在（前端不回显凭据）。

### 改动 OpenAPI 工具导入链路后必须手工验证
自动化测试覆盖不到的四条：
1. **真实公开 schema 走通全链**：导入 → 勾选 → 绑进 skill → 对话触发 → 确认出站 URL 与参数正确。单测只能证明映射规则对，证明不了「生成出来的工具真的能被 LLM 调用并拿到结果」。
2. **可选参数不填时端到端正确**：提一个不涉及可选参数的问题，确认出站 URL 里既没有残留 `{占位符}`、也没有半截的 query 段。`UrlTemplateFillerTest` 钉的是纯函数，这条钉的是它真的被接在链路上。
3. **YAML schema 经 URL 拉取能导入**：`OpenApiSchemaParserTest` 已用 mock 文本覆盖了 JSON/YAML 两条解析分支，这条重复验证没有意义；真正测不到的是**经 URL 拉取**这条路径——`OpenApiSchemaFetcher` 的 Content-Type 白名单（`text/yaml`/`application/yaml`/`application/x-yaml`）要靠一次真实出站请求拿到真实的响应头才能验证，单测里 fetcher 是 mock 的，覆盖不到这层。
4. **会 302 的 schema URL 给出可读错误**：禁重定向是硬安全取舍（重定向目标不再经 `SsrfGuard` 校验），只能靠错误消息补偿——必须明确说「请填写跳转后的最终地址」，而不是丢一个含糊的失败。

### 改动 widget 协议层或 CLI 后必须手工验证
自动化测试覆盖不到的两条：
1. **同一问题用两种身份各问一遍**，确认访客视角命中的文档确实比服务端视角少。这一条同时验证三件事：鉴权抽象接对了、`--as-visitor` 的 Origin 真的走到了浏览器那条鉴权路径、文档可见性分级在开放 API 上确实生效。单测里 fetch 是桩的，验不到真实鉴权。
2. **widget 真机回归**：嵌一次 widget，确认会话恢复、卡片按钮的三类动作（打开链接/发消息/复制）仍然正常。协议层抽取动了 session 与 actionUri 两条运行时路径，而 `url://` 被 `safeUrl` 拒绝时「点击无反应」是正确行为——这条 fail-closed 语义只有真机点得出来。

### 前端（frontend/，pnpm）
```bash
pnpm install && pnpm dev   # Vite 开发服务器（:5173，/api 代理到 :8080）
pnpm build                 # tsc -b && vite build
pnpm lint                  # ESLint（flat config: eslint.config.js）
pnpm test                  # vitest（jsdom + @testing-library/react）
```

### Widget（widget/，pnpm — 独立子项目）
```bash
pnpm install
pnpm dev     # 本地调试（index.html）
pnpm build   # ⚠️ 产出到 backend/src/main/resources/static/widget.js
pnpm test    # vitest
```
**关键**：`widget.js` 是**提交进仓库的构建产物**，由后端作为静态资源对外提供（`<script src=".../api/widget.js?appKey=...">`）。改动 `widget/src/` 后必须重新 build 并提交 `widget.js`；`.gitattributes` 强制其为 LF 行尾以保证跨平台 diff 稳定。

**`scripts/buildFreshness.test.ts` 现在守住了这条**：`vite.config.ts` 的 `injectSrcHash` 插件把 `widget/src/**/*.ts`（排除 `*.test.ts`）的 sha256 指纹写进产物，测试重算比对，改了源码没 build 即红。忘了 build 的后果本来是完全无声的——后端照常启动、页面照常加载 widget，只是线上跑旧代码，改动看起来「没生效」而任何日志里都查不到原因。三轮变异验证过：改 src 不 build → 红；只改 `*.test.ts` → 仍绿（测试不进产物，不该要求重建）；抹掉产物 banner → 红。

⚠️ 动这套指纹机制时的两个坑：**指纹注入必须用插件的 `generateBundle`，`rollupOptions.output.banner` 在 lib 模式下实测完全不生效**（产物一个字节不变、build 无任何提示）；**指纹计算必须归一化行尾与路径分隔符**，否则 Windows（CRLF 工作区）与 Linux 算出的指纹不同，变成谁跑谁红。

### CLI 调试客户端（cli/，pnpm — 独立子项目）
```bash
cd cli && pnpm install && pnpm build
node dist/cli/src/index.js --api-base http://localhost:8080/api --app-key <appKey> --api-secret <secret>
node dist/cli/src/index.js --api-base ... --app-key <appKey> --as-visitor --origin https://已在白名单的域名
```
面向运维排查「这条回答为什么不对」，同时是 widget 协议层（`widget/src/core/`）的第一个非浏览器消费者。⚠️ 产物路径是 `dist/cli/src/index.js` 而非 `dist/index.js`——`tsconfig.json` 的 `include` 把 `../widget/src/core/**/*.ts` 一起纳入编译，`tsc` 按各源文件相对 `include` 根的路径重建目录结构，`cli/src/` 下的文件因此多出一层 `cli/src/` 前缀。

⚠️ **两种身份看到的知识库不一样**：`--api-secret` 是服务端身份（可见 INTERNAL 文档），`--as-visitor` 是浏览器访客身份（仅见 PUBLIC）。拿错视角排查访客问题会得出「我这儿明明查得到」的误导结论，所以两者互斥且必须显式二选一，CLI 启动时会打印当前身份。

⚠️ **`widget/src/core/` 的测试必须跑在 node 环境**（每个测试文件首行 `// @vitest-environment node`）。widget 整体跑 jsdom，协议层里若还粘着 `document`/`localStorage` 是看不出来的——切成 node 才炸得出来。这是「协议层抽干净了」唯一一条机器可判定的证据，**别为了让测试过而把某个 core 测试改回 jsdom**。

## 测试约定（重要）

分两层，由 Maven 插件按文件名/标签自动切分（见 `pom.xml`）：

- **单元测试（Surefire，`mvn test`）**：纯 Mockito（`@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`），不启 Spring 容器、**不需要 Docker**。Surefire 会排除 `*IT.java` 与 `@Tag("integration-test")`。**新增测试优先写成这类单测。**
- **集成测试（Failsafe，`mvn verify`）**：类名以 `IT` 结尾，放在 `it/` 包，继承 `support/AbstractPostgresIT`——它用 **Testcontainers 起一个 JVM 内单例 `pgvector/pgvector:pg17` 容器**（`@DynamicPropertySource` 注入数据源），`@SpringBootTest(MOCK)` + `@AutoConfigureMockMvc`。**需要 Docker Desktop 运行**；`mvn test` 不会触发它们，故无 Docker 也能跑单测。
- IT 基类提供 `seedApp(...)` 播种租户+应用。多租户过滤器只在**真实 HTTP 请求**流经 `TenantInterceptor` 时生效，因此 IT 里的种子数据要么走 MockMvc 请求，要么用不继承 `BaseTenantEntity` 的实体（如 `Application` 靠关系映射）。
- `*FallbackIT`（`ChatStrategyFallbackIT`/`PromptTemplateFallbackIT`/`ModelRouterDefaultIT`/`HandoverRuleFallbackIT`）专门守护下方「多级配置回退」这条易错链路，改动相关服务后务必 `mvn verify`。
- ⚠️ **跑 `mvn verify` 前必须先停掉本机在跑的 dev 后端**（`:8080`），否则 `WebhookDeliveryIT` 必红。IT 用 Testcontainers 起自己的 PG，但 Redis 连的是**本机 6379**，与 dev 后端共用消费组 `webhook-workers`——dev 后端的 `WebhookStreamConsumer` 会抢走 IT 投进流里的消息并 ACK，再拿 `deliveryId` 去自己的 dev 库查（查不到），IT 的投递行永远到不了 SUCCESS，最后表现为一条 10 秒 `ConditionTimeout`，**看不出与 Redis 有任何关系**。已实测确认：停掉 dev 后端后同一条 IT 即绿。

### 前端测试（frontend/，vitest）

`src/**/*.test.tsx` 与被测文件同目录并放（同 `widget/`）。`pnpm test` 跑一次就退，`pnpm test:watch` 是监听模式。

- ⚠️ **故意不开 `globals: true`**（这点与 `widget/` 不同）：测试文件显式 `import { describe, it, expect, vi } from 'vitest'`。收益是 eslint 不必加 vitest globals、`tsconfig` 不必加全局类型，前端 lint 的既有 error 基线零新增。
- ⚠️ **代价是 RTL 不再自动清理**：`@testing-library/react` 只在 `afterEach` 是全局函数时才自己注册 `cleanup`。`src/test/setup.ts` 里那行 `afterEach(cleanup)` **不能删**——漏掉的症状是「单个测试跑绿、整套一起跑红」，因为上一个测试的 DOM 还挂在 `document` 上。
- ⚠️ **推进时间一律 `await vi.advanceTimersByTimeAsync(ms)`，包在 `act` 里**。轮询类代码的回调内部有 `await`，同步的 `advanceTimersByTime` 只把定时器回调排出去、推不动后面的 promise 链——测试会「通过」但实际什么都没验证。
- 桩 HTTP 一律**桩到 `api/*.ts` 的 service 对象**（`vi.mock('@/api/knowledge')`），返回 `{code, message, data}`。这一层正是「HTTP 200 + `body.code`」约定发生的位置，构造 `{code: 400}` 最直接。不引入 MSW。
- `vi.mock` 的工厂被提到所有 import 之前执行，工厂里引用模块顶层 `const` 会撞 TDZ——桩函数必须用 `vi.hoisted` 一起提上去。
- Radix 组件（Dialog / Select 等）的 portal 与 focus trap 在 jsdom 下行为特殊，测别的逻辑时把它们 `vi.mock` 掉、别顺带一起测。⚠️ 但**别桩成 `() => null`**：如果被桩组件是通过回调（`onSubmit` / `onChange`）驱动父组件逻辑的，桩成空组件会让那条回调路径**静默零覆盖**——`CrawlSiteDialog` 就这么让 `handleCreate` / `handleUpdate` 一行都没执行过，而测试全绿、没有任何现象提醒。桩成一个「只在 `open` 时渲染、暴露回调触发点」的极简 stub 即可（见 `CrawlSiteList.test.tsx` 里那个桩）。
- `src/test/strictModeDoubleInvoke.test.tsx` 是**工具链前提哨兵**，不测产品代码：它断言 vitest 下 `<StrictMode>` 真的把 effect 跑成 setup→cleanup→setup。`CrawlSiteList.test.tsx` 里那条 `isMountedRef` 回归测试依赖这个前提——前提一旦不成立，那条测试会**静默退化成永真**而不是转红，所以把前提本身钉成一条测试。
- ⚠️ **断言「默认值」时小心永真**：只断言「首次打开时复选框未勾选」守不住 `openDeleteDialog` 里那行复位——`useState(false)` 的初值本来就是 `false`，删掉复位照样绿。要走「改动 → 关闭 → 重开仍是默认值」这条路才有判别力。凡是「组件级 state + 每次打开时复位」的组合都吃这个坑。
- **常态约定：动了某个页面/组件的写操作，就顺手给它补一条回归测试。** 追覆盖率数字没意义（27 个页面一次性补完不现实，补出来的也多是走过场的断言），但「改到哪补到哪」能让覆盖面跟着实际风险增长。最省事的模板是 `ToolConfigPage.test.tsx`：桩 api service + `useToast`，点一下触发写操作，断言业务错误弹 destructive toast、成功才刷新列表——两条测试就把一条写操作钉住了。
- ⚠️ **覆盖面故意不在这里记数字**：这串统计腐烂过两次（每次都是照抄上一版而没重数，条数与文件名各自漂移，最后一次文档里只有 3 个文件、实际已有 11 个）。要看当前覆盖面就地跑一条命令：`cd frontend && find src -name "*.test.ts*" | xargs grep -cE "^\s*(it|test)\("`。绝大多数页面与组件仍是零覆盖，靠上一条「改到哪补到哪」的约定增长。

## 架构要点

### 多租户隔离（两级：tenant_id + app_id）
理解任何数据读写前必须先理解这套机制，它是全自动、隐式生效的：

1. **请求入口** `TenantInterceptor`（`config/`）：从 Sa-Token 会话取 `tenantId`，从 `X-App-Id` 请求头取 `appId`，写入 `TenantContext`（ThreadLocal）；`afterCompletion` 清理，防泄漏。
2. **查询过滤** `TenantDataAspect`（`config/`）：`@Before` 切所有 `repository..*` 调用，解包 Hibernate Session 并 `enableFilter("tenantFilter"/"appFilter")`，参数取自 `TenantContext`。平台管理员绕过过滤；非管理员且无上下文时用 `-1L`（拒绝一切）。
3. **过滤器定义** `BaseTenantEntity`（`entity/`，`@MappedSuperclass`）：用 `@FilterDef`/`@Filter` 声明 `tenant_id`/`app_id` 两个过滤条件。绝大多数业务实体继承它。
4. **写入自动填充** `TenantEntityListener`：`@PrePersist` 时从 `TenantContext` 回填 `tenantId`/`appId`。
5. **绑定层防护** `BaseTenantEntity.tenantId`/`appId` 加 `@JsonProperty(access = READ_ONLY)`：只写出、不读入，请求体给了也绑不上。⚠️ 少了它，`TenantEntityListener.prePersist` 只在字段为 null 时才回填、非 null 不覆盖——租户 POST `{"tenantId": <他人租户id>}` 就能把行建进别人租户。⚠️ **不能改成「监听器总是用 `TenantContext` 覆盖」**：`QaGenerationService` 在 `@Async` 里用 setter 从源文档向新生成的 QA 传播租户，那样会把它写成 null，重蹈「向量 metadata 落空串、对任何租户都检索不到」的事故（见本文件网站知识库、站点爬取两节）。守护两层：`entity/TenantOwnershipNotBindableTest`（单测，守反序列化方向）+ `it/CreateTenantInjectionIT`（IT，额外守序列化方向——改成 `@JsonIgnore` 会让单测仍绿但 IT 转红）。

6. **按全局唯一键查找必须绕过过滤器**（`username` / `appKey`）。`User` **不继承** `BaseTenantEntity`，但类上直接标了 `@Filter(name = "tenantFilter", ...)`——一样受管，只是不显眼。而 `username` 是 `@Column(unique = true)` 的**全局**唯一键，按它查找天然跨租户，被过滤就是错的。故 `UserRepository` 只提供 native 的 `findByUsernameAcrossTenants` / `existsByUsernameAcrossTenants`，**刻意不提供**派生的 `findByUsername`（留着必被误用）。
   ⚠️ **`TenantDataAspect` 那条「未登录则不过滤」的豁免不够**：判据 `!isLoggedIn()` 是个**间接代理量**，它假设「登录/注册总发生在未登录态」。上一个账号的 satoken 还在时该假设不成立——浏览器 cookie 残留最常见（Sa-Token 会写 cookie，前端 `localStorage.clear()` 清不掉它），此时 `TenantInterceptor` 已把上一个租户的 tenantId 写进上下文，过滤器照常生效。三种症状各不相同、**都指向错误的排查方向**：登录别租户的账号报「用户名或密码错误」（密码其实是对的，人会一路去查密码）；注册/建用户时重名查不出来 → 放行到 INSERT 撞唯一约束 → HTTP 500 且把原始 SQL 与约束名回给客户端（正确响应是 400「用户名已存在」）。`LoginIT` 的五条测试分两半守这条：前两条守「未登录态」，后三条守「带着别人的登录态」——缺陷正藏在后一半里。2026-09-02 真机验证时发现并修复。

7. **`TenantOwnershipGuard` 选哪个方法：看「这个实体的 `tenant_id IS NULL` 是不是有意共享」**。判据**不是**「读操作还是写操作」，也**不是**「带不带密钥」——那两个都是代理量，各自脱钩过。
   - **有共享语义 → `belongsToCurrentContext`**：只限配置类（`ChatStrategy`/`PromptTemplate`/`HandoverRule`/`AiModelConfig`）。它们的 NULL 行由 `@PostConstruct` **播种**而来，是「多级配置回退」的承重结构，租户必须读得到。
   - **无共享语义 → `mutableInCurrentContext`**：业务实体一律归此类（知识库/文档/QA/skill/工具/MCP/附件/会话/A-B 实验）。它们**没有播种逻辑**，NULL 行只可能是平台管理员无上下文时建的、或「tenantId 静默落 NULL」事故——都不是共享意图。
   ⚠️ **这条已踩过两次**：聊天附件（平台管理员上传的附件被任意租户读到）、知识库文档（`requireDocumentOwnership` 被 get/delete/reprocess/status 四处共用，一处读级守卫同时放开读、**删**、写——2026-09-02 实测另一租户管理员可读到平台私有文档正文、可将其**真的删除**、还能往平台私有库插文档）。普查佐证：配置类表 NULL 行成片存在（`handover_rules` 3/3、`chat_strategy` 2/3、`prompt_templates` 1/1、`ai_model_config` 1/1），业务实体表几乎为零。
   ⚠️ 改守卫时**必须同时钉「平台上下文仍可读」**（`TenantOwnershipGuardIT` 的三条 NULL 归属用例都这么写）：只钉「租户读不到」会让人把守卫改成无条件拒绝 NULL，那样平台管理员自己也进不去，而没有任何测试会红。

> **例外**：异步落库的实体 `WebhookDelivery`、`SysAuditLog`、`SysApiUsage` **不继承** `BaseTenantEntity`，而是持有显式的 `tenantId`/`appId` 字段——因为 `@Async` 线程没有 `TenantContext`，不能依赖 Hibernate Filter。新增异步/跨线程写入实体时务必沿用此模式。

### 核心业务：RAG 对话链路（`ChatServiceImpl`）
产品主功能是知识库检索增强的智能客服，唯一有 `service/impl` 拆分的复杂服务之一（另一个是 `KnowledgeService`）。一次问答串起下面这些协作者，改动任一环节先读懂全链：
1. **取会话策略** `ChatStrategyService.getActiveStrategy("default")` → 拿 `topK` / `similarityThreshold` / `fallbackMessage`。
2. **知识检索** `searchKnowledge()` 走 Spring AI `VectorStore.similaritySearch`（pgvector），`buildContext()` 拼上下文；检索异常时 fail-soft 返回空、不阻断对话。
3. **组装 Prompt** `PromptTemplateService` 取模板 + 上下文。
4. **选模型** `ModelRouterService` 返回 `ChatClient`（按 `AiModelConfig` 路由到某个 OpenAI 兼容端点）。
5. **人工转接判定** `HandoverRuleService.checkLowConfidenceTrigger(maxScore)` 等规则决定 `suggestHandover`。
6. **流式 + 计量** `streamChat(request, IntConsumer onTokensUsed, IntConsumer onFallbackTokensUsed)` 返回 `Flux<ChatStreamEvent>`（START/DELTA/CARD/DONE/ERROR），`meterContentStream()` 累加 token 后在 `doFinally` 回调 `onTokensUsed`；工具调用触发非流式降级（见上方「流式工具调用」）成功后另经 `onFallbackTokensUsed` 补记真实 token，避免复用 `onTokensUsed` 把这一轮重复计成两次调用。
> 关键：**token 计量是「开放 API 专属」**。`OpenChatController`（`/open/v1/chat/*`）才传入 `onTokensUsed -> apiUsageService.record(...)` / `onFallbackTokensUsed -> apiUsageService.recordFallbackTokens(...)` 落 `SysApiUsage`；管理后台的 `ChatController` 不计量。新增计费/用量入口时沿用此回调注入方式，别把计量塞进 `ChatServiceImpl` 内部。

### 多级配置回退（重要陷阱）
`ChatStrategy` / `PromptTemplate` / `AiModelConfig` / `HandoverRule` 都遵循「租户自有配置 → 平台默认」的回退语义，且平台默认由各服务 `@PostConstruct` 播种为 **`tenant_id IS NULL`** 的行。但这会撞上多租户 Hibernate 过滤器（租户查询根本看不到 NULL 行）。因此解析顺序固定为：
1. 查当前租户/应用范围内的激活配置；
2. **回退平台默认：用原生 SQL 查询绕过 Hibernate 过滤器**取 `tenant_id IS NULL` 的行（这是闭合该 bug 的唯一关键，普通 JPA 查询取不到）；
3. 降级到 `default` 场景默认；
4. 兜底静态对象（`ChatStrategy` 语义允许无配置安全默认，故不抛异常；`PromptTemplate` 缺失则抛异常）。
`enums/OwnerScope`（`PLATFORM` vs `TENANT`）标记归属。新增「平台可共享 + 租户可覆盖」的配置实体时必须复刻这套回退，否则平台默认对租户不可见。

### 异步执行器分两个池（`config/AsyncConfig`）
`@Async` 任务按时长分池，**新增 `@Async` 方法必须显式归类**：
- **短任务 → Boot 默认 `applicationTaskExecutor`**（不写 qualifier）：用量计量、审计日志落库、webhook 入队与直投。毫秒到秒级、要求低延迟。
- **长任务 → `@Async(AsyncConfig.LONG_TASK_EXECUTOR)`**（固定 6 线程 + 500 有界队列 + `AbortPolicy`）：文档摄取、QA 生成、批量重嵌、网页同步、站点爬取。分钟到小时级。

⚠️ **为什么必须分**：Boot 默认池是 core 8 + **无界队列**，而无界队列下 core 占满后 `maxPoolSize` 永不扩容（它只在队列也满时才生效）。所以只要 8 个长任务同时在跑，之后**每一次**审计日志落库与 webhook 投递都排在它们后面，等待时长等于最慢那个长任务的剩余时间。漏加 qualifier 的长任务会掉回默认池把这个问题带回来，**且没有任何报错**——只在生产上表现为审计与 webhook 莫名延迟。`AsyncExecutorRoutingTest` 扫描全部 `@Async` 方法强制这条约定：不在短任务白名单里的一律必须带长任务 qualifier，新增时忘了归类就红。

⚠️ 分池**没有**改变「线程会复用」这个前提——`@Async` 里写 `TenantContext` 的地方，`finally` 该 clear 照样要 clear。

### AOP 横切
- `@AuditLog(action=, targetType=)`（`annotation/`）+ `AuditLogAspect`（`config/`）：`@Around` 业务方法成功后 `AuditLogService.saveAsync()` 异步落库，含正则脱敏（apiKey/password/secret → `***`）。给写操作打审计日志时加此注解即可。
- 限流：`RateLimitConfig` + `RateLimitService`（Bucket4j + Lettuce Redis），`tryConsume(appKey, limitPerMinute)`，Redis 异常时 **fail-open**（放行）。

### 平台共享工具（`AiTool.scope`）

平台管理员可建 `scope=PLATFORM` 的共享工具（`tenant_id`/`app_id` 均 null），租户在自己的
skill 里勾选使用。**只共享工具、不共享 skill**——skill 的 markdown 注入每一轮系统提示，
共享它等于静默改掉所有租户的回答行为。

⚠️ 五条容易写错的地方：

1. **四条 native 共享查询必须同时钉 `scope = 'PLATFORM'` 和 `tenant_id IS NULL`**
   （`AiToolRepository` 两条 + `AiModelConfigRepository` 两条，后者是同类既有漏洞、同批修的）。
   自有工具走的 `findByIdInAndEnabledTrue` 是 filter-bound 的，别的租户的 id 天然查不出来；
   共享查询是 native、不受 `@Filter` 保护，只判 `scope` 就变成任意 id 可绑 = **跨租户调用他人
   工具并用掉他人的鉴权头**。⚠️ **`scope` 是客户端可控字段**——跟着 `@RequestBody` 进来，租户
   POST `/v1/ai-tools` 时可在请求体里自封 `"scope":"PLATFORM"`（已真机复现：自封后落库
   scope=PLATFORM + 自己的 tenant_id，随即出现在所有租户的共享列表里，带着攻击者的
   authHeader 被别的租户绑进 skill 调用）。真正表示「平台所有」的是 `tenant_id IS NULL`，它
   由服务层强制写入、请求体改不动——**只判 `scope` 等于让租户自己决定要不要被全平台挂载**。
   写入侧：租户侧 `create` 强制 `setScope(TENANT)`，平台共享只能走 `/v1/platform/*`。
2. **脱敏只能用在管理面 list 出口**（`listTools` / `getAllModels`），**绝不能碰运行时解析**
   （`resolveTools`）——`HttpToolCallback` 要用真 `authHeader` 发出站请求。误伤的症状是共享
   工具静默失效：第三方返 401，模型拿着错误响应继续编答案，全程无异常无日志。
3. **`SharedCredentialMasker` 脱敏前必须 `em.detach`**。本项目未配置
   `spring.jpa.open-in-view`，即 Spring Boot 默认的 `true`，OSIV 让 list 查出来的实体在整个
   请求期间保持**受管**，直接 setter 置空会被脏检查刷回库——本意是防泄漏，结果是**把凭据真的
   删掉**。`SharedToolIT` 用真实 MockMvc 请求钉住这条（直调 service 时实体本来就是分离的，
   断言会静默退化成永真）。
4. **共享工具应限于只读 / 幂等端点**。`HttpToolCallback` 的 POST body 由模型按租户 skill 的
   提示词生成、服务端不校验（`inputSchema` 只是给模型的提示）。以前工具与凭据同属一租户，
   参数乱填是自伤；现在凭据是平台的，租户能驱动模型往平台的第三方端点发任意 JSON body。URL
   侧安全（`fillTemplate` 做了 `URLEncoder.encode`、host 由模板钉死），**只影响 POST**。
5. **改动本链路后必须真机验证一次**：建一个平台共享工具（`authHeader` 设成可识别的值）→
   租户建 skill 绑定它 → 发一句触发工具的对话 → **确认出站请求头里带着那个平台凭据**。理由：
   脱敏若误伤运行时路径，症状是第三方返 401、模型拿着错误响应继续编答案、**全程无异常无
   日志**，只有真机能发现。

### MCP 接入（`service/mcp/`）

远端 MCP server 的工具清单同步进 `mcp_server`/`mcp_tool` 两张表（均继承
`BaseTenantEntity`），运行时由 `ChatToolService.resolve()` 与现有 HTTP 工具合并进同一份
`toolCallbacks`。设计文档：`docs/superpowers/specs/2026-08-21-mcp-integration-design.md`。

⚠️ 容易写错的地方：

1. **连接不缓存**：`resolve()` 只读数据库，不连远端——工具清单已经落库，解析阶段根本不需要
   MCP 协议往返。连接只在 LLM **真的调用**某个工具那一刻由 `McpConnectionFactory` 建立，
   `McpToolCallback.call()` 用完即关（`McpConnection.close()` 同时关 `McpSyncClient` 与
   `PinnedAddressResolverGroup`，二者缺一都会在长期运行下泄漏——用户自定义的
   `AddressResolverGroup` 不会被 Reactor Netty 或 SDK 自动关闭）。换来的是省掉一整类连接池
   生命周期 bug（按租户缓存的失效策略、陈旧连接、跨租户串号），代价是每次调用多一次
   `initialize` 握手。
2. **只读门槛落在 `resolveMcpCallbacks()` 的查询条件上，不在界面拦**：
   `McpToolRepository.findByIdInAndStatusAndReadOnlyTrue` 同时卡 `readOnly = true` 与
   `status = ACTIVE`。界面把可写工具置灰挡不住接口层直接提交 `mcpToolIds`，门槛必须落在这条
   查询里。
3. **同步绝不覆盖 `readOnly`**：`McpTool.readOnly` 是租户自己标注的安全判据；`readOnlyHint`
   （远端 annotation 自报、可选不填、不可信）只作界面默认建议值。`McpServerService.sync()`
   每次刷新 `description`/`inputSchema`/`readOnlyHint` 时刻意不碰 `readOnly`——一旦被顺手带
   进 upsert，后果是安全降级且完全静默：一个可写工具重新出现在 `resolve()` 结果里被 LLM
   调用。
4. **远端工具消失时标 `DEPRECATED`，不删行**：工具可能已被某个 skill 的 `mcpToolIds` 绑定，
   删行会让绑定悬空（`resolve()` 查不到、界面也看不到，运营无从得知）。同步失败时同理更
   保守——只写 `lastSyncError`，一行工具都不动，一次网络抖动不该让整套工具集体从对话里
   消失。
5. **LLM 可见的工具名 = `{server.code}__{tool.name}`**（双下划线）：避免两个 server 挂同名
   工具（`search`/`query` 撞名概率很高）让 LLM 收到语义不明的重复工具名。前缀只存在于暴露
   给 LLM 的那一层，`McpToolCallback.call()` 发给远端用的是原始 `tool.getName()`。
6. **SSRF 守卫只能套在 webflux 传输上**：`McpConnectionFactory` 用
   `WebClientStreamableHttpTransport`（Reactor Netty）配合自定义 `PinnedAddressResolverGroup`
   把 `SsrfGuard` 校验期解析出的 IP 钉进连接期，语义同 `RestClientHttpToolExecutor`。**换成
   httpclient 版传输会静默失去这层防护**——它基于 JDK `HttpClient`，不支持自定义 DNS
   解析，代码照常编译、连接照常建立、功能照常可用，只是钉 IP 那层没了，退回「校验时公网、
   连接时翻内网」可绕过的状态。同理漏掉 `.followRedirect(false)` 会让 302 把请求带去未经
   校验的主机。
7. **删 `McpServer` 必须同时清理 skill 绑定**：`McpServerService.deleteServer` 在删工具行前
   先遍历所有 `AiSkill` 摘掉指向这些工具 id 的绑定。漏了的后果是静默降级——`resolve()` 的
   filter-bound 查询查不到就自然跳过，不报错不打日志，skill 界面上仍显示绑着 N 个工具，
   实际挂给 LLM 的少了几个。
8. **SSE 传输本期不支持**：`McpTransport.SSE` 枚举值保留，但 `McpConnectionFactory.create()`
   遇到它直接抛 `UnsupportedOperationException`，前端下拉里该选项禁用不可选。原因是 MCP
   SDK 的 `WebFluxSseClientTransport` 已标 `@Deprecated(forRemoval=true)`，且只有
   `sseEndpoint(String)`、没有 base-URL setter——硬接只会做出一个对着相对路径发请求、静默
   连不上的分支。
9. **同步失败必须落 `lastSyncError`，拉取与写库两个阶段都算**：两条路径都走
   `recordSyncFailure`。早先只有拉取阶段被 try 包住，写库阶段的异常直接穿透到
   `GlobalExceptionHandler`——HTTP 500、原始 SQL 回给客户端，而 `lastSyncError` 一片空白，
   界面的琥珀色警示条与列表失败图标全部沦为死代码。⚠️ 消息**必须截断**：`lastSyncError`
   自己是 `varchar(1000)`，不截断则 catch 里那次 save 会再抛一次，把「已处理好的失败」重新
   变成 500 且连原因都留不下（同一个坑换个位置复发）。⚠️ 写库阶段失败时**已写入的行不回滚**
   （`sync()` 刻意不带 `@Transactional`），库里会留半截状态，重新同步一次即收敛。
10. **`McpTool.description` 必须是 `text`，不能设列宽**：长度由第三方 server 决定，MCP 规范
    对它没有任何约束。曾是 `varchar(1000)`，真机接入 context7 时整个 server 接不进来。
    `McpToolLongDescriptionIT` 守住新建库；**存量库另需跑迁移 SQL**——`ddl-auto: update`
    只加列、不改已有列的类型，改了注解也不会动线上那一列，症状与改之前一模一样。
11. **`timeoutMs` 是「单次请求的响应总时长」，不是连接超时**：同一个值同时喂给 Netty 的
    `responseTimeout` 与 MCP SDK 的 `requestTimeout`，两者同名不同义——前者是**相邻两次
    网络读之间的最大间隔**（`ReadTimeoutHandler`），远端只要持续吐字节就永不触发；真正给
    慢工具封顶的是后者（SDK 内部是 Reactor 的 `.timeout(...)`，**总时长，吐进度也不豁免**）。
    两个推论：一次 `callTool` 的实际耗时上限 ≈ `2×timeoutMs`（`initialize` 与 `tools/call`
    是两次请求、各享独立一份，**不是**共用一个预算）；同步工具清单永远撞不上它（毫秒级
    往返），所以「同步正常但工具调用失败」是这条的典型症状，别去查连通性。
    `McpTimeoutSemanticsTest` 起真实 HTTP server 用 TCP 时序把两层分别钉住（静默必被 Netty
    掐断 / 持续心跳能穿透那层 / 心跳救不了 SDK 那层），变异验证过：摘掉 `responseTimeout`
    只有「静默」那条转红，另两条仍绿——这正说明它们分属两层。

### OpenAPI 工具导入（`service/openapi/` + `service/tool/UrlTemplateFiller`）

租户管理员粘贴一份 OpenAPI 3.x schema（文本或 URL），系统解析出可用端点批量生成 `AiTool`
行，把接入一套 REST API 的成本从「按端点数线性投入」降到「一次导入」。四个组件职责单一：
`OpenApiSchemaParser`（文本 → `OpenAPI` 模型，JSON/YAML 都收）、`OpenApiToolMapper`（**纯
函数**，`OpenAPI` + 配置 → 候选列表 + warnings，本特性规则最密的地方）、
`OpenApiSchemaFetcher`（委托 `WebPageFetcher.downloadRaw` 拉取 URL，复用其 SSRF 硬门槛而
非另起一套出站防线）、`OpenApiImportService`（编排：取文本 → 解析 → 映射 → 冲突标记 / 批量
落库）。设计文档：`docs/superpowers/specs/2026-08-23-openapi-tool-import-design.md`。

同批顺带把 `HttpToolCallback.fillTemplate` 抽成独立纯函数类 `UrlTemplateFiller`，修了一个
既有缺陷：旧实现对 LLM 未填的占位符原样保留，可选 query 参数因此一直是坏的（
`?limit={limit}` 被真的发出去）。**新规则按 URL 两段分别处理**：**path 段**缺值直接抛
异常（由 `HttpToolCallback.call()` 既有 catch 变成「缺少必填参数 X」返回给 LLM，好过发出去
必得 404 且无从判断原因）；**query 段**按 `&` 拆开逐段判断，**段内任一占位符缺值就整段
删除**（`range=5-` 这种半填值几乎肯定比不传更糟），无占位符的静态段原样保留。

⚠️ 容易写错的地方：

1. **OpenAPI 导入只收 GET 与 POST**：`RestClientHttpToolExecutor.execute` 的分派是
   `"POST".equalsIgnoreCase(method) ? HttpMethod.POST : HttpMethod.GET`——非 POST 一律降级
   成 GET，硬把 PUT/PATCH/DELETE 导进来只会让一个 DELETE 端点被当成 GET 发出去，语义错得
   无声无息。故这三类 operation 在 `OpenApiToolMapper` 里跳过并记全局 warning，而非静默
   丢弃。
2. **工具名截断到 64，不是 `AiTool.name` 的列宽 100**：OpenAI 兼容端点对函数名的限制是
   64。超长的工具挂给 LLM 时被模型侧拒绝，症状是「工具配好了、界面一切正常、就是从来不被
   调用」——没有异常、没有日志，与「LLM 觉得不需要调用」完全无法区分。
3. **`inputSchema` 必须合并路径级共享参数，不能只看操作级**：OpenAPI 允许在 `PathItem`
   级别声明对该路径下所有 operation 都生效的共享参数（真实 schema 很常见，Petstore 就有）。
   `OpenApiToolMapper.mergeParameters` 把路径级参数在前、操作级参数在后合并，按 `(name,
   in)` 两者都相同才算同名覆盖——只比 name 会把「query 里的 id」与「header 里的 id」错误
   合并成一个参数。漏合并的后果不是「少点能力」：路径级 path 参数的占位符仍在 urlTemplate
   里（直接来自 path 字符串），但 inputSchema 没有对应属性，LLM 不知道要填，运行时必然撞上
   `UrlTemplateFiller` 的「缺少必填参数」——而工具调用是 fail-soft 的，模型拿着错误文本
   继续编答案，全程无异常无日志。⚠️ **参数本身也可能是 `$ref`**（`{"$ref":
   "#/components/parameters/X"}`，GitHub REST、Kubernetes 的官方 schema 几乎全是这种
   写法），合并**之前**必须先解引用——`mergeParameters` 在合并前对路径级与操作级两份原始
   列表分别调用 `resolveParams`/`derefParameter`。不解引用的话 swagger-core 交出来的是
   `name`/`in` 均为 null 的 `Parameter`，症状与漏合并路径级参数完全同构：path 参数缺失
   必填、query 参数彻底消失、header 参数连「被忽略」的告警都不触发。`requestBody` 同样
   可以是 `$ref`（`derefRequestBody` 解引用），解不开时全局 warning 必须明确说「请求体是
   无法解析的引用」，不能说成「请求体不是对象结构」——那会把用户引向错误的排查方向。
4. **POST 请求体字段被平铺到 inputSchema 顶层**：`HttpToolCallback` 在 POST 时把整个
   toolInput 原样当 body 发出去，body 字段必须与 path/query 参数同级，不能嵌在
   `body: {...}` 里；因此 `requestBody` 的 schema 不是 object 时该 operation 整个跳过
   （数组/裸字符串在当前模型里表达不了）。⚠️ 已知限制不修：POST 时 path/query 参数会
   **同时**出现在 URL 与 body JSON 里（因为整个 toolInput 作 body），多数服务端会忽略
   多余字段，要修得改运行时热路径的 body 构造，不在本期范围。
5. **`$ref` 只解本地 `#/components/schemas/X`，带深度上限 + 循环检测**：自引用结构（分类
   树、评论树）在真实 schema 里很常见，不设防就是无限递归栈溢出；超限或解不开时降级成
   `{"type":"object"}` 并记 warning，不让一个字段拖垮整份 schema 的导入。远程 `$ref` 直接
   不支持——那是安全决策而非工作量决策，避免一条绕过 `SsrfGuard` 的出站路径藏在解析器依赖
   内部。
6. **候选超 200 条会截断，前端必须显式提示**：`parse` 返回 `truncated: true` 时界面要有
   醒目提示告知用户「看到的不是全部」（`OpenApiImportDialog.tsx` 顶部的 destructive 提示
   条），不能只在 warnings 文案里悄悄带一句。

### 工具商店（能力包，`service/pkg/` + `dto/pkg/`）

平台管理员把自己已经调通的一组工具 + skill **导出成包**上架，租户挑一个包、填自己的凭据、
一键安装成**自己名下的副本**（可改可停用可删）。三个纯函数类关住全部规则：
`PackageManifestBuilder`（实体 → manifest + warnings）、`PackageManifestParser`（JSON ↔ manifest
+ 结构校验）、`PackageVariableApplier`（变量填充 + 残留检测）；两个 `@Service` 只做取数与落库。
设计文档：`docs/superpowers/specs/2026-08-25-tool-package-store-design.md`。

⚠️ Java 包名用 `pkg` 而非 `package`——后者是关键字，`service.package` 编译不过。

⚠️ 容易写错的地方：

1. **⚠️ 变量语法 `${VAR}` 与 `UrlTemplateFiller` 的 `{param}` 正则重叠，替换后必须断言不残留
   `${`**。`UrlTemplateFiller.PLACEHOLDER` 是 `\{([^{}]+)}`，它**会匹配 `${VAR}` 里的 `{VAR}`**。
   一个没被替换掉的变量落库之后：残留在 **query 段**会让该段被**整段静默删除**（鉴权参数就此
   消失 → 第三方 401 → 模型拿着错误响应继续编答案，全程无异常无日志）；残留在 **path 段**会
   抛「缺少必填参数 VAR」——一个 LLM 永远填不出来的参数名，排查的人会一路去查 inputSchema。
   同理，被行内引用的变量**取值本身不得含 `{` 或 `}`**，否则租户填一个 `evil{city}host.com`
   就凭空造出一个假占位符。两条都由 `PackageVariableApplier` 硬拦。
2. **安装前必须断言 `TenantContext` 的 `tenantId` 与 `appId` 都非空**。`appId` 为空时建出来的行
   `app_id` 落 null，而 `ChatToolService.resolve()` 是 filter-bound 查询——`NULL` 对任何应用都
   匹配不上，**装了等于没装且完全静默**。`tenantId` 为空的来源是
   `SecurityUtil.requireTenantAdmin()` **放行平台管理员**，而他登录后 `tenantId` 恒为 null，
   落库会写成 `tenant_id IS NULL`（「平台共享」的语义位置）。
3. **导出必须显式拒绝 `tenantId != null` 的行**。`TenantOwnershipGuard.mutableInCurrentContext`
   在上下文 tenantId 为空时**无条件放行**，而平台管理员正是这种情况——于是
   `chatToolService.getTool(任意 id)` 能取到**任意租户**的工具。不拦住，平台管理员切到某个租户
   的应用再导出，就会把该租户私有的工具配置（name / description / urlTemplate）随包发给所有
   租户。判据取 `tenantId == null` 而非应用范围：平台管理员在任何应用下建的行 `tenant_id` 都是
   null，这条精确等于「只导出平台管理员自己建的配置」。
4. **导出全程只读实体，绝不 setter 置空**。本项目未配 `spring.jpa.open-in-view`（即 Boot 默认
   `true`），OSIV 让查出来的实体在整个请求期保持受管——为了「不导出明文凭据」而写一句
   `setAuthHeader(null)` 会被脏检查刷回库，**把平台管理员自己的凭据真的删掉**（同
   `SharedCredentialMasker` 那次，靠 `em.detach` 才闭合）。⚠️ 直调 service 的单测**能**抓住
   朴素的错误（如 `setAuthHeader(null)` 忘记还原——`build()` 的 for-each 绑定的是同一个对象
   引用，没有 copy/clone，断言不会退化成永真）；抓不住的是更窄的一类：真实 OSIV 下「设 null →
   中途被别的查询触发 auto-flush → 之后再还原」的时序问题，这一类由走真实 MockMvc 的
   `PackageExportIT` 钉住。
5. **引用一律用包内 `key`，绝不用 id**。`AiSkill` 绑的是 `toolIds`，而安装时工具是新建行、id
   全新；安装期间维护 `key → 新 id` 映射再回填。漏掉这层映射**完全静默**：`resolve()` 查不到
   就跳过，界面上 skill 仍显示绑着 N 个工具（同「MCP 接入」第 7 条同一失效模式）。
6. **`sync()` 绝不进安装事务**，安装因此分两段：段一（事务内）建行，段二（事务外）同步 MCP
   工具清单并回填 skill 绑定。段二失败**不回滚段一**——这是有意的部分成功，界面必须把
   「请手动同步后再到技能里勾选工具」显眼地留在页面上，不能只弹一次 toast。
7. **manifest 里没有 `scope` / `tenantId` / `appId`，安装时服务端强制写入**。「`scope` 是客户端
   可控字段」这条已经真实栽过一次（见上面「平台共享工具」第 1 条），manifest 是另一条能把值送
   进实体的路径——**字段压根不存在比判空更可靠**。
8. **`tool_package.manifest` 必须是 `text`**，长度由包内容决定、无可预估上限（同
   `McpTool.description` 那次 context7 接不进来）。⚠️ 存量库另需迁移 SQL——`ddl-auto: update`
   只加列、不改已有列的类型。
9. **冲突检测按 name 整体拒绝，不自动改名**；工具名的已占用集合**必须算上平台共享工具**——
   `resolve()` 遇到重名会静默跳过其中一个，与共享工具重名的工具装进去了也挂不上 LLM。
10. **不建安装记录表**（spec §2.3）：一次性、装完断开。有了它就会长出「已安装」状态，而那个
    状态是假的——租户随手删掉装下来的工具，表里仍写着已安装。
11. **⚠️ 绑定完成 ≠ AI 能用（真机验证挖出，2026-08-28）**：`syncMcpAndBindSkills` 只按名字把
    刚同步出来的 `mcp_tool` id 塞进 `skill.mcpToolIds`，而 `McpServerService.sync()` 对全新
    同步的工具一律 `readOnly=false`（「未知一律当可写、不挂载」），`ChatToolService.
    resolveMcpCallbacks` 又是 `findByIdInAndStatusAndReadOnlyTrue`——只取 `readOnly=true`
    的。三者叠加：**安装报告零 warning、界面显示技能绑着 N 个 MCP 工具，实际挂给 AI 的是 0
    个**。⚠️ 修法**绝不能是自动帮租户标只读**——CLAUDE.md「MCP 接入」第 3 条明令「同步绝不
    覆盖 `readOnly`」，它是租户自己的安全判据，代劳就是安全降级且完全静默（一个可写工具会被
    LLM 调用）。正确做法是 `syncMcpAndBindSkills` 在回填绑定时额外加一条 warning，明确告知
    「已绑定 N 个 MCP 工具，但默认可写、暂不会挂给 AI，请自己去 MCP 服务页标只读」——多一步
    人工确认，好过让一个可写工具静默生效。
12. **导出的勾选列表在前端也要过滤 `tenantId == null`（真机验证挖出，2026-09-02）**：
    `PackageExportDialog` 拉的 `aiToolService.list()` / `mcpApi.listServers()` /
    `aiSkillService.list()` 都是**应用级**接口，而 `TenantDataAspect` 对 PLATFORM_ADMIN 整体不
    启用 `@Filter`——平台管理员选中某个租户的应用时，拿回来的是**那个租户的私有配置**。不
    过滤的后果有两层：界面把租户的私有工具铺出来任人勾选、勾了才被后端拒绝（用户无从区分
    哪个能导），且同一判据在前后端各写一次会各自漂移。判据必须与 `PackageManifestBuilder`
    的导出判据逐字一致，由 `PackageExportDialog.test.tsx` 守护（变异验证过：换回
    `toolRes.data ?? []` 转红）。⚠️ 空状态文案也要跟着说「没有**你自己建的**工具」——
    说成「当前应用下没有工具」是错的，那个应用下往往有一堆，只是都不是平台管理员的。

**四道扫描哨兵覆盖本链路**：`PlatformEndpointGuardTest`（`/v1/platform/**` 每个端点必须调
`requirePlatformAdmin()`）、`PackageManifestFieldCoverageTest`（实体可变字段必须进 manifest 或
进白名单并挑一个类别）、以及既有的 `UpdateRequestFieldCoverageTest` / `UpdateRequestPropagationTest`
（`ToolPackageUpdateRequest` 已登记）。⚠️ `UpdateRequestFieldCoverageTest.DTO_TO_ENTITY` 现在用
`Map.ofEntries` 而非 `Map.of`——后者最多 10 对，已经用满。

### 改动工具商店 / 能力包链路后必须手工验证

自动化测试覆盖不到的四条：

1. **凭据真的是租户填的那个，且出站 URL 形态正确**：装一个含 HTTP 工具 + skill 的包（工具的
   `urlTemplate` 里要带一个 `${VAR}` 变量）→ 发一句触发工具的对话 → 确认**出站请求头里带的是
   安装时填的值**、**URL 里既没有残留 `${`、也没有半截的 query 段**。凭据若在某个环节被清掉，
   症状是第三方返 401、模型拿着错误响应继续编答案，**全程无异常无日志**（同平台共享工具那条）。
2. **MCP 包的两条路径**：正常 url 装一次，确认 sync 成功后 skill 的 `mcpToolIds` 真绑上了、
   **且看到那条「已绑定 N 个 MCP 工具，默认可写、暂不会挂给 AI，需自己标只读」的提示**——
   绑上了不等于能用，见上面「容易写错的地方」第 11 条；再用坏 url 装一次，确认是**部分成功**
   （工具与 skill 都在、提示明确说了要手动同步），不是整体失败。
3. **上下文不泄漏**：连续给**两个不同租户**各装同一个包，确认第二个租户的行 `tenant_id` /
   `app_id` 仍正确。`PackageInstallIT` 已覆盖 MockMvc 层的同款场景，真机这条验的是**真实浏览器
   会话下连做两次**（切账号、切应用），只有真机连做才暴露会话级的上下文残留（同站点爬取第 7 条）。
4. **manifest 预览逐字核对**：导出一个 URL 里带 `?apikey=` 的工具，确认界面弹出了密钥告警；再
   人工通读一遍 manifest，确认没有明文凭据、变量的 label 与 help 是人话。凭据识别与密钥扫描都是
   启发式，**没法自动化**——同噪声剔除，这是唯一的验收方式。

### 安全基线（2026-09-03 全项目审查后必须保持的几条）

审查报告在 `docs/superpowers/reviews/2026-09-03-security-review.md`（分级发现 + 修复状态）。下面这些是修完后立下的不变量，改动相关代码前先读对应条目：

1. **平台管理员口令不写死**：`AuthService.init` 从 `qingyu.care.platform.admin-initial-password`（`QINGYU_ADMIN_INITIAL_PASSWORD`）取，为空则随机生成、只打一次 WARN。dev/test profile 固定 `admin123`（多条 IT 靠它登录）。`AuthServiceInitTest` 守着「不回落 admin123」。
2. **自助注册只能建新租户**：带 `tenantCode` 一律 403。租户 code 不是秘密，凭它加入曾等于自封任意租户的管理员。
3. **同租户「改他人」必须是管理员**：`UserController.updateUser` 先判 self / admin，坐席只能改自己。`User.password` 是 `WRITE_ONLY`，实体当响应体也不会带哈希。
4. **应用管理走 `ApplicationView`，密钥只给管理员**：list/get 全体成员可用（顶部选应用靠它）但坐席拿到的 `appSecret`/`webhookSecret` 为 null；create/update/delete/重置密钥/测试 Webhook 一律 `requireTenantAdmin`。⚠️ 脱敏不能在受管实体上 setter 置空（OSIV 会刷回库），必须走视图对象。
5. **五条出站路径共用一套防线**：HTTP 工具、网页抓取、Webhook 走 `PinnedHttpClientFactory`（钉 IP + 禁重定向 + 超时），MCP 走 `PinnedAddressResolverGroup`，OpenAPI schema 复用抓取器。⚠️ Webhook 回调地址落库前过 `SsrfGuard.assertAllowedHttpUrl`；`qingyu.care.webhook.allow-private-targets` 只在 test profile 为 true（IT 的接收端起在 127.0.0.1），它只跳过网段校验、不跳过钉 IP。`SsrfGuard.isBlocked` 手工补了 100.64/10（阿里云元数据 100.100.100.200 在这段）、192.0.0/24、198.18/15、240/4、NAT64。
6. **CORS 两套 + 不用 cookie**：`/open/v1/**` 与 `/widget.js` 任意来源、`allowCredentials(false)`；其余只放行 `qingyu.care.cors.allowed-origins`（默认空 = 拒绝一切跨域，前端与后端同源）。`sa-token.is-read-cookie: false`——SPA 只用 `satoken` 请求头，cookie 一开就是 CSRF 面。`CorsPolicyIT` 守着。
7. **异常处理器不回显 `e.getMessage()`**：`RuntimeException` 回「服务器内部错误（错误编号 xxx）」，编号进日志；`IllegalArgumentException` 归 400 且同样不回显。要给用户看的原因改抛 `InvalidOperationException` / `BusinessException`。
8. **前端退出登录走 `lib/session.performLogout`**：先调后端注销再清本地（含 `qingyu-current-app`）。
9. **`X-App-Id` 必须属于当前租户才进上下文**：`TenantInterceptor` 用 `findTenantIdById` 反查，不属于就忽略并 WARN（平台管理员豁免）；`WebhookDispatcher` 再兜一道「应用租户 == 事件租户」。`TenantInterceptorTest` 守着。
10. **模型 endpoint 落库前过守卫**：`ModelRouterService.requireValidEndpoint` 只在管理面入口（建 / 改 / 测试连接，含平台共享模型）调用，不进对话热路径；`qingyu.care.model.allow-private-endpoints` 只在 dev profile 打开（本机 Ollama）。单测里 `ModelRouterService` 是 mock，控制器测试不会真的解析 DNS。
11. **Swagger / metrics / DEBUG 日志基线关闭，只有 dev profile 打开**：三者的路径都在 Sa-Token 豁免名单里，「关掉」是唯一可靠手段。`ManagementEndpointsExposureIT` 守着基线形态（test profile 不含 dev）。
12. **开放 API 与登录都校验租户状态与 `expireTime`**：冻结或到期的租户，widget / 开放 API / 登录一起停。⚠️ `ApplicationRepository.findByAppKey` 带 `@EntityGraph(tenant)`——鉴权在拦截器阶段执行、没有 OSIV 的 EntityManager，懒加载的 `tenant` 一碰非 id 字段就是 LazyInitializationException，而拦截器的兜底 catch 曾把它静默吞成 500（现在会记日志）。
13. **开放 API 两层限流**：应用级（`default-rate-limit-per-minute`，应用可覆盖）之下再按「应用 + 客户端 IP」分桶（`per-client-rate-limit-per-minute`，默认 20，0 关闭）。客户端 IP 与审计日志一样取 `X-Forwarded-For` 的**最后一段**（nginx 追加模式，前面的段客户端可自填）。
14. **知识库上传先校验再传 S3**：`getSize()` 闸门（`qingyu.care.knowledge.max-upload-bytes`，30MB）→ 非空 → 扩展名白名单（`UPLOAD_EXTENSIONS`，与 `detectDocumentType` 一一对应）→ 文件名截到 255；`documentRepository.save` 失败必须 `deleteFile` 回收对象。
15. **摄取走 `TikaTextExtractor` 流式抽取，两道上限**：`max-extracted-chars`（200 万，超出截断 + WARN）与 `max-chunks-per-document`（2000）。⚠️ `TokenTextSplitter.withMaxNumChunks` 到顶后会把**剩余全部文本追加成最后一块**，所以切完必须再 `subList` 一次，否则封顶形同虚设。不再用 Spring AI 的 `TikaDocumentReader`（要整文件 `byte[]` 且输出无上限）；`ParseContext` 刻意不放 `Parser`，不递归解析嵌入文档。
16. **附件单会话上限 + 孤儿 2 小时**：`ChatAttachmentService.upload` 先 `countBySessionId` 判 `max-per-session`（20）；`orphan-retain-hours` 从 24 缩到 2，它是无凭据灌存储的唯一落点。租户级存储配额尚未做（要按套餐定数字）。
17. **Webhook 签名覆盖「时间戳.请求体」**：`WebhookSignature.sign(secret, timestampMillis, body)`，时间戳同时随 `X-Qingyu-Timestamp` 发出；接收方复算 + 5 分钟窗口 + 按载荷 id 去重。改契约在未上线的现在零成本，上线后别再动。
18. **Prompt 定界**：知识库正文包在 `<knowledge>`（`ChatServiceImpl.buildContext`）、工具返回包在 `<tool_result>`（`ToolResultDelimiter.wrap`，HTTP 工具与 MCP 都走它，截断作用在正文、标签不占额度），系统提示末尾固定追加 `DATA_GUARD_PROMPT` 声明标签内是数据不是指令。放在代码里而不是只改默认模板：模板已播种进库。是缓解不是根治。
19. **分析 rollup 有日期锁**：`AnalyticsRollupDao.tryLockDay` 用 `pg_try_advisory_xact_lock(classId, day)` 双参数形式，与文档处理互斥的单参数形式键空间互不重叠；抢不到返回 false、`backfill` 不计入成功。其余 7 个 `@Scheduled` 各有自己的互斥（文档咨询锁 / CAS 认领 / 消费组）或重复无害，不需要锁。
20. **依赖扫描是手动步骤**：`scripts/dep-audit.sh`（`-Pdepcheck` + `pnpm audit`），刻意不绑进 `mvn verify`，见脚本头注释。⚠️ `pnpm audit` 必须显式 `--registry=https://registry.npmjs.org`——npmmirror 之类的镜像没有 audit 端点，会报 `ERR_PNPM_AUDIT_ENDPOINT_NOT_EXISTS`；后端那半需要免费的 `NVD_API_KEY`，不给会在更新漏洞库时直接失败。
21. **反代场景必须配 `server.forward-headers-strategy: framework`**：nginx 终结 TLS 后后端收到的是 http，而浏览器 `Origin` 是 https——不读 `X-Forwarded-Proto` 的话 Spring 把**同源**请求判成跨域，撞上管理端默认为空的 CORS 白名单直接 403，**整个后台登录不了**，而浏览器里只显示「网络错误，请稍后再试」。⚠️ 前提是 8080 只经受信任的反代对外，别直接暴露公网。`CorsPolicyIT` 那两条（同源放行 / 反代终结 TLS 后同源放行）守着。
22. **Vite 代理必须摘掉 `Origin` 头**：`changeOrigin: true` 只改 Host。不摘 Origin 的话后端看到 `Origin=localhost:5175` 而 `Host=localhost:8080`，判成跨域 → 403；而 5173 被占用时 Vite 会自动漂到 5174/5175，白名单永远追不上。走反代时前端与后端本就该视为同源。
23. **`allow-private-*` 开关的落库校验与出站校验必须看同一个开关**：`ApplicationController.requireValidWebhookUrl` 与 `RestClientWebhookHttpClient` 各有一道，只改一处会让开关半失效（出站放行、落库仍拒），本地联调根本配不上地址。⚠️ 集成测试发现不了——它们直接 `setWebhookUrl` 写库，绕过控制器。
24. **工具调用失败的日志要能区分「被守卫拦下」与「网络问题」**：`HttpToolCallback.describeForLog` 对守卫抛的 `IllegalArgumentException` 记完整消息（里面只有 host），其余异常只记类名（消息可能带含密钥的 URL）。MCP 侧复用同一方法，两条链路口径必须一致。
25. **⚠️ 本机若用 fake-IP 模式的代理，所有出站工具调用都会被 SsrfGuard 拒**：Clash 一类代理把域名解析到 `198.18.0.x`，而该段在黑名单里（RFC 2544 基准测试段，生产无正当用途）。症状是工具静默失效，日志现在会明写「已拒绝: <host>」。AI 对话不受影响（走 SDK 直连、不经守卫）。这是环境限制不是缺陷，别为此放宽黑名单。
26. **三条出站路径的私网开关是一套**：`model.allow-private-endpoints`（本地 Ollama）、`webhook.allow-private-targets`（本地接收器）、`mcp.allow-private-servers`（本地自建 MCP server），都走 `SsrfGuard.assertAllowedHttpUrlAllowingPrivate`，dev profile 全开、生产全关。⚠️ 缺任何一个都会让对应场景在本地根本调不通，而这不是「不可验证」，是开关没配齐——MCP 那个就是 2026-09-04 补的。
27. **MCP 登记时不校验 URL、只在同步时校验，这与 Webhook「落库就拦」的差异是有意的**：MCP 有 `lastSyncError` + 界面警示条这条设计好的失败反馈通道，错误立即可见；Webhook 配错地址是在事件推送时静默失败，用户不会主动看投递表，所以必须落库就拦。别为了「一致」把 MCP 也改成落库拦。
28. **租户级存储配额只算对象存储**：`StorageUsageDao` 求 `document.file_size + chat_attachment.file_size`，按套餐配 `billing.quota.<PLAN>.storage-bytes`（FREE 1GB / PRO 20GB / ENTERPRISE 不限，0=不限）。⚠️ 爬取的 URL 类文档 `file_size` 为 null、不占 S3，**刻意不计入**，那条链路由 `max-pages-limit`（500）约束。检查点在知识库上传与附件上传两处、都在传 S3 之前；统计失败 fail-open 放行。⚠️ `KnowledgeServiceImpl` 的 `QuotaService` 是 setter 注入（构造函数被 10 个单测手工调用），没注入会 NPE 而不是静默跳过。
29. **`sa-token.is-log` 保持开启，但日志实现必须是脱敏版**：内置的 `SaLogForConsole` 会把完整会话凭证打进 INFO 且直接 `System.out`（logback 的 pattern 过滤够不到）。`SaTokenConfig.installMaskingLog` 在 `@PostConstruct` 里换成 `TokenMaskingSaLog`——复用 Sa-Token 自己的 `StrFormatter` 渲染后整体正则脱敏，再转交 SLF4J。⚠️ 正则的 `` 词边界不能去掉，否则配置日志里的 `tokenName=satoken` 会被一起脱掉，而那条正是排查「请求头名字配错」时要看的。别把这段当冗余删掉，删了之后 token 会重新进日志且没有任何报错。
30. **`allow-private-*` 开关永不放行云元数据**：`model.allow-private-endpoints` 与 `webhook.allow-private-targets` 是给本地 Ollama / IT 的本地接收器用的，两者都走 `SsrfGuard.assertAllowedHttpUrlAllowingPrivate`——放行环回与普通私网，但 169.254.0.0/16、100.64.0.0/10、fe80::/10、fd00:ec2::/64 **任何开关都拦**。⚠️ 这条是**真机冒烟点出来的**：原实现打开开关后只校验 URL 形态，dev 下把模型 endpoint 填成 `http://100.100.100.200/v1`（阿里云元数据）能成功落库，而当时单测是绿的——因为断言把「只校验形态」写成了预期，测试固化了错误行为。改这两个开关时先问一句：放开的是「本地地址」还是「连元数据一起」。

### 对外开放 API（`/open/v1/**`）
- `OpenApiAuthInterceptor` + `OpenApiAuthService`：双路径鉴权——服务端用 `X-App-Id` + `X-Api-Key`（校验 `app.appSecret`）；浏览器 Widget 用 `X-App-Id` + Origin 白名单（`app.allowedOrigins`）。鉴权通过后写入 `TenantContext` 并做限流，超限返回 429 + `Retry-After`。
- 控制器在 `controller/open/`，DTO 在 `dto/open/`（如 `OpenChatController` 的 `/chat/completions` 与 SSE `/chat/stream`）。

### 网站知识库（`service/web/` + `scheduler/WebPageSyncScheduler`）
把网页正文抓进知识库并定期同步。`Document` 复用现有实体，URL 类文档多用 5 个字段（`sourceUrl`/`contentHash`/`syncInterval`/`lastSyncedAt`/`syncFailureCount`），文件类文档这 5 列为 null。
- `WebPageFetcher` 不碰数据库、纯可测：SSRF 硬门槛（钉 IP 防 rebinding + `disableRedirectHandling` + 10s 超时 + 5MB 边读边计数 + Content-Type 白名单）→ **先按状态码拒绝非 2xx，再读 body**（否则 404/500 错误页文案会被当正文摄入）→ Jsoup 剔噪声 → SHA-256。
- `WebPageSyncService.syncDocument` 三条分支是核心：**hash 未变→只更新 `lastSyncedAt`、不重嵌**（省 embedding 成本；这条一旦退化成"每次都重嵌"，功能照常、只是悄悄烧钱，没有任何现象提醒）；**hash 变了→`reprocessDocument` 重切重嵌**；**抓取失败→保留旧内容与旧向量**，只累加 `syncFailureCount`，连续 10 次转 `MANUAL` 熔断（任一次成功清零）。
- ⚠️ **调度器的到期查询必须用原生 SQL**（`DocumentRepository.findDueWebPageIds`）：`Document extends BaseTenantEntity` 受 `@Filter` 保护，而调度线程没有 `TenantContext`——用 JPQL 会一条都查不到，且**任务照跑、日志照打，纯静默失败**。同「多级配置回退」那个坑。
- ⚠️ **`addWebPage` → `syncDocumentAsync` → `syncDocument` 两跳都必须走 `self`**（`@Autowired @Lazy` 自注入）：同类直调不经代理，`@Async` 与 `@Transactional` 双双失效；后者失效时 doc 中途 detached，`save()` 会把陈旧字段 merge 回库、静默覆盖 `processDocument` 刚提交的结果，导致 `contentHash` 永不落库 → **每轮无条件重嵌，永远**。
- ⚠️ 取知识库一律用 `knowledgeService.getKnowledgeBase(id)`（内含 `TenantOwnershipGuard`），**绝不用 `knowledgeBaseRepository.findById`**——后者不受 `@Filter` 保护，等于让任意租户往别人知识库里插文档。
- ⚠️ **噪声剔除是启发式，只认语义标签**（`script/style/nav/header/footer/aside/noscript/iframe/form`）。真机实测：用 `<div>` 做导航的站点（如菜鸟教程）导航文字会大量入库并稀释检索相似度。这是已知局限，不是 bug；不够用时再按 URL 加自定义 CSS 选择器。
- `processDocument`（`KnowledgeServiceImpl`）对 URL 类文档走独立分支：直接用 `doc.getContent()` 切分嵌入，**跳过 S3 下载与 Tika 解析**（网页文档 `filePath` 恒为 null）。新增非文件来源的文档类型时务必同步加分支。
- **站点爬取（第二期）**：`CrawlSite` 表存站点配置（入口/范围前缀/页数/深度/正文选择器/同步周期），`Document.crawl_site_id` 可空外键指向它（单页手动添加的为 null）。`SiteCrawlService` 做 BFS 编排：`LinkExtractor`（纯函数，规范化 + 范围判定 + 抽链接）→ `RobotsChecker`（robots.txt，取不到按允许）→ 逐页建档摄取。新页面靠**手动重爬**发现，不做定期自动重爬（内容更新已由周期同步覆盖，页面消失沿用既有熔断）。
- ⚠️ **`LinkExtractor.inScope` 是一个 `return` 语句里的三个 `||` 分支各挡一个边界，不是三个 `return` 分支，不能简化成裸 `startsWith`**：规范化会去掉末尾斜杠而前缀常带斜杠（裸判会把**入口页自己**排除，爬取一开始就空转）；拿去斜杠的前缀判又会把 `/helpdesk` 当成 `/help` 的子页整个爬进来；前缀是根路径时补斜杠会拼出 `//` 一条都匹配不上。
- ⚠️ **`FetchResult.dom` 是剔噪前 `clone()` 的副本**：`extractFrom` 就地移除 `nav`/`header`/`footer`，而帮助中心的目录导航恰在 `nav` 里。交出残树的后果是链接发现几乎抓不到东西且**不报错**，表现为「爬了 1 页就结束」——与上面那三个边界的表象完全一致，排查时要分别验证。加了 `dom` 字段后 record 自动生成的 `equals`/`toString` 也不能按字面意思用：Jsoup 的 `Document` 是恒等语义（连 `a.equals(a.clone())` 都是 false，别拿 `FetchResult` 做相等比较或塞进 `Set`），`toString()` 会把整棵 DOM 序列化进去（5MB 响应体对应的树进日志就是一次事故，别把整个 `FetchResult` 记日志）。
- ⚠️ **爬取跑在 `@Async` 线程，四个 `TenantContext` 值必须由请求线程传入、`finally` 必须 clear**：不搬运则新建文档的 `tenant_id`/`app_id` 静默落 null——**不是「对所有租户可见」，而是反过来「对任何租户都检索不到」**：`KnowledgeServiceImpl.processDocument` 在 tenantId 为 null 时把向量 metadata 写成 `"tenantId": ""`，而 `RetrievalScope.toFilterExpression` 对非 null 租户拼的是 `tenantId == '<真实租户id>'` 等值条件，`""` 永远匹配不上任何真实租户 id。后果是成本/效果事故（embedding 钱照花、0 命中、全程无异常无日志）而非安全问题；不清理则泄漏给下一个复用该线程的任务（长任务池同样复用线程，流式聊天改造时实测复现过平台管理员会话落进别人租户）。**新增在 `@Async` 里建实体的代码一律照此处理。** ⚠️ 同一线程里 `CrawlSite.knowledgeBase`（LAZY）只有 `getId()` 是安全的——本项目未开字节码增强，detached 代理上调 `getId()` 走 `HibernateProxy` 缓存的 identifier，既不抛也不打 DB；但改成 `getKnowledgeBase().getName()`，或把 `getKnowledgeBase()` 拿到的代理塞进 `Set`/调它的 `equals`（`KnowledgeBase` 是类级 `@Data`，equals 摸全字段；`CrawlSite` 自己倒是安全的——它显式声明按 `id` 判等，不会碰到这个代理），就会在这条异步线程里真的抛 `LazyInitializationException`。
- ⚠️ **`maxPages` 计「已尝试抓取」而非成功页数**，被 robots 拒绝的不计（没发请求）。计成功数的话，一个到处 404 的站点会让队列不断展开、永远碰不到上限。
- ⚠️ **爬取新建文档同样遵守「摄取成功才落 `contentHash`」，且必须同时设 `lastSyncedAt`**——只落前者不落后者会让 `findDueWebPageIds` 因 `last_synced_at IS NULL` 立刻判为到期，爬完 50 篇后下一轮调度把它们全部重抓一遍。
- **站点级互斥用内存 map，不能用第一期那把 `pg_try_advisory_xact_lock`**：后者是事务级的，与「几分钟长任务 + 逐页提交」直接冲突。多实例下内存互斥失效，最坏是重复文档——刻意**不加** `(kb_id, source_url)` 唯一约束兜底，因为第一期 `addWebPage` 无去重校验，存量库可能已有重复 URL，`ddl-auto: update` 建唯一索引会让应用起不来。
- ⚠️ **内存进度表 `jobs` 里的 RUNNING 是全局并发额度的判据**：`runCrawl` 的 `finally` 必须把残留 RUNNING 兜底置终态（`catch` 只抓 `Exception`，抓不住 `Error`）；`launch` 投递 `@Async` 失败（线程池拒绝）必须同时回滚 `crawlingSites` 与 `jobs`——漏一处都会让某条 Progress 永久占额度，只能重启恢复。
- ⚠️ **`crawl()` 收尾不能用几分钟前查出的、此刻已 detached 的 `site` 去 `save`**：`em.merge()` 会把开爬那一刻的全部陈旧字段（含运营在爬取期间边看边改的 `contentSelector`）覆盖回库，静默抹掉配置修改且无异常无日志。必须收尾时重新 `findById` 再设值——同 `WebPageSyncService` 类注释点名过的「陈旧字段 merge 回库」同一个坑，换了个位置复发。
- ⚠️ **`startCrawl` 不能带 `@Transactional`**：带上会让 `save()` 只 flush 不 commit，紧接着在同一未提交事务内把 `runCrawl` 投给 `@Async`——异步线程走独立连接，READ COMMITTED 下看不到这次未提交的 INSERT，`findById` 直接 `orElseThrow`，首次开爬非确定性地立刻 FAILED「站点不存在」。`ReembedService.startReembed` 就是不带 `@Transactional` 的同一范式；`updateSite`/`deleteSite` 不投递异步任务，`@Transactional` 保留。
- ⚠️ **robots.txt 解析两处易错**：连续 `User-agent` 行属同一组（规范上如此，逐行重置状态会丢掉该组的 `Disallow`）；UTF-8 BOM 附着在首行 key 上，`trim()` 不剥 U+FEFF，会让整份文件的 `Disallow` 全部静默失效。两条错误方向都是「放行站点明确禁止的路径」，遵守 robots.txt 是替租户出站的合规承诺，故这两条判定"现在修"不留技术债。
- ⚠️ **`Crawl-delay` 是第三方（被爬站点）可控值，必须钳上限**：不设上限时对方声明 `Crawl-delay: 300` 乘以 500 页硬顶就是 40 多小时，全程占着长任务池（`AsyncConfig.LONG_TASK_EXECUTOR`，固定 6 线程）的一个线程。
- ⚠️ **删站点勾选删文档时必须逐篇走 `knowledgeService.deleteDocument`**，不能 `repository.deleteAll`——只删库行会留下再也没有库行指向、**却仍参与检索**的孤儿向量（继续被命中并拼进 Prompt，而界面上已看不到这些文档）。同附件孤儿清理必须连 S3 一起删。
- **重爬时已存在的页会被抓两次**（一次为提链接、一次在 `syncDocument` 内部）。这是刻意的取舍：抽取 `syncDocument` 的三分支能省下这些请求，但那段代码的每条注释都是一个真实缺陷的墓碑。触发重新考虑的条件写在 `docs/superpowers/specs/2026-08-06-site-crawl-design.md` §5.2。
- ⚠️ **前端轮询两个坑**（`CrawlSiteList.tsx`）：进度/定时器必须按 `jobId` 而非 `siteId` 索引（后端 `maxConcurrentJobs=2` 明确支持双任务并发，按 `siteId` 索引会在旧任务收尾与新任务写入之间的窗口期互相覆盖）；`isMountedRef` **必须在 effect 的 setup 阶段复位为 true**——只在 cleanup 里置 false，会被 `<StrictMode>` 的 `setup→cleanup→setup` 卡死在 false，`pnpm dev` 下轮询一次都跑不起来（进度不更新/不续期/不 toast/不刷新列表），而 `vite build` 不受影响、React 18 起也没有 console 警告，极难发现。

### 聊天附件（多媒体消息，`service/media/` + `controller/support/AttachmentResponses`）
用户（Widget）与坐席（工作台）互发图片/文件。`ChatAttachment` 独立表（继承 `BaseTenantEntity`），**上传与发消息分两步**：先 `POST .../chat/files` 拿 id，再发消息带 `attachmentIds`。
- **`messageId` 可空**是这套设计的核心，带来两个必须处理的后果：① 「传了没发」的孤儿由 `AttachmentCleanupScheduler` 清理，**必须连 S3 对象一起删**——只删库行会留下永久占用且再也查不到的对象（`cleanupOrphans` 只按 `messageId IS NULL` 查）；② 发消息时必须校验 `attachmentIds` **属于当前租户（`mutableInCurrentContext`）+ 属于当前会话 + 未被占用**，少一道就能遍历 id 把别人的附件挂到自己消息上。
- ⚠️ **`sessionId` 空串两端都拒绝**。它曾经是「所有访客共享的命名空间」：既能被任意同 app 调用方枚举下载，且这类附件**永远挂不上消息**（`attachToMessage` 要求 sessionId 相等）→ 传上去就是死数据。**上传前必须先有会话**（widget 的附件按钮在建会话前禁用）。
- ⚠️ **开放 API 的下载端点必须带 `?sessionId=`**：`belongsToCurrentContext` 在开放 API 上只能校到 **app 粒度**，而开放 API 的身份本来就是「这个 app」——守卫对同 app 调用方等于不设防，而 `id` 是自增整数。sessionId(UUID) 是第二重能力凭证，与本仓库其它访客私有资源一致。
- ⚠️ **读路径用 `mutableInCurrentContext` 而非 `belongsToCurrentContext`**：后者对 `tenantId == null` 返回 true（那是为「平台默认配置=有意共享」设计的豁免），而平台管理员上传的附件 NULL 租户**从无共享意图** → 会被任意租户读到。
- **私有桶 → 一律经代理端点**，图片 `Content-Disposition: inline`、其余强制 `attachment`，都带 `X-Content-Type-Options: nosniff`（与「白名单不收 SVG」是同一防御目标的两半）。
- ⚠️ **`AttachmentDTO` 不含 url，客户端必须 fetch 带鉴权头取 blob 再 `createObjectURL`**：下载端点是**请求头鉴权**，而浏览器 `<img src>`/`<a download>` **不发自定义头**，直接用必 401。也**不能复用卡片的 `image` 组件**——`safeUrl` 白名单只放行 http/https，`blob:` 会被 fail-closed 拒掉且**静默不显示、无任何报错**。
- ⚠️ **`width`/`height` 对 webp 恒为 null**：JDK 内置 ImageIO 无 WebP 解码器，且 `ImageIO.read` 无解码器时是**返回 null 不抛异常**。占位代码必须容忍 null。
- ⚠️ **读图片尺寸只读文件头、不解码像素**（`ImageIO.createImageInputStream` + `getImageReaders` + `getWidth(0)`），并对 `width*height` 设 8000 万像素上限。用 `ImageIO.read` 全解码时，一枚 410KB 的合法 PNG（IHDR 声明 12000×12000）就能分配 411MB 堆 + 2.8s CPU，**5MB 的字节限额对解码后内存零约束**。
- **上传校验四道顺序不可换**：按类型分别限额（图 5MB/文件 20MB）→ 扩展名白名单 → **Tika 探测真实 MIME 并与扩展名比对** → 图片再读尺寸。只信客户端 `Content-Type` 等于没校验。**SVG 不进白名单**（XSS 载体，而附件从本域下发）。
- **`getSize()` 硬闸门必须早于 `getBytes()`**：Spring 的 multipart 上限是 32MB（与知识库上传共用；知识库单文件 30MB、附件 20MB，改这三个数要一起改），不先卡就会把整个文件完整读进堆才拒绝。
- ⚠️ **`RealtimeNotifier.messageJson` 是在推送那一刻现查附件**，所以 attach 必须排在 notify **之前**——顺序颠倒会让「坐席已接管、用户发截图」这个最核心场景下推给坐席的 `attachments` 恒为空数组（只有文本没有图），而没有任何测试会红。
- ⚠️ **流式路径的 attach 走 `self.attachInNewTransaction`（REQUIRES_NEW）**：`attachToMessage` 自带 `@Transactional`，若在 `transactionTemplate.execute` 内以**参与型**事务抛异常，`globalRollbackOnParticipationFailure` 会把外层标 rollback-only，catch 住也救不回来 → 提交时抛 `UnexpectedRollbackException` 被既有 catch 吞掉 → **用户消息与 AI 回复双双回滚，而客户端收到正常 DONE、刷新后整轮问答消失**。非流式 `chat()` 保持整轮回滚（异常可见、用户能重试），两条路径的取舍**有意不同**。

### Webhook 事件推送（`event/` + `service/webhook/`）
链路：业务发布 `WebhookDomainEvent` → `WebhookDispatcher`（`@TransactionalEventListener(AFTER_COMMIT)`，按 app 订阅事件过滤）落库 `WebhookDelivery(PENDING)` → `WebhookQueue.dispatch()`（**Redis Stream 队列**：XADD 入流，`WebhookStreamConsumer` 消费组拉取；队列关闭或 Redis 故障进入冷却期时回退 `WebhookSender.@Async deliverAsync()` 直投，fail-open）→ HTTP 投递，`WebhookSignature` 做 HMAC-SHA256 签名（头 `X-Qingyu-Signature: sha256=...`）→ 失败按 `backoff-minutes` 退避重试，`WebhookRetryScheduler`（`@Scheduled`）扫描到期的重投（同样经队列派发）。**语义边界：DB 投递行（PENDING + claim CAS）才是事实源与至少一次保证，队列只做加速分发——消费侧先 ACK 再投递，消息丢失/重复均无害。** 配置见 `WebhookProperties`（前缀 `qingyu.care.webhook`，队列子配置 `qingyu.care.webhook.queue.*`）。`WebhookQueueIT`（真 Redis 容器，独立 Spring 上下文）与 `WebhookDeliveryIT`（无 Redis，验证直投兜底）分别守护两条路径。

### 服务层与 DTO
- **只有复杂服务（`ChatService`、`KnowledgeService`）才用 `service/` 接口 + `service/impl/` 实现拆分**；其余服务（`ConfigService`/`ModelRouterService`/`ChatStrategyService` 等）是 `service/` 下的单个 `@Service` 具体类，不必强行加接口。
- 定时任务：`scheduler/SessionTimeoutScheduler`（`@Scheduled` 扫描超时会话）；Webhook 重投的 `WebhookRetryScheduler` 在 `service/webhook/`。
- **未使用 MapStruct**（pom 里虽引入，但实际用静态工厂如 `OpenChatResponse.from(...)` 手动转换 DTO）。
- 统一响应包 `dto/ApiResponse`，分页 `dto/PageDTO`，全局异常在 `exception/GlobalExceptionHandler`（业务异常 `BusinessException`/`ResourceNotFoundException`/`OpenApiException` 等）。
- **新增 update 端点一律用专用 `*UpdateRequest` DTO 收请求体，字段不写初始化器**：直接用实体收
  局部更新请求体是本仓库踩过的一类真实缺陷——实体字段的初始化器（含 `@Builder.Default`）在
  Jackson 走无参构造反序列化时照样生效，请求体没给的字段会拿到类默认值而非 `null`，
  `if (x != null)` 恒真，局部 PUT 时把库里的值静默覆盖回默认值（真实案例：点一下工具启用/停用
  开关，`httpMethod`/`timeoutMs` 被打回默认值）。凭据类字段
  （密码/`apiKey`/`authHeader`）额外判 `!= null && !isBlank()`——前端不回显凭据、编辑弹窗把
  该字段初始化为空串（或粘贴带来的全空格）却照样提交，只判 `!= null` 挡不住。反过来，**实体
  上的初始化器不能因此被清理掉**：`create` 端点仍然直接收实体作为请求体，这些初始化器在
  create 路径上是承重的（`RequestBodyDeserializationTest` 正向要求它们存活）。
  `UpdateRequestNoInitializerTest` 反射扫描 `dto` 包下所有 `*UpdateRequest` 类，强制每个字段
  在无参构造后必须是 `null`——新增 DTO、或给现有 DTO 加一个带初始化器的新字段，都会被它拦住。

  **四道哨兵各守一段，新增 update 端点时四段都要过**（缺任一段的失效都是完全静默的）：

  | 哨兵 | 守的那一段 | 漏了会怎样 |
  |---|---|---|
  | `UpdateRequestNoInitializerTest` | DTO 字段不写初始化器 | 判空恒真，没传的字段被打回类默认值 |
  | `UpdateRequestFieldCoverageTest` | 实体字段 → 进 DTO，或进白名单并挑一个 `ExclusionCategory` + 写说明 | 实体加了字段，DTO 没跟上且没人发现 |
  | `PutEndpointBodyRegistrationTest` | PUT 端点的请求体 → 必须被上表或全量覆盖豁免表登记 | 换个不以 `UpdateRequest` 结尾的类名就绕开前两道 |
  | `UpdateRequestPropagationTest` | DTO 字段 → 控制器/服务层那行 `setX` | 字段在界面上改不动，无报错无日志 |

  ⚠️ **第三道是 2026-08-19 补的，补它的直接原因是边界外真藏了一个活缺陷**：`KnowledgeBaseRequest`
  （建库改库共用、`enabled` 带 `= true` 初始化器）长期喂着 `PUT /knowledge/{id}`，改一次知识库
  名字就把已停用的库重新启用。它躲开前两道的唯一原因就是类名不叫 `*UpdateRequest`。
  ⚠️ **第四道登记的是「更新路径」而非「控制器」**：映射代码分布在两层（`AbExperiment` 在
  `AbTestService`、`KnowledgeBase` 在 `KnowledgeServiceImpl`，其余在控制器里），写死成「调控制器」
  会让服务层那两条因 mock 而静默零覆盖。同一个 DTO 喂两个端点时（`AiTool`/`AiModelConfig` 各有
  租户侧与平台共享侧）**两条都要登记**——两份映射代码各自独立抄写、会各自漂移。

### 前端结构（frontend/src/）
- **HTTP** `api/client.ts`：单例 axios。请求拦截器从 localStorage 注入 `satoken`（key `qingyu-auth`）与 `X-App-Id`（key `qingyu-current-app`）；响应拦截器返回 `response.data`（即后端那整个 `ApiResponse`，**不是**再往里取一层 `.data`——调用方拿到的是 `{code, message, data}`），401 清登录态并跳 `/login`。各领域 service（`api/*.ts`）都走这个实例。
- ⚠️ **业务异常是「HTTP 200 + `body.code`」约定，axios 不会 reject**：响应拦截器只特殊处理 401，其余状态原样把 `{code, message, data}` resolve 给调用方——**写操作必须显式判 `if (res.code !== 200) throw new Error(res.message)`**，不判就会把后端业务错误（重复入口、资源被占用、并发上限等）静默吞掉，用户点按钮毫无反应且没有任何报错。不限于某一特性，是全局约定；参考写法见 `components/knowledge/CrawlSiteList.tsx` 的四个写操作。
- **状态** Zustand + persist：`stores/authStore.ts`（token/user，持久化）、`stores/appStore.ts`（当前应用 `currentApp`，其 `id` 即上面注入的 `X-App-Id`）。
- **路由/守卫** `App.tsx`：`<ProtectedRoute>` 校验登录；应用级配置页（知识库/Prompt/策略等）额外包 `<RequireApp>`——未选应用时显示空状态。`layouts/MainLayout.tsx` 按 `user.role`（PLATFORM_ADMIN/TENANT_ADMIN/TENANT_MEMBER）过滤侧边导航。
- **UI** shadcn 风格封装在 `components/ui/`（CVA 变体 + `lib/utils.ts` 的 `cn()`），别名 `@/` → `src/`。

## 语言与提交约定
- 全部使用简体中文：对话、代码注释、commit 信息。
- 本仓库允许 `git commit`；在默认分支上先建特性分支再改，未获授权不 `git push`。

## 文档地图
- `docs/README.md`：文档索引，区分「使用者该读的」与「开发过程存档」。
- `docs/使用手册.md`：面向租户管理员的功能操作指南（配截图）。改了界面行为要同步。
- `docs/部署文档.md`：部署、容器化、备份恢复、监控与**故障手册**——本文件里那些「不报错只静默异常」的坑，运维视角的版本在那儿。
- `SaaS平台规划.md`：Phase 1（多租户基础）/ Phase 2（对外接入：开放 API + Widget + Webhook）/ Phase 3（管理后台）/ Phase 4（计费、多渠道、高级 RAG）的详细任务与接口设计。
- `brand/README.md`：logo 与品牌色。⚠️ 图形的路径数据在仓库里存了**五份**（favicon / 管理端组件 / widget 常量 / 字标 / 基准），三个独立构建目标之间没有共享模块的通道。`frontend/scripts/brandAssetSync.test.ts` 守着五处同源，改一处漏其它即红；**改了 `widget/src/logo.ts` 还必须重新 build 并提交 `widget.js`**。
- `docs/superpowers/` 下为各子功能的 spec 与实现计划（开发过程存档，刻意不迁走：本文件有若干带章节号的指针指向具体 spec，且它是写 spec 的默认路径）。

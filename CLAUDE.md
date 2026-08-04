# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

轻语（QingYu Care）是面向企业的多租户 AI 智能客服 SaaS 平台。后端 Spring Boot 4.1 + Spring AI 2.0 + pgvector，前端 React 19 管理后台，另有一个可嵌入网页的独立 Widget 子项目。

## 常用命令

### 基础设施（必须先启动）
```bash
cd docker && docker compose up -d   # PostgreSQL(pgvector) + RustFS(S3) + Redis
```
`docker/init.sql` 仅负责 `CREATE EXTENSION vector`；业务表由后端启动时通过 JPA `ddl-auto: update` 自动建立。

### 后端（backend/，Maven）
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 启动（dev profile）
mvn clean install                                    # 全量构建 + 单测（不含 IT）
mvn test                                              # 跑单元测试（Surefire，免 Docker）
mvn test -Dtest=ConfigServiceTest                     # 单个测试类
mvn test -Dtest=WebhookSignatureTest#signs_body_with_hmac_sha256   # 单个方法
mvn verify                                            # 单测 + 集成测试（Failsafe，需 Docker Desktop）
mvn verify -Dit.test=OpenApiAuthIT                     # 单个 IT（Failsafe 用 it.test 而非 test）
```
- JDK 17。后端启动需注入环境变量 `DASHSCOPE_API_KEY`（通义千问）。可选覆盖 `AI_BASE_URL` / `AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL` 切换任意 OpenAI 兼容端点。
- ⚠️ **`AI_BASE_URL` 必须自带 `/v1`**（如 `https://dashscope.aliyuncs.com/compatible-mode/v1`）。Spring AI 2.0 起改用官方 openai-java SDK，只往 baseUrl 后拼 `chat/completions` / `embeddings`，**不再补 `/v1`**（1.x 的 `OpenAiApi` 拼的是 `/v1/xxx`，故当年要求不带，契约已翻转）。该项同时喂给 ChatModel 与 EmbeddingModel，缺 `/v1` 会让对话/摄取/检索全线 404；库里 `ai_model_config.endpoint` 同理。
- 服务上下文路径是 `/api`，Swagger 在 `http://localhost:8080/api/swagger-ui.html`。

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
卡片按钮的动作协议（`url://` 打开链接 / `msg://` 发消息 / `copy://` 复制）白名单分散在三个语言里，**没有任何自动化测试能比对它们**：

1. `ChatServiceImpl.CARD_PROMPT`（Java 字符串，决定 LLM 会生成什么）
2. `frontend/src/lib/actionUri.ts` 的 `VALID_PROTOCOLS`
3. `widget/src/actionUri.ts` 的 `VALID_PROTOCOLS`

漏改任一处的后果：LLM 产出客户端不认的协议 → 按钮退化成发一条奇怪的消息，或彻底静默。改协议后务必真机点一遍三类按钮。

⚠️ **`url://` 是 fail-closed 的**：被 `safeUrl`（http/https 白名单）拒绝时**不回退** `value`，点击无反应是**正确行为**——协议有效、只是内容被安全策略拦下，此时改去发消息属语义错乱。`executeActionUri` 的返回值表示「是否已作为动作处理」而非「是否成功」，回退只在协议本身无效（格式不匹配/不在白名单）时发生。两端各有一份实现，改一处必须同步另一处。

### 改动网站知识库同步链路后必须手工验证
自动化测试覆盖不到的三条（单测里 `WebPageFetcher` 与 `VectorStore` 全被 mock，调度线程的过滤器状态更是单测造不出来）：
1. **二次同步不重嵌**：同一网页连点两次「立即重新抓取」，确认 `chunk_count` 与 `vector_store` 行数**都没变**。退化成每次重嵌不会报错、只会悄悄烧 embedding 的钱。
2. **调度器真能查到文档**：把某文档 `last_synced_at` 改到很久以前，等 `@Scheduled` 触发（临时用 `-Dqingyu.care.web-sync.interval-ms=30000` 缩短间隔），确认日志打出「网页定时同步：N 篇到期」。原生 SQL 一旦被改回 JPQL，这里会**静默地一篇都查不到**。
3. **人工读一遍入库正文**：`SELECT content FROM document WHERE id=?`，看有没有混进导航栏/Cookie 提示/页脚版权。这是启发式剔噪声唯一的验收方式。

排查网页文档问题时，**先跟文件文档做同身份对照**再归因新代码——真机冒烟中「网页文档 `tenant_id` 落成 NULL」曾被误判为本特性缺陷，实为平台管理员建档的既有行为（文件上传路径一模一样）。

### 前端（frontend/，pnpm）
```bash
pnpm install && pnpm dev   # Vite 开发服务器（:5173，/api 代理到 :8080）
pnpm build                 # tsc -b && vite build
pnpm lint                  # ESLint（flat config: eslint.config.js）
```

### Widget（widget/，pnpm — 独立子项目）
```bash
pnpm install
pnpm dev     # 本地调试（index.html）
pnpm build   # ⚠️ 产出到 backend/src/main/resources/static/widget.js
pnpm test    # vitest
```
**关键**：`widget.js` 是**提交进仓库的构建产物**，由后端作为静态资源对外提供（`<script src=".../api/widget.js?appKey=...">`）。改动 `widget/src/` 后必须重新 build 并提交 `widget.js`；`.gitattributes` 强制其为 LF 行尾以保证跨平台 diff 稳定。

## 测试约定（重要）

分两层，由 Maven 插件按文件名/标签自动切分（见 `pom.xml`）：

- **单元测试（Surefire，`mvn test`）**：纯 Mockito（`@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`），不启 Spring 容器、**不需要 Docker**。Surefire 会排除 `*IT.java` 与 `@Tag("integration-test")`。**新增测试优先写成这类单测。**
- **集成测试（Failsafe，`mvn verify`）**：类名以 `IT` 结尾，放在 `it/` 包，继承 `support/AbstractPostgresIT`——它用 **Testcontainers 起一个 JVM 内单例 `pgvector/pgvector:pg17` 容器**（`@DynamicPropertySource` 注入数据源），`@SpringBootTest(MOCK)` + `@AutoConfigureMockMvc`。**需要 Docker Desktop 运行**；`mvn test` 不会触发它们，故无 Docker 也能跑单测。
- IT 基类提供 `seedApp(...)` 播种租户+应用。多租户过滤器只在**真实 HTTP 请求**流经 `TenantInterceptor` 时生效，因此 IT 里的种子数据要么走 MockMvc 请求，要么用不继承 `BaseTenantEntity` 的实体（如 `Application` 靠关系映射）。
- `*FallbackIT`（`ChatStrategyFallbackIT`/`PromptTemplateFallbackIT`/`ModelRouterDefaultIT`/`HandoverRuleFallbackIT`）专门守护下方「多级配置回退」这条易错链路，改动相关服务后务必 `mvn verify`。
- ⚠️ **跑 `mvn verify` 前必须先停掉本机在跑的 dev 后端**（`:8080`），否则 `WebhookDeliveryIT` 必红。IT 用 Testcontainers 起自己的 PG，但 Redis 连的是**本机 6379**，与 dev 后端共用消费组 `webhook-workers`——dev 后端的 `WebhookStreamConsumer` 会抢走 IT 投进流里的消息并 ACK，再拿 `deliveryId` 去自己的 dev 库查（查不到），IT 的投递行永远到不了 SUCCESS，最后表现为一条 10 秒 `ConditionTimeout`，**看不出与 Redis 有任何关系**。已实测确认：停掉 dev 后端后同一条 IT 即绿。

## 架构要点

### 多租户隔离（两级：tenant_id + app_id）
理解任何数据读写前必须先理解这套机制，它是全自动、隐式生效的：

1. **请求入口** `TenantInterceptor`（`config/`）：从 Sa-Token 会话取 `tenantId`，从 `X-App-Id` 请求头取 `appId`，写入 `TenantContext`（ThreadLocal）；`afterCompletion` 清理，防泄漏。
2. **查询过滤** `TenantDataAspect`（`config/`）：`@Before` 切所有 `repository..*` 调用，解包 Hibernate Session 并 `enableFilter("tenantFilter"/"appFilter")`，参数取自 `TenantContext`。平台管理员绕过过滤；非管理员且无上下文时用 `-1L`（拒绝一切）。
3. **过滤器定义** `BaseTenantEntity`（`entity/`，`@MappedSuperclass`）：用 `@FilterDef`/`@Filter` 声明 `tenant_id`/`app_id` 两个过滤条件。绝大多数业务实体继承它。
4. **写入自动填充** `TenantEntityListener`：`@PrePersist` 时从 `TenantContext` 回填 `tenantId`/`appId`。

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

### AOP 横切
- `@AuditLog(action=, targetType=)`（`annotation/`）+ `AuditLogAspect`（`config/`）：`@Around` 业务方法成功后 `AuditLogService.saveAsync()` 异步落库，含正则脱敏（apiKey/password/secret → `***`）。给写操作打审计日志时加此注解即可。
- 限流：`RateLimitConfig` + `RateLimitService`（Bucket4j + Lettuce Redis），`tryConsume(appKey, limitPerMinute)`，Redis 异常时 **fail-open**（放行）。

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

### Webhook 事件推送（`event/` + `service/webhook/`）
链路：业务发布 `WebhookDomainEvent` → `WebhookDispatcher`（`@TransactionalEventListener(AFTER_COMMIT)`，按 app 订阅事件过滤）落库 `WebhookDelivery(PENDING)` → `WebhookQueue.dispatch()`（**Redis Stream 队列**：XADD 入流，`WebhookStreamConsumer` 消费组拉取；队列关闭或 Redis 故障进入冷却期时回退 `WebhookSender.@Async deliverAsync()` 直投，fail-open）→ HTTP 投递，`WebhookSignature` 做 HMAC-SHA256 签名（头 `X-Qingyu-Signature: sha256=...`）→ 失败按 `backoff-minutes` 退避重试，`WebhookRetryScheduler`（`@Scheduled`）扫描到期的重投（同样经队列派发）。**语义边界：DB 投递行（PENDING + claim CAS）才是事实源与至少一次保证，队列只做加速分发——消费侧先 ACK 再投递，消息丢失/重复均无害。** 配置见 `WebhookProperties`（前缀 `qingyu.care.webhook`，队列子配置 `qingyu.care.webhook.queue.*`）。`WebhookQueueIT`（真 Redis 容器，独立 Spring 上下文）与 `WebhookDeliveryIT`（无 Redis，验证直投兜底）分别守护两条路径。

### 服务层与 DTO
- **只有复杂服务（`ChatService`、`KnowledgeService`）才用 `service/` 接口 + `service/impl/` 实现拆分**；其余服务（`ConfigService`/`ModelRouterService`/`ChatStrategyService` 等）是 `service/` 下的单个 `@Service` 具体类，不必强行加接口。
- 定时任务：`scheduler/SessionTimeoutScheduler`（`@Scheduled` 扫描超时会话）；Webhook 重投的 `WebhookRetryScheduler` 在 `service/webhook/`。
- **未使用 MapStruct**（pom 里虽引入，但实际用静态工厂如 `OpenChatResponse.from(...)` 手动转换 DTO）。
- 统一响应包 `dto/ApiResponse`，分页 `dto/PageDTO`，全局异常在 `exception/GlobalExceptionHandler`（业务异常 `BusinessException`/`ResourceNotFoundException`/`OpenApiException` 等）。

### 前端结构（frontend/src/）
- **HTTP** `api/client.ts`：单例 axios。请求拦截器从 localStorage 注入 `satoken`（key `qingyu-auth`）与 `X-App-Id`（key `qingyu-current-app`）；响应拦截器拆 `ApiResponse.data`，401 清登录态并跳 `/login`。各领域 service（`api/*.ts`）都走这个实例。
- **状态** Zustand + persist：`stores/authStore.ts`（token/user，持久化）、`stores/appStore.ts`（当前应用 `currentApp`，其 `id` 即上面注入的 `X-App-Id`）。
- **路由/守卫** `App.tsx`：`<ProtectedRoute>` 校验登录；应用级配置页（知识库/Prompt/策略等）额外包 `<RequireApp>`——未选应用时显示空状态。`layouts/MainLayout.tsx` 按 `user.role`（PLATFORM_ADMIN/TENANT_ADMIN/TENANT_MEMBER）过滤侧边导航。
- **UI** shadcn 风格封装在 `components/ui/`（CVA 变体 + `lib/utils.ts` 的 `cn()`），别名 `@/` → `src/`。

## 语言与提交约定
- 全部使用简体中文：对话、代码注释、commit 信息。
- 本仓库允许 `git commit`；在默认分支上先建特性分支再改，未获授权不 `git push`。

## 路线图参考
- `SaaS平台规划.md`：Phase 1（多租户基础）/ Phase 2（对外接入：开放 API + Widget + Webhook）/ Phase 3（管理后台）/ Phase 4（计费、多渠道、高级 RAG）的详细任务与接口设计。
- `docs/superpowers/` 下为各子功能的 spec 与实现计划。

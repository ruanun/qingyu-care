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
6. **流式 + 计量** `streamChat(request, IntConsumer onTokensUsed)` 返回 `Flux<String>`，`meterContentStream()` 累加 token 后在 `doFinally` 回调 `onTokensUsed`。
> 关键：**token 计量是「开放 API 专属」**。`OpenChatController`（`/open/v1/chat/*`）才传入 `onTokensUsed -> apiUsageService.record(...)` 落 `SysApiUsage`；管理后台的 `ChatController` 不计量。新增计费/用量入口时沿用此回调注入方式，别把计量塞进 `ChatServiceImpl` 内部。

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

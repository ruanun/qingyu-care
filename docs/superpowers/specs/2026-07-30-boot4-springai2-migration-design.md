# Spring Boot 3.5.16 → 4.1.0 + Spring AI 1.1.8 → 2.0.0 迁移设计

- 日期：2026-07-30
- 分支：`chore/boot4-springai2`
- 作者：ruan
- 来源：技术债 B4（依赖大版本升级）最后一项。同批的 Tailwind CSS v3→v4 已于 2026-07-30 单独合入 master（`9c8379e`），本次只动后端。

## 1. 背景与目标

后端当前钉在 Spring Boot `3.5.16` + Spring AI `1.1.8`，JDK 17。目标是升到 Spring Boot `4.1.0` + Spring AI `2.0.0`，**JDK 保持 17 不动**，功能行为零回归（390 单测 + 97 IT 全绿 + 真机冒烟通过）。

不在本次范围：JDK 17→21（Boot 4.1 官方最低就是 17，本仓不用唯一要求 21 的 jOOQ；升 JDK 会给本就很大的迁移再加一个变量，留作独立立项）；前端与 widget（不受影响）。

## 2. 调研结论（均已直连 Maven Central / 官方文档核实）

### 2.1 顺序：必须一起，不存在可用中间态

| 证据 | 结论 |
| --- | --- |
| `spring-ai-starter-model-openai:1.1.8` 硬依赖 `spring-boot-starter:3.5.15` | Spring AI 1.1.x 绑死 Boot 3.5 |
| `spring-ai-starter-model-openai:2.0.0` 硬依赖 `spring-boot-starter-restclient/webclient:4.1.0` | Spring AI 2.0 绑死 Boot 4.x |
| 官方文档：*Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x* | 两条线无重叠区间 |
| BOM 版本序列 `1.1.8` → `2.0.0-M1`，中间无 1.2.x | 无桥接版本 |

即「Boot 4.1 + AI 1.1.8」这个中间态不可用，**两个大版本不能拆成两个各自能 `mvn verify` 绿的分支**。已与用户确认：单分支一把梭。

### 2.2 目标版本带来的传递升级

Spring Boot `4.1.0` GA 管理：Spring Framework `7.0.8`、Jakarta EE 11（Servlet 6.1 基线）、Hibernate `7.4.1.Final`、**Jackson `3.1.4`**、**Testcontainers `2.0.5`**、**Lettuce `7.5.2.RELEASE`**、Tomcat `11.0.22`、JUnit Jupiter `6.0.3`、Mockito `5.23.0`、Spring Data `2026.0.0`。

Spring Boot 4.1 最低 Java 版本为 **17**（官方原文 "Spring Boot 4.0 requires Java 17 or later"）。

### 2.3 第三方兼容性：四个疑似阻塞点全部有解

| 依赖 | 现状 → 目标 | 结论 |
| --- | --- | --- |
| Sa-Token | `sa-token-spring-boot3-starter:1.45.0` → **`sa-token-spring-boot4-starter:1.45.0`** | 已于 2026-03-08 发布。两个 starter 的 jar 内都只有一个 `Placeholder.class`，纯 POM 差异（`sa-token-jackson` → `sa-token-jackson3`），底层共用 `sa-token-spring-boot-webmvc-v3v4-common` → **业务代码零改动**。 |
| springdoc-openapi | `2.8.6` → **`3.0.3`** | 3.0.x 的 parent 是 `spring-boot-starter-parent:4.0.5`，专为 Boot 4 发布。 |
| Testcontainers | `1.21.3` → **`2.0.5`** | 坐标加前缀（`postgresql` → `testcontainers-postgresql`、`junit-jupiter` → `testcontainers-junit-jupiter`）；容器类下沉到模块专属包（新增 `org.testcontainers.postgresql.PostgreSQLContainer`，旧包 `org.testcontainers.containers.PostgreSQLContainer` 仍保留）；JUnit 4 支持移除（本仓全 JUnit 5，无影响）。 |
| x-file-storage | `2.3.0` 保持 | jar 内**没有** `META-INF/spring/…AutoConfiguration.imports`，靠 `@EnableFileStorage` 的 `@Import` 手动装配 → 对 Boot 4 的自动配置模块重构免疫。风险低。 |

其余依赖（AWS SDK v2 `2.46.7`、`httpclient5`、postgresql 驱动、Lombok、reactor-test、awaitility）无兼容性问题，随 BOM 走。本仓用到的 9 个 Boot starter（web / data-jpa / validation / websocket / actuator / data-redis / test / testcontainers / devtools）在 `4.1.0` 全部存在。

## 3. 代码级迁移点

### 3.1 最大断点：`OpenAiApi` 在 Spring AI 2.0 被删除

`spring-ai-openai` 2.0 整体重写为官方 `com.openai:openai-java-core:4.39.1`（并传递引入 `com.squareup.okhttp3:okhttp:4.12.0`）。`org.springframework.ai.openai.api.OpenAiApi` 已不存在，`ModelRouterService.createOpenAiCompatibleClient()` 必须重写。

已 `javap` 核实的 2.0 API 形态：

- `OpenAiChatModel.Builder`：`.openAiApi(OpenAiApi)` → `.openAiClient(com.openai.client.OpenAIClient)`；`.defaultOptions(...)` → `.options(...)`
- `OpenAiChatOptions.Builder` **自带** `.baseUrl(String)` / `.apiKey(String)` / `.timeout(Duration)` / `.maxRetries(Integer)` / `.proxy(Proxy)` —— 我们按租户动态换端点的场景可直接由 options 承载，比 1.x 的「先造 OpenAiApi 再造 Model」更简洁
- **`.streamUsage(boolean)` 保留** → 流式 token 计量链路（`ChatServiceImpl.meterContentStream()` 靠末尾 chunk 的 `total_tokens`）不破
- `N()` 重命名为 `n()`；`ChatOptions.copy()` 移除，改 `mutate()`；`chatModel.getDefaultOptions()` 废弃，改 `getOptions()`（本仓均未使用）

其余 Spring AI 类已逐个核实在 2.0 存在且包路径不变：`ChatClient`、`AssistantMessage`/`SystemMessage`/`UserMessage`/`Message`、`ChatResponseMetadata`、`DefaultUsage`、`ChatResponse`、`Generation`、`ToolContext`、`Document`、`ToolCallback`、`ToolDefinition`/`DefaultToolDefinition`、`ToolMetadata`、`VectorStore`、`SearchRequest`、`PgVectorStore`、`TikaDocumentReader`、`TokenTextSplitter`。`PgVectorStoreProperties` 属性面（dimensions / index-type / distance-type / table-name / schema-name / max-document-batch-size）不变。

### 3.2 Jackson 2 → 3（全量迁移，已与用户确认）

实际扫描面（`backend/src`，64 处 import / 52 文件）：

| import | 处数 | 处理 |
| --- | --- | --- |
| `com.fasterxml.jackson.databind.ObjectMapper` | 45 | → `tools.jackson.databind.ObjectMapper` |
| `com.fasterxml.jackson.databind.JsonNode` | 4 | → `tools.jackson.databind.JsonNode` |
| `com.fasterxml.jackson.core.type.TypeReference` | 3 | → `tools.jackson.core.type.TypeReference` |
| `com.fasterxml.jackson.core.JsonProcessingException` | 3 | Jackson 3 异常改为 **unchecked** → 去 `throws`，`catch` 改 `tools.jackson.core.JacksonException` |
| `com.fasterxml.jackson.annotation.*`（`@JsonIgnore` / `@JsonInclude` / `@JsonIgnoreProperties`） | 9 | **不动**——Jackson 3 保留 `com.fasterxml.jackson.core:jackson-annotations`，包名不变 |

不采用 `spring-boot-jackson2` 兼容层：该模块官方已标 deprecated，且会与 Spring AI 2.0 / Sa-Token boot4 的 Jackson 3 形成双栈共存，留下技术债和潜在序列化行为不一致。

### 3.3 配置属性

- `spring.ai.openai.chat.options.model` → `spring.ai.openai.chat.model`；`spring.ai.openai.chat.options.temperature` → `.chat.temperature`
- `spring.ai.openai.embedding.options.{model,dimensions}` → `spring.ai.openai.embedding.{model,dimensions}`
- 同步改 `ModelRouterService` 的 `@Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")`
- Boot 4 侧属性迁移以 `spring-boot-properties-migrator` 的实际报告为准（预期涉及 `management.tracing.*`、`spring.dao.exceptiontranslation.*` 等，本仓未必命中）

### 3.4 Testcontainers（改动面已精确核实：pom + 1 个 import）

已比对 `testcontainers-2.0.5.jar` 与 `testcontainers-postgresql-2.0.5.jar` 的实际类清单：

- `GenericContainer`、`DockerImageName`、`ImageFromDockerfile` **仍在原包** → `WebhookQueueIT`、`RealtimeRedisBridgeIT` 的 import **零改动**
- `PostgreSQLContainer` 新旧两个包**同时存在**（`org.testcontainers.postgresql.*` 为新位置，`org.testcontainers.containers.*` 保留兼容）→ 仅换 pom 坐标即可编译通过；仍主动把 `AbstractPostgresIT` 改到新包，避免依赖将来会移除的兼容位置

所以本提交的实质改动是 pom 三处坐标 + `AbstractPostgresIT` 一行 import。

### 3.5 已确认安全、无需改动的部分

- **Hibernate 7 的 `@FilterDef` / `@Filter` / `@ParamDef` / `Session#enableFilter` 无破坏性变更** —— 多租户隔离核心机制（`BaseTenantEntity` + `TenantDataAspect`）安全
- `@MockBean` / `@SpyBean` 在 Boot 4 已移除，本仓已全量使用 `@MockitoBean`
- Sa-Token 业务 API（`StpUtil` 等）不变
- `-parameters` 编译参数由 Boot parent 默认注入，无需显式声明

## 4. 分支与提交序列

单分支 `chore/boot4-springai2`。前三个提交在 Boot 3.5 下完成且**各自 `mvn verify` 全绿**——即使后面大跳出问题，也能确定问题不在这三块。

| # | 提交 | 基线 | 是否要求绿 |
| --- | --- | --- | --- |
| C1 | Testcontainers `1.21.3` → `2.0.5` | Boot 3.5 | ✅ |
| C2 | bucket4j `8.14.0` → `8.19.0` **+ 新增真 Redis 限流 IT** | Boot 3.5 | ✅ |
| C3 | `spring-boot-properties-migrator` 扫描 + 废弃 API 预清理 | Boot 3.5 | ✅ |
| C4 | Boot `3.5.16` → `4.1.0`（Sa-Token boot4 + springdoc 3.0.3 + Jackson 3 全量 + 属性迁移） | Boot 4.1 + AI 1.1.8 | ❌ **必然不绿** |
| C5 | Spring AI `1.1.8` → `2.0.0`（含 `ModelRouterService` 重写 + `spring.ai.*` 属性扁平化） | Boot 4.1 + AI 2.0 | ✅ |
| C6 | 真机冒烟修正 | — | ✅ |

C4 不绿是「一把梭」的固有代价（2.1 已论证 AI 1.1.8 与 Boot 4 无重叠区间）。仍拆成 C4 / C5 两个提交，纯粹为了评审时 diff 可读——Jackson 与 starter 的机械改动不与 Spring AI 的语义改动混在一起看。

**C2 是整条链路最关键的一步**：先在 Lettuce 6 下建立「限流真的会返回 429」的 IT 基线，C5 升到 Lettuce 7.5.2 后这个 IT 才能立刻暴露静默失效。没有它，bucket4j 的 fail-open 会让全部测试绿着骗过我们——与「转人工触发平台默认回退」（`6a62d83`）、「RealtimeRedisBridge 可选依赖击穿 fail-open」是同一类坑：**降级路径一旦被静默触发，测试全绿而功能已死**。

## 5. 风险登记与退路

| 级别 | 风险 | 缓解 / 退路 |
| --- | --- | --- |
| **P0** | **bucket4j × Lettuce 7 静默失效**：`bucket4j_jdk17-lettuce` 把 `lettuce-core` 声明为 `provided 6.1.8`，运行期将撞 Boot 4.1 的 `7.5.2`；官方未声明 Lettuce 7 支持。而限流是 fail-open、当前只有 Mockito 单测（`RateLimitServiceTest`）、**没有任何真 Redis IT** | C2 先建 IT 基线。若 C5 后变红：① 查更高版 bucket4j；② pin `lettuce-core` 6.x（需验证与 spring-data-redis `2026.0.0` 是否冲突）；③ 改用 spring-data-redis 原生 Lua 令牌桶自实现 |
| **P0** | **`base-url` 语义变化**：现值 `https://dashscope.aliyuncs.com/compatible-mode`（无 `/v1`），Spring AI 1.x 自行补 `/v1`；换官方 SDK 后可能要求完整 base | C6 真机冒烟必测；必要时 `application.yml` 与 `ModelRouterService.getDefaultEndpoint()` 统一补 `/v1` |
| **P1** | **Jackson 3 下按构造参数名反序列化是否仍成立**：SP3 的 `OpenSessionResponse.Msg.uiParts` 历史卡片依赖 `-parameters` + parameter-names 模块按 `UiPart` 的 `@AllArgsConstructor` 参数名绑定 | `OpenSessionResponseTest` 已覆盖；C4 单独确认 Jackson 3 是否默认内置 parameter-names 支持（Jackson 3 已将其并入 databind），不成立则显式注册 |
| **P1** | x-file-storage-core 带 `tika-core:2.4.1`（compile scope）与 Spring AI 的 Tika 3.x 存在既有冲突面 | C5 后 `mvn dependency:tree` 复查实际收敛版本，必要时加 exclusion（现状在 Boot 3.5 下可用，非新增风险） |
| **P2** | Hibernate 7「具有隐式 SELECT 但无明确结果类型的查询不再被接受」影响 native query | 配置回退类的 native 查询（`findPlatformDefaultBy*`、`findAllSharedModels` 等）是重灾区，靠 `*FallbackIT` + `ModelRouterDefaultIT` 覆盖 |
| **P2** | springdoc `3.0.3` 基于 Boot `4.0.5` 构建，实际跑在 `4.1.0` | Swagger UI 冒烟（`/api/swagger-ui.html`） |
| **P2** | 新增 OkHttp `4.12.0` 传递依赖 | 不影响工具调用的 SSRF 钉 IP（那条走 `httpclient5` 自定义 `DnsResolver`）；仅确认无版本冲突 |
| **P2** | Hibernate 7 `char`/`Character` 由 `char(1)` 改映射 `varchar(1)`，叠加 `ddl-auto: update` | 本仓实体未使用 `char`/`Character` 字段，确认后放行 |

## 6. 测试与验证策略

- 每个提交：`mvn test`（Surefire，390 单测，免 Docker）
- C1 / C2 / C3 / C5 / C6：额外 `mvn verify`（Failsafe，97 IT，需 Docker Desktop）
- 新增测试沿用本仓约定：单测纯 Mockito（`@ExtendWith(MockitoExtension.class)`）；IT 类名以 `IT` 结尾、放 `it/` 包。C2 的限流 IT 需真 Redis 容器，参照 `WebhookQueueIT` 的独立 Spring 上下文写法，断言「第 N+1 次请求返回 429 + `Retry-After`」而非仅「不抛异常」
- C6 真机冒烟清单：登录 → 建应用 → 上传文档摄取 → RAG 对话（非流式 + 流式）→ token 计量落 `SysApiUsage` → 开放 API 限流打到 429 → Webhook 投递 → 坐席 SSE → Swagger UI

## 7. 交付约定

- 简体中文（对话 / 注释 / commit）
- 本仓允许 `git commit`；已从 master 建特性分支 `chore/boot4-springai2`；**未获授权不 `git push`**
- 改动留工作树，逐步与用户确认；合入 master 在本地进行

## 8. 迁移结论（实施后回填）

- **实际落地版本**：Spring Boot 4.1.0 / Spring AI 2.0.0 / JDK 17（Testcontainers 2.0.5、bucket4j 8.19.0、Sa-Token 1.45.0、springdoc 3.0.3）
- **P0「bucket4j × Lettuce 7」**：**兼容，未启用任何退路**。`lettuce-core` 实际收敛到 `7.5.2.RELEASE`，`bucket4j_jdk17-lettuce:8.19.0` 直接可用。C2 预埋的真 Redis `RateLimitRedisIT` 升级后仍绿；真机复验「配额 3/分钟连打 6 次 → 前 3 次 200、后 3 次 429 + `Retry-After: 60`」，确认限流未静默退化成 fail-open。
- **P0「base-url 语义」**：**需要补 `/v1`，且不是「语义微调」而是契约完全翻转**，详见下方专节。
- **P1「Jackson 3 构造参数名绑定」**：**成立，无需显式注册**（Jackson 3 已把 parameter-names 支持并入 databind），`UiPart` 的隐式创建器照常工作。但同一机制反向暴露了一个设计稿未预见的 Critical，详见下方专节。
- **P1「tika-core 版本收敛」**：收敛到 `3.3.1`（Spring AI 侧胜出），**未加 exclusion**，与 Boot 3.5 时期一致。
- **P2「Hibernate 7 隐式 SELECT」**：**未命中**。配置回退类的 native 查询（`findPlatformDefaultBy*`、`findAllSharedModels` 等）零改动，`*FallbackIT` / `ModelRouterDefaultIT` 全绿。`repository/` 包在整条迁移里只改了 `KeywordSearchDao` 的 Jackson 包名（`com.fasterxml.jackson` → `tools.jackson`）。
- **P2「springdoc 3.0.3 跑在 Boot 4.1」**：可用。`/api/swagger-ui.html` 与 `/api/swagger-ui/index.html` 均 200，`/api/v3/api-docs` 正常输出 `openapi: 3.1.0`。
- **P2「okhttp 4.12.0 传递依赖」**：确认引入 `4.12.0`，无版本冲突；工具调用 SSRF 钉 IP 仍走 `httpclient5` 自定义 `DnsResolver`，不受影响。
- **P2「Hibernate 7 `char` 映射变化」**：本仓实体未使用 `char`/`Character` 字段，未命中。
- **最终测试规模**：**398 单测 + 99 IT 全绿**（`mvn clean verify` BUILD SUCCESS）。单测由 390 增至 398，新增的 8 例即下方 Jackson 缺陷的回归网。
- **真机冒烟通过项**：管理端非流式对话（真实 DashScope）、文档上传 → 摄取 → 向量落库 → RAG 检索命中、开放 API 流式 SSE + token 计量落 `sys_api_usage`（`token_count=1139`）、开放 API 限流 429 + `Retry-After`、Webhook 投递 `SUCCESS` + HMAC 签名头、坐席 SSE 实时收 `event:message`、Swagger UI、文件上传（RustFS）、前端 `pnpm build`。

### 8.1 P0「baseUrl 契约翻转」——本次迁移最危险的一处

设计稿原文只写「换官方 SDK 后**可能**要求完整 base」。实际情况比这严重：**契约方向完全反了过来**。

| | Spring AI 1.1.8 | Spring AI 2.0.0 |
| --- | --- | --- |
| 默认 baseUrl 常量 | `https://api.openai.com`（**不带** `/v1`） | `https://api.openai.com/v1`（**自带** `/v1`） |
| 库往 baseUrl 后拼的路径 | `/v1/chat/completions`、`/v1/embeddings` | `chat/completions`、`embeddings`（**不含 `v1`**） |
| 因此 `base-url` 配置要求 | **必须不带** `/v1` | **必须带** `/v1` |

即：1.x 时代写全 `/v1` 会打成 `/v1/v1/...`，2.0 时代不写 `/v1` 会打成 `/chat/completions`——两边都是 404，**且方向相反**，照抄旧配置必然全线失效。`OpenAiSetup.calculateBaseUrl()` 对 OPEN_AI provider 原样返回、不做补全。

**三处修法**（提交 `0df486b`）：

1. `application.yml` 的 `spring.ai.openai.base-url` 默认值 → `https://dashscope.aliyuncs.com/compatible-mode/v1`（该项同时喂给自动装配的 ChatModel 与 EmbeddingModel）
2. `ModelRouterService.defaultBaseUrl` 的 `@Value` 兜底值 → `https://api.openai.com/v1`（它会被 `init()` 播种成平台默认模型行的 endpoint）
3. `ModelRouterService.getDefaultEndpoint()` 四个 provider 分支统一自带 `/v1`（`openai` 分支原为 `https://api.openai.com`）

刻意**不做**「缺 `/v1` 就自动补全」的归一化：404 是可见错误，自动补全只会把配置错误掩盖成难查的行为漂移。

**这个修复测试证明不了**——`/v1` 缺失前后 398 单测 + 99 IT 都是绿的，因为**整套 IT 没有一条发真实模型请求**（`VectorStore` 与 `ChatClient` 在测试里都被 mock）。最终靠真机冒烟的两路证据闭合：

- **直接证据**：把 `AI_BASE_URL` 与平台模型行的 endpoint 临时指向本机 HTTP 监听器，抓到的原始请求行为
  `POST /v1/chat/completions HTTP/1.1` 与 `POST /v1/embeddings HTTP/1.1`——baseUrl 给的是 `http://127.0.0.1:9999/v1`，说明 SDK 只补了 `chat/completions` / `embeddings`。同一监听器上用迁移前提交（Spring AI 1.1.8）以不带 `/v1` 的 baseUrl 复打，抓到的同样是 `POST /v1/chat/completions`——两版落到同一路径，正是契约翻转的直接印证。
- **端到端证据**：真实 DashScope key 打通对话与摄取，返回正常内容而非 404。

**留给后人的规则**：`AI_BASE_URL` / `ai_model_config.endpoint` **必须包含 `/v1`**（已同步写进 `CLAUDE.md` 与 `application.yml` 注释）。

### 8.2 Jackson 3 隐式创建器缺陷（设计稿完全未预见）

**缺陷本质**：Jackson 3 会把 Lombok `@AllArgsConstructor` 当成**隐式属性创建器**（靠 `-parameters` 取参数名），**即使类同时有无参构造**，优先级高于「无参构造 + setter」。于是请求体里缺省的属性被当作 `null` 传进构造器：

1. 原始类型直接抛 `Cannot map null into type boolean`（Jackson 3 默认开启 `FAIL_ON_NULL_FOR_PRIMITIVES`）→ HTTP 400；
2. 字段初值 / `@Builder.Default` 被 `null` 静默覆盖。

设计稿的 P1 只想到「参数名绑定**还能不能用**」（怕它失效），完全没想到反方向——**它太能用了，把本不该当创建器的构造器也认了**。

**影响面**：8 个「同时有 `@NoArgsConstructor` 与 `@AllArgsConstructor`」且用作 `@RequestBody` 的类。其中 **`ChatRequest` 是致命的**：它的 `openChannel` 带 `@JsonIgnore`、**永远无法从请求体提供**，所以管理端 `/chat` 与 `/chat/stream` 对**任何**请求体都必然 400——整条管理后台对话链路不可用。其余（`Application` / `ChatStrategy` / `AiModelConfig` / `AiTool` / `AiSkill` / `AbExperiment` / `Tenant`）表现为丢默认值：`enabled`、`topK=5`、`similarityThreshold=0.7`、`scenario=default`、`httpMethod=GET`、`TenantStatus.ACTIVE` 等，轻则破坏 RAG 检索参数，重则 NOT NULL 列落库 500。

**修法**（提交 `bf675cb`）：给这 8 个类的无参构造显式标 `@JsonCreator`（`@NoArgsConstructor(onConstructor_ = @JsonCreator)`），恢复 Jackson 2 的「无参构造 + setter」填充语义。**不采用**「全局关闭隐式创建器」——`UiPart` 这类只有 `@AllArgsConstructor` 的类合法依赖隐式创建器（正是上面 P1 关心的那条路径），全局关会推坏结构化卡片反序列化；新增的 `RequestBodyDeserializationTest` 里专门留了一条反向守护用例盯住它。

另两个同模式的类经核验非缺陷、未改动：`AgentReplyRequest`（两字段皆 `@NotBlank` String，缺省被校验拦成 400，行为不变）、`KnowledgeBaseRequest`（`enabled` 初值虽被覆盖，但 `KnowledgeServiceImpl` 建/改两处均已有 null 兜底）。

**最该记住的事实**：**390 单测 + 99 IT 全数漏掉了它**，是真机冒烟准备阶段才发现的。原因是 IT 构造请求体时习惯把字段填满，恰好绕开了「缺省属性」这一触发条件。这与 `6a62d83`（转人工平台默认回退静默失效）、`RealtimeRedisBridge` 可选依赖击穿 fail-open 是同一类教训：**测试全绿不等于功能可用，凡是「默认值 / 降级路径」都要有真机或缺省态用例**。

### 8.3 冒烟顺带发现的两处存量缺陷（与本次迁移无关）

真机冒烟上传文档时整条摄取链路不可用，定位到两个互相掩盖的缺陷。用**迁移前提交（`5e6c698^`，Boot 3.5.16 + Spring AI 1.1.8）跑同样流程**做对照，两者表现完全一致，确认**与本次迁移无关**，已单独提交（`d3ea4ef`）：

1. **上传必 500**：`FileStorageConfig` 的自定义 amazon-s3-v2 工厂返回裸 `S3Client`，而 `AmazonS3V2FileStorage.getClient()` 对工厂产物做的是无条件 `checkcast` 到 `AmazonS3V2Client`（字节码已核）。引入于 `42d3c34`（AWS SDK v1→v2），即 **2026-07-22 起文件上传一直是坏的**。
2. **摄取静默失败**：`processDocumentAsync` 直调 `processDocument` 属同类自调用、不经代理，`@Transactional` 失效；而 `processDocument` 首行 `deleteByDocumentId` 是 `@Modifying` 原生删除，无环绕事务直接抛 `TransactionRequiredException`，异常烂在 `@Async` 线程里、文档永远停在 PENDING。被缺陷 1 掩盖（上传早就 500，摄取压根跑不到）。

两者均逃过 398 单测 + 99 IT——**摄取全链路（S3 + Embedding）此前明确不做 IT 覆盖**，这正是该决定的代价：一条完全没有自动化守护的链路，坏了 9 天没人知道。

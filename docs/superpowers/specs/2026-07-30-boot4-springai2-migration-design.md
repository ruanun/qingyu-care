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

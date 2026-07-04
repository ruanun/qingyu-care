---
title: 质量加固 · 集成回归网 + 种子归属审计 设计
author: ruan
date: 2026-07-04
status: 已实现
---

# 质量加固 · 集成回归网 + 种子归属审计 设计

## 1. 背景与范围

2026-07-03/04 首次对 Phase 2「对外接入」做真实 DB + 浏览器端到端联调，接连暴露并修复 **6 个阻断级 bug**：

1. 登录被多租户切面 `tenant_id=-1` 过滤挡死（全员无法登录）；
2. Webhook 在 `@TransactionalEventListener(AFTER_COMMIT)` 内落库未提交（投递永不发生）；
3. `widget.js` 被 Sa-Token 拦截器要求登录（外部 `<script>` 401）；
4. `widget.js` 资源处理器把单文件当目录（500 + Content-Type 退化 octet-stream）；
5. 开放 API 对 CORS 预检 OPTIONS 也鉴权（预检 401，浏览器跨域全被拦）；
6. 开放 API 用量计量 `@Modifying` 更新缺事务，抛异常被吞、计量静默丢失。

**共性**：全部是「多租户过滤 / 事务提交 / CORS / MVC 配置」这类**集成层**问题，**没有一个能被纯 Mockito 单测捕获**——现有 129 个主类仅 14 个测试、几乎全是 Mockito 单测，唯一的 `@SpringBootTest` 依赖本机真实 PG 跑不了，无 Testcontainers。测试策略存在结构性盲区。

**本设计目标**：建一张**集成回归网**，把上述 6 类问题固化为自动化测试；并顺带**审计**同类隐患——「`@PostConstruct` 建默认种子 = `tenant_id=NULL` 被租户过滤挡住」这一模式在 5 处初始化点出现（Auth/ChatStrategy/HandoverRule/ModelRouter/PromptTemplate），已在登录、Prompt 两处踩雷，另 3 处需逐一确认并修复。

**决策已定**（brainstorming 2026-07-04）：
- **范围**：回归网 + 种子归属审计修复（非「只建基座」，也非「拓宽全控制器」）。
- **构建接入**：Failsafe + `mvn verify`。集成测试命名 `*IT.java` 绑 `verify` 阶段；`mvn test` 保持纯 Mockito 单测、免 Docker、快。
- **不打真实 LLM**：本次 6 个 bug 均在基础设施层，与模型生成无关。集成测试在「基础设施接缝」处验证，`ModelRouterService`（Spring bean）在需要整条 chat 的用例用 `@MockBean` 返回固定回复。

## 2. 目标与非目标

**目标**
- 引入 Testcontainers（`pgvector/pgvector:pg17`），提供可复用的集成测试基座。
- 为 6 个已修 bug 各写一条集成回归用例，防复发。
- 审计另 3 处 `@PostConstruct` 种子归属，硬失败者套用「平台级回退」修复，已有兜底者补 IT 固化。
- `mvn test` 行为不变（纯单测、免 Docker）；`mvn verify` 额外跑集成测试。

**非目标（YAGNI）**
- 不加 CI 工作流（GitHub Actions 等）。
- 不拓宽到全部控制器/业务流的集成覆盖。
- 不做流式 `/stream` 计量、`token_count` 补全、Widget 流式。
- 不把 5 处 `@PostConstruct` 重构为统一的「租户优先 + 平台回退」配置解析层（记为后续）。

## 3. 设计

### 3.1 Testcontainers 基座

**依赖（test scope）**：
- `org.testcontainers:testcontainers-bom`（dependencyManagement 统一版本）
- `org.testcontainers:postgresql`、`org.testcontainers:junit-jupiter`
- `org.awaitility:awaitility`（等异步 Webhook 投递）

**构建插件**：`maven-failsafe-plugin`，`integration-test` + `verify` 目标，默认拾取 `*IT.java`。`maven-surefire-plugin` 保持默认（`*Test.java`）。

**基类 `AbstractPostgresIT`**（`src/test/java/com/qingyu/care/support/`）：
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureMockMvc`；
- 静态 `PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))`，容器级复用（`static` + 单例，跨用例共享，加速）；
- 通过 Testcontainers `withInitScript(...)` 执行 `CREATE EXTENSION IF NOT EXISTS vector`（init 脚本置于测试资源，内容与 `docker/init.sql` 一致）；
- `@DynamicPropertySource` 注入 `spring.datasource.url/username/password`，`ddl-auto=create-drop`，`spring.ai.vectorstore.pgvector.dimensions=1024`；
- `@MockBean ModelRouterService`（默认返回固定 `ChatResponse`，避免真实模型调用；仅需要整条 chat 的用例用到）。
- 提供播种辅助（建租户/应用/激活模板等），供子类复用，避免各用例重复样板。

**驱动方式**：优先 `MockMvc`（经真实的 Sa-Token 拦截器、开放 API 拦截器、CORS、资源处理器链），只有「直接验证 service 语义」的用例（计量、Prompt 回退）才注入 service 调用。

**Profile**：复用现有 `test` profile（`ddl-auto: create-drop`、`ai.openai.api-key: test-key`）；其中写死的 `localhost:5432` 数据源由 `@DynamicPropertySource` 用容器地址覆盖，故不再依赖本机 PG。

### 3.2 回归用例（每条对应一个已修 bug）

| 用例 | 覆盖 bug | 断言要点 |
|---|---|---|
| `LoginIT` | #1 登录被租户过滤 | `POST /v1/auth/login` admin/admin123 → 200 + token；未登录访问受保护接口 → 401 |
| `OpenApiAuthIT` | 开放 API 鉴权 | `widget/config`：白名单 Origin 200 / 非白名单 403 / 未知 appKey 401；`chat/completions` 无凭据 401、错 secret 401 |
| `CorsPreflightIT` | #5 CORS 预检 | `OPTIONS /open/v1/widget/config`（带 `Origin` + `Access-Control-Request-*`）→ 2xx |
| `WidgetJsIT` | #3/#4 静态脚本 | `GET /api/widget.js` → 200 + `Content-Type: text/javascript` + 非空 JS |
| `WebhookDeliveryIT` | #2 AFTER_COMMIT 落库 | 配好 webhookUrl/secret/events 的应用，触发 `POST /open/v1/sessions/{id}/handover` → Awaitility 等到：`sys_webhook_delivery` 有 SUCCESS 行、内嵌 `HttpServer` 收到请求、`X-Qingyu-Signature` HMAC 校验通过 |
| `ApiUsageMeteringIT` | #6 计量缺事务 | 调 `ApiUsageService.record(tenantId, appId, 1, tokens)` → `sys_api_usage` 出现并累加对应行 |
| `PromptTemplateFallbackIT` | Prompt 回退 | 仅存在平台默认模板（`tenant_id=NULL`）时，设租户上下文调 `getActiveTemplate()` → 返回平台默认而非抛 404 |

**Webhook 接收桩**：用 JDK 内置 `com.sun.net.httpserver.HttpServer` 起本地端点收投递（无需 WireMock 依赖），记录 header+body 供验签断言。

**异步等待**：Webhook 投递为 `@Async`，用 Awaitility 轮询（`sys_webhook_delivery` 状态或接收桩），避免固定 sleep 的脆弱。

### 3.3 种子归属审计（另 3 处 `@PostConstruct`）

对 `ChatStrategyService` / `HandoverRuleService` / `ModelRouterService` 的默认种子（`@PostConstruct` 建、`tenant_id=NULL`）逐一核实：**在租户上下文下，其查询是被租户过滤挡掉（潜在硬失败）还是已有跨租户/兜底机制**。

- **硬失败者**（如 Prompt 那样直接抛/取不到）→ 套用同款修复：加原生查询（绕过 Hibernate 过滤）取平台级默认，并在解析处 `.or(...)` 回退。
- **已有兜底者**（联调中 app=2 对话跑通，说明策略/模型至少有可用路径）→ 不改逻辑，补一条 IT 固化「租户无自有配置时仍能拿到平台默认」的行为，防未来回归。

产出：审计结论（每处一句：有无 bug、如何处理）+ 相应修复/IT。

**审计结论（2026-07-04）：**

- **ChatStrategyService — 发现隐性 bug 并修复**：`@PostConstruct` 默认策略 `tenant_id=NULL` 被租户过滤挡掉，无自有策略的租户实际拿到运行时硬编码兜底（`allowNoKnowledge=true`）而非配置的平台默认策略（如严格的 complaint 策略）。解析方法 `getActiveStrategy(scenario)`。修复：`ChatStrategyRepository` 加原生查询 `findPlatformDefaultByScenario`，`getActiveStrategy` 在租户场景查询后 `.or(平台场景回退)`（保留原有运行时兜底，strategy 语义允许安全默认）。

- **HandoverRuleService — 无 bug（优雅降级）**：规则查询空列表即"不触发转人工"，各判定方法均 `.orElse(false)`/`Integer.MAX_VALUE` 兜底，租户无自有规则时安全退化，不报错。仅加 IT 固化。

- **ModelRouterService — 无 bug（设计已正确）**：`AiModelConfigRepository.findSharedModelsByScenario` 本就是原生查询，`scope=PLATFORM` 默认模型不受 Hibernate 过滤影响，任何租户上下文均可见。仅加 IT 固化。

- **关键测试经验**：回退类 IT 必须**经真实请求（MockMvc 打开放 API 端点）**驱动，才能让 `TenantDataAspect` 的 Hibernate 过滤器在查询所用的请求作用域会话上真正生效；直接在测试线程 `set TenantContext` 再调 service 不会触发过滤器，会得到"假绿"。故 PromptTemplate/ChatStrategy 回退 IT 均改为经 `/open/v1/chat/completions`（`@MockitoBean ModelRouterService` 免 LLM）驱动。ModelRouterDefaultIT 因不使用 `@MockitoBean` 位于独立 Spring 上下文，与使用 `@MockitoBean` 的 IT 共用同一 Testcontainer DB，需在 `@BeforeEach` 自播种平台模型以防 `create-drop` 跨上下文清除种子。

### 3.4 交付边界

见 §2 非目标。特别地：本次**不**新建 CI；集成测试通过 `mvn verify` 手动/后续接入。

## 4. 风险与考量

- **Testcontainers 需 Docker**：仅影响 `mvn verify`；`mvn test` 不受影响。本机 Docker 29.x 可用。
- **pgvector 镜像拉取**：首次 `mvn verify` 需拉 `pgvector/pgvector:pg17`（本机已有）；容器复用降低多用例开销。
- **`@MockBean ModelRouterService` 与真实路由差异**：集成测试不覆盖模型路由/降级本身（那是单测 `ModelRouterServiceMergeTest` 的职责），只保证 chat 端到端接线（鉴权→上下文→计量→响应）正确。
- **审计可能扩出修复量**：若 3 处种子都需回退修复，工作量略增；但均为同款小改，且正是本次要消除的隐患。

## 5. 验收标准

- `mvn test` 仍为纯单测、无需 Docker、全绿。
- `mvn verify` 额外跑通全部 `*IT`（需 Docker），覆盖 §3.2 七条用例。
- 3 处 `@PostConstruct` 种子归属审计有明确结论；硬失败者已修并有 IT。
- 若刻意还原任一已修 bug（如撤掉 CORS 预检放行），对应 IT 失败（回归网有效）。

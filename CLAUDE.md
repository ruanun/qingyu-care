# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

轻语（QingYu Care）是面向企业的多租户 AI 智能客服 SaaS 平台。后端 Spring Boot 3 + Spring AI + pgvector，前端 React 19 管理后台，另有一个可嵌入网页的独立 Widget 子项目。

## 常用命令

### 基础设施（必须先启动）
```bash
cd docker && docker compose up -d   # PostgreSQL(pgvector) + RustFS(S3) + Redis
```
`docker/init.sql` 仅负责 `CREATE EXTENSION vector`；业务表由后端启动时通过 JPA `ddl-auto: update` 自动建立。

### 后端（backend/，Maven）
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 启动（dev profile）
mvn clean install                                    # 全量构建 + 测试
mvn test                                              # 跑全部单测
mvn test -Dtest=ConfigServiceTest                     # 单个测试类
mvn test -Dtest=WebhookSignatureTest#signs_body_with_hmac_sha256   # 单个方法
```
- 后端启动需注入环境变量 `DASHSCOPE_API_KEY`（通义千问）。可选覆盖 `AI_BASE_URL` / `AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL` 切换任意 OpenAI 兼容端点。
- 服务上下文路径是 `/api`，Swagger 在 `http://localhost:8080/api/swagger-ui.html`。

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

- 后端主要是**纯 Mockito 单元测试**（`@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`），不依赖 Spring 容器，可直接 `mvn test`。
- `test` profile（`src/test/resources/application-test.yml`）连的是**真实 PostgreSQL（非 H2）**，`ddl-auto: create-drop`。因此 `@SpringBootTest` 的集成测试（如 `QingyuCareApplicationTests`）在本机没有运行的 PostgreSQL 时无法启动——新增测试优先写成不启容器的 Mockito 单测。

## 架构要点

### 多租户隔离（两级：tenant_id + app_id）
理解任何数据读写前必须先理解这套机制，它是全自动、隐式生效的：

1. **请求入口** `TenantInterceptor`（`config/`）：从 Sa-Token 会话取 `tenantId`，从 `X-App-Id` 请求头取 `appId`，写入 `TenantContext`（ThreadLocal）；`afterCompletion` 清理，防泄漏。
2. **查询过滤** `TenantDataAspect`（`config/`）：`@Before` 切所有 `repository..*` 调用，解包 Hibernate Session 并 `enableFilter("tenantFilter"/"appFilter")`，参数取自 `TenantContext`。平台管理员绕过过滤；非管理员且无上下文时用 `-1L`（拒绝一切）。
3. **过滤器定义** `BaseTenantEntity`（`entity/`，`@MappedSuperclass`）：用 `@FilterDef`/`@Filter` 声明 `tenant_id`/`app_id` 两个过滤条件。绝大多数业务实体继承它。
4. **写入自动填充** `TenantEntityListener`：`@PrePersist` 时从 `TenantContext` 回填 `tenantId`/`appId`。

> **例外**：异步落库的实体 `WebhookDelivery`、`SysAuditLog`、`SysApiUsage` **不继承** `BaseTenantEntity`，而是持有显式的 `tenantId`/`appId` 字段——因为 `@Async` 线程没有 `TenantContext`，不能依赖 Hibernate Filter。新增异步/跨线程写入实体时务必沿用此模式。

### AOP 横切
- `@AuditLog(action=, targetType=)`（`annotation/`）+ `AuditLogAspect`（`config/`）：`@Around` 业务方法成功后 `AuditLogService.saveAsync()` 异步落库，含正则脱敏（apiKey/password/secret → `***`）。给写操作打审计日志时加此注解即可。
- 限流：`RateLimitConfig` + `RateLimitService`（Bucket4j + Lettuce Redis），`tryConsume(appKey, limitPerMinute)`，Redis 异常时 **fail-open**（放行）。

### 对外开放 API（`/open/v1/**`）
- `OpenApiAuthInterceptor` + `OpenApiAuthService`：双路径鉴权——服务端用 `X-App-Id` + `X-Api-Key`（校验 `app.appSecret`）；浏览器 Widget 用 `X-App-Id` + Origin 白名单（`app.allowedOrigins`）。鉴权通过后写入 `TenantContext` 并做限流，超限返回 429 + `Retry-After`。
- 控制器在 `controller/open/`，DTO 在 `dto/open/`（如 `OpenChatController` 的 `/chat/completions` 与 SSE `/chat/stream`）。

### Webhook 事件推送（`event/` + `service/webhook/`）
链路：业务发布 `WebhookDomainEvent` → `WebhookDispatcher`（`@TransactionalEventListener(AFTER_COMMIT)`，按 app 订阅事件过滤）落库 `WebhookDelivery(PENDING)` → `WebhookSender.@Async deliverAsync()` HTTP 投递，`WebhookSignature` 做 HMAC-SHA256 签名（头 `X-Qingyu-Signature: sha256=...`）→ 失败按 `backoff-minutes` 退避重试，`WebhookRetryScheduler`（`@Scheduled`）扫描到期的重投。配置见 `WebhookProperties`（前缀 `qingyu.care.webhook`）。

### 服务层与 DTO
- `service/`（接口）+ `service/impl/`（实现）分离。
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

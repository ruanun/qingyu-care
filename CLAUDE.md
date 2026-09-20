# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

轻语（QingYu Care）是面向企业的多租户 AI 智能客服 SaaS 平台。后端 Spring Boot 4.1 + Spring AI 2.0 + pgvector，前端 React 19 管理后台，另有一个可嵌入网页的独立 Widget 子项目。

## ⚠️ 本文件不是全部

CLAUDE.md 只留「每次都用得上」的部分——常用命令、测试约定、全局机制。另外三份在 `docs/dev/`，**改到对应范围就必须先读**：

| 改动范围 | 先读 |
|---|---|
| `service/mcp/` · `service/openapi/` · `service/pkg/` · `service/web/` · `service/media/` · `AiTool`/平台共享工具 | [`docs/dev/子系统要点.md`](docs/dev/子系统要点.md) |
| 鉴权 / 多租户守卫 / 出站请求（HTTP 工具·网页抓取·Webhook·MCP·OpenAPI schema）/ 文件上传 / CORS / 异常处理 / 限流与额度 | [`docs/dev/安全基线.md`](docs/dev/安全基线.md) |
| 流式对话 / 卡片协议 / 站点爬取 / 聊天附件 / 已读回执 / MCP / OpenAPI 导入 / widget 协议层 / 工具商店 / 升级依赖 | [`docs/dev/手工验证清单.md`](docs/dev/手工验证清单.md) |

这三份里绝大多数条目都是一个真实缺陷的墓碑，且**多数失效是静默的**（不报错、不打日志、没有任何测试会红）。漏读的代价不是「写得不够好」，是把同一个坑原样再踩一遍。

不确定该读哪份，就让脚本按本次改动的文件点名：

```bash
./scripts/manual-smoke-hints.sh              # 看工作区改动
./scripts/manual-smoke-hints.sh master...HEAD  # 看整个分支
```

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
- JDK 25。后端启动需注入环境变量 `DASHSCOPE_API_KEY`（通义千问）。可选覆盖 `AI_BASE_URL` / `AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL` 切换任意 OpenAI 兼容端点。
- ⚠️ **`AI_BASE_URL` 必须自带 `/v1`**（如 `https://dashscope.aliyuncs.com/compatible-mode/v1`）。Spring AI 2.0 起改用官方 openai-java SDK，只往 baseUrl 后拼 `chat/completions` / `embeddings`，**不再补 `/v1`**（1.x 的 `OpenAiApi` 拼的是 `/v1/xxx`，故当年要求不带，契约已翻转）。该项同时喂给 ChatModel 与 EmbeddingModel，缺 `/v1` 会让对话/摄取/检索全线 404。
  ⚠️ **但「一律带 `/v1`」只对 DashScope 这类厂商成立，不是通则**：库里 `ai_model_config.endpoint` 是租户逐个模型填的，各家 OpenAI 兼容模式的 base 路径后缀并不统一（智谱 `/api/paas/v4`、火山方舟 `/api/v3`、Gemini `/v1beta/openai`），给它们补 `/v1` 就是 404。判据是「拼上 `chat/completions` 是否恰好等于该厂商的真实请求地址」，不是「有没有 `/v1`」。各家的正确值见 `ModelProviderCatalog`，那是全仓唯一一份厂商清单，前端经 `GET /v1/ai-models/providers` 拉同一份渲染下拉与徽章——**别在前端另起一份**。
- 服务上下文路径是 `/api`，Swagger 在 `http://localhost:8080/api/swagger-ui.html`。

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

### 演示数据与截图

`DemoDataSeeder`（`@Profile("demo")` + `ApplicationRunner`）播种「云舟科技」整套演示数据：租户/应用/用户、租户级问答策略、知识库 4 篇文档（走真实 `uploadDocument()`，S3 + Tika + 真实 embedding，向量可检索）、8 条 QA、12 组会话（铺在最近 30 天）、30 天用量、分析日表经 `backfill` 真实聚合。幂等键是租户 code `demo-cloudboat`，反复重启不重播。演示账号 `demo_admin` / `demo123456`（租户管理员）、`demo_agent` / `demo123456`（坐席）。

⚠️ 三个坑已写在类注释里，改动时别退化：

1. **`ApplicationRunner` 没有 `TenantContext`**，不手工搬运则知识库与文档的 `tenant_id` 静默落 NULL——后果不是「对所有租户可见」而是反过来「对任何租户都检索不到」（metadata 写成 `tenantId: ""`，与 `tenantId == '<真实id>'` 永远匹配不上）。`finally` 里必须 `clear()`，启动线程会被后续请求复用。
2. **`ChatSession`/`ChatMessage` 的 `createdAt` 带 `@CreationTimestamp`**，Hibernate 在 INSERT 时覆盖任何手工赋值，必须落库后用原生 UPDATE 回填，否则 12 条会话全落在今天，而 rollup 按 `cm.created_at` 分日聚合，30 天曲线退化成一根柱子。
3. **`hit_document_ids` 必须写 JSON 数组字面量**（`"[16]"`）而非裸数字：`AnalyticsRollupDao.insertDocHits` 用 `json_array_elements_text` 展开，标量直接抛，而 `rollupDay` 把三条 insert 包在同一事务里——那一天的质量与热词聚合会被一起回滚。

`docs/images/` 的 15 张截图基于这套数据实拍（1440×900）。改了界面要重拍时：起 `dev,demo` profile，用 `demo_admin` 登录并**在顶部选中「云舟商城客服」应用**（应用级配置页外包 `<RequireApp>`，未选应用截出来是空白），按编号顺序走一遍。⚠️ 每张截图都在 README 或使用手册里有引用，删改文件名要同步改引用。

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
   ⚠️ **`TenantDataAspect` 那条「未登录则不过滤」的豁免不够**：判据 `!isLoggedIn()` 是个**间接代理量**，它假设「登录/注册总发生在未登录态」。上一个账号的 satoken 还在时该假设不成立——浏览器 cookie 残留最常见（Sa-Token 会写 cookie，前端 `localStorage.clear()` 清不掉它），此时 `TenantInterceptor` 已把上一个租户的 tenantId 写进上下文，过滤器照常生效。两类症状**都指向错误的排查方向**：登录别租户的账号报「用户名或密码错误」（密码其实是对的，人会一路去查密码）；注册或建用户时重名查不出来 → 放行到 INSERT 撞唯一约束 → HTTP 500 且把原始 SQL 回给客户端（正确响应是 400「用户名已存在」）。`LoginIT` 的五条测试分两半守这条：前两条守「未登录态」，后三条守「带着别人的登录态」——缺陷正藏在后一半里。2026-09-02 真机验证时发现并修复。

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
- `docs/dev/子系统要点.md` / `docs/dev/安全基线.md` / `docs/dev/手工验证清单.md`：本文件拆出去的三份，路由见顶部的「本文件不是全部」。
- `docs/README.md`：文档索引，区分「使用者该读的」与「开发者该读的」。
- `docs/使用手册.md`：面向租户管理员的功能操作指南（配截图）。改了界面行为要同步。
- `docs/部署文档.md`：部署、容器化、备份恢复、监控与**故障手册**——本文件里那些「不报错只静默异常」的坑，运维视角的版本在那儿。
- `docs/开发流程文档.md`：环境搭建、分支与提交规范、测试体系、各子项目的开发约定。
- `SaaS平台规划.md`：Phase 1（多租户基础）/ Phase 2（对外接入：开放 API + Widget + Webhook）/ Phase 3（管理后台）/ Phase 4（计费、多渠道、高级 RAG）/ Phase 5（智能客服能力补齐）的详细任务与接口设计。
- `brand/README.md`：logo 与品牌色。⚠️ 图形的路径数据在仓库里存了**五份**（favicon / 管理端组件 / widget 常量 / 字标 / 基准），三个独立构建目标之间没有共享模块的通道。`frontend/scripts/brandAssetSync.test.ts` 守着五处同源，改一处漏其它即红；**改了 `widget/src/logo.ts` 还必须重新 build 并提交 `widget.js`**。

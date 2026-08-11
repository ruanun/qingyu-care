# 开源就绪：演示数据 + 截图 + 文档体系 + 仓库整理

> 日期：2026-08-11
> 作者：ruan

## 1. 背景与目标

轻语的功能已经收口到 Phase 4，但仓库对**外部读者**是不友好的：README 停留在两个大版本之前且含一处会让人直接跑不起来的错误；没有任何截图；没有面向使用者的操作手册；部署文档只到「本地能跑」；仓库里还堆着生成物与散落的存档。

本次目标是让这个仓库**能被一个陌生人 clone 下来、看懂它是什么、跑起来、并照着文档用起来**。

四条工作线：

1. **演示数据**——一条命令产出一整套可截图、可试用的真实数据（含真实向量，RAG 检索能命中）。
2. **截图**——14 张管理后台与 Widget 实拍，供 README 与使用手册引用。
3. **文档**——README 重写、新增使用手册、部署文档升级到生产级（含容器化产物）。
4. **整理**——清生成物、拆技术债清单、建文档索引、补 LICENSE 与 .env.example。

### 非目标

- 不做 CI/CD 流水线（本仓库无 CI，守护靠 `mvn test` / `pnpm test` 内的测试）。
- 不改动任何业务逻辑。演示数据播种类是新增的、被 profile 隔离的旁路代码。
- 不迁移 `docs/superpowers/`（理由见 §6.2）。
- 不写 CONTRIBUTING / CHANGELOG / SECURITY（本轮范围外，用户已明确）。

---

## 2. 现状勘查（已核实）

| 事实 | 影响 |
|------|------|
| README 写 Spring Boot 3.4.1 / Spring AI 1.0.0-M4，实际 pom 是 **4.1.0 / 2.0.0** | 技术栈表整体失真 |
| README 第 126 行示例 `AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode`（缺 `/v1`） | **照抄即全线 404**。Spring AI 2.0 起改用官方 openai-java SDK，只往 baseUrl 后拼 `chat/completions`，不再补 `/v1` |
| README 声明 MIT，仓库无 LICENSE 文件 | 许可证声明落空 |
| README 未提及：网站知识库、站点爬取、结构化卡片、多媒体消息、工具调用/Skill、流式对话 | 六个已交付特性对外不可见 |
| `docs/architecture/qingyu-care-runtime.html` 571KB 生成物已提交 | 仓库体积与噪声 |
| `docs/名字.png` 全仓库零引用 | 孤儿文件 |
| 技术债清单内嵌在 `SaaS平台规划.md`（§技术债清单，约 110 行） | 规划与欠账混在一个文件，两边都难读 |
| `docs/superpowers/` 被 CLAUDE.md 3 处、开发流程文档 3 处、SaaS平台规划 4 处引用 | 迁移成本真实存在 |
| `uploadDocument()` 带 `@Transactional`，投递经 `AfterCommit` | 播种线程调用时是**真异步**，必须轮询等 `COMPLETED` |
| `AnalyticsRollupService.backfill(from, to)` 现成可用 | 分析日表走真实聚合，不用手写假数据 |
| `QaPairService.create(kbId, q, a)` 现成可用 | QA 走真实嵌入 |
| Docker 三件套已在本机运行，`DASHSCOPE_API_KEY` 已在环境变量中 | 截图具备执行条件 |

---

## 3. 演示数据

### 3.1 形态与触发

新增 `backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java`：

```java
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner { ... }
```

启动方式：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo
```

**为什么用 `ApplicationRunner` 而不是 `@PostConstruct`**：`ChatStrategyService` / `HandoverRuleService` / `PromptTemplateService` / `ModelRouterService` 四个服务在 `@PostConstruct` 里播种 `tenant_id IS NULL` 的平台默认行，演示数据要能继承它们，就必须排在其后。`@PostConstruct` 之间的相对顺序取决于 bean 依赖图，是隐式且脆弱的；`ApplicationRunner` 在容器完全就绪后执行，顺序是确定的。

**为什么用 `@Profile("demo")` 而不是配置开关**：profile 是编译期即可见的隔离边界，生产启动时这个 bean 根本不会被创建，不存在"配置写错了往生产库塞演示数据"的可能。

### 3.2 租户上下文必须手工搬运（本设计最容易出事的一点）

`ApplicationRunner` 跑在启动线程，**没有 `TenantContext`**。若不设置，`TenantEntityListener` 的 `@PrePersist` 回填不到值，知识库与文档的 `tenant_id` 会静默落 NULL。

后果不是"对所有租户可见"，而是**反过来对任何租户都检索不到**：`KnowledgeServiceImpl.processDocument` 在 tenantId 为 null 时把向量 metadata 写成 `"tenantId": ""`，而 `RetrievalScope.toFilterExpression` 对非 null 租户拼的是 `tenantId == '<真实id>'` 等值条件，空串永远匹配不上。表现为 embedding 的钱照花、检索 0 命中、全程无异常无日志——正是 CLAUDE.md 在「站点爬取」一节点名过的同一个坑。

因此播种流程固定为：

```java
try {
    // 1. 建租户 / 应用（不受 @Filter 约束的关系映射实体）
    // 2. 显式建立上下文
    TenantContext.setTenantId(tenant.getId());
    TenantContext.setAppId(app.getId());
    TenantContext.setUserId(adminUser.getId());
    TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);
    // 3. 在上下文内建知识库 / 文档 / QA / 会话
} finally {
    TenantContext.clear();   // 启动线程会被后续请求复用，不清理即泄漏
}
```

### 3.3 幂等与容错

- **幂等键**：`tenantRepository.findByCode("demo-cloudboat")`。存在即整体跳过，打一行 `log.info("演示数据已存在，跳过播种")`。反复重启不会重复烧 embedding。
- **容错**：整个 `run()` 包 try/catch，失败只 `log.error` 不外抛。演示数据播种失败不应该让应用起不来——它是旁路，不是主链路。

### 3.4 数据内容

虚构公司**云舟科技**的电商客服场景。用具体业务场景而非"测试租户1"，是因为截图要能直接放进 README，占位符数据撑不起门面。

| 层级 | 内容 | 落地方式 |
|------|------|----------|
| 租户 | 云舟科技（code `demo-cloudboat`，plan `PRO`，status `ACTIVE`） | `tenantRepository.save` |
| 用户 | `demo_admin`（TENANT_ADMIN）、`demo_agent`（TENANT_MEMBER，坐席台演示用） | `BCrypt.hashpw`，与 `AuthService` 一致 |
| 应用 | ① 云舟商城客服（**固定 appKey**，配欢迎语/主题色/allowedOrigins/限流）② 云舟内部 IT 助手 | `applicationRepository.save` |
| 知识库 | 云舟商城帮助中心 | `knowledgeService.createKnowledgeBase` |
| 文档 | 4 篇 Markdown：退换货政策 / 配送与物流 / 会员与积分 / 支付与发票 | **`knowledgeService.uploadDocument()`** 真实路径 |
| QA | 8 条问答对 | `qaPairService.create()` 真实嵌入 |
| 会话 | 12 条历史会话 + 消息：含命中分数、token 用量、赞踩反馈、1 条转人工、1 条带 `uiSpec` 的卡片消息 | repository 直写 |
| 用量 | 最近 30 天 `SysApiUsage`（调用数/消息数/token 数，带自然波动） | repository 直写 |
| 分析 | 三张 `stat_*_daily` 日表 | `analyticsRollupService.backfill(today-29, today)` **真实聚合** |

**文档为什么走 `uploadDocument()` 而不是直接构造实体**：走真实路径意味着演示数据与真人操作产出的数据**完全同构**——S3 里真有对象、Tika 真解析过、切分块数与文件大小都是真的、向量是真的。截图里的文档列表因此不会露出破绽，而且顺带验证了整条摄取链路可用。播种类内提供一个约 20 行的极简 `MultipartFile` 实现（`byte[]` + 文件名）作为入参。

**为什么 appKey 用固定值而非随机**：使用手册与 README 里的 Widget 嵌入代码可以直接写死这个 appKey，读者照抄就能跑通。appSecret 同样固定，并在文档中显著标注「演示专用，生产必须重新生成」。

### 3.5 等待文档就绪

`uploadDocument()` 内部经 `AfterCommit` 把 `processDocumentAsync` 推迟到事务提交后投递，因此播种线程返回时文档仍是 `PENDING`。播种类在建完 4 篇文档后**轮询 `getDocumentStatus`** 直到全部 `COMPLETED`，超时上限 120 秒（超时只警告，不失败）。

不等的后果是截图时文档卡在"处理中"，且后续会话数据里的命中记录与实际向量对不上。

---

## 4. 截图

### 4.1 执行方式

后端以 `dev,demo` 启动，前端 dev server 已在 `:5173`，用 chrome-devtools 逐页导航截图。统一视口 **1440×900**，存放 `docs/images/`，命名 `NN-<英文短名>.png`。

### 4.2 清单（14 张）

| # | 文件名 | 页面 | 用途 |
|---|--------|------|------|
| 01 | `01-login.png` | 登录页 | 使用手册 |
| 02 | `02-dashboard.png` | 系统概览 | README 首屏 |
| 03 | `03-knowledge-list.png` | 知识库列表 | README |
| 04 | `04-document-detail.png` | 文档详情（切分块） | 使用手册 |
| 05 | `05-crawl-site.png` | 站点爬取配置与进度 | README |
| 06 | `06-strategy.png` | 问答策略 | 使用手册 |
| 07 | `07-prompt.png` | Prompt 管理 | 使用手册 |
| 08 | `08-model-config.png` | 模型配置与路由 | 使用手册 |
| 09 | `09-sessions.png` | 会话记录 | 使用手册 |
| 10 | `10-agent-workbench.png` | 人工工作台 | README |
| 11 | `11-usage.png` | 用量看板 | README |
| 12 | `12-analytics.png` | 分析三件套 | README |
| 13 | `13-integration.png` | 接入配置（Widget 代码） | 使用手册 |
| 14 | `14-widget-chat.png` | Widget 实机对话（含卡片渲染） | README 首屏 |

### 4.3 脱敏

截图前逐张检查不得出现真实凭据。演示数据本身是虚构的，但 `.env` 或浏览器 devtools 可能意外入镜。appSecret 类字段若在页面上明文展示，截图时以演示值出现即可（已标注为演示专用）。

---

## 5. 文档

### 5.1 README 重写

保留现有骨架（功能特性 / 技术栈 / 项目结构 / 快速开始 / 接口 / 配置 / 文档 / 路线图），修改：

- **订正技术栈表**：表中每个版本号一律从 `backend/pom.xml`、`frontend/package.json`、`widget/package.json` 现读现填，不沿用旧表任何一行（旧表已被证实至少三处失真，逐条核对比挑错改更可靠）。
- **订正 `AI_BASE_URL` 示例**为 `https://dashscope.aliyuncs.com/compatible-mode/v1`，并加一行醒目说明「必须自带 `/v1`」——这是本次文档修改里唯一一条**会直接决定读者能否跑起来**的。
- **补齐六个未提及特性**：网站知识库（URL 抓取 + 定期同步）、站点爬取（BFS 多页）、结构化卡片（生成式 UI）、多媒体消息（图片/文件）、工具调用与 Skill、流式对话（SSE）。
- **加截图墙**：首屏放 `02` / `14`，功能区穿插 `03` / `05` / `10` / `11` / `12`。
- **文档索引指向** `docs/README.md`。

### 5.2 新增 `docs/使用手册.md`

面向**租户管理员**的端到端操作指南，配截图：

1. 登录与角色说明（平台管理员 / 租户管理员 / 租户成员各能看到什么）
2. 创建应用与获取接入凭据
3. 建知识库 → 上传文档 → 查看切分与处理状态
4. 网站知识库：单页添加与站点爬取（含范围前缀、正文选择器、robots 与限速的实际含义）
5. QA 问答对
6. 调问答策略（TopK / 相似度阈值 / 兜底话术）与 Prompt 模板
7. 模型配置与路由降级
8. 转人工规则与人工工作台接管流程
9. 嵌入 Widget（一行 script，含固定 appKey 的可运行示例）
10. 开放 API 调用（curl 示例，服务端 `X-Api-Key` 路径）
11. 看用量、账单额度与分析三件套
12. A/B 实验

### 5.3 `docs/部署文档.md` 升级

在现有九节基础上补：

- **容器化部署**（新）：`docker-compose.prod.yml` 一键拉起全栈的完整流程
- **Nginx / HTTPS 反代**：证书、SSE 长连接的 `proxy_buffering off`（流式对话必需，这条漏了会表现为"回复不逐字出现"）
- **备份与恢复演练**：不只给备份命令，给一遍**恢复**的验证步骤
- **监控与告警**：actuator 端点、关键指标（长任务池饱和度、webhook 投递积压、embedding 失败率）
- **故障手册**：把 CLAUDE.md 里那些静默失败模式翻译成运维视角的排查路径

### 5.4 容器化产物（代码）

| 文件 | 内容 |
|------|------|
| `backend/Dockerfile` | 多阶段：maven 构建 → JRE 17 运行，非 root 用户 |
| `frontend/Dockerfile` | 多阶段：pnpm build → nginx 托管，含 SPA history fallback 与 `/api` 反代 |
| `frontend/nginx.conf` | 上条所需的站点配置 |
| `docker/docker-compose.prod.yml` | PG + RustFS + Redis + backend + frontend，环境变量走 `.env` |

**验收**：这四个文件必须实际 `docker compose -f docker-compose.prod.yml build` 通过并起得来，不能只是写在文档里好看。

### 5.5 LICENSE 与 .env.example

- `LICENSE`：MIT，版权行 `Copyright (c) 2026 ruan`。消解 README 声明与仓库现状的矛盾。
- `.env.example`：集中 `DASHSCOPE_API_KEY` / `AI_BASE_URL`（含必须带 `/v1` 的注释）/ 数据库与 Redis 连接 / S3 凭据 / Sa-Token 密钥，供 `docker-compose.prod.yml` 与本地开发共用。

---

## 6. 仓库整理

### 6.1 执行项

| 动作 | 对象 | 理由 |
|------|------|------|
| 删除 | `docs/architecture/`（html 571KB + json） | 生成物，非手写文档，无人引用 |
| 删除 | `docs/名字.png` | 全仓库零引用的孤儿文件 |
| 拆出 | `SaaS平台规划.md` 的技术债清单 → `docs/技术债清单.md` | 路线图与欠账清单是两种读法、两种更新节奏，混在一个文件里两边都难读。原位置留一行指针 |
| 新增 | `docs/README.md` | 文档索引：区分「使用者该读的」与「开发过程存档」 |

### 6.2 `docs/superpowers/` 保持原地不动

原计划移到 `docs/dev-history/`，评估后放弃，两条理由：

1. **迁移要同步改 10 处引用**，其中 CLAUDE.md 里那两条带 §章节号（`2026-07-30-boot4-springai2-migration-design.md` §8.1/§8.3、`2026-08-06-site-crawl-design.md` §5.2），是排查迁移与爬取问题时真正会去翻的指针，改错即失效。
2. **`docs/superpowers/specs/` 是 brainstorming skill 的默认写入路径**。改名后下次写 spec 仍会落到 `superpowers/`，目录立刻分裂成两份——这正是 CLAUDE.md 里「卡片协议白名单分裂三处」同一类病。

而"把使用文档与开发存档分开"这个目标不迁移也已达成：`docs/` 根目录下本来就只有面向使用者的文件。在 `docs/README.md` 里写明 `superpowers/` 是开发过程存档、使用者不必阅读即可。

---

## 7. 影响面与风险

| 风险 | 处理 |
|------|------|
| 演示数据播种失败拖垮应用启动 | `run()` 整体 try/catch，只记 error |
| 演示数据误入生产库 | `@Profile("demo")` 隔离，生产不激活该 profile 则 bean 不存在 |
| 重复播种重复烧 embedding | 幂等键 `findByCode("demo-cloudboat")` 整体跳过 |
| 播种线程 `TenantContext` 泄漏给后续请求 | `finally` 中 `clear()` |
| 文档 `tenant_id` 落 NULL 导致 0 命中 | §3.2 显式建立上下文；验收时实查 `document.tenant_id` 非 NULL |
| 生产 compose 只写不验 | §5.4 要求实际 build 并起得来 |
| 技术债清单拆出后两处不同步 | 原位置只留指针，不复述内容 |

### 测试与验收

**自动化**：本次不新增测试。播种类是 profile 隔离的旁路代码，为它写单测的收益低于维护成本；`AsyncExecutorRoutingTest` 与 `AsyncDispatchOutsideTransactionTest` 会自动覆盖到新代码里若有的 `@Async` 与事务内投递（本设计中没有，但守护仍然生效）。

**必须手工验收**（自动化测试覆盖不到）：

1. `mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo` 全新库起一次，4 篇文档最终都是 `COMPLETED`。
2. `SELECT tenant_id, app_id FROM document WHERE knowledge_base_id=?` **非 NULL**——这是 §3.2 那个坑的唯一验收方式。
3. 在演示应用里提一个问题，确认命中知识库且 `score > 0`（向量真的可检索，不是只有行没有向量）。
4. 再启动一次，日志出现「演示数据已存在，跳过播种」且行数不变。
5. `docker compose -f docker/docker-compose.prod.yml build && up -d` 起得来，前端能访问、后端 health 绿。
6. README 与使用手册里的每条命令、每个 URL 亲自跑一遍。

---

## 8. 交付物清单

**新增代码**
- `backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java`
- `backend/Dockerfile` / `frontend/Dockerfile` / `frontend/nginx.conf` / `docker/docker-compose.prod.yml`

**新增文档**
- `docs/使用手册.md` / `docs/README.md` / `docs/技术债清单.md`
- `LICENSE` / `.env.example`
- `docs/images/*.png`（14 张）

**修改**
- `README.md`（重写）/ `docs/部署文档.md`（升级）/ `SaaS平台规划.md`（技术债段落换成指针）/ `CLAUDE.md`（补 demo profile 用法）

**删除**
- `docs/architecture/` / `docs/名字.png`

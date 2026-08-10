# 轻语 SaaS 平台规划

## 平台定位

面向企业客户的 AI 智能客服 SaaS 平台，支持多租户、多应用接入，提供问答助手、智能客服等能力。

---

## 里程碑总览

> 按产品版本视角快速把握能力边界；细粒度任务清单见下方 Phase 1–4。

| 版本 | 能力边界 | 对应 Phase | 状态 |
|------|----------|-----------|------|
| **MVP（基础客服）** | 单实例可用的 RAG 智能客服：知识库管理（多格式解析/切分/向量化）、RAG 问答（向量检索 + 多轮对话）、人工接管与工作台、Prompt 模板、会话与消息记录 | 多租户改造前的基础功能 | ✅ |
| **多租户 SaaS（增强版）** | 多租户数据架构与自动隔离、Sa-Token 认证与角色权限、管理后台全套（策略中心 / 多模型路由与降级 / 转人工规则 / 配置中心 / 共享模型池 / 审计日志 / 健康概览 / 菜单深度分组 / 用量与效果看板） | Phase 1 + Phase 3 | ✅ |
| **企业版** | 对外接入（开放 API / JS Widget / Webhook）、计费与套餐额度、高级 RAG（多库联合 / 混合检索 / 文档权限）、分析与优化（质量 / 热点 / 覆盖率 / A/B 测试）、成本监控 | Phase 2 + Phase 4 | ✅（多渠道接入 ⏸ 暂缓） |

---

## 核心概念

```
平台 (Platform)
└── 租户 (Tenant) - 企业客户
    └── 应用 (Application) - 机器人实例
        ├── 知识库 (KnowledgeBase)
        ├── Prompt 模板
        ├── 问答策略
        ├── 转人工规则
        ├── 模型配置 (可继承租户级)
        └── 会话/消息
```

---

## Phase 1: 多租户基础架构 ✅

### 1.1 租户管理
- [x] `Tenant` 实体：id, name, status, plan, api_key, created_at
- [x] 租户注册/审核流程
- [x] 租户状态管理（正常/冻结/过期）
- [x] 租户级 API Key 管理

### 1.2 应用管理
- [x] `Application` 实体：id, tenant_id, name, app_key, app_secret, avatar, welcome_message
- [x] 应用创建/编辑/删除
- [x] 应用级 API Key 管理
- [x] 应用配置（欢迎语、头像、主题色）

### 1.3 数据隔离改造
- [x] 现有表增加 `tenant_id` / `app_id` 字段
- [x] Hibernate Filter + AOP 实现自动数据隔离
- [x] 向量库按租户隔离（metadata + filter expression）

### 1.4 用户与权限
- [x] `User` 实体：id, tenant_id, username, password, role
- [x] 角色：平台管理员 / 租户管理员 / 租户成员
- [x] Sa-Token 认证 + 权限校验
- [x] 登录/注册/密码重置

---

## Phase 2: 接入能力 ✅

> 2026-07-03 完成并合入 master：开放 REST API、JS Widget、Webhook 三个子项均已实现（含单元测试）。2026-07-04 完成真实 DB + 浏览器端到端联调（修复 7 个隐性问题），并落地 Testcontainers 集成回归网（`mvn verify`）。

### 2.1 REST API 接入 ✅
- [x] 标准化开放 API（统一前缀 `/open/v1/**`）
- [x] 双路径鉴权：服务端 `X-App-Id` + `X-Api-Key`；浏览器 Widget `X-App-Id` + Origin 白名单
- [x] 核心接口：
  - `POST /open/v1/chat/completions` - 发起对话
  - `POST /open/v1/chat/stream` - 流式对话（SSE）
  - `GET /open/v1/sessions/{id}` - 获取会话
  - `POST /open/v1/sessions/{id}/handover` - 请求转人工
  - `GET /open/v1/widget/config` - Widget 配置
- [x] 接口限流（Bucket4j + Redis，应用级可配，故障 fail-open）与用量计量（按应用 + 日期异步聚合）

### 2.2 JS Widget 嵌入 ✅
- [x] 独立的 Widget SDK 项目（`widget/`，Vite + TS，产物 `widget.js` 提交进后端静态资源）
- [x] 一行代码嵌入：`<script src="https://xxx/api/widget.js?appKey=xxx"></script>`
- [x] 可配置样式（主题色、位置等），Shadow DOM 样式隔离
- [x] 支持自定义欢迎语、头像
- [x] 移动端适配

### 2.3 Webhook 事件推送 ✅
- [x] 事件类型：`session.handover_requested`、`session.ended`（点号命名，可扩展）
- [x] Webhook 配置管理（接入页配置 URL / 订阅事件 / 密钥 / 测试事件端点）
- [x] 事务后落库 + `@Async` 投递 + HMAC-SHA256 签名（头 `X-Qingyu-Signature`）+ 退避重试与定时重投

---

## Phase 3: 管理后台重构 ✅

> 2026-07-05 更新：平台配置 / 租户配置 / 共享模型池 / 系统监控（审计日志+健康概览）/ 接入配置（IntegrationPage）/ 用量统计看板均已完成并合入 master，Phase 3 全部收口（在线支付账单不做，套餐额度归入 Phase 4.1）。详见 `docs/superpowers/specs/2026-07-02-phase3-admin-capabilities-design.md`。

### 3.1 平台级管理（超级管理员）
- [x] 租户列表与管理
- [x] 全局模型配置（共享模型池，租户可继承平台共享模型）
- [x] 系统监控与日志（操作审计日志 + 健康概览；完整可观测性栈未接入）
- [x] 平台配置（键值配置中心）

### 3.2 租户级管理（企业管理员）
- [x] 应用列表与管理
- [x] 团队成员管理
- [x] 用量统计与套餐额度（用量看板 2026-07-05 ✅，含 Token 费率成本估算[2026-07-22]；套餐额度见 Phase 4.1 ✅；在线支付账单不做）
- [x] 租户配置（键值配置中心；全部配置项已接入生效：品牌名→Widget、会话超时→定时任务、模型场景→路由，2026-07 收尾）

### 3.3 应用级管理（每个机器人）
- [x] 知识库管理
- [x] Prompt 管理
- [x] 问答策略
- [x] 转人工规则
- [x] 模型配置（继承或覆盖）
- [x] 接入配置（API Key / Widget 代码生成 / Webhook 配置，接入页 IntegrationPage）
- [x] 会话记录

### 3.4 菜单分组优化
```
🏢 租户管理（仅超管可见）
├── 租户列表
└── 系统配置

📱 应用管理
├── 我的应用
└── 创建应用

🤖 应用配置（选中应用后）
├── RAG 知识库
│   ├── 知识库管理
│   ├── Prompt 管理
│   └── 问答策略
├── AI 模型
│   ├── 模型配置
│   └── 转人工规则
└── 接入配置
    ├── API 接入
    └── Widget 配置

💬 客服中心
├── 智能问答（测试）
├── 人工工作台
└── 会话管理

📊 数据统计
├── 用量统计
└── 效果分析
```

---

## Phase 4: 高级功能

### 4.1 计费与套餐
- [x] 套餐定义（FREE/PRO/ENTERPRISE 额度映射，平台可配 + 内置默认）
- [x] 用量计量（开放 API 调用次数 + token 双维度；文档数/存储量维度暂缓）
- [x] 超额限制与告警（403 QUOTA_EXCEEDED、近阈值 X-Quota-Usage 头 + QUOTA_WARN 审计、月度自然重置、fail-open）

### 4.2 多渠道接入 ⏸ 暂缓

> 2026-07-22 决定暂缓：各渠道均有外部硬前置（真实公众号/企业账号、公网回调地址、本地联调需内网穿透），待条件具备后作为独立专项启动（届时先做渠道抽象层技术选型）。

- [ ] 微信公众号
- [ ] 企业微信
- [ ] 钉钉
- [ ] 飞书

### 4.3 高级 RAG 能力
- [x] 多知识库联合检索（应用内 enabled 库自动联合，全局 top-K + IN 过滤，appId 双重隔离）
- [x] 混合检索（向量 + zhparser 中文关键词 FTS，RRF 融合，ChatStrategy 可配开关，fail-soft 降级）
- [x] 文档权限控制（PUBLIC/INTERNAL 两级 + 文档启用开关，按鉴权路径定级，等值过滤 + 存量回填）

### 4.4 分析与优化
- [x] 对话质量评估
- [x] 热点问题统计
- [x] 知识库覆盖率分析
- [x] A/B 测试（策略即变体、会话级分桶、仅开放流量、实时对比报表）

---

## Phase 5: tgo 对标能力补齐

> 来源：2026-08-04 对参考项目 [tgoai/tgo](https://github.com/tgoai/tgo)（本地 `references/tgo`，未纳入版本控制）的第二轮对照分析。
> 第一轮（2026-07-24）产出的「第一档四项」——工具调用 / QA 知识库 / 真人坐席 / 结构化卡片——已全部合入，其中卡片能力由 2026-08-04 的流式改造收官。
> tgo 是 15 个子仓库的 Python/FastAPI 微服务 monorepo；**其微服务拆分与设备控制（tgo-device-*）不在借鉴范围**——前者对本项目规模不适用，后者是其特有场景。

### 5.1 近期（按性价比排序）

- [x] **卡片 Action URI 协议**
  现状：卡片 `button` 只有一种动作——`onAction(value)` 发合成消息（`frontend/src/components/chat/registry.tsx` 的 `case 'button'`，widget `render.ts` 同）。
  目标：对齐 tgo 的标准化 Action URI，支持「链接跳转 / 发送消息 / 复制内容」三类动作。
  判断：刚完成的卡片能力的自然延伸，改动集中在两端 registry/render + UiSpec 协议扩展，**无新架构决策，投入产出比最高**。注意 widget 侧的 XSS 三防（跳转 URL 必须走 `safeUrl` 白名单，与 image 同款 fail-closed）。

- [x] **网站知识库（URL 抓取 + 定期同步）**
  现状：`DocumentType.URL` **枚举已占位但零实现**（全库无 Jsoup/crawl/fetchUrl 命中）。
  目标：给定 URL 抓取正文入知识库，并支持定期重新抓取同步（内容变更触发重嵌）。
  判断：客服知识的主要来源就是官网 FAQ/帮助中心，手工传文档是长期运营负担；**产品价值最大的一项**。摄取链路（切分/嵌入/重嵌）与向量重嵌能力均已就绪，主要新增抓取与调度。
  落地：本期只做**单页抓取 + 定期同步**（站点爬取留第二期，见 spec §10）。`Document` 加 5 个 URL 专用字段，`WebPageFetcher`（SSRF 硬门槛 + Jsoup 剔噪声 + SHA-256）+ `WebPageSyncService`（未变不重嵌 / 变了重嵌 / 失败保留旧内容三分支 + 连续 10 次失败熔断转 `MANUAL`）+ `WebPageSyncScheduler`（原生 SQL 绕租户过滤器）。⚠️ 已知局限：噪声剔除只认语义标签，用 `<div>` 做导航的站点噪声会入库（详见 CLAUDE.md）。

- [x] **多媒体消息（图片 / 文件）**
  现状：`ChatMessage` 只有 `content` 文本 + `uiSpec` 卡片，无附件模型。
  目标：用户与坐席可发图片/文件（客服日常刚需——用户发截图）。
  判断：三项里改动面最大（消息模型 + 两端渲染 + widget 上传交互），但存储侧 RustFS 已具备。
  落地：`ChatAttachment` 独立表 + 上传与发消息分两步（先传拿 id、发消息带 `attachmentIds`）；四道上传校验（分类型限额 / 扩展名白名单 / Tika 探测真实 MIME 比对 / 只读文件头取尺寸防解压炸弹，SVG 不进白名单）；私有桶经代理端点下发（图片 inline、其余强制 attachment，均带 nosniff），开放 API 侧额外要求 `?sessionId=` 作第二重能力凭证；孤儿附件 24 小时清理（连 S3 对象一起删）。三端渲染均 fetch 带鉴权头取 blob，坐席台顺带补上了此前缺失的卡片渲染。⚠️ 已知局限：`width`/`height` 对 webp 恒为 null（JDK 无 WebP 解码器）；widget 需先发一条消息建会话才能加附件。详见 CLAUDE.md 的架构要点与五条手工验证项。

### 5.2 中期

- [x] **站点爬取（网站知识库第二期）**
  落地：`CrawlSite` 表存站点配置 + `SiteCrawlService` BFS 编排（`LinkExtractor` 纯函数做规范化/范围判定/抽链接，`RobotsChecker` 解析 robots.txt——过程中修了两处解析缺陷：连续 `User-agent` 行未按同组处理会丢规则、UTF-8 BOM 附着首行 key 致整份文件规则失效；并把站点声明的 `Crawl-delay` 钳了平台上限，防第三方站点用超大声明值拖垮共享线程池）+ 正文 CSS 选择器（白名单，解决第一期启发式对 `<div>` 导航站点无效的已知局限）。默认 50 页 / 深度 3，硬顶 500 / 5，全局最多 2 个并发爬取任务。新页面靠手动重爬发现（内容更新仍由第一期周期同步覆盖，页面消失沿用既有熔断），故不做定期自动重爬。删站点用 Dialog + 复选框二次确认（默认不勾，即不删文档）。
  ⚠️ 已知取舍：重爬时已存在的页会被抓两次（避免动 `syncDocument` 那段注释最密的代码）；站点级互斥是单实例内存态，不加唯一约束兜底。详见 `docs/superpowers/specs/2026-08-06-site-crawl-design.md` 与 CLAUDE.md「网站知识库」一节的架构要点。

- [ ] **MCP 接入**（把远端 MCP 工具转为 `ToolCallback`）——原工具调用延后清单第 4 项。⚠️ 那份清单写于 Spring AI 1.1.8 时期，现为 2.0，接入方式须重新核实。tgo 整个工具体系即 MCP（另有内置 40+ 工具的 CLI MCP Server）可作对照。
- [ ] **OpenAPI Schema 自动解析生成工具表单**——现状是手工填 URL/method/inputSchema；tgo 支持丢一个 OpenAPI schema 自动生成交互表单，配置体验差距明显。
- [ ] **多智能体编排**——tgo 是「配置多个 Agent、按业务场景选」，本项目是「一个应用一套 Prompt/策略配置」。这是**产品形态选择而非技术差距**，需先决定要不要走这条路。
- [ ] **消息已读 / 送达状态**——`ChatMessage.isRead` 字段已建但**全库无人读写**（死字段）。tgo 有完整的已读未读 + 送达确认。

### 5.3 大工程（暂不建议启动）

- [ ] 工作流 DAG 引擎（对标 tgo-workflow：LLM / API / 条件 / 工具节点）
- [ ] 插件运行时 + 工具商店（原延后清单第 5 项，须在 MCP 与平台共享工具之后）
- [ ] 多端 Widget SDK（iOS / Flutter / 微信小程序 / CLI）
- 多渠道接入见 **4.2**（已暂缓，tgo 侧对应 tgo-platform，另支持 Telegram / Slack / 邮件）

### 5.4 工具方向的既有延后清单

见 `docs/superpowers/specs/2026-07-24-chat-tool-calling-design.md` §10（8 项）。截至 2026-08-04 的状态变化：
- 第 1 项**流式工具调用已完成**（2026-08-04，含 Spring AI 2.0 × Qwen 上游缺陷的受限降级）
- 第 8 项 **token 多轮聚合计量仍缺**——带工具时 usage 只反映末轮、存在少计；流式工具接通后这个漏计变得实际，优先级应上调
- 第 6 项**平台共享工具/skill** 结构上最便宜（仿 `AiModelConfig` 共享回退语义，项目内已有四处同款范式）

---

## 技术债清单

> 与上面各 Phase 的「未做功能」区分开：这里记的是**已交付代码里欠的账**，都有明确位置与后果。
> 设计上有意接受的已知局限（`<div>` 导航噪声、webp 尺寸恒 null、重爬抓两次等）不在此列，见 `CLAUDE.md` 各链路的架构要点。
> 末次盘点：2026-08-07。第 1 / 3 / 5 项已于同日清理（见 `tech-debt/async-pool-isolation` 分支的三个提交）。

### 会咬人的代码债

1. ~~**`@Async` 池是空壳默认池，长任务与关键短任务共享**~~ ✅ **已清理（2026-08-07）**
   现已分两池：短任务（用量计量/审计日志/webhook）留在 Boot 默认池，长任务（文档摄取/QA 生成/批量重嵌/网页同步/站点爬取）走 `AsyncConfig.LONG_TASK_EXECUTOR`（固定 6 线程 + 500 有界队列 + `AbortPolicy`）。`AsyncExecutorRoutingTest` 扫描全部 `@Async` 方法强制归类，新增长任务忘加 qualifier 会红。
   <details><summary>原问题</summary>
   `config/AsyncConfig` 只有 `@EnableAsync`，用的是 Spring Boot 默认 `applicationTaskExecutor`——**core 8、队列无界**。共享方：审计日志 `saveAsync`、webhook `deliverAsync`、文档摄取、网页同步、重嵌批任务，以及**几十分钟级的站点爬取**。队列无界意味着 core 满后 maxPoolSize 永不扩容、后来者排进一条无界 FIFO。
   站点爬取现用两层并发上限（全局 + 租户，见 `SiteCrawlProperties`）挡住最坏情况，但那是**用业务层配额补偿基础设施缺陷**。
   </details>

2. **token 多轮聚合计量少计**（涉及计费准确性）
   `ChatServiceImpl` 里是 `tokens.set(...)` 覆盖而非累加，非流式同样只取单个 response 的 usage。带工具时 Spring AI 内部跑多轮，只记末轮。详见 §5.4 第 8 项。
   ⚠️ 未上线前实际损失为零，而修法可能要接管 Spring AI 的工具循环（关 `internalToolExecutionEnabled` 自驱多轮，或走 observation 拦截累加）——属「看着小其实不小」，别低估。

3. ~~**`deleteKnowledgeBase` 无级联清理**~~ ✅ **已清理（2026-08-07）**
   改为删除前先数 `document`/`qa_pair`/`crawl_site` 三类内容，有内容则拒绝并在文案里说清各有多少。刻意不做级联删除——`Document.knowledgeBase` 是 `nullable = false`，文档没法「置 null 退化」，级联即意味着连文档与向量一起不可恢复地删掉；真需要时再加显式 force 参数。
   <details><summary>原问题</summary>
   `KnowledgeServiceImpl.deleteKnowledgeBase` 只做 `knowledgeBaseRepository.delete(kb)`。库下有文档 / QA / 爬取站点时直接 FK 违约 → 500，且没有可读文案。
   暴露面小：`DELETE /knowledge/{id}` 前端未接线，只能从 Swagger 或直连 API 触发。
   </details>

4. **`ChatMessage.isRead` 是死字段** —— 字段已建、全库无人读写。要么实现已读/送达（见 §5.2 第四项），要么删掉它。

5. ~~**站点爬取 `existing` 分支的失败永不计入 `p.failed`**~~ ✅ **已清理（2026-08-07）**
   改为同步后回读 `syncFailureCount` 判成败（不用 `status`——「抓取失败保留旧内容」分支刻意不动它，只看 status 会漏掉最常见的那种失败）。
   <details><summary>原问题</summary>
   `SiteCrawlService.processPage` 走 `existing != null` 分支时直接 `p.updated++`，而 `webPageSyncService.syncDocument` 自身 fail-soft 永不抛——于是重爬时 50 页全部同步失败也会显示「更新 50 · 失败 0」。
   </details>

### 测试覆盖缺口

6. **管理后台前端测试覆盖仍近乎为零**（基建已建，2026-08-10）
   `frontend/` 已接 vitest + jsdom + `@testing-library/react`（`pnpm test`），并用五条测试把站点爬取前端那三轮返工的缺陷锁住（`CrawlSiteList.test.tsx`：轮询续期 / 终态收尾 / 双任务互踩 / 卸载后在途请求 / 写操作业务异常），每条都配了变异实验。**但也仅此一处**——27 个页面与其余 29 个组件仍是零覆盖（这部分唯一验收仍是 `pnpm build` + lint），所以这条债**没清完**、不折叠。
   ⚠️ **`CrawlSiteList` 自己的覆盖也不完整**：建站/编辑/删除三条对话框路径里只有前两条因 `CrawlSiteDialog` 被桩成空组件而未覆盖，删除路径（`openDeleteDialog` 的「破坏性操作默认不勾选」+ `handleConfirmDelete`）用的是内联 `Dialog`、并没有被桩掉，补测不需要改桩策略——只是现有五条测试都没点过「删除」按钮。这条一旦退化（默认值改回勾选、或误删逻辑漂移），后果是不可恢复地删掉文档与向量；上一批评审专门把这里从「两次 confirm」改成现在这样。
   ⚠️ **`pnpm test` 目前不被任何东西自动执行**：`pnpm build` 只是 `tsc -b && vite build`，不跑测试；仓库没有 CI（CI 与覆盖率门槛是本批 spec 明确排除的范围）。这五条测试只在有人主动想起去跑的时候才起作用，不能假设「有测试了、回归会被自动抓住」。
   <details><summary>原问题</summary>
   `frontend/` 无 vitest，唯一验收是 `pnpm build` + lint；反倒是 `widget/` 有 vitest 与 10 个测试文件。
   代价已经实际发生过：站点爬取的前端三轮返工（轮询生命周期、双并发任务互踩、StrictMode 下 `isMountedRef` 永久卡死）全靠人工代码走查发现，**有测试的话第一轮就该红**。
   </details>

7. **前端 lint 有 69 个既有 error 基线**
   构成：`@typescript-eslint/no-explicit-any` 50（多为 `catch (error: any)`）、`react-hooks/set-state-in-effect` 15、`react-hooks/exhaustive-deps` 9、`no-unused-vars` 2。
   当前只能保证「每次改动不新增」，无法把 lint 当红绿灯用。

8. **站点爬取 C-1 只有结构性哨兵**
   「`startCrawl` 在事务内投递 `@Async`」这条竞态无法确定性复现（实测把 `@Transactional` 加回后 IT 连跑 4 次全绿），现在只有一条反射断言守住「注解被加回来」这一种回归方式，竞态本身靠手工验证项兜底。
   同批的另两处（`Error` 逃逸留僵尸 RUNNING、收尾 detached merge 静默回滚配置）终审已证明纯 Mockito 可测并补了真守护。

9. **管理后台大量写操作把业务错误静默吞掉**
   形态是 `if (res.code === 200) { 成功分支 }` **没有 else**——不是没判 code，是判了却没有失败分支。业务异常走「HTTP 200 + `body.code`」、axios 不 reject，于是后端的业务错误（重复入口、资源被占用、并发上限、额度超限等）到了前端一律无声无息：用户点按钮毫无反应，也没有任何报错。
   已确认的样本：`ApplicationListPage`（创建 / 更新 / 启停）、`HandoverRulePage`（启停 / 保存配置）、`KnowledgePage`（删文档）、`PromptPage`（创建版本 / 激活版本 / 删除版本——`pages/PromptPage.tsx:69`/`:83`/`:96` 三处均是裸 `if (response.code === 200) {...}` 无 else）。
   ⚠️ 初步 `grep -A4` 式扫描显示约 20 个左右页面/组件命中 `code === 200`，但尚未逐处分类点数：既未排除**读操作**（拉列表、看板取数，本条债只关心写操作），也未排除**三元表达式**（如 `CrawlSiteList.tsx:105` 的 `p = res.code === 200 ? res.data : null`，形态上撞了关键词但不是本条债的问题）。`AbTestPage` 曾被浅层扫描误列为样本——复核后它的 `runAction` 封装（`AbTestPage.tsx:216-227`）其实带 else 分支 + destructive toast，是本仓库里业务错误处理**做对了**的页面，已从样本里删除。**没有可信的总数**；处理这项债时必须先逐处分类点数，不能沿用任何聚合数字。
   正确写法在仓库里现成：`components/knowledge/CrawlSiteList.tsx` 的四个写操作都是 `if (res.code !== 200) throw new Error(res.message)` + catch 里 destructive toast。
   ⚠️ 修的时候顺手给关键页面补回归测试——基建已经有了（见第 6 项），这类缺陷正是它最擅长抓的。

---

## 技术栈补充

| 模块 | 技术选型 |
|:---|:---|
| 认证 | Sa-Token (替代 Spring Security + JWT) |
| 多租户 | Hibernate Filter + AOP 自动切面 |
| 前端框架 | React + TypeScript + Vite |
| UI 组件 | Shadcn UI + Tailwind CSS |
| 状态管理 | Zustand |
| Widget SDK | 独立 Vite + TypeScript 项目（`widget/`，Shadow DOM 隔离） |
| 限流 | Bucket4j + Lettuce Redis（应用级可配，故障 fail-open） |
| 消息队列 | Redis Stream（Webhook 投递队列：XADD + 消费组，故障回退 @Async 直投，DB 重试兜底） |
| 文件存储 | RustFS (S3 兼容) |

---

## 当前状态

**四个 Phase 均已收口（截至 2026-07-22）**：Phase 1 多租户基础、Phase 2 对外接入（开放 API + Widget + Webhook）、Phase 3 管理后台、Phase 4 高级功能（计费额度 / 高级 RAG / 分析与优化含 A/B 测试）全部完成并合入 master。

**Phase 1-4 之后的增量**（均已合入 master）：工具调用 / Skill、QA 问答对知识库、真人坐席、结构化卡片（SP1-SP3）、Boot 4.1 + Spring AI 2.0 迁移、RealtimeHub 多实例、**流式聊天改造（2026-08-04）**——两端主聊天改 SSE 事件流并把流式补齐到与非流式同语义。

**Phase 5.1 近期三项已全部合入**（2026-08-04 ~ 08-06）：卡片 Action URI 协议 / 网站知识库（URL 抓取 + 定期同步）/ 多媒体消息（图片 + 文件）。

**Phase 5.2 首项已合入**（2026-08-06）：站点爬取（网站知识库第二期）。

**待办**：
- **4.2 多渠道接入 ⏸ 暂缓**——外部账号与公网回调等硬前置未具备，待条件成熟后作为独立专项启动（届时先做渠道抽象层技术选型）。
- **Phase 5.2 中期**——站点爬取已完成（2026-08-06）；剩 MCP 接入、OpenAPI Schema 自动生成工具表单、多智能体编排（需先定产品形态）、消息已读/送达状态，见 5.2 小节。

> 各功能的完成日期与实现要点见上方 Phase 清单内注释，设计与实现细节见 `docs/superpowers/` 下对应的 spec / plan 文档。

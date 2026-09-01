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

- [x] **MCP 接入**（把远端 MCP 工具转为 `ToolCallback`）——原工具调用延后清单第 4 项。⚠️ 那份清单写于 Spring AI 1.1.8 时期，现为 2.0，接入方式已重新核实（不用官方 starter，自建 client + webflux 传输，见设计文档 §2.1/§9）。tgo 整个工具体系即 MCP（另有内置 40+ 工具的 CLI MCP Server）可作对照。
  **2026-08-21 完成**：`mcp_server`/`mcp_tool` 独立表（不复用 `ai_tool`，理由见设计文档 §2.6）。连接不缓存——`ChatToolService.resolve()` 只读数据库，真正的 MCP 连接只在工具被调用那一刻建立、用完即弃；只读判据是租户在界面标注的 `McpTool.readOnly`，落在解析查询上而非界面拦截，与远端自报、不可信的 `readOnlyHint` 严格区分；远端工具下线后同步标 `DEPRECATED` 而非删行（防 skill 绑定悬空）；SSRF 守卫复用现有 `SsrfGuard`，用自定义 `AddressResolverGroup` 把校验期解析出的 IP 钉进连接期，但**只能挂在 webflux 传输上**（httpclient 版基于 JDK `HttpClient`，不支持自定义 DNS 解析）；本期 SSE 传输暂不支持（MCP SDK 的 `WebFluxSseClientTransport` 已标 `@Deprecated(forRemoval=true)` 且缺 base-URL setter）。详见 `docs/superpowers/specs/2026-08-21-mcp-integration-design.md` 与 CLAUDE.md「MCP 接入」「改动 MCP 接入链路后必须手工验证」两节。
- [x] **OpenAPI Schema 自动解析生成工具表单**——现状是手工填 URL/method/inputSchema；tgo 支持丢一个 OpenAPI schema 自动生成交互表单，配置体验差距明显。
  **2026-08-23 完成**：租户粘贴文本或填 URL 导入一份 OpenAPI 3.x schema（JSON/YAML），解析出候选端点批量生成 `AiTool`，接入一套 REST API 的成本从「按端点数线性投入」降到「一次导入」。四组件职责单一：解析器（swagger-core，不引 `swagger-parser-v3`——它会自己 fetch 远程 `$ref`，是一条绕过 `SsrfGuard` 的出站路径）、映射层（**纯函数**，规则最密的部分）、URL 拉取（委托 `WebPageFetcher.downloadRaw`，复用网站知识库那套 SSRF 硬门槛而非另起一套）、编排落库。关键取舍：只解本地 `$ref`（带深度上限 + 循环检测防自引用结构栈溢出）、只导入 GET/POST（`RestClientHttpToolExecutor` 对非 POST 一律降级成 GET，硬导 PUT/PATCH/DELETE 会让语义错得无声无息）、工具名截断 64 而非 `AiTool.name` 列宽 100（OpenAI 兼容端点对函数名的硬限制）、导入是一次性的不与 schema 持续同步（与 MCP 的 `sync()` 刻意不同，复杂度与「降低首次接入成本」这一目标不成比例）。同批顺带修了一个既有缺陷：`HttpToolCallback.fillTemplate` 对 LLM 未填的占位符原样保留导致可选 query 参数一直是坏的，抽成独立纯函数 `UrlTemplateFiller` 后按 URL 两段分别处理（path 段缺值抛异常、query 段缺值整段删除）。⚠️ 已知限制：POST 时 path/query 参数会同时出现在 URL 与 body JSON 里（整个 toolInput 作 body）；header/cookie 参数与非 JSON 请求体无法表达，均记 warning 提示用户。详见 `docs/superpowers/specs/2026-08-23-openapi-tool-import-design.md` 与 CLAUDE.md「OpenAPI 工具导入」「改动 OpenAPI 工具导入链路后必须手工验证」两节。
- [ ] **多智能体编排** ⏸ **暂缓（2026-08-23 决定）**——tgo 是「配置多个 Agent、按业务场景选」，本项目是「一个应用一套 Prompt/策略配置」。这是**产品形态选择而非技术差距**，需先决定要不要走这条路；已决定暂不走，待产品形态明确后再重新评估。
- [x] **消息已读 / 送达状态**——tgo 有完整的已读未读 + 送达确认。
  ⚠️ **2026-08-11 更新：本条原来的立项理由已消失**。原描述是「`ChatMessage.isRead` 字段已建但全库无人读写（死字段），顺手补上用法即可」，而那个死字段已作为技术债第 4 项**删除**（提交 `cfb74da`，选择删字段而非补用法——留着一个没人写的字段比没有更糟）。现在要做这个功能等于**从零设计**：字段、两端渲染、坐席与访客双向的送达/已读时机、以及它与现有 SSE 实时通道的关系，都要重新定。性价比因此显著下降，重新评估后再决定要不要做。
  **2026-08-20 完成，范围有意收窄**：**只做已读、不做送达**——送达在本场景下几乎不携带信息（访客消息只要 HTTP 没报错就一定入库；唯一有信息量的「送到坐席那儿了吗」本质是坐席在不在线，是会话级状态而非消息属性，详见 spec §2.2）。**多坐席协作单独立项**：回执表按 `(session_id, reader_type, reader_id)` 建模，能容纳多个读者，但系统层面支持多坐席是会话模型的改动（`ChatSession.agentId` 单字段 → 关联表、`ChatMessage` 缺发送者字段等），与已读本身无关（spec §2.7）。落地：独立表 `MessageReadReceipt` 存双向水位线（坐席读访客 / 访客读坐席），带条件的原子 UPDATE 保证只增不减；坐席台新增「我的会话」列表（顺带修了接管第二个会话会丢第一个、未读徽章死代码两个既有缺陷）；SSE 独立 `read` 事件，不复用 `session`（后者对文案无条件展示，复用会产生视觉噪音）。详见 `docs/superpowers/specs/2026-08-19-message-read-receipt-design.md` 与 CLAUDE.md「改动已读回执链路后必须手工验证」一节。

### 5.3 大工程（暂不建议启动）

- [ ] 工作流 DAG 引擎（对标 tgo-workflow：LLM / API / 条件 / 工具节点）
- [x] **工具商店（能力包）**（原延后清单第 5 项，须在 MCP 与平台共享工具之后）
  **2026-08-28 完成**：平台管理员把自己已调通的一组工具 / MCP 服务 / 技能导出成能力包上架，凭据在导出时转成安装期变量；租户挑一个包、填自己的凭据、一键安装成**自己名下的副本**（可改可停用可删）。规则关在三个纯函数类里（导出 `PackageManifestBuilder` / 解析校验 `PackageManifestParser` / 变量填充 `PackageVariableApplier`），两个服务只做取数与落库。关键取舍：安装是**一次性复制**，装完与包断开（同 OpenAPI 导入，故**不建安装记录表**——有了它就会长出「已安装」这个会失真的状态）；安装分两段，段一事务内建行、段二事务外同步 MCP 工具清单并回填技能绑定，**段二失败不回滚段一**（有意的部分成功，界面须显眼提示需手动同步）；引用一律用包内 `key` 而非 id；manifest 里压根没有 `scope`/`tenantId`/`appId` 字段，安装时由服务端强制写入（「`scope` 是客户端可控字段」这条已真实栽过一次）。⚠️ 最险的一处是变量语法 `${VAR}` 与 `UrlTemplateFiller` 的 `{param}` 正则重叠——未替换的残留落在 query 段会被整段静默删除（鉴权参数消失 → 第三方 401 → 模型拿着错误响应继续编答案，全程无异常无日志），故替换后硬断言不残留 `${`。新增两道扫描哨兵：`PlatformEndpointGuardTest`、`PackageManifestFieldCoverageTest`。
  ⚠️ **「插件运行时」有意不做**（非目标而非欠账）：「让第三方扩展能力」已由 **MCP 接入**给出答案——第三方代码跑在对方那边，本项目只做协议客户端，安全边界是 `SsrfGuard` 而非本地沙箱；自建本地运行时等于把已推出去的风险重新拉回来。同样不做的还有版本升级、租户投稿上架、包分类/图标/评分，理由见设计文档 §1。
  详见 `docs/superpowers/specs/2026-08-25-tool-package-store-design.md` 与 CLAUDE.md「工具商店（能力包）」「改动工具商店 / 能力包链路后必须手工验证」两节。
  **真机手工验证 4/4 已于 2026-09-02 走完**（凭据出站用公开回显服务抓包核对：出站头是租户填的值、URL 无 `${` 残留、LLM 未填的可选 query 段被整段删除；MCP 包正常/坏 url 两条路径；两租户连做的上下文隔离；manifest 预览与密钥告警）。同轮顺带验到未声明变量在**发布阶段**即被拒、缺 appId 安装被拒、超长取值被长度校验拦下、导出拒绝 `tenantId != null` 的行四道防线均生效。**挖出两个缺陷已修**：导出对话框列出了后端必然拒绝的租户私有配置（前端缺 `tenantId == null` 过滤，判据与后端各写一次），以及三处错误文案把中文直接拼在英文字段名后。
- [ ] 多端 Widget SDK（iOS / Flutter / 微信小程序 / CLI）
  **第一步（协议层抽取 + CLI 调试客户端）已完成（2026-08-24）**：把 `widget/src` 里与浏览器无关的协议逻辑（鉴权请求头、SSE 解析、流式事件编排、会话持久化、Action URI 解析等）抽成 `widget/src/core/`，作为独立子项目 `cli/` 写了一个零运行时依赖的调试 CLI 作为第一个非浏览器消费者——它的价值不在工具本身，而在于逼出协议层里还粘着多少浏览器假设（测试跑在 vitest 的 node 环境下验证，jsdom 抓不出这类残留）。详见 `docs/superpowers/specs/2026-08-24-widget-core-extraction-design.md`，用法见 CLAUDE.md「CLI 调试客户端」一节。
  其余端**仍未启动**，各自的外部账号前置：iOS 原生需 Apple Developer Program 账号（证书/描述文件；若走 TestFlight 或 App Store 还需上架审核）；Flutter 若双端发布需同时具备 Apple Developer 与 Google Play Developer 账号；微信小程序需微信公众平台小程序账号（完整能力通常要求企业资质认证）及微信侧审核。这些账号与审核流程不在本仓库控制范围内，是各自独立立项时才需要处理的前置条件。
- 多渠道接入见 **4.2**（已暂缓，tgo 侧对应 tgo-platform，另支持 Telegram / Slack / 邮件）

### 5.4 工具方向的既有延后清单

见 `docs/superpowers/specs/2026-07-24-chat-tool-calling-design.md` §10（8 项）。截至 2026-08-04 的状态变化：
- 第 1 项**流式工具调用已完成**（2026-08-04，含 Spring AI 2.0 × Qwen 上游缺陷的受限降级）
- 第 4 项 ~~**MCP 接入仍缺**~~ ✅ **已完成（2026-08-21）**：详见 `docs/superpowers/specs/2026-08-21-mcp-integration-design.md`，条目见 5.2 节。
- 第 8 项 ~~**token 多轮聚合计量仍缺**~~ ⚠️ **已证伪，不要照它动手**（2026-08-11）
  原描述是「带工具时 usage 只反映末轮、存在少计，优先级应上调」。读 Spring AI 2.0 源码逐条核对后结论**相反**：非流式经 `UsageCalculator.getCumulativeUsage` 在工具多轮之间**本就逐项累加**，最终 response 的 usage 已是跨轮总量；流式取末值也正确。**按原描述改成累加会造成重复计费。** 残余风险窄到当前配置下不可能发生——仅在「流式且真发生多轮工具调用」时无跨轮累计，而本项目走不到（Qwen 分帧发 tool_call 会先把上游 `ChunkMerger` 打挂，`streamChat` 因此降级到非流式，而非流式是累计的）。**换供应商时必须重新评估**，验收只能靠真机。
- 第 6 项 ~~**平台共享工具/skill**~~ ✅ **已完成（2026-08-12，只做工具）**
  平台管理员维护共享 HTTP 工具库（`/v1/platform/tools`），租户建 skill 时可勾选使用，
  可用不可见凭据。**刻意不共享 skill**：它的 markdown 注入每一轮系统提示，平台加一条等于
  静默改掉所有租户的 Prompt 与回答行为，风险与收益不匹配。
  同批修掉一个既有漏洞：租户管理员此前能通过 `GET /v1/ai-models` 读到平台共享模型的明文
  `apiKey`。详见 `docs/superpowers/specs/2026-08-12-platform-shared-tools-design.md`。


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

**Phase 5.2 首项已合入**（2026-08-06）：站点爬取（网站知识库第二期）。**第二项已合入**（2026-08-20）：消息已读回执（只做已读不做送达，多坐席协作单独立项，见 5.2 小节）。**第三项已合入**（2026-08-21）：MCP 接入（远端工具转 `ToolCallback`，连接不缓存、只读门槛落在解析查询上，见 5.2 小节）。**第四项已合入**（2026-08-23）：OpenAPI Schema 自动解析生成工具表单（映射层纯函数、只解本地 `$ref`、只导入 GET/POST，见 5.2 小节）。

**Phase 5.3 已开工两项**：多端 Widget SDK 的**第一步**——协议层抽取 + CLI 调试客户端（2026-08-24，其余各端未启动且各有账号前置）；**工具商店（能力包）已整项合入**（2026-08-28，其中「插件运行时」是有意不做的非目标，见 5.3 小节）。剩下的工作流 DAG 引擎仍未启动。

**待办**：
- **4.2 多渠道接入 ⏸ 暂缓**——外部账号与公网回调等硬前置未具备，待条件成熟后作为独立专项启动（届时先做渠道抽象层技术选型）。
- **Phase 5.2 中期已全部完成**——站点爬取（2026-08-06）、消息已读回执（2026-08-20）、MCP 接入（2026-08-21）、OpenAPI Schema 自动解析生成工具表单（2026-08-23）均已合入；多智能体编排 ⏸ 已于 2026-08-23 决定暂缓（产品形态未定）。
- **Phase 5.4 工具方向**——第 6 项「平台共享工具/skill」已完成（2026-08-12，只做工具不共享 skill，见上）；第 8 项已证伪不必做。

> 各功能的完成日期与实现要点见上方 Phase 清单内注释，设计与实现细节见 `docs/superpowers/` 下对应的 spec / plan 文档。

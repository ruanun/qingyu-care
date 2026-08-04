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

- [ ] **多媒体消息（图片 / 文件）**
  现状：`ChatMessage` 只有 `content` 文本 + `uiSpec` 卡片，无附件模型。
  目标：用户与坐席可发图片/文件（客服日常刚需——用户发截图）。
  判断：三项里改动面最大（消息模型 + 两端渲染 + widget 上传交互），但存储侧 RustFS 已具备。

### 5.2 中期

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

**待办**：
- **4.2 多渠道接入 ⏸ 暂缓**——外部账号与公网回调等硬前置未具备，待条件成熟后作为独立专项启动（届时先做渠道抽象层技术选型）。
- **Phase 5 tgo 对标能力补齐**——近期三项：卡片 Action URI 协议 / 网站知识库 / 多媒体消息。

> 各功能的完成日期与实现要点见上方 Phase 清单内注释，设计与实现细节见 `docs/superpowers/` 下对应的 spec / plan 文档。

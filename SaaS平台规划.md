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

唯一遗留：**4.2 多渠道接入 ⏸ 暂缓**（微信公众号 / 企业微信 / 钉钉 / 飞书）——外部账号与公网回调等硬前置未具备，待条件成熟后作为独立专项启动（届时先做渠道抽象层技术选型）。

> 各功能的完成日期与实现要点见上方 Phase 清单内注释，设计与实现细节见 `docs/superpowers/` 下对应的 spec / plan 文档。

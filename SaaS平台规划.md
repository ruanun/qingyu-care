# 轻语 SaaS 平台规划

## 平台定位

面向企业客户的 AI 智能客服 SaaS 平台，支持多租户、多应用接入，提供问答助手、智能客服等能力。

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

> 2026-07-03 完成并合入 master：开放 REST API、JS Widget、Webhook 三个子项均已实现（含单元测试）。Widget 与 Webhook 的手工端到端联调待补。

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

## Phase 3: 管理后台重构 (接近完成)

> 2026-07-03 更新：平台配置 / 租户配置 / 共享模型池 / 系统监控（审计日志+健康概览）四项已完成并接入菜单路由；「接入配置」已随 Phase 2 完成（接入页 IntegrationPage：API Key / Widget 片段 / Webhook 配置）。仅剩「用量统计与账单」（延后）未做。详见 `docs/superpowers/specs/2026-07-02-phase3-admin-capabilities-design.md`。

### 3.1 平台级管理（超级管理员）
- [x] 租户列表与管理
- [x] 全局模型配置（共享模型池，租户可继承平台共享模型）
- [x] 系统监控与日志（操作审计日志 + 健康概览；完整可观测性栈未接入）
- [x] 平台配置（键值配置中心）

### 3.2 租户级管理（企业管理员）
- [x] 应用列表与管理
- [x] 团队成员管理
- [ ] 用量统计与账单（延后：用量统计 + 套餐额度，不接在线支付）
- [x] 租户配置（键值配置中心；部分配置项待接入生效）

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

### 4.2 多渠道接入
- [ ] 微信公众号
- [ ] 企业微信
- [ ] 钉钉
- [ ] 飞书

### 4.3 高级 RAG 能力
- [x] 多知识库联合检索（应用内 enabled 库自动联合，全局 top-K + IN 过滤，appId 双重隔离）
- [x] 混合检索（向量 + zhparser 中文关键词 FTS，RRF 融合，ChatStrategy 可配开关，fail-soft 降级）
- [x] 文档权限控制（PUBLIC/INTERNAL 两级 + 文档启用开关，按鉴权路径定级，等值过滤 + 存量回填）

### 4.4 分析与优化
- [ ] 对话质量评估
- [ ] 热点问题统计
- [ ] 知识库覆盖率分析
- [ ] A/B 测试

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
| 消息队列 | RabbitMQ / Redis Stream (待实现) |
| 文件存储 | RustFS (S3 兼容) |

---

## 实施优先级

1. **Phase 1** (多租户基础) → ✅ 已完成
2. **Phase 2** (接入能力) → ✅ 已完成（开放 API + Widget + Webhook）
3. **Phase 3** (管理后台) → ✅ 已完成（平台/租户配置、共享模型池、审计+概览、接入配置、用量统计看板均已合入）
4. **Phase 4** (高级功能) → 🔄 进行中（4.1 计费额度 ✅；4.3 高级 RAG 三项全部完成：多知识库联合检索 ✅、混合检索 ✅、文档权限控制 ✅；剩余：多渠道接入、分析与优化）

---

## 当前状态

**Phase 1 已完成的核心能力：**
- ✅ 多租户数据架构 (Tenant / Application / User)
- ✅ 自动化数据隔离 (Hibernate Filter + TenantDataAspect)
- ✅ Sa-Token 认证与权限控制
- ✅ 前端管理页面 (租户/应用/用户管理)
- ✅ 角色过滤导航菜单

**已有的业务功能（已适配多租户）：**
- ✅ RAG 知识库管理
- ✅ Prompt 模板管理
- ✅ 问答策略中心
- ✅ 多模型管理与降级
- ✅ 转人工规则
- ✅ 会话管理

**Phase 2 已完成的对外接入能力（2026-07-03）：**
- ✅ 开放 REST API（`/open/v1/**`，双路径鉴权：服务端 Api-Key / 浏览器 Origin 白名单）
- ✅ 接口限流（Bucket4j + Redis，fail-open）与用量计量（按应用 + 日期异步聚合）
- ✅ JS Widget 独立子项目（`widget/`，Shadow DOM 隔离，一行 script 嵌入，产物 `widget.js` 入库）
- ✅ Webhook 事件推送（事务后落库 → 异步投递 → HMAC-SHA256 签名 → 退避重试与定时重投）
- ⬜ Widget / Webhook 手工端到端联调（待补）

**Phase 3 已完成的管理能力（2026-07-02）：**
- ✅ 统一配置中心（`sys_config`，平台配置 + 租户配置一套机制）
- ✅ 共享模型池（平台级共享模型，租户可继承；顺带修复"平台默认模型被租户过滤挡住"的隐患）
- ✅ 操作审计日志（`@AuditLog` + AOP 异步落库、脱敏，超管全量/租户限本租户）
- ✅ 系统健康概览（Actuator 健康 + 关键计数）
- ✅ 菜单按角色显隐并接入新页面路由
- ✅ 接入配置（接入页 IntegrationPage：API Key / Widget 片段 / Webhook）
- ⬜ 用量统计与账单（延后）

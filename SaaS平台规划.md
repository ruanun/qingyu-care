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

## Phase 2: 接入能力

### 2.1 REST API 接入
- [ ] 标准化 API 设计（OpenAPI 规范）
- [ ] 认证方式：`X-App-Id` + `X-Api-Key`
- [ ] 核心接口：
  - `POST /v1/chat/completions` - 发起对话
  - `POST /v1/chat/stream` - 流式对话
  - `GET /v1/sessions/{id}` - 获取会话
  - `POST /v1/sessions/{id}/handover` - 请求转人工
- [ ] 接口限流与计量

### 2.2 JS Widget 嵌入
- [ ] 独立的 Widget SDK 项目
- [ ] 一行代码嵌入：`<script src="https://xxx/widget.js?app=xxx"></script>`
- [ ] 可配置样式（主题色、位置、尺寸）
- [ ] 支持自定义欢迎语、头像
- [ ] 移动端适配

### 2.3 Webhook 事件推送
- [ ] 事件类型：session_created, message_received, handover_requested, session_closed
- [ ] Webhook 配置管理
- [ ] 重试与日志

---

## Phase 3: 管理后台重构 (进行中)

### 3.1 平台级管理（超级管理员）
- [x] 租户列表与管理
- [ ] 全局模型配置（共享模型池）
- [ ] 系统监控与日志
- [ ] 平台配置

### 3.2 租户级管理（企业管理员）
- [x] 应用列表与管理
- [x] 团队成员管理
- [ ] 用量统计与账单
- [ ] 租户配置

### 3.3 应用级管理（每个机器人）
- [x] 知识库管理
- [x] Prompt 管理
- [x] 问答策略
- [x] 转人工规则
- [x] 模型配置（继承或覆盖）
- [ ] 接入配置（API Key、Widget 代码生成）
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
- [ ] 套餐定义（免费版/专业版/企业版）
- [ ] 用量计量（对话次数、文档数、存储量）
- [ ] 超额限制与告警

### 4.2 多渠道接入
- [ ] 微信公众号
- [ ] 企业微信
- [ ] 钉钉
- [ ] 飞书

### 4.3 高级 RAG 能力
- [ ] 多知识库联合检索
- [ ] 混合检索（向量 + 关键词）
- [ ] 文档权限控制

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
| Widget SDK | 待开发 (独立 Vite + TypeScript 项目) |
| 限流 | Bucket4j / Redis (待实现) |
| 消息队列 | RabbitMQ / Redis Stream (待实现) |
| 文件存储 | RustFS (S3 兼容) |

---

## 实施优先级

1. **Phase 1** (多租户基础) → ✅ 已完成
2. **Phase 2** (接入能力) → 📌 下一步重点
3. **Phase 3** (管理后台) → 🔄 进行中 (基础页面已完成)
4. **Phase 4** (高级功能) → 待规划

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

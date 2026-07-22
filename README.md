# 轻语 (QingYu Care)

<p align="center">
  <strong>🤖 面向企业的 AI 智能客服 SaaS 平台</strong>
</p>

<p align="center">
  基于 Spring Boot 3 + Spring AI + pgvector 构建，支持多租户、多应用、RAG 知识库问答与人工接管
</p>

---

## ✨ 功能特性

### 已实现
- ✅ **多租户架构** - 租户 / 应用 / 用户三级模型，`tenant_id` + `app_id` 两级数据隔离（Hibernate Filter + AOP 自动切面）
- ✅ **认证与权限** - 基于 Sa-Token，角色分为平台管理员 / 租户管理员 / 租户成员
- ✅ **知识库管理** - 支持 PDF/Word/Excel/PPT/HTML 等多种文档格式（Apache Tika 解析）
- ✅ **RAG 智能问答** - 基于 pgvector 向量检索的增强生成，多轮上下文对话
- ✅ **问答策略中心** - 动态配置 TopK、相似度阈值、兜底策略（数据库驱动）
- ✅ **Prompt 管理** - 模板版本管理与激活/回滚
- ✅ **多模型管理** - 多提供商模型配置，按场景优先级路由与降级
- ✅ **转人工规则** - 关键词 / 低置信度 / 连续未命中等多策略触发，含人工工作台
- ✅ **会话管理** - 会话记录、标签、摘要、超时自动关闭
- ✅ **配置中心** - 平台级 / 租户级两级键值配置（`sys_config`）
- ✅ **共享模型池** - 平台级共享模型，租户可继承或自建覆盖
- ✅ **操作审计日志** - 关键写操作自动记录（`@AuditLog` + AOP 异步落库、敏感字段脱敏）
- ✅ **系统概览** - 系统健康状态与租户/应用/用户/会话关键指标
- ✅ **对外接入** - 开放 REST API（`/open/v1/**`，服务端 `X-Api-Key` / 浏览器 Origin 白名单双路径鉴权，限流 + 用量计量）、JS Widget 一行嵌入（Shadow DOM 隔离）、Webhook 事件推送（Redis Stream 队列投递 + HMAC-SHA256 签名 + 退避重试，Redis 故障回退直投）
- ✅ **用量统计看板** - 开放 API 调用次数 / token 消耗趋势与聚合（按角色自适应平台 / 租户 / 应用视角）
- ✅ **计费与套餐额度** - 套餐额度映射（FREE/PRO/ENTERPRISE），调用 + token 双维度月度额度，超额 403 与近阈值告警，月度自然重置
- ✅ **高级 RAG** - 多知识库联合检索、混合检索（向量 + zhparser 中文关键词 FTS，RRF 融合）、文档权限控制（PUBLIC/INTERNAL 按鉴权路径定级）
- ✅ **分析与优化** - 对话质量评估、热点问题统计、知识库覆盖率分析（Widget 赞/踩反馈埋点 + rollup 日粒度预聚合看板）
- ✅ **A/B 测试** - 问答策略变体实验（开放流量会话级权重分桶、变体质量指标实时对比）

### 规划中
- ⏸ 多渠道接入（微信公众号 / 企业微信 / 钉钉 / 飞书）——暂缓，待外部账号与公网回调条件具备后启动

> 详细路线图见 [`SaaS平台规划.md`](./SaaS平台规划.md)。

## 🛠️ 技术栈

### 后端
| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | LTS 版本 |
| Spring Boot | 3.4.1 | 应用框架 |
| Spring AI | 1.0.0-M4 | AI 能力集成（OpenAI 兼容模式） |
| 通义千问 DashScope | - | 默认 LLM（`qwen-plus`）与向量模型（`text-embedding-v3`，1024 维） |
| PostgreSQL | 17 | 主数据库 |
| pgvector | 0.8+ | 向量存储扩展，HNSW 索引 + 余弦距离 |
| Sa-Token | 1.38.0 | 认证与权限 |
| Apache Tika | 2.9.1 | 文档解析 |
| X File Storage | 2.3.0 | S3 兼容对象存储（对接 RustFS） |
| Lombok | - | 样板代码简化（DTO 转换为手写静态工厂，未启用 MapStruct） |

### 前端
| 技术 | 版本 | 说明 |
|------|------|------|
| React | 19 | UI 框架 |
| TypeScript | 5.9 | 类型系统 |
| Vite | 7 | 构建工具 |
| Tailwind CSS + shadcn/Radix UI | - | 样式与组件库 |
| Zustand | 5 | 状态管理 |
| React Router | 7 | 路由 |

### 基础设施（`docker/docker-compose.yml`）
PostgreSQL 17（自定义镜像 `qingyu/pgvector-zhparser:pg17`，pgvector 向量检索 + zhparser 中文分词） · RustFS（S3 兼容对象存储） · Redis 7（限流 / Webhook 投递队列 Redis Stream / 缓存）

## 📁 项目结构

```
qingyu-care/
├── backend/                          # 后端服务 (Spring Boot)
│   ├── src/main/java/com/qingyu/care/
│   │   ├── annotation/              # 自定义注解（如 @AuditLog）
│   │   ├── config/                  # 配置类（含多租户 TenantContext/Aspect/Filter、AOP 切面）
│   │   ├── controller/              # REST 控制器（含 open/ 对外开放 API）
│   │   ├── dto/                     # 数据传输对象（含 open/ 开放 API DTO）
│   │   ├── entity/                  # JPA 实体
│   │   ├── enums/                   # 枚举类
│   │   ├── event/                   # 领域事件（Webhook 事件发布）
│   │   ├── exception/               # 异常处理
│   │   ├── repository/              # 数据访问层
│   │   ├── scheduler/               # 定时任务（会话超时等）
│   │   └── service/                 # 业务逻辑层（含 webhook/ 投递、impl/ 实现）
│   ├── src/main/resources/
│   │   ├── application.yml          # 主配置
│   │   ├── application-dev.yml      # 开发环境配置
│   │   ├── schema.sql               # 表结构参考文档（实际建表由 JPA 自动完成）
│   │   └── static/widget.js        # Widget 构建产物（由 widget/ 子项目构建后入库）
│   └── pom.xml
├── frontend/                         # 前端管理后台 (React + Vite)
│   └── src/{api,pages,components,layouts,stores,...}
├── widget/                           # 可嵌入网页的 Widget SDK（独立 Vite 子项目，产物 widget.js）
├── docker/                           # 基础设施 docker-compose 与初始化脚本
├── SaaS平台规划.md                   # 产品与技术路线图
└── README.md
```

## 🚀 快速开始

### 环境要求
- JDK 17+，Maven 3.9+
- Node.js 18+ 与 pnpm
- Docker & Docker Compose

### 1. 启动基础设施

```bash
cd docker
docker compose up -d   # 启动 PostgreSQL(pgvector) + RustFS + Redis
```

> `docker/init.sql` 会在 PostgreSQL 首次启动时自动 `CREATE EXTENSION vector`；
> 业务表由后端启动时通过 JPA `ddl-auto: update` 自动创建。

### 2. 配置 API Key

后端通过环境变量注入通义千问（DashScope）凭据，默认值见 `application.yml`：

```bash
export DASHSCOPE_API_KEY=your-dashscope-api-key
# 可选覆盖：
# export AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
# export AI_CHAT_MODEL=qwen-plus
# export AI_EMBEDDING_MODEL=text-embedding-v3
```

> 也可切换为任意 OpenAI 兼容端点，只需相应修改 `AI_BASE_URL` 与模型名。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

### 5. 访问服务

- 前端管理后台: http://localhost:5173
- 后端 API 文档 (Swagger): http://localhost:8080/api/swagger-ui.html
- 健康检查: http://localhost:8080/api/actuator/health

## 📡 主要接口

后端统一前缀 `/api`。完整接口以 Swagger 为准，核心模块：

| 模块 | 说明 |
|------|------|
| `AuthController` | 登录 / 注册 / 认证 |
| `ChatController` | 智能问答对话 |
| `KnowledgeController` | 知识库与文档管理 |
| `PromptController` / `ChatStrategyController` | Prompt 与问答策略 |
| `AiModelController` / `HandoverRuleController` | 模型配置与转人工规则 |
| `TenantController` / `ApplicationController` / `UserController` | 租户 / 应用 / 用户管理 |
| `DashboardController` | 统计仪表盘 |
| `AnalyticsController` / `BillingController` | 数据分析（用量 / 质量 / 热点 / 覆盖率）与套餐额度 |
| `open/*`（`/open/v1/**`） | 对外开放 API：对话 / 会话 / 反馈 / Widget 配置（独立鉴权与限流） |

## 🔧 核心配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `qingyu.care.session.timeout-minutes` | 30 | 会话超时时间（分钟） |
| `qingyu.care.session.max-history-messages` | 20 | 上下文最大历史消息数 |
| `qingyu.care.knowledge.chunking.default-chunk-size` | 1000 | 文档切分长度 |
| `qingyu.care.knowledge.chunking.default-overlap` | 200 | 切分重叠长度 |
| `spring.ai.vectorstore.pgvector.dimensions` | 1024 | 向量维度（需与 embedding 模型匹配） |

> 注意：TopK 与相似度阈值等检索参数已改为数据库动态配置（`chat_strategy` 表 / 问答策略页面）。

## 📊 开发路线图

```
Phase 1  多租户基础架构   ████████████ 已完成
Phase 2  对外接入能力     ████████████ 已完成（开放 API + Widget + Webhook）
Phase 3  管理后台         ████████████ 已完成（含用量统计看板）
Phase 4  高级功能         ███████████░ 已收口（计费额度 ✅ / 高级 RAG ✅ / 分析与优化 ✅[含 A/B 测试]；多渠道接入 ⏸ 暂缓）
```

## 📄 许可证

MIT License

---

<p align="center">
  Made with ❤️ by QingYu Team
</p>

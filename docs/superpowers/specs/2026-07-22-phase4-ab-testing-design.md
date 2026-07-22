# Phase 4.4 A/B 测试设计

> 日期：2026-07-22
> 作者：ruan
> 状态：设计已确认，待实施
> 前置：分析三件套（`2026-07-09-analytics-suite-design.md`）已合入 master(86f6892)

## 1. 背景与目标

Phase 4.4 的最后一项。为应用提供「问答策略 / Prompt 实验对比」能力：同一应用内把开放流量按权重分给 2~N 个策略变体，用已有的质量指标（赞踩 / 兜底 / 低置信 / 转人工 / 知识命中）对比各变体效果，由人工判断胜者。

**成功标准**：
- 租户管理员能在管理后台创建实验、启动、结束、查看变体对比报表；
- 开放流量（`/open/v1` API + Widget）新会话按权重分桶，整个会话固定变体；
- 实验链路任何异常都不阻断对话（fail-soft 回默认策略）；
- 对已合入的分析 rollup 体系零改动。

## 2. 需求决策（澄清结论）

| 决策点 | 结论 |
|--------|------|
| 实验对象 | **策略即变体**：一个变体 = 一条 `ChatStrategy`（含 topK/阈值/混合检索/兜底语）。顺带接线 `ChatStrategy.promptTemplateId`（字段已存在但 `buildMessages()` 未使用），使策略变体天然涵盖 Prompt 变体 |
| 分桶粒度 | **会话级随机**：新会话创建时按权重随机分桶，变体记在会话上，会话内固定 |
| 流量范围 | **仅开放流量**：`/open/v1` API + Widget 参与分流；管理后台「智能问答测试」恒走默认策略，不污染样本（沿用 token 计量「开放专属」注入先例） |
| 并发模型 | **同应用同时最多一个 RUNNING 实验**（可含 2~N 个变体），服务层启动时校验 |
| 报表深度 | **指标对比 + 样本量**，由人判断，不做显著性检验 |
| 报表取数 | **实时 SQL 聚合**（方案 A）：单应用实验流量小、可看当天数据、rollup 零改动 |

## 3. 数据模型

### 3.1 新实体 `AbExperiment`（表 `ab_experiment`）

继承 `BaseTenantEntity`（租户 + 应用双级隔离自动生效）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 主键 |
| `name` | String(100) | 实验名 |
| `description` | String(500) | 可选描述 |
| `status` | 枚举 `ExperimentStatus` | `DRAFT / RUNNING / ENDED` |
| `startedAt` / `endedAt` | LocalDateTime | 启动 / 结束时间 |
| `createdAt` / `updatedAt` | LocalDateTime | 时间戳 |
| `variants` | `@ElementCollection` | 变体集合，嵌入表 `ab_experiment_variant` |

变体 `@Embeddable AbVariant`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String(50) | 变体名（如 "A"、"B"），实验内唯一，报表分组键 |
| `strategyId` | Long | 绑定的 `ChatStrategy` id |
| `weight` | Integer | 流量权重（百分比整数） |

**状态机**：`DRAFT →(start)→ RUNNING →(end)→ ENDED`，不可逆、不可跳步。RUNNING 后变体锁定不可改。

**启动校验**（服务层，失败抛 `BusinessException`）：
- ≥2 个变体，变体名不重复；
- 权重为 0~100 的整数、和恰为 100（**允许单个变体权重为 0**——该变体不接流量，用于软下线与 IT 确定性断言）；
- 每个 `strategyId` 对应策略存在且 `enabled`（走 JPA 查询，租户过滤天然限定本租户可见范围）;
- 当前应用范围内无其他 RUNNING 实验。

### 3.2 `ChatSession` 增加三个可空字段

| 字段 | 说明 |
|------|------|
| `experimentId` | 参与的实验 id；null = 未参与 |
| `variantName` | 变体名，报表分组用 |
| `variantStrategyId` | 变体绑定的策略 id，对话取策略免 join 实验定义 |

`ddl-auto: update` 自动加列，存量数据全 null，无迁移。

## 4. 分流与对话链路

### 4.1 分流时机（仅开放流量）

- `ChatRequest` 增加内部字段 `openChannel`（boolean，默认 false）；`OpenChatController` 在 `OpenChatRequest → ChatRequest` 转换处置 true。**管理后台 `ChatController` 侧强制覆写为 false**（该 DTO 也从请求体绑定，防手工构造 JSON 混入分流污染样本），并对该字段加 `@JsonIgnore` 或转换层剥离，双保险。
- `ChatServiceImpl.createNewSession()`：若 `openChannel` 为 true，调 `AbTestService.assign()`——查当前应用 RUNNING 实验，存在则按权重随机选变体，返回 `(experimentId, variantName, variantStrategyId)` 落在新会话上；无实验或任何异常返回 null，会话不参与实验。
- 随机源可注入（构造器注入 `Random` 或 supplier），单测可控。

### 4.2 对话时取策略

`chat()` / `streamChat()` 把 `chatStrategyService.getActiveStrategy("default")` 替换为 `resolveStrategy(session)`：

1. 会话 `experimentId` 非空 → 查实验：仍 `RUNNING` → 按 `variantStrategyId` 走 JPA `findById` 取策略（租户过滤防跨租户），策略存在且 enabled → 使用之；
2. 其余一切情形（未参与实验 / 实验已结束或被删 / 策略被禁用或删除 / 异常）→ 回退 `getActiveStrategy("default")`，异常路径记 warn 日志。

实验一结束，存量会话下一条消息立即回归默认策略。

### 4.3 promptTemplateId 接线（闭合既有缺口）

`buildMessages()` 增加 `ChatStrategy` 参数：
- `strategy.promptTemplateId` 非空 → `promptTemplateService.getById(id)` 取该模板内容；取不到（被删）fail-soft 回退全局激活模板 + warn；
- 为空 → 维持现状 `getActiveTemplateContent()`。

此接线对非实验流量同样生效（策略绑了模板就用），语义与字段注释一致，属修复而非行为破坏。

### 4.4 策略删除保护

`ChatStrategyService.deleteStrategy()` 增加校验：被 RUNNING 实验引用的策略拒绝删除（`BusinessException`）。DRAFT 实验引用的策略可删，启动校验会兜住。

## 5. 报表口径

`AbTestService.getReport(experimentId)`：
1. 先 JPA `getById(experimentId)`——租户过滤天然校验归属（越权看不到，404）；
2. 再 native SQL 按 `variant_name` 分组聚合（native 不受 Hibernate 过滤器保护，但入参 `experiment_id` 已经 JPA 校验过归属，安全）：

**会话级**（`chat_session where experiment_id = ?`）：
- 会话数、转人工数（`handover_time` 非空）、AI 解决数（`ENDED` 且 `agent_id` 为 null）、已结束数。

**消息级**（`chat_message join chat_session`）：
- 提问数（role=USER）、AI 回复数（role=ASSISTANT 且置信度非空）、平均置信度、低置信数（`confidence < 0.5`，与 `StatQualityDaily` 同口径）、兜底数（`fallback = TRUE`）、命中知识数（`hit_document_ids` 非空）、赞数 / 踩数。

**DTO**：每变体一行——样本量（会话数、提问数、AI 回复数）+ 派生率（赞踩率、兜底率、低置信率、转人工率、知识命中率、AI 解决率）+ 平均置信度。派生率分母口径与分析三件套一致，除零时率为 null。会话已打标，不再卡时间窗。

## 6. 管理 API

新 `AbTestController`（`/ab-experiments`，应用级，需 `X-App-Id`；写操作加 `@AuditLog`）：

| 端点 | 说明 |
|------|------|
| `GET /ab-experiments` | 实验列表（本应用） |
| `POST /ab-experiments` | 创建（初始 DRAFT） |
| `PUT /ab-experiments/{id}` | 编辑（仅 DRAFT 可改） |
| `POST /ab-experiments/{id}/start` | 启动（做 §3.1 全部校验） |
| `POST /ab-experiments/{id}/end` | 结束 |
| `GET /ab-experiments/{id}/report` | 变体对比报表 |
| `DELETE /ab-experiments/{id}` | 删除（仅 DRAFT / ENDED） |

校验失败抛 `BusinessException`（项目惯例：HTTP 200 + code 400）。

## 7. 前端

新 `AbTestPage`（`<RequireApp>` 包裹，导航挂数据统计组旁，`api/abtest.ts` 走统一 axios 实例）：
- **实验列表**：名称 / 状态徽章（DRAFT 灰 / RUNNING 绿 / ENDED 中性）/ 起止时间 / 操作（启动、结束、报表、编辑、删除，按状态显隐）；
- **创建 / 编辑对话框**：实验名 + 描述 + 变体行（策略下拉复用现有策略 API + 权重整数输入），前端校验权重和为 100、≥2 变体；
- **报表视图**：变体对比表格（行 = 指标，列 = 变体），表头带样本量；shadcn 风格，无图表。

## 8. 错误处理原则

实验链路全程 fail-soft，任何异常不阻断对话：
- 分桶失败 → 会话不参与实验，走默认策略；
- 变体策略失效（禁用 / 删除）→ 回默认策略 + warn；
- 实验被删（ENDED 后允许删）→ 存量会话标记悬空，resolve 查不到实验自动回默认，无害（已知取舍）；
- 报表 / 管理端错误正常抛业务异常，管理端可见。

## 9. 测试策略

按项目两层约定（Surefire 纯 Mockito 单测 / Failsafe Testcontainers IT）：

**单测**：
- `AbTestServiceTest`：权重分桶（注入可控随机源，验证边界 0/100 与区间落点）、启动校验各分支（变体数 / 权重和 / 策略失效 / 已有 RUNNING）、状态机非法迁移、`resolveActiveVariantStrategy` 回退分支（无实验 / 已结束 / 策略失效）；
- `ChatServiceImplTest` 补：`buildMessages` promptTemplateId 接线三分支（绑定有效 / 绑定失效回退 / 未绑定）；
- 报表 DTO 派生率计算（除零边界）。

**IT（`AbTestFlowIT`，继承 `AbstractPostgresIT`）**：
- 全链路：建实验 → 启动 → 开放路径新会话分桶落标（权重 100/0 保证确定性）→ 对话走变体策略 → 报表聚合数字正确 → 结束实验后回归默认策略；
- 多租户隔离：A 租户实验对 B 租户不可见、不分流；
- 后台入口（非开放路径）不分流；
- 播种注意既有坑：IT 播种配置须 tenantId + appId 双设（appFilter 无 NULL 兜底）。

## 10. 非目标与已知取舍

- **不做**：显著性检验、多实验并行 / 分层、用户级稳定分桶、按实验维度扩展 rollup、自动应用胜者（结束后由人工手动把胜出策略设为默认，现有策略页已支持）；
- **取舍**：流式 AI 消息不落 `tokenUsage` 的既有局限不变，token 相关指标不进报表；关键词命中置信度为 0 的既有局限（见 `resolveConfidenceScore`）沿袭，对各变体公平不影响对比；
- **实验期间改动变体所绑策略的参数**（策略本身仍可编辑）会即时影响该变体后续对话——不做快照，文档口径：实验期间请勿改动参与实验的策略。

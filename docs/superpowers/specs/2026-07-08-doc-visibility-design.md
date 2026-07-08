---
title: 文档权限控制（可见性分级 + 文档开关）设计
author: ruan
date: 2026-07-08
status: 已实现
---

# 文档权限控制（可见性分级 + 文档开关）设计

Phase 4.3「高级 RAG 能力」最后一个子项目（混合检索、多知识库联合检索已落地）。在 `RetrievalScopeResolver` 预留的扩展点上，把检索范围进一步收窄到「当前调用方有权可见的文档集」。

## 1. 背景与范围

现状：检索范围已按「租户 + 应用 + enabled 知识库」过滤（多知识库联合检索，2026-07-08 落地），但**文档粒度没有任何控制**——同一知识库里的所有文档对所有渠道一视同仁：Widget 匿名访客能检索到的内容与持 API Key 的服务端完全一样，也没有办法临时下线单个文档（只能删除，重新上线需重新嵌入）。

**范围**：
- **可见性分级**：文档标 `PUBLIC`（默认，对所有渠道可见）或 `INTERNAL`（仅受信调用可见）；调用方级别**按鉴权路径**判定，不可伪造。
- **文档级启用开关**：`enabled=false` 的文档不参与检索；不删 chunk，重新启用免重嵌入。
- **管理入口**：后端接口 + 前端知识库文档列表的可见性切换与启用开关。
- **顺带收尾历史债**：摄取时 `appId` 缺上下文写成空串的存量 chunk，借本期的幂等回填 runner 按 `documentId` 反查回填。

**非目标（YAGNI）**：
- 三级以上有序级别、自由标签集（两级枚举可后向扩展，等真实需求）。
- 端用户体系 / 按 `userId` 定级（对话端 userId 是调用方自报的自由字符串，不可信）。
- 服务端请求主动降级参数（`accessLevel` 请求字段，本期用不上）。
- 租户内成员对库/文档的管理 ACL（后台权限体系，另行立项）。
- 知识库级可见性默认值（文档级已够用）。

## 2. 需求决策（已与用户确认）

| 决策点 | 结论 |
|---|---|
| 核心语义 | **可见性分级 + 文档级启用开关都做**（同为检索侧文档粒度过滤，实现同渠道） |
| 分级模型 | **两级枚举 `PUBLIC`/`INTERNAL`**，文档默认 PUBLIC，存量零迁移 |
| 级别归属 | **按鉴权路径**：服务端 API Key（持密钥即受信）→ 可见 PUBLIC+INTERNAL；Widget/Origin（浏览器匿名）→ 仅 PUBLIC；管理后台（已登录成员）→ PUBLIC+INTERNAL。请求字段可被访客伪造，故不采用 |
| 过滤实现 | **metadata 为准 + 变更同步**（方案 A）：chunk metadata 写 `visibility`/`docEnabled`，管理端变更时 native `jsonb_set` 同步；不用 documentId IN 列表（文档数成百上千时表达式爆炸、每检索多一次全量查询） |

## 3. 关键设计约束：缺键语义陷阱

Spring AI filter 表达式（及关键词路 SQL）里对 metadata 缺键行做 `!=` 判断时，SQL 语义是 `NULL != 'x' → NULL → false`——**缺键 chunk 会被静默筛掉**。因此本设计统一采用：

> **摄取写全键 + 启动回填存量 + 检索一律等值条件**

- 新 chunk 摄取时必写 `visibility`、`docEnabled` 两键；
- 存量 chunk 由幂等启动 runner 一次性回填；
- 检索条件只用 `==`（`docEnabled == 'true'`、`visibility == 'PUBLIC'`），不依赖缺键行为。

**连带影响**：所有直接向 `vector_store` 播种的既有 IT（`HybridRetrievalIT`/`KeywordSearchDaoIT`/`MultiKbRetrievalIT`）种子 metadata 必须补全新键，否则被等值条件筛掉——列为高风险适配任务（同多知识库联合检索的经验）。

## 4. 架构

### 4.1 数据模型
- `enums/DocumentVisibility`（新）：`PUBLIC` / `INTERNAL`。
- `Document` 加字段：`visibility`（`@Enumerated(STRING)`，默认 PUBLIC）、`enabled`（Boolean，默认 true）。`ddl-auto: update` 自动加列；存量行为 null → 读取处 null 安全（null 视为 PUBLIC / 启用）。

### 4.2 调用方级别判定（TenantContext 扩展）
- `TenantContext` 加第 4 个 ThreadLocal `ACCESS_LEVEL`（类型 `DocumentVisibility`，语义=调用方最高可见级），`clear()` 一并清理。
- `OpenApiAuthInterceptor`：API Key 路径鉴权通过 → set INTERNAL；Origin/Widget 路径 → set PUBLIC。
- `TenantInterceptor`（管理后台，已登录）→ set INTERNAL。
- 任何路径未设置（null）→ `RetrievalScopeResolver` 兜底按 **PUBLIC**（最小权限）。

### 4.3 检索链路（RetrievalScope 扩展）
- `RetrievalScope` record 加组件 `accessLevel`（`DocumentVisibility`）；`empty()`、既有构造调用点、单测同步适配。
- `toFilterExpression()` 追加：` && docEnabled == 'true'`；当 `accessLevel == PUBLIC` 再追加 ` && visibility == 'PUBLIC'`。
- `KeywordSearchDao.search` SQL 追加同语义条件：`AND metadata->>'docEnabled' = 'true'`；PUBLIC 级再加 `AND metadata->>'visibility' = 'PUBLIC'`。
- `RetrievalScopeResolver.resolve()`：从 `TenantContext` 读 accessLevel（null→PUBLIC）填入 scope。其余逻辑不变。

### 4.4 摄取与同步
- `KnowledgeServiceImpl.processDocument`：chunk metadata 增写 `"visibility"`（文档当前值，null 视为 PUBLIC）与 `"docEnabled"`（`"true"`；上传即处理的文档必然启用）。
- `VectorStoreRepository` 加两个 native update（有 `deleteByKnowledgeBaseId` 先例）：
  ```sql
  UPDATE vector_store SET metadata = jsonb_set(metadata, '{visibility}', to_jsonb(:v))
  WHERE metadata->>'documentId' = :documentId
  -- docEnabled 同构
  ```
- `KnowledgeService` 加「更新文档可见性 / 启用状态」方法：改 `Document` 行 + 同步 chunk metadata，同一事务；`@AuditLog` 记审计。

### 4.5 存量回填（DocMetadataBackfillRunner，新）
幂等 `ApplicationRunner`（`@Order` 排在 `HybridSearchSchemaInitializer` 之后，失败仅 `log.warn` 不阻断启动）：
1. `visibility` 缺键 → 回填 `"PUBLIC"`；`docEnabled` 缺键 → 回填 `"true"`；
2. `appId` 空串/缺失 → 按 `metadata->>'documentId'` 反查 `document` 表回填 `appId`/`tenantId`（历史债收尾；反查不到的孤儿 chunk 保持原样并记 warn 数量）。

### 4.6 管理入口
- 后端 `KnowledgeController`：两个独立端点 `PUT /knowledge/documents/{id}/visibility` 与 `PUT /knowledge/documents/{id}/enabled`（PUT 对齐既有 `PUT /{id}/enabled` 知识库开关惯例；各自语义单一、审计粒度清晰）。
- 前端 `KnowledgePage` 文档列表：可见性徽章切换（PUBLIC/INTERNAL）+ 启用开关；`api/knowledge.ts` 加对应方法。

### 4.7 数据流
```
请求 → 鉴权拦截器写 TenantContext.accessLevel（API Key/后台=INTERNAL，Widget=PUBLIC，缺省→PUBLIC）
  → RetrievalScopeResolver.resolve()（tenant+app+kbIds+accessLevel）
  → 两路检索条件：tenant+app+kbIds IN + docEnabled=='true' [+ visibility=='PUBLIC']
```

## 5. 错误处理

- 回填 runner 失败 → warn 不阻断启动（下次启动重试，幂等）。
- 变更同步（jsonb_set）失败 → 与文档行更新同事务回滚，管理端收到错误（不允许 Document 行与 metadata 不一致的静默状态）。
- 检索链路 fail-soft 基调不变（最坏空命中 + fallback）。

## 6. 影响面

| 文件 | 改动 |
|---|---|
| `enums/DocumentVisibility.java` | **新增** |
| `entity/Document.java` | 加 `visibility`/`enabled` |
| `config/TenantContext.java` | 加 `ACCESS_LEVEL` ThreadLocal |
| `config/TenantInterceptor.java`、`OpenApiAuthInterceptor`（开放 API 鉴权拦截器，具体路径以实现计划核实为准） | 写入 accessLevel |
| `service/RetrievalScope.java` | 加 `accessLevel` 组件 + 表达式条件 |
| `service/RetrievalScopeResolver.java` | 读 accessLevel（null→PUBLIC） |
| `repository/KeywordSearchDao.java` | SQL 加 docEnabled/visibility 条件 |
| `repository/VectorStoreRepository.java` | 加两个 jsonb_set native update |
| `service/impl/KnowledgeServiceImpl.java` + `service/KnowledgeService.java` | 摄取写新键；更新可见性/启用方法 |
| `config/DocMetadataBackfillRunner.java` | **新增**：幂等回填（含 appId 历史债） |
| `controller/KnowledgeController.java` | PATCH 接口 |
| `frontend/src/pages/KnowledgePage.tsx`、`frontend/src/api/knowledge.ts`、`frontend/src/types/index.ts` | 可见性切换 + 启用开关 |
| 既有 IT 种子（3 个文件） | **适配**：metadata 补 `visibility`/`docEnabled` 键 |

## 7. 测试

- **单测（Mockito，免 Docker）**：
  - `RetrievalScope.toFilterExpression()`：INTERNAL 级（只加 docEnabled）与 PUBLIC 级（再加 visibility）两分支。
  - `RetrievalScopeResolver`：accessLevel 传导 + null→PUBLIC 兜底。
  - `KnowledgeService` 更新方法：文档行变更 + 同步调用（mock 验证）。
- **IT（Testcontainers，`DocVisibilityRetrievalIT`）**：
  - PUBLIC 级调用检不到 INTERNAL 文档 chunk；INTERNAL 级能检到两类。
  - 禁用文档不参与检索；重新启用后恢复（metadata 同步生效）。
  - 回填 runner 幂等：种缺键/appId 空串行 → 手动触发回填 → 键补全且二次执行无副作用。
- **既有 IT 适配**（高风险任务单列）：3 个 IT 的 seed 辅助补全新键后全绿。
- 前端 `pnpm build && pnpm lint` 无新增错误。
- `mvn test` 免 Docker、`mvn verify` 全绿。

## 8. 关键决策与风险

- **等值条件而非缺键判断**（§3）：多一个回填 runner，换来过滤语义确定性；这是本设计最重要的工程决策。
- **按鉴权路径定级**：零新参数、不可伪造；代价是同一 App 的 Widget 与服务端天然不同级（这正是需求本意）。
- **变更同步与文档行同事务**：保证一致性；vector_store 无 Hibernate 实体，native update 参与 Spring 事务（同一 DataSource）没有障碍。
- **行为变化面≈0**：存量与新上传文档默认 PUBLIC+启用，任何渠道行为不变；只有显式标 INTERNAL/禁用后才产生差异。
- **关键词路命中置信度仍为 0** 的既有局限不受本期影响（与权限无关）。

## 9. 验收标准

- Widget（Origin 鉴权）对话检索不到 INTERNAL 文档；持 API Key 的服务端调用与管理后台聊天能检索到。
- 文档禁用后立即不参与检索（两路），重新启用后恢复，无需重新上传/嵌入。
- 管理页可切换文档可见性与启用状态，操作留审计日志。
- 存量 chunk 经启动回填后具备全键；appId 空串历史 chunk 被回填（孤儿除外）。
- 存量部署升级后默认行为无变化。
- `mvn test` / `mvn verify` 全绿，前端构建与 lint 通过。

## 10. 实现结论（2026-07-08 落地）

Task 1-9 全部完成，`mvn verify` 全绿（Surefire 单测 **109**、Failsafe IT **54**，合计 **163**，0 失败 0 错误）。提交序列：

`1d3e431`（枚举+Document 字段+TenantContext 级别）→ `d1f2dd9`（RetrievalScope 4 组件+等值条件；顺带 Resolver 占位与 KeywordSearchDaoIT 编译适配）→ `9945576`（级别按鉴权路径写入）→ `77e54fc`（关键词路条件+三 IT 种子补键）→ `e00fcad`+`6854122`（摄取全键+回填 runner；评审修复见下）→ `e7eee5c`（管理端接口）→ `1e770da`（DocVisibilityRetrievalIT 4 用例）→ `b5610ef`+`a23e494`（前端入口+toast）→ `fd4cec9`（本次收尾：拦截器访问级别单测）。

### 10.1 与设计的偏差/实现发现

1. **`vector_store.metadata` 列实为 `json` 而非 `jsonb`**（反编译 Spring AI M4 jar 证实；`schema.sql` 注释误写 JSONB）——所有 `jsonb_set` 必须 `metadata::jsonb` 显式转换；Task 5 踩坑并修正，Task 6 预防性应用。
2. **评审抓到的 Critical**：回填 runner 的 appId 反查最初只防 `d.app_id IS NOT NULL` 未防 `tenant_id`——psql 实测 `jsonb_set` 第三参 NULL 会把**整行 metadata 置空且不可自愈**（`documentId` 也没了）。已补对称防护 + 防回归 IT 用例。教训：**对 json/jsonb 列做 `jsonb_set` 时，凡 new_value 可能为 NULL 的来源列都必须显式 `IS NOT NULL` 防护**。
3. `docEnabled` 摄取从设计的硬编码 `"true"` 改为读 `doc.getEnabled()` null 安全——否则「重新处理已禁用文档」会静默重新启用（`reprocessDocument` 复用 `processDocument`）。
4. 回填 runner 按设计补了孤儿 chunk 统计 warn。
5. IT 用 `DocumentType.TEXT`（枚举无 TXT）。
6. 拦截器访问级别分支的单测由本收尾提交补上（评审指出的缺口）：`backend/src/test/java/com/qingyu/care/config/OpenApiAuthInterceptorAccessLevelTest.java` 三用例（apiKey→INTERNAL、Origin→PUBLIC、鉴权失败不留级别）全绿。`TenantInterceptor` 的登录分支（`StpUtil.isLogin()` 决定是否写 INTERNAL）**未做单测**——并非缺 `mockStatic` 能力（项目里 `TenantDataAspectTest` 已用 `mockStatic(StpUtil.class)` 验证过该能力可用），而是本次收尾任务的显式交付范围只列了 `OpenApiAuthInterceptorAccessLevelTest` 一个文件；`TenantInterceptor` 覆盖留作后续可选补项。

### 10.2 遗留观察（评审留档，不阻塞）

- `TenantInterceptor` 注册在 `/**` 且早于 `OpenApiAuthInterceptor`——`/open/v1/**` 上两者叠加的正确性隐式依赖注册顺序（当前无漏洞：开放拦截器两分支都无条件覆盖级别，失败则整体 clear）；后续可把 `/open/v1/**` 从 `TenantInterceptor` 排除或补顺序回归测试。
- `KnowledgeServiceImpl` 两个同步方法未记录受影响 chunk 行数日志；`getDocument` 沿用裸 `RuntimeException`（404 场景返 500）——均为既有惯例延续。
- 前端连点无防抖（管理后台低频操作，可接受）。

### 10.3 实测验证

- `mvn test`：Surefire **109** 个测试，0 失败 0 错误，BUILD SUCCESS。
- `mvn verify`：Surefire **109** + Failsafe **54**，合计 **163**，0 失败 0 错误，BUILD SUCCESS。日志中出现的向量检索 `401`（DASHSCOPE 凭据在测试环境未配置）触发 `HybridRetrievalServiceImpl` 向量路 fail-soft 退化为关键词/空、`RateLimitService`/`QuotaService` 因 Redis/DB 不可达时的 fail-open 放行、`RetrievalScopeResolver` 查询异常 fail-soft 为空范围等 WARN，均为既有 fail-soft/fail-open 设计的预期行为，非测试缺陷。`DocVisibilityRetrievalIT` 4 个用例全部通过（其中 3 个用例内部的向量检索因同样原因退化为关键词路，属预期，不影响可见性过滤断言）。

### 10.4 行为变化提示

- 存量/新文档默认 PUBLIC+启用，升级后行为不变；显式标 INTERNAL 后 Widget 匿名渠道立即不可检索。
- 禁用文档两路（向量/关键词）立即隐身，重新启用免重嵌入立即恢复。

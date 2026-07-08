---
title: 文档权限控制（可见性分级 + 文档开关）设计
author: ruan
date: 2026-07-08
status: 已评审
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

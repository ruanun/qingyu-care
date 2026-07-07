---
title: 多知识库联合检索 设计
author: ruan
date: 2026-07-07
status: 已评审
---

# 多知识库联合检索 设计

Phase 4.3「高级 RAG 能力」第二个子项目（混合检索已于 2026-07-06 落地）。文档权限控制是同层级的独立子项目，排在本项目之后。

## 1. 背景与范围

数据模型早已支持多知识库：`KnowledgeBase`（带 `enabled` 开关）→ `Document` → 向量 chunk，chunk metadata 摄取时已写入 `knowledgeBaseId` / `appId` / `tenantId` / `documentId`。但**检索端完全没有「范围」概念**：

- 纯向量路与混合检索路都只按 `metadata.tenantId` 过滤——实际是「整租户所有知识库无差别盲搜」；
- 连 `appId` 都未过滤：**同租户下 A 应用能检索到 B 应用的知识（隔离缺口）**；
- `KnowledgeBase.enabled` 开关不参与检索；
- `ChatRequest.knowledgeBaseId` / `OpenChatRequest.knowledgeBaseId` 字段存在但全链路无人消费，前端 `ChatPage.tsx` 还写死 `knowledgeBaseId: 1`（死代码）。

**范围**：给检索链路补上「检索范围」概念——检索从「租户全量盲搜」变为「当前应用下所有 enabled 知识库的联合检索」，顺带闭合 appId 隔离缺口、清理死代码。

**非目标（YAGNI）**：
- 按场景/请求精确指定知识库列表（`ChatStrategy.knowledgeBaseIds` 或请求参数）——enabled 开关已满足范围控制，需要时再加。
- 每库独立 top-K 配额、按库加权。
- 文档权限控制（4.3 独立子项目，本设计的 `RetrievalScopeResolver` 为其预留扩展点）。
- 跨应用/跨租户共享知识库。

## 2. 需求决策（已与用户确认）

| 决策点 | 结论 |
|---|---|
| 范围归属 | **知识库 `enabled` 开关**：自动联合当前应用下所有 `enabled=true` 的库；管理员在知识库列表页勾选即控制范围，无新配置面 |
| 联合语义 | **全局 top-K + IN 过滤**：一次检索，过滤条件加 `knowledgeBaseId IN (启用集合)`，全局按相似度/RRF 取 top-K；不保证每库有代表 |
| appId 缺口 | **修，双重保险**：库列表按显式 appId 条件查询天然闭合缺口；向量/关键词两路再显式加 `appId` 过滤作第二道防线 |
| 无应用上下文 | **严格：不检索**：返回空命中、走 `fallbackMessage` 语义。前端聊天页本就要求先选应用、开放 API 必带 `X-App-Id`，实际影响面≈0 |

## 3. 架构

### 3.1 `RetrievalScope`（新，值对象）
```java
record RetrievalScope(Long tenantId, Long appId, List<Long> kbIds) {
    boolean isEmpty() { return appId == null || kbIds == null || kbIds.isEmpty(); }
}
```

### 3.2 `RetrievalScopeResolver`（新，`service/` 下单个 `@Service`）
遵循「简单服务不拆接口」惯例。职责：解析当前请求的合法检索范围。

1. 从 `TenantContext` 取 `tenantId` / `appId`；**`appId == null` → 空范围**（严格语义，同时规避平台管理员绕过 Hibernate Filter 导致库列表跨租户泄漏的问题）。
2. `KnowledgeBaseRepository.findByAppIdAndEnabledTrue(appId)`（**显式 appId 派生查询**，不只依赖 TenantDataAspect 的隐式过滤——双重保险第一道）取 enabled 库 id 集合。
3. 查询异常 → fail-soft 空范围 + `log.warn`，不阻断对话。

后续「文档权限控制」在此组件上叠加（scope 再收窄到有权文档集），是天然扩展点。

### 3.3 检索链路接入
- `ChatServiceImpl.searchKnowledge(query, strategy)`：先 `resolver.resolve()`；**空范围短路返回 `List.of()`**（省掉 embedding 调用），走既有 `allowNoKnowledge` / `fallbackMessage` 语义；非空传给两条路。
- `HybridRetrievalService.search` 签名：`(query, tenantId, strategy)` → **`(query, scope, strategy)`**。
- **向量路** filterExpression：`tenantId == 'T' && appId == 'A' && knowledgeBaseId in ['1','2',...]`（tenantId 为 null 时省略该子句，同现状；metadata 值均为字符串）。拼接逻辑抽成纯静态方法（如 `RetrievalScope#toFilterExpression`），可免容器单测。
- **关键词路** `KeywordSearchDao.search(query, scope, limit)`：SQL 追加
  ```sql
  AND metadata ->> 'appId' = ?
  AND metadata ->> 'knowledgeBaseId' IN (?, ?, ...)   -- 按 kbIds 数量动态占位
  ```
- **纯向量路**（`hybridEnabled=false`）应用同样的 scope 过滤，两条路径行为一致。

### 3.4 数据流
```
chat 请求 → ChatStrategy → RetrievalScopeResolver
  ├─ 空范围（无 app / 无 enabled 库 / 查询异常）→ 空命中 → fallback 语义
  └─ 非空 → hybrid（向量+关键词，两路均带 tenant+app+kbIds 过滤）或纯向量
           → RRF/top-K → buildContext
```

chunk metadata 摄取时已含 `knowledgeBaseId`/`appId`，**无需数据迁移**；无 `ChatStrategy` 变更、无新配置面。

## 4. 死代码清理

- 删除 `ChatRequest.knowledgeBaseId`、`OpenChatRequest.knowledgeBaseId`（从未被消费；Spring Boot Jackson 默认忽略未知字段，开放 API 老调用方传了也不报错，向后兼容）。
- 删除 `OpenChatController` 中对应映射行。
- 删除前端 `ChatPage.tsx` 写死的 `knowledgeBaseId: 1` 与 `types/index.ts` 对应字段。

## 5. 错误处理

全链 fail-soft 保持既有基调：
- resolver 查询异常 → 空范围（= 空命中 + fallback）；
- 向量/关键词两路各自 try/catch 不变；
- 最坏情况 = 空命中 + `fallbackMessage`，**永不阻断对话**。

## 6. 影响面

| 文件 | 改动 |
|---|---|
| `service/RetrievalScope.java` | **新增**：范围值对象（record）+ filterExpression 拼接纯函数 |
| `service/RetrievalScopeResolver.java` | **新增**：范围解析（TenantContext + enabled 库查询，fail-soft） |
| `repository/KnowledgeBaseRepository.java` | 加 `findByAppIdAndEnabledTrue(Long appId)` |
| `service/HybridRetrievalService.java` + `impl/` | 签名改 scope；两路过滤条件扩展 |
| `repository/KeywordSearchDao.java` | SQL 加 appId + kbIds IN 条件 |
| `service/impl/ChatServiceImpl.java` | `searchKnowledge` 接 resolver + 空范围短路；纯向量路同样用 scope |
| `dto/ChatRequest.java`、`dto/open/OpenChatRequest.java`、`controller/open/OpenChatController.java` | 删死字段/映射 |
| `frontend/src/pages/ChatPage.tsx`、`frontend/src/types/index.ts` | 删写死的 knowledgeBaseId |
| 既有 IT（`HybridRetrievalIT` 等） | **适配**：种子向量须配套 `KnowledgeBase(enabled=true)` 行且 metadata 带匹配 appId/knowledgeBaseId，否则被新过滤筛掉 |

## 7. 测试

- **单测（Mockito，免 Docker）**：
  - `RetrievalScopeResolverTest`：有/无 app 上下文、空库列表、查询异常 fail-soft。
  - filterExpression 拼接纯函数：单库/多库 IN、tenantId 为 null 省略子句。
  - `ChatServiceImpl`：空范围短路（不触达 VectorStore/HybridRetrievalService）。
  - `HybridRetrievalServiceImpl`：scope 传导到两路（mock 验证过滤参数）。
- **IT（Testcontainers，`MultiKbRetrievalIT`）**：
  - 同应用两个知识库各带向量（一个 `enabled=false`）→ 只命中 enabled 的库。
  - 同租户两个应用 → A 应用检索不到 B 应用的知识（appId 缺口回归守护）。
  - 无 enabled 库 → 空命中 → fallback 语义。
  - 关键词路同样受 kbIds/appId 约束（复用 zhparser 精确术语手法）。
- **既有 IT 适配**是本次最可能翻车处，实现计划单列任务；IT 写法沿用既有坑位经验（种子走真实请求或非 BaseTenantEntity 实体、不 mock VectorStore、类级 `@Transactional`）。
- `mvn test` 免 Docker 通过、`mvn verify` 全绿。

## 8. 关键决策与风险

- **enabled 开关而非新配置**：复用现有字段与管理页，零新配置面；粒度是「应用级全选 enabled」，不支持按场景差异化（YAGNI，见 §1 非目标）。
- **全局 top-K 而非每库配额**：相关度优先、实现最简（两路各加一个过滤条件）；小库可能被大库淹没——接受，需要公平曝光时再演进为每库检索+RRF（现有 `RrfFuser` 天然支持多路）。
- **严格空范围语义**：行为可预测、隔离彻底；代价是平台管理员不带 `X-App-Id` 直接测试对话时只会得到 fallback 回复（可接受，管理后台聊天页本就要求先选应用）。
- **行为变化**：升级后检索范围从「租户全量」收窄到「本应用 enabled 库」。对单应用租户无感；多应用租户如依赖跨应用串知识（本是缺口）会察觉收窄——这是修复不是回归。
- **IN 列表长度**：库数量为应用级管理数据（个位数~几十），IN 条件无性能顾虑。

## 9. 验收标准

- 同应用多个 enabled 知识库的内容可在一次问答中被联合命中；`enabled=false` 的库不参与。
- 同租户不同应用的知识互不可见（向量路与关键词路都验证）。
- 无应用上下文 / 无 enabled 库时返回 fallback 回复，对话不中断。
- 死代码（请求字段、前端写死值）清理完毕，开放 API 向后兼容。
- 单测 + IT 全绿；`mvn test` 免 Docker 通过、`mvn verify` 通过。

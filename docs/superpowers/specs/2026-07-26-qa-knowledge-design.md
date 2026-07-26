# QA 问答对知识库（MVP）设计

- 日期：2026-07-26
- 分支：`feat/qa-knowledge`
- 作者：ruan
- 来源：对参考项目 tgo 的借鉴——第一档能力缺口 #2（#1 对话内工具调用已合入 master c15b880）。

## 1. 背景与目标

现有 RAG 只有「文档上传→切块→嵌入→混合检索」一条链路。FAQ 场景需要**问答对精确命中**：用户问题匹配预设问题、返回预设答案。本设计新增 **QA 问答对知识库**——把整条 `问+答` **不切分**嵌成单向量，进**同一个 pgvector 向量库**，**复用现有检索链路**；并支持 **LLM 从已有文档自动生成 QA 对**（草稿→人工复核→入库）。

核心原则：**摄取侧分叉（不切分 + 标记），检索热路径零改动。**

## 2. MVP 范围

### IN
- `QaPair` 实体挂现有 `KnowledgeBase`（一个库可混文档 + QA）。
- 手动 QA 对 CRUD + 启用/停用。
- 整条 `问：{q}\n答：{a}` 不切分嵌入共享向量库。
- 走现有 `searchKnowledge`（向量 + 关键词混合）。
- LLM 文档→QA 生成（异步、草稿态、人工复核后入库）。

### OUT（YAGNI）
批量导入（CSV/JSON）、网站抓取 KB、QA 专用精确匹配 pass（先复用同一检索）、生成的 token 计量。

## 3. 数据模型 · `QaPair`（表 `qa_pair`，继承 `BaseTenantEntity`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 主键 |
| `knowledgeBase` | `@ManyToOne`(nullable=false) | 挂现有知识库；检索范围（enabled 库集合）天然含之 |
| `question` | text | 问题 |
| `answer` | text | 答案 |
| `source` | enum `MANUAL`/`GENERATED` | 手动录入 / LLM 生成 |
| `status` | enum `DRAFT`/`ACTIVE`/`DISABLED` | 见下状态语义 |
| `sourceDocumentId` | Long(nullable) | 生成来源文档（GENERATED 时记录） |
| `createdAt`/`updatedAt` | LocalDateTime | 时间戳 |

**状态语义与转换**（对齐现有 `Document` 的 enabled/status 模式）：
- 手动创建 → `ACTIVE`（立即嵌入）。
- LLM 生成 → `DRAFT`（**不嵌入**，进复核列表）。
- 复核通过 `DRAFT→ACTIVE`（嵌入）。
- 停用 `ACTIVE→DISABLED`（`docEnabled` 置 false，**保留向量免重嵌**，同 Document）。
- 重新启用 `DISABLED→ACTIVE`（`docEnabled` 置 true，不重嵌）。
- 编辑 question/answer：ACTIVE 态文本变了 → **重嵌**（删旧向量 + 加新）；DRAFT 态（未嵌入）→ **仅改行、无向量操作**；DISABLED 态 → 改行 + 重嵌但保持 docEnabled=false。
- 删除 → 移除向量 + 删行。

## 4. 摄取（嵌入）· `QaPairService`

组文本 `问：{q}\n答：{a}`，构一条**不切分** Spring AI `Document`，`vectorStore.add(List.of(doc))`。metadata：

```
qaPairId, knowledgeBaseId, tenantId, appId, source(=问题截断，展示用), visibility=PUBLIC, docEnabled=true, contentType=qa
```

**RetrievalScope 过滤必需键（已核实 `RetrievalScope.toFilterExpression:33-50`）**：`tenantId` / `appId` / `knowledgeBaseId` / `docEnabled=='true'` /（PUBLIC 鉴权路径）`visibility=='PUBLIC'`。QA 向量必带这 5 键（缺键行会被等值过滤静默筛掉），其余 `qaPairId`/`source`/`contentType` 为附加。
- **`tenantId`/`appId` 取自 `qaPair` 行**（`qaPair.getTenantId()/getAppId()`，@PrePersist 已回填），非 `TenantContext`——防止未来若改异步嵌入时上下文缺失写空串（沿用 Document 既有修法的教训）。

**向量生命周期实现**：
- 嵌入（DRAFT→ACTIVE / 手动创建）：组文本 + add。
- 停用/启用：`VectorStoreRepository` 新增 `updateDocEnabledByQaPairId(qaPairId, 'false'/'true')`（native `metadata->>'qaPairId'`，仿既有 `updateDocEnabledByDocumentId`）。
- 删除 / 重嵌前清旧：`VectorStoreRepository` 新增 `deleteByQaPairId(qaPairId)`（native，仿 `deleteByDocumentId`）。

## 5. LLM 文档→QA 生成 · `QaGenerationService`（`@Async`）

`generateFromDocument(documentId)`：
1. 归属校验（经 `KnowledgeService.getDocument` 或 `TenantOwnershipGuard`），取文档所属 KB。
2. 读 `document.content`（文档处理完成后已存）。
3. `ModelRouterService.callWithFallback("default", client -> client.prompt().user(prompt).call().content())`，prompt：「据以下内容生成问答对，只输出 JSON 数组 `[{"question":..,"answer":..}]`，问题简洁、答案基于原文…\n\n{content}」。
4. **容错解析** JSON（截取 `[`..`]`、解析失败 fail-soft 记录不抛）。
5. 每条存 `QaPair(source=GENERATED, status=DRAFT, sourceDocumentId=documentId, knowledgeBase=文档所属库)`，**不嵌入**。

- **不计量**（内部管理动作，非开放 API；沿用「token 计量是开放专属」既定边界）。
- 生成跑 `@Async`（LLM 调用慢）；`@Async` 线程无 TenantContext，故 KB/租户/应用取自**文档行**（同 processDocument 教训）。

## 6. 检索融入（零改核心）

`ChatServiceImpl.searchKnowledge` / `HybridRetrievalService` / `buildContext` **一行不改**。QA 的 ACTIVE 向量带齐 §4 的 5 个必需键 → 自动通过既有 `RetrievalScope` 过滤 → 与文档块一起参与向量 + 关键词混合检索；其 `问+答` 文本经 `Document.getText()` 直接进 `buildContext`。

**已知局限**：`ChatServiceImpl.extractHitDocumentIds` 读 `documentId` 做覆盖率埋点，QA 命中（带 `qaPairId` 无 `documentId`）不计入覆盖率——MVP 接受；如需，后续扩 `qaPairId`。

## 7. 管理面

**后端 `QaPairController`**（归属经 `getKnowledgeBase` + `TenantOwnershipGuard`）：
- `GET /v1/knowledge/{kbId}/qa-pairs`（列表，按 kbId 收窄，含 status 过滤）
- `POST /v1/knowledge/{kbId}/qa-pairs`（手动创建→ACTIVE）
- `PUT /v1/qa-pairs/{id}` / `DELETE /v1/qa-pairs/{id}`
- `POST /v1/qa-pairs/{id}/approve`（DRAFT→ACTIVE）/ `POST /v1/qa-pairs/{id}/disable`（ACTIVE→DISABLED）/ `.../enable`
- `POST /v1/qa-pairs/generate?documentId=`（触发 LLM 生成，异步，返回受理）

**前端**：`KnowledgePage` 知识库详情加「问答对」区/tab：列表（source/status 徽章）、增改表单、通过/停用/删、「从文档生成」按钮（选已完成文档→生成→草稿进复核列表）。

## 8. 测试

- **单测（优先，纯 Mockito）**：`QaPairService` 组文本 + 嵌入（mock `VectorStore`）、状态流转（各转换调对 add/updateDocEnabled/deleteByQaPairId）、归属校验；`QaGenerationService` 的 JSON 解析（含容错：非法 JSON、含前后缀文本）。
- **IT**：`QaPair` 持久化 + 检索 metadata 齐全（真库，继承 `AbstractPostgresIT`，验证 ACTIVE QA 向量带齐 5 必需键、能被 `RetrievalScope` 过滤命中）。全链路真嵌入（依赖真 Embedding 端点）IT 跳过，沿用既定取舍。

## 9. 分期（一条分支 `feat/qa-knowledge`）
- **A 后端核心**：`QaPair` 实体/repo + `QaPairService`（CRUD + 嵌入/停启/删）+ `VectorStoreRepository` 两个 native 方法 + `QaPairController` 手动 CRUD + 检索融入验证。
- **B LLM 生成**：`QaGenerationService`（@Async + JSON 容错）+ 生成/复核控制器端点。
- **C 前端**：QA 管理区 + 生成复核 UI。
- **D 收尾**：IT + 全量 verify。

## 10. 待核实（实现前）
- Spring AI `VectorStore.add` 传单条 Document 即"不切分"（不经 TokenTextSplitter），行为确认。
- 新增 metadata 键（qaPairId/contentType）对 pgvector filter 等值过滤无副作用（既有等值过滤已验，仅新增键，无影响预期）。

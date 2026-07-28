# 向量重嵌能力（换 embedding 模型迁移）设计

- 日期：2026-07-28
- 分支：`feat/vector-reembed`
- 作者：ruan
- 来源：AI 模型切换（embedding→qwen3.7-text-embedding，合入 62ecaaa）后的必要配套——换 embedding 模型后存量向量在旧语义空间，须重嵌，否则检索出垃圾。

## 1. 背景与目标

换 embedding 模型后，存量向量（旧模型嵌的）与新查询向量在**不同语义空间**，跨空间 cosine 相似度是噪声（即使维度都是 1024）。源文本仍在（`documents.content`、`qa_pairs` 问答），故可重嵌。目标：把「换 embedding 模型」从**隐性事故**变成**有意识、可执行、检索自动安全**的迁移——三件套协同：
- **打标**：每条向量记录其嵌入模型（`embeddingModel` metadata 键）。
- **护栏**：检索只匹配当前模型的向量（跨模型/未标者自动排除，永不出垃圾）。
- **重嵌**：批任务把旧知识重嵌回当前模型空间并打标。

## 2. 现有可复用积木（已勘查）

- 文档嵌入 `KnowledgeServiceImpl.processDocument`：Tika 解析 + `TokenTextSplitter` 切块 + 设 metadata（documentId/knowledgeBaseId/tenantId/appId/source/visibility/docEnabled）+ `vectorStore.add`。**重嵌前不删旧向量**（现成 `reprocessDocument` 直接调它会重复向量）。
- QA 嵌入 `QaPairService.embed(qa)`（package-private）：组「问：{q}\n答：{a}」不切分 + metadata（qaPairId/knowledgeBaseId/tenantId/appId/source/visibility/docEnabled/contentType）+ `vectorStore.add`。`update` 已是「`deleteByQaPairId`+`embed`」（delete-first 幂等）。
- 删除 native：`VectorStoreRepository.deleteByDocumentId`/`deleteByKnowledgeBaseId`/`deleteByQaPairId`。
- 检索过滤：`RetrievalScope.toFilterExpression()` 拼 `tenantId==.. && appId==.. && knowledgeBaseId in[..] && docEnabled=='true' [&& visibility=='PUBLIC']`（向量路）；`KeywordSearchDao.search` 手写 SQL 同款 metadata 等值过滤（关键词路）。
- 嵌入模型名：`spring.ai.openai.embedding.options.model`（yml，现无 @Value 注入点）。

## 3. 组件设计

### 3.1 `EmbeddingModelProvider`（新，`@Component`）
当前 embedding 模型名的单一来源：`@Value("${spring.ai.openai.embedding.options.model}") private String model;` + `getModel()`。写入打标、检索护栏、重嵌、体检共用，避免各处散读配置漂移。

### 3.2 写入侧打标
- `KnowledgeServiceImpl.processDocument` 的 chunk metadata 加 `"embeddingModel", embeddingModelProvider.getModel()`。
- `QaPairService.embed` 的 metadata 加同键。
- 注入 `EmbeddingModelProvider` 到两个 service。

### 3.3 检索护栏（拒绝式）
- **向量路**：`RetrievalScope.toFilterExpression(String embeddingModel)` 增参，末尾追加 `&& embeddingModel == '<embeddingModel>'`；`ChatServiceImpl.searchKnowledge` 注入 `EmbeddingModelProvider`、调用时传入。既有无参 `toFilterExpression()` 的调用点（测试等）一并改为传参（或保留无参重载委托 + 传当前模型，避免破坏面过大——实现时择一，以不遗漏调用点为准）。
- **关键词路**：`KeywordSearchDao.search` 的 SQL 加 `AND metadata ->> 'embeddingModel' = ?`（参数为当前模型名）；`HybridRetrievalServiceImpl`/`KeywordSearchDao` 注入 provider 传入。
- 效果：跨模型（`embeddingModel` != 当前）与未标（无该键 → 等值过滤静默排除，同其它必需键语义）向量**不参与检索**，无垃圾匹配。

### 3.4 `ReembedService`（新）+ 重建批任务
- `startReembed(scope) -> jobId`：按范围解析待重嵌集合——
  - 文档：范围内 `COMPLETED` 状态文档，逐条 `deleteByDocumentId` + `processDocument`（重新切块+新模型嵌入+新标）。
  - QA：范围内 `ACTIVE` 状态 QA，逐条 `deleteByQaPairId` + `embed`。
- **范围粒度**：`ALL`（平台管理员，全平台）/ `APP`（当前应用，租户管理员）/ `KB`（单知识库）。走现有归属守卫（`getKnowledgeBase`/`TenantOwnershipGuard`/平台管理员判定）。
- **异步 + 进度**：`@Async` 执行；进度用**内存 job 状态**（`ConcurrentHashMap<String, ReembedProgress>`，字段 total/done/failed/status/startedAt/finishedAt/scope）。每条 `try/catch` fail-soft（单条失败 failed++ 不中断整批）。**幂等**（每条 delete+重嵌）→ 重启后重跑即续（持久化断点续作后续增强，YAGNI）。
- **端点**：`POST /v1/knowledge/reembed?scope=ALL|APP|KB&knowledgeBaseId=`（返回 jobId）；`GET /v1/knowledge/reembed/{jobId}`（返回进度）。加 `@AuditLog`。
- 复用 `KnowledgeServiceImpl`/`QaPairService` 的嵌入逻辑，`ReembedService` 只做编排（不重复嵌入细节）。

### 3.5 顺带修 `reprocessDocument`
现状 `reprocessDocument(documentId)` → `processDocumentAsync` → `processDocument`（不删旧向量 → 重复）。改为**先 `vectorStoreRepository.deleteByDocumentId` 再重处理**，使重处理/重嵌幂等（`ReembedService` 文档重嵌复用此语义）。

### 3.6 dev 清理
清掉换模型前用 text-embedding-v3 嵌的 1~2 条冒烟旧向量（`TRUNCATE`/`DELETE` dev vector_store 中无当前模型标的行），避免部署后被护栏排除后残留。

## 4. 部署 / rollout 语义（护栏拒绝式的直接后果）

合入后**存量未标向量立即被检索护栏排除**（无 `embeddingModel` 键 → 不匹配当前模型过滤）。因此上线该能力后须**跑一次全量重嵌**（`scope=ALL`）把存量向量打标、带回当前模型空间。本项目未上线、dev 仅 2 条向量 → 合入后清空重灌即可，恰是最省事适配时机；真实部署遵循「部署 → 立即全量重嵌」。

## 5. 错误处理 / 多租户
- 重嵌单条 fail-soft（failed 计数，不拖垮整批）；@Async 无 TenantContext → 租户/应用取自实体行（同 processDocument/embed 既有教训）。
- 范围/端点经现有归属守卫：租户管理员只能重嵌本应用（`scope=APP`/`KB` 校验归属），平台管理员可 `scope=ALL`。
- 护栏过滤与既有多租户过滤叠加，不改租户隔离语义。

## 6. 测试
- **单测（纯 Mockito）**：`EmbeddingModelProvider` 返回配置模型名；`processDocument`/`QaPairService.embed` 写入的 metadata 含 `embeddingModel`（验 chunk/Document metadata）；`RetrievalScope.toFilterExpression(model)` 生成串含 `embeddingModel == '<model>'`；`ReembedService` 按范围迭代 + 每条 delete+重嵌调用 + fail-soft（单条抛不中断）+ 进度累计（mock KnowledgeService/QaPairService/repo）；`reprocessDocument` 先 delete 再处理（InOrder）。
- **IT（继承 `AbstractPostgresIT`）**：jdbcTemplate 播种带不同 `embeddingModel` 标的 vector_store 行（当前模型 vs 旧模型 vs 无标），验证 `KeywordSearchDao`（真库）只命中当前模型标的行——护栏排旧成立。真实嵌入的全链重嵌 IT 沿用既定跳过取舍（依赖真 Embedding 端点）。

## 7. 分期（一分支 `feat/vector-reembed`）
- **A 打标**：`EmbeddingModelProvider` + `processDocument`/`QaPairService.embed` 加 `embeddingModel` 键 + 单测。
- **B 检索护栏**：`RetrievalScope.toFilterExpression(model)`（向量路）+ `KeywordSearchDao` SQL（关键词路）+ 注入 provider + 单测/IT。
- **C 重建批任务**：`ReembedService`（编排 + 内存 job 进度 + fail-soft）+ 端点 + 修 `reprocessDocument` delete-first + 单测。
- **D 收尾**：dev 清理（清旧向量）+ IT + 全量 verify。

## 8. 明确不做（YAGNI）
- 持久化断点续（内存进度 + 幂等重跑够 MVP）；重嵌限流/并发调度；自动在换模型时触发重嵌（保持人工有意识触发）；前端重嵌 UI（管理端端点 MVP，前端另议）；跨模型历史向量的自动 backfill 打标（语义不可靠，一律靠重嵌）。

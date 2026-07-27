# 检索失败事务隔离（fail-soft 修复）设计

- 日期：2026-07-27
- 分支：`fix/retrieval-tx-isolation`
- 作者：ruan
- 来源：QA 问答对知识库**真机冒烟**发现的健壮性缺陷。

## 1. 背景与根因

`ChatServiceImpl.chat()` 是 `@Transactional`。其内 `searchKnowledge(query, strategy)` 执行检索 SQL：
- 混合路 `HybridRetrievalServiceImpl.search` → `KeywordSearchDao`（`JdbcTemplate` 原生 SQL，查 `content_tsv` 全文）；
- 纯向量路 `vectorStore.similaritySearch`（Spring AI PgVectorStore，内部 `JdbcTemplate`）。

二者都跑在 **chat() 写事务的同一条 JDBC 连接**上。任一检索 SQL 出错（如 `content_tsv` 缺失/zhparser 故障/维度问题）→ **PostgreSQL 在连接层把当前事务置为 aborted（25P02）**。`HybridRetrievalServiceImpl` 的 per-path try/catch 吞掉了异常、优雅返回空，但**那条连接的事务已被毒化**：后续任何查询（`ChatToolService.resolve`、`saveMessages`）都报 `current transaction is aborted` → 整个 `chat()` 返回 **500**。

这违背 CLAUDE.md 明写的契约：「知识检索 fail-soft：任何异常均返回空列表、不阻断对话」。`searchKnowledge` 顶层已有 try/catch 返回 `List.of()`，但对**连接层事务毒化无效**——异常被吞了，事务仍是坏的。

## 2. 方案：检索整体走独立只读新事务

`searchKnowledge(query, strategy)` 改用 `@Transactional(propagation = REQUIRES_NEW, readOnly = true)`：
- 检索在**独立连接 C2** 上运行（REQUIRES_NEW 挂起 chat 外层事务、从池取新连接）；
- 检索 SQL 失败只毒化 C2；C2 是只读检索事务，回滚无副作用；PostgreSQL 对已 abort 事务执行 commit 即静默 rollback，不抛错；
- chat 写事务的连接 **C1 全程干净** → `resolve`/`saveMessages` 正常 → chat 不再 500，真正 fail-soft（返回空知识、正常出兜底话术或无知识回答）。
- 混合路与纯向量路都在 `searchKnowledge` 内 → **一处 REQUIRES_NEW 覆盖两条路径**。

## 3. 实现要点

- `searchKnowledge(query, strategy)` 被 `chat()`/`streamChat()` **自调用**（同 bean），直接加 `@Transactional` 因 Spring 代理自调用不生效。→ **自注入 `self`**（复刻 `KnowledgeServiceImpl` 既有 `self` + `@Lazy` setter 范式）：
  - `ChatServiceImpl` 加 `private ChatServiceImpl self;` + `@Autowired @Lazy setSelf(...)`；
  - `searchKnowledge(String, ChatStrategy)` 由 private 改 **public** + `@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)`；
  - `chat()`（约 130 行）与 `streamChat()`（约 210 行）两处调用改为 `self.searchKnowledge(...)`。
- 顶层既有 try/catch（异常 → `List.of()`）保留，与事务隔离叠加双保险。
- **不重构 chat() 整体事务边界、不动检索业务逻辑/RRF/RetrievalScope。**

## 4. 测试

- **单测**：现有调用 `chat()` 的单测（`ChatServiceSessionOwnershipTest` 的经-chat 用例等）在 `@InjectMocks` 无 Spring 环境下 `self` 为 null → 需在 setup 里 `service.setSelf(service)` 令 `self.searchKnowledge` 可达，保证既有用例不回归（这是本次实现必须一并处理的测试影响）。
- **IT（真库，择一可行者）**：验证「检索 SQL 失败不毒化外层事务」这一核心不变式——
  - 首选：一个 IT 在 `chat()` 路径上诱导关键词检索失败（如测试内临时使 `content_tsv` 相关查询失败），断言 `chat()` 仍返回成功（HTTP 200 / 非异常）、知识命中为空、且同请求内后续 `ChatToolService.resolve` 等不受毒化；
  - 若在共享容器里诱导失败过于脆弱/影响其它 IT，则退化为一个专门的事务隔离 IT（外层 `@Transactional` 中调用一个 REQUIRES_NEW 的失败 SQL、断言外层后续查询仍可用），加结构验证（chat 经 self 路由 searchKnowledge），并标注「LLM 额度恢复后真机 re-smoke 复验完整链路」。

## 5. 明确不做（YAGNI）
- 不修 zhparser 镜像/`content_tsv` 建列（那是部署环境问题，另行处理）。
- 不动 DashScope 额度（外部账户）。
- 不改检索算法、RRF、RetrievalScope、chat() 的写事务原子性语义。

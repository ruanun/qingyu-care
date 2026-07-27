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
  - **仅** `chat()`（约 130 行）与 `streamChat()`（约 226 行）两处调用改为 `self.searchKnowledge(...)`（经代理走 REQUIRES_NEW）。
- **关键约束：公开入口 `searchKnowledge(String)`（约 520 行）内部对 2 参方法的调用保持直调（`searchKnowledge(query, strategy)`，自调用不经代理 → 注解失效、跑在调用方连接上），绝不改成 `self.`。** 原因：检索类 IT（`HybridRetrievalIT`/`DocVisibilityRetrievalIT`/`MultiKbRetrievalIT`）是类级 `@Transactional`，在**同一事务**内先播种（KB 走 JPA、chunk 走 `jdbcTemplate`）再经 `searchKnowledge(String)` 检索；若该路径进了 REQUIRES_NEW 新连接，会看不见本事务未提交的播种数据 → 三个 IT 全崩。故 REQUIRES_NEW 只在生产热路径（chat/streamChat）经代理生效，测试入口保持同事务可见性。
- 顶层既有 try/catch（异常 → `List.of()`）保留，与事务隔离叠加双保险。
- **不重构 chat() 整体事务边界、不动检索业务逻辑/RRF/RetrievalScope。**

## 4. 测试

- **单测（纯 Mockito）**：
  - **路由**：新增 `chat()` 用例——把 `self` 注入为 mock `ChatServiceImpl`，令 `searchKnowledge` 返回空、策略 `allowNoKnowledge=false`（走兜底分支，不触达 LLM），`verify(selfMock).searchKnowledge(...)` 证明检索经代理路由（而非私有自调用，后者代理不生效）。
  - **注解守卫**：反射断言 2 参 `searchKnowledge(String, ChatStrategy)` 为 public 且带 `@Transactional(propagation=REQUIRES_NEW, readOnly=true)`——守住本修复的核心配置不被误删。
  - **回归**：现有调用 `chat()` 的 `ChatServiceSessionOwnershipTest` 在 `@InjectMocks` 无 Spring 环境下 `self` 为 null → 在 `@BeforeEach` 里 `chatService.setSelf(chatService)` 令 `self.searchKnowledge` 可达，保证既有用例不回归。（`ChatServiceSearchScopeTest` 走 `searchKnowledge(String)` 直调路径、不触达 `self`，无需改。）
- **IT（真库，机制级验证）** `RetrievalTxIsolationIT extends AbstractPostgresIT`：因关键词 SQL 用 `plainto_tsquery`（任意输入不报错）、真实 SQL 失败只源于 schema 级（`content_tsv` 缺失，共享容器里恒存在），且 chat() 端到端受 LLM 依赖阻塞（与既有 LLM-IT 跳过先例一致），故以探针 bean 验证本修复所依赖的 Spring/PG 不变式：
  - **正例**：外层 `@Transactional` 中调用探针的 `@Transactional(REQUIRES_NEW, readOnly=true)` 方法（内部跑必失败 SQL `SELECT 1/0` 并吞异常，镜像 `searchKnowledge` 的 fail-soft），随后在外层连接上 `SELECT 1` 断言仍返回 1（外层未被毒化）。
  - **反例（证明隔离是必需的）**：外层 `@Transactional` 中调用探针的 `REQUIRED`（同事务）失败方法，随后 `SELECT 1` 断言抛出（`current transaction is aborted`），坐实不隔离即毒化。
- **已知局限（登记留债）**：`chat()` 全链路 fail-soft（真实检索失败→不 500）无端到端 IT——受 LLM 依赖阻塞，沿用既定 LLM-IT 跳过取舍；待 DashScope 额度恢复后真机 re-smoke 复验完整链路。

## 5. 明确不做（YAGNI）
- 不修 zhparser 镜像/`content_tsv` 建列（那是部署环境问题，另行处理）。
- 不动 DashScope 额度（外部账户）。
- 不改检索算法、RRF、RetrievalScope、chat() 的写事务原子性语义。

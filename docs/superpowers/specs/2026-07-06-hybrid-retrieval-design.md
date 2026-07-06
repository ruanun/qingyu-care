---
title: 混合检索（向量 + zhparser 关键词）设计
author: ruan
date: 2026-07-06
status: 已实现
---

# 混合检索（向量 + zhparser 关键词）设计

Phase 4.3「高级 RAG 能力」的第一个子项目。多知识库联合检索、文档权限控制是同层级的独立子项目，各自单独 spec，排在本项目之后。

## 1. 背景与范围

产品主功能是知识库检索增强的智能客服（`ChatServiceImpl` 的 RAG 链路）。现状检索**只有向量一路**：`searchKnowledge()` 走 Spring AI `VectorStore.similaritySearch`（pgvector，HNSW + cosine，通义 `text-embedding-v3`，1024 维），`SearchRequest` 仅按 `metadata.tenantId` 过滤，topK/similarityThreshold 取自 `ChatStrategy`。

纯向量检索对「语义弱、字面强」的查询召回不佳：产品型号（`XZ-9000`）、编号、专有名词、缩写等，向量相似度未必把精确匹配排进 topK。

**范围**：在现有检索链路旁挂一路**中文关键词全文检索**（PostgreSQL `zhparser` 中文分词 + `tsvector`/GIN），用 **RRF（Reciprocal Rank Fusion）** 与向量结果融合；是否启用与调参经 `ChatStrategy` 按租户可配（复用「租户自有 → 平台默认」回退）。检索作用域、租户隔离与现有向量路保持一致。

**非目标（YAGNI）**：
- 多知识库作用域 / 按库检索（4.3 的独立子项目，本期不动，关键词路沿用现有仅 `tenantId` 过滤）。
- 文档权限控制（4.3 独立子项目）。
- 重排模型（cross-encoder rerank）、查询改写 / HyDE / 多路 query 扩展。
- 非中文语料的 FTS 调优（本期面向简体中文场景）。
- 向量维度 / embedding 模型变更。

## 2. 目标与非目标

**目标**
- 中文关键词检索：基于 `zhparser` 的真正中文分词 FTS（`tsvector` + GIN 排序），非按字 trigram。
- RRF 融合向量与关键词两路结果，提升精确术语召回，且在关键词无命中时退化≈纯向量。
- 按租户经 `ChatStrategy` 配置（启用开关 + 关键词 topK + RRF k），走多级回退，与现有 topK/threshold 语义一致。
- fail-soft：关键词路故障退化为纯向量，整体故障返回空、不阻断对话。
- 单测 + 集成 IT 守护（延续现有回归网）。

**非目标**：见 §1。

## 3. 基础设施（zhparser 镜像 + 中文 FTS + 生成列）

本项目最大成本项。选 `zhparser`（而非无需换镜像的 `pg_trgm`）是为真正的中文分词 FTS 质量，代价是自定义镜像与测试基座调整。

### 3.1 自定义 PostgreSQL 镜像
新增 `docker/postgres-zhparser/Dockerfile`：
- `FROM pgvector/pgvector:pg17`（保留 pgvector）。
- 安装构建依赖，编译安装 **SCWS**（简易中文分词库）与 **zhparser** 扩展，匹配 pg17。
- 产出镜像同时具备 `vector` 与 `zhparser` 两个扩展能力。

`docker/docker-compose.yml` 的 postgres 服务改为 `build:` 该 Dockerfile（替代直接拉 `pgvector/pgvector:pg17`）。

### 3.2 扩展与中文文本搜索配置
初始化脚本（`docker/init.sql` 或等价 init）在 `CREATE EXTENSION vector` 之后追加：
```sql
CREATE EXTENSION IF NOT EXISTS zhparser;
CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION chinese_zh
  ADD MAPPING FOR n,v,a,i,e,l,j WITH simple;
```
（词性映射 `n,v,a,i,e,l,j` 覆盖名词/动词/形容词等主要实词；`simple` 词典不做词干还原，适合中文。）

### 3.3 `vector_store` 生成列 + GIN 索引
`vector_store` 表由 Spring AI 运行时建（`initialize-schema`），故不能靠静态 init 脚本加列。用一个**幂等的 `ApplicationRunner`**（`HybridSearchSchemaInitializer`），在 Spring 上下文就绪（表已存在）后执行：
```sql
ALTER TABLE vector_store
  ADD COLUMN IF NOT EXISTS content_tsv tsvector
  GENERATED ALWAYS AS (to_tsvector('chinese_zh', content)) STORED;
CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv
  ON vector_store USING GIN (content_tsv);
```
- `to_tsvector(regconfig, text)`（显式配置版）是 IMMUTABLE，可用于 `GENERATED ALWAYS ... STORED` 生成列；PG 自动维护，**文档摄取代码零改动**。
- 幂等（`IF NOT EXISTS`），可重复启动安全。执行前确保 `chinese_zh` 配置存在（init 脚本已建；Runner 内可 `CREATE ... IF NOT EXISTS` 兜底防遗漏）。

### 3.4 测试基座（Testcontainers）
`AbstractPostgresIT` 当前用固定镜像 `pgvector/pgvector:pg17`。改为用 **`ImageFromDockerfile`** 指向 `docker/postgres-zhparser/Dockerfile`（Testcontainers 首跑构建、按内容 hash 缓存复用），使 `mvn verify` 保持自洽、无需手工预构建。
- **代价**：首跑镜像构建 ~1-2 分钟（之后缓存命中秒级）。
- `mvn test`（Surefire，纯 Mockito）不启容器，**不受影响、仍免 Docker**。
- 备选（若更重 CI 速度）：改为 `docker compose build` 出固定 tag、IT 引用该 tag——但 `mvn verify` 不再完全自洽（需先构建）。本设计取自洽优先，采用 `ImageFromDockerfile`。

## 4. 检索与融合

### 4.1 关键词检索 DAO
新增原生查询（`JdbcTemplate` 或 `@Query(nativeQuery)`）：
```sql
SELECT id, content, metadata,
       ts_rank_cd(content_tsv, plainto_tsquery('chinese_zh', :q)) AS rank
FROM vector_store
WHERE content_tsv @@ plainto_tsquery('chinese_zh', :q)
  AND metadata ->> 'tenantId' = :tenantId
ORDER BY rank DESC
LIMIT :keywordTopK
```
- 租户隔离用 `metadata ->> 'tenantId'`，与向量路 `filterExpression` 同源一致（`vector_store` 非 Hibernate filter 实体，手写条件）。
- 返回 chunk 行（`id` 即 `vector_store` 主键 UUID、`content`、`metadata`）。

### 4.2 `HybridRetrievalService`（新）
编排两路 + RRF 融合：
- 向量路：复用现有 `vectorStore.similaritySearch(SearchRequest.query(q).withTopK(topK).withSimilarityThreshold(threshold).withFilterExpression("tenantId == '...'"))`，取回 `topK` 候选。
- 关键词路：§4.1 DAO，取回 `keywordTopK` 候选。
- **RRF**：对每一路的排名列表，chunk `c` 得分 `score(c) = Σ_i 1 / (rrfK + rank_i(c))`（`rank_i` 从 1 起；`c` 未在某路则该项为 0）。按融合得分降序，取最终 `topK` 条（即两路各自取回候选池，融合后截断到 `topK`；`keywordTopK` 通常 ≥ `topK` 以给关键词路足够候选参与融合）。
- **对齐/去重**：两路结果都以 `vector_store.id`(UUID) 为身份，融合天然按 id 去重对齐。
- 返回 `List<org.springframework.ai.document.Document>`（关键词行映射为 `Document(content, metadata)`），供 `buildContext()` 无缝消费。
- `HybridRetrievalService` 是本项目引入的复杂协作者，遵循项目「复杂服务用 `service/` 接口 + `service/impl/` 拆分」惯例（与 `ChatService`/`KnowledgeService` 一致）；RRF 融合逻辑独立成纯函数便于单测。

### 4.3 接入 `ChatServiceImpl.searchKnowledge`
- `strategy.hybridEnabled` 为真 → 调 `HybridRetrievalService.search(query, strategy)`；否则维持现有纯向量路径（零行为变化）。
- **fail-soft**（保持现有语义并加固）：关键词路抛异常 → 记 `log.warn` 并退化为纯向量结果；两路皆失败 → 返回空，由既有 `allowNoKnowledge`/`fallbackMessage` 逻辑兜底，**不阻断对话**。

## 5. 配置（ChatStrategy 多级回退）

`ChatStrategy` 新增字段：
| 字段 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `hybridEnabled` | boolean | 平台默认 `true` | 是否启用混合检索 |
| `keywordTopK` | int | 10 | 关键词路取回条数 |
| `rrfK` | int | 60 | RRF 常数 k |

复用现有 `topK`（最终融合条数）、`similarityThreshold`（向量阈值）。

解析顺序沿用既有多级回退（**这是项目已知易错链路**）：
1. 查当前租户/应用范围内激活的 `ChatStrategy`；
2. **回退平台默认：native SQL 绕过 Hibernate 过滤器**取 `tenant_id IS NULL` 的行；
3. 降级 `default` 场景默认；
4. 静态兜底对象（`ChatStrategy` 语义允许无配置安全默认，不抛异常）。

平台默认由 `ChatStrategyService` `@PostConstruct` 播种为 `tenant_id IS NULL` 行，含 `hybridEnabled=true` 等新字段默认。旧数据行经 `ddl-auto: update` 新增列为 null → 解析时按字段默认值处理（null 安全）。扩展 `ChatStrategyFallbackIT` 守护新字段的回退。

## 6. 影响面

| 文件 | 改动 |
|---|---|
| `docker/postgres-zhparser/Dockerfile` | **新增**：pgvector + SCWS + zhparser 镜像 |
| `docker/docker-compose.yml` | postgres 服务改 `build:` 自定义镜像 |
| `docker/init.sql`（或等价 init） | 追加 `zhparser` 扩展 + `chinese_zh` 文本搜索配置 |
| `config/HybridSearchSchemaInitializer.java` | **新增**：幂等加 `content_tsv` 生成列 + GIN 索引（ApplicationRunner） |
| `repository/...`（关键词 DAO） | **新增**：`vector_store` 中文 FTS 原生查询 |
| `service/HybridRetrievalService.java` + `service/impl/HybridRetrievalServiceImpl.java` | **新增**：两路编排 + RRF 融合 |
| `entity/ChatStrategy.java` | 加 `hybridEnabled`/`keywordTopK`/`rrfK` |
| `service/ChatStrategyService.java` | 解析/播种新字段（native 回退不变） |
| `service/impl/ChatServiceImpl.java` | `searchKnowledge` 按 `hybridEnabled` 接 Hybrid + fail-soft |
| `support/AbstractPostgresIT.java` | 改用 `ImageFromDockerfile`（zhparser 镜像） |
| `ChatStrategyFallbackIT` | 扩展守护新字段回退 |

## 7. 测试

- **单测（Mockito，免 Docker）**：
  - RRF 融合纯逻辑：两列表去重对齐、rank 从 1 起、`rrfK` 参数、单路为空、两路交叠 / 不交叠的排序。
  - `HybridRetrievalService` 在 `hybridEnabled=false` / 关键词路抛异常时的降级（mock 两路）。
- **集成测试（Testcontainers zhparser 镜像，`HybridRetrievalIT`）**：
  - 摄取含精确术语（如 `XZ-9000`）的中文文档，查询该术语 → 混合检索命中该 chunk，且验证纯向量单独未必命中（对照）。
  - 两路都命中 → 验 RRF 融合排序合理。
  - `hybridEnabled=false` → 纯向量路径（关键词不参与）。
  - 关键词路租户隔离：A 租户文档不被 B 租户查询命中。
  - `ChatStrategyFallbackIT`：新字段的租户自有 / 平台默认回退。
- `mvn test` 免 Docker、`mvn verify` 含新 IT 全绿（首跑构建 zhparser 镜像）。

## 8. 关键决策与风险

- **选 zhparser 而非 pg_trgm**：追求真正中文分词 FTS 质量；代价是自定义镜像 + Testcontainers 首跑构建耗时（已在 §3.4 权衡，取 `mvn verify` 自洽优先）。
- **复用 `vector_store` 生成列（方案 A）而非独立 chunk 表**：摄取零改动、chunk 粒度与向量天然对齐、RRF 按行 UUID 融合最简；代价是依赖 Spring AI 表结构（若其升级改表需回归验证生成列）。
- **RRF 而非加权线性融合**：基于排名、无需分数归一化（向量 cosine 与 `ts_rank_cd` 量纲不可比），实现稳、参数少（仅 `rrfK`）。
- **平台默认 `hybridEnabled=true`**：特性默认开、租户可各自关。部署后检索行为对所有租户增量变化——但融合是叠加式（关键词无命中时 RRF≈纯向量），风险可控；如需保守可改默认 `false` 灰度。
- **fail-soft 一致**：关键词/向量任一路故障不阻断对话，与既有检索、限流、额度的 fail-soft 口径一致。
- **性能**：每次检索多一路 GIN 全文查询（有索引、topK 小，成本低）+ 内存 RRF 融合（topK 量级）。

## 9. 验收标准

- 部署自定义 zhparser 镜像后，`chinese_zh` 配置与 `vector_store.content_tsv` 生成列 + GIN 索引自动就绪（幂等）。
- 某租户开启 `hybridEnabled` 后，对「精确术语」查询能召回纯向量漏掉的 chunk；关闭后行为回到纯向量。
- 关键词路与向量路同样只命中本租户数据（跨租户隔离）。
- `ChatStrategy` 新字段走「租户自有 → 平台默认（native 绕过滤器）」回退，`ChatStrategyFallbackIT` 绿。
- 关键词路故障时对话不中断（退化为纯向量）。
- 单测 + IT 全绿；`mvn verify` 通过（含 zhparser 镜像构建）；`mvn test` 免 Docker 通过。

## 10. 实现结论（2026-07-06 落地）

Task 1-8 全部完成并合入特性分支 `feat/hybrid-retrieval`。`mvn verify` 全绿（Surefire 单测 82、Failsafe IT 38，0 失败 0 错误），`mvn test` 免 Docker 通过。落地要点与设计的偏差如实记录如下：

### 10.1 基础设施（zhparser 镜像 + Testcontainers 基座）
- 自定义镜像 `docker/postgres-zhparser/Dockerfile`：`FROM pgvector/pgvector:pg17`，编译安装 **SCWS + zhparser**。实测偏差：SCWS 改用 **git 源**编译（发行 tar 包源不稳），zhparser 用 **v2.3**（非计划里的 V2.2）；`apt` 换 **阿里云源**规避官方源 502。产出镜像同具 `vector` 与 `zhparser` 两扩展。
- `AbstractPostgresIT` 改用 `ImageFromDockerfile("qingyu/pgvector-zhparser:it", false).withDockerfile(...)`。偏差：testcontainers **1.20.4 的 `PostgreSQLContainer` 无接收 `Future<String>` 的构造函数**，故用 `.get()` 同步触发 `docker build`（返回镜像 tag）后再包成 `DockerImageName` 传入。首跑构建数分钟，之后 Docker 本地缓存复用秒级；`mvn test`（Surefire 纯 Mockito）不启容器、仍免 Docker。
- `chinese_zh` 文本搜索配置在 compose init（`docker/init.sql`）与 IT init（`backend/src/test/resources/db/init-vector.sql`）两处以幂等 `DO` 块建立；`HybridSearchSchemaInitializer` 内再 `IF NOT EXISTS` 兜底。

### 10.2 生成列 + 关键词检索 + 融合
- `HybridSearchSchemaInitializer`（`ApplicationRunner`，`@Order(0)`）：上下文就绪后幂等给 `vector_store` 加 `content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('chinese_zh', content)) STORED` 生成列 + GIN 索引，失败仅 `log.warn` 不阻断启动。
- `KeywordSearchDao`（`JdbcTemplate`）：`ts_rank_cd + plainto_tsquery('chinese_zh', ?)`，租户隔离用 `metadata ->> 'tenantId'`（与向量路 `filterExpression` 同源）。
- `RrfFuser`：标准 RRF（`score = Σ 1/(k+rank)`，rank 从 1 起、`LinkedHashMap` 保序），纯函数、4 个单测。
- `HybridRetrievalService`/`Impl`：编排两路 + RRF 融合，按 `vector_store.id` 对齐去重。**两路均 fail-soft**——向量路与关键词路各自独立 `try/catch`，任一路异常 → 该路空、由另一路贡献；两路皆空 → RRF 融合返回空，语义正确、不阻断对话（比设计更进一步：设计里向量路异常由上游兜底，落地时下沉到本服务内，也是下面端到端 IT 不再 mock VectorStore 的前提）。

### 10.3 ChatStrategy 配置回退与接入
- `ChatStrategy` 加 `hybridEnabled/keywordTopK/rrfK`，平台默认由 `@PostConstruct` 播种 `tenant_id IS NULL` 行为 `true/10/60`。native「回退平台默认」查询是 `SELECT *`（实体映射），随 `ddl-auto: update` 新增列**自动带出**，无需改 SQL。
- 接入 `ChatServiceImpl.searchKnowledge`：**开关用 `!Boolean.FALSE.equals(strategy.getHybridEnabled())`（null→开）**——特性默认开，且免去既有部署（旧平台默认行 `hybridEnabled=null`）的 backfill 迁移。整段 `searchKnowledge` 外层 `try/catch` 返回空、fail-soft 兜底。

### 10.4 测试关键差异（务必留意）
- **维度差异**：test profile 的 pgvector 维度是 **1536**（`application-test.yml`，非生产 1024）。IT 种子零向量用 `array_fill(0::real, ARRAY[1536])::vector`，与之对齐。
- **端到端 IT 的表污染坑与根治**：`HybridRetrievalIT` 最初用 `@MockitoBean VectorStore`（避开无 embedding key 的 401）。但 mock 使真实 `PgVectorStore` 的 `initialize-schema` 不跑，该测试上下文里 JPA（`ddl-auto: create-drop`）按预置的 `VectorStoreEntity`（映射不含 embedding 列）把**共享单例容器**的 `vector_store` 重建成缺 `embedding` 列的表；`@BeforeEach` 手动 `ALTER TABLE ADD COLUMN embedding` 的补丁又被类级 `@Transactional` 回滚，导致后续复用旧上下文的 `KeywordSearchDaoIT` 插种子失败（`column "embedding" does not exist`）——**顺序敏感、仅全量 `mvn verify` 复现**。
  - **根治**：给向量路加 fail-soft 后，`HybridRetrievalIT` **去掉 `@MockitoBean VectorStore`**，直接用真实 `PgVectorStore`（与其它 IT 共享同一上下文，表由 PgVectorStore 建全含 embedding、不再污染共享表）。向量路对零向量种子 cosine 低于阈值不命中、或无 key 时 401 → fail-soft 空；关键词路真实 FTS 命中精确术语 `XZ-9000`。两用例：hybrid 开 → 断言结果 `anyMatch(含 XZ-9000)`（关键词召回向量漏掉的术语）；hybrid 关 → 断言 `noneMatch(含 XZ-9000)`（关闭时关键词路不参与，仅向量路）。
  - **保留类级 `@Transactional`**：现在安全（表结构在上下文初始化时已建好并提交、不在测试事务内，`@Transactional` 只回滚种子数据），同时它解决非 HTTP 直调下租户过滤器 session 错配的 `NonUniqueResultException`。
- **预期告警（非失败）**：日志里 `Knowledge search failed: 401` / `向量检索失败，退化为...`（无 embedding key 的向量路 fail-soft）、`关键词检索失败,退化为纯向量`（测试故意触发降级）均为预期。

### 10.5 既有部署提示
- 因开关语义为 `!Boolean.FALSE.equals`，旧平台默认行 `hybridEnabled=null` 也视为**开启**，无需 backfill 迁移即可让特性对所有租户生效；如需保守灰度，可显式把租户/平台默认置 `false`。

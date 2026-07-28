# 向量重嵌能力（换 embedding 模型迁移）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「换 embedding 模型」做成有意识、可执行、检索自动安全的迁移——向量打 `embeddingModel` 标、检索只匹配当前模型（排跨模型/未标）、批任务把旧知识重嵌回当前模型空间。

**Architecture:** 三件套：`EmbeddingModelProvider`（当前模型名单一来源）；写入侧 `processDocument`/`QaPairService.embed` 打 `embeddingModel` 标；检索护栏拒绝式（`RetrievalScope.toFilterExpression(model)` 向量路 + `KeywordSearchDao` SQL 关键词路）；`ReembedService` 编排批重嵌（`@Async` + 内存进度 + 幂等）。

**Tech Stack:** Spring Boot 3 + Spring AI（VectorStore/pgvector）+ JPA / JUnit5 + Mockito 单测 / Testcontainers pgvector IT。

## Global Constraints

- 全部简体中文：代码注释、commit、文档。作者署名 `ruan`。
- 本项目允许 `git commit`，仅在特性分支 `feat/vector-reembed` 上；未获授权**不 push**。
- 单测纯 Mockito（不启 Spring）；IT 类名以 `IT` 结尾、继承 `com.qingyu.care.support.AbstractPostgresIT`。
- 多租户：范围/端点走现有归属守卫（租户管理员限本应用 `APP`/`KB`；平台管理员可 `ALL`）；`@Async` 内**无 TenantContext**，租户/应用取自**实体行**（同 processDocument/embed 既有教训）。
- **护栏拒绝式后果**：合入后存量未标向量立即被检索排除；须跑一次全量重嵌打标带回（Task 4 dev 清旧向量即适配）。
- 既有事实：`toFilterExpression()` 无参、仅 2 生产调用点（`ChatServiceImpl:574`、`HybridRetrievalServiceImpl:47`），无测试直调；`processDocument` 重嵌前不删旧向量；`QaPairService.embed` package-private；`VectorStoreRepository` 有 `deleteByDocumentId/deleteByQaPairId`；`DocumentRepository.findByKnowledgeBaseIdAndStatus`、`QaPairRepository.findByKnowledgeBaseIdAndStatus` 已存在；枚举 `DocumentStatus.COMPLETED` / `QaPairStatus.ACTIVE`；`knowledgeBaseRepository.findByAppId(appId)` / `findAll()` 已用。

---

## 文件结构
- 新增 `service/EmbeddingModelProvider.java`：`@Value` 持当前 embedding 模型名。
- 改 `service/impl/KnowledgeServiceImpl.java`：`processDocument` metadata 加 `embeddingModel` + **起始先 `deleteByDocumentId`（幂等，修 reprocess 重复 + 供 reembed 复用）**；`processDocument` 提到 `KnowledgeService` 接口（供 ReembedService 同步调用）。
- 改 `service/QaPairService.java`：`embed` metadata 加 `embeddingModel` + 新增 public `reembed(QaPair)`。
- 改 `service/RetrievalScope.java`：`toFilterExpression(String embeddingModel)` 追加 `&& embeddingModel == '<model>'`。
- 改 `service/impl/ChatServiceImpl.java` + `service/impl/HybridRetrievalServiceImpl.java`：注入 `EmbeddingModelProvider`，调用 `toFilterExpression(provider.getModel())`。
- 改 `repository/KeywordSearchDao.java`：注入 provider + SQL 加 `AND metadata->>'embeddingModel' = ?`。
- 新增 `service/ReembedService.java`：编排批重嵌 + 内存 job 进度。
- 新增 `dto/ReembedProgress.java`（或内嵌 record）。
- 改 `controller/KnowledgeController.java`：`POST /reembed` + `GET /reembed/{jobId}` 端点 + `@AuditLog`。
- 单测：`EmbeddingModelProviderTest`、`QaPairEmbedTagTest`、`RetrievalScopeFilterTest`、`ReembedServiceTest`。
- IT：`KeywordSearchEmbeddingModelFilterIT`。

---

### Task 1: embeddingModel 打标（Provider + 写入侧）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/EmbeddingModelProvider.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/QaPairService.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/KnowledgeServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/service/EmbeddingModelProviderTest.java`、`backend/src/test/java/com/qingyu/care/service/QaPairEmbedTagTest.java`

**Interfaces:**
- Produces：`EmbeddingModelProvider.getModel() -> String`（当前 embedding 模型名）；写入的向量 metadata 含键 `embeddingModel`。

- [ ] **Step 1：写 `EmbeddingModelProvider`**

```java
package com.qingyu.care.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 当前 embedding 模型名的单一来源（读 spring.ai.openai.embedding.options.model）。
 * 写入向量打标、检索护栏、重嵌共用，避免各处散读配置漂移。
 *
 * @author ruan
 */
@Component
public class EmbeddingModelProvider {

    private final String model;

    public EmbeddingModelProvider(@Value("${spring.ai.openai.embedding.options.model}") String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
    }
}
```

- [ ] **Step 2：写 `EmbeddingModelProviderTest`（先失败）**

```java
package com.qingyu.care.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingModelProviderTest {
    @Test
    void 返回构造注入的模型名() {
        assertThat(new EmbeddingModelProvider("qwen3.7-text-embedding").getModel())
                .isEqualTo("qwen3.7-text-embedding");
    }
}
```

- [ ] **Step 3：写 `QaPairEmbedTagTest`（先失败）**

先 Read `service/QaPairService.java` 的 `embed`（约 120-131）与构造依赖，确认 `@RequiredArgsConstructor` 字段。`embed` 注入 `EmbeddingModelProvider` 后，单测验证写入的 `Document` metadata 含 `embeddingModel`：

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA 嵌入写入的向量 metadata 含 embeddingModel 标。 */
@ExtendWith(MockitoExtension.class)
class QaPairEmbedTagTest {

    @Mock VectorStore vectorStore;
    @Mock com.qingyu.care.repository.QaPairRepository qaPairRepository;
    @Mock com.qingyu.care.repository.VectorStoreRepository vectorStoreRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock EmbeddingModelProvider embeddingModelProvider;

    @Test
    void embed写入metadata含embeddingModel() {
        when(embeddingModelProvider.getModel()).thenReturn("qwen3.7-text-embedding");
        // 用真实构造装配 QaPairService（按其 @RequiredArgsConstructor 字段顺序传 mock；
        // 实现前 Read QaPairService 确认构造参数与顺序）
        QaPairService service = new QaPairService(qaPairRepository, vectorStore, vectorStoreRepository,
                knowledgeService, embeddingModelProvider);

        QaPair qa = QaPair.builder().question("营业时间").answer("9-18")
                .knowledgeBase(KnowledgeBase.builder().id(7L).build()).build();
        qa.setId(3L); qa.setTenantId(1L); qa.setAppId(2L);

        service.embed(qa);

        ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(cap.capture());
        assertThat(cap.getValue().get(0).getMetadata()).containsEntry("embeddingModel", "qwen3.7-text-embedding");
    }
}
```
> 注：`QaPairService` 现有构造字段以实际代码为准（Read 确认）；`EmbeddingModelProvider` 加入其 `@RequiredArgsConstructor`（末位或按现有顺序）。若 `QaPair.setId/setTenantId/setAppId` 来自 `BaseTenantEntity`（@Builder 不含父类字段），用 setter 赋值（同既有 IT 手法）。

- [ ] **Step 4：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=EmbeddingModelProviderTest,QaPairEmbedTagTest`
Expected: 编译失败（`EmbeddingModelProvider` 不存在 / `QaPairService` 构造未含 provider）。

- [ ] **Step 5：实现打标**

1) `QaPairService`：`@RequiredArgsConstructor` 字段加 `private final EmbeddingModelProvider embeddingModelProvider;`；`embed(qa)` 的 metadata 加：
```java
        md.put("embeddingModel", embeddingModelProvider.getModel());
```
2) `KnowledgeServiceImpl`：构造器补 `EmbeddingModelProvider embeddingModelProvider`（该类是手写构造非 Lombok——Read 确认后加到构造参数 + 字段 + 赋值）；`processDocument` 的 chunk metadata `Map.of(...)` 加一项：
```java
                        "embeddingModel", embeddingModelProvider.getModel()
```
（`Map.of` 最多 10 键，现 7 键 + embeddingModel = 8，OK。）

- [ ] **Step 6：运行确认通过 + 全量单测**

Run: `cd backend && mvn -q test -Dtest=EmbeddingModelProviderTest,QaPairEmbedTagTest`
Expected: PASS。
再跑 `cd backend && mvn -q test` 全量无回归（⚠️ `KnowledgeServiceImpl`/`QaPairService` 既有单测若因构造新增依赖编译失败，补 `@Mock EmbeddingModelProvider` 到那些测试类）。

- [ ] **Step 7：提交**

```bash
cd backend && git add -A && git commit -m "feat: 向量写入打 embeddingModel 标（EmbeddingModelProvider + processDocument/QaPair.embed）"
```

---

### Task 2: 检索护栏（拒绝式：向量路 + 关键词路）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/RetrievalScope.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/HybridRetrievalServiceImpl.java`
- Modify: `backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java`
- Test: `backend/src/test/java/com/qingyu/care/service/RetrievalScopeFilterTest.java`、`backend/src/test/java/com/qingyu/care/it/KeywordSearchEmbeddingModelFilterIT.java`

**Interfaces:**
- Consumes：`EmbeddingModelProvider.getModel()`（Task 1）。
- Produces：`RetrievalScope.toFilterExpression(String embeddingModel) -> String`（签名改为带参）。

- [ ] **Step 1：写 `RetrievalScopeFilterTest`（先失败）**

```java
package com.qingyu.care.service;

import com.qingyu.care.enums.DocumentVisibility;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalScopeFilterTest {
    @Test
    void 过滤表达式追加embeddingModel等值() {
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.INTERNAL);
        String expr = scope.toFilterExpression("qwen3.7-text-embedding");
        assertThat(expr).contains("appId == '2'")
                .contains("knowledgeBaseId in ['10']")
                .contains("docEnabled == 'true'")
                .contains("embeddingModel == 'qwen3.7-text-embedding'");
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=RetrievalScopeFilterTest`
Expected: 编译失败（`toFilterExpression(String)` 不存在）。

- [ ] **Step 3：改 `RetrievalScope.toFilterExpression`**

签名加参、末尾追加 embeddingModel 等值（放在 return 前）：
```java
    public String toFilterExpression(String embeddingModel) {
        if (isEmpty()) {
            throw new IllegalStateException("空检索范围不可拼接过滤表达式（调用方应先判 isEmpty() 短路）");
        }
        StringBuilder sb = new StringBuilder();
        if (tenantId != null) {
            sb.append("tenantId == '").append(tenantId).append("' && ");
        }
        sb.append("appId == '").append(appId).append("'");
        sb.append(" && knowledgeBaseId in [")
          .append(kbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",")))
          .append("]");
        sb.append(" && docEnabled == 'true'");
        if (accessLevel == DocumentVisibility.PUBLIC) {
            sb.append(" && visibility == 'PUBLIC'");
        }
        // 护栏：只匹配当前 embedding 模型嵌的向量，跨模型/未标向量静默排除（防混空间检索出垃圾）
        sb.append(" && embeddingModel == '").append(embeddingModel).append("'");
        return sb.toString();
    }
```

- [ ] **Step 4：两处向量路调用点传入当前模型**

- `ChatServiceImpl`：`@RequiredArgsConstructor` 字段加 `private final EmbeddingModelProvider embeddingModelProvider;`；约 574 行 `.filterExpression(scope.toFilterExpression())` → `.filterExpression(scope.toFilterExpression(embeddingModelProvider.getModel()))`。
- `HybridRetrievalServiceImpl`：同样注入 provider；约 47 行同款改。

- [ ] **Step 5：关键词路 `KeywordSearchDao` 加过滤**

`KeywordSearchDao` `@RequiredArgsConstructor` 字段加 `private final EmbeddingModelProvider embeddingModelProvider;`；`search` 的 SQL 在 `docEnabled` 等值后加 embeddingModel 子句：
```java
        sql.append("AND metadata ->> 'docEnabled' = 'true' ");
        sql.append("AND metadata ->> 'embeddingModel' = ? ");
        params.add(embeddingModelProvider.getModel());
```
（放在 docEnabled 之后、visibility/tenantId 之前，params 顺序与 sql 拼接顺序严格对应。）

- [ ] **Step 6：写护栏 IT `KeywordSearchEmbeddingModelFilterIT`**

仿 `it/KeywordSearchDaoIT.java`（Read 其播种手法），jdbcTemplate 播种三条 vector_store 行（同 appId/kb/tenant/docEnabled/内容含关键词，仅 `embeddingModel` 不同：当前模型 / 旧模型 / 无该键），断言 `keywordSearchDao.search` 只命中**当前模型**那条：
```java
// 当前模型来自注入的 EmbeddingModelProvider（@Autowired 到 IT 或从配置取）；
// 播种时把「当前模型」那条的 embeddingModel 设为 provider.getModel()，另两条设为 "old-model" 与不写该键。
// 断言 hits.size()==1 且命中的是当前模型那条。
```
> 该 IT 依赖 content_tsv（zhparser）——IT 容器已具备（同 KeywordSearchDaoIT）。`@AfterEach` 按 tenantId 清理。

- [ ] **Step 7：修既有检索 IT 的种子（护栏拒绝式的直接连带）**

护栏给检索加 `embeddingModel ==` 过滤后，既有检索 IT 用 jdbcTemplate 播种的 chunk **没写 embeddingModel 键 → 会被护栏排除、断言失败**。给下列 IT 的 `seedChunk`/`seedChunkWithScope` 等播种方法的 metadata JSON **补 `"embeddingModel":"<当前模型>"`**（当前模型 = `spring.ai.openai.embedding.options.model` 的 test profile 值；实现时从配置/`EmbeddingModelProvider` 取或直接硬编码 test 值，与 `KeywordSearchDao` 过滤用的一致）：
- `it/KeywordSearchDaoIT.java`（`seedChunkWithScope`）
- `it/HybridRetrievalIT.java`（`seedChunk`）
- `it/DocVisibilityRetrievalIT.java`、`it/MultiKbRetrievalIT.java`（各自 chunk 播种处）
Read 各 IT 确认播种方法与 test profile 的 embedding 模型名，逐一补键。

- [ ] **Step 8：运行 + 全量**

Run: `cd backend && mvn -q test -Dtest=RetrievalScopeFilterTest`（PASS）；`cd backend && mvn -q verify -Dit.test=KeywordSearchEmbeddingModelFilterIT,KeywordSearchDaoIT,HybridRetrievalIT,DocVisibilityRetrievalIT,MultiKbRetrievalIT -DfailIfNoTests=false`（全 PASS，需 Docker——验证种子补键后既有检索 IT 不回归 + 新护栏 IT 绿）。
⚠️ `ChatServiceImpl`/`HybridRetrievalServiceImpl` 新增 `EmbeddingModelProvider` 依赖 → **所有 `@InjectMocks ChatServiceImpl`/`HybridRetrievalServiceImpl` 单测类补 `@Mock EmbeddingModelProvider`**（否则构造 null → NPE；对调用 `toFilterExpression` 的用例还需 `when(provider.getModel()).thenReturn("m")`）。跑全量 `mvn -q test` 确认无因此 NPE 回归。

- [ ] **Step 9：提交**

```bash
cd backend && git add -A && git commit -m "feat: 检索护栏拒绝式——向量路+关键词路只匹配当前 embeddingModel，跨模型/未标向量排除（含既有检索 IT 种子补键）"
```

---

### Task 3: ReembedService 重建批任务 + 端点 + processDocument 幂等

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/KnowledgeServiceImpl.java`（processDocument 起始先删 + 提到接口）
- Modify: `backend/src/main/java/com/qingyu/care/service/KnowledgeService.java`（接口加 `processDocument`）
- Modify: `backend/src/main/java/com/qingyu/care/service/QaPairService.java`（加 `reembed`）
- Create: `backend/src/main/java/com/qingyu/care/service/ReembedService.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/KnowledgeController.java`（端点）
- Test: `backend/src/test/java/com/qingyu/care/service/ReembedServiceTest.java`

**Interfaces:**
- Consumes：`KnowledgeService.processDocument(Long)`（幂等，delete-first）、`QaPairService.reembed(QaPair)`、`knowledgeBaseRepository.findByAppId/findAll`、`DocumentRepository.findByKnowledgeBaseIdAndStatus`、`QaPairRepository.findByKnowledgeBaseIdAndStatus`。
- Produces：`ReembedService.startReembed(scope, kbId) -> String jobId`、`ReembedService.getProgress(jobId) -> ReembedProgress`；`ReembedProgress`(record: jobId,scope,total,done,failed,status,startedAt,finishedAt)。

- [ ] **Step 1：processDocument 起始先删（幂等）+ 提到接口**

先 Read `KnowledgeServiceImpl.processDocument`（217-）与 `KnowledgeService` 接口。在 `processDocument` 内 `documentRepository.findById` 拿到非空 doc 后、`setStatus(PROCESSING)` 附近，加：
```java
            // 幂等：重嵌/重处理前先删旧向量，避免重复（初次上传时删 0 行无害）
            vectorStoreRepository.deleteByDocumentId(documentId.toString());
```
并把 `void processDocument(Long documentId);` 加入 `KnowledgeService` 接口（若未在）。

- [ ] **Step 2：QaPairService 加 public `reembed`**

```java
    /** 重嵌一条 QA（删旧向量 + 用当前模型重嵌），供批量重建复用。 */
    public void reembed(QaPair qa) {
        vectorStoreRepository.deleteByQaPairId(qa.getId().toString());
        embed(qa);
    }
```

- [ ] **Step 3：写 `ReembedServiceTest`（先失败）**

单测（Mockito）：`startReembed(APP, null)` 在有 appId 上下文时——解析该 app 的 KB → 每 KB 的 COMPLETED 文档调 `knowledgeService.processDocument(id)`、ACTIVE QA 调 `qaPairService.reembed(qa)`；单条抛异常 → failed++ 不中断；进度 total/done/failed 正确。用 `@Mock` knowledgeService/qaPairService/knowledgeBaseRepository/documentRepository/qaPairRepository。注意 `@Async` 方法在单测中直接同步调用其实现（或把编排逻辑抽成同步 `doReembed(job)` 方法便于测，`startReembed` 只建 job + 触发）。示例断言：
```java
// 2 KB, 每 KB 2 COMPLETED 文档 + 1 ACTIVE QA; 其中一个 processDocument 抛异常
// → verify processDocument 调 4 次、reembed 调 2 次; progress.total==6, done==5, failed==1
```

- [ ] **Step 4：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ReembedServiceTest`
Expected: 编译失败（`ReembedService` 不存在）。

- [ ] **Step 5：写 `ReembedService`**

```java
package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 向量重建（换 embedding 模型迁移）：按范围重嵌 active 文档 + QA 到当前模型空间并打标。
 * 每条 delete+重嵌幂等；@Async 跑，内存进度；单条 fail-soft 不中断整批。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReembedService {

    public enum Scope { ALL, APP, KB }

    /** 内存进度（重启不留存；幂等重跑即续）。 */
    public static final class Progress {
        public final String jobId; public final String scope;
        public volatile int total; public final AtomicInteger done = new AtomicInteger();
        public final AtomicInteger failed = new AtomicInteger();
        public volatile String status; public final LocalDateTime startedAt = LocalDateTime.now();
        public volatile LocalDateTime finishedAt;
        Progress(String jobId, String scope) { this.jobId = jobId; this.scope = scope; this.status = "RUNNING"; }
    }

    private final KnowledgeService knowledgeService;
    private final QaPairService qaPairService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final QaPairRepository qaPairRepository;
    private final ReembedService self;   // 自注入触发 @Async（见 setter）

    private final ConcurrentHashMap<String, Progress> jobs = new ConcurrentHashMap<>();

    /** 触发重嵌，返回 jobId（异步执行）。范围归属校验在请求线程完成。 */
    public String startReembed(Scope scope, Long knowledgeBaseId) {
        List<KnowledgeBase> kbs = resolveKbs(scope, knowledgeBaseId);
        String jobId = UUID.randomUUID().toString();
        Progress p = new Progress(jobId, scope.name());
        jobs.put(jobId, p);
        self.runReembed(p, kbs.stream().map(KnowledgeBase::getId).toList());
        return jobId;
    }

    public Progress getProgress(String jobId) {
        Progress p = jobs.get(jobId);
        if (p == null) throw new BusinessException(404, "重建任务不存在: " + jobId);
        return p;
    }

    /** 范围解析 + 归属校验（请求线程，依赖 TenantContext）。 */
    private List<KnowledgeBase> resolveKbs(Scope scope, Long knowledgeBaseId) {
        return switch (scope) {
            case ALL -> {
                if (!TenantOwnershipGuard.isPlatformAdmin()) {
                    throw new BusinessException(403, "全平台重建仅限平台管理员");
                }
                yield knowledgeBaseRepository.findAll();
            }
            case APP -> {
                Long appId = TenantContext.getAppId();
                if (appId == null) throw new BusinessException(400, "未选择应用");
                yield knowledgeBaseRepository.findByAppId(appId);
            }
            case KB -> {
                KnowledgeBase kb = knowledgeService.getKnowledgeBase(knowledgeBaseId); // 归属校验（越权抛）
                yield List.of(kb);
            }
        };
    }

    /** 异步执行：遍历 KB → COMPLETED 文档重嵌 + ACTIVE QA 重嵌；单条 fail-soft。 */
    @Async
    public void runReembed(Progress p, List<Long> kbIds) {
        try {
            List<Document> docs = kbIds.stream()
                    .flatMap(kb -> documentRepository.findByKnowledgeBaseIdAndStatus(kb, DocumentStatus.COMPLETED).stream())
                    .toList();
            List<QaPair> qas = kbIds.stream()
                    .flatMap(kb -> qaPairRepository.findByKnowledgeBaseIdAndStatus(kb, QaPairStatus.ACTIVE).stream())
                    .toList();
            p.total = docs.size() + qas.size();
            for (Document d : docs) {
                try { knowledgeService.processDocument(d.getId()); p.done.incrementAndGet(); }
                catch (Exception e) { p.failed.incrementAndGet(); log.warn("文档重嵌失败 id={}: {}", d.getId(), e.getMessage()); }
            }
            for (QaPair q : qas) {
                try { qaPairService.reembed(q); p.done.incrementAndGet(); }
                catch (Exception e) { p.failed.incrementAndGet(); log.warn("QA 重嵌失败 id={}: {}", q.getId(), e.getMessage()); }
            }
            p.status = "COMPLETED";
        } catch (Exception e) {
            p.status = "FAILED"; log.error("重建任务异常 jobId={}: {}", p.jobId, e.getMessage(), e);
        } finally {
            p.finishedAt = LocalDateTime.now();
        }
    }
}
```
> 注：`self` 自注入触发 `@Async`（同 KnowledgeServiceImpl 范式）——加 `@Autowired @Lazy setSelf(...)`；或改用注入 `ApplicationContext`/事件。以最小可行为准，实现前 Read `KnowledgeServiceImpl` 的 self 范式照搬。`TenantOwnershipGuard.isPlatformAdmin()` 若不存在，用 `SecurityUtil.isPlatformAdmin()`（Read 确认可用者）。

- [ ] **Step 6：KnowledgeController 加端点**

先 Read `KnowledgeController`（`@RequestMapping("/v1/knowledge")`，reprocess 端点在 149）。加：
```java
    @PostMapping("/reembed")
    @AuditLog(action = "VECTOR_REEMBED", targetType = "KnowledgeBase")
    @Operation(summary = "重建向量索引", description = "换 embedding 模型后按范围重嵌 active 文档+QA")
    public ApiResponse<java.util.Map<String, String>> reembed(
            @RequestParam ReembedService.Scope scope,
            @RequestParam(required = false) Long knowledgeBaseId) {
        String jobId = reembedService.startReembed(scope, knowledgeBaseId);
        return ApiResponse.success(java.util.Map.of("jobId", jobId));
    }

    @GetMapping("/reembed/{jobId}")
    @Operation(summary = "重建进度")
    public ApiResponse<ReembedService.Progress> reembedProgress(@PathVariable String jobId) {
        return ApiResponse.success(reembedService.getProgress(jobId));
    }
```
（`KnowledgeController` 注入 `ReembedService`。import `@AuditLog`/`@Operation` 与现有一致。）

- [ ] **Step 7：运行确认通过 + 全量**

Run: `cd backend && mvn -q test -Dtest=ReembedServiceTest`（PASS）；全量 `cd backend && mvn -q test`（无回归）。

- [ ] **Step 8：提交**

```bash
cd backend && git add -A && git commit -m "feat: ReembedService 重建向量索引批任务(范围ALL/APP/KB+异步内存进度+fail-soft)+端点; processDocument 幂等 delete-first + QaPair.reembed"
```

---

### Task 4: 收尾（dev 清旧向量 + 全量回归）

- [ ] **Step 1：dev 清换模型前的旧向量（无当前模型标）**

在 dev postgres 清掉换 embedding 模型前嵌的旧向量（否则被护栏排除后成残留死数据）：
```bash
docker exec qingyu-postgres psql -U postgres -d qingyu_care -c \
  "DELETE FROM vector_store WHERE metadata->>'embeddingModel' IS DISTINCT FROM 'qwen3.7-text-embedding';"
```
（dev 数据极少；生产则改为「部署后跑一次 POST /v1/knowledge/reembed?scope=ALL」而非删。）

- [ ] **Step 2：全量 verify**

Run: `cd backend && mvn -q verify`
Expected: BUILD SUCCESS，全部单测 + IT 绿（重点：三个检索 IT `HybridRetrievalIT`/`DocVisibilityRetrievalIT`/`MultiKbRetrievalIT` 仍绿——它们的种子若无 embeddingModel 标会被新护栏排除致失败，须确认这些 IT 的 chunk 播种补上 `embeddingModel`=当前模型键，或其断言容忍空命中；实现时按真实结果修 IT 种子）。

- [ ] **Step 3：留债登记**
真机 re-smoke（重嵌后检索命中）待验；持久化断点续/重嵌前端 UI 作后续。

## Self-Review

**1. Spec coverage：** §3.1 Provider→T1；§3.2 写入打标→T1；§3.3 护栏(向量路+关键词路)→T2；§3.4 ReembedService+端点→T3；§3.5 reprocess 幂等→T3 Step1；§3.6 dev 清理→T4；§4 部署语义→Global Constraints+T4；§6 测试→各任务单测+IT。无遗漏。
**2. Placeholder scan：** 无 TBD；Provider/护栏/ReembedService 均给完整代码；ReembedServiceTest/护栏IT 给了断言意图 + 具体播种/verify 要求（非「写测试」空话）。self 自注入/isPlatformAdmin 标注「Read 确认照搬」属既有范式引用，非模糊要求。
**3. Type consistency：** `EmbeddingModelProvider.getModel()`、`toFilterExpression(String)`、`ReembedService.Scope{ALL,APP,KB}`/`startReembed(Scope,Long)->String`/`getProgress(String)->Progress`、`QaPairService.reembed(QaPair)`、`KnowledgeService.processDocument(Long)`、metadata 键 `embeddingModel` 前后一致。

**⚠️ 跨切影响（每任务实现者必读）**：T1 给 `KnowledgeServiceImpl`/`QaPairService`、T2 给 `ChatServiceImpl`/`HybridRetrievalServiceImpl`/`KeywordSearchDao` 新增 `EmbeddingModelProvider` 构造依赖——**所有 @InjectMocks 这些类的既有单测须补 `@Mock EmbeddingModelProvider`**（否则 NPE），每任务末尾跑全量 `mvn test` 兜底。

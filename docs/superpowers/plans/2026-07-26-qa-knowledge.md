# QA 问答对知识库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让租户维护 QA 问答对，整条 `问+答` 不切分嵌入共享 pgvector 库、走现有检索；支持 LLM 从文档自动生成 QA 草稿供人工复核。

**Architecture:** 新增 `QaPair` 实体（挂现有 `KnowledgeBase`）+ `QaPairService`（CRUD + 向量生命周期）+ `QaGenerationService`（@Async LLM 生成草稿）。QA 的 ACTIVE 向量带齐 `RetrievalScope` 过滤所需 metadata 键，与文档块一起被现有 `searchKnowledge` 检索——检索热路径零改动。

**Tech Stack:** Spring Boot 3.5 / Spring AI 1.1.8（`VectorStore.add` / `Document`）/ JPA + pgvector / React 19 + shadcn。

## Global Constraints

- QA 的 ACTIVE 向量**必带** `RetrievalScope.toFilterExpression` 所需 5 键：`tenantId` / `appId` / `knowledgeBaseId` / `docEnabled=='true'` / `visibility=='PUBLIC'`（缺键行被等值过滤静默筛掉）。
- **不切分**：只建一条 `org.springframework.ai.document.Document` 直接 `vectorStore.add`，不经 `TokenTextSplitter`。
- QA 向量的 `tenantId`/`appId` 取自 **qaPair 行**（`qa.getTenantId()/getAppId()`），非 `TenantContext`；`@Async` 生成时 QaPair 的 tenant/app 显式从**源文档行**回填（`@Async` 线程无 TenantContext，沿用 `processDocument` 教训）。
- LLM 生成用 `ModelRouterService.callWithFallback("default", ...)`，**不计量**（内部管理，非开放 API）。
- 归属校验：QaPair（继承 `BaseTenantEntity`）按 id 加载后用 `TenantOwnershipGuard.belongsToCurrentContext`；经 kbId/documentId 的入口先 `KnowledgeService.getKnowledgeBase/getDocument` 校验。
- 检索侧 `ChatServiceImpl.searchKnowledge` / `HybridRetrievalService` / `buildContext` **一行不改**。
- git：允许 commit（`feat/qa-knowledge` 分支），未授权不 push；注释/commit 简体中文（作者 ruan）。
- 测试：单测优先纯 Mockito（mock `VectorStore`）；IT 继承 `AbstractPostgresIT`（需 Docker）。

---

## 文件结构

**新增（后端）**
- `enums/QaPairSource.java`（MANUAL/GENERATED）、`enums/QaPairStatus.java`（DRAFT/ACTIVE/DISABLED）
- `entity/QaPair.java`、`repository/QaPairRepository.java`
- `dto/QaPairRequest.java`
- `service/QaPairService.java`（CRUD + 向量生命周期）
- `service/QaGenerationService.java`（@Async LLM 生成）
- `controller/QaPairController.java`

**修改（后端）**
- `repository/VectorStoreRepository.java` — 加 `deleteByQaPairId` / `updateDocEnabledByQaPairId`

**新增（前端）**
- `api/qa.ts`、`components/QaPairPanel.tsx`

**修改（前端）**
- `pages/KnowledgePage.tsx` — 右侧加「文档 / 问答对」tab

**测试**
- `test/.../service/QaPairServiceTest.java`、`service/QaGenerationServiceTest.java`、`it/QaPairPersistenceIT.java`

---

## Phase A — 后端核心

### Task A1: QaPair 实体 / 枚举 / 仓库 / 请求 DTO

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/enums/QaPairSource.java`
- Create: `backend/src/main/java/com/qingyu/care/enums/QaPairStatus.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/QaPair.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/QaPairRepository.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/QaPairRequest.java`

**Interfaces:**
- Produces: `QaPair`（extends BaseTenantEntity；字段 id/knowledgeBase/question/answer/source/status/sourceDocumentId/时间戳）；`QaPairRepository.findByKnowledgeBaseId(Long)` / `findByKnowledgeBaseIdAndStatus(Long, QaPairStatus)`；`QaPairRequest{question,answer}`。

- [ ] **Step 1: 写枚举**

`QaPairSource.java`:
```java
package com.qingyu.care.enums;

/** QA 问答对来源：手动录入 / LLM 从文档生成 */
public enum QaPairSource {
    MANUAL, GENERATED
}
```
`QaPairStatus.java`:
```java
package com.qingyu.care.enums;

/** QA 问答对状态：DRAFT=生成待复核(无向量) / ACTIVE=已嵌入可检索 / DISABLED=停用(保向量, docEnabled=false) */
public enum QaPairStatus {
    DRAFT, ACTIVE, DISABLED
}
```

- [ ] **Step 2: 写实体**

```java
package com.qingyu.care.entity;

import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * QA 问答对：挂现有知识库；整条「问+答」不切分嵌入共享向量库，供 FAQ 检索。
 */
@Entity
@Table(name = "qa_pair")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaPair extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库（检索范围=enabled 库集合，天然含之） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    @Column(columnDefinition = "text", nullable = false)
    private String question;

    @Column(columnDefinition = "text", nullable = false)
    private String answer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private QaPairSource source = QaPairSource.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private QaPairStatus status = QaPairStatus.ACTIVE;

    /** LLM 生成来源文档 id（GENERATED 时记录） */
    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: 写仓库 + 请求 DTO**

`QaPairRepository.java`:
```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QaPairRepository extends JpaRepository<QaPair, Long> {

    /** 某知识库下的全部 QA 对（kbId 归属由调用方先校验） */
    List<QaPair> findByKnowledgeBaseId(Long knowledgeBaseId);

    /** 某知识库下按状态过滤（如复核 DRAFT 列表） */
    List<QaPair> findByKnowledgeBaseIdAndStatus(Long knowledgeBaseId, QaPairStatus status);
}
```
`QaPairRequest.java`:
```java
package com.qingyu.care.dto;

import lombok.Data;

/** QA 问答对创建/更新请求 */
@Data
public class QaPairRequest {
    private String question;
    private String answer;
}
```

- [ ] **Step 4: 编译确认**

Run: `cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/enums/QaPairSource.java backend/src/main/java/com/qingyu/care/enums/QaPairStatus.java backend/src/main/java/com/qingyu/care/entity/QaPair.java backend/src/main/java/com/qingyu/care/repository/QaPairRepository.java backend/src/main/java/com/qingyu/care/dto/QaPairRequest.java
git commit -m "feat: 新增 QaPair 实体/枚举/仓库/请求 DTO"
```

---

### Task A2: VectorStoreRepository 增 QA 向量 native 操作

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/VectorStoreRepository.java`

**Interfaces:**
- Produces: `VectorStoreRepository.deleteByQaPairId(String)` / `updateDocEnabledByQaPairId(String, String)`。

- [ ] **Step 1: 追加两个 native 方法（仿既有 documentId 版）**

在 `updateDocEnabledByDocumentId` 之后、类结束 `}` 之前追加：
```java
    /** 按 metadata 中的 qaPairId 删除向量（删除/重嵌 QA 前清旧）。 */
    @Modifying
    @Query(value = "DELETE FROM vector_store WHERE metadata->>'qaPairId' = :qaPairId", nativeQuery = true)
    int deleteByQaPairId(@Param("qaPairId") String qaPairId);

    /** 按 qaPairId 同步 QA 向量的启用标记（停用不删向量，重新启用免重嵌入）。metadata 列是 json，须先转 jsonb。 */
    @Modifying
    @Query(value = "UPDATE vector_store SET metadata = jsonb_set(metadata::jsonb, '{docEnabled}', to_jsonb(:enabled)) "
            + "WHERE metadata->>'qaPairId' = :qaPairId", nativeQuery = true)
    int updateDocEnabledByQaPairId(@Param("qaPairId") String qaPairId, @Param("enabled") String enabled);
```

- [ ] **Step 2: 编译确认**

Run: `cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/repository/VectorStoreRepository.java
git commit -m "feat: VectorStoreRepository 增 QA 向量 deleteByQaPairId/updateDocEnabledByQaPairId"
```

---

### Task A3: QaPairService（CRUD + 向量生命周期）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/QaPairService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/QaPairServiceTest.java`

**Interfaces:**
- Consumes: `QaPairRepository`（A1）、`KnowledgeService`、`VectorStore`、`VectorStoreRepository`（A2）、`TenantOwnershipGuard`。
- Produces: `QaPairService`：`list(Long)` / `create(Long,String,String)` / `getQaPair(Long)` / `update(Long,String,String)` / `approve(Long)` / `setEnabled(Long,boolean)` / `delete(Long)`；package-private `embed(QaPair)`。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QaPairServiceTest {

    @Mock QaPairRepository qaPairRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock VectorStore vectorStore;
    @Mock VectorStoreRepository vectorStoreRepository;

    private QaPairService service() {
        return new QaPairService(qaPairRepository, knowledgeService, vectorStore, vectorStoreRepository);
    }

    private KnowledgeBase kb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        return kb;
    }

    private QaPair active(long id) {
        QaPair qa = QaPair.builder().id(id).knowledgeBase(kb()).question("如何退货").answer("7天内订单页申请")
                .source(QaPairSource.MANUAL).status(QaPairStatus.ACTIVE).build();
        qa.setTenantId(11L);
        qa.setAppId(22L);
        return qa;
    }

    @Test
    void create_savesActiveAndEmbedsSingleUnsplitVectorWithRequiredKeys() {
        when(knowledgeService.getKnowledgeBase(7L)).thenReturn(kb());
        when(qaPairRepository.save(any())).thenAnswer(inv -> {
            QaPair q = inv.getArgument(0);
            q.setId(5L);
            q.setTenantId(11L);
            q.setAppId(22L);
            return q;
        });

        service().create(7L, "如何退货", "7天内订单页申请");

        ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(cap.capture());
        assertThat(cap.getValue()).hasSize(1); // 不切分：单条
        Document d = cap.getValue().get(0);
        assertThat(d.getText()).isEqualTo("问：如何退货\n答：7天内订单页申请");
        // RetrievalScope 过滤 5 必需键 + qaPairId/contentType
        assertThat(d.getMetadata())
                .containsEntry("tenantId", "11").containsEntry("appId", "22")
                .containsEntry("knowledgeBaseId", "7").containsEntry("docEnabled", "true")
                .containsEntry("visibility", "PUBLIC").containsEntry("qaPairId", "5")
                .containsEntry("contentType", "qa");
    }

    @Test
    void approve_draftToActive_embeds() {
        QaPair draft = active(5L);
        draft.setSource(QaPairSource.GENERATED);
        draft.setStatus(QaPairStatus.DRAFT);
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(draft));
        when(qaPairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        QaPair result = service().approve(5L);

        assertThat(result.getStatus()).isEqualTo(QaPairStatus.ACTIVE);
        verify(vectorStore).add(any());
    }

    @Test
    void setEnabled_false_flipsDocEnabledKeepsVector() {
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(active(5L)));
        when(qaPairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().setEnabled(5L, false);

        verify(vectorStoreRepository).updateDocEnabledByQaPairId("5", "false");
        verify(vectorStore, never()).add(any()); // 停用不重嵌
    }

    @Test
    void delete_removesVectorThenRow() {
        QaPair qa = active(5L);
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(qa));

        service().delete(5L);

        verify(vectorStoreRepository).deleteByQaPairId("5");
        verify(qaPairRepository).delete(qa);
    }

    @Test
    void update_activeContentChanged_reEmbeds() {
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(active(5L)));
        when(qaPairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().update(5L, "怎么退货", "改后的答案");

        verify(vectorStoreRepository).deleteByQaPairId("5");
        verify(vectorStore).add(any());
    }

    @Test
    void getQaPair_crossTenant_throwsNotFound() {
        QaPair other = active(5L);
        other.setTenantId(999L); // 他租户
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(other));
        com.qingyu.care.config.TenantContext.setTenantId(11L);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().getQaPair(5L))
                    .isInstanceOf(com.qingyu.care.exception.ResourceNotFoundException.class);
        } finally {
            com.qingyu.care.config.TenantContext.clear();
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=QaPairServiceTest`
Expected: 编译失败 / `QaPairService` 不存在。

- [ ] **Step 3: 写实现**

```java
package com.qingyu.care.service;

import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QA 问答对服务：CRUD + 向量生命周期（整条「问+答」不切分嵌入共享向量库）。
 * 归属校验：按 id 加载后 belongsToCurrentContext；经 kbId 入口先 KnowledgeService.getKnowledgeBase 校验。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QaPairService {

    private final QaPairRepository qaPairRepository;
    private final KnowledgeService knowledgeService;
    private final VectorStore vectorStore;
    private final VectorStoreRepository vectorStoreRepository;

    public List<QaPair> list(Long knowledgeBaseId) {
        knowledgeService.getKnowledgeBase(knowledgeBaseId); // 归属校验（越权抛）
        return qaPairRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }

    @Transactional
    public QaPair create(Long knowledgeBaseId, String question, String answer) {
        KnowledgeBase kb = knowledgeService.getKnowledgeBase(knowledgeBaseId);
        QaPair qa = qaPairRepository.save(QaPair.builder()
                .knowledgeBase(kb).question(question).answer(answer)
                .source(QaPairSource.MANUAL).status(QaPairStatus.ACTIVE).build());
        embed(qa);
        return qa;
    }

    /** 按 id 取 QA 对，findById 不受 @Filter 保护，显式补归属校验，越权按「不存在」防探测。 */
    public QaPair getQaPair(Long id) {
        QaPair qa = qaPairRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("问答对", id.toString()));
        if (!TenantOwnershipGuard.belongsToCurrentContext(qa.getTenantId(), qa.getAppId())) {
            throw new ResourceNotFoundException("问答对", id.toString());
        }
        return qa;
    }

    @Transactional
    public QaPair update(Long id, String question, String answer) {
        QaPair qa = getQaPair(id);
        boolean changed = !question.equals(qa.getQuestion()) || !answer.equals(qa.getAnswer());
        qa.setQuestion(question);
        qa.setAnswer(answer);
        qaPairRepository.save(qa);
        // DRAFT 无向量，仅改行；ACTIVE/DISABLED 已有向量，内容变则重嵌
        if (changed && qa.getStatus() != QaPairStatus.DRAFT) {
            vectorStoreRepository.deleteByQaPairId(id.toString());
            embed(qa);
            if (qa.getStatus() == QaPairStatus.DISABLED) {
                vectorStoreRepository.updateDocEnabledByQaPairId(id.toString(), "false");
            }
        }
        return qa;
    }

    /** 复核通过：DRAFT→ACTIVE，嵌入。 */
    @Transactional
    public QaPair approve(Long id) {
        QaPair qa = getQaPair(id);
        if (qa.getStatus() != QaPairStatus.DRAFT) {
            throw new InvalidOperationException("仅草稿状态可复核通过");
        }
        qa.setStatus(QaPairStatus.ACTIVE);
        qaPairRepository.save(qa);
        embed(qa);
        return qa;
    }

    /** 启用/停用：ACTIVE↔DISABLED，翻转向量 docEnabled 标记（不删向量、不重嵌）。 */
    @Transactional
    public QaPair setEnabled(Long id, boolean enabled) {
        QaPair qa = getQaPair(id);
        qa.setStatus(enabled ? QaPairStatus.ACTIVE : QaPairStatus.DISABLED);
        qaPairRepository.save(qa);
        vectorStoreRepository.updateDocEnabledByQaPairId(id.toString(), String.valueOf(enabled));
        return qa;
    }

    @Transactional
    public void delete(Long id) {
        QaPair qa = getQaPair(id);
        vectorStoreRepository.deleteByQaPairId(id.toString());
        qaPairRepository.delete(qa);
    }

    /**
     * 组「问：{q}\n答：{a}」为一条不切分向量入库。metadata 带齐 RetrievalScope 过滤 5 必需键
     * （tenantId/appId/knowledgeBaseId/docEnabled/visibility）+ qaPairId/source/contentType。
     * tenantId/appId 取自 qaPair 行（非 TenantContext），防上下文缺失写空串。package-private 便于同包单测。
     */
    void embed(QaPair qa) {
        String text = "问：" + qa.getQuestion() + "\n答：" + qa.getAnswer();
        Map<String, Object> md = new HashMap<>();
        md.put("qaPairId", qa.getId().toString());
        md.put("knowledgeBaseId", qa.getKnowledgeBase().getId().toString());
        md.put("tenantId", qa.getTenantId() != null ? qa.getTenantId().toString() : "");
        md.put("appId", qa.getAppId() != null ? qa.getAppId().toString() : "");
        md.put("source", truncate(qa.getQuestion(), 100));
        md.put("visibility", "PUBLIC");
        md.put("docEnabled", "true");
        md.put("contentType", "qa");
        vectorStore.add(List.of(Document.builder().text(text).metadata(md).build()));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

> **实现说明（对执行者）**：若 `Document.builder().text(...).metadata(...).build()` 在 Spring AI 1.1.8 签名不符，用 context7 查 `/spring-projects/spring-ai/v1.1.8` 的 `org.springframework.ai.document.Document` 构造方式后微调（以编译+测试通过为准）。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=QaPairServiceTest`
Expected: PASS（6 个用例）。

- [ ] **Step 5: 全量单测无回归**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/QaPairService.java backend/src/test/java/com/qingyu/care/service/QaPairServiceTest.java
git commit -m "feat: QaPairService（CRUD + QA 不切分嵌入/停启/删向量生命周期）"
```

---

### Task A4: QaPairController（手动 CRUD + 复核/停启端点）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/controller/QaPairController.java`

**Interfaces:**
- Consumes: `QaPairService`（A3）、`QaPairRequest`（A1）。
- Produces: REST 端点（见下）。

- [ ] **Step 1: 写控制器（generate 端点在 Task B1 追加，这里先不含）**

```java
package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.QaPairRequest;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.service.QaPairService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** QA 问答对管理 API */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class QaPairController {

    private final QaPairService qaPairService;

    @GetMapping("/knowledge/{kbId}/qa-pairs")
    public ApiResponse<List<QaPair>> list(@PathVariable Long kbId) {
        return ApiResponse.success(qaPairService.list(kbId));
    }

    @AuditLog(action = "QA_CREATE", targetType = "QaPair")
    @PostMapping("/knowledge/{kbId}/qa-pairs")
    public ApiResponse<QaPair> create(@PathVariable Long kbId, @RequestBody QaPairRequest req) {
        return ApiResponse.success("问答对已创建", qaPairService.create(kbId, req.getQuestion(), req.getAnswer()));
    }

    @AuditLog(action = "QA_UPDATE", targetType = "QaPair")
    @PutMapping("/qa-pairs/{id}")
    public ApiResponse<QaPair> update(@PathVariable Long id, @RequestBody QaPairRequest req) {
        return ApiResponse.success("问答对已更新", qaPairService.update(id, req.getQuestion(), req.getAnswer()));
    }

    @AuditLog(action = "QA_APPROVE", targetType = "QaPair")
    @PostMapping("/qa-pairs/{id}/approve")
    public ApiResponse<QaPair> approve(@PathVariable Long id) {
        return ApiResponse.success("问答对已通过复核", qaPairService.approve(id));
    }

    @AuditLog(action = "QA_ENABLED", targetType = "QaPair")
    @PutMapping("/qa-pairs/{id}/enabled")
    public ApiResponse<QaPair> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        return ApiResponse.success(enabled ? "问答对已启用" : "问答对已停用", qaPairService.setEnabled(id, enabled));
    }

    @AuditLog(action = "QA_DELETE", targetType = "QaPair")
    @DeleteMapping("/qa-pairs/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        qaPairService.delete(id);
        return ApiResponse.success("问答对已删除", null);
    }
}
```

- [ ] **Step 2: 编译 + 全量单测**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/QaPairController.java
git commit -m "feat: QaPairController（QA 手动 CRUD + 复核/停启）"
```

---

## Phase B — LLM 文档→QA 生成

### Task B1: QaGenerationService（@Async 生成草稿）+ generate 端点

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/QaGenerationService.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/QaPairController.java`
- Test: `backend/src/test/java/com/qingyu/care/service/QaGenerationServiceTest.java`

**Interfaces:**
- Consumes: `DocumentRepository`、`QaPairRepository`、`ModelRouterService`、`com.fasterxml.jackson.databind.ObjectMapper`、`KnowledgeService`（controller 侧归属校验）。
- Produces: `QaGenerationService.generateFromDocument(Long)`（@Async）；static `parseQaJson(String, ObjectMapper)`；`POST /v1/qa-pairs/generate?documentId=`。

- [ ] **Step 1: 写失败测试（聚焦 JSON 容错解析，纯静态）**

```java
package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QaGenerationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseQaJson_extractsArrayEvenWithSurroundingText() {
        String raw = "好的，以下是问答对：\n[{\"question\":\"如何退货\",\"answer\":\"7天内申请\"}]\n希望有用";
        var list = QaGenerationService.parseQaJson(raw, mapper);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).question()).isEqualTo("如何退货");
        assertThat(list.get(0).answer()).isEqualTo("7天内申请");
    }

    @Test
    void parseQaJson_filtersBlankAndReturnsEmptyOnGarbage() {
        assertThat(QaGenerationService.parseQaJson("不是 JSON", mapper)).isEmpty();
        assertThat(QaGenerationService.parseQaJson(null, mapper)).isEmpty();
        assertThat(QaGenerationService.parseQaJson("[{\"question\":\"\",\"answer\":\"x\"}]", mapper)).isEmpty();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=QaGenerationServiceTest`
Expected: 编译失败 / `QaGenerationService` 不存在。

- [ ] **Step 3: 写实现**

```java
package com.qingyu.care.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.QaPairRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM 文档→QA 生成：读文档内容，调 LLM 生成问答对，落为 DRAFT 草稿供人工复核。
 * @Async 线程无 TenantContext，QaPair 的 tenant/app 显式从源文档行回填（沿用 processDocument 教训）。
 * 不计量（内部管理动作，非开放 API）。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QaGenerationService {

    private final DocumentRepository documentRepository;
    private final QaPairRepository qaPairRepository;
    private final ModelRouterService modelRouterService;
    private final ObjectMapper objectMapper;

    /** 从文档内容异步生成 QA 草稿。调用方（控制器）须先在请求线程校验文档归属。 */
    @Async
    public void generateFromDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) {
            log.warn("文档 {} 无内容，跳过 QA 生成", documentId);
            return;
        }
        try {
            String prompt = "你是知识库助手。请从下面的资料中提炼尽量多的高质量常见问答对，"
                    + "问题简洁、答案严格基于资料原文、不要编造。只输出 JSON 数组，形如 "
                    + "[{\"question\":\"...\",\"answer\":\"...\"}]，不要输出任何额外文字。\n\n资料：\n" + doc.getContent();
            String resp = modelRouterService.callWithFallback("default",
                    client -> client.prompt().user(prompt).call().content());
            List<QaDraft> drafts = parseQaJson(resp, objectMapper);
            for (QaDraft d : drafts) {
                QaPair qa = QaPair.builder()
                        .knowledgeBase(doc.getKnowledgeBase())
                        .question(d.question()).answer(d.answer())
                        .source(QaPairSource.GENERATED).status(QaPairStatus.DRAFT)
                        .sourceDocumentId(documentId).build();
                // @Async 无 TenantContext：显式从源文档行回填租户/应用，避免 @PrePersist 写 null
                qa.setTenantId(doc.getTenantId());
                qa.setAppId(doc.getAppId());
                qaPairRepository.save(qa);
            }
            log.info("文档 {} 生成 {} 条 QA 草稿", documentId, drafts.size());
        } catch (Exception e) {
            log.error("文档 {} 生成 QA 失败: {}", documentId, e.getMessage(), e);
        }
    }

    /**
     * 容错解析 LLM 返回的 QA JSON 数组：截取首个 '[' 到最后一个 ']'，解析失败/空白项过滤。
     * static 便于纯单测。
     */
    static List<QaDraft> parseQaJson(String raw, ObjectMapper mapper) {
        if (raw == null) {
            return List.of();
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            List<QaDraft> parsed = mapper.readValue(raw.substring(start, end + 1),
                    new TypeReference<List<QaDraft>>() {});
            return parsed.stream()
                    .filter(d -> d.question() != null && !d.question().isBlank()
                            && d.answer() != null && !d.answer().isBlank())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** LLM 生成的问答对草稿（JSON 映射）。 */
    public record QaDraft(String question, String answer) {
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=QaGenerationServiceTest`
Expected: PASS（2 个用例）。

- [ ] **Step 5: 控制器加 generate 端点 + drafts 列表**

在 `QaPairController` 加字段与端点（`KnowledgeService` 用于请求线程归属校验，`QaGenerationService` 触发异步；drafts 复用 service 列表按状态）：

在类字段处：
```java
    private final QaGenerationService qaGenerationService;
    private final com.qingyu.care.service.KnowledgeService knowledgeService;
```
在 `delete` 方法后追加：
```java
    /** 触发 LLM 从文档生成 QA 草稿（异步）。请求线程先校验文档归属。 */
    @AuditLog(action = "QA_GENERATE", targetType = "Document")
    @PostMapping("/qa-pairs/generate")
    public ApiResponse<Void> generate(@RequestParam Long documentId) {
        knowledgeService.getDocument(documentId); // 归属校验（越权抛）
        qaGenerationService.generateFromDocument(documentId);
        return ApiResponse.success("已开始生成问答对，稍后在草稿列表复核", null);
    }
```

- [ ] **Step 6: 编译 + 全量单测**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/QaGenerationService.java backend/src/test/java/com/qingyu/care/service/QaGenerationServiceTest.java backend/src/main/java/com/qingyu/care/controller/QaPairController.java
git commit -m "feat: QaGenerationService LLM 文档→QA 草稿（@Async + JSON 容错）+ generate 端点"
```

---

## Phase C — 前端

### Task C1: 前端 QA 接口 + 问答对面板 + KnowledgePage tab

**Files:**
- Create: `frontend/src/api/qa.ts`
- Create: `frontend/src/components/QaPairPanel.tsx`
- Modify: `frontend/src/pages/KnowledgePage.tsx`

**Interfaces:**
- Consumes: `api/client`。

- [ ] **Step 1: 写 API 客户端**

`frontend/src/api/qa.ts`:
```ts
import api from './client';
import type { ApiResponse } from '../types';

export interface QaPair {
    id: number;
    question: string;
    answer: string;
    source: 'MANUAL' | 'GENERATED';
    status: 'DRAFT' | 'ACTIVE' | 'DISABLED';
    sourceDocumentId: number | null;
    createdAt: string;
    updatedAt: string;
}

export const qaService = {
    list: (kbId: number): Promise<ApiResponse<QaPair[]>> =>
        api.get(`/knowledge/${kbId}/qa-pairs`),
    create: (kbId: number, data: { question: string; answer: string }): Promise<ApiResponse<QaPair>> =>
        api.post(`/knowledge/${kbId}/qa-pairs`, data),
    update: (id: number, data: { question: string; answer: string }): Promise<ApiResponse<QaPair>> =>
        api.put(`/qa-pairs/${id}`, data),
    approve: (id: number): Promise<ApiResponse<QaPair>> =>
        api.post(`/qa-pairs/${id}/approve`),
    setEnabled: (id: number, enabled: boolean): Promise<ApiResponse<QaPair>> =>
        api.put(`/qa-pairs/${id}/enabled?enabled=${enabled}`),
    remove: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/qa-pairs/${id}`),
    generate: (documentId: number): Promise<ApiResponse<void>> =>
        api.post(`/qa-pairs/generate?documentId=${documentId}`),
};
```

- [ ] **Step 2: 写问答对面板组件**

`frontend/src/components/QaPairPanel.tsx`:
```tsx
import React, { useState, useEffect, useCallback } from 'react';
import { Plus, Trash2, Check, Loader2, Sparkles, MessageCircleQuestion } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { qaService } from '@/api/qa';
import type { QaPair } from '@/api/qa';
import type { Document } from '@/types';
import { useToast } from '@/hooks/use-toast';

interface Props {
    kbId: number;
    documents: Document[];
}

const QaPairPanel: React.FC<Props> = ({ kbId, documents }) => {
    const [pairs, setPairs] = useState<QaPair[]>([]);
    const [loading, setLoading] = useState(false);
    const [question, setQuestion] = useState('');
    const [answer, setAnswer] = useState('');
    const [genDocId, setGenDocId] = useState<string>('');
    const { toast } = useToast();

    const fetchPairs = useCallback(async () => {
        setLoading(true);
        try {
            const res = await qaService.list(kbId);
            if (res.code === 200) setPairs(res.data);
        } catch (e) {
            toast({ variant: 'destructive', title: '获取问答对失败', description: e instanceof Error ? e.message : String(e) });
        } finally {
            setLoading(false);
        }
    }, [kbId, toast]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        fetchPairs();
    }, [fetchPairs]);

    const handleCreate = async () => {
        if (!question.trim() || !answer.trim()) {
            toast({ variant: 'destructive', title: '请填写问题和答案' });
            return;
        }
        try {
            const res = await qaService.create(kbId, { question, answer });
            if (res.code === 200) { toast({ title: '问答对已创建' }); setQuestion(''); setAnswer(''); fetchPairs(); }
        } catch (e) { toast({ variant: 'destructive', title: '创建失败', description: e instanceof Error ? e.message : String(e) }); }
    };

    const handleApprove = async (id: number) => {
        try { const res = await qaService.approve(id); if (res.code === 200) { toast({ title: '已通过复核' }); fetchPairs(); } }
        catch (e) { toast({ variant: 'destructive', title: '操作失败', description: e instanceof Error ? e.message : String(e) }); }
    };

    const handleToggle = async (p: QaPair) => {
        try { const res = await qaService.setEnabled(p.id, p.status === 'DISABLED'); if (res.code === 200) fetchPairs(); }
        catch (e) { toast({ variant: 'destructive', title: '操作失败', description: e instanceof Error ? e.message : String(e) }); }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('确定删除该问答对吗？相关向量也会清除。')) return;
        try { const res = await qaService.remove(id); if (res.code === 200) { toast({ title: '已删除' }); fetchPairs(); } }
        catch (e) { toast({ variant: 'destructive', title: '删除失败', description: e instanceof Error ? e.message : String(e) }); }
    };

    const handleGenerate = async () => {
        if (!genDocId) { toast({ variant: 'destructive', title: '请选择用于生成的文档' }); return; }
        try {
            const res = await qaService.generate(Number(genDocId));
            if (res.code === 200) toast({ title: '已开始生成', description: '稍后刷新查看草稿并复核' });
        } catch (e) { toast({ variant: 'destructive', title: '生成失败', description: e instanceof Error ? e.message : String(e) }); }
    };

    const statusBadge = (p: QaPair) => {
        const map: Record<string, string> = {
            DRAFT: 'bg-amber-50 text-amber-700 border-amber-200',
            ACTIVE: 'bg-primary/10 text-primary border-primary/20',
            DISABLED: 'bg-muted text-muted-foreground border-border line-through',
        };
        const label: Record<string, string> = { DRAFT: '待复核', ACTIVE: '检索中', DISABLED: '已停用' };
        return <span className={`text-[10px] px-2 py-0.5 rounded-full border ${map[p.status]}`}>{label[p.status]}</span>;
    };

    const completedDocs = documents.filter((d) => d.status === 'COMPLETED');

    return (
        <div className="space-y-6">
            {/* 从文档生成 */}
            <div className="flex items-center gap-2 rounded-xl border bg-muted/20 p-3">
                <Sparkles className="w-4 h-4 text-primary shrink-0" />
                <select className="flex-1 rounded-md border bg-background px-2 py-1.5 text-sm" value={genDocId} onChange={(e) => setGenDocId(e.target.value)}>
                    <option value="">从文档生成问答对（选一个已完成文档）…</option>
                    {completedDocs.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
                <Button size="sm" variant="outline" onClick={handleGenerate}>生成草稿</Button>
            </div>

            {/* 手动新增 */}
            <div className="rounded-xl border p-4 space-y-2">
                <Input placeholder="问题，如：如何退货？" value={question} onChange={(e) => setQuestion(e.target.value)} />
                <textarea className="w-full min-h-[70px] rounded-md border px-3 py-2 text-sm" placeholder="答案" value={answer} onChange={(e) => setAnswer(e.target.value)} />
                <div className="flex justify-end">
                    <Button size="sm" onClick={handleCreate}><Plus className="w-4 h-4 mr-1" />添加问答对</Button>
                </div>
            </div>

            {/* 列表 */}
            <div className="space-y-3">
                <h4 className="text-sm font-semibold">问答对（{pairs.length}）</h4>
                {loading ? (
                    <div className="flex justify-center py-8"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
                ) : pairs.length === 0 ? (
                    <div className="py-10 text-center border-2 border-dashed rounded-xl">
                        <MessageCircleQuestion className="w-10 h-10 mx-auto text-muted-foreground/30 mb-2" />
                        <p className="text-muted-foreground text-sm">暂无问答对</p>
                    </div>
                ) : pairs.map((p) => (
                    <div key={p.id} className="p-4 rounded-xl border bg-muted/20">
                        <div className="flex items-start justify-between gap-2">
                            <div className="flex-1">
                                <div className="flex items-center gap-2 flex-wrap">
                                    <span className="text-sm font-medium">{p.question}</span>
                                    {statusBadge(p)}
                                    {p.source === 'GENERATED' && <span className="text-[10px] px-2 py-0.5 rounded-full border bg-blue-50 text-blue-700 border-blue-200">AI 生成</span>}
                                </div>
                                <p className="text-xs text-muted-foreground mt-1 whitespace-pre-wrap">{p.answer}</p>
                            </div>
                            <div className="flex items-center gap-1 shrink-0">
                                {p.status === 'DRAFT' && (
                                    <Button size="icon" variant="ghost" className="h-8 w-8 text-primary" title="通过复核" onClick={() => handleApprove(p.id)}><Check className="w-4 h-4" /></Button>
                                )}
                                {p.status !== 'DRAFT' && (
                                    <button className="text-[10px] px-2 py-0.5 rounded-full border" onClick={() => handleToggle(p)}>{p.status === 'DISABLED' ? '启用' : '停用'}</button>
                                )}
                                <Button size="icon" variant="ghost" className="h-8 w-8 text-destructive hover:bg-destructive/10" onClick={() => handleDelete(p.id)}><Trash2 className="w-4 h-4" /></Button>
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
};

export default QaPairPanel;
```

- [ ] **Step 3: KnowledgePage 加「文档 / 问答对」tab**

在 `frontend/src/pages/KnowledgePage.tsx`：

3a. import 区加：
```tsx
import QaPairPanel from '@/components/QaPairPanel';
```
3b. 组件内 state 区（`const { toast } = useToast();` 附近）加：
```tsx
    const [activeTab, setActiveTab] = useState<'docs' | 'qa'>('docs');
```
3c. 在 `<CardContent>` 内、`<Separator className="mb-6" />` 之后，包一层 tab 切换：把原本紧接的 `<div className="space-y-4">...文档列表...</div>` 用下面结构包住（tab 头 + 条件渲染）：
```tsx
                                <div className="flex gap-4 border-b mb-4">
                                    <button className={cn('pb-2 text-sm font-medium border-b-2 -mb-px', activeTab === 'docs' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground')} onClick={() => setActiveTab('docs')}>文档</button>
                                    <button className={cn('pb-2 text-sm font-medium border-b-2 -mb-px', activeTab === 'qa' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground')} onClick={() => setActiveTab('qa')}>问答对</button>
                                </div>
                                {activeTab === 'qa' ? (
                                    <QaPairPanel kbId={selectedKb.id} documents={documents} />
                                ) : (
                                    <div className="space-y-4">
                                        {/* …原有文档列表 JSX 原样保留在此… */}
                                    </div>
                                )}
```
> 执行者注意：把原本 `<div className="space-y-4">` 到其匹配 `</div>`（含"已上传文档"标题与文档 map）整段作为上面 `activeTab === 'docs'` 分支的内容，不改动其内部；仅在外面包 tab 头与三元。

- [ ] **Step 4: 构建 + lint**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建成功；lint 除既有基线的 `set-state-in-effect`（新面板同款挂载取数）外无新增。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/api/qa.ts frontend/src/components/QaPairPanel.tsx frontend/src/pages/KnowledgePage.tsx
git commit -m "feat(frontend): 知识库问答对面板（手动 CRUD + 从文档生成 + 复核）+ 文档/问答对 tab"
```

---

## Phase D — 收尾

### Task D1: QaPair 持久化 IT

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/QaPairPersistenceIT.java`

**Interfaces:**
- Consumes: `AbstractPostgresIT`、`QaPairRepository`、`KnowledgeBaseRepository`。

> 说明：本 IT 验证 `QaPair` 及其 `@ManyToOne knowledgeBase`/status 在真库持久化+回读、`findByKnowledgeBaseIdAndStatus` 派生查询正确。真嵌入（依赖 Embedding 端点）不测，沿用既定取舍。

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 QaPair 在真实 Postgres 容器持久化+回读，及按状态派生查询。
 *
 * @author ruan
 */
class QaPairPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private QaPairRepository qaPairRepository;
    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Test
    void qaPairRoundTripAndStatusQuery() {
        KnowledgeBase kb = knowledgeBaseRepository.saveAndFlush(
                KnowledgeBase.builder().name("FAQ库").enabled(true).build());

        qaPairRepository.saveAndFlush(QaPair.builder()
                .knowledgeBase(kb).question("如何退货").answer("7天内订单页申请")
                .source(QaPairSource.MANUAL).status(QaPairStatus.ACTIVE).build());
        qaPairRepository.saveAndFlush(QaPair.builder()
                .knowledgeBase(kb).question("草稿问").answer("草稿答")
                .source(QaPairSource.GENERATED).status(QaPairStatus.DRAFT).sourceDocumentId(99L).build());

        assertThat(qaPairRepository.findByKnowledgeBaseId(kb.getId())).hasSize(2);
        assertThat(qaPairRepository.findByKnowledgeBaseIdAndStatus(kb.getId(), QaPairStatus.DRAFT))
                .singleElement()
                .satisfies(q -> {
                    assertThat(q.getQuestion()).isEqualTo("草稿问");
                    assertThat(q.getSourceDocumentId()).isEqualTo(99L);
                    assertThat(q.getSource()).isEqualTo(QaPairSource.GENERATED);
                });
    }
}
```

- [ ] **Step 2: 运行 IT（需 Docker）**

Run: `cd backend && mvn -q verify -Dit.test=QaPairPersistenceIT`
Expected: PASS（1 个用例）。

- [ ] **Step 3: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/QaPairPersistenceIT.java
git commit -m "test: QaPair 持久化 + 状态派生查询 IT"
```

---

### Task D2: 全量回归

**Files:** 无（验证任务）

- [ ] **Step 1: 后端全量单测**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 2: 后端集成测试（需 Docker）**

Run: `cd backend && mvn -q verify`
Expected: BUILD SUCCESS（含 QaPairPersistenceIT 与既有 IT，无回归）。

- [ ] **Step 3: 前端构建 + lint**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建成功；lint 无新增（除既有基线 set-state-in-effect）。

- [ ] **Step 4: 若 `Document.builder()` 等 Spring AI API 与 §10 待核实项有出入，更正并提交**

```bash
git add docs/superpowers/specs/2026-07-26-qa-knowledge-design.md
git commit -m "docs: 按实现校正 QA 知识库设计稿 Spring AI API 细节"
```
（若无出入，跳过。）

---

## Self-Review

**1. Spec coverage（对照设计稿逐节）**
- §3 QaPair 实体/枚举/状态机 → A1 ✓
- §4 摄取（不切分嵌入、5 必需键、tenant/app 取自行、向量生命周期）→ A2（native）+ A3（embed + 生命周期）✓
- §5 LLM 文档→QA 生成（@Async + JSON 容错 + 不计量 + tenant/app 从文档回填）→ B1 ✓
- §6 检索融入（零改核心）→ 检索侧不改；QA 向量带齐键（A3 单测断言）✓
- §7 管理面（控制器 + 前端 QA tab + 生成/复核）→ A4 + B1 端点 + C1 ✓
- §8 测试（单测 mock VectorStore / IT 持久化）→ A3/B1 单测 + D1 IT ✓
- §9 分期 A/B/C/D → 四阶段 ✓
- §2 OUT（批量导入/网站抓取/QA 专用 pass/生成计量）→ 均未纳入 ✓

**2. Placeholder scan：** 无 TBD/TODO；A3/D2 的「实现说明」是对 Spring AI 1.1.8 `Document` 构造的核实指引（已给完整可编译实现，仅提示签名不符时微调），非占位。

**3. Type consistency：**
- `QaPairService(QaPairRepository, KnowledgeService, VectorStore, VectorStoreRepository)` 构造在 A3 定义、测试一致 ✓
- `QaPairService` 方法名 list/create/getQaPair/update/approve/setEnabled/delete/embed 在 A3 定义、A4 控制器调用一致 ✓
- `QaGenerationService.generateFromDocument(Long)` / `parseQaJson(String, ObjectMapper)` / `QaDraft(question,answer)` 在 B1 定义、测试与控制器一致 ✓
- `VectorStoreRepository.deleteByQaPairId/updateDocEnabledByQaPairId` 在 A2 定义、A3 调用一致 ✓
- 前端 `qaService`（list/create/update/approve/setEnabled/remove/generate）在 C1 定义、面板使用一致 ✓
- QA 向量 metadata 键（tenantId/appId/knowledgeBaseId/docEnabled/visibility/qaPairId/source/contentType）与 §4 及 `RetrievalScope.toFilterExpression` 所需键一致 ✓

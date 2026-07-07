# 多知识库联合检索 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 RAG 检索链路补上「检索范围」概念——从「租户全量盲搜」变为「当前应用下所有 enabled 知识库的联合检索」，双重保险闭合 appId 隔离缺口，并清理 `knowledgeBaseId` 死代码。

**Architecture:** 新增 `RetrievalScope` 值对象 + `RetrievalScopeResolver` 服务解析范围（TenantContext + enabled 库查询，fail-soft）；`ChatServiceImpl.searchKnowledge` 先解析范围、空范围短路，非空传给向量路（filterExpression 加 appId/kbIds）与关键词路（SQL 加对应条件）。设计依据：`docs/superpowers/specs/2026-07-07-multi-kb-retrieval-design.md`。

**Tech Stack:** Spring Boot 3 / Spring AI M4（pgvector VectorStore filterExpression）/ JdbcTemplate / JUnit5 + Mockito（单测）/ Testcontainers + Failsafe（IT）。

**约定：**
- 后端命令均在 `backend/` 下执行；单测 `mvn test -Dtest=Xxx`，IT `mvn verify -Dit.test=XxxIT`（需 Docker Desktop）。
- 全程简体中文注释与 commit；作者 `ruan`。当前分支 `feat/multi-kb-retrieval`，**不 push**。
- 测试 profile 的 pgvector 维度是 **1536**（非生产 1024），IT 种子零向量用 `array_fill(0::real, ARRAY[1536])::vector`。
- IT 不 mock VectorStore（会污染共享容器表结构，见混合检索 spec §10.4）；非 HTTP 直调 service 的 IT 用类级 `@Transactional`。

---

### Task 1: `RetrievalScope` 值对象（纯函数 + 单测）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/RetrievalScope.java`
- Test: `backend/src/test/java/com/qingyu/care/service/RetrievalScopeTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalScopeTest {

    @Test
    void 拼接filterExpression_含tenant与多库IN() {
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L, 11L));
        assertThat(scope.toFilterExpression())
                .isEqualTo("tenantId == '1' && appId == '2' && knowledgeBaseId in ['10','11']");
    }

    @Test
    void 拼接filterExpression_无tenant省略子句() {
        RetrievalScope scope = new RetrievalScope(null, 2L, List.of(10L));
        assertThat(scope.toFilterExpression())
                .isEqualTo("appId == '2' && knowledgeBaseId in ['10']");
    }

    @Test
    void isEmpty_无app或无库为真() {
        assertThat(new RetrievalScope(1L, null, List.of(10L)).isEmpty()).isTrue();
        assertThat(new RetrievalScope(1L, 2L, List.of()).isEmpty()).isTrue();
        assertThat(new RetrievalScope(1L, 2L, null).isEmpty()).isTrue();
        assertThat(new RetrievalScope(1L, 2L, List.of(10L)).isEmpty()).isFalse();
    }

    @Test
    void empty工厂_保留tenantId() {
        RetrievalScope scope = RetrievalScope.empty(1L);
        assertThat(scope.isEmpty()).isTrue();
        assertThat(scope.tenantId()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RetrievalScopeTest`
Expected: 编译失败（`RetrievalScope` 不存在）。

- [ ] **Step 3: 最小实现**

```java
package com.qingyu.care.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索范围值对象：当前请求合法可检索的（租户、应用、enabled 知识库集合）。
 * isEmpty() 为真表示无合法范围（无应用上下文 / 无启用知识库），检索应短路返回空。
 *
 * @author ruan
 */
public record RetrievalScope(Long tenantId, Long appId, List<Long> kbIds) {

    /** 空范围（保留 tenantId 便于日志排查）。 */
    public static RetrievalScope empty(Long tenantId) {
        return new RetrievalScope(tenantId, null, List.of());
    }

    public boolean isEmpty() {
        return appId == null || kbIds == null || kbIds.isEmpty();
    }

    /**
     * 拼接 Spring AI 向量路 filterExpression：
     * tenantId == 'T' && appId == 'A' && knowledgeBaseId in ['1','2']
     * （metadata 值摄取时均为字符串；tenantId 为 null 时省略该子句，同既有语义）
     * 仅在非空范围下调用。
     */
    public String toFilterExpression() {
        StringBuilder sb = new StringBuilder();
        if (tenantId != null) {
            sb.append("tenantId == '").append(tenantId).append("' && ");
        }
        sb.append("appId == '").append(appId).append("'");
        sb.append(" && knowledgeBaseId in [")
          .append(kbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",")))
          .append("]");
        return sb.toString();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=RetrievalScopeTest`
Expected: 4 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/RetrievalScope.java backend/src/test/java/com/qingyu/care/service/RetrievalScopeTest.java
git commit -m "feat: RetrievalScope 检索范围值对象 + filterExpression 拼接纯函数"
```

---

### Task 2: `RetrievalScopeResolver` + 仓库派生查询

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/KnowledgeBaseRepository.java`
- Create: `backend/src/main/java/com/qingyu/care/service/RetrievalScopeResolver.java`
- Test: `backend/src/test/java/com/qingyu/care/service/RetrievalScopeResolverTest.java`

- [ ] **Step 1: 写失败测试**

注意：`TenantContext` 是静态 ThreadLocal，测试里直接 set，`@AfterEach` 必须 `clear()` 防泄漏。

```java
package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalScopeResolverTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @InjectMocks RetrievalScopeResolver resolver;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void 无应用上下文_空范围且不查库() {
        TenantContext.setTenantId(1L);   // 只有租户、无应用

        RetrievalScope scope = resolver.resolve();

        assertThat(scope.isEmpty()).isTrue();
        verifyNoInteractions(knowledgeBaseRepository);
    }

    @Test
    void 正常_取当前应用enabled库id集合() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(2L))
                .thenReturn(List.of(
                        KnowledgeBase.builder().id(10L).name("库A").enabled(true).build(),
                        KnowledgeBase.builder().id(11L).name("库B").enabled(true).build()));

        RetrievalScope scope = resolver.resolve();

        assertThat(scope.isEmpty()).isFalse();
        assertThat(scope.tenantId()).isEqualTo(1L);
        assertThat(scope.appId()).isEqualTo(2L);
        assertThat(scope.kbIds()).containsExactly(10L, 11L);
    }

    @Test
    void 无enabled库_空范围() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(2L)).thenReturn(List.of());

        assertThat(resolver.resolve().isEmpty()).isTrue();
    }

    @Test
    void 查询异常_failSoft空范围() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(2L))
                .thenThrow(new RuntimeException("db down"));

        assertThat(resolver.resolve().isEmpty()).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RetrievalScopeResolverTest`
Expected: 编译失败（`RetrievalScopeResolver` / `findByAppIdAndEnabledTrue` 不存在）。

- [ ] **Step 3: 实现**

`KnowledgeBaseRepository` 加一行（保留既有方法）：

```java
    /** 显式按 appId 过滤（不只依赖 TenantDataAspect 隐式过滤——appId 隔离双重保险第一道）。 */
    List<KnowledgeBase> findByAppIdAndEnabledTrue(Long appId);
```

新建 `RetrievalScopeResolver`：

```java
package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索范围解析：从 TenantContext 取租户/应用，查当前应用下 enabled 的知识库集合。
 * 严格语义：无应用上下文 → 空范围（不检索）；这同时规避了平台管理员绕过
 * Hibernate Filter 时库列表跨租户泄漏的问题（显式 appId 条件是第二道防线）。
 * fail-soft：库查询异常 → 空范围，不阻断对话。
 * 后续「文档权限控制」（Phase 4.3）在此组件上叠加收窄。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalScopeResolver {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public RetrievalScope resolve() {
        Long tenantId = TenantContext.getTenantId();
        Long appId = TenantContext.getAppId();
        if (appId == null) {
            log.debug("无应用上下文，检索范围为空 tenantId={}", tenantId);
            return RetrievalScope.empty(tenantId);
        }
        try {
            List<Long> kbIds = knowledgeBaseRepository.findByAppIdAndEnabledTrue(appId)
                    .stream().map(KnowledgeBase::getId).toList();
            return new RetrievalScope(tenantId, appId, kbIds);
        } catch (Exception e) {
            log.warn("检索范围解析失败，fail-soft 为空范围 appId={}", appId, e);
            return RetrievalScope.empty(tenantId);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=RetrievalScopeResolverTest`
Expected: 4 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/RetrievalScopeResolver.java backend/src/main/java/com/qingyu/care/repository/KnowledgeBaseRepository.java backend/src/test/java/com/qingyu/care/service/RetrievalScopeResolverTest.java
git commit -m "feat: RetrievalScopeResolver 范围解析（显式 appId 查询 + fail-soft 空范围）"
```

---

### Task 3: `KeywordSearchDao` 增加按 scope 过滤的检索（旧方法暂留）

旧方法 `search(query, tenantId, limit)` 仍被 `HybridRetrievalServiceImpl` 调用，**本任务先加新方法、不删旧方法**（保持每个任务可编译可绿），Task 5 再删。

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java`
- Test: `backend/src/test/java/com/qingyu/care/it/KeywordSearchDaoIT.java`（新增用例）

- [ ] **Step 1: 在 `KeywordSearchDaoIT` 新增失败用例**

在类中追加 seed 辅助与两个用例（既有代码不动）：

```java
    /** 带完整 scope 元数据的种子（appId/knowledgeBaseId 摄取时即写入，见 KnowledgeServiceImpl）。 */
    private void seedChunkWithScope(String content, String tenantId, String appId, String kbId) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + tenantId + "\",\"appId\":\"" + appId
                        + "\",\"knowledgeBaseId\":\"" + kbId + "\"}");
    }

    @Test
    void scope过滤_仅命中启用库与本应用() {
        seedChunkWithScope("库一 产品型号XK-1111 说明", T1, "80001", "901");
        seedChunkWithScope("库二 产品型号XK-1111 说明", T1, "80001", "902");   // 不在 kbIds，不应命中
        seedChunkWithScope("他应用 产品型号XK-1111 说明", T1, "80002", "901"); // 他应用，不应命中

        List<KeywordSearchDao.KeywordHit> hits = keywordSearchDao.search(
                "产品型号",
                new com.qingyu.care.service.RetrievalScope(70001L, 80001L, List.of(901L)),
                10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("库一");
    }

    @Test
    void scope过滤_多库IN联合命中() {
        seedChunkWithScope("库一 产品型号XK-1111", T1, "80001", "901");
        seedChunkWithScope("库二 产品型号XK-1111", T1, "80001", "902");

        List<KeywordSearchDao.KeywordHit> hits = keywordSearchDao.search(
                "产品型号",
                new com.qingyu.care.service.RetrievalScope(70001L, 80001L, List.of(901L, 902L)),
                10);

        assertThat(hits).hasSize(2);
    }
```

注意 `T1 = "70001"` 与 scope 里的 `70001L` 数值一致。

- [ ] **Step 2: 跑 IT 确认失败**

Run: `mvn verify -Dit.test=KeywordSearchDaoIT`（需 Docker Desktop）
Expected: 编译失败（DAO 无 scope 重载）。

- [ ] **Step 3: 实现新方法**

在 `KeywordSearchDao` 中追加（旧 `search(String, String, int)` 与旧 `parseMetadata(String, String)` 原样保留）：

```java
    /**
     * 按检索范围（应用 + enabled 知识库集合 + 租户）做中文关键词全文检索。
     * appId/kbIds 双重保险闭合应用隔离缺口；tenantId 为 null 时省略该子句（同向量路语义）。
     * 仅在非空范围下调用（调用方已短路空范围）。
     */
    public List<KeywordHit> search(String query, com.qingyu.care.service.RetrievalScope scope, int limit) {
        List<Object> params = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id::text AS id, content, metadata::text AS metadata_json FROM vector_store " +
                "WHERE content_tsv @@ plainto_tsquery('chinese_zh', ?) " +
                "AND metadata ->> 'appId' = ? ");
        params.add(query);
        params.add(String.valueOf(scope.appId()));
        sql.append("AND metadata ->> 'knowledgeBaseId' IN (")
           .append(String.join(",", java.util.Collections.nCopies(scope.kbIds().size(), "?")))
           .append(") ");
        for (Long kbId : scope.kbIds()) {
            params.add(String.valueOf(kbId));
        }
        if (scope.tenantId() != null) {
            sql.append("AND metadata ->> 'tenantId' = ? ");
            params.add(String.valueOf(scope.tenantId()));
        }
        sql.append("ORDER BY ts_rank_cd(content_tsv, plainto_tsquery('chinese_zh', ?)) DESC LIMIT ?");
        params.add(query);
        params.add(limit);
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new KeywordHit(
                        rs.getString("id"),
                        rs.getString("content"),
                        parseMetadata(rs.getString("metadata_json"), scope)),
                params.toArray());
    }

    /** scope 版兜底：解析失败时保留 scope 中已知的隔离键，避免单行坏数据拖垮整路检索。 */
    private Map<String, Object> parseMetadata(String metadataJson, com.qingyu.care.service.RetrievalScope scope) {
        if (metadataJson != null) {
            try {
                return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("关键词检索命中的 metadata 解析失败，兜底为 scope 隔离键 appId={}", scope.appId(), e);
            }
        }
        Map<String, Object> fallback = new java.util.HashMap<>();
        if (scope.tenantId() != null) {
            fallback.put("tenantId", String.valueOf(scope.tenantId()));
        }
        fallback.put("appId", String.valueOf(scope.appId()));
        return fallback;
    }
```

- [ ] **Step 4: 跑 IT 确认通过**

Run: `mvn verify -Dit.test=KeywordSearchDaoIT`
Expected: 4 个用例（2 旧 + 2 新）全 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java backend/src/test/java/com/qingyu/care/it/KeywordSearchDaoIT.java
git commit -m "feat: 关键词 FTS 按 RetrievalScope 过滤（appId + kbIds IN，tenant 子句可省略）"
```

---

### Task 4: 贯通检索链路（Hybrid 签名改 scope + ChatServiceImpl 接线）

一个内聚改动单元：`HybridRetrievalService` 接口/实现换 scope，`ChatServiceImpl` 注入 resolver、空范围短路、纯向量路同样用 scope 过滤。

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/HybridRetrievalService.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/HybridRetrievalServiceImpl.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/service/HybridRetrievalServiceTest.java`（适配）
- Test: `backend/src/test/java/com/qingyu/care/service/ChatServiceSearchScopeTest.java`（新增）

- [ ] **Step 1: 先改两个测试（失败）**

`HybridRetrievalServiceTest`：加 scope 辅助方法，所有 `service.search("查询", "1", strategy(true))` 调用改为 `service.search("查询", scope(), strategy(true))`：

```java
    private com.qingyu.care.service.RetrievalScope scope() {
        return new com.qingyu.care.service.RetrievalScope(1L, 2L, List.of(10L));
    }
```

（4 处调用点逐一替换第二个参数 `"1"` → `scope()`。）

新增 `ChatServiceSearchScopeTest`：

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** searchKnowledge 的范围语义：空范围短路（不触达检索）、非空范围传导 scope。 */
@ExtendWith(MockitoExtension.class)
class ChatServiceSearchScopeTest {

    @Mock VectorStore vectorStore;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock PromptTemplateService promptTemplateService;
    @Mock HandoverRuleService handoverRuleService;
    @Mock ModelRouterService modelRouterService;
    @Mock ChatStrategyService chatStrategyService;
    @Mock HybridRetrievalService hybridRetrievalService;
    @Mock RetrievalScopeResolver retrievalScopeResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks ChatServiceImpl chatService;

    @Test
    void 空范围_短路返回空_不触达任何检索路() {
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(new ChatStrategy());
        when(retrievalScopeResolver.resolve()).thenReturn(RetrievalScope.empty(1L));

        List<Document> out = chatService.searchKnowledge("有什么产品");

        assertThat(out).isEmpty();
        verifyNoInteractions(vectorStore, hybridRetrievalService);
    }

    @Test
    void 非空范围_hybrid开_scope传给混合检索() {
        ChatStrategy strategy = new ChatStrategy();   // hybridEnabled=null → 视为开启
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(strategy);
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L));
        when(retrievalScopeResolver.resolve()).thenReturn(scope);
        when(hybridRetrievalService.search("有什么产品", scope, strategy)).thenReturn(List.of());

        chatService.searchKnowledge("有什么产品");

        verify(hybridRetrievalService).search("有什么产品", scope, strategy);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=HybridRetrievalServiceTest,ChatServiceSearchScopeTest`
Expected: 编译失败（签名不匹配 / ChatServiceImpl 无 resolver 字段）。

- [ ] **Step 3: 实现**

`HybridRetrievalService` 接口：

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 混合检索：向量 + 关键词两路，RRF 融合。
 *
 * @author ruan
 */
public interface HybridRetrievalService {

    /**
     * @param query    用户查询
     * @param scope    检索范围（租户 + 应用 + enabled 知识库集合，调用方保证非空范围）
     * @param strategy 提供 topK/similarityThreshold/keywordTopK/rrfK
     * @return 融合后的 chunk（Spring AI Document），供 buildContext 消费
     */
    List<Document> search(String query, RetrievalScope scope, ChatStrategy strategy);
}
```

`HybridRetrievalServiceImpl.search` 方法体改为（类头/依赖不变，加 `import com.qingyu.care.service.RetrievalScope;`）：

```java
    @Override
    public List<Document> search(String query, RetrievalScope scope, ChatStrategy strategy) {
        int topK = strategy.getTopK() != null ? strategy.getTopK() : 5;
        int keywordTopK = strategy.getKeywordTopK() != null ? strategy.getKeywordTopK() : 10;
        int rrfK = strategy.getRrfK() != null ? strategy.getRrfK() : 60;
        double threshold = strategy.getSimilarityThreshold() != null ? strategy.getSimilarityThreshold() : 0.7;

        // 向量路（fail-soft：异常则空列表 → 融合退化为关键词/空）
        List<RrfFuser.Item> vectorItems = new ArrayList<>();
        try {
            SearchRequest searchRequest = SearchRequest.query(query)
                    .withTopK(topK)
                    .withSimilarityThreshold(threshold)
                    .withFilterExpression(scope.toFilterExpression());
            for (Document d : vectorStore.similaritySearch(searchRequest)) {
                String content = d.getContent() != null ? d.getContent() : "";
                vectorItems.add(new RrfFuser.Item(d.getId(), content, d.getMetadata()));
            }
        } catch (Exception e) {
            log.warn("向量检索失败，退化为关键词/空 appId={}", scope.appId(), e);
        }

        // 关键词路（fail-soft：异常则空列表 → 融合退化为纯向量）
        List<RrfFuser.Item> keywordItems = new ArrayList<>();
        try {
            for (KeywordSearchDao.KeywordHit h : keywordSearchDao.search(query, scope, keywordTopK)) {
                String keywordContent = h.content() != null ? h.content() : "";
                keywordItems.add(new RrfFuser.Item(h.id(), keywordContent, h.metadata()));
            }
        } catch (Exception e) {
            log.warn("关键词检索失败，退化为纯向量 appId={}", scope.appId(), e);
        }

        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(vectorItems, keywordItems), rrfK, topK);
        List<Document> result = new ArrayList<>();
        for (RrfFuser.Item it : fused) {
            result.add(new Document(it.id(), it.content(), it.metadata()));
        }
        return result;
    }
```

`ChatServiceImpl`：
1. 字段区加 `private final RetrievalScopeResolver retrievalScopeResolver;`（import `com.qingyu.care.service.RetrievalScopeResolver` 与 `com.qingyu.care.service.RetrievalScope`）。
2. `searchKnowledge(String query, ChatStrategy strategy)` 整体替换为：

```java
    /**
     * 先解析检索范围（当前应用下 enabled 知识库集合）：空范围（无应用上下文 / 无启用库 / 解析异常）
     * 直接短路返回空（省掉 embedding 调用），走既有 allowNoKnowledge/fallbackMessage 语义。
     * 非空范围下按 strategy 分流：hybridEnabled（null 视为开启）走混合检索，否则纯向量；
     * 两条路径都带 tenant+app+kbIds 过滤。整体 fail-soft：任何异常均返回空列表，不阻断对话。
     */
    private List<Document> searchKnowledge(String query, ChatStrategy strategy) {
        try {
            RetrievalScope scope = retrievalScopeResolver.resolve();
            if (scope.isEmpty()) {
                log.debug("检索范围为空（无应用上下文或无启用知识库），跳过检索");
                return List.of();
            }

            // null 视为开启：特性默认开，且免去既有部署（旧数据行 hybridEnabled 为 null）的 backfill 迁移
            boolean hybrid = !Boolean.FALSE.equals(strategy.getHybridEnabled());
            if (hybrid) {
                return hybridRetrievalService.search(query, scope, strategy);
            }

            int topK = strategy.getTopK() != null ? strategy.getTopK() : 5;
            double threshold = strategy.getSimilarityThreshold() != null ? strategy.getSimilarityThreshold() : 0.7;
            SearchRequest searchRequest = SearchRequest.query(query)
                    .withTopK(topK)
                    .withSimilarityThreshold(threshold)
                    .withFilterExpression(scope.toFilterExpression());
            return vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.warn("Knowledge search failed: {}", e.getMessage());
            return List.of();
        }
    }
```

（原方法里手取 `TenantContext.getTenantId()` 的两行、无租户分支一并删除；`searchKnowledge(String query)` 公开入口不变。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=HybridRetrievalServiceTest,ChatServiceSearchScopeTest`
Expected: 6 个测试 PASS。再跑 `mvn test` 确认全量单测无回归（此时 IT 不跑）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/HybridRetrievalService.java backend/src/main/java/com/qingyu/care/service/impl/HybridRetrievalServiceImpl.java backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java backend/src/test/java/com/qingyu/care/service/HybridRetrievalServiceTest.java backend/src/test/java/com/qingyu/care/service/ChatServiceSearchScopeTest.java
git commit -m "feat: 检索链路贯通 RetrievalScope（空范围短路 + 两路 scope 过滤）"
```

---

### Task 5: 删除 DAO 旧方法 + 适配既有 IT（高风险任务）

`HybridRetrievalIT` 是最可能翻车处：`searchKnowledge` 现在要求应用上下文 + enabled 库行 + chunk metadata 匹配，原种子会被新过滤筛掉。

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java`（删旧 `search(String,String,int)` 与旧 `parseMetadata(String,String)`）
- Modify: `backend/src/test/java/com/qingyu/care/it/KeywordSearchDaoIT.java`（旧用例迁到 scope 签名）
- Modify: `backend/src/test/java/com/qingyu/care/it/HybridRetrievalIT.java`（适配范围语义）

- [ ] **Step 1: 删除 `KeywordSearchDao` 旧方法**

删除 `search(String query, String tenantId, int limit)` 与 `parseMetadata(String metadataJson, String tenantId)` 两个方法（scope 版已在 Task 3 就位；生产调用方已在 Task 4 切换）。

- [ ] **Step 2: 迁移 `KeywordSearchDaoIT` 旧用例**

旧用例 `中文关键词命中且仅本租户`、`无命中返回空` 调的是旧签名。改为 scope 签名并让种子带完整 metadata（`seedChunk` 辅助删除，统一用 Task 3 的 `seedChunkWithScope`）：

```java
    @Test
    void 中文关键词命中且仅本租户() {
        seedChunkWithScope("轻语客服支持产品型号XZ-9000的售后咨询", T1, "80001", "901");
        seedChunkWithScope("其他无关内容", T1, "80001", "901");
        seedChunkWithScope("他租户也有产品型号XZ-9000", T2, "80001", "901");   // 他租户，不应命中

        List<KeywordSearchDao.KeywordHit> hits = keywordSearchDao.search(
                "产品型号",
                new com.qingyu.care.service.RetrievalScope(70001L, 80001L, List.of(901L)),
                10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("XZ-9000");
    }

    @Test
    void 无命中返回空() {
        seedChunkWithScope("完全不相关的文本", T1, "80001", "901");
        assertThat(keywordSearchDao.search(
                "量子纠缠",
                new com.qingyu.care.service.RetrievalScope(70001L, 80001L, List.of(901L)),
                10)).isEmpty();
    }
```

- [ ] **Step 3: 适配 `HybridRetrievalIT`**

要点：加 `KnowledgeBaseRepository`，种 enabled 库行（在 tenant+app 上下文内 save，`TenantEntityListener` 自动回填）、chunk metadata 带 appId/kbId、`search()` 同时设 tenantId 和 appId。完整改后骨架：

```java
@Transactional
class HybridRetrievalIT extends AbstractPostgresIT {

    private static final Long TID = 60001L;
    private static final Long APP = 60002L;

    @Autowired ChatService chatService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ChatStrategyRepository chatStrategyRepository;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;

    /** 在租户+应用上下文内种 enabled 知识库行（TenantEntityListener 回填 tenant/app）。 */
    private Long seedKb() {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);
        try {
            return knowledgeBaseRepository.save(KnowledgeBase.builder()
                    .name("混合检索IT库").enabled(true).build()).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private void seedChunk(String content, Long kbId) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + TID + "\",\"appId\":\"" + APP
                        + "\",\"knowledgeBaseId\":\"" + kbId + "\"}");
    }

    // seedTenantStrategyHybridDisabled(...) 不变（原样保留）

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", String.valueOf(TID));
        jdbcTemplate.update("DELETE FROM chat_strategy WHERE tenant_id = ?", TID);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE tenant_id = ?", TID);
    }

    /** 设置租户+应用上下文调用检索入口（检索范围现在要求应用上下文）。 */
    private List<Document> search(String q) {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);
        try {
            return chatService.searchKnowledge(q);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 混合检索召回向量漏掉的精确术语() {
        Long kbId = seedKb();
        seedChunk("售后：产品型号XZ-9000 的保修政策说明", kbId);

        List<Document> docs = search("产品型号XZ-9000");

        assertThat(docs).extracting(Document::getContent).anyMatch(c -> c.contains("XZ-9000"));
    }

    @Test
    void hybrid关闭_仅向量路_不含关键词chunk() {
        seedTenantStrategyHybridDisabled(TID);
        Long kbId = seedKb();
        seedChunk("售后：产品型号XZ-9000 的保修政策说明", kbId);

        List<Document> docs = search("产品型号XZ-9000");

        assertThat(docs).extracting(Document::getContent).noneMatch(c -> c.contains("XZ-9000"));
    }
}
```

（import 补 `com.qingyu.care.entity.KnowledgeBase`、`com.qingyu.care.repository.KnowledgeBaseRepository`；类注释追加一句「检索范围要求应用上下文 + enabled 库行，种子相应配套」。）

- [ ] **Step 4: 跑相关 IT 确认通过**

Run: `mvn verify -Dit.test='KeywordSearchDaoIT,HybridRetrievalIT'`
Expected: 全 PASS（日志中向量路 401/降级 warn 属预期）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java backend/src/test/java/com/qingyu/care/it/KeywordSearchDaoIT.java backend/src/test/java/com/qingyu/care/it/HybridRetrievalIT.java
git commit -m "refactor: 删除 DAO 旧签名 + 既有 IT 适配检索范围语义（种子配套库行与 scope 元数据）"
```

---

### Task 6: 新增 `MultiKbRetrievalIT`（核心验收 IT）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/MultiKbRetrievalIT.java`

- [ ] **Step 1: 写 IT（对新行为是验收，先全文写出）**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多知识库联合检索端到端：enabled 库联合命中、disabled 不参与、应用隔离、
 * 无应用上下文/无 enabled 库时空命中。向量路在测试环境 fail-soft 为空，
 * 命中均来自关键词路（真实中文 FTS），与 HybridRetrievalIT 同手法。
 * 类级 @Transactional：非 HTTP 直调 service 下保证种子与查询共享 Session（见 HybridRetrievalIT 注释）。
 *
 * @author ruan
 */
@Transactional
class MultiKbRetrievalIT extends AbstractPostgresIT {

    private static final Long TID = 61001L;
    private static final Long APP_A = 61002L;
    private static final Long APP_B = 61003L;

    @Autowired ChatService chatService;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long seedKb(Long appId, boolean enabled) {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(appId);
        try {
            return knowledgeBaseRepository.save(KnowledgeBase.builder()
                    .name("多库IT-" + appId + "-" + enabled).enabled(enabled).build()).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private void seedChunk(String content, Long appId, Long kbId) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + TID + "\",\"appId\":\"" + appId
                        + "\",\"knowledgeBaseId\":\"" + kbId + "\"}");
    }

    private List<Document> search(String q, Long appId) {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(appId);
        try {
            return chatService.searchKnowledge(q);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", String.valueOf(TID));
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE tenant_id = ?", TID);
    }

    @Test
    void 多enabled库联合命中_disabled库不参与() {
        Long kb1 = seedKb(APP_A, true);
        Long kb2 = seedKb(APP_A, true);
        Long kb3 = seedKb(APP_A, false);
        seedChunk("知识一 产品型号XK-1111 说明", APP_A, kb1);
        seedChunk("知识二 产品型号XK-1111 说明", APP_A, kb2);
        seedChunk("禁用库 产品型号XK-1111 说明", APP_A, kb3);

        List<Document> docs = search("产品型号XK-1111", APP_A);

        assertThat(docs).extracting(Document::getContent)
                .anyMatch(c -> c.contains("知识一"))
                .anyMatch(c -> c.contains("知识二"))
                .noneMatch(c -> c.contains("禁用库"));
    }

    @Test
    void 应用隔离_A应用检索不到B应用知识() {
        Long kbB = seedKb(APP_B, true);
        seedChunk("B应用内部 产品型号XK-2222 说明", APP_B, kbB);
        seedKb(APP_A, true);   // A 应用有 enabled 库（非空范围）但无内容

        List<Document> docs = search("产品型号XK-2222", APP_A);

        assertThat(docs).isEmpty();
    }

    @Test
    void 无应用上下文_不检索返回空() {
        Long kb = seedKb(APP_A, true);
        seedChunk("知识 产品型号XK-3333 说明", APP_A, kb);

        TenantContext.setTenantId(TID);   // 只设租户、不设应用
        try {
            assertThat(chatService.searchKnowledge("产品型号XK-3333")).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 无enabled库_返回空() {
        Long kb = seedKb(APP_A, false);   // 只有 disabled 库
        seedChunk("知识 产品型号XK-4444 说明", APP_A, kb);

        assertThat(search("产品型号XK-4444", APP_A)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑 IT 确认通过**

Run: `mvn verify -Dit.test=MultiKbRetrievalIT`
Expected: 4 个用例 PASS。若「联合命中」用例失败，优先排查 IN 条件与 metadata 键名（`knowledgeBaseId` 大小写）、库行是否真的落库（@Transactional 内 save 后 id 非 null）。

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/qingyu/care/it/MultiKbRetrievalIT.java
git commit -m "test: MultiKbRetrievalIT 多库联合/禁用排除/应用隔离/空范围 端到端守护"
```

---

### Task 7: 死代码清理（后端 DTO/Controller + 前端）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/dto/ChatRequest.java`
- Modify: `backend/src/main/java/com/qingyu/care/dto/open/OpenChatRequest.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`
- Modify: `frontend/src/pages/ChatPage.tsx`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: 后端删字段与映射**

`ChatRequest.java` 删：

```java
    /**
     * 知识库ID（可选，不传则使用默认知识库）
     */
    private Long knowledgeBaseId;
```

`OpenChatRequest.java` 删：

```java
    /**
     * 知识库 ID（可选，不传则使用默认知识库）
     */
    private Long knowledgeBaseId;
```

`OpenChatController.toInternal` 删一行 `.knowledgeBaseId(req.getKnowledgeBaseId())`。

- [ ] **Step 2: 后端编译 + 全量单测**

Run: `mvn test`
Expected: BUILD SUCCESS（若有测试引用该字段编译报错，删除对应引用行——字段从未影响行为）。

- [ ] **Step 3: 前端删写死值与类型字段**

`frontend/src/pages/ChatPage.tsx` 删 `knowledgeBaseId: 1, // Default knowledge base` 一行。
`frontend/src/types/index.ts` 的 `ChatRequest` 接口删 `knowledgeBaseId?: number;` 一行。

- [ ] **Step 4: 前端构建 + lint**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建通过、无新 lint 错误。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/dto/ChatRequest.java backend/src/main/java/com/qingyu/care/dto/open/OpenChatRequest.java backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java frontend/src/pages/ChatPage.tsx frontend/src/types/index.ts
git commit -m "chore: 清理从未消费的 knowledgeBaseId 死代码（后端 DTO/映射 + 前端写死值）"
```

---

### Task 8: 全量验证 + 文档收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-07-07-multi-kb-retrieval-design.md`（status → 已实现 + 实现结论小节）
- Modify: `SaaS平台规划.md`（勾选 4.3 多知识库联合检索）

- [ ] **Step 1: 全量单测（免 Docker）**

Run: `mvn test`
Expected: BUILD SUCCESS，0 失败 0 错误。

- [ ] **Step 2: 全量含 IT**

Run: `mvn verify`
Expected: BUILD SUCCESS（Surefire + Failsafe 全绿；向量路 401/降级 warn 属预期日志）。

- [ ] **Step 3: 更新 spec 与规划**

- spec frontmatter `status: 已评审` → `status: 已实现`；文末追加「## 10. 实现结论（2026-07-07 落地）」小节，如实记录与设计的任何偏差（无偏差也要写明测试数量与验证结果，参照混合检索 spec §10 的写法）。
- `SaaS平台规划.md`：`- [ ] 多知识库联合检索` → `- [x] 多知识库联合检索（应用内 enabled 库自动联合，全局 top-K + IN 过滤，appId 双重隔离）`。

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-07-07-multi-kb-retrieval-design.md SaaS平台规划.md
git commit -m "docs: 多知识库联合检索 spec 置为已实现 + 规划勾选"
```

- [ ] **Step 5: 汇报**

向用户汇报：任务完成情况、测试数量、与设计的偏差；分支为 `feat/multi-kb-retrieval`，合并/PR 由用户决定（不 push）。

---

## 自审记录

- **Spec 覆盖**：§3 架构（Task 1/2/4）、§3.3 两路过滤（Task 3/4）、§4 死代码清理（Task 7）、§5 fail-soft（Task 2/4 单测）、§6 既有 IT 适配（Task 5）、§7 测试矩阵（Task 1-6）、§9 验收（Task 6/8）——无缺口。
- **占位符**：无 TBD/TODO；所有代码步骤含完整代码。
- **类型一致性**：`RetrievalScope(Long, Long, List<Long>)`、`resolve()`、`search(String, RetrievalScope, ChatStrategy)`、`search(String, RetrievalScope, int)`、`findByAppIdAndEnabledTrue(Long)` 全计划一致。

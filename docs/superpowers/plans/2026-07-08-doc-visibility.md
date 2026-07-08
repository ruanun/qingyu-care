# 文档权限控制 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 文档两级可见性（PUBLIC/INTERNAL，按鉴权路径判定）+ 文档级启用开关，检索两路按等值条件过滤；幂等回填存量 chunk（顺带收尾 appId 空串历史债）；管理端提供切换入口。

**Architecture:** 调用方级别由鉴权拦截器写入 `TenantContext.ACCESS_LEVEL`（API Key/后台登录=INTERNAL，Widget/Origin=PUBLIC，缺省兜底 PUBLIC）；`RetrievalScope` 加 `accessLevel` 组件，向量路 filterExpression 与关键词路 SQL 一律**等值条件**（`docEnabled == 'true'`，PUBLIC 级再加 `visibility == 'PUBLIC'`）——规避 metadata 缺键的 SQL NULL 语义陷阱；摄取写全键 + `DocMetadataBackfillRunner` 幂等回填存量。设计依据：`docs/superpowers/specs/2026-07-08-doc-visibility-design.md`。

**Tech Stack:** Spring Boot 3 / Spring AI M4（pgvector filterExpression）/ JdbcTemplate + native jsonb_set / JUnit5 + Mockito / Testcontainers + Failsafe / React 19 + pnpm。

**约定：**
- 后端命令在 `backend/` 下执行；单测 `mvn test -Dtest=Xxx`，IT `mvn verify -Dit.test=XxxIT`（需 Docker Desktop）。前端在 `frontend/` 下用 pnpm。
- 全程简体中文注释与 commit；作者 `ruan`。分支 `feat/doc-visibility`，**不 push**。
- IT 种子零向量维度 1536（test profile，非生产 1024）；不 mock VectorStore；非 HTTP 直调 service 的 IT 用类级 `@Transactional`；播种租户覆盖型配置须同时设 tenantId+appId。
- **本计划的贯通顺序保证每个任务收尾时 `mvn test` 全绿**；IT 层在 Task 4 引入等值条件的同一任务内完成全部种子适配。

---

### Task 1: 模型与上下文底座（枚举 + Document 字段 + TenantContext.accessLevel）

纯数据与上下文载体，无独立行为逻辑；以全量单测编译绿为验收（行为测试在 Task 2/3 跟进）。

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/enums/DocumentVisibility.java`
- Modify: `backend/src/main/java/com/qingyu/care/entity/Document.java`
- Modify: `backend/src/main/java/com/qingyu/care/config/TenantContext.java`

- [ ] **Step 1: 新建枚举**

```java
package com.qingyu.care.enums;

/**
 * 文档可见性级别（同时用作调用方访问级别：PUBLIC=只见公开文档，INTERNAL=全见）。
 *
 * @author ruan
 */
public enum DocumentVisibility {
    /** 公开：对所有渠道（含 Widget 匿名访客）可见 */
    PUBLIC,
    /** 内部：仅受信调用（服务端 API Key / 管理后台登录成员）可见 */
    INTERNAL
}
```

- [ ] **Step 2: `Document` 实体加字段**

在 `chunkCount` 字段之前插入（import 加 `com.qingyu.care.enums.DocumentVisibility`）：

```java
    /**
     * 可见性级别（null 视为 PUBLIC，存量零迁移）
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DocumentVisibility visibility = DocumentVisibility.PUBLIC;

    /**
     * 是否参与检索（null 视为启用；禁用不删 chunk，重新启用免重嵌入）
     */
    @Column(name = "enabled")
    private Boolean enabled = true;
```

- [ ] **Step 3: `TenantContext` 加第 4 个 ThreadLocal**

在 `USER_ID` 之后加（import 加 `com.qingyu.care.enums.DocumentVisibility`）：

```java
    private static final ThreadLocal<DocumentVisibility> ACCESS_LEVEL = new ThreadLocal<>();
```

对应 getter/setter（与既有风格一致）：

```java
    public static void setAccessLevel(DocumentVisibility accessLevel) {
        ACCESS_LEVEL.set(accessLevel);
    }

    public static DocumentVisibility getAccessLevel() {
        return ACCESS_LEVEL.get();
    }
```

`clear()` 里追加 `ACCESS_LEVEL.remove();`。

- [ ] **Step 4: 编译 + 全量单测**

Run: `mvn test`
Expected: BUILD SUCCESS（101 个既有单测全绿，无行为变化）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/enums/DocumentVisibility.java backend/src/main/java/com/qingyu/care/entity/Document.java backend/src/main/java/com/qingyu/care/config/TenantContext.java
git commit -m "feat: DocumentVisibility 枚举 + Document 可见性/启用字段 + TenantContext 访问级别"
```

---

### Task 2: `RetrievalScope` 加 accessLevel 组件与过滤条件

record 组件从 3 个变 4 个——**构造签名变化**，本任务同步适配所有单测构造点。

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/RetrievalScope.java`
- Test: `backend/src/test/java/com/qingyu/care/service/RetrievalScopeTest.java`（适配 + 新用例）
- Test: `backend/src/test/java/com/qingyu/care/service/HybridRetrievalServiceTest.java`（仅 `scope()` 辅助适配）
- Test: `backend/src/test/java/com/qingyu/care/service/ChatServiceSearchScopeTest.java`（构造点适配）

- [ ] **Step 1: 改测试（失败）**

`RetrievalScopeTest`：既有 4 处构造 `new RetrievalScope(tenantId, appId, kbIds)` 全部改为 4 参 `new RetrievalScope(tenantId, appId, kbIds, DocumentVisibility.INTERNAL)`（import `com.qingyu.care.enums.DocumentVisibility`），既有断言的期望字符串更新为含 `docEnabled` 条件（见下）；再新增两个用例：

```java
    @Test
    void 拼接filterExpression_INTERNAL级_只加docEnabled条件() {
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.INTERNAL);
        assertThat(scope.toFilterExpression())
                .isEqualTo("tenantId == '1' && appId == '2' && knowledgeBaseId in ['10']"
                        + " && docEnabled == 'true'");
    }

    @Test
    void 拼接filterExpression_PUBLIC级_再加visibility条件() {
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.PUBLIC);
        assertThat(scope.toFilterExpression())
                .isEqualTo("tenantId == '1' && appId == '2' && knowledgeBaseId in ['10']"
                        + " && docEnabled == 'true' && visibility == 'PUBLIC'");
    }
```

既有用例期望值同步：`拼接filterExpression_含tenant与多库IN`（accessLevel 传 INTERNAL）期望尾部追加 ` && docEnabled == 'true'`；`拼接filterExpression_无tenant省略子句`（传 INTERNAL）同理。`empty工厂_保留tenantId` 不变（empty 的 accessLevel 传 null 即可，isEmpty 不受影响）。

`HybridRetrievalServiceTest` 的 `scope()` 辅助改为：

```java
    private RetrievalScope scope() {
        return new RetrievalScope(1L, 2L, List.of(10L), com.qingyu.care.enums.DocumentVisibility.INTERNAL);
    }
```

`ChatServiceSearchScopeTest` 两处 `new RetrievalScope(1L, 2L, List.of(10L))` 同样加第 4 参 `DocumentVisibility.INTERNAL`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RetrievalScopeTest`
Expected: 编译失败（构造参数不匹配）。

- [ ] **Step 3: 实现**

`RetrievalScope` 整体改为：

```java
package com.qingyu.care.service;

import com.qingyu.care.enums.DocumentVisibility;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索范围值对象：当前请求合法可检索的（租户、应用、enabled 知识库集合、调用方访问级别）。
 * isEmpty() 为真表示无合法范围（无应用上下文 / 无启用知识库），检索应短路返回空。
 * accessLevel：PUBLIC=只见公开文档，INTERNAL=全见（按鉴权路径判定，见 RetrievalScopeResolver）。
 *
 * @author ruan
 */
public record RetrievalScope(Long tenantId, Long appId, List<Long> kbIds, DocumentVisibility accessLevel) {

    /** 空范围（保留 tenantId 便于日志排查；accessLevel 无意义置 null）。 */
    public static RetrievalScope empty(Long tenantId) {
        return new RetrievalScope(tenantId, null, List.of(), null);
    }

    public boolean isEmpty() {
        return appId == null || kbIds == null || kbIds.isEmpty();
    }

    /**
     * 拼接 Spring AI 向量路 filterExpression：
     * tenantId == 'T' && appId == 'A' && knowledgeBaseId in ['1','2'] && docEnabled == 'true' [&& visibility == 'PUBLIC']
     * 一律等值条件（metadata 缺键行做 != 判断时 SQL 为 NULL→false 会静默筛掉，故靠摄取写全键+启动回填保证键存在）。
     * 仅在非空范围下调用，空范围抛 IllegalStateException。
     */
    public String toFilterExpression() {
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
        return sb.toString();
    }
}
```

（注意：accessLevel 为 null 时按不加 visibility 条件处理——但 Resolver 会兜底为 PUBLIC，null 只出现在直接构造的测试里。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test`
Expected: 全量单测 BUILD SUCCESS（含 RetrievalScopeTest 7 用例、其余适配点编译绿）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/RetrievalScope.java backend/src/test/java/com/qingyu/care/service/RetrievalScopeTest.java backend/src/test/java/com/qingyu/care/service/HybridRetrievalServiceTest.java backend/src/test/java/com/qingyu/care/service/ChatServiceSearchScopeTest.java
git commit -m "feat: RetrievalScope 加访问级别与 docEnabled/visibility 等值过滤条件"
```

---

### Task 3: Resolver 读级别 + 鉴权拦截器写级别

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/RetrievalScopeResolver.java`
- Modify: `backend/src/main/java/com/qingyu/care/config/TenantInterceptor.java`
- Modify: `backend/src/main/java/com/qingyu/care/config/OpenApiAuthInterceptor.java`
- Test: `backend/src/test/java/com/qingyu/care/service/RetrievalScopeResolverTest.java`（适配 + 新用例）

- [ ] **Step 1: 改测试（失败）**

`RetrievalScopeResolverTest` 既有用例 `正常_取当前应用enabled库id集合` 追加断言（在 kbIds 断言后）：

```java
        assertThat(scope.accessLevel()).isEqualTo(DocumentVisibility.PUBLIC);   // 未设上下文 → 兜底 PUBLIC
```

新增用例：

```java
    @Test
    void 上下文有访问级别_原样传导() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(2L))
                .thenReturn(List.of(KnowledgeBase.builder().id(10L).name("库A").enabled(true).build()));

        assertThat(resolver.resolve().accessLevel()).isEqualTo(DocumentVisibility.INTERNAL);
    }
```

（import `com.qingyu.care.enums.DocumentVisibility`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RetrievalScopeResolverTest`
Expected: 断言失败或编译失败（resolver 未填 accessLevel）。

- [ ] **Step 3: 实现**

`RetrievalScopeResolver.resolve()` 改为（import `com.qingyu.care.enums.DocumentVisibility`）：

```java
    public RetrievalScope resolve() {
        Long tenantId = TenantContext.getTenantId();
        Long appId = TenantContext.getAppId();
        if (appId == null) {
            log.debug("无应用上下文，检索范围为空 tenantId={}", tenantId);
            return RetrievalScope.empty(tenantId);
        }
        // 访问级别缺省按 PUBLIC 兜底（最小权限）；由鉴权拦截器按路径写入，不接受请求参数
        DocumentVisibility accessLevel = TenantContext.getAccessLevel() != null
                ? TenantContext.getAccessLevel() : DocumentVisibility.PUBLIC;
        try {
            List<Long> kbIds = knowledgeBaseRepository.findByAppIdAndEnabledTrue(appId)
                    .stream().map(KnowledgeBase::getId).toList();
            return new RetrievalScope(tenantId, appId, kbIds, accessLevel);
        } catch (Exception e) {
            log.warn("检索范围解析失败，fail-soft 为空范围 appId={}", appId, e);
            return RetrievalScope.empty(tenantId);
        }
    }
```

`TenantInterceptor.preHandle` 在 `TenantContext.setUserId(userId);` 之后加一行（import `com.qingyu.care.enums.DocumentVisibility`）：

```java
            // 管理后台已登录成员：受信，全见（含 INTERNAL 文档）
            TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);
```

`OpenApiAuthInterceptor.preHandle` 的两个鉴权分支里分别写级别（import 同上），改为：

```java
            Application app;
            if (apiKey != null && !apiKey.isBlank()) {
                // 服务端到服务端：appKey + secret（持密钥即受信，可见 INTERNAL 文档）
                app = openApiAuthService.authenticate(appId, apiKey);
                TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);
            } else {
                // 浏览器 Widget：appKey + Origin 白名单（匿名访客，仅见 PUBLIC 文档）
                app = openApiAuthService.authenticateByOrigin(appId, resolveOrigin(request));
                TenantContext.setAccessLevel(DocumentVisibility.PUBLIC);
            }
```

（两拦截器的 `afterCompletion`/异常路径已有 `TenantContext.clear()`，Task 1 已把 ACCESS_LEVEL 纳入 clear，无需额外清理。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test`
Expected: 全量单测 BUILD SUCCESS（RetrievalScopeResolverTest 5 用例）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/RetrievalScopeResolver.java backend/src/main/java/com/qingyu/care/config/TenantInterceptor.java backend/src/main/java/com/qingyu/care/config/OpenApiAuthInterceptor.java backend/src/test/java/com/qingyu/care/service/RetrievalScopeResolverTest.java
git commit -m "feat: 访问级别按鉴权路径写入（API Key/后台=INTERNAL，Widget=PUBLIC），Resolver 兜底 PUBLIC"
```

---

### Task 4: 关键词路等值条件 + 全部既有 IT 种子适配（高风险任务）

引入等值条件的同一任务内完成所有种子适配，保证 IT 层不留红窗口。**需 Docker。**

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java`
- Test: `backend/src/test/java/com/qingyu/care/it/KeywordSearchDaoIT.java`（seed 补键 + 构造点 4 参 + 新用例）
- Test: `backend/src/test/java/com/qingyu/care/it/HybridRetrievalIT.java`（seed 补键）
- Test: `backend/src/test/java/com/qingyu/care/it/MultiKbRetrievalIT.java`（seed 补键）

- [ ] **Step 1: 改 IT（失败）**

三个 IT 的 seed 辅助 metadata JSON 统一补两键 `"visibility":"PUBLIC","docEnabled":"true"`：

`KeywordSearchDaoIT.seedChunkWithScope`（并新增带可见性参数的重载）：

```java
    private void seedChunkWithScope(String content, String tenantId, String appId, String kbId) {
        seedChunkWithScope(content, tenantId, appId, kbId, "PUBLIC", "true");
    }

    /** 可指定 visibility/docEnabled 的种子（检索条件为等值判断，种子必须写全键）。 */
    private void seedChunkWithScope(String content, String tenantId, String appId, String kbId,
                                    String visibility, String docEnabled) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + tenantId + "\",\"appId\":\"" + appId
                        + "\",\"knowledgeBaseId\":\"" + kbId + "\",\"visibility\":\"" + visibility
                        + "\",\"docEnabled\":\"" + docEnabled + "\"}");
    }
```

`KeywordSearchDaoIT` 所有 `new RetrievalScope(70001L, 80001L, List.of(...))` 构造点加第 4 参 `DocumentVisibility.INTERNAL`（import），并新增两个用例：

```java
    @Test
    void PUBLIC级_检不到INTERNAL文档chunk() {
        seedChunkWithScope("公开知识 产品型号XP-1111", T1, "80001", "901", "PUBLIC", "true");
        seedChunkWithScope("内部知识 产品型号XP-1111", T1, "80001", "901", "INTERNAL", "true");

        List<KeywordSearchDao.KeywordHit> hits = keywordSearchDao.search(
                "产品型号",
                new RetrievalScope(70001L, 80001L, List.of(901L), DocumentVisibility.PUBLIC),
                10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("公开知识");
    }

    @Test
    void 禁用文档chunk_任何级别都不命中() {
        seedChunkWithScope("已禁用 产品型号XP-2222", T1, "80001", "901", "PUBLIC", "false");

        assertThat(keywordSearchDao.search(
                "产品型号",
                new RetrievalScope(70001L, 80001L, List.of(901L), DocumentVisibility.INTERNAL),
                10)).isEmpty();
    }
```

`HybridRetrievalIT.seedChunk` 与 `MultiKbRetrievalIT.seedChunk` 的 metadata JSON 同样补 `,\"visibility\":\"PUBLIC\",\"docEnabled\":\"true\"`（既有用例语义不变）。

- [ ] **Step 2: 跑 IT 确认失败**

Run: `mvn verify -Dit.test=KeywordSearchDaoIT`
Expected: 新用例失败（DAO 尚无 visibility/docEnabled 条件，INTERNAL/禁用 chunk 也被命中）。

- [ ] **Step 3: 实现 DAO 条件**

`KeywordSearchDao.search(query, scope, limit)` 在 kbIds IN 之后、tenantId 可选子句之前追加（import `com.qingyu.care.enums.DocumentVisibility`）：

```java
        // 等值条件（摄取写全键 + 启动回填保证键存在；缺键行做 <> 判断会被 SQL NULL 语义静默筛掉，故不用）
        sql.append("AND metadata ->> 'docEnabled' = 'true' ");
        if (scope.accessLevel() == DocumentVisibility.PUBLIC) {
            sql.append("AND metadata ->> 'visibility' = 'PUBLIC' ");
        }
```

- [ ] **Step 4: 跑相关 IT 确认通过**

Run: `mvn verify -Dit.test='KeywordSearchDaoIT,HybridRetrievalIT,MultiKbRetrievalIT'`
Expected: 全 PASS（6+2+5=13 用例；向量路 401 warn 属预期）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java backend/src/test/java/com/qingyu/care/it/KeywordSearchDaoIT.java backend/src/test/java/com/qingyu/care/it/HybridRetrievalIT.java backend/src/test/java/com/qingyu/care/it/MultiKbRetrievalIT.java
git commit -m "feat: 关键词路 docEnabled/visibility 等值过滤 + 既有 IT 种子补全新键"
```

---

### Task 5: 摄取写全键 + `DocMetadataBackfillRunner` 存量回填（含 appId 历史债）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/KnowledgeServiceImpl.java`（processDocument 的 metadata 块）
- Create: `backend/src/main/java/com/qingyu/care/config/DocMetadataBackfillRunner.java`
- Test: `backend/src/test/java/com/qingyu/care/it/DocMetadataBackfillIT.java`（新增）

- [ ] **Step 1: 写回填 IT（失败）**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.DocMetadataBackfillRunner;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存量 chunk 回填：缺 visibility/docEnabled 键补默认值；appId 空串按 documentId 反查 document 表回填。
 * runner 幂等（二次执行无副作用）。不用类级 @Transactional：runner 直接 JdbcTemplate 更新，
 * 种子与断言同线程同连接即可，@AfterEach 显式清理。
 *
 * @author ruan
 */
class DocMetadataBackfillIT extends AbstractPostgresIT {

    private static final String TID = "62001";

    @Autowired DocMetadataBackfillRunner runner;
    @Autowired JdbcTemplate jdbcTemplate;

    private void seedRaw(String metadataJson) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), '回填测试内容', ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                metadataJson);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", TID);
        jdbcTemplate.update("DELETE FROM document WHERE tenant_id = ?", Long.valueOf(TID));
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE tenant_id = ?", Long.valueOf(TID));
    }

    @Test
    void 缺键chunk_回填默认值_且幂等() {
        seedRaw("{\"tenantId\":\"" + TID + "\",\"appId\":\"62002\",\"knowledgeBaseId\":\"1\"}");

        runner.run(null);

        var row = jdbcTemplate.queryForMap(
                "SELECT metadata->>'visibility' AS v, metadata->>'docEnabled' AS e "
                        + "FROM vector_store WHERE metadata->>'tenantId' = ?", TID);
        assertThat(row.get("v")).isEqualTo("PUBLIC");
        assertThat(row.get("e")).isEqualTo("true");

        runner.run(null);   // 幂等：二次执行不报错、值不变
        var again = jdbcTemplate.queryForMap(
                "SELECT metadata->>'visibility' AS v FROM vector_store WHERE metadata->>'tenantId' = ?", TID);
        assertThat(again.get("v")).isEqualTo("PUBLIC");
    }

    @Test
    void appId空串chunk_按documentId反查document表回填() {
        // 先造 document 行（直接 SQL，绕过实体监听器，显式给 tenant/app）
        jdbcTemplate.update(
                "INSERT INTO knowledge_base(name, enabled, tenant_id, app_id) VALUES ('回填库', true, ?, ?)",
                Long.valueOf(TID), 62002L);
        Long kbId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_base WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, Long.valueOf(TID));
        jdbcTemplate.update(
                "INSERT INTO document(name, document_type, status, chunk_count, knowledge_base_id, tenant_id, app_id) "
                        + "VALUES ('回填文档', 'TXT', 'COMPLETED', 1, ?, ?, ?)",
                kbId, Long.valueOf(TID), 62002L);
        Long docId = jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, Long.valueOf(TID));
        // 历史坏数据：appId 空串
        seedRaw("{\"tenantId\":\"" + TID + "\",\"appId\":\"\",\"documentId\":\"" + docId
                + "\",\"knowledgeBaseId\":\"" + kbId + "\"}");

        runner.run(null);

        String appId = jdbcTemplate.queryForObject(
                "SELECT metadata->>'appId' FROM vector_store WHERE metadata->>'documentId' = ?",
                String.class, String.valueOf(docId));
        assertThat(appId).isEqualTo("62002");
    }
}
```

注意：`document.document_type`/`status` 的合法枚举值以实际表为准（`DocumentType`/`DocumentStatus`），若 `TXT` 不在 `DocumentType` 中，改用实际存在的枚举常量（先 `grep enums/DocumentType.java`）。

- [ ] **Step 2: 跑 IT 确认失败**

Run: `mvn verify -Dit.test=DocMetadataBackfillIT`
Expected: 编译失败（runner 不存在）。

- [ ] **Step 3: 实现**

`KnowledgeServiceImpl.processDocument` 的 metadata 块：`Map.of(...)` 最多 10 对，现有 5 对加 2 对仍在限内，扩为：

```java
            for (org.springframework.ai.document.Document chunk : chunks) {
                chunk.getMetadata().putAll(Map.of(
                        "documentId", documentId.toString(),
                        "knowledgeBaseId", doc.getKnowledgeBase().getId().toString(),
                        "tenantId", tenantId != null ? tenantId.toString() : "",
                        "appId", appId != null ? appId.toString() : "",
                        "source", doc.getName(),
                        // 检索条件为等值判断，摄取必写全键（缺键行会被静默筛掉）
                        "visibility", doc.getVisibility() != null ? doc.getVisibility().name() : "PUBLIC",
                        "docEnabled", "true"
                ));
            }
```

新建 `DocMetadataBackfillRunner`：

```java
package com.qingyu.care.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 存量 chunk metadata 幂等回填：检索条件为等值判断（docEnabled=='true'、visibility=='PUBLIC'），
 * 缺键的存量行会被静默筛掉，故启动时一次性补默认键；顺带收尾历史债——摄取时 appId
 * 缺上下文写成空串的 chunk 按 documentId 反查 document 表回填 appId/tenantId。
 * 幂等（WHERE 只命中缺键/坏值行），失败仅 warn 不阻断启动，下次启动自动重试。
 *
 * @author ruan
 */
@Component
@Order(1)   // 排在 HybridSearchSchemaInitializer(@Order(0)) 之后
@RequiredArgsConstructor
@Slf4j
public class DocMetadataBackfillRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int v = jdbcTemplate.update("""
                    UPDATE vector_store SET metadata = jsonb_set(metadata, '{visibility}', '"PUBLIC"')
                    WHERE metadata ->> 'visibility' IS NULL""");
            int e = jdbcTemplate.update("""
                    UPDATE vector_store SET metadata = jsonb_set(metadata, '{docEnabled}', '"true"')
                    WHERE metadata ->> 'docEnabled' IS NULL""");
            int a = jdbcTemplate.update("""
                    UPDATE vector_store v
                    SET metadata = jsonb_set(jsonb_set(v.metadata, '{appId}', to_jsonb(d.app_id::text)),
                                             '{tenantId}', to_jsonb(d.tenant_id::text))
                    FROM document d
                    WHERE (v.metadata ->> 'appId' = '' OR v.metadata ->> 'appId' IS NULL)
                      AND v.metadata ->> 'documentId' = d.id::text
                      AND d.app_id IS NOT NULL""");
            log.info("文档 metadata 回填完成：visibility={} 行, docEnabled={} 行, appId 历史债={} 行", v, e, a);
        } catch (Exception ex) {
            log.warn("文档 metadata 回填失败（不阻断启动，下次启动重试）", ex);
        }
    }
}
```

- [ ] **Step 4: 跑 IT 确认通过**

Run: `mvn verify -Dit.test='DocMetadataBackfillIT,KeywordSearchDaoIT'`
Expected: 全 PASS（回填 2 用例 + 既有 6 用例；runner 在上下文启动时也会跑一次，属预期、幂等无害）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/impl/KnowledgeServiceImpl.java backend/src/main/java/com/qingyu/care/config/DocMetadataBackfillRunner.java backend/src/test/java/com/qingyu/care/it/DocMetadataBackfillIT.java
git commit -m "feat: 摄取写 visibility/docEnabled 全键 + 存量回填 runner（顺带收尾 appId 空串历史债）"
```

---

### Task 6: 管理端后端（Service 更新方法 + jsonb_set 同步 + PUT 端点）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/VectorStoreRepository.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/KnowledgeService.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/KnowledgeServiceImpl.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/KnowledgeController.java`
- Test: `backend/src/test/java/com/qingyu/care/service/KnowledgeDocumentAccessTest.java`（新增，Mockito）

- [ ] **Step 1: 写失败单测**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文档可见性/启用状态更新：文档行变更 + vector_store metadata 同步（同一事务）。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentAccessTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock VectorStore vectorStore;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;

    private KnowledgeServiceImpl service() {
        return new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorStore, fileService, vectorStoreRepository);
    }

    @Test
    void 更新可见性_文档行与chunk元数据同步() {
        Document doc = Document.builder().id(7L).name("说明书").build();
        when(documentRepository.findById(7L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(doc)).thenReturn(doc);

        Document out = service().updateDocumentVisibility(7L, DocumentVisibility.INTERNAL);

        assertThat(out.getVisibility()).isEqualTo(DocumentVisibility.INTERNAL);
        verify(vectorStoreRepository).updateVisibilityByDocumentId("7", "INTERNAL");
    }

    @Test
    void 设置启用状态_文档行与chunk元数据同步() {
        Document doc = Document.builder().id(7L).name("说明书").build();
        when(documentRepository.findById(7L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(doc)).thenReturn(doc);

        Document out = service().setDocumentEnabled(7L, false);

        assertThat(out.getEnabled()).isFalse();
        verify(vectorStoreRepository).updateDocEnabledByDocumentId("7", "false");
    }
}
```

（若 `KnowledgeServiceImpl` 构造函数签名与上不符，以实际为准调整 mock 列表。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=KnowledgeDocumentAccessTest`
Expected: 编译失败（方法不存在）。

- [ ] **Step 3: 实现**

`VectorStoreRepository` 追加：

```java
    /** 按 documentId 同步 chunk 的可见性标记（与文档行更新同事务）。 */
    @Modifying
    @Query(value = "UPDATE vector_store SET metadata = jsonb_set(metadata, '{visibility}', to_jsonb(:visibility)) "
            + "WHERE metadata->>'documentId' = :documentId", nativeQuery = true)
    int updateVisibilityByDocumentId(@Param("documentId") String documentId, @Param("visibility") String visibility);

    /** 按 documentId 同步 chunk 的启用标记（禁用不删 chunk，重新启用免重嵌入）。 */
    @Modifying
    @Query(value = "UPDATE vector_store SET metadata = jsonb_set(metadata, '{docEnabled}', to_jsonb(:enabled)) "
            + "WHERE metadata->>'documentId' = :documentId", nativeQuery = true)
    int updateDocEnabledByDocumentId(@Param("documentId") String documentId, @Param("enabled") String enabled);
```

`KnowledgeService` 接口追加：

```java
    /**
     * 更新文档可见性（同步 chunk metadata）
     */
    Document updateDocumentVisibility(Long documentId, com.qingyu.care.enums.DocumentVisibility visibility);

    /**
     * 设置文档启用状态（同步 chunk metadata；禁用不删 chunk）
     */
    Document setDocumentEnabled(Long documentId, boolean enabled);
```

`KnowledgeServiceImpl` 追加实现（`getDocument(documentId)` 已存在可复用；import `DocumentVisibility`）：

```java
    @Override
    @Transactional
    public Document updateDocumentVisibility(Long documentId, DocumentVisibility visibility) {
        Document doc = getDocument(documentId);
        doc.setVisibility(visibility);
        Document saved = documentRepository.save(doc);
        // 与文档行同事务同步 chunk 标记，保证不出现行与 metadata 不一致的静默状态
        vectorStoreRepository.updateVisibilityByDocumentId(documentId.toString(), visibility.name());
        return saved;
    }

    @Override
    @Transactional
    public Document setDocumentEnabled(Long documentId, boolean enabled) {
        Document doc = getDocument(documentId);
        doc.setEnabled(enabled);
        Document saved = documentRepository.save(doc);
        vectorStoreRepository.updateDocEnabledByDocumentId(documentId.toString(), String.valueOf(enabled));
        return saved;
    }
```

`KnowledgeController` 追加两个端点（对齐既有 `PUT /{id}/enabled` 风格；import `AuditLog`、`DocumentVisibility`）：

```java
    @PutMapping("/documents/{documentId}/visibility")
    @Operation(summary = "更新文档可见性", description = "PUBLIC 对所有渠道可见；INTERNAL 仅受信调用（API Key/后台）可见")
    @AuditLog(action = "DOC_VISIBILITY_UPDATE", targetType = "Document")
    public ApiResponse<Document> updateDocumentVisibility(
            @PathVariable Long documentId,
            @RequestParam DocumentVisibility visibility) {
        Document doc = knowledgeService.updateDocumentVisibility(documentId, visibility);
        return ApiResponse.success("文档可见性已更新为 " + visibility, doc);
    }

    @PutMapping("/documents/{documentId}/enabled")
    @Operation(summary = "设置文档启用状态", description = "禁用的文档不参与检索；不删向量，重新启用免重嵌入")
    @AuditLog(action = "DOC_ENABLED_UPDATE", targetType = "Document")
    public ApiResponse<Document> setDocumentEnabled(
            @PathVariable Long documentId,
            @RequestParam boolean enabled) {
        Document doc = knowledgeService.setDocumentEnabled(documentId, enabled);
        return ApiResponse.success(enabled ? "文档已启用" : "文档已禁用", doc);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test`
Expected: 全量单测 BUILD SUCCESS（新增 2 用例）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/repository/VectorStoreRepository.java backend/src/main/java/com/qingyu/care/service/KnowledgeService.java backend/src/main/java/com/qingyu/care/service/impl/KnowledgeServiceImpl.java backend/src/main/java/com/qingyu/care/controller/KnowledgeController.java backend/src/test/java/com/qingyu/care/service/KnowledgeDocumentAccessTest.java
git commit -m "feat: 文档可见性/启用管理接口（行+chunk metadata 同事务同步，含审计）"
```

---

### Task 7: 端到端验收 IT（DocVisibilityRetrievalIT）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/DocVisibilityRetrievalIT.java`

- [ ] **Step 1: 写 IT（验收测试，直接全文落地）**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
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
 * 文档权限控制端到端：PUBLIC 级检不到 INTERNAL 文档、INTERNAL 级全见、禁用文档不参与、
 * 变更同步（jsonb_set）实时生效。命中均来自关键词路（向量路测试环境 fail-soft），与既有 IT 同手法。
 * 类级 @Transactional：非 HTTP 直调 service 下保证种子与查询共享 Session（见 HybridRetrievalIT 注释）。
 *
 * @author ruan
 */
@Transactional
class DocVisibilityRetrievalIT extends AbstractPostgresIT {

    private static final Long TID = 63001L;
    private static final Long APP = 63002L;

    @Autowired ChatService chatService;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired VectorStoreRepository vectorStoreRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long seedKb() {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);
        try {
            return knowledgeBaseRepository.save(KnowledgeBase.builder()
                    .name("可见性IT库").enabled(true).build()).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private void seedChunk(String content, Long kbId, String docId, String visibility, String docEnabled) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + TID + "\",\"appId\":\"" + APP
                        + "\",\"knowledgeBaseId\":\"" + kbId + "\",\"documentId\":\"" + docId
                        + "\",\"visibility\":\"" + visibility + "\",\"docEnabled\":\"" + docEnabled + "\"}");
    }

    /** 以指定访问级别发起检索（模拟鉴权拦截器写入的级别）。 */
    private List<Document> search(String q, DocumentVisibility level) {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);
        TenantContext.setAccessLevel(level);
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
    void PUBLIC级_只见公开文档_INTERNAL级_全见() {
        Long kb = seedKb();
        seedChunk("公开FAQ 产品型号XV-1111 说明", kb, "801", "PUBLIC", "true");
        seedChunk("内部价格表 产品型号XV-1111 说明", kb, "802", "INTERNAL", "true");

        List<Document> anonymous = search("产品型号XV-1111", DocumentVisibility.PUBLIC);
        assertThat(anonymous).extracting(Document::getContent)
                .anyMatch(c -> c.contains("公开FAQ"))
                .noneMatch(c -> c.contains("内部价格表"));

        List<Document> trusted = search("产品型号XV-1111", DocumentVisibility.INTERNAL);
        assertThat(trusted).extracting(Document::getContent)
                .anyMatch(c -> c.contains("公开FAQ"))
                .anyMatch(c -> c.contains("内部价格表"));
    }

    @Test
    void 未设访问级别_按PUBLIC兜底() {
        Long kb = seedKb();
        seedChunk("内部知识 产品型号XV-2222", kb, "803", "INTERNAL", "true");

        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);   // 不设 accessLevel
        try {
            assertThat(chatService.searchKnowledge("产品型号XV-2222")).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 禁用文档不参与_重新启用后恢复() {
        Long kb = seedKb();
        seedChunk("可开关知识 产品型号XV-3333", kb, "804", "PUBLIC", "true");

        // 禁用（直接走同步仓库方法，与管理端同一条路径）
        vectorStoreRepository.updateDocEnabledByDocumentId("804", "false");
        assertThat(search("产品型号XV-3333", DocumentVisibility.INTERNAL)).isEmpty();

        // 重新启用 → 恢复命中（chunk 未删、免重嵌入）
        vectorStoreRepository.updateDocEnabledByDocumentId("804", "true");
        assertThat(search("产品型号XV-3333", DocumentVisibility.INTERNAL))
                .extracting(Document::getContent).anyMatch(c -> c.contains("可开关知识"));
    }

    @Test
    void 可见性变更同步_实时生效() {
        Long kb = seedKb();
        seedChunk("待收紧知识 产品型号XV-4444", kb, "805", "PUBLIC", "true");
        assertThat(search("产品型号XV-4444", DocumentVisibility.PUBLIC)).isNotEmpty();

        vectorStoreRepository.updateVisibilityByDocumentId("805", "INTERNAL");

        assertThat(search("产品型号XV-4444", DocumentVisibility.PUBLIC)).isEmpty();
        assertThat(search("产品型号XV-4444", DocumentVisibility.INTERNAL)).isNotEmpty();
    }
}
```

- [ ] **Step 2: 跑 IT 确认通过**

Run: `mvn verify -Dit.test=DocVisibilityRetrievalIT`
Expected: 4 用例 PASS。若失败优先排查：`@Modifying` 方法在 `@Transactional` 测试内需 flush 语义（native update 直达 DB，与 JdbcTemplate 查询同连接，正常无碍）；等值键名大小写。

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/qingyu/care/it/DocVisibilityRetrievalIT.java
git commit -m "test: DocVisibilityRetrievalIT 可见性分级/兜底/开关/变更同步 端到端守护"
```

---

### Task 8: 前端管理入口（可见性切换 + 启用开关）

**Files:**
- Modify: `frontend/src/types/index.ts`（Document 接口）
- Modify: `frontend/src/api/knowledge.ts`
- Modify: `frontend/src/pages/KnowledgePage.tsx`

- [ ] **Step 1: 类型与 API**

`types/index.ts` 的 `Document` 接口加两字段：

```ts
export interface Document {
    id: number;
    name: string;
    status: DocumentStatus;
    fileSize?: number;  // 文件大小（字节）
    chunkCount?: number;
    errorMessage?: string;
    createdAt: string;
    visibility?: 'PUBLIC' | 'INTERNAL';   // 缺省视为 PUBLIC
    enabled?: boolean;                     // 缺省视为启用
}
```

`api/knowledge.ts` 的 `knowledgeService` 追加：

```ts
    // 更新文档可见性
    updateDocumentVisibility: (docId: number, visibility: 'PUBLIC' | 'INTERNAL'): Promise<ApiResponse<Document>> =>
        api.put(`/knowledge/documents/${docId}/visibility?visibility=${visibility}`),

    // 设置文档启用状态
    setDocumentEnabled: (docId: number, enabled: boolean): Promise<ApiResponse<Document>> =>
        api.put(`/knowledge/documents/${docId}/enabled?enabled=${enabled}`),
```

- [ ] **Step 2: 文档行加控件**

`KnowledgePage.tsx` 文档行（`doc.chunkCount`/日期那一排 meta 信息附近、删除按钮之前）插入两个控件。处理函数加在组件内（与 `handleDeleteDocument` 同层）：

```tsx
    const handleToggleVisibility = async (doc: Document) => {
        const next = doc.visibility === 'INTERNAL' ? 'PUBLIC' : 'INTERNAL';
        const response = await knowledgeService.updateDocumentVisibility(doc.id, next);
        if (response.code === 200) {
            setDocuments((prev) => prev.map((d) => (d.id === doc.id ? { ...d, visibility: next } : d)));
        }
    };

    const handleToggleEnabled = async (doc: Document) => {
        const next = !(doc.enabled ?? true);
        const response = await knowledgeService.setDocumentEnabled(doc.id, next);
        if (response.code === 200) {
            setDocuments((prev) => prev.map((d) => (d.id === doc.id ? { ...d, enabled: next } : d)));
        }
    };
```

行内控件（复用项目 `components/ui` 里的 `Badge`/`Switch`，若无 `Switch` 则用小按钮；样式跟随该行既有元素）：

```tsx
    <button
        onClick={() => handleToggleVisibility(doc)}
        className={`text-[10px] px-2 py-0.5 rounded-full border transition-colors ${
            doc.visibility === 'INTERNAL'
                ? 'bg-amber-50 text-amber-700 border-amber-200'
                : 'bg-emerald-50 text-emerald-700 border-emerald-200'
        }`}
        title="点击切换可见性"
    >
        {doc.visibility === 'INTERNAL' ? '内部' : '公开'}
    </button>
    <button
        onClick={() => handleToggleEnabled(doc)}
        className={`text-[10px] px-2 py-0.5 rounded-full border transition-colors ${
            (doc.enabled ?? true)
                ? 'bg-primary/10 text-primary border-primary/20'
                : 'bg-muted text-muted-foreground border-border line-through'
        }`}
        title="点击切换是否参与检索"
    >
        {(doc.enabled ?? true) ? '检索中' : '已停用'}
    </button>
```

- [ ] **Step 3: 构建 + lint**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建通过；lint 与基线相比零新增问题（存量 81 problems 是已知债务）。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/api/knowledge.ts frontend/src/pages/KnowledgePage.tsx
git commit -m "feat: 知识库文档列表加可见性切换与启用开关"
```

---

### Task 9: 全量验证 + 文档收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-07-08-doc-visibility-design.md`（status → 已实现 + 实现结论小节）
- Modify: `SaaS平台规划.md`（勾选 4.3 文档权限控制；4.3 整节完成）

- [ ] **Step 1: 全量单测**

Run: `mvn test`
Expected: BUILD SUCCESS，0 失败。记录总数。

- [ ] **Step 2: 全量含 IT**

Run: `mvn verify`
Expected: BUILD SUCCESS（Surefire + Failsafe 全绿）。记录两层数字。

- [ ] **Step 3: 更新 spec 与规划**

- spec frontmatter `status: 已评审` → `已实现`；文末追加「## 10. 实现结论（日期）」：提交序列、与设计的偏差（如实）、实测数字、行为变化提示（参照既有 spec §10 写法）。
- `SaaS平台规划.md`：`- [ ] 文档权限控制` → `- [x] 文档权限控制（PUBLIC/INTERNAL 两级 + 文档启用开关，按鉴权路径定级，等值过滤 + 存量回填）`；「实施优先级」行同步 4.3 全部完成。

- [ ] **Step 4: Commit + 汇报**

```bash
git add docs/superpowers/specs/2026-07-08-doc-visibility-design.md SaaS平台规划.md
git commit -m "docs: 文档权限控制 spec 置为已实现 + 规划勾选（Phase 4.3 全部完成）"
```

向用户汇报：任务完成情况、测试数字、偏差；分支 `feat/doc-visibility`，合并由用户决定（不 push）。

---

## 自审记录

- **Spec 覆盖**：§4.1 模型（Task 1）、§4.2 级别判定（Task 1/3）、§4.3 检索链路（Task 2/4）、§4.4 摄取与同步（Task 5/6）、§4.5 回填（Task 5）、§4.6 管理入口（Task 6/8）、§3 缺键陷阱→等值+全键（Task 2/4/5 贯彻）、§7 测试矩阵（Task 2-7）、§9 验收（Task 7/9）——无缺口。
- **占位符**：无 TBD/TODO；代码步骤全部含完整代码；仅两处「以实际为准」的核实指令（KnowledgeServiceImpl 构造签名、DocumentType 枚举值）属于防御性核实而非留白。
- **类型一致性**：`RetrievalScope(Long, Long, List<Long>, DocumentVisibility)`、`updateVisibilityByDocumentId(String, String)`、`updateDocEnabledByDocumentId(String, String)`、`updateDocumentVisibility(Long, DocumentVisibility)`、`setDocumentEnabled(Long, boolean)`、metadata 键 `visibility`/`docEnabled` 全计划一致。
- **绿窗口**：Task 2/3 只影响单测层（关键词路条件未变，IT 仍绿）；Task 4 在同一任务内引入条件并适配全部种子——每任务收尾单测全绿、IT 相关子集绿。

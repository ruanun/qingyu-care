# 混合检索（向量 + zhparser 关键词）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 RAG 对话检索链路旁挂一路 zhparser 中文关键词全文检索，与向量结果 RRF 融合，按租户经 ChatStrategy 可配，提升精确术语召回。

**Architecture:** 自定义 `pgvector + zhparser` 镜像提供中文分词 FTS；`vector_store` 加 `content_tsv` 生成列 + GIN 索引；`HybridRetrievalService` 编排「向量 similaritySearch + 关键词 FTS DAO」并按 RRF 融合；`ChatServiceImpl.searchKnowledge` 按 `ChatStrategy.hybridEnabled` 接入，全程 fail-soft。

**Tech Stack:** Spring Boot 3 / Spring AI（pgvector VectorStore）/ Spring Data JPA + JdbcTemplate / PostgreSQL zhparser 中文 FTS / Testcontainers（ImageFromDockerfile）/ 单测 Mockito。

**关键前提（读之前必读）：**
- IT 基类 `AbstractPostgresIT`（`support/`）当前用固定镜像 `pgvector/pgvector:pg17` + `.withInitScript("db/init-vector.sql")`（测试资源 `backend/src/test/resources/db/init-vector.sql`）。`mvn verify` 跑 IT（Failsafe），`mvn test` 免 Docker（Surefire）。
- 检索现状：`service/impl/ChatServiceImpl.searchKnowledge()` 用 `vectorStore.similaritySearch(SearchRequest.query(q).withTopK(topK).withSimilarityThreshold(threshold).withFilterExpression("tenantId == '<tid>'"))`；tenantId 取自 `TenantContext`。`buildContext()` 把返回的 Spring AI `Document` 列表拼上下文。**动手前先读 `ChatServiceImpl` 确认 `Document` 的取文本方法（`getContent()` 或 `getText()`，随 Spring AI 版本）与 tenantId 的确切取法，后续 Task 一律沿用现状。**
- 多级配置回退（易错陷阱）：`ChatStrategyService` 解析「租户自有 → 平台默认（**native SQL 绕 Hibernate 过滤器**取 `tenant_id IS NULL` 行）→ default 场景 → 静态兜底」，平台默认由 `@PostConstruct` 播种 `tenant_id IS NULL` 行。`ChatStrategyFallbackIT` 守护此链路。

---

## Task 1: zhparser 镜像 + 中文 FTS 配置 + Testcontainers 基座

**Files:**
- Create: `docker/postgres-zhparser/Dockerfile`
- Modify: `docker/init.sql`（compose 用；在 `CREATE EXTENSION vector` 之后加 zhparser 配置）
- Modify: `backend/src/test/resources/db/init-vector.sql`（IT 用；同样加 zhparser 配置）
- Modify: `docker/docker-compose.yml`（postgres 服务改 build 自定义镜像）
- Modify: `backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java`
- Test: `backend/src/test/java/com/qingyu/care/it/ZhparserSmokeIT.java`

- [ ] **Step 1: 写 smoke IT（验证中文分词 FTS 可用）**

`ZhparserSmokeIT.java`：
```java
package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证自定义镜像的 zhparser 扩展 + chinese_zh 文本搜索配置可用（中文能分词、能匹配）。 */
class ZhparserSmokeIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void chinese_zh配置存在且能匹配中文() {
        // to_tsvector 分词后应能被 plainto_tsquery 命中
        Boolean matched = jdbcTemplate.queryForObject(
                "SELECT to_tsvector('chinese_zh', '轻语智能客服支持产品型号XZ-9000') " +
                        "@@ plainto_tsquery('chinese_zh', '产品型号')",
                Boolean.class);
        assertThat(matched).isTrue();
    }
}
```

- [ ] **Step 2: 运行确认失败（当前镜像无 zhparser）**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=ZhparserSmokeIT`
Expected: 失败——`chinese_zh` 文本搜索配置不存在（`text search configuration "chinese_zh" does not exist`）。

- [ ] **Step 3: 创建自定义镜像 Dockerfile**

`docker/postgres-zhparser/Dockerfile`：
```dockerfile
# pgvector + zhparser（中文分词全文检索）二合一镜像
FROM pgvector/pgvector:pg17

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl bison flex \
        postgresql-server-dev-17; \
    # 1) 编译安装 SCWS（zhparser 依赖的中文分词库）
    curl -fSL -o /tmp/scws.tar.bz2 http://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2; \
    tar -xjf /tmp/scws.tar.bz2 -C /tmp; \
    cd /tmp/scws-1.2.3; ./configure --prefix=/usr/local/scws; make; make install; \
    # 2) 编译安装 zhparser 扩展
    curl -fSL -o /tmp/zhparser.tar.gz https://github.com/amutu/zhparser/archive/refs/tags/V2.2.tar.gz; \
    tar -xzf /tmp/zhparser.tar.gz -C /tmp; \
    cd /tmp/zhparser-2.2; SCWS_HOME=/usr/local/scws make; make install; \
    # 3) 清理构建依赖，减小镜像
    apt-get purge -y --auto-remove build-essential bison flex postgresql-server-dev-17 curl; \
    rm -rf /var/lib/apt/lists/* /tmp/*
```

> **风险提示（务必真实上报）**：SCWS/zhparser 的下载源（xunsearch.com、github release）可能不可达或版本变动。若 `docker build` 因下载失败或编译报错而无法完成，**停下用 BLOCKED 上报**，贴出构建日志，不要伪造通过。可尝试的调整点：SCWS 版本（1.2.3 是常用稳定版）、zhparser tag（V2.2 对应 pg17 可用）、必要时用国内镜像源。

- [ ] **Step 4: 在两个 init 脚本加 zhparser 扩展与中文文本搜索配置**

`docker/init.sql`（在 `CREATE EXTENSION ... vector` 之后追加）与 `backend/src/test/resources/db/init-vector.sql`（同样追加）都加入：
```sql
-- 中文全文检索：zhparser 扩展 + chinese_zh 文本搜索配置
CREATE EXTENSION IF NOT EXISTS zhparser;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese_zh') THEN
        CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
        ALTER TEXT SEARCH CONFIGURATION chinese_zh
            ADD MAPPING FOR n,v,a,i,e,l,j WITH simple;
    END IF;
END
$$;
```
> `DO` 块保证幂等（重复执行不报错）。词性映射 `n,v,a,i,e,l,j`（名/动/形/习用语/成语/习惯用语/简称）覆盖主要实词；`simple` 词典不做词干还原，适合中文。

- [ ] **Step 5: docker-compose 改用自定义镜像**

`docker/docker-compose.yml` 的 postgres 服务：把 `image: pgvector/pgvector:pg17` 改为
```yaml
    build:
      context: ./postgres-zhparser
```
（其余端口/卷/环境变量不变。若原来有 `image:` 行则删除或保留作为构建产物 tag：`image: qingyu/pgvector-zhparser:pg17` + `build:` 并存亦可。）

- [ ] **Step 6: `AbstractPostgresIT` 改用 ImageFromDockerfile**

把容器创建从固定镜像改为从 Dockerfile 构建（Testcontainers 按内容 hash 缓存，首跑构建、之后复用）。在 `AbstractPostgresIT` 中：

import 追加：
```java
import org.testcontainers.images.builder.ImageFromDockerfile;
import java.nio.file.Paths;
```
把
```java
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
                    .withInitScript("db/init-vector.sql");
```
改为
```java
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    new ImageFromDockerfile("qingyu/pgvector-zhparser:it", false)
                            .withDockerfile(Paths.get("../docker/postgres-zhparser/Dockerfile")))
                    .withInitScript("db/init-vector.sql");
```
> 说明：`../docker/...` 是相对 `backend/` 工作目录（Failsafe 的 CWD 为模块根）的路径，指向仓库根下的 Dockerfile。`ImageFromDockerfile(name, deleteOnExit=false)` 复用缓存镜像。`DockerImageName`/原 import 若不再使用则移除，避免 lint 报未用 import。

- [ ] **Step 7: 运行确认通过（首跑会构建镜像，耗时数分钟）**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=ZhparserSmokeIT`
Expected: 首跑构建 zhparser 镜像（~1-2 分钟），随后 `ZhparserSmokeIT` 1 绿、BUILD SUCCESS。

- [ ] **Step 8: 提交**

```bash
cd /d/Code/m/qingyu-care
git add docker/postgres-zhparser/Dockerfile docker/init.sql docker/docker-compose.yml backend/src/test/resources/db/init-vector.sql backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java backend/src/test/java/com/qingyu/care/it/ZhparserSmokeIT.java
git commit -m "feat: zhparser 中文FTS 镜像 + chinese_zh 配置 + Testcontainers 基座"
```

---

## Task 2: vector_store 生成列 + GIN 索引（HybridSearchSchemaInitializer）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/HybridSearchSchemaInitializer.java`
- Test: `backend/src/test/java/com/qingyu/care/it/HybridSchemaInitializerIT.java`

- [ ] **Step 1: 写失败的 IT（断言启动后生成列 + GIN 索引就绪）**

`HybridSchemaInitializerIT.java`：
```java
package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 应用启动后：vector_store 应有 content_tsv 生成列与其 GIN 索引（幂等）。 */
class HybridSchemaInitializerIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 生成列与GIN索引启动后就绪() {
        Integer colCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                        "WHERE table_name='vector_store' AND column_name='content_tsv'",
                Integer.class);
        assertThat(colCount).isEqualTo(1);

        Integer idxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes " +
                        "WHERE tablename='vector_store' AND indexname='idx_vector_store_content_tsv'",
                Integer.class);
        assertThat(idxCount).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=HybridSchemaInitializerIT`
Expected: 失败（`content_tsv` 列不存在，count=0）。

- [ ] **Step 3: 实现幂等的 ApplicationRunner**

`HybridSearchSchemaInitializer.java`：
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
 * 混合检索表结构初始化：给 Spring AI 运行时建的 vector_store 加中文 FTS 生成列 + GIN 索引。
 * ApplicationRunner 在上下文就绪后运行（此时 Spring AI 已建好 vector_store），幂等可重复启动。
 *
 * @author ruan
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class HybridSearchSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // chinese_zh 配置由 init 脚本建；此处兜底确保存在（生成列引用它）
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese_zh') THEN
                            CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
                            ALTER TEXT SEARCH CONFIGURATION chinese_zh
                                ADD MAPPING FOR n,v,a,i,e,l,j WITH simple;
                        END IF;
                    END
                    $$;""");
            jdbcTemplate.execute("""
                    ALTER TABLE vector_store
                        ADD COLUMN IF NOT EXISTS content_tsv tsvector
                        GENERATED ALWAYS AS (to_tsvector('chinese_zh', content)) STORED""");
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv
                        ON vector_store USING GIN (content_tsv)""");
            log.info("混合检索：vector_store.content_tsv 生成列与 GIN 索引已就绪");
        } catch (Exception e) {
            // 不阻断启动：若失败，混合检索关键词路会退化（详见 HybridRetrievalService fail-soft）
            log.warn("混合检索表结构初始化失败，关键词检索可能不可用", e);
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=HybridSchemaInitializerIT`
Expected: BUILD SUCCESS，1 绿。

- [ ] **Step 5: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/config/HybridSearchSchemaInitializer.java backend/src/test/java/com/qingyu/care/it/HybridSchemaInitializerIT.java
git commit -m "feat: HybridSearchSchemaInitializer 幂等加 content_tsv 生成列+GIN"
```

---

## Task 3: 关键词检索 DAO（中文 FTS 原生查询）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java`
- Test: `backend/src/test/java/com/qingyu/care/it/KeywordSearchDaoIT.java`

- [ ] **Step 1: 写失败的 IT（关键词命中 + 租户隔离）**

`KeywordSearchDaoIT.java`：
```java
package com.qingyu.care.it;

import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 关键词 FTS DAO：中文分词命中 + 仅命中本租户。直接向 vector_store 播种（embedding 用零向量）。 */
class KeywordSearchDaoIT extends AbstractPostgresIT {

    private static final String T1 = "70001", T2 = "70002";

    @Autowired KeywordSearchDao keywordSearchDao;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 向 vector_store 插一行；embedding 用 1024 维零向量（array_fill 造，避免巨型字面量）。 */
    private void seedChunk(String content, String tenantId) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1024])::vector)",
                content, "{\"tenantId\":\"" + tenantId + "\"}");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' IN (?,?)", T1, T2);
    }

    @Test
    void 中文关键词命中且仅本租户() {
        seedChunk("轻语客服支持产品型号XZ-9000的售后咨询", T1);
        seedChunk("其他无关内容", T1);
        seedChunk("他租户也有产品型号XZ-9000", T2);   // 他租户，不应命中

        List<KeywordSearchDao.KeywordHit> hits =
                keywordSearchDao.search("产品型号", T1, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("XZ-9000");
    }

    @Test
    void 无命中返回空() {
        seedChunk("完全不相关的文本", T1);
        assertThat(keywordSearchDao.search("量子纠缠", T1, 10)).isEmpty();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=KeywordSearchDaoIT`
Expected: 编译失败（`KeywordSearchDao` 不存在）。

- [ ] **Step 3: 实现 DAO**

`KeywordSearchDao.java`：
```java
package com.qingyu.care.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 关键词全文检索（zhparser 中文分词）DAO。直接查 Spring AI 的 vector_store，
 * 按 metadata.tenantId 隔离（与向量路 filterExpression 同源）。vector_store 非 Hibernate 实体，手写 SQL。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class KeywordSearchDao {

    private final JdbcTemplate jdbcTemplate;

    /** 一条命中：id 为 vector_store 主键（与向量路 Document.id 同源，供 RRF 对齐）。 */
    public record KeywordHit(String id, String content, Map<String, Object> metadata) {
    }

    /**
     * 按中文关键词全文检索，返回按 ts_rank_cd 降序的命中（仅本租户）。
     * plainto_tsquery 把查询串分词后按 AND 组合；无命中返回空列表。
     */
    public List<KeywordHit> search(String query, String tenantId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT id::text AS id, content, metadata::text AS metadata_json
                FROM vector_store
                WHERE content_tsv @@ plainto_tsquery('chinese_zh', ?)
                  AND metadata ->> 'tenantId' = ?
                ORDER BY ts_rank_cd(content_tsv, plainto_tsquery('chinese_zh', ?)) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new KeywordHit(
                        rs.getString("id"),
                        rs.getString("content"),
                        Map.of("tenantId", tenantId)),   // 融合只需 id/content；metadata 保留 tenantId 即可
                query, tenantId, query, limit);
    }
}
```
> 说明：查询里 `?` 顺序为 `query`(where)、`tenantId`、`query`(order by rank)、`limit`。`metadata` 融合阶段不需要完整反序列化，这里只回填 `tenantId`；若后续需要更多元数据，可解析 `metadata_json`。

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=KeywordSearchDaoIT`
Expected: BUILD SUCCESS，2 绿。

- [ ] **Step 5: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/repository/KeywordSearchDao.java backend/src/test/java/com/qingyu/care/it/KeywordSearchDaoIT.java
git commit -m "feat: 关键词 FTS DAO（zhparser 中文分词 + 租户隔离）"
```

---

## Task 4: RRF 融合（纯逻辑单测）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/RrfFuser.java`
- Test: `backend/src/test/java/com/qingyu/care/service/RrfFuserTest.java`

- [ ] **Step 1: 写失败的单测**

`RrfFuserTest.java`：
```java
package com.qingyu.care.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RRF 融合纯逻辑：多列表按 1/(k+rank) 求和排序、按 id 去重、截断 topK。 */
class RrfFuserTest {

    private RrfFuser.Item item(String id) {
        return new RrfFuser.Item(id, "内容" + id);
    }

    @Test
    void 两列表交叠项得分更高_排前() {
        // 列表A: a,b,c ; 列表B: b,d,a → b 出现在两列表且靠前，应排第一
        List<RrfFuser.Item> a = List.of(item("a"), item("b"), item("c"));
        List<RrfFuser.Item> b = List.of(item("b"), item("d"), item("a"));

        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(a, b), 60, 10);

        assertThat(fused).extracting(RrfFuser.Item::id).containsExactly("b", "a", "d", "c");
        // d(1/62) > c(1/63)：d 在其列表中排名第2，c 在其列表中排名第3，故 d 的 RRF 分数更高、排在 c 之前
    }

    @Test
    void 按id去重_同一项只出现一次() {
        List<RrfFuser.Item> a = List.of(item("x"), item("y"));
        List<RrfFuser.Item> b = List.of(item("x"));
        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(a, b), 60, 10);
        assertThat(fused).extracting(RrfFuser.Item::id).containsExactly("x", "y");
    }

    @Test
    void 截断到topK() {
        List<RrfFuser.Item> a = List.of(item("a"), item("b"), item("c"));
        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(a), 60, 2);
        assertThat(fused).hasSize(2);
    }

    @Test
    void 单列表为空不报错() {
        List<RrfFuser.Item> a = List.of(item("a"));
        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(a, List.of()), 60, 10);
        assertThat(fused).extracting(RrfFuser.Item::id).containsExactly("a");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test -Dtest=RrfFuserTest`
Expected: 编译失败（`RrfFuser` 不存在）。

- [ ] **Step 3: 实现 RrfFuser**

`RrfFuser.java`：
```java
package com.qingyu.care.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：对多路排名列表按 score(item)=Σ 1/(k+rank) 融合。
 * 基于排名、无需分数归一化（向量 cosine 与 ts_rank 量纲不可比）。按 id 去重。
 *
 * @author ruan
 */
public final class RrfFuser {

    private RrfFuser() {
    }

    /** 融合项：id 用于去重与跨列表对齐（此处即 vector_store 行 id）。 */
    public record Item(String id, String content) {
    }

    /**
     * @param rankedLists 各路的排名列表（每路已按相关性降序）
     * @param k           RRF 常数（常用 60）
     * @param topK        最终返回条数
     */
    public static List<Item> fuse(List<List<Item>> rankedLists, int k, int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Item> items = new LinkedHashMap<>();
        for (List<Item> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                Item it = list.get(rank);
                scores.merge(it.id(), 1.0 / (k + rank + 1), Double::sum); // rank 从 1 起 → +1
                items.putIfAbsent(it.id(), it);
            }
        }
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(scores.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed());
        List<Item> result = new ArrayList<>();
        for (Map.Entry<String, Double> e : ordered) {
            if (result.size() >= topK) {
                break;
            }
            result.add(items.get(e.getKey()));
        }
        return result;
    }
}
```
> 排序稳定性：`LinkedHashMap` 保留首次插入序，`List.sort` 稳定，故同分项按首次出现顺序排列（测试用例 `a,b,c,d` 中 `c` 先于 `d` 因 `c` 在列表 A 中先出现）。

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test -Dtest=RrfFuserTest`
Expected: BUILD SUCCESS，4 绿。

- [ ] **Step 5: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/service/RrfFuser.java backend/src/test/java/com/qingyu/care/service/RrfFuserTest.java
git commit -m "feat: RrfFuser RRF 融合纯逻辑 + 单测"
```

---

## Task 5: HybridRetrievalService（编排两路 + 降级）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/HybridRetrievalService.java`（接口）
- Create: `backend/src/main/java/com/qingyu/care/service/impl/HybridRetrievalServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/service/HybridRetrievalServiceTest.java`

> 先读 `ChatServiceImpl.searchKnowledge`/`buildContext` 确认：① Spring AI `Document` 构造方式（`new Document(id, text, metadata)` 或 `new Document(text, metadata)` 随版本）与取文本方法（`getContent()`/`getText()`）；② `SearchRequest` 构建 API（`SearchRequest.query(...).withTopK(...)` 或新版 `SearchRequest.builder()`）；③ tenantId 取法（`TenantContext.getTenantId()`）。**下面代码按「M4 风格 + `getContent()` + `new Document(id, content, metadata)`」写，若与现状不符，以现状为准最小适配（尤其 Document 的构造/取值和 SearchRequest 构建）。**

- [ ] **Step 1: 写失败的单测（mock 两路 + 降级）**

`HybridRetrievalServiceTest.java`：
```java
package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.service.impl.HybridRetrievalServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridRetrievalServiceTest {

    @Mock VectorStore vectorStore;
    @Mock KeywordSearchDao keywordSearchDao;
    @InjectMocks HybridRetrievalServiceImpl service;

    private ChatStrategy strategy(boolean hybrid) {
        ChatStrategy s = new ChatStrategy();
        s.setTopK(10);
        s.setSimilarityThreshold(0.7);
        s.setHybridEnabled(hybrid);
        s.setKeywordTopK(10);
        s.setRrfK(60);
        return s;
    }

    private Document vecDoc(String id) {
        return new Document(id, "向量内容" + id, Map.of());
    }

    @Test
    void 两路融合_关键词命中项进入结果() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vecDoc("a"), vecDoc("b")));
        when(keywordSearchDao.search(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new KeywordSearchDao.KeywordHit("c", "关键词内容c", Map.of())));

        List<Document> out = service.search("查询", "1", strategy(true));

        assertThat(out).extracting(Document::getId).contains("a", "b", "c");
    }

    @Test
    void 关键词路异常_降级为纯向量() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vecDoc("a")));
        when(keywordSearchDao.search(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("fts down"));

        List<Document> out = service.search("查询", "1", strategy(true));

        assertThat(out).extracting(Document::getId).containsExactly("a");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test -Dtest=HybridRetrievalServiceTest`
Expected: 编译失败（`HybridRetrievalService`/`setHybridEnabled` 等不存在）。

> 注：`ChatStrategy` 的新 setter（`setHybridEnabled`/`setKeywordTopK`/`setRrfK`）在 Task 6 才加。本 Task 为让单测先编译，允许**先在 Task 6 加字段**或在本步临时加字段——推荐调整任务顺序时把「Task 6 加 ChatStrategy 字段」的 Step 3 先做。为保持计划线性，这里在 Step 3 实现服务的同时，若 `ChatStrategy` 尚无这些字段，先补上（Task 6 再补回退/播种/IT）。

- [ ] **Step 3: 实现接口 + 实现类（必要时先给 ChatStrategy 加三字段）**

若 `ChatStrategy` 尚无字段，先加（与 Task 6 一致）：
```java
    // 混合检索配置（详见 Task 6）
    private Boolean hybridEnabled;
    private Integer keywordTopK;
    private Integer rrfK;
```

`HybridRetrievalService.java`：
```java
package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import org.springframework.ai.document.Document;

import java.util.List;

/** 混合检索：向量 + 关键词两路，RRF 融合。 */
public interface HybridRetrievalService {

    /**
     * @param query    用户查询
     * @param tenantId 当前租户（隔离）
     * @param strategy 提供 topK/similarityThreshold/keywordTopK/rrfK
     * @return 融合后的 chunk（Spring AI Document），供 buildContext 消费
     */
    List<Document> search(String query, String tenantId, ChatStrategy strategy);
}
```

`HybridRetrievalServiceImpl.java`：
```java
package com.qingyu.care.service.impl;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.RrfFuser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 混合检索编排：向量 similaritySearch + 关键词 FTS DAO，RRF 融合（按 Document.id 对齐）。
 * fail-soft：关键词路异常 → 退化为纯向量；向量路异常由上游 searchKnowledge 兜底。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridRetrievalServiceImpl implements HybridRetrievalService {

    private final VectorStore vectorStore;
    private final KeywordSearchDao keywordSearchDao;

    @Override
    public List<Document> search(String query, String tenantId, ChatStrategy strategy) {
        int topK = strategy.getTopK() != null ? strategy.getTopK() : 5;
        int keywordTopK = strategy.getKeywordTopK() != null ? strategy.getKeywordTopK() : 10;
        int rrfK = strategy.getRrfK() != null ? strategy.getRrfK() : 60;
        double threshold = strategy.getSimilarityThreshold() != null ? strategy.getSimilarityThreshold() : 0.7;

        // 向量路（沿用现状构建方式；若与 ChatServiceImpl 现状不同以现状为准）
        List<Document> vectorDocs = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(threshold)
                        .withFilterExpression("tenantId == '" + tenantId + "'"));

        // 关键词路（fail-soft：异常则空列表 → 融合退化为纯向量）
        List<RrfFuser.Item> keywordItems = new ArrayList<>();
        try {
            for (KeywordSearchDao.KeywordHit h : keywordSearchDao.search(query, tenantId, keywordTopK)) {
                keywordItems.add(new RrfFuser.Item(h.id(), h.content()));
            }
        } catch (Exception e) {
            log.warn("关键词检索失败，退化为纯向量 tenantId={}", tenantId, e);
        }

        List<RrfFuser.Item> vectorItems = new ArrayList<>();
        for (Document d : vectorDocs) {
            vectorItems.add(new RrfFuser.Item(d.getId(), d.getContent()));
        }

        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(vectorItems, keywordItems), rrfK, topK);
        // 映射回 Spring AI Document（保留 id/content；metadata 融合阶段非必需）
        List<Document> result = new ArrayList<>();
        for (RrfFuser.Item it : fused) {
            result.add(new Document(it.id(), it.content(), Map.of()));
        }
        return result;
    }
}
```
> **适配点**：`SearchRequest.query(...).withTopK/withSimilarityThreshold/withFilterExpression` 与 `d.getContent()`、`new Document(id, content, metadata)` 均需与 `ChatServiceImpl` 现状一致；若项目 Spring AI 版本用 `SearchRequest.builder()` 或 `getText()`，照现状改。

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test -Dtest=HybridRetrievalServiceTest`
Expected: BUILD SUCCESS，2 绿。

- [ ] **Step 5: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/service/HybridRetrievalService.java backend/src/main/java/com/qingyu/care/service/impl/HybridRetrievalServiceImpl.java backend/src/main/java/com/qingyu/care/entity/ChatStrategy.java backend/src/test/java/com/qingyu/care/service/HybridRetrievalServiceTest.java
git commit -m "feat: HybridRetrievalService 两路编排 + RRF 融合 + 关键词路降级"
```

---

## Task 6: ChatStrategy 配置字段 + 多级回退 + 播种

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/ChatStrategy.java`（若 Task 5 已加字段则确认）
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatStrategyService.java`（播种平台默认 + native 回退含新列）
- Test: `backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java`（扩展）

> 先读 `ChatStrategyService`：确认 ①`@PostConstruct` 播种平台默认（`tenant_id IS NULL`）的位置与字段设置；②「回退平台默认」的 **native SQL** 长什么样——**若它显式列出字段（而非 `SELECT *` 或实体映射），必须把 `hybrid_enabled`/`keyword_top_k`/`rrf_k` 三列加入 SELECT 与行映射**，否则回退取到的平台默认里新字段为 null。

- [ ] **Step 1: 扩展 `ChatStrategyFallbackIT` 加失败断言**

在 `ChatStrategyFallbackIT` 增加用例（沿用该类既有 seed/请求风格；下面为断言意图，具体 API 对齐现有用例写法）：
```java
    @Test
    void 平台默认hybrid字段_对租户可见() {
        // 不建租户自有 ChatStrategy → 解析应回退到平台默认（tenant_id IS NULL）
        // 断言解析出的 strategy.getHybridEnabled()==true（平台默认播种值）
        //        且 getKeywordTopK()/getRrfK() 为平台默认值（如 10 / 60）
        // 具体获取方式：走该类既有「解析激活策略」的入口（如 chatStrategyService.getActiveStrategy("default")）
    }
```
> 用该类现有的解析入口与断言风格补全此用例（读 `ChatStrategyFallbackIT` 现有用例照搬结构）。核心：**在无租户自有配置时，解析结果的三个新字段等于平台默认播种值**（验证 native 回退带出了新列）。

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=ChatStrategyFallbackIT`
Expected: 失败（新字段 getter 不存在，或回退取到的新字段为 null）。

- [ ] **Step 3: 加字段 + 播种默认 + native 回退带新列**

`ChatStrategy.java`（若 Task 5 未加则加；已加则确认 getter/setter 齐全，Lombok `@Data` 自动生成）：
```java
    /** 是否启用混合检索（向量+关键词）。平台默认 true。 */
    private Boolean hybridEnabled;
    /** 关键词路取回条数。默认 10。 */
    private Integer keywordTopK;
    /** RRF 融合常数 k。默认 60。 */
    private Integer rrfK;
```

`ChatStrategyService`：
- `@PostConstruct` 播种平台默认（`tenant_id IS NULL`）的那段，给新字段设默认：
```java
    defaultStrategy.setHybridEnabled(true);
    defaultStrategy.setKeywordTopK(10);
    defaultStrategy.setRrfK(60);
```
（若已存在平台默认行的「更新/补齐」逻辑，同样补设这三字段，保证既有环境升级后有值。）
- **native 回退 SQL**：若为显式列投影，加入三列并在 `RowMapper`/结果映射里 set：
```sql
SELECT id, scene, top_k, similarity_threshold, fallback_message, /*...原有列...*/
       hybrid_enabled, keyword_top_k, rrf_k
FROM chat_strategy
WHERE tenant_id IS NULL AND scene = ? AND enabled = true
ORDER BY id LIMIT 1
```
并在映射中 `strategy.setHybridEnabled(rs.getObject("hybrid_enabled", Boolean.class))` 等。
> 若该 native 查询是 `SELECT *` 或返回实体（JPA 自动映射），新列随 `ddl-auto: update` 自动纳入，无需改 SQL——**以现状为准**。

- [ ] **Step 4: 运行确认通过 + 单测无回归**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=ChatStrategyFallbackIT`
Expected: BUILD SUCCESS，含新用例全绿。
Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test`
Expected: BUILD SUCCESS（免 Docker，无回归）。

- [ ] **Step 5: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/entity/ChatStrategy.java backend/src/main/java/com/qingyu/care/service/ChatStrategyService.java backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java
git commit -m "feat: ChatStrategy 混合检索配置字段 + 平台默认回退播种"
```

---

## Task 7: 接入 ChatServiceImpl.searchKnowledge + fail-soft（端到端 IT）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（`searchKnowledge` 接入 Hybrid）
- Test: `backend/src/test/java/com/qingyu/care/it/HybridRetrievalIT.java`

> 端到端 IT 用 `@MockitoBean VectorStore` 控制向量路（避开无 embedding key 的 401），关键词路走真实 DAO + 真实 `vector_store` 种子行——验证融合与 `hybridEnabled` 开关贯通到 `searchKnowledge`。

- [ ] **Step 1: 写失败的 IT**

`HybridRetrievalIT.java`：
```java
package com.qingyu.care.it;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * 端到端：searchKnowledge 在 hybridEnabled 时融合关键词路（真实 FTS）与向量路（mock）。
 * 关键词能召回向量路漏掉的精确术语。
 *
 * @author ruan
 */
class HybridRetrievalIT extends AbstractPostgresIT {

    private static final String TID = "60001";

    @Autowired ChatService chatService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean VectorStore vectorStore;

    private void seedChunk(String content) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1024])::vector)",
                content, "{\"tenantId\":\"" + TID + "\"}");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", TID);
    }

    @Test
    void 混合检索召回向量漏掉的精确术语() {
        // 向量路（mock）只返回无关内容、漏掉精确术语
        Mockito.when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("v1", "一些语义相近但无型号的内容", Map.of())));
        // 关键词路真实：种子含精确术语 XZ-9000
        seedChunk("售后：产品型号XZ-9000 的保修政策说明");

        // 走 ChatService 的检索入口（按该类实际暴露的检索/对话方法调用；
        // 若 searchKnowledge 非 public，可用一个薄测试入口或经 chat() 断言上下文含该 chunk）
        List<Document> docs = chatServiceSearch("产品型号XZ-9000", TID);

        assertThat(docs).extracting(Document::getContent)
                .anyMatch(c -> c.contains("XZ-9000"));
    }

    @Test
    void hybrid关闭时_仅走向量路_不含关键词chunk() {
        // 给本租户播种一条 hybridEnabled=false 的自有 ChatStrategy（覆盖平台默认 true）
        // —— 复刻 ChatStrategyFallbackIT 里「租户自有策略」的 seed 方式；scene="default"、enabled=true、
        //    setHybridEnabled(false)，并在 @AfterEach 清理该策略行。
        seedTenantStrategyHybridDisabled(TID);
        Mockito.when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("v1", "纯向量内容", Map.of())));
        seedChunk("售后：产品型号XZ-9000 的保修政策说明");   // 关键词路种子，关闭时不应出现

        List<Document> docs = chatServiceSearch("产品型号XZ-9000", TID);

        assertThat(docs).extracting(Document::getContent).containsExactly("纯向量内容");
        assertThat(docs).extracting(Document::getContent).noneMatch(c -> c.contains("XZ-9000"));
    }

    // 按 ChatService 实际 API 实现的检索入口（占位——Step 3 明确）
    private List<Document> chatServiceSearch(String q, String tid) {
        return null; // Step 3 用真实入口替换
    }

    // 复刻 ChatStrategyFallbackIT 的租户自有策略 seed（scene=default、enabled、hybridEnabled=false）；
    // @AfterEach 需清理该策略行。Step 3 落地时按 ChatStrategyRepository 实际 API 实现。
    private void seedTenantStrategyHybridDisabled(String tenantId) {
        // Step 3 实现：mirror ChatStrategyFallbackIT 的 seed 助手
    }
}
```
> 第二个用例断言「关闭混合检索时，关键词种子 chunk 不出现，只返回向量路（mock）结果」。`seedTenantStrategyHybridDisabled` 与清理按 `ChatStrategyFallbackIT` 既有租户策略 seed/清理方式落地（读该类照搬），`chatServiceSearch` 入口在 Step 3 与首个用例一并回填。
> **说明**：`searchKnowledge` 现为 `ChatServiceImpl` 私有方法。两种落地方式（Step 3 选其一并回填上面 `chatServiceSearch`）：**(a)** 把 `searchKnowledge` 提升为 `ChatService` 接口上的可测方法（`List<Document> searchKnowledge(String query)`，租户取自 `TenantContext`——IT 需在调用前 `TenantContext.setTenantId(...)`）；**(b)** 经 `chat()` 端到端断言回答上下文包含该 chunk。推荐 (a)，改动小、断言直接。

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=HybridRetrievalIT`
Expected: 失败（`chatServiceSearch` 占位返回 null / 或所选入口未接 Hybrid）。

- [ ] **Step 3: 接入 `searchKnowledge` + 暴露可测入口**

在 `ChatServiceImpl`：注入 `HybridRetrievalService`（`@RequiredArgsConstructor` final 字段）与 `ChatStrategyService`（若未注入）。改 `searchKnowledge`：
```java
    // 原：仅向量。改为按策略选择混合/纯向量，全程 fail-soft
    public List<Document> searchKnowledge(String query) {
        try {
            ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");
            String tenantId = String.valueOf(TenantContext.getTenantId());
            if (Boolean.TRUE.equals(strategy.getHybridEnabled())) {
                return hybridRetrievalService.search(query, tenantId, strategy);
            }
            // 纯向量（保留原有构建方式）
            return vectorStore.similaritySearch(SearchRequest.query(query)
                    .withTopK(strategy.getTopK() != null ? strategy.getTopK() : 5)
                    .withSimilarityThreshold(strategy.getSimilarityThreshold() != null ? strategy.getSimilarityThreshold() : 0.7)
                    .withFilterExpression("tenantId == '" + tenantId + "'"));
        } catch (Exception e) {
            log.warn("知识检索失败，返回空（fail-soft）", e);
            return List.of();
        }
    }
```
- 若 `searchKnowledge` 原来接收 `(query, topK, threshold)` 参数，保持对内部 `chat()/streamChat()` 调用点的兼容：可新增无参重载或调整调用点改为从 strategy 取值。**以现状最小改动为准，保证 `chat()`/`streamChat()` 仍能编译并走到该逻辑。**
- 若选方式 (a)：把该方法提升到 `ChatService` 接口（`List<Document> searchKnowledge(String query)`）。IT 里回填 `chatServiceSearch`：`TenantContext.setTenantId(Long.valueOf(tid)); try { return chatService.searchKnowledge(q); } finally { TenantContext.clear(); }`。

- [ ] **Step 4: 运行确认通过 + 全量 IT 无回归**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=HybridRetrievalIT`
Expected: BUILD SUCCESS，1 绿。
Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/service backend/src/test/java/com/qingyu/care/it/HybridRetrievalIT.java
git commit -m "feat: searchKnowledge 接入混合检索（hybridEnabled 开关 + fail-soft）"
```

---

## Task 8: 全量回归 + spec 置为已实现

- [ ] **Step 1: 全量回归**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify`
Expected: 单测 + 全部 IT（含 `ZhparserSmokeIT`/`HybridSchemaInitializerIT`/`KeywordSearchDaoIT`/`RrfFuserTest`/`HybridRetrievalServiceTest`/`ChatStrategyFallbackIT`/`HybridRetrievalIT`）全绿，BUILD SUCCESS。首跑构建 zhparser 镜像。
> 若有失败：判断偶发（容器/镜像构建竞态）可重跑一次；稳定失败指向真实缺陷则 BLOCKED 上报，不擅改吞测试。

- [ ] **Step 2: 更新 spec 状态**

把 `docs/superpowers/specs/2026-07-06-hybrid-retrieval-design.md` frontmatter 的 `status: 草案` 改为 `status: 已实现`，末尾追加「实现结论」一节（zhparser 镜像 + 生成列 + RRF 融合 + ChatStrategy 回退 + fail-soft；如实记录端到端 IT 用 mock 向量路 + 真实关键词路的覆盖方式）。

- [ ] **Step 3: 提交**

```bash
cd /d/Code/m/qingyu-care
git add docs/superpowers/specs/2026-07-06-hybrid-retrieval-design.md
git commit -m "docs: 混合检索 spec 置为已实现"
```

- [ ] **Step 4: 验收核对**（对照 spec §9）
  - 自定义镜像部署后 `chinese_zh` 配置 + `content_tsv` 生成列 + GIN 自动就绪（幂等）。
  - `hybridEnabled` 开启后精确术语可召回；关闭回到纯向量。
  - 关键词路与向量路同样只命中本租户。
  - `ChatStrategy` 新字段走租户→平台默认（native 绕过滤器）回退，`ChatStrategyFallbackIT` 绿。
  - 关键词路故障对话不中断（降级纯向量）。
  - `mvn verify` 全绿、`mvn test` 免 Docker 通过。

---

## 附：任务依赖与说明

- 严格顺序：Task 1（镜像/配置/基座）是所有 IT 的前提 → Task 2（生成列）→ Task 3（DAO，依赖生成列）；Task 4（RRF）独立可先做；Task 5（服务）依赖 3+4，并会先给 `ChatStrategy` 加字段；Task 6（回退/播种/IT）补齐字段的回退语义；Task 7（接入）依赖 5+6；Task 8 收尾。
- 全程 TDD、小提交；后端单测（Task 4/5 的 `*Test`）免 Docker，IT（`*IT`）需 Docker Desktop 且首跑构建 zhparser 镜像。
- **前端不在本期范围**：ChatStrategy 新字段的管理端 UI 暴露留作后续（平台默认 `hybridEnabled=true` 使特性无需 UI 即生效）。
- **最大风险**：Task 1 的 zhparser 镜像构建（下载源/编译）。若不可达，按 Task 1 Step 3 的提示 BLOCKED 上报。

# 站点爬取实现计划（网站知识库第二期）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给定一个入口 URL，自动发现并抓取该站点范围内的多个页面入知识库（每页一篇文档），站点配置持久化以支持一键重新爬取，正文提取支持自定义 CSS 选择器。

**Architecture:** 三个新增单元各担一职——`LinkExtractor`（纯函数：URL 规范化 + 范围判定 + 抽链接）、`RobotsChecker`（robots.txt 抓取与解析）、`SiteCrawlService`（BFS 编排 + 内存进度 + 站点级互斥）。新实体 `CrawlSite` 持久化站点配置，`Document` 加可空外键指向它。第一期的 `WebPageFetcher` 只加一个带选择器的重载并回传剔噪前的 DOM 副本，`WebPageSyncService` 的三分支同步逻辑**原样复用、不重构**。

**Tech Stack:** Spring Boot 4.1 + JDK 17 + JPA/Hibernate 6 + PostgreSQL(pgvector) + Jsoup + Apache HttpClient5 + JUnit 5 / Mockito / AssertJ / Testcontainers；前端 React 19 + TypeScript + Vite + pnpm。

**设计文档：** `docs/superpowers/specs/2026-08-06-site-crawl-design.md`（下称 spec，章节号引用它）
**第一期文档：** `docs/superpowers/specs/2026-08-04-website-knowledge-design.md`

---

## Global Constraints

以下是全项目铁律，**每个任务的要求都隐含包含本节**：

- **语言**：对话、代码注释、commit message 一律简体中文。新建文件的 `@author` 写 `ruan`。
- **Git**：本仓库允许 `git commit`。当前已在特性分支 `feature/site-crawl` 上，**不要切分支、不要 `git push`、不要 `git merge`/`rebase`/`reset`**。
- **包管理器**：前端一律 `pnpm`，不用 npm/yarn。
- **爬取规模硬顶**：页数 500、深度 5（配置项 `qingyu.care.site-crawl.max-pages-limit` / `max-depth-limit`）。默认值：页数 50、深度 3。服务端**必须**钳制入参到硬顶，不能只靠前端。
- **默认限速** 1000ms（`default-delay-ms`），robots.txt 的 `Crawl-delay` 优先于它。
- **全局并发爬取任务上限** 2（`max-concurrent-jobs`）。
- **同 host 是硬约束**：`scopePrefix` 的 host 必须与 `entryUrl` 一致，服务端强制校验。这是安全边界不是配置项。
- **知识库归属一律经 `knowledgeService.getKnowledgeBase(id)`**（内含 `TenantOwnershipGuard`）。**绝不用 `knowledgeBaseRepository.findById`**——后者不受 Hibernate `@Filter` 保护，等于让任意租户往别人知识库里插文档（第一期的 Critical 越权教训）。
- **测试分层**：单元测试用纯 Mockito（`@ExtendWith(MockitoExtension.class)`），**不启 Spring 容器、不需要 Docker**，`mvn test` 跑；集成测试类名以 `IT` 结尾、放 `it/` 包、继承 `com.qingyu.care.support.AbstractPostgresIT`，`mvn verify` 跑（需 Docker Desktop）。**新增测试优先写成纯 Mockito 单测。**
- ⚠️ **跑 `mvn verify` 前必须先停掉本机在跑的 dev 后端（`:8080`）**，否则 `WebhookDeliveryIT` 必红（与 dev 后端共用本机 Redis 消费组，表现为一条 10 秒 `ConditionTimeout`，看不出与 Redis 有关）。
- **测试有效性是硬要求**：每个任务收尾前，对该任务的关键守护做一次**变异自证**——把实现改坏（注释掉一行守卫 / 把上限改大），确认**有测试变红**，然后改回来。绿本身证明不了测试有效。

---

## 文件结构

**后端新建**（`backend/src/main/java/com/qingyu/care/`）：

| 文件 | 职责 |
|:---|:---|
| `entity/CrawlSite.java` | 站点配置实体，继承 `BaseTenantEntity` |
| `repository/CrawlSiteRepository.java` | 站点仓储 |
| `config/SiteCrawlProperties.java` | 配置绑定（`qingyu.care.site-crawl.*`） |
| `service/web/LinkExtractor.java` | 纯函数：`normalize` / `inScope` / `extract` |
| `service/web/RobotsChecker.java` | robots.txt 抓取与解析 |
| `service/web/SiteCrawlService.java` | BFS 编排 + 内存进度 + 站点级互斥 |
| `dto/CrawlSiteRequest.java` | 建站/改配置入参 |
| `dto/CrawlStartResponse.java` | `{siteId, jobId}` |
| `dto/CrawlProgressDTO.java` | 进度响应 |

**后端修改**：

| 文件 | 改动 |
|:---|:---|
| `entity/Document.java` | 加可空外键 `crawlSite` |
| `repository/DocumentRepository.java` | 加 `findFirstByKnowledgeBaseIdAndSourceUrl`、`findByCrawlSiteId` |
| `service/web/WebPageFetcher.java` | `fetch` 重载（选择器）+ `FetchResult` 加 `dom` + `download` 提包级 |
| `service/web/WebPageSyncService.java` | `syncDocument` 里 fetch 传所属站点的选择器（一行） |
| `controller/KnowledgeController.java` | 6 个端点 |
| `src/main/resources/application.yml` | `qingyu.care.site-crawl.*` 配置块 |

**后端测试新建**：`service/web/LinkExtractorTest.java`、`RobotsCheckerTest.java`、`SiteCrawlServiceTest.java`、`WebPageFetcherSelectorTest.java`、`entity/CrawlSiteEqualsTest.java`、`it/SiteCrawlIT.java`

**前端**：新建 `src/components/knowledge/CrawlSiteDialog.tsx`、`CrawlSiteList.tsx`；修改 `src/api/knowledge.ts`、`src/pages/KnowledgePage.tsx`

**文档**：`CLAUDE.md`（架构要点 + 手工验证项）、`SaaS平台规划.md`（标记完成）

---

## Task 1: CrawlSite 实体 + 仓储 + Document 外键 + 配置绑定

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/entity/CrawlSite.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/CrawlSiteRepository.java`
- Create: `backend/src/main/java/com/qingyu/care/config/SiteCrawlProperties.java`
- Modify: `backend/src/main/java/com/qingyu/care/entity/Document.java`（加外键字段）
- Modify: `backend/src/main/java/com/qingyu/care/repository/DocumentRepository.java`（加两个派生查询）
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/qingyu/care/entity/CrawlSiteEqualsTest.java`

**Interfaces:**
- Consumes: 无（本任务是地基）
- Produces:
  - `CrawlSite`（Lombok `@Builder`）字段：`Long id`、`KnowledgeBase knowledgeBase`、`String entryUrl`、`String scopePrefix`、`Integer maxPages`、`Integer maxDepth`、`String contentSelector`、`SyncInterval syncInterval`、`LocalDateTime lastCrawledAt`、`createdAt`、`updatedAt`
  - `CrawlSiteRepository.findByKnowledgeBaseIdOrderByIdDesc(Long) : List<CrawlSite>`
  - `Document.getCrawlSite() : CrawlSite` / `Document.setCrawlSite(CrawlSite)`（`@Data` 生成）
  - `DocumentRepository.findFirstByKnowledgeBaseIdAndSourceUrl(Long, String) : Optional<Document>`
  - `DocumentRepository.findByCrawlSiteId(Long) : List<Document>`
  - `SiteCrawlProperties` getter：`getMaxPagesLimit()`、`getMaxDepthLimit()`、`getDefaultDelayMs()`、`getMaxConcurrentJobs()`

**背景（实现者需要知道的）：** `BaseTenantEntity` 是 `@MappedSuperclass`，带 `@Data` 且声明了 `tenant_id`/`app_id` 两个 Hibernate `@Filter`；继承它的实体在写入时由 `TenantEntityListener` 自动回填这两个字段，查询时自动被过滤器收窄。

- [ ] **Step 1: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/entity/CrawlSiteEqualsTest.java`：

```java
package com.qingyu.care.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@link CrawlSite} 的判等语义：必须按 {@code id} 判等。
 *
 * <p>不显式声明 {@code @EqualsAndHashCode} 就会直接继承 {@link BaseTenantEntity} 上
 * {@code @Data} 生成的版本——那个版本只比较 {@code tenantId}/{@code appId}，于是两个
 * 内容完全不同、但都还没落租户上下文（两者皆为 null）的站点实例会被判定为"相等"，
 * 放进 {@code Set}/{@code Map} 去重时会静默合并。{@code ChatAttachment} 踩过同一个坑。
 *
 * @author ruan
 */
class CrawlSiteEqualsTest {

    @Test
    @DisplayName("租户上下文均为 null 时，不同 id 的站点不相等")
    void distinct_ids_are_not_equal_even_without_tenant_context() {
        CrawlSite a = CrawlSite.builder().id(1L).entryUrl("https://a.com/help/").build();
        CrawlSite b = CrawlSite.builder().id(2L).entryUrl("https://b.com/docs/").build();

        assertThat(a.getTenantId()).isNull();
        assertThat(b.getTenantId()).isNull();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("id 相同即相等，其余字段不参与判等")
    void same_id_is_equal_regardless_of_other_fields() {
        CrawlSite a = CrawlSite.builder().id(7L).entryUrl("https://a.com/help/").maxPages(50).build();
        CrawlSite b = CrawlSite.builder().id(7L).entryUrl("https://b.com/docs/").maxPages(200).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn -q test -Dtest=CrawlSiteEqualsTest
```
预期：编译失败，`CrawlSite` 类不存在。

- [ ] **Step 3: 建 CrawlSite 实体**

创建 `backend/src/main/java/com/qingyu/care/entity/CrawlSite.java`：

```java
package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qingyu.care.enums.SyncInterval;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 爬取站点配置。一个站点爬出 N 篇独立的 {@link Document}（每页一篇），
 * 配置持久化是为了让运营能一键「重新爬取」以发现新页面，而不必重填入口/范围/深度/选择器。
 *
 * <p>显式声明按 {@code id} 判等：不声明会直接继承 {@link BaseTenantEntity} 上
 * {@code @Data} 生成的 equals/hashCode，那个版本只比较 {@code tenantId}/{@code appId}——
 * 两个内容完全不同、但都还没落租户上下文（两者皆为 null）的站点实例会被判定为"相等"，
 * 用在 {@code Set}/{@code Map} 去重时会静默错误合并（{@code ChatAttachment} 踩过同一个坑）。
 *
 * @author ruan
 */
@Entity
@Table(name = "crawl_site")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class CrawlSite extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** 爬出来的页都进这个知识库。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    /** 入口 URL；列宽对齐 {@code document.source_url}。 */
    @Column(name = "entry_url", nullable = false, length = 2048)
    private String entryUrl;

    /**
     * 范围前缀：只爬以此为前缀的 URL。创建时由入口 URL 推导预填，用户可改，
     * 但 host 必须与 {@link #entryUrl} 一致（服务端强制校验，安全边界）。
     */
    @Column(name = "scope_prefix", nullable = false, length = 2048)
    private String scopePrefix;

    /** 单次爬取的页数上限（已按平台硬顶钳制）。 */
    @Column(name = "max_pages", nullable = false)
    private Integer maxPages;

    /** 链接发现深度上限（已按平台硬顶钳制）；入口页为深度 0。 */
    @Column(name = "max_depth", nullable = false)
    private Integer maxDepth;

    /**
     * 正文 CSS 选择器（白名单）。为空则走 {@code WebPageFetcher} 的启发式噪声剔除。
     * 刻意不冗余到 {@code Document} 上——读站点行意味着改了选择器之后，
     * 已建文档的下一轮定期同步立刻用新值。
     */
    @Column(name = "content_selector", length = 500)
    private String contentSelector;

    /** 爬出的每篇文档写入这个同步周期。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_interval", nullable = false, length = 20)
    private SyncInterval syncInterval;

    /** 上次爬取完成时间，列表展示用。 */
    @Column(name = "last_crawled_at")
    private LocalDateTime lastCrawledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn -q test -Dtest=CrawlSiteEqualsTest
```
预期：2 个用例 PASS。

- [ ] **Step 5: 变异自证**

临时把 `CrawlSite` 上的 `@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)` 整行注释掉，重跑 Step 4 —— **必须**看到 `distinct_ids_are_not_equal_even_without_tenant_context` 变红（继承来的版本会把两个 null 租户的实例判为相等）。确认后把注解改回来，再跑一次确认恢复绿。

- [ ] **Step 6: 建仓储**

创建 `backend/src/main/java/com/qingyu/care/repository/CrawlSiteRepository.java`：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.CrawlSite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 爬取站点仓储。
 *
 * <p>普通派生查询受 Hibernate {@code @Filter}（tenant/app）保护；但 {@code findById}
 * 不受保护（Hibernate 6 的已知行为），故服务层取站点后仍要过
 * {@code TenantOwnershipGuard.mutableInCurrentContext} 显式校验。
 *
 * @author ruan
 */
@Repository
public interface CrawlSiteRepository extends JpaRepository<CrawlSite, Long> {

    /** 列某知识库下的站点，最新创建的在前。 */
    List<CrawlSite> findByKnowledgeBaseIdOrderByIdDesc(Long knowledgeBaseId);
}
```

- [ ] **Step 7: 给 Document 加外键，给 DocumentRepository 加两个查询**

在 `backend/src/main/java/com/qingyu/care/entity/Document.java` 的 `syncFailureCount` 字段之后、`createdAt` 之前插入：

```java
    /**
     * 所属爬取站点；单页手动添加的网页文档与文件类文档为 null。
     * 定期同步经它读 {@code contentSelector}（见 WebPageSyncService.syncDocument）。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crawl_site_id")
    private CrawlSite crawlSite;
```

在 `backend/src/main/java/com/qingyu/care/repository/DocumentRepository.java` 的接口体内追加：

```java
    /**
     * 按知识库 + 源地址找已存在的文档，供爬取判断"这一页是否已建过档"。
     *
     * <p>⚠️ 用 {@code findFirst} 而不是返回单个结果的 {@code findBy...}：第一期的
     * {@code addWebPage} 没有去重校验，存量库可能已有同 URL 的重复文档，
     * 用单结果查询会在那些库上抛 {@code IncorrectResultSizeDataAccessException}
     * 把整轮爬取打断。加 {@code ORDER BY id} 保证选中的是最早那篇、结果稳定。
     */
    Optional<Document> findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc(Long knowledgeBaseId, String sourceUrl);

    /** 某爬取站点下的全部文档，供删除站点时连带处理。 */
    List<Document> findByCrawlSiteId(Long crawlSiteId);
```

（若 `DocumentRepository` 尚未 import `java.util.Optional`，一并加上。）

- [ ] **Step 8: 建配置绑定类**

创建 `backend/src/main/java/com/qingyu/care/config/SiteCrawlProperties.java`：

```java
package com.qingyu.care.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 站点爬取配置（qingyu.care.site-crawl.*）。
 *
 * @author ruan
 */
@Component
@ConfigurationProperties(prefix = "qingyu.care.site-crawl")
@Data
public class SiteCrawlProperties {

    /** 单次爬取页数硬顶。服务端按它钳制入参，不能只靠前端。 */
    private int maxPagesLimit = 500;

    /** 链接发现深度硬顶。 */
    private int maxDepthLimit = 5;

    /** 默认抓取间隔（毫秒）；robots.txt 的 Crawl-delay 优先于它。 */
    private long defaultDelayMs = 1000;

    /**
     * 全局并发爬取任务上限。不只是对目标站点礼貌的问题：@Async 池
     * （Spring Boot 默认 applicationTaskExecutor，core 8）是和审计日志异步落库、
     * webhook 直投、文档摄取共享的，几分钟的长任务无上界并发会挤掉那些短任务。
     */
    private int maxConcurrentJobs = 2;
}
```

- [ ] **Step 9: 加 application.yml 配置块**

在 `backend/src/main/resources/application.yml` 的 `qingyu.care` 节点下（与 `web-sync` 同级）加：

```yaml
    # 站点爬取（网站知识库第二期）
    site-crawl:
      max-pages-limit: 500        # 单次爬取页数硬顶
      max-depth-limit: 5          # 链接发现深度硬顶
      default-delay-ms: 1000      # 默认抓取间隔（robots 的 Crawl-delay 优先）
      max-concurrent-jobs: 2      # 全局并发爬取任务上限
```

先执行 `grep -n "web-sync" -A 4 -B 2 backend/src/main/resources/application.yml` 确认 `qingyu.care` 的实际缩进层级，按它对齐。

- [ ] **Step 10: 全量编译 + 单测**

```bash
cd backend && mvn -q clean test
```
预期：BUILD SUCCESS，无失败。`ddl-auto: update` 会在应用启动时建 `crawl_site` 表与 `document.crawl_site_id` 列，单测不启容器所以此处只验编译与既有测试无回归。

- [ ] **Step 11: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/CrawlSite.java \
        backend/src/main/java/com/qingyu/care/repository/CrawlSiteRepository.java \
        backend/src/main/java/com/qingyu/care/config/SiteCrawlProperties.java \
        backend/src/main/java/com/qingyu/care/entity/Document.java \
        backend/src/main/java/com/qingyu/care/repository/DocumentRepository.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/qingyu/care/entity/CrawlSiteEqualsTest.java
git commit -m "feat(backend): CrawlSite 实体与配置绑定——站点爬取的数据地基

- CrawlSite 继承 BaseTenantEntity，显式按 id 判等（不声明会继承基类 @Data
  只比 tenantId/appId 的版本，两个 null 租户的实例会被判为相等）
- Document 加可空外键 crawl_site_id；contentSelector 刻意不冗余，读站点行
  意味着改了选择器后已建文档的下一轮同步立刻用新值
- findFirstBy...OrderByIdAsc 而非单结果查询：存量库可能有重复 URL"
```

---

## Task 2: LinkExtractor（URL 规范化 + 范围判定 + 抽链接）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/web/LinkExtractor.java`
- Test: `backend/src/test/java/com/qingyu/care/service/web/LinkExtractorTest.java`

**Interfaces:**
- Consumes: 无
- Produces（全部 `public static`，供 `SiteCrawlService` 调用）：
  - `LinkExtractor.normalize(String url) : String`（非法/非 http(s) 返回 `null`）
  - `LinkExtractor.inScope(String normalizedUrl, String normalizedPrefix) : boolean`
  - `LinkExtractor.extract(org.jsoup.nodes.Document dom, String normalizedPrefix) : Set<String>`
  - `LinkExtractor.hostOf(String url) : String`（非法返回 `null`）

**背景：** 这是本期最好测的一块——不碰网络也不碰数据库。`org.jsoup.nodes.Document` 与本项目实体 `Document` 同名，代码里一律用全限定名区分（第一期 `WebPageFetcher` 已是这个写法）。

⚠️ **`inScope` 不能写成裸 `startsWith`**，spec §4.1 列了三个会同时出问题的边界，实现里的三个 `return` 分支各对应一个，测试要逐个覆盖。

- [ ] **Step 1: 写失败测试（规范化部分）**

创建 `backend/src/test/java/com/qingyu/care/service/web/LinkExtractorTest.java`：

```java
package com.qingyu.care.service.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LinkExtractor} 单测。纯函数、不碰网络与数据库。
 *
 * @author ruan
 */
class LinkExtractorTest {

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("去掉 fragment——锚点是同一页")
        void strips_fragment() {
            assertThat(LinkExtractor.normalize("https://x.com/help/#top")).isEqualTo("https://x.com/help");
        }

        @Test
        @DisplayName("去掉 tracking 参数，保留其余参数并按名排序")
        void strips_tracking_params_and_sorts_rest() {
            assertThat(LinkExtractor.normalize("https://x.com/help?page=2&utm_source=a&fbclid=b"))
                    .isEqualTo("https://x.com/help?page=2");
            assertThat(LinkExtractor.normalize("https://x.com/help?b=2&a=1"))
                    .isEqualTo("https://x.com/help?a=1&b=2");
        }

        @Test
        @DisplayName("路径末尾斜杠统一去掉，但根路径保留")
        void unifies_trailing_slash_but_keeps_root() {
            assertThat(LinkExtractor.normalize("https://x.com/help/")).isEqualTo("https://x.com/help");
            assertThat(LinkExtractor.normalize("https://x.com/help")).isEqualTo("https://x.com/help");
            assertThat(LinkExtractor.normalize("https://x.com/help///")).isEqualTo("https://x.com/help");
            assertThat(LinkExtractor.normalize("https://x.com/")).isEqualTo("https://x.com/");
            assertThat(LinkExtractor.normalize("https://x.com")).isEqualTo("https://x.com/");
        }

        @Test
        @DisplayName("host 小写，scheme 不升级")
        void lowercases_host_without_upgrading_scheme() {
            assertThat(LinkExtractor.normalize("HTTP://X.COM/Help")).isEqualTo("http://x.com/Help");
        }

        @Test
        @DisplayName("非 http(s) 与非法 URL 返回 null")
        void rejects_non_http_and_malformed() {
            assertThat(LinkExtractor.normalize("mailto:a@b.com")).isNull();
            assertThat(LinkExtractor.normalize("javascript:alert(1)")).isNull();
            assertThat(LinkExtractor.normalize("tel:123")).isNull();
            assertThat(LinkExtractor.normalize("ht tp://x.com")).isNull();
            assertThat(LinkExtractor.normalize("https://")).isNull();
        }

        @Test
        @DisplayName("非默认端口保留")
        void keeps_explicit_port() {
            assertThat(LinkExtractor.normalize("https://x.com:8443/help/")).isEqualTo("https://x.com:8443/help");
        }
    }

    @Nested
    @DisplayName("inScope 的三个边界")
    class InScope {

        @Test
        @DisplayName("边界一：入口页自身在范围内（规范化去掉了斜杠，前缀带斜杠）")
        void entry_page_itself_is_in_scope() {
            assertThat(LinkExtractor.inScope("https://x.com/help", "https://x.com/help")).isTrue();
        }

        @Test
        @DisplayName("边界二：helpdesk 这类兄弟目录被排除")
        void sibling_directory_sharing_prefix_is_excluded() {
            assertThat(LinkExtractor.inScope("https://x.com/helpdesk", "https://x.com/help")).isFalse();
            assertThat(LinkExtractor.inScope("https://x.com/help-center", "https://x.com/help")).isFalse();
        }

        @Test
        @DisplayName("边界三：根路径前缀能匹配子页")
        void root_prefix_matches_children() {
            assertThat(LinkExtractor.inScope("https://x.com/faq", "https://x.com/")).isTrue();
            assertThat(LinkExtractor.inScope("https://x.com/", "https://x.com/")).isTrue();
        }

        @Test
        @DisplayName("范围内的子页与更深层都放行")
        void children_and_grandchildren_are_in_scope() {
            assertThat(LinkExtractor.inScope("https://x.com/help/faq", "https://x.com/help")).isTrue();
            assertThat(LinkExtractor.inScope("https://x.com/help/faq/a", "https://x.com/help")).isTrue();
        }
    }

    @Nested
    @DisplayName("extract")
    class Extract {

        private org.jsoup.nodes.Document dom(String bodyHtml) {
            return Jsoup.parse("<html><body>" + bodyHtml + "</body></html>", "https://x.com/help/");
        }

        @Test
        @DisplayName("相对地址按 baseUri 解析")
        void resolves_relative_urls() {
            Set<String> links = LinkExtractor.extract(dom("<a href='faq'>f</a>"), "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/faq");
        }

        @Test
        @DisplayName("外域链接被排除")
        void excludes_other_hosts() {
            Set<String> links = LinkExtractor.extract(
                    dom("<a href='https://other.com/help/x'>o</a><a href='/help/ok'>k</a>"),
                    "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/ok");
        }

        @Test
        @DisplayName("范围外的同域链接被排除")
        void excludes_out_of_scope_same_host() {
            Set<String> links = LinkExtractor.extract(
                    dom("<a href='/blog/post'>b</a><a href='/help/ok'>k</a>"),
                    "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/ok");
        }

        @Test
        @DisplayName("非 http(s) 协议与空 href 被排除，不抛异常")
        void excludes_non_http_schemes() {
            Set<String> links = LinkExtractor.extract(
                    dom("<a href='mailto:a@b.com'>m</a><a href='javascript:void(0)'>j</a>"
                        + "<a href=''>e</a><a href='/help/ok'>k</a>"),
                    "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/ok");
        }

        @Test
        @DisplayName("同一页的不同写法规范化后只保留一份")
        void dedupes_after_normalization() {
            Set<String> links = LinkExtractor.extract(
                    dom("<a href='/help/faq'>1</a><a href='/help/faq/'>2</a>"
                        + "<a href='/help/faq#top'>3</a><a href='/help/faq?utm_source=x'>4</a>"),
                    "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/faq");
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn -q test -Dtest=LinkExtractorTest
```
预期：编译失败，`LinkExtractor` 不存在。

- [ ] **Step 3: 实现 LinkExtractor**

创建 `backend/src/main/java/com/qingyu/care/service/web/LinkExtractor.java`：

```java
package com.qingyu.care.service.web;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 链接发现：从已解析的 DOM 抽出「同域 + 在范围内 + 已规范化」的链接。
 * 纯函数，不碰网络也不碰数据库。
 *
 * <p>注意 Jsoup 的 Document 与本项目实体 Document 同名，此处一律用全限定名区分
 * （同 {@link WebPageFetcher} 的既有写法）。
 *
 * @author ruan
 */
@Slf4j
public final class LinkExtractor {

    private LinkExtractor() {
    }

    /**
     * 纯 tracking 参数：去掉不改变页面内容。
     * 刻意不收 {@code ref}/{@code source}——部分站点用它们做真实的内容参数，
     * 误删会把两个不同页面折叠成一个。
     */
    private static final Set<String> TRACKING_PARAMS =
            Set.of("fbclid", "gclid", "msclkid", "yclid", "spm");

    /**
     * URL 规范化。非法、非 http(s)、无主机名一律返回 {@code null}，由调用方过滤。
     *
     * <p>不做规范化的话，{@code /faq}、{@code /faq/}、{@code /faq#top}、
     * {@code /faq?utm_source=x} 会被当成四页各抓一次各建一篇档，同一篇内容在知识库里
     * 重复四份、检索时互相挤占 top-K。
     */
    public static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host.toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(normalizePath(uri.getPath()));
        String query = cleanQuery(uri.getQuery());
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    /**
     * 路径末尾斜杠统一去掉，<b>根路径例外</b>——{@code https://x.com/} 若也去掉斜杠
     * 就退化成不带路径的裸 origin，与 {@code inScope} 的边界处理对不上。
     */
    private static String normalizePath(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /** 剔除 tracking 参数，其余按名排序（分页/语言参数是有意义的，必须保留）。 */
    private static String cleanQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return Arrays.stream(query.split("&"))
                .filter(pair -> !pair.isBlank())
                .filter(pair -> {
                    int eq = pair.indexOf('=');
                    String name = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase(Locale.ROOT);
                    return !name.startsWith("utm_") && !TRACKING_PARAMS.contains(name);
                })
                .sorted()
                .collect(Collectors.joining("&"));
    }

    /** 取 host（已小写），非法返回 null。 */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 范围判定。两个入参都必须是 {@link #normalize} 的输出。
     *
     * <p>⚠️ <b>不能写成裸 {@code startsWith}</b>，三个边界会同时出问题：
     * <ol>
     *   <li>规范化把 {@code https://x.com/help/} 变成 {@code https://x.com/help}，而
     *       用户配置的前缀常带末尾斜杠——裸 {@code startsWith(prefix)} 会把<b>入口页
     *       自己</b>判为范围外，爬取一开始就空转；</li>
     *   <li>反过来拿去掉斜杠的前缀做 {@code startsWith}，又会放进
     *       {@code https://x.com/helpdesk}（{@code /help} 是它的前缀），把隔壁目录
     *       整个爬进来；</li>
     *   <li>前缀本身是根路径（{@code https://x.com/}）时，简单地"补一个斜杠再比"
     *       会拼出 {@code https://x.com//}，一条都匹配不上。</li>
     * </ol>
     * 下面三个 return 分支各对应上面一个边界，改动前先读测试里同名的三条用例。
     */
    public static boolean inScope(String normalizedUrl, String normalizedPrefix) {
        if (normalizedUrl == null || normalizedPrefix == null) {
            return false;
        }
        String base = normalizedPrefix.endsWith("/") ? normalizedPrefix : normalizedPrefix + "/";
        String bareBase = base.substring(0, base.length() - 1);
        return normalizedUrl.equals(base)
                || normalizedUrl.equals(bareBase)
                || normalizedUrl.startsWith(base);
    }

    /**
     * 从 DOM 抽链接。{@code normalizedPrefix} 必须是 {@link #normalize} 的输出。
     *
     * <p>⚠️ 传进来的 DOM 必须是<b>剔噪之前</b>的那棵树：帮助中心的目录导航常在
     * {@code nav}/{@code header} 里，而 {@code WebPageFetcher.extractFrom} 会就地
     * 移除这些标签。详见 {@code WebPageFetcher.fetch} 里的 clone 说明。
     */
    public static Set<String> extract(org.jsoup.nodes.Document dom, String normalizedPrefix) {
        if (dom == null || normalizedPrefix == null) {
            return Set.of();
        }
        String prefixHost = hostOf(normalizedPrefix);
        if (prefixHost == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (org.jsoup.nodes.Element a : dom.select("a[href]")) {
            String abs = a.absUrl("href");
            String normalized = normalize(abs);
            if (normalized == null) {
                continue;
            }
            // 同域是硬约束：范围前缀已由服务层校验过同 host，这里再挡一次
            // 是为了防「站内相对链接解析出别的 host」这类边角情况
            if (!prefixHost.equals(hostOf(normalized))) {
                continue;
            }
            if (!inScope(normalized, normalizedPrefix)) {
                continue;
            }
            out.add(normalized);
        }
        return out;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn -q test -Dtest=LinkExtractorTest
```
预期：全部 PASS。

- [ ] **Step 5: 变异自证（三个边界各一次）**

把 `inScope` 的 `return` 临时改成裸 `return normalizedUrl.startsWith(normalizedPrefix);`，重跑 Step 4。**必须**看到「边界二：helpdesk 这类兄弟目录被排除」变红（`helpdesk` 会被裸 startsWith 放行）。

再把它临时改成 `return normalizedUrl.startsWith(base);`（只留第三个分支），重跑。**必须**看到「边界一：入口页自身在范围内」变红。

两次都确认后恢复原实现，重跑确认全绿。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/web/LinkExtractor.java \
        backend/src/test/java/com/qingyu/care/service/web/LinkExtractorTest.java
git commit -m "feat(backend): LinkExtractor——URL 规范化、范围判定与链接发现

规范化去 fragment/tracking 参数/末尾斜杠（根路径例外），其余参数按名排序保留。
不做规范化时 /faq、/faq/、/faq#top、/faq?utm_source=x 会被当成四页各建一篇档。

⚠️ inScope 的三个 return 分支各挡一个边界，不能写成裸 startsWith：
① 规范化去了斜杠而前缀带斜杠 → 入口页自己被判范围外，爬取一开始就空转
② 拿去斜杠的前缀做 startsWith → /helpdesk 被当成 /help 的子页整个爬进来
③ 前缀是根路径时补斜杠会拼出 // → 一条都匹配不上
三条边界各有一条同名测试，已用变异自证（改成裸 startsWith 后 ② 红、
只留第三分支后 ① 红）。"
```

---

## Task 3: WebPageFetcher 支持选择器与 DOM 回传 + 同步链路接上选择器

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/web/WebPageFetcher.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/web/WebPageSyncService.java`（`syncDocument` 内一处 fetch 调用）
- Test: `backend/src/test/java/com/qingyu/care/service/web/WebPageFetcherSelectorTest.java`

**Interfaces:**
- Consumes: 无（`LinkExtractor` 不在本任务用到）
- Produces:
  - `WebPageFetcher.FetchResult(String title, String content, String contentHash, int httpStatus, org.jsoup.nodes.Document dom)` —— **record 加了第 5 个组件 `dom`**
  - `WebPageFetcher.fetch(String url) : FetchResult`（保留，等价于 `fetch(url, null)`）
  - `WebPageFetcher.fetch(String url, String contentSelector) : FetchResult`
  - `WebPageFetcher.downloadRaw(String url) : Downloaded` —— 由 `private download` 提升为 **public**，供 `RobotsChecker`（Task 4）取 robots.txt
  - `WebPageFetcher.extractBySelector(org.jsoup.nodes.Document doc, String selector) : String`（包级 static，免容器单测）

**背景（实现者必读）：** 现有 `WebPageFetcher.fetch` 的流程是「`download` → `parseHtml` → `extractTitle` → `extractFrom`」，其中 `extractFrom` 会**就地修改** DOM 树（`doc.select(NOISE_SELECTOR).remove()`，移除 `script, style, nav, header, footer, aside, noscript, iframe, form`）。既有注释已写明「标题必须在剔噪声之前取」，本任务**不改动** `extractFrom` / `extractTitle` / `parseHtml` / `download` 的既有逻辑。

⚠️ **`FetchResult.dom` 必须是剔噪之前 `clone()` 出来的副本。** 若把原树对象直接放进 record，调用方拿到的已是被剔掉 `nav`/`header`/`footer` 的残树——而帮助中心的目录导航恰恰常在 `nav` 里，链接发现会几乎抓不到东西且**不报错**，表现为「爬了 1 页就结束」。代价是多一份 DOM 副本的内存；5MB 响应体上限下 DOM 峰值十几 MB、克隆再翻倍，而爬取是串行的（同一时刻只有一份），可接受。

- [ ] **Step 1: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/web/WebPageFetcherSelectorTest.java`：

```java
package com.qingyu.care.service.web;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebPageFetcher} 的选择器分支与「剔噪前 DOM 副本」单测。
 * 全部打在包级 static 方法上，不发网络请求。
 *
 * @author ruan
 */
class WebPageFetcherSelectorTest {

    private static final String PAGE = """
            <html><head><title>帮助中心</title></head><body>
              <nav><a href="/help/faq">常见问题</a><a href="/help/contact">联系我们</a>导航噪声</nav>
              <div class="doc-content"><p>这是正文内容。</p></div>
              <footer>页脚版权噪声</footer>
            </body></html>
            """;

    @Test
    @DisplayName("配了选择器：只取容器内文本，导航与页脚都不在")
    void selector_takes_only_container_text() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");

        String content = WebPageFetcher.extractBySelector(doc, ".doc-content");

        assertThat(content).contains("这是正文内容");
        assertThat(content).doesNotContain("导航噪声").doesNotContain("页脚版权噪声");
    }

    @Test
    @DisplayName("选择器选不中任何元素：返回空串，由调用方回退启发式")
    void selector_matching_nothing_returns_blank() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");

        assertThat(WebPageFetcher.extractBySelector(doc, ".not-exist")).isBlank();
    }

    @Test
    @DisplayName("选择器语法非法：返回空串而不是抛异常——写错选择器不该让整站抓取全线失败")
    void invalid_selector_returns_blank_instead_of_throwing() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");

        assertThat(WebPageFetcher.extractBySelector(doc, "div[")).isBlank();
    }

    @Test
    @DisplayName("多个匹配元素取并集")
    void multiple_matches_are_joined() {
        org.jsoup.nodes.Document doc = Jsoup.parse(
                "<html><body><p class='c'>一</p><p class='c'>二</p></body></html>", "https://x.com/");

        String content = WebPageFetcher.extractBySelector(doc, ".c");

        assertThat(content).contains("一").contains("二");
    }

    @Test
    @DisplayName("剔噪会就地改树——这是 dom 必须 clone 的原因，此处固定住该前提")
    void extractFrom_mutates_the_tree_in_place() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");
        assertThat(doc.select("nav a")).isNotEmpty();

        WebPageFetcher.extractFrom(doc);

        // nav 整个被移除，导航里的链接一起消失——若把这棵树交给 LinkExtractor，一条链接都提不到
        assertThat(doc.select("nav a")).isEmpty();
    }

    @Test
    @DisplayName("剔噪前 clone 的副本保留 nav 里的链接")
    void cloned_dom_keeps_nav_links_after_noise_removal() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");
        org.jsoup.nodes.Document snapshot = doc.clone();

        WebPageFetcher.extractFrom(doc);

        assertThat(doc.select("nav a")).isEmpty();
        assertThat(snapshot.select("nav a")).hasSize(2);
        assertThat(snapshot.select("nav a").first().absUrl("href")).isEqualTo("https://x.com/help/faq");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn -q test -Dtest=WebPageFetcherSelectorTest
```
预期：编译失败，`extractBySelector` 不存在。

- [ ] **Step 3: 改 WebPageFetcher**

在 `WebPageFetcher.java` 里做四处改动：

**(a) `FetchResult` record 加 `dom` 组件**，替换原来那一行：

```java
    /**
     * 抓取结果。{@code dom} 是<b>剔噪之前</b>的 DOM 副本，供 {@link LinkExtractor} 抽链接。
     *
     * <p>⚠️ 必须是副本而不是原树引用：{@link #extractFrom} 会就地移除
     * {@code nav}/{@code header}/{@code footer}，而帮助中心的目录导航恰恰常在这些标签里。
     * 交出残树的后果是链接发现几乎抓不到东西，且<b>不报错</b>——表现为「爬了 1 页就结束」。
     */
    public record FetchResult(String title, String content, String contentHash,
                              int httpStatus, org.jsoup.nodes.Document dom) {}
```

**(b) `fetch` 拆成两个重载**，替换原有的 `public FetchResult fetch(String url)` 整个方法：

```java
    /** 单页抓取，走启发式噪声剔除（等价于 {@code fetch(url, null)}）。 */
    public FetchResult fetch(String url) {
        return fetch(url, null);
    }

    /**
     * 单页抓取。{@code contentSelector} 非空时只取该选择器命中的容器内文本；
     * 为空、选不中或语法非法时回退启发式剔噪（行为与不传选择器完全一致）。
     *
     * <p>⚠️ 三步的顺序不能变，且 {@code domForLinks} 必须在剔噪之前克隆：
     * {@link #extractFrom} 会就地改树，标题与链接都得赶在它之前拿到。把顺序陷阱
     * 收在本方法内部，调用方无从写错。
     */
    public FetchResult fetch(String url, String contentSelector) {
        Downloaded downloaded = downloadRaw(url);
        org.jsoup.nodes.Document parsed = parseHtml(downloaded.body(), downloaded.charsetName(), url);

        // 剔噪之前留一份副本给链接发现（extractFrom 会就地移除 nav/header/footer）
        org.jsoup.nodes.Document domForLinks = parsed.clone();
        // 标题必须在剔噪声之前取：extractFrom 会就地改这棵树
        String title = extractTitle(parsed, url);

        String content = null;
        if (contentSelector != null && !contentSelector.isBlank()) {
            content = extractBySelector(parsed, contentSelector);
            if (content.isBlank()) {
                log.warn("正文选择器未命中任何元素，回退启发式剔噪 selector={} url={}", contentSelector, url);
                content = null;
            }
        }
        if (content == null) {
            content = extractFrom(parsed);
        }
        if (content.isBlank()) {
            throw new BusinessException("网页正文为空，可能是需要 JS 渲染的页面: " + url);
        }
        return new FetchResult(title, content, sha256(content), downloaded.status(), domForLinks);
    }

    /**
     * 按 CSS 选择器取正文（白名单语义）：命中元素的文本按序拼接。
     *
     * <p>选不中或选择器语法非法都返回空串而<b>不抛异常</b>——运营写错一个选择器不该
     * 让整站抓取全线失败，回退启发式并在日志留痕是更可用的行为。
     * 包级 static 免容器单测（同本类其余提取方法的惯例）。
     */
    static String extractBySelector(org.jsoup.nodes.Document doc, String selector) {
        try {
            org.jsoup.select.Elements matched = doc.select(selector);
            if (matched.isEmpty()) {
                return "";
            }
            return matched.stream()
                    .map(org.jsoup.nodes.Element::text)
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (RuntimeException e) {
            log.warn("正文选择器语法非法，将回退启发式剔噪 selector={}: {}", selector, e.getMessage());
            return "";
        }
    }
```

**(c) `download` 改名 `downloadRaw` 并提升为 public**：把原来的

```java
    /** 安全下载：SSRF 钉 IP + 禁重定向 + 超时 + 限量 + Content-Type 白名单。 */
    private Downloaded download(String url) {
```

改成

```java
    /**
     * 安全下载：SSRF 钉 IP + 禁重定向 + 超时 + 限量 + Content-Type 白名单。
     *
     * <p>public 是为了让 {@link RobotsChecker} 复用这一整套出站防线取 robots.txt——
     * 新开一条抓取路径就等于新开一个 SSRF 缺口，robots.txt 也必须走这里。
     */
    public Downloaded downloadRaw(String url) {
```

**(d) `Downloaded` record 由包级提升为 public**（`RobotsChecker` 同包，其实包级就够；但 `downloadRaw` 是 public 方法、返回类型必须至少同等可见，否则跨包调用方无法声明变量）：

```java
    public record Downloaded(byte[] body, String charsetName, int status) {}
```

保留它原有的 Javadoc 不动。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn -q test -Dtest=WebPageFetcherSelectorTest
```
预期：6 个用例 PASS。

- [ ] **Step 5: 修既有测试的编译错误**

`FetchResult` 多了一个组件，既有测试里所有 `new FetchResult(...)` 的四参构造都会编译失败。定位并修：

```bash
cd backend && grep -rn "new WebPageFetcher.FetchResult\|new FetchResult" src/test/java/
```

逐处在末尾补一个 DOM 参数。**桩数据一律用真实的空 DOM 而不是 `null`**：

```java
new WebPageFetcher.FetchResult("标题", "正文", "hash", 200, org.jsoup.Jsoup.parse("<html></html>"))
```

传 `null` 会让「爬取时对 dom 的使用」在这些测试里永远走不到，掩盖 NPE。

然后跑第一期的两个相关测试类确认无回归：

```bash
cd backend && mvn -q test -Dtest='WebPageFetcherTest,WebPageFetcherDownloadTest,WebPageSyncServiceTest'
```

- [ ] **Step 6: 让定期同步用上所属站点的选择器**

在 `WebPageSyncService.syncDocument` 里，把

```java
            WebPageFetcher.FetchResult result = webPageFetcher.fetch(doc.getSourceUrl());
```

替换为

```java
            // 选择器取自所属爬取站点（单页手动添加的文档 crawlSite 为 null，传 null 即走启发式，
            // 行为与第一期完全一致）。刻意读站点行而不冗余到文档上——改了站点选择器之后，
            // 已建文档的下一轮同步立刻用新值，不需要回填 N 行
            String selector = doc.getCrawlSite() == null ? null : doc.getCrawlSite().getContentSelector();
            WebPageFetcher.FetchResult result = webPageFetcher.fetch(doc.getSourceUrl(), selector);
```

⚠️ `doc.getCrawlSite()` 是 `LAZY` 关联，`syncDocument` 自带 `@Transactional`，此处在事务内，懒加载安全。

- [ ] **Step 7: 给 WebPageSyncServiceTest 补一条选择器传递的测试**

在 `backend/src/test/java/com/qingyu/care/service/web/WebPageSyncServiceTest.java` 里加：

```java
    @Test
    @DisplayName("同步时把所属站点的正文选择器传给抓取器；无站点则传 null")
    void passes_content_selector_from_crawl_site() {
        CrawlSite site = CrawlSite.builder().id(9L).contentSelector(".doc-content").build();
        Document doc = webDoc(1L, "https://x.com/help/faq");   // 复用本类既有的建桩辅助方法
        doc.setCrawlSite(site);
        when(documentRepository.tryLockDocumentProcessing(1L)).thenReturn(true);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString(), any()))
                .thenReturn(new WebPageFetcher.FetchResult("标题", "正文", "hash", 200,
                        org.jsoup.Jsoup.parse("<html></html>")));

        service.syncDocument(1L);

        verify(webPageFetcher).fetch("https://x.com/help/faq", ".doc-content");
    }
```

⚠️ **断言必须用 `verify(webPageFetcher).fetch(url, ".doc-content")` 这种带实参的形式**，不能写成 `verify(webPageFetcher).fetch(anyString(), any())` —— 后者对「选择器传成了 null」照样绿，等于没有守护。

若该测试类里没有现成的 `webDoc(...)` 辅助方法，就地内联构造一个：

```java
        Document doc = Document.builder()
                .id(1L).sourceUrl("https://x.com/help/faq")
                .documentType(DocumentType.URL).status(DocumentStatus.PENDING)
                .build();
```

- [ ] **Step 8: 跑相关测试**

```bash
cd backend && mvn -q test -Dtest='WebPageFetcherSelectorTest,WebPageSyncServiceTest'
```
预期：全绿。

- [ ] **Step 9: 变异自证（两条）**

其一：把 `fetch(String url, String contentSelector)` 里的 `parsed.clone()` 改成 `parsed`，重跑 Step 4 —— **必须**看到 `cloned_dom_keeps_nav_links_after_noise_removal` 变红。改回。

其二：把 Step 6 的 `selector` 表达式硬编码成 `null`，重跑 Step 8 —— **必须**看到 `passes_content_selector_from_crawl_site` 变红。改回。

- [ ] **Step 10: 全量单测 + 提交**

```bash
cd backend && mvn -q clean test
```
预期：BUILD SUCCESS。

```bash
git add backend/src/main/java/com/qingyu/care/service/web/WebPageFetcher.java \
        backend/src/main/java/com/qingyu/care/service/web/WebPageSyncService.java \
        backend/src/test/java/com/qingyu/care/service/web/
git commit -m "feat(backend): WebPageFetcher 支持正文选择器并回传剔噪前 DOM 副本

- fetch 拆两个重载，原签名保留等价于传 null；选择器选不中或语法非法都回退
  启发式并留日志（运营写错一个选择器不该让整站抓取全线失败）
- FetchResult 加 dom 组件，⚠️必须是剔噪前 clone 的副本：extractFrom 就地移除
  nav/header/footer，而帮助中心目录导航恰在 nav 里，交出残树会让链接发现
  几乎抓不到东西且不报错，表现为「爬了 1 页就结束」。顺序陷阱收在 fetch 内部
- download 提升为 public downloadRaw 供 RobotsChecker 复用同一套出站防线
  （新开一条抓取路径就等于新开一个 SSRF 缺口）
- syncDocument 读所属站点的选择器，改了选择器后已建文档下一轮同步立刻生效

变异自证：clone 去掉后 DOM 副本测试红；选择器硬编码 null 后传递测试红。"
```

---

## Task 4: RobotsChecker（robots.txt 抓取与解析）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/web/RobotsChecker.java`
- Test: `backend/src/test/java/com/qingyu/care/service/web/RobotsCheckerTest.java`

**Interfaces:**
- Consumes: `WebPageFetcher.downloadRaw(String) : WebPageFetcher.Downloaded`（Task 3 产出）
- Produces:
  - `RobotsChecker.RobotsRules` record：`(List<String> disallowedPaths, Long crawlDelayMs)`，带 `boolean allows(String url)` 与常量 `RobotsRules.ALLOW_ALL`
  - `RobotsChecker.parse(String body) : RobotsRules`（包级 static，免网络单测）
  - `RobotsChecker.fetch(String origin, WebPageFetcher fetcher) : RobotsRules`

**背景与硬性要求：**
- 只解析 `User-agent: *` 段 —— 本项目不声明自定义 UA，通用段即适用段
- `Disallow: /path` 按**路径前缀**匹配；`Disallow:`（空值）在 robots 规范里表示**允许全部**，必须跳过不收
- `Crawl-delay: N` 单位是秒，可以是小数（`0.5`）
- ⚠️ **取不到 robots.txt（404 / 超时 / 解析失败）一律按「允许」处理。** 绝大多数站点没有这个文件，fail-closed 会让功能对多数站点直接不可用。注意 `downloadRaw` 对非 2xx 会抛 `BusinessException`（第一期的 `requireSuccessStatus`），所以 404 走的是 catch 分支

- [ ] **Step 1: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/web/RobotsCheckerTest.java`：

```java
package com.qingyu.care.service.web;

import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link RobotsChecker} 单测。解析部分纯字符串、不发网络；抓取部分 mock
 * {@link WebPageFetcher#downloadRaw}。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class RobotsCheckerTest {

    @Mock
    private WebPageFetcher webPageFetcher;

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("收 User-agent:* 段的 Disallow，按路径前缀拦")
        void collects_disallow_of_star_section() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    User-agent: *
                    Disallow: /admin
                    Disallow: /private/
                    """);

            assertThat(rules.allows("https://x.com/admin/users")).isFalse();
            assertThat(rules.allows("https://x.com/private/a")).isFalse();
            assertThat(rules.allows("https://x.com/help/faq")).isTrue();
        }

        @Test
        @DisplayName("只认 * 段：其它 UA 段的 Disallow 不生效")
        void ignores_other_user_agent_sections() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    User-agent: Googlebot
                    Disallow: /help

                    User-agent: *
                    Disallow: /admin
                    """);

            assertThat(rules.allows("https://x.com/help/faq")).isTrue();
            assertThat(rules.allows("https://x.com/admin")).isFalse();
        }

        @Test
        @DisplayName("Disallow 空值表示允许全部，不能被当成前缀 \\"\\" 拦下一切")
        void empty_disallow_means_allow_all() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    User-agent: *
                    Disallow:
                    """);

            assertThat(rules.disallowedPaths()).isEmpty();
            assertThat(rules.allows("https://x.com/anything")).isTrue();
        }

        @Test
        @DisplayName("解析 Crawl-delay，支持小数秒")
        void parses_crawl_delay_including_fractional() {
            assertThat(RobotsChecker.parse("User-agent: *\nCrawl-delay: 2").crawlDelayMs()).isEqualTo(2000L);
            assertThat(RobotsChecker.parse("User-agent: *\nCrawl-delay: 0.5").crawlDelayMs()).isEqualTo(500L);
            assertThat(RobotsChecker.parse("User-agent: *\nCrawl-delay: abc").crawlDelayMs()).isNull();
            assertThat(RobotsChecker.parse("User-agent: *\nDisallow: /a").crawlDelayMs()).isNull();
        }

        @Test
        @DisplayName("注释与空行被忽略，key 大小写不敏感")
        void ignores_comments_and_is_case_insensitive() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    # 这是注释
                    USER-AGENT: *

                    DISALLOW: /admin   # 行尾注释
                    """);

            assertThat(rules.allows("https://x.com/admin")).isFalse();
        }

        @Test
        @DisplayName("空文件或全是无关内容 → 允许全部")
        void blank_body_allows_all() {
            assertThat(RobotsChecker.parse("").allows("https://x.com/a")).isTrue();
            assertThat(RobotsChecker.parse("Sitemap: https://x.com/sitemap.xml").allows("https://x.com/a")).isTrue();
        }

        @Test
        @DisplayName("allows 只比路径，查询串不影响判定")
        void allows_compares_path_only() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("User-agent: *\nDisallow: /admin");

            assertThat(rules.allows("https://x.com/admin?page=2")).isFalse();
            assertThat(rules.allows("https://x.com/help?admin=1")).isTrue();
        }
    }

    @Nested
    @DisplayName("fetch")
    class Fetch {

        @Test
        @DisplayName("正常取到并解析")
        void fetches_and_parses() {
            byte[] body = "User-agent: *\nDisallow: /admin\n".getBytes(StandardCharsets.UTF_8);
            when(webPageFetcher.downloadRaw("https://x.com/robots.txt"))
                    .thenReturn(new WebPageFetcher.Downloaded(body, "UTF-8", 200));

            RobotsChecker.RobotsRules rules = RobotsChecker.fetch("https://x.com", webPageFetcher);

            assertThat(rules.allows("https://x.com/admin")).isFalse();
        }

        @Test
        @DisplayName("robots.txt 取不到（404/超时）按允许全部处理——多数站点没有这个文件")
        void missing_robots_allows_all() {
            when(webPageFetcher.downloadRaw(anyString()))
                    .thenThrow(new BusinessException("网页返回 HTTP 404"));

            RobotsChecker.RobotsRules rules = RobotsChecker.fetch("https://x.com", webPageFetcher);

            assertThat(rules.allows("https://x.com/anything")).isTrue();
            assertThat(rules.disallowedPaths()).isEmpty();
        }

        @Test
        @DisplayName("charsetName 为 null 时按 UTF-8 解码，不抛异常")
        void tolerates_null_charset() {
            byte[] body = "User-agent: *\nDisallow: /admin\n".getBytes(StandardCharsets.UTF_8);
            when(webPageFetcher.downloadRaw(anyString()))
                    .thenReturn(new WebPageFetcher.Downloaded(body, null, 200));

            assertThat(RobotsChecker.fetch("https://x.com", webPageFetcher)
                    .allows("https://x.com/admin")).isFalse();
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn -q test -Dtest=RobotsCheckerTest
```
预期：编译失败，`RobotsChecker` 不存在。

- [ ] **Step 3: 实现 RobotsChecker**

创建 `backend/src/main/java/com/qingyu/care/service/web/RobotsChecker.java`：

```java
package com.qingyu.care.service.web;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * robots.txt 抓取与解析。爬取开始时取一次，规则在本轮爬取内复用。
 *
 * <p>只解析 {@code User-agent: *} 段——本项目不声明自定义 UA，通用段即适用段。
 *
 * <p>取 robots.txt 走 {@link WebPageFetcher#downloadRaw}，复用它那一整套出站防线
 * （SSRF 钉 IP 防 rebinding、硬关重定向、超时、5MB 上限、Content-Type 白名单——
 * {@code text/plain} 本来就在白名单里）。新开一条抓取路径就等于新开一个 SSRF 缺口。
 *
 * @author ruan
 */
@Slf4j
public final class RobotsChecker {

    private RobotsChecker() {
    }

    /**
     * 解析后的规则。{@code crawlDelayMs} 为 null 表示站点未声明，由调用方用默认限速。
     */
    public record RobotsRules(List<String> disallowedPaths, Long crawlDelayMs) {

        /** 无限制。robots.txt 缺失或解析失败时用它——见 {@link RobotsChecker#fetch}。 */
        public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), null);

        /**
         * 该 URL 是否允许抓取。按<b>路径</b>前缀匹配，查询串不参与判定。
         */
        public boolean allows(String url) {
            if (disallowedPaths.isEmpty()) {
                return true;
            }
            String path = pathOf(url);
            return disallowedPaths.stream().noneMatch(path::startsWith);
        }

        private static String pathOf(String url) {
            try {
                String path = new URI(url).getPath();
                return path == null || path.isEmpty() ? "/" : path;
            } catch (URISyntaxException e) {
                // 解析不出路径的 URL 本来也进不了爬取队列（LinkExtractor.normalize 会拒），
                // 这里返回根路径是保守侧：只要站点 Disallow 了 "/" 就一并拦下
                return "/";
            }
        }
    }

    /**
     * 取并解析 {@code {origin}/robots.txt}。
     *
     * <p>⚠️ <b>取不到一律按「允许全部」</b>：绝大多数站点没有这个文件，
     * {@code downloadRaw} 对 404 会抛 {@code BusinessException}（第一期的
     * {@code requireSuccessStatus}），fail-closed 会让本功能对多数站点直接不可用。
     */
    public static RobotsRules fetch(String origin, WebPageFetcher fetcher) {
        String url = origin.endsWith("/") ? origin + "robots.txt" : origin + "/robots.txt";
        try {
            WebPageFetcher.Downloaded downloaded = fetcher.downloadRaw(url);
            Charset charset = downloaded.charsetName() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(downloaded.charsetName());
            RobotsRules rules = parse(new String(downloaded.body(), charset));
            log.debug("robots.txt 已解析 url={} disallow={} crawlDelayMs={}",
                    url, rules.disallowedPaths().size(), rules.crawlDelayMs());
            return rules;
        } catch (Exception e) {
            log.debug("robots.txt 取不到或解析失败，按允许全部处理 url={}: {}", url, e.getMessage());
            return RobotsRules.ALLOW_ALL;
        }
    }

    /** 解析 robots.txt 正文。包级 static 免网络单测。 */
    static RobotsRules parse(String body) {
        List<String> disallowed = new ArrayList<>();
        Long crawlDelayMs = null;
        boolean inStarSection = false;

        for (String rawLine : body.split("\\R")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            switch (key) {
                case "user-agent" -> inStarSection = "*".equals(value);
                case "disallow" -> {
                    // Disallow 空值在 robots 规范里表示「允许全部」，不能收成前缀 "" 拦下一切
                    if (inStarSection && !value.isEmpty()) {
                        disallowed.add(value);
                    }
                }
                case "crawl-delay" -> {
                    if (inStarSection) {
                        Long parsed = parseDelayMs(value);
                        if (parsed != null) {
                            crawlDelayMs = parsed;
                        }
                    }
                }
                default -> {
                    // Sitemap / Allow / Host 等本期不处理
                }
            }
        }
        return new RobotsRules(List.copyOf(disallowed), crawlDelayMs);
    }

    /** {@code Crawl-delay} 单位是秒，可为小数；非法值返回 null（当作未声明）。 */
    private static Long parseDelayMs(String value) {
        try {
            double seconds = Double.parseDouble(value);
            return seconds <= 0 ? null : (long) (seconds * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn -q test -Dtest=RobotsCheckerTest
```
预期：全部 PASS。

- [ ] **Step 5: 变异自证（两条）**

其一：把 `case "disallow"` 分支里的 `&& !value.isEmpty()` 删掉，重跑 —— **必须**看到 `empty_disallow_means_allow_all` 变红（空前缀会拦下一切）。改回。

其二：把 `fetch` 的 catch 分支从 `return RobotsRules.ALLOW_ALL` 改成 `throw new RuntimeException(e)`，重跑 —— **必须**看到 `missing_robots_allows_all` 变红。改回。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/web/RobotsChecker.java \
        backend/src/test/java/com/qingyu/care/service/web/RobotsCheckerTest.java
git commit -m "feat(backend): RobotsChecker——robots.txt 抓取与解析

只解析 User-agent:* 段（本项目不声明自定义 UA）；Disallow 按路径前缀匹配、
查询串不参与；Crawl-delay 支持小数秒。取 robots.txt 复用 downloadRaw 的整套
出站防线（SSRF 钉 IP/禁重定向/超时/5MB/白名单），不新开抓取路径。

⚠️ 两处 fail-open 是有意的：
- Disallow 空值在规范里表示「允许全部」，收成前缀 \"\" 会拦下一切
- 取不到 robots.txt（404/超时）按允许处理——多数站点没这个文件，
  fail-closed 会让功能对多数站点直接不可用

两条都做了变异自证。"
```

---

## Task 5: SiteCrawlService（BFS 编排 + 内存进度 + 站点级互斥）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/web/SiteCrawlService.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/CrawlSiteRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/CrawlStartResponse.java`
- Test: `backend/src/test/java/com/qingyu/care/service/web/SiteCrawlServiceTest.java`

**Interfaces:**
- Consumes:
  - `LinkExtractor.normalize(String)` / `inScope(...)` / `extract(dom, prefix)` / `hostOf(String)`（Task 2）
  - `WebPageFetcher.fetch(String url, String contentSelector) : FetchResult`（含 `dom()`，Task 3）
  - `RobotsChecker.fetch(String origin, WebPageFetcher) : RobotsRules`，`RobotsRules.allows(String)` / `crawlDelayMs()`（Task 4）
  - `CrawlSiteRepository`、`DocumentRepository.findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc`（Task 1）
  - `SiteCrawlProperties`（Task 1）
  - 既有：`KnowledgeService.getKnowledgeBase(Long)`、`KnowledgeService.processDocument(Long)`、`WebPageSyncService.syncDocument(Long)`、`WebPageSyncService.truncate(String,int)`（包级 static，同包可调）、`TenantContext`、`TenantOwnershipGuard.mutableInCurrentContext(Long,Long)`、`BusinessException`
- Produces:
  - `SiteCrawlService.Progress`（public static final class）字段：`jobId`、`siteId`、`crawled`、`created`、`updated`、`failed`、`maxPages`、`status`、`errorMessage`、`startedAt`、`finishedAt`
  - `startCrawl(Long knowledgeBaseId, CrawlSiteRequest req) : CrawlStartResponse`
  - `recrawl(Long siteId) : String`（返回 jobId）
  - `getProgress(String jobId) : Progress`
  - `listSites(Long knowledgeBaseId) : List<CrawlSite>`
  - `updateSite(Long siteId, CrawlSiteRequest req) : CrawlSite`
  - `deleteSite(Long siteId, boolean deleteDocuments) : void`
  - `runCrawl(Progress, Long siteId, Long tenantId, Long appId, Long userId, DocumentVisibility) : void`（`@Async`，public 供代理）
  - 包级 static：`deriveScopePrefix(String entryUrl)`、`originOf(String url)`

**背景（实现者必读，这几条都是踩过的坑）：**

1. ⚠️ **`@Async` 线程没有 `TenantContext`。** 第一期 `addWebPage` 是在**请求线程**建档、只把抓取扔进 `@Async`，所以租户字段正常回填。而本任务要在 `@Async` 线程里建 N 篇文档，`TenantEntityListener` 从 `TenantContext` 回填时拿到的是 null。做法：请求线程捕获 `tenantId`/`appId`/`userId`/`accessLevel` **四个值**当参数传进 `runCrawl`，方法开头搬进 `TenantContext`，**`finally` 必须 `TenantContext.clear()`** —— `AsyncConfig` 用的是 Spring Boot 默认 `applicationTaskExecutor`（core 8、**线程复用**），不清理就是把上下文泄漏给下一个复用该线程的任务（流式聊天改造时实测复现过平台管理员会话落进别人租户）。

2. ⚠️ **`self` 自注入不可省。** `@Async` 同类直调不经 Spring 代理会完全失效（变成同步阻塞请求线程）。范式：构造器注入其余依赖 + `@Autowired @Lazy public void setSelf(SiteCrawlService self)`，照 `ReembedService` / `WebPageSyncService` 写。

3. ⚠️ **`@Async` 投递失败必须回滚互斥。** `crawlingSites.add(siteId)` 之后若 `self.runCrawl(...)` 抛异常（池拒绝），互斥不释放会让该站点**永久无法再爬**。

4. ⚠️ **摄取成功才落 `contentHash`。** 第一期用一个 Critical 缺陷换来的：写早了会让下一轮同步误判「内容未变」而永久跳过重试，该文档 0 向量、永远检索不到、且不再有任何自动重试，**全程无异常无日志**。本任务的建档路径是新代码，不会自动继承这个教训。

5. ⚠️ **新建文档必须设 `lastSyncedAt`。** 不设的话 `findDueWebPageIds` 会因 `last_synced_at IS NULL` 立刻判为到期，爬完 50 篇后下一轮调度会把它们**全部重抓一遍**。只在摄取成功的分支设——失败的留 null 正是为了让调度器尽快重试。

6. **不加 `(kb_id, source_url)` 唯一约束**：第一期 `addWebPage` 没有去重校验，存量库可能已有重复 URL，`ddl-auto: update` 建唯一索引会直接让应用起不来。用「建档前查一下就跳过」+ 内存互斥。

- [ ] **Step 1: 建两个 DTO**

创建 `backend/src/main/java/com/qingyu/care/dto/CrawlSiteRequest.java`：

```java
package com.qingyu.care.dto;

import com.qingyu.care.enums.SyncInterval;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 建站点 / 改站点配置的请求体。
 *
 * <p>{@code maxPages}/{@code maxDepth} 的 {@code @Max} 只是给用户的即时反馈，
 * <b>真正的硬顶由服务端按 {@code SiteCrawlProperties} 钳制</b>——注解里的字面量
 * 无法读配置，改配置就会与它不一致，所以不能只靠它。
 *
 * @author ruan
 */
@Data
public class CrawlSiteRequest {

    /** 入口 URL。校验与 {@code AddWebPageRequest.url} 完全一致（同一列宽、同一协议白名单）。 */
    @NotBlank(message = "入口 URL 不能为空")
    @Size(max = 2048, message = "网址长度不能超过 2048 个字符")
    @Pattern(regexp = "^https?://.+", message = "只支持 http/https 开头的网址")
    private String entryUrl;

    /** 范围前缀；不传则由入口 URL 推导。host 必须与入口一致（服务端强制校验）。 */
    @Size(max = 2048, message = "范围前缀长度不能超过 2048 个字符")
    private String scopePrefix;

    /** 页数上限；不传用默认 50。 */
    @Min(value = 1, message = "页数上限至少为 1")
    @Max(value = 500, message = "页数上限不能超过 500")
    private Integer maxPages;

    /** 深度上限；不传用默认 3。入口页为深度 0。 */
    @Min(value = 0, message = "深度不能为负")
    @Max(value = 5, message = "深度不能超过 5")
    private Integer maxDepth;

    /** 正文 CSS 选择器；空则走启发式剔噪。 */
    @Size(max = 500, message = "选择器长度不能超过 500 个字符")
    private String contentSelector;

    /** 爬出的每篇文档的同步周期；不传按 MANUAL 处理。 */
    private SyncInterval syncInterval;
}
```

创建 `backend/src/main/java/com/qingyu/care/dto/CrawlStartResponse.java`：

```java
package com.qingyu.care.dto;

/**
 * 启动爬取的响应：站点 id + 用于轮询进度的任务 id。
 *
 * @author ruan
 */
public record CrawlStartResponse(Long siteId, String jobId) {
}
```

- [ ] **Step 2: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/web/SiteCrawlServiceTest.java`：

```java
package com.qingyu.care.service.web;

import com.qingyu.care.config.SiteCrawlProperties;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.CrawlSiteRequest;
import com.qingyu.care.entity.CrawlSite;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.CrawlSiteRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.KnowledgeService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SiteCrawlService} 单测。直接调 {@code runCrawl}（不经 @Async 代理），
 * 断言 BFS 行为、上限、互斥与租户上下文搬运。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiteCrawlServiceTest {

    @Mock private CrawlSiteRepository crawlSiteRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private KnowledgeService knowledgeService;
    @Mock private WebPageSyncService webPageSyncService;
    @Mock private WebPageFetcher webPageFetcher;

    private SiteCrawlService service;
    private SiteCrawlProperties props;

    /** 记录每次 fetch 的 URL，用于断言"抓了哪些页、抓了几页"。 */
    private final List<String> fetched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        props = new SiteCrawlProperties();
        props.setDefaultDelayMs(0);          // 单测不真的睡
        service = new SiteCrawlService(crawlSiteRepository, documentRepository,
                knowledgeService, webPageSyncService, webPageFetcher, props);
        service.setSelf(service);            // 单测里 self 就是自身
        fetched.clear();
        // robots.txt 取不到 → 允许全部（RobotsChecker 的 fail-open 分支）
        when(webPageFetcher.downloadRaw(anyString())).thenThrow(new BusinessException("404"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ===== 建桩辅助 =====

    private CrawlSite site(int maxPages, int maxDepth) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(100L);
        CrawlSite s = CrawlSite.builder()
                .id(9L).knowledgeBase(kb)
                .entryUrl("https://x.com/help/").scopePrefix("https://x.com/help/")
                .maxPages(maxPages).maxDepth(maxDepth)
                .syncInterval(SyncInterval.DAILY)
                .build();
        when(crawlSiteRepository.findById(9L)).thenReturn(Optional.of(s));
        when(crawlSiteRepository.save(any(CrawlSite.class))).thenAnswer(inv -> inv.getArgument(0));
        return s;
    }

    /** 让 fetch(url, ...) 返回一个「正文 + 指定链接」的页面，并记录被抓的 URL。 */
    private void stubPage(String url, String... hrefs) {
        StringBuilder html = new StringBuilder("<html><head><title>T</title></head><body><p>正文</p>");
        for (String href : hrefs) {
            html.append("<a href='").append(href).append("'>l</a>");
        }
        html.append("</body></html>");
        org.jsoup.nodes.Document dom = Jsoup.parse(html.toString(), url);
        when(webPageFetcher.fetch(eq(url), any())).thenAnswer(inv -> {
            fetched.add(url);
            return new WebPageFetcher.FetchResult("T", "正文", "hash-" + url, 200, dom);
        });
    }

    /** 建档后 processDocument 把状态改成 COMPLETED（模拟摄取成功）。 */
    private void stubIngestSucceeds() {
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(System.nanoTime());
            }
            return d;
        });
        when(documentRepository.findById(anyLong())).thenAnswer(inv -> {
            Document d = Document.builder().id(inv.getArgument(0)).status(DocumentStatus.COMPLETED).build();
            return Optional.of(d);
        });
        when(documentRepository.findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
    }

    private SiteCrawlService.Progress newProgress(CrawlSite s) {
        return service.newProgressForTest("job-1", s.getId(), s.getMaxPages());
    }

    // ===== BFS 与上限 =====

    @Test
    @DisplayName("按深度截断：maxDepth=1 时只抓入口与其直接子页")
    void stops_at_max_depth() {
        CrawlSite s = site(50, 1);
        stubIngestSucceeds();
        stubPage("https://x.com/help", "/help/a");
        stubPage("https://x.com/help/a", "/help/a/deep");
        stubPage("https://x.com/help/a/deep");

        service.runCrawl(newProgress(s), 9L, 1L, 2L, 3L, null);

        assertThat(fetched).containsExactly("https://x.com/help", "https://x.com/help/a");
    }

    @Test
    @DisplayName("maxPages 是「已尝试抓取」的上限，达到即停")
    void stops_at_max_pages() {
        CrawlSite s = site(2, 5);
        stubIngestSucceeds();
        stubPage("https://x.com/help", "/help/a", "/help/b", "/help/c");
        stubPage("https://x.com/help/a");
        stubPage("https://x.com/help/b");
        stubPage("https://x.com/help/c");

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        assertThat(fetched).hasSize(2);
        assertThat(p.crawled).isEqualTo(2);
    }

    @Test
    @DisplayName("被 robots 拒绝的页不计入 maxPages——它根本没发请求")
    void robots_denied_pages_do_not_count_toward_max_pages() {
        CrawlSite s = site(2, 5);
        stubIngestSucceeds();
        byte[] robots = "User-agent: *\nDisallow: /help/skip\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(webPageFetcher.downloadRaw(anyString()))
                .thenReturn(new WebPageFetcher.Downloaded(robots, "UTF-8", 200));
        stubPage("https://x.com/help", "/help/skip1", "/help/skip2", "/help/ok");
        stubPage("https://x.com/help/ok");

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        // skip1/skip2 被 robots 拦下不计数，所以入口 + ok 两页都抓到了
        assertThat(fetched).containsExactly("https://x.com/help", "https://x.com/help/ok");
        assertThat(p.crawled).isEqualTo(2);
    }

    @Test
    @DisplayName("同一页的不同写法只抓一次（visited 去重）")
    void visits_each_page_once() {
        CrawlSite s = site(50, 3);
        stubIngestSucceeds();
        stubPage("https://x.com/help", "/help/a", "/help/a/", "/help/a#top", "/help/a?utm_source=x");
        stubPage("https://x.com/help/a");

        service.runCrawl(newProgress(s), 9L, 1L, 2L, 3L, null);

        assertThat(fetched).containsExactly("https://x.com/help", "https://x.com/help/a");
    }

    // ===== 已存在 / 新建 =====

    @Test
    @DisplayName("已存在的 URL 走既有 syncDocument，不重复建档")
    void existing_url_goes_through_sync_document() {
        CrawlSite s = site(50, 0);
        stubPage("https://x.com/help");
        Document existing = Document.builder().id(77L).sourceUrl("https://x.com/help").build();
        when(documentRepository.findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc(100L, "https://x.com/help"))
                .thenReturn(Optional.of(existing));

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        verify(webPageSyncService).syncDocument(77L);
        verify(documentRepository, never()).save(any(Document.class));
        assertThat(p.updated).isEqualTo(1);
        assertThat(p.created).isZero();
    }

    @Test
    @DisplayName("摄取成功才落 contentHash，并设 lastSyncedAt")
    void writes_content_hash_only_after_successful_ingest() {
        CrawlSite s = site(50, 0);
        stubIngestSucceeds();
        stubPage("https://x.com/help");
        List<Document> saved = new ArrayList<>();
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(55L);
            }
            saved.add(d);
            return d;
        });
        when(documentRepository.findById(55L)).thenReturn(Optional.of(
                Document.builder().id(55L).status(DocumentStatus.COMPLETED).build()));

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        verify(knowledgeService).processDocument(55L);
        Document last = saved.get(saved.size() - 1);
        assertThat(last.getContentHash()).isEqualTo("hash-https://x.com/help");
        assertThat(last.getLastSyncedAt()).isNotNull();
        assertThat(p.created).isEqualTo(1);
    }

    @Test
    @DisplayName("摄取失败：contentHash 与 lastSyncedAt 都不落，计入 failed")
    void leaves_content_hash_null_when_ingest_fails() {
        CrawlSite s = site(50, 0);
        stubPage("https://x.com/help");
        List<Document> saved = new ArrayList<>();
        when(documentRepository.findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(56L);
            }
            saved.add(d);
            return d;
        });
        when(documentRepository.findById(56L)).thenReturn(Optional.of(
                Document.builder().id(56L).status(DocumentStatus.FAILED).build()));

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        assertThat(saved).allSatisfy(d -> {
            assertThat(d.getContentHash()).isNull();
            assertThat(d.getLastSyncedAt()).isNull();
        });
        assertThat(p.created).isZero();
        assertThat(p.failed).isEqualTo(1);
    }

    // ===== fail-soft 与入口 =====

    @Test
    @DisplayName("单页抓取失败不中断整批，只累加 failed")
    void single_page_failure_does_not_abort_the_batch() {
        CrawlSite s = site(50, 1);
        stubIngestSucceeds();
        stubPage("https://x.com/help", "/help/bad", "/help/good");
        when(webPageFetcher.fetch(eq("https://x.com/help/bad"), any()))
                .thenThrow(new BusinessException("网页返回 HTTP 500"));
        stubPage("https://x.com/help/good");

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        assertThat(fetched).contains("https://x.com/help/good");
        assertThat(p.failed).isEqualTo(1);
        assertThat(p.status).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("入口页抓取失败：整个任务 FAILED")
    void entry_page_failure_fails_the_whole_job() {
        CrawlSite s = site(50, 1);
        when(webPageFetcher.fetch(eq("https://x.com/help"), any()))
                .thenThrow(new BusinessException("网页返回 HTTP 404"));

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        assertThat(p.status).isEqualTo("FAILED");
        assertThat(p.errorMessage).contains("入口页");
    }

    // ===== 租户上下文（陷阱 6.1）=====

    @Test
    @DisplayName("爬取线程内建档时 TenantContext 已被搬运——否则租户字段会落 null")
    void carries_tenant_context_into_the_async_thread() {
        CrawlSite s = site(50, 0);
        stubIngestSucceeds();
        stubPage("https://x.com/help");
        AtomicReference<Long> seenTenantId = new AtomicReference<>();
        AtomicReference<Long> seenAppId = new AtomicReference<>();
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            seenTenantId.set(TenantContext.getTenantId());
            seenAppId.set(TenantContext.getAppId());
            Document d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(55L);
            }
            return d;
        });

        service.runCrawl(newProgress(s), 9L, 42L, 7L, 3L, null);

        assertThat(seenTenantId.get()).isEqualTo(42L);
        assertThat(seenAppId.get()).isEqualTo(7L);
    }

    @Test
    @DisplayName("runCrawl 返回后 TenantContext 已清理——线程池会复用线程，不清理就泄漏给下一个任务")
    void clears_tenant_context_in_finally() {
        CrawlSite s = site(50, 0);
        stubIngestSucceeds();
        stubPage("https://x.com/help");

        service.runCrawl(newProgress(s), 9L, 42L, 7L, 3L, null);

        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getAppId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
    }

    @Test
    @DisplayName("即使爬取整体失败，TenantContext 仍被清理")
    void clears_tenant_context_even_when_crawl_fails() {
        when(crawlSiteRepository.findById(9L)).thenReturn(Optional.empty());
        SiteCrawlService.Progress p = service.newProgressForTest("job-1", 9L, 50);

        service.runCrawl(p, 9L, 42L, 7L, 3L, null);

        assertThat(p.status).isEqualTo("FAILED");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    // ===== 静态辅助 =====

    @Test
    @DisplayName("范围前缀推导：目录/文件/根路径三种形态")
    void derives_scope_prefix() {
        assertThat(SiteCrawlService.deriveScopePrefix("https://x.com/help/"))
                .isEqualTo("https://x.com/help/");
        assertThat(SiteCrawlService.deriveScopePrefix("https://x.com/help/faq/article-1.html"))
                .isEqualTo("https://x.com/help/faq/");
        assertThat(SiteCrawlService.deriveScopePrefix("https://x.com/")).isEqualTo("https://x.com/");
        assertThat(SiteCrawlService.deriveScopePrefix("https://x.com")).isEqualTo("https://x.com/");
    }

    @Test
    @DisplayName("范围前缀异域直接拒绝——这是安全边界不是配置项")
    void rejects_scope_prefix_on_another_host() {
        CrawlSiteRequest req = new CrawlSiteRequest();
        req.setEntryUrl("https://x.com/help/");
        req.setScopePrefix("https://evil.com/");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(100L);
        when(knowledgeService.getKnowledgeBase(100L)).thenReturn(kb);

        assertThatThrownBy(() -> service.startCrawl(100L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同域");
    }

    @Test
    @DisplayName("页数与深度被钳制到平台硬顶")
    void clamps_max_pages_and_depth_to_platform_limits() {
        props.setMaxPagesLimit(500);
        props.setMaxDepthLimit(5);
        CrawlSiteRequest req = new CrawlSiteRequest();
        req.setEntryUrl("https://x.com/help/");
        req.setMaxPages(99999);
        req.setMaxDepth(99);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(100L);
        when(knowledgeService.getKnowledgeBase(100L)).thenReturn(kb);
        List<CrawlSite> saved = new ArrayList<>();
        when(crawlSiteRepository.save(any(CrawlSite.class))).thenAnswer(inv -> {
            CrawlSite cs = inv.getArgument(0);
            cs.setId(9L);
            saved.add(cs);
            return cs;
        });
        when(crawlSiteRepository.findById(9L)).thenAnswer(inv -> Optional.of(saved.get(0)));
        stubIngestSucceeds();
        stubPage("https://x.com/help");

        service.startCrawl(100L, req);

        assertThat(saved.get(0).getMaxPages()).isEqualTo(500);
        assertThat(saved.get(0).getMaxDepth()).isEqualTo(5);
    }

    @Test
    @DisplayName("同一站点已有爬取在进行时拒绝再起一个")
    void rejects_concurrent_crawl_of_the_same_site() {
        CrawlSite s = site(50, 0);
        service.markCrawlingForTest(9L);

        assertThatThrownBy(() -> service.recrawl(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有爬取");
    }

    @Test
    @DisplayName("进度不存在时抛 404 语义的业务异常")
    void unknown_job_id_throws() {
        assertThatThrownBy(() -> service.getProgress("nope"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd backend && mvn -q test -Dtest=SiteCrawlServiceTest
```
预期：编译失败，`SiteCrawlService` 不存在。

- [ ] **Step 4: 实现 SiteCrawlService**

创建 `backend/src/main/java/com/qingyu/care/service/web/SiteCrawlService.java`：

```java
package com.qingyu.care.service.web;

import com.qingyu.care.config.SiteCrawlProperties;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.dto.CrawlSiteRequest;
import com.qingyu.care.dto.CrawlStartResponse;
import com.qingyu.care.entity.CrawlSite;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.CrawlSiteRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点爬取编排：BFS 发现页面、逐页建档摄取、内存进度、站点级互斥。
 *
 * <p>范式照 {@code ReembedService}：请求线程同步做归属校验与参数钳制、建 {@code Progress}
 * 并返回 {@code jobId}，{@code self.@Async} 跑批，前端轮询进度。进程重启则进度丢失，
 * 但已建文档都在（每页建档即提交）——运营重新点爬取时已存在的 URL 会被跳过，
 * <b>幂等重跑即是恢复</b>，故不设 {@code CrawlJob} 表。
 *
 * <p>⚠️ 站点级互斥不能用第一期那把 {@code pg_try_advisory_xact_lock}：它是<b>事务级</b>的、
 * 锁随事务结束释放，而爬取是几分钟的长任务且必须逐页提交（否则一个大事务跑几分钟、
 * 中途失败全丢）。逐页的 {@code processDocument} 内部仍各自抢自己文档那把锁，
 * 文档级互斥不受影响。
 *
 * @author ruan
 */
@Service
@Slf4j
public class SiteCrawlService {

    /** {@code document.name} / {@code original_filename} 都是 varchar(255)。 */
    private static final int NAME_MAX_LENGTH = 255;

    /** 内存进度（重启不留存；幂等重跑即续）。 */
    public static final class Progress {
        public final String jobId;
        public final Long siteId;
        public final int maxPages;
        /** 已<b>尝试抓取</b>的页数（不是成功数，见 crawl 内注释）。 */
        public volatile int crawled;
        public volatile int created;
        public volatile int updated;
        public volatile int failed;
        public volatile String status;
        public volatile String errorMessage;
        public final LocalDateTime startedAt = LocalDateTime.now();
        public volatile LocalDateTime finishedAt;

        Progress(String jobId, Long siteId, int maxPages) {
            this.jobId = jobId;
            this.siteId = siteId;
            this.maxPages = maxPages;
            this.status = "RUNNING";
        }
    }

    /** BFS 队列元素。 */
    private record PageRef(String url, int depth) {}

    private final CrawlSiteRepository crawlSiteRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeService knowledgeService;
    private final WebPageSyncService webPageSyncService;
    private final WebPageFetcher webPageFetcher;
    private final SiteCrawlProperties props;

    // 自注入解决 @Async 同类调用不生效问题（同 ReembedService/WebPageSyncService 范式）
    private SiteCrawlService self;

    private final ConcurrentHashMap<String, Progress> jobs = new ConcurrentHashMap<>();

    /** 正在爬取的站点 id，站点级互斥。多实例下失效——见类文档与 spec §6.2 的取舍。 */
    private final Set<Long> crawlingSites = ConcurrentHashMap.newKeySet();

    public SiteCrawlService(CrawlSiteRepository crawlSiteRepository,
                            DocumentRepository documentRepository,
                            KnowledgeService knowledgeService,
                            WebPageSyncService webPageSyncService,
                            WebPageFetcher webPageFetcher,
                            SiteCrawlProperties props) {
        this.crawlSiteRepository = crawlSiteRepository;
        this.documentRepository = documentRepository;
        this.knowledgeService = knowledgeService;
        this.webPageSyncService = webPageSyncService;
        this.webPageFetcher = webPageFetcher;
        this.props = props;
    }

    @Autowired
    @Lazy
    public void setSelf(SiteCrawlService self) {
        this.self = self;
    }

    // ===== 对外操作（均在请求线程执行，依赖 TenantContext）=====

    /**
     * 建站点并立即开爬。
     *
     * <p>⚠️ 知识库必须经 {@code knowledgeService.getKnowledgeBase(...)} 取——它内部做了
     * {@code TenantOwnershipGuard} 归属校验；直接用 {@code knowledgeBaseRepository.findById}
     * 不受 Hibernate {@code @Filter} 保护，等于让任意租户往别人知识库里插文档
     * （第一期的 Critical 越权教训）。
     */
    @Transactional
    public CrawlStartResponse startCrawl(Long knowledgeBaseId, CrawlSiteRequest req) {
        KnowledgeBase kb = knowledgeService.getKnowledgeBase(knowledgeBaseId);
        CrawlSite site = crawlSiteRepository.save(applyRequest(new CrawlSite(), req, kb));
        return new CrawlStartResponse(site.getId(), launch(site));
    }

    /** 重新爬取已有站点，复用其配置（已存在的 URL 会被跳过，只建新页）。 */
    public String recrawl(Long siteId) {
        return launch(requireOwnedSite(siteId));
    }

    /** 改站点配置。下一轮定期同步就会用上新的 {@code contentSelector}。 */
    @Transactional
    public CrawlSite updateSite(Long siteId, CrawlSiteRequest req) {
        CrawlSite site = requireOwnedSite(siteId);
        return crawlSiteRepository.save(applyRequest(site, req, site.getKnowledgeBase()));
    }

    public List<CrawlSite> listSites(Long knowledgeBaseId) {
        knowledgeService.getKnowledgeBase(knowledgeBaseId);   // 归属校验
        return crawlSiteRepository.findByKnowledgeBaseIdOrderByIdDesc(knowledgeBaseId);
    }

    /**
     * 删站点。{@code deleteDocuments=false}（默认）时文档保留、外键置 null，
     * 退化成普通网页文档，各自继续定期同步。
     *
     * <p>⚠️ 要删文档时<b>必须逐篇调 {@code knowledgeService.deleteDocument}</b>，
     * 不能 {@code documentRepository.deleteAll}：前者才会把该文档的向量一并删除，
     * 后者只删 {@code document} 行，留下一批再也没有库行指向、<b>却仍参与检索</b>的
     * 孤儿向量——它们会继续被 {@code similaritySearch} 命中并拼进 Prompt，而运营在
     * 界面上已经看不到这些文档了。同 {@code ChatAttachment} 孤儿清理必须连 S3 一起删。
     */
    @Transactional
    public void deleteSite(Long siteId, boolean deleteDocuments) {
        CrawlSite site = requireOwnedSite(siteId);
        List<Document> docs = documentRepository.findByCrawlSiteId(siteId);
        if (deleteDocuments) {
            for (Document doc : docs) {
                knowledgeService.deleteDocument(doc.getId());
            }
        } else {
            for (Document doc : docs) {
                doc.setCrawlSite(null);
                documentRepository.save(doc);
            }
        }
        crawlSiteRepository.delete(site);
        log.info("爬取站点已删除 siteId={} 连带删文档={} 文档数={}", siteId, deleteDocuments, docs.size());
    }

    public Progress getProgress(String jobId) {
        Progress p = jobs.get(jobId);
        if (p == null) {
            throw new BusinessException(404, "爬取任务不存在: " + jobId);
        }
        return p;
    }

    // ===== 启动与互斥 =====

    /**
     * 建进度、抢互斥、投递异步任务。
     *
     * <p>⚠️ {@code self.runCrawl} 投递失败（线程池拒绝）必须回滚互斥——否则该站点
     * 会<b>永久无法再爬</b>。
     */
    private String launch(CrawlSite site) {
        long running = jobs.values().stream().filter(p -> "RUNNING".equals(p.status)).count();
        if (running >= props.getMaxConcurrentJobs()) {
            throw new BusinessException("已有 " + running + " 个爬取任务在进行，请稍后再试");
        }
        if (!crawlingSites.add(site.getId())) {
            throw new BusinessException("该站点已有爬取在进行，请等待完成");
        }
        try {
            Progress p = new Progress(UUID.randomUUID().toString(), site.getId(), site.getMaxPages());
            jobs.put(p.jobId, p);
            // 必须经 self 才能命中 Spring 代理让 @Async 真正生效（同类直调会退化成同步阻塞请求线程）。
            // 四个 TenantContext 值必须在请求线程取好传进去——@Async 线程没有上下文
            self.runCrawl(p, site.getId(), TenantContext.getTenantId(), TenantContext.getAppId(),
                    TenantContext.getUserId(), TenantContext.getAccessLevel());
            return p.jobId;
        } catch (RuntimeException e) {
            crawlingSites.remove(site.getId());
            throw e;
        }
    }

    // ===== 异步执行 =====

    /**
     * 异步爬取入口。
     *
     * <p>⚠️ 四个租户上下文值由请求线程传入并在此搬进 {@code TenantContext}：本方法跑在
     * {@code @Async} 线程，没有上下文时 {@code TenantEntityListener} 回填出来的
     * {@code tenant_id}/{@code app_id} 全是 null。
     *
     * <p>⚠️ {@code finally} 必须 {@code TenantContext.clear()}：{@code AsyncConfig} 用的是
     * Spring Boot 默认 {@code applicationTaskExecutor}（core 8、<b>线程复用</b>），
     * 不清理就是把上下文泄漏给下一个复用该线程的任务（流式聊天改造时实测复现过
     * 平台管理员会话落进别人租户）。
     */
    @Async
    public void runCrawl(Progress p, Long siteId, Long tenantId, Long appId,
                         Long userId, DocumentVisibility accessLevel) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setAppId(appId);
        TenantContext.setUserId(userId);
        TenantContext.setAccessLevel(accessLevel);
        try {
            crawl(p, siteId);
            p.status = "COMPLETED";
            log.info("站点爬取完成 siteId={} 已爬={} 新建={} 更新={} 失败={}",
                    siteId, p.crawled, p.created, p.updated, p.failed);
        } catch (Exception e) {
            p.status = "FAILED";
            p.errorMessage = e.getMessage();
            log.warn("站点爬取失败 siteId={}: {}", siteId, e.getMessage());
        } finally {
            p.finishedAt = LocalDateTime.now();
            crawlingSites.remove(siteId);
            TenantContext.clear();
        }
    }

    /** BFS 主循环。 */
    private void crawl(Progress p, Long siteId) {
        CrawlSite site = crawlSiteRepository.findById(siteId)
                .orElseThrow(() -> new BusinessException("爬取站点不存在: " + siteId));

        String entry = LinkExtractor.normalize(site.getEntryUrl());
        if (entry == null) {
            throw new BusinessException("入口 URL 非法: " + site.getEntryUrl());
        }
        String prefix = LinkExtractor.normalize(site.getScopePrefix());
        if (prefix == null) {
            throw new BusinessException("范围前缀非法: " + site.getScopePrefix());
        }

        RobotsChecker.RobotsRules robots = RobotsChecker.fetch(originOf(entry), webPageFetcher);
        long delayMs = robots.crawlDelayMs() != null ? robots.crawlDelayMs() : props.getDefaultDelayMs();

        Deque<PageRef> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new PageRef(entry, 0));
        visited.add(entry);
        boolean isEntry = true;

        while (!queue.isEmpty() && p.crawled < site.getMaxPages()) {
            PageRef ref = queue.poll();
            if (!robots.allows(ref.url())) {
                // 不计入 crawled：它根本没发请求。maxPages 计的是「已尝试抓取」的页数，
                // 计成功数的话一个到处 404 的站点会让队列不断展开、永远碰不到上限
                log.debug("robots 拒绝，跳过 {}", ref.url());
                continue;
            }
            sleepQuietly(delayMs);
            p.crawled++;
            try {
                processPage(site, ref, prefix, queue, visited, p);
            } catch (Exception e) {
                p.failed++;
                log.warn("爬取单页失败 url={}: {}", ref.url(), e.getMessage());
                if (isEntry) {
                    // 入口页是唯一的例外：抓不到入口就没有爬取可言。站点行保留，
                    // 用户改完配置能直接重试
                    throw new BusinessException("入口页抓取失败: " + e.getMessage());
                }
            }
            isEntry = false;
        }

        site.setLastCrawledAt(LocalDateTime.now());
        crawlSiteRepository.save(site);
    }

    /** 处理一页：抓取 → 建档或同步 → 抽链接入队。 */
    private void processPage(CrawlSite site, PageRef ref, String prefix,
                             Deque<PageRef> queue, Set<String> visited, Progress p) {
        WebPageFetcher.FetchResult result = webPageFetcher.fetch(ref.url(), site.getContentSelector());

        Document existing = documentRepository
                .findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc(
                        site.getKnowledgeBase().getId(), ref.url())
                .orElse(null);
        if (existing != null) {
            // ⚠️ 必须是同步调用而非 syncDocumentAsync：后者会让 N 篇瞬间涌进 @Async 池，
            // robots 的 Crawl-delay 与默认限速当场失效，等于对目标站点发起并发冲击。
            // 代价是这一页在本轮被抓两次（一次为提链接、一次在 syncDocument 内部）——
            // 见 spec §5.2 的取舍与重新考虑的触发条件
            webPageSyncService.syncDocument(existing.getId());
            p.updated++;
        } else {
            createAndIngest(site, ref.url(), result);
            p.created++;
        }

        if (ref.depth() < site.getMaxDepth()) {
            // ⚠️ result.dom() 是剔噪之前的副本；用剔噪后的树会丢掉 nav 里的目录链接
            for (String link : LinkExtractor.extract(result.dom(), prefix)) {
                if (visited.add(link)) {
                    queue.add(new PageRef(link, ref.depth() + 1));
                }
            }
        }
    }

    /**
     * 新建文档并摄取。
     *
     * <p>⚠️ <b>摄取成功才落 {@code contentHash}</b>：写早了会让下一轮同步误判
     * 「内容未变」而永久跳过重试，该文档 0 向量、永远检索不到、且不再有任何自动重试，
     * 全程无异常无日志（第一期的 Critical 缺陷）。
     *
     * <p>⚠️ 同理 {@code lastSyncedAt} 也只在成功分支设：不设的话
     * {@code findDueWebPageIds} 会因 {@code last_synced_at IS NULL} 立刻判为到期，
     * 爬完 50 篇后下一轮调度会把它们全部重抓一遍；而失败的留 null 正是为了让调度器尽快重试。
     */
    private void createAndIngest(CrawlSite site, String url, WebPageFetcher.FetchResult result) {
        Document doc = documentRepository.save(Document.builder()
                .knowledgeBase(site.getKnowledgeBase())
                .crawlSite(site)
                .name(WebPageSyncService.truncate(result.title(), NAME_MAX_LENGTH))
                .originalFilename(WebPageSyncService.truncate(url, NAME_MAX_LENGTH))
                .documentType(DocumentType.URL)
                .status(DocumentStatus.PENDING)
                .sourceUrl(url)
                .content(result.content())
                .syncInterval(site.getSyncInterval() == null ? SyncInterval.MANUAL : site.getSyncInterval())
                .build());

        knowledgeService.processDocument(doc.getId());

        DocumentStatus after = documentRepository.findById(doc.getId())
                .map(Document::getStatus)
                .orElse(null);
        if (after != DocumentStatus.COMPLETED) {
            throw new BusinessException("摄取未成功（status=" + after
                    + "），contentHash 不落库以便定期同步重试");
        }
        Document fresh = documentRepository.findById(doc.getId()).orElseThrow();
        fresh.setContentHash(result.contentHash());
        fresh.setLastSyncedAt(LocalDateTime.now());
        documentRepository.save(fresh);
    }

    // ===== 参数处理与归属校验 =====

    /** 把请求里的字段套到站点上（建与改共用），含钳制与同域校验。 */
    private CrawlSite applyRequest(CrawlSite site, CrawlSiteRequest req, KnowledgeBase kb) {
        String entryUrl = req.getEntryUrl();
        String scopePrefix = (req.getScopePrefix() == null || req.getScopePrefix().isBlank())
                ? deriveScopePrefix(entryUrl)
                : req.getScopePrefix();
        requireSameHost(entryUrl, scopePrefix);

        site.setKnowledgeBase(kb);
        site.setEntryUrl(entryUrl);
        site.setScopePrefix(scopePrefix);
        site.setMaxPages(clamp(req.getMaxPages(), 50, props.getMaxPagesLimit()));
        site.setMaxDepth(clamp(req.getMaxDepth(), 3, props.getMaxDepthLimit()));
        site.setContentSelector(
                req.getContentSelector() == null || req.getContentSelector().isBlank()
                        ? null : req.getContentSelector().trim());
        site.setSyncInterval(req.getSyncInterval() == null ? SyncInterval.MANUAL : req.getSyncInterval());
        return site;
    }

    /** 取站点并做归属校验——{@code findById} 不受 Hibernate {@code @Filter} 保护。 */
    private CrawlSite requireOwnedSite(Long siteId) {
        CrawlSite site = crawlSiteRepository.findById(siteId)
                .orElseThrow(() -> new BusinessException(404, "爬取站点不存在: " + siteId));
        if (!TenantOwnershipGuard.mutableInCurrentContext(site.getTenantId(), site.getAppId())) {
            // 按「不存在」同语义处理，防枚举探测
            throw new BusinessException(404, "爬取站点不存在: " + siteId);
        }
        return site;
    }

    /**
     * 由入口 URL 推导范围前缀：目录形态原样、文件形态取其所在目录、根路径即整站。
     * 包级 static 免容器单测。
     */
    static String deriveScopePrefix(String entryUrl) {
        final URI uri;
        try {
            uri = new URI(entryUrl.trim());
        } catch (URISyntaxException e) {
            throw new BusinessException("入口 URL 非法: " + entryUrl);
        }
        String origin = originOf(entryUrl);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.isEmpty() || "/".equals(path)) {
            return origin + "/";
        }
        if (path.endsWith("/")) {
            return origin + path;
        }
        return origin + path.substring(0, path.lastIndexOf('/') + 1);
    }

    /** {@code scheme://host[:port]}。 */
    static String originOf(String url) {
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new BusinessException("URL 非法: " + url);
        }
        StringBuilder sb = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        return sb.toString();
    }

    /**
     * 范围前缀必须与入口同域。
     *
     * <p>这是<b>安全边界不是配置项</b>——放开等于给了一个「用平台身份抓任意站点」的入口。
     */
    private static void requireSameHost(String entryUrl, String scopePrefix) {
        String entryHost = LinkExtractor.hostOf(entryUrl);
        String prefixHost = LinkExtractor.hostOf(scopePrefix);
        if (entryHost == null || prefixHost == null || !entryHost.equals(prefixHost)) {
            throw new BusinessException("范围前缀必须与入口 URL 同域");
        }
    }

    /** 入参为空取默认值，超硬顶钳到硬顶，低于 1（深度低于 0）抬回下界。 */
    private static int clamp(Integer value, int defaultValue, int limit) {
        int v = value == null ? defaultValue : value;
        return Math.max(0, Math.min(v, limit));
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("爬取被中断");
        }
    }

    // ===== 仅供单测的窄接口 =====

    /** 单测直接构造进度，避免为了拿 Progress 去跑一遍 launch 的互斥与并发检查。 */
    Progress newProgressForTest(String jobId, Long siteId, int maxPages) {
        Progress p = new Progress(jobId, siteId, maxPages);
        jobs.put(jobId, p);
        return p;
    }

    /** 单测模拟「该站点已有爬取在进行」。 */
    void markCrawlingForTest(Long siteId) {
        crawlingSites.add(siteId);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend && mvn -q test -Dtest=SiteCrawlServiceTest
```
预期：全部 PASS。若有失败，逐个读断言消息定位——常见原因是桩数据里 `KnowledgeBase.id` 与 `findFirstBy...` 的第一个参数不一致。

- [ ] **Step 6: 变异自证（四条，逐条做）**

1. 把 `runCrawl` 的 `finally` 里 `TenantContext.clear()` 注释掉 → **必须** `clears_tenant_context_in_finally` 与 `clears_tenant_context_even_when_crawl_fails` 变红。
2. 把 `runCrawl` 开头四行 `TenantContext.setXxx` 注释掉 → **必须** `carries_tenant_context_into_the_async_thread` 变红。
3. 把 `crawl` 里 robots 拒绝分支的 `continue` 之前加一句 `p.crawled++;` → **必须** `robots_denied_pages_do_not_count_toward_max_pages` 变红。
4. 把 `createAndIngest` 里的 `if (after != DocumentStatus.COMPLETED) throw ...` 整段删掉（即无条件落 contentHash）→ **必须** `leaves_content_hash_null_when_ingest_fails` 变红。

每条确认后改回，最后重跑 Step 5 确认全绿。

- [ ] **Step 7: 全量单测 + 提交**

```bash
cd backend && mvn -q clean test
```
预期：BUILD SUCCESS。

```bash
git add backend/src/main/java/com/qingyu/care/service/web/SiteCrawlService.java \
        backend/src/main/java/com/qingyu/care/dto/CrawlSiteRequest.java \
        backend/src/main/java/com/qingyu/care/dto/CrawlStartResponse.java \
        backend/src/test/java/com/qingyu/care/service/web/SiteCrawlServiceTest.java
git commit -m "feat(backend): SiteCrawlService——BFS 编排、内存进度与站点级互斥

范式照 ReembedService：请求线程校验+建 Progress 发 jobId，self.@Async 跑批。
不设 CrawlJob 表——每页建档即提交，重跑时已存在 URL 跳过，幂等重跑即恢复。

四个陷阱都已处理并有测试守护（每条都做了变异自证）：
- @Async 线程没有 TenantContext，四个值由请求线程传入搬运，finally 必须
  clear（默认 applicationTaskExecutor 会复用线程，不清理就泄漏给下一个任务）
- @Async 投递失败要回滚站点互斥，否则该站点永久无法再爬
- maxPages 计「已尝试抓取」；robots 拒绝的不计数（它没发请求）；计成功数
  会让到处 404 的站点永远碰不到上限
- 摄取成功才落 contentHash 与 lastSyncedAt：前者写早了会让下一轮同步误判
  「内容未变」而永久跳过重试；后者不设会让爬完的 50 篇下一轮被全部重抓

站点级互斥用内存 map 而非第一期那把咨询锁：pg_try_advisory_xact_lock 是事务级的，
与「几分钟长任务 + 逐页提交」直接冲突。
删站点连带删文档必须逐篇走 knowledgeService.deleteDocument——只删库行会留下
再也没有库行指向、却仍参与检索的孤儿向量。"
```

---

## Task 6: 六个端点 + 越权单测 + 落库 IT

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/CrawlProgressDTO.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/KnowledgeController.java`
- Modify: `backend/src/test/java/com/qingyu/care/service/web/SiteCrawlServiceTest.java`（补两条越权用例）
- Test: `backend/src/test/java/com/qingyu/care/it/SiteCrawlIT.java`

**Interfaces:**
- Consumes: `SiteCrawlService` 的全部 public 方法（Task 5）、`CrawlSiteRequest`、`CrawlStartResponse`
- Produces:
  - `CrawlProgressDTO.from(SiteCrawlService.Progress) : CrawlProgressDTO`
  - 六条 HTTP 端点（见下表）

| 方法 | 路径 | 返回 |
|:---|:---|:---|
| POST | `/knowledge/{knowledgeBaseId}/crawl-sites` | `ApiResponse<CrawlStartResponse>` |
| GET | `/knowledge/{knowledgeBaseId}/crawl-sites` | `ApiResponse<List<CrawlSite>>` |
| GET | `/knowledge/crawl-jobs/{jobId}` | `ApiResponse<CrawlProgressDTO>` |
| POST | `/knowledge/crawl-sites/{siteId}/recrawl` | `ApiResponse<String>`（jobId） |
| PUT | `/knowledge/crawl-sites/{siteId}` | `ApiResponse<CrawlSite>` |
| DELETE | `/knowledge/crawl-sites/{siteId}` | `ApiResponse<Void>` |

**背景：** 项目惯例是控制器直接返回实体（`ApiResponse<Document>`，见既有 `addWebPage`），不用 MapStruct；`ApiResponse.success(msg, data)` / `ApiResponse.success(data)`。写操作打 `@AuditLog(action=, targetType=)`。

- [ ] **Step 1: 建进度 DTO**

创建 `backend/src/main/java/com/qingyu/care/dto/CrawlProgressDTO.java`：

```java
package com.qingyu.care.dto;

import com.qingyu.care.service.web.SiteCrawlService;

import java.time.LocalDateTime;

/**
 * 爬取进度响应。{@code total} 刻意不存在——BFS 边爬边发现，总数是动态的
 * （这与 {@code ReembedService} 不同，重嵌开始时就知道总数）。前端显示成
 * 「已爬 12 / 上限 50 页」。
 *
 * @author ruan
 */
public record CrawlProgressDTO(String jobId, Long siteId, String status,
                               int crawled, int created, int updated, int failed,
                               int maxPages, String errorMessage,
                               LocalDateTime startedAt, LocalDateTime finishedAt) {

    public static CrawlProgressDTO from(SiteCrawlService.Progress p) {
        return new CrawlProgressDTO(p.jobId, p.siteId, p.status,
                p.crawled, p.created, p.updated, p.failed,
                p.maxPages, p.errorMessage, p.startedAt, p.finishedAt);
    }
}
```

- [ ] **Step 2: 加六个端点**

在 `KnowledgeController` 里注入 `SiteCrawlService`（该类用的是构造器注入还是 `@RequiredArgsConstructor`，跟着现状加字段即可），然后在既有 `syncWebPage` 端点之后插入：

```java
    // ===== 站点爬取（网站知识库第二期）=====

    @PostMapping("/{knowledgeBaseId}/crawl-sites")
    @Operation(summary = "新建爬取站点并立即开爬",
               description = "按入口 URL 发现并抓取范围内的多个页面，每页一篇文档；返回 siteId 与用于轮询进度的 jobId")
    @AuditLog(action = "ADD_CRAWL_SITE", targetType = "CrawlSite")
    public ApiResponse<CrawlStartResponse> addCrawlSite(@PathVariable Long knowledgeBaseId,
                                                        @Valid @RequestBody CrawlSiteRequest request) {
        return ApiResponse.success("已开始爬取", siteCrawlService.startCrawl(knowledgeBaseId, request));
    }

    @GetMapping("/{knowledgeBaseId}/crawl-sites")
    @Operation(summary = "获取爬取站点列表")
    public ApiResponse<List<CrawlSite>> listCrawlSites(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.success(siteCrawlService.listSites(knowledgeBaseId));
    }

    @GetMapping("/crawl-jobs/{jobId}")
    @Operation(summary = "查询爬取进度",
               description = "内存进度，应用重启后丢失；重启后重新发起爬取即可（已存在的 URL 会被跳过）")
    public ApiResponse<CrawlProgressDTO> getCrawlProgress(@PathVariable String jobId) {
        return ApiResponse.success(CrawlProgressDTO.from(siteCrawlService.getProgress(jobId)));
    }

    @PostMapping("/crawl-sites/{siteId}/recrawl")
    @Operation(summary = "重新爬取站点",
               description = "复用站点已保存的配置；已存在的 URL 只做内容同步、不重复建档")
    @AuditLog(action = "RECRAWL_SITE", targetType = "CrawlSite")
    public ApiResponse<String> recrawlSite(@PathVariable Long siteId) {
        return ApiResponse.success("已开始爬取", siteCrawlService.recrawl(siteId));
    }

    @PutMapping("/crawl-sites/{siteId}")
    @Operation(summary = "更新爬取站点配置",
               description = "改 contentSelector 后，该站点已建文档的下一轮定期同步就会用上新选择器")
    @AuditLog(action = "UPDATE_CRAWL_SITE", targetType = "CrawlSite")
    public ApiResponse<CrawlSite> updateCrawlSite(@PathVariable Long siteId,
                                                  @Valid @RequestBody CrawlSiteRequest request) {
        return ApiResponse.success("站点配置已更新", siteCrawlService.updateSite(siteId, request));
    }

    @DeleteMapping("/crawl-sites/{siteId}")
    @Operation(summary = "删除爬取站点",
               description = "deleteDocuments=false（默认）时文档保留并退化成普通网页文档；为 true 时连带删除文档与其向量")
    @AuditLog(action = "DELETE_CRAWL_SITE", targetType = "CrawlSite")
    public ApiResponse<Void> deleteCrawlSite(@PathVariable Long siteId,
                                             @RequestParam(defaultValue = "false") boolean deleteDocuments) {
        siteCrawlService.deleteSite(siteId, deleteDocuments);
        return ApiResponse.success(deleteDocuments ? "站点与其文档已删除" : "站点已删除，文档已保留", null);
    }
```

补齐 import：`com.qingyu.care.dto.CrawlProgressDTO`、`CrawlSiteRequest`、`CrawlStartResponse`、`com.qingyu.care.entity.CrawlSite`、`com.qingyu.care.service.web.SiteCrawlService`，以及 `org.springframework.web.bind.annotation.PutMapping` / `DeleteMapping` / `RequestParam`（若尚未 import）。

- [ ] **Step 3: 编译**

```bash
cd backend && mvn -q clean test-compile
```
预期：编译通过。

- [ ] **Step 4: 补两条越权单测**

在 `SiteCrawlServiceTest` 末尾追加（`TenantOwnershipGuard` 读的是 `TenantContext`，纯 Mockito 下直接设即可）：

```java
    @Test
    @DisplayName("拿别的租户的站点 id 重爬：按「不存在」拒绝，防枚举探测")
    void rejects_recrawl_of_another_tenants_site() {
        CrawlSite other = CrawlSite.builder().id(9L).build();
        other.setTenantId(999L);
        other.setAppId(888L);
        when(crawlSiteRepository.findById(9L)).thenReturn(Optional.of(other));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);

        assertThatThrownBy(() -> service.recrawl(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("拿别的租户的站点 id 删除：同样按「不存在」拒绝，且一篇文档都不动")
    void rejects_delete_of_another_tenants_site() {
        CrawlSite other = CrawlSite.builder().id(9L).build();
        other.setTenantId(999L);
        other.setAppId(888L);
        when(crawlSiteRepository.findById(9L)).thenReturn(Optional.of(other));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);

        assertThatThrownBy(() -> service.deleteSite(9L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verify(knowledgeService, never()).deleteDocument(anyLong());
        verify(crawlSiteRepository, never()).delete(any(CrawlSite.class));
    }
```

⚠️ 第二条的两个 `verify(never())` 是本条用例的全部区分度所在：只断言「抛了异常」的话，一个「先删完文档再校验归属」的实现照样绿。

- [ ] **Step 5: 跑单测**

```bash
cd backend && mvn -q test -Dtest=SiteCrawlServiceTest
```
预期：全绿。

- [ ] **Step 6: 写落库 IT**

创建 `backend/src/test/java/com/qingyu/care/it/SiteCrawlIT.java`：

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.CrawlSite;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.repository.CrawlSiteRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.web.SiteCrawlService;
import com.qingyu.care.service.web.WebPageFetcher;
import com.qingyu.care.support.AbstractPostgresIT;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 集成测试：爬取产出的文档，租户字段必须正确落库。
 *
 * <p>这条只能在 IT 里守护——{@code TenantEntityListener} 是 Hibernate 生命周期回调，
 * 纯 Mockito 根本触发不到。而爬取跑在 {@code @Async} 线程、天然没有 {@code TenantContext}：
 * 若 {@code runCrawl} 开头漏了搬运，{@code document.tenant_id}/{@code app_id} 会静默落 null，
 * 那批文档从此对所有租户可见（配置类实体的 NULL 租户是"有意共享"，文档的 NULL 不是）。
 *
 * <p>{@code webPageFetcher} 与 {@code knowledgeService} 用 {@code @MockitoBean} 换掉：
 * 本 IT 只关心租户字段与外键落库，不关心真实抓取与嵌入（S3+embedding 全链路本仓库已明确
 * 不测，见 knowledge-ingest-it-skipped 记录）。{@code getKnowledgeBase} 委托真实仓储以保住
 * 归属校验语义，{@code processDocument} 用 doAnswer 模拟"在自己的事务里把状态改完并提交"。
 *
 * <p>不加类级 {@code @Transactional}：异步线程的写入需要真正提交，且测试线程后续的轮询
 * 读取不能被同一个长事务的一级缓存挡住返回陈旧数据（同 {@link WebPageSyncTransactionIT} 的选择）。
 *
 * @author ruan
 */
class SiteCrawlIT extends AbstractPostgresIT {

    @Autowired private SiteCrawlService siteCrawlService;
    @Autowired private CrawlSiteRepository crawlSiteRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;

    @MockitoBean private WebPageFetcher webPageFetcher;
    @MockitoBean private KnowledgeService knowledgeService;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 播种：租户 + 应用 + 知识库 + 站点，全部带正确的租户上下文。 */
    private CrawlSite seedSite(String suffix) {
        Application app = seedApp(suffix, "https://example.com");
        TenantContext.setTenantId(app.getTenant().getId());
        TenantContext.setAppId(app.getId());
        try {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setName("库-" + suffix);
            kb = knowledgeBaseRepository.save(kb);

            return crawlSiteRepository.save(CrawlSite.builder()
                    .knowledgeBase(kb)
                    .entryUrl("https://site-" + suffix + ".com/help/")
                    .scopePrefix("https://site-" + suffix + ".com/help/")
                    .maxPages(1).maxDepth(0)
                    .syncInterval(SyncInterval.DAILY)
                    .build());
        } finally {
            TenantContext.clear();
        }
    }

    /** 让抓取返回一张固定页面；让 processDocument 把状态改成 COMPLETED 并提交。 */
    private void stubCollaborators() {
        when(webPageFetcher.fetch(anyString(), any())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return new WebPageFetcher.FetchResult("标题", "正文内容", "hash-" + url, 200,
                    Jsoup.parse("<html><body><p>正文内容</p></body></html>", url));
        });
        when(knowledgeService.getKnowledgeBase(anyLong()))
                .thenAnswer(inv -> knowledgeBaseRepository.findById(inv.<Long>getArgument(0)).orElseThrow());
        doAnswer(inv -> {
            Long docId = inv.getArgument(0);
            Document doc = documentRepository.findById(docId).orElseThrow();
            doc.setStatus(DocumentStatus.COMPLETED);
            doc.setChunkCount(3);
            documentRepository.save(doc);
            return null;
        }).when(knowledgeService).processDocument(anyLong());
    }

    @Test
    @DisplayName("爬取产出的文档带正确的 tenant_id / app_id / crawl_site_id")
    void crawled_documents_carry_tenant_and_site_fields() {
        CrawlSite site = seedSite("a");
        Long tenantId = site.getTenantId();
        Long appId = site.getAppId();
        assertThat(tenantId).isNotNull();
        stubCollaborators();

        SiteCrawlService.Progress p = siteCrawlService.newProgressForTest("it-job-a", site.getId(), 1);
        siteCrawlService.runCrawl(p, site.getId(), tenantId, appId, null, null);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(p.status).isNotEqualTo("RUNNING"));
        assertThat(p.status).isEqualTo("COMPLETED");

        List<Document> docs = documentRepository.findByCrawlSiteId(site.getId());
        assertThat(docs).hasSize(1);
        Document doc = docs.get(0);
        assertThat(doc.getTenantId()).isEqualTo(tenantId);
        assertThat(doc.getAppId()).isEqualTo(appId);
        assertThat(doc.getCrawlSite().getId()).isEqualTo(site.getId());
        assertThat(doc.getContentHash()).isNotNull();
        assertThat(doc.getLastSyncedAt()).isNotNull();
        assertThat(doc.getSyncInterval()).isEqualTo(SyncInterval.DAILY);
    }

    @Test
    @DisplayName("连续爬两个不同租户的站点，第二个的文档租户字段仍正确——线程池会复用线程")
    void does_not_leak_tenant_context_between_consecutive_crawls() {
        CrawlSite first = seedSite("b");
        CrawlSite second = seedSite("c");
        assertThat(first.getTenantId()).isNotEqualTo(second.getTenantId());
        stubCollaborators();

        SiteCrawlService.Progress p1 = siteCrawlService.newProgressForTest("it-job-b", first.getId(), 1);
        siteCrawlService.runCrawl(p1, first.getId(), first.getTenantId(), first.getAppId(), null, null);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(p1.status).isEqualTo("COMPLETED"));

        SiteCrawlService.Progress p2 = siteCrawlService.newProgressForTest("it-job-c", second.getId(), 1);
        siteCrawlService.runCrawl(p2, second.getId(), second.getTenantId(), second.getAppId(), null, null);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(p2.status).isEqualTo("COMPLETED"));

        Document docOfSecond = documentRepository.findByCrawlSiteId(second.getId()).get(0);
        assertThat(docOfSecond.getTenantId()).isEqualTo(second.getTenantId());
        assertThat(docOfSecond.getAppId()).isEqualTo(second.getAppId());
    }

    @Test
    @DisplayName("删站点默认不删文档：文档保留、crawl_site_id 置 null")
    void delete_site_keeps_documents_by_default() {
        CrawlSite site = seedSite("d");
        stubCollaborators();
        SiteCrawlService.Progress p = siteCrawlService.newProgressForTest("it-job-d", site.getId(), 1);
        siteCrawlService.runCrawl(p, site.getId(), site.getTenantId(), site.getAppId(), null, null);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(p.status).isEqualTo("COMPLETED"));
        Long docId = documentRepository.findByCrawlSiteId(site.getId()).get(0).getId();

        TenantContext.setTenantId(site.getTenantId());
        TenantContext.setAppId(site.getAppId());
        siteCrawlService.deleteSite(site.getId(), false);

        assertThat(crawlSiteRepository.findById(site.getId())).isEmpty();
        Document kept = documentRepository.findById(docId).orElseThrow();
        assertThat(kept.getCrawlSite()).isNull();
        assertThat(kept.getSourceUrl()).isNotNull();   // 仍是可正常定期同步的网页文档
    }

    @Test
    @DisplayName("删站点勾选删文档：逐篇走 knowledgeService.deleteDocument（它才会删向量）")
    void delete_site_with_documents_delegates_to_knowledge_service() {
        CrawlSite site = seedSite("e");
        stubCollaborators();
        SiteCrawlService.Progress p = siteCrawlService.newProgressForTest("it-job-e", site.getId(), 1);
        siteCrawlService.runCrawl(p, site.getId(), site.getTenantId(), site.getAppId(), null, null);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(p.status).isEqualTo("COMPLETED"));
        Long docId = documentRepository.findByCrawlSiteId(site.getId()).get(0).getId();

        TenantContext.setTenantId(site.getTenantId());
        TenantContext.setAppId(site.getAppId());
        siteCrawlService.deleteSite(site.getId(), true);

        // knowledgeService 是 mock，故文档行仍在——本条守的是「调了正确的删除入口」，
        // 而不是「行没了」。真正删向量的逻辑属于 knowledgeService 自己的职责
        org.mockito.Mockito.verify(knowledgeService).deleteDocument(docId);
    }
}
```

- [ ] **Step 7: 跑 IT**

⚠️ 先停掉本机在跑的 dev 后端（`:8080`），否则 `WebhookDeliveryIT` 必红（与 dev 后端共用本机 Redis 消费组）。确保 Docker Desktop 在运行。

```bash
cd backend && mvn -q verify -Dit.test=SiteCrawlIT
```
预期：4 个用例 PASS。

- [ ] **Step 8: 变异自证**

把 `SiteCrawlService.runCrawl` 开头的四行 `TenantContext.setXxx(...)` 注释掉，重跑 Step 7 —— **必须**看到 `crawled_documents_carry_tenant_and_site_fields` 变红（`tenantId` 落 null）。改回后重跑确认恢复绿。

这一步是本任务存在的核心理由：它证明这条 IT 真的守得住陷阱 6.1，而不是碰巧绿。

- [ ] **Step 9: 全量 verify + 提交**

```bash
cd backend && mvn -q clean verify
```
预期：BUILD SUCCESS，单测与 IT 全绿。

```bash
git add backend/src/main/java/com/qingyu/care/dto/CrawlProgressDTO.java \
        backend/src/main/java/com/qingyu/care/controller/KnowledgeController.java \
        backend/src/test/java/com/qingyu/care/service/web/SiteCrawlServiceTest.java \
        backend/src/test/java/com/qingyu/care/it/SiteCrawlIT.java
git commit -m "feat(backend): 站点爬取六个端点 + 越权单测 + 落库 IT

端点：建站开爬 / 列站点 / 查进度 / 重新爬取 / 改配置 / 删站点（可选连带删文档）。
写操作全部打 @AuditLog；知识库归属一律经 knowledgeService.getKnowledgeBase。

CrawlProgressDTO 刻意不带 total——BFS 边爬边发现，总数是动态的（与 ReembedService
不同，那边开始时就知道总数），前端显示成「已爬 12 / 上限 50 页」。

IT 守的是纯 Mockito 测不到的那一条：TenantEntityListener 是 Hibernate 生命周期
回调，而爬取跑在 @Async 线程天然没有 TenantContext——漏了搬运会让 tenant_id
静默落 null，那批文档从此对所有租户可见。已变异自证（注释掉搬运后 IT 变红）。
另有一条连续爬两个租户的用例守 finally clear（单次爬取永远是绿的）。

越权单测的区分度在两个 verify(never())：只断言抛异常的话，一个「先删完文档
再校验归属」的实现照样绿。"
```

---

## Task 7: 前端——站点配置对话框、站点列表与进度轮询

**Files:**
- Create: `frontend/src/components/knowledge/CrawlSiteDialog.tsx`
- Create: `frontend/src/components/knowledge/CrawlSiteList.tsx`
- Modify: `frontend/src/types/index.ts`（加两个类型；若类型定义在别处，按 `grep -rn "interface Document" frontend/src/types` 的结果放）
- Modify: `frontend/src/api/knowledge.ts`（加 6 个方法）
- Modify: `frontend/src/pages/KnowledgePage.tsx`（入口按钮 + 挂两个组件）

**Interfaces:**
- Consumes: Task 6 的六条端点
- Produces: 两个组件的 props 契约（见下方代码）

**背景：** `api/client.ts` 是单例 axios，响应拦截器已把 `ApiResponse.data` 拆出来；各 `api/*.ts` 都走它。`KnowledgePage.tsx` 已 493 行，站点管理必须抽成组件，否则会推到 700 行以上。UI 组件在 `components/ui/`（shadcn 风格），别名 `@/` → `src/`，但 `KnowledgePage.tsx` 现用的是相对路径 import（`../components/ui/...`），新组件跟着它的邻居写法即可。

⚠️ **不要用 `dangerouslySetInnerHTML` 渲染任何站点字段**：`entryUrl` / `contentSelector` 都是用户输入。React 默认转义，直接 `{site.entryUrl}` 就是安全的。

- [ ] **Step 1: 加类型**

在 `frontend/src/types/index.ts` 里加：

```ts
/** 爬取站点配置 */
export interface CrawlSite {
    id: number;
    entryUrl: string;
    scopePrefix: string;
    maxPages: number;
    maxDepth: number;
    contentSelector: string | null;
    syncInterval: 'MANUAL' | 'HOURLY' | 'DAILY' | 'WEEKLY';
    lastCrawledAt: string | null;
    createdAt: string;
}

/** 爬取进度。刻意没有 total——BFS 边爬边发现，总数是动态的 */
export interface CrawlProgress {
    jobId: string;
    siteId: number;
    status: 'RUNNING' | 'COMPLETED' | 'FAILED';
    crawled: number;
    created: number;
    updated: number;
    failed: number;
    maxPages: number;
    errorMessage: string | null;
    startedAt: string;
    finishedAt: string | null;
}

/** 建站 / 改配置的入参 */
export interface CrawlSiteRequest {
    entryUrl: string;
    scopePrefix?: string;
    maxPages?: number;
    maxDepth?: number;
    contentSelector?: string;
    syncInterval?: string;
}
```

- [ ] **Step 2: 加 6 个 api 方法**

在 `frontend/src/api/knowledge.ts` 的 `knowledgeService` 对象里追加（`syncWebPage` 之后）：

```ts
    // ===== 站点爬取 =====

    // 新建爬取站点并立即开爬
    addCrawlSite: (kbId: number, req: CrawlSiteRequest): Promise<ApiResponse<{ siteId: number; jobId: string }>> =>
        api.post(`/knowledge/${kbId}/crawl-sites`, req),

    // 站点列表
    listCrawlSites: (kbId: number): Promise<ApiResponse<CrawlSite[]>> =>
        api.get(`/knowledge/${kbId}/crawl-sites`),

    // 查爬取进度（内存进度，应用重启后会 404）
    getCrawlProgress: (jobId: string): Promise<ApiResponse<CrawlProgress>> =>
        api.get(`/knowledge/crawl-jobs/${jobId}`),

    // 重新爬取（复用站点配置，已存在的 URL 只做内容同步）
    recrawlSite: (siteId: number): Promise<ApiResponse<string>> =>
        api.post(`/knowledge/crawl-sites/${siteId}/recrawl`),

    // 更新站点配置
    updateCrawlSite: (siteId: number, req: CrawlSiteRequest): Promise<ApiResponse<CrawlSite>> =>
        api.put(`/knowledge/crawl-sites/${siteId}`, req),

    // 删除站点；deleteDocuments=true 时连带删除其文档与向量
    deleteCrawlSite: (siteId: number, deleteDocuments: boolean): Promise<ApiResponse<void>> =>
        api.delete(`/knowledge/crawl-sites/${siteId}?deleteDocuments=${deleteDocuments}`),
```

并把 `CrawlSite`、`CrawlProgress`、`CrawlSiteRequest` 加进文件顶部的 `import type {...}`。

- [ ] **Step 3: 建配置对话框组件**

创建 `frontend/src/components/knowledge/CrawlSiteDialog.tsx`：

```tsx
import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../ui/dialog';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import type { CrawlSite, CrawlSiteRequest } from '../../types';

/**
 * 爬取站点配置表单。建站与改配置共用。
 *
 * @author ruan
 */
interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    /** 传入则为编辑模式 */
    initial?: CrawlSite | null;
    onSubmit: (req: CrawlSiteRequest) => Promise<void>;
}

/**
 * 由入口 URL 推导范围前缀，用于表单预填。
 *
 * ⚠️ 这只是给用户的预填提示——真值由后端 SiteCrawlService.deriveScopePrefix 推导，
 * 并强制校验 host 与入口一致。前端这份不要当成权威。
 *
 * 规则：`…/help/` → 原样；`…/help/faq/a.html` → `…/help/faq/`；`…/` 或无路径 → 整站
 */
function deriveScopePrefix(entryUrl: string): string {
    try {
        const u = new URL(entryUrl);
        const path = u.pathname;
        if (!path || path === '/') return `${u.origin}/`;
        if (path.endsWith('/')) return `${u.origin}${path}`;
        return `${u.origin}${path.slice(0, path.lastIndexOf('/') + 1)}`;
    } catch {
        return '';
    }
}

export function CrawlSiteDialog({ open, onOpenChange, initial, onSubmit }: Props) {
    const [entryUrl, setEntryUrl] = useState('');
    const [scopePrefix, setScopePrefix] = useState('');
    const [prefixTouched, setPrefixTouched] = useState(false);
    const [maxPages, setMaxPages] = useState(50);
    const [maxDepth, setMaxDepth] = useState(3);
    const [contentSelector, setContentSelector] = useState('');
    const [syncInterval, setSyncInterval] = useState('DAILY');
    const [submitting, setSubmitting] = useState(false);

    // 打开时按 initial 重置；新建模式回到默认值
    useEffect(() => {
        if (!open) return;
        setEntryUrl(initial?.entryUrl ?? '');
        setScopePrefix(initial?.scopePrefix ?? '');
        setPrefixTouched(!!initial);
        setMaxPages(initial?.maxPages ?? 50);
        setMaxDepth(initial?.maxDepth ?? 3);
        setContentSelector(initial?.contentSelector ?? '');
        setSyncInterval(initial?.syncInterval ?? 'DAILY');
    }, [open, initial]);

    // 用户没手动改过前缀时，跟着入口 URL 自动推导
    const handleEntryChange = (value: string) => {
        setEntryUrl(value);
        if (!prefixTouched) setScopePrefix(deriveScopePrefix(value));
    };

    const handleSubmit = async () => {
        setSubmitting(true);
        try {
            await onSubmit({
                entryUrl: entryUrl.trim(),
                scopePrefix: scopePrefix.trim() || undefined,
                maxPages,
                maxDepth,
                contentSelector: contentSelector.trim() || undefined,
                syncInterval,
            });
            onOpenChange(false);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{initial ? '编辑爬取站点' : '爬取整个站点'}</DialogTitle>
                    <DialogDescription>
                        从入口页出发自动发现同域链接并逐页入库，每页一篇文档。遵守站点的 robots.txt，
                        并按 1 秒/页限速（站点声明 Crawl-delay 时以它为准）。
                    </DialogDescription>
                </DialogHeader>
                <div className="space-y-4">
                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">入口 URL</label>
                        <Input
                            placeholder="https://example.com/help/"
                            value={entryUrl}
                            onChange={(e) => handleEntryChange(e.target.value)}
                        />
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">爬取范围（只抓以此为前缀的地址）</label>
                        <Input
                            placeholder="https://example.com/help/"
                            value={scopePrefix}
                            onChange={(e) => { setPrefixTouched(true); setScopePrefix(e.target.value); }}
                        />
                        <p className="text-xs text-muted-foreground">
                            默认按入口地址推导：<code>/help/</code> 取整个 help 目录；
                            <code>/help/faq/a.html</code> 取 <code>/help/faq/</code>；根地址则为整站。
                            必须与入口同域。
                        </p>
                    </div>

                    <div className="grid grid-cols-2 gap-3">
                        <div className="space-y-1">
                            <label className="text-xs text-muted-foreground">最多爬取页数（上限 500）</label>
                            <Input
                                type="number" min={1} max={500} value={maxPages}
                                onChange={(e) => setMaxPages(Number(e.target.value))}
                            />
                        </div>
                        <div className="space-y-1">
                            <label className="text-xs text-muted-foreground">链接深度（上限 5）</label>
                            <Input
                                type="number" min={0} max={5} value={maxDepth}
                                onChange={(e) => setMaxDepth(Number(e.target.value))}
                            />
                        </div>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">正文选择器（可选）</label>
                        <Input
                            placeholder="article 或 .doc-content"
                            value={contentSelector}
                            onChange={(e) => setContentSelector(e.target.value)}
                        />
                        <p className="text-xs text-muted-foreground">
                            填了就只取该容器内的文字，能彻底避开用 div 做的导航栏；不填则自动剔除
                            nav/header/footer 等噪声标签。F12 找一次 class 换整站准确度。
                        </p>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">每篇文档的自动同步周期</label>
                        <select
                            className="w-full h-9 px-3 rounded-md border bg-background text-sm"
                            value={syncInterval}
                            onChange={(e) => setSyncInterval(e.target.value)}
                        >
                            <option value="MANUAL">不自动同步</option>
                            <option value="HOURLY">每小时</option>
                            <option value="DAILY">每天</option>
                            <option value="WEEKLY">每周</option>
                        </select>
                    </div>

                    <Button
                        className="w-full"
                        onClick={handleSubmit}
                        disabled={submitting || !entryUrl.trim()}
                    >
                        {initial ? '保存配置' : submitting ? '正在启动…' : '开始爬取'}
                    </Button>
                </div>
            </DialogContent>
        </Dialog>
    );
}
```

- [ ] **Step 4: 建站点列表组件**

创建 `frontend/src/components/knowledge/CrawlSiteList.tsx`：

```tsx
import { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '../ui/button';
import { knowledgeService } from '../../api/knowledge';
import type { CrawlProgress, CrawlSite, CrawlSiteRequest } from '../../types';
import { CrawlSiteDialog } from './CrawlSiteDialog';

/**
 * 爬取站点列表：重新爬取 / 编辑配置 / 删除（可选连带删文档）+ 进度轮询。
 *
 * @author ruan
 */
interface Props {
    knowledgeBaseId: number;
    /** 爬取结束后通知父组件刷新文档列表 */
    onCrawlFinished: () => void;
}

const INTERVAL_LABELS: Record<string, string> = {
    MANUAL: '不自动同步', HOURLY: '每小时', DAILY: '每天', WEEKLY: '每周',
};

export function CrawlSiteList({ knowledgeBaseId, onCrawlFinished }: Props) {
    const [sites, setSites] = useState<CrawlSite[]>([]);
    const [progress, setProgress] = useState<CrawlProgress | null>(null);
    const [editing, setEditing] = useState<CrawlSite | null>(null);
    const [dialogOpen, setDialogOpen] = useState(false);
    const timerRef = useRef<number | null>(null);

    const loadSites = useCallback(async () => {
        const res = await knowledgeService.listCrawlSites(knowledgeBaseId);
        setSites(res.data ?? []);
    }, [knowledgeBaseId]);

    useEffect(() => { void loadSites(); }, [loadSites]);

    // 组件卸载时清掉轮询定时器，避免切换知识库后仍在轮询已结束的任务
    useEffect(() => () => {
        if (timerRef.current) window.clearTimeout(timerRef.current);
    }, []);

    const poll = useCallback((jobId: string) => {
        const tick = async () => {
            try {
                const res = await knowledgeService.getCrawlProgress(jobId);
                const p = res.data;
                setProgress(p ?? null);
                if (p && p.status === 'RUNNING') {
                    timerRef.current = window.setTimeout(tick, 2000);
                    return;
                }
            } catch {
                // 进度是内存态，应用重启后会 404——停止轮询即可，已建文档不受影响
            }
            await loadSites();
            onCrawlFinished();
        };
        timerRef.current = window.setTimeout(tick, 2000);
    }, [loadSites, onCrawlFinished]);

    const handleCreate = async (req: CrawlSiteRequest) => {
        const res = await knowledgeService.addCrawlSite(knowledgeBaseId, req);
        await loadSites();
        if (res.data?.jobId) poll(res.data.jobId);
    };

    const handleUpdate = async (req: CrawlSiteRequest) => {
        if (!editing) return;
        await knowledgeService.updateCrawlSite(editing.id, req);
        setEditing(null);
        await loadSites();
    };

    const handleRecrawl = async (site: CrawlSite) => {
        const res = await knowledgeService.recrawlSite(site.id);
        if (res.data) poll(res.data);
    };

    const handleDelete = async (site: CrawlSite) => {
        const withDocs = window.confirm(
            `删除站点「${site.entryUrl}」。\n\n点「确定」= 同时删除该站点抓取的全部文档与向量（不可恢复）。\n`
            + '点「取消」后会再问一次是否只删配置、保留文档。'
        );
        if (!withDocs) {
            const keepDocs = window.confirm('只删除站点配置、保留已抓取的文档？（文档会变成普通网页文档，各自继续定期同步）');
            if (!keepDocs) return;
        }
        await knowledgeService.deleteCrawlSite(site.id, withDocs);
        await loadSites();
        onCrawlFinished();
    };

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between">
                <h4 className="text-sm font-medium">爬取站点</h4>
                <Button size="sm" variant="outline" onClick={() => { setEditing(null); setDialogOpen(true); }}>
                    爬取整个站点
                </Button>
            </div>

            {progress && (
                <div className="rounded-md border p-3 text-sm space-y-1">
                    <div className="flex items-center gap-2">
                        <span className="font-medium">
                            {progress.status === 'RUNNING' ? '正在爬取…'
                                : progress.status === 'COMPLETED' ? '爬取完成' : '爬取失败'}
                        </span>
                        <span className="text-muted-foreground">
                            已爬 {progress.crawled} / 上限 {progress.maxPages} 页
                            · 新建 {progress.created} · 更新 {progress.updated} · 失败 {progress.failed}
                        </span>
                    </div>
                    {progress.errorMessage && (
                        <p className="text-xs text-destructive">{progress.errorMessage}</p>
                    )}
                </div>
            )}

            {sites.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                    还没有爬取站点。给一个帮助中心的入口地址，就能把整个目录抓进这个知识库。
                </p>
            ) : (
                <div className="space-y-2">
                    {sites.map((site) => (
                        <div key={site.id} className="flex items-center justify-between rounded-md border p-3">
                            <div className="min-w-0 space-y-0.5">
                                <p className="text-sm truncate">{site.entryUrl}</p>
                                <p className="text-xs text-muted-foreground">
                                    范围 {site.scopePrefix} · 最多 {site.maxPages} 页 / 深度 {site.maxDepth}
                                    {site.contentSelector ? ` · 选择器 ${site.contentSelector}` : ' · 自动去噪'}
                                    {' · '}{INTERVAL_LABELS[site.syncInterval] ?? site.syncInterval}
                                    {site.lastCrawledAt ? ` · 上次爬取 ${site.lastCrawledAt.replace('T', ' ').slice(0, 16)}` : ' · 尚未爬取'}
                                </p>
                            </div>
                            <div className="flex gap-2 shrink-0">
                                <Button size="sm" variant="outline" onClick={() => void handleRecrawl(site)}>
                                    重新爬取
                                </Button>
                                <Button size="sm" variant="ghost"
                                        onClick={() => { setEditing(site); setDialogOpen(true); }}>
                                    编辑
                                </Button>
                                <Button size="sm" variant="ghost" onClick={() => void handleDelete(site)}>
                                    删除
                                </Button>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <CrawlSiteDialog
                open={dialogOpen}
                onOpenChange={(open) => { setDialogOpen(open); if (!open) setEditing(null); }}
                initial={editing}
                onSubmit={editing ? handleUpdate : handleCreate}
            />
        </div>
    );
}
```

- [ ] **Step 5: 在 KnowledgePage 里挂上**

在 `frontend/src/pages/KnowledgePage.tsx`：

1. 顶部加 `import { CrawlSiteList } from '../components/knowledge/CrawlSiteList';`
2. 找到「添加网页」`Dialog` 所在的 `CardContent`（约 336 行 `<Separator className="mb-6" />` 附近），在文档列表 tab 区域之前插入站点列表区块：

```tsx
                                {selectedKb && (
                                    <>
                                        <CrawlSiteList
                                            knowledgeBaseId={selectedKb.id}
                                            onCrawlFinished={() => void loadDocuments(selectedKb.id)}
                                        />
                                        <Separator className="my-6" />
                                    </>
                                )}
```

`loadDocuments` 是该页已有的文档加载函数——先 `grep -n "loadDocuments\|const load" frontend/src/pages/KnowledgePage.tsx` 确认它的真实名字与签名，按实际的写。若它不接收参数（用闭包里的 `selectedKb`），就写成 `onCrawlFinished={() => void loadDocuments()}`。

- [ ] **Step 6: 构建 + lint**

```bash
cd frontend && pnpm build
```
预期：`tsc -b` 与 `vite build` 都通过。

```bash
cd frontend && pnpm lint
```
预期：**新增文件零新 error**。该项目 lint 有既有基线告警（约 71 条），只需确认没有新增来自本任务文件的错误：

```bash
cd frontend && pnpm lint 2>&1 | grep -E "knowledge/CrawlSite|api/knowledge|KnowledgePage" || echo "本任务文件无 lint 问题"
```

- [ ] **Step 7: 提交**

```bash
git add frontend/src/components/knowledge/ frontend/src/api/knowledge.ts \
        frontend/src/types/index.ts frontend/src/pages/KnowledgePage.tsx
git commit -m "feat(frontend): 爬取站点配置对话框与站点列表

抽到 components/knowledge/ 两个新组件——KnowledgePage 已 493 行，站点管理
再堆进去会推到 700 行以上。

- CrawlSiteDialog：建站与改配置共用；范围前缀跟着入口 URL 自动推导，用户手动
  改过之后不再覆盖。⚠️前端这份推导只是预填提示，真值由后端推导并强制校验同域
- CrawlSiteList：重新爬取 / 编辑 / 删除（二次确认区分「连带删文档」与「只删配置」）
  + 2 秒轮询进度；进度是内存态，应用重启后 404 属预期，静默停止轮询即可
- 进度按「已爬 N / 上限 M 页」展示——BFS 边爬边发现，没有确定的总数

全部字段用 JSX 插值渲染，不用 dangerouslySetInnerHTML（entryUrl 与选择器都是
用户输入）。"
```

---

## Task 8: 文档收尾——CLAUDE.md 架构要点与手工验证项、规划标记

**Files:**
- Modify: `CLAUDE.md`
- Modify: `SaaS平台规划.md`

**Interfaces:**
- Consumes: 前七个任务的全部产出
- Produces: 无代码产出

**背景：** `CLAUDE.md` 是每个会话自动载入的项目指令。它已有「网站知识库」架构要点与「改动网站知识库同步链路后必须手工验证」三条。本任务把第二期的内容并进去，**不新开一节**——同一条链路的知识分散在两处会让后人只读到一半。

- [ ] **Step 1: 扩写 CLAUDE.md 的架构要点**

找到「### 网站知识库（`service/web/` + `scheduler/WebPageSyncScheduler`）」一节，在其末尾（`processDocument` 那条之后）追加：

```markdown
- **站点爬取（第二期）**：`CrawlSite` 表存站点配置（入口/范围前缀/页数/深度/正文选择器/同步周期），`Document.crawl_site_id` 可空外键指向它（单页手动添加的为 null）。`SiteCrawlService` 做 BFS 编排：`LinkExtractor`（纯函数，规范化 + 范围判定 + 抽链接）→ `RobotsChecker`（robots.txt，取不到按允许）→ 逐页建档摄取。新页面靠**手动重爬**发现，不做定期自动重爬（内容更新已由周期同步覆盖，页面消失沿用既有熔断）。
- ⚠️ **`LinkExtractor.inScope` 三个 return 分支各挡一个边界，不能简化成裸 `startsWith`**：规范化会去掉末尾斜杠而前缀常带斜杠（裸判会把**入口页自己**排除，爬取一开始就空转）；拿去斜杠的前缀判又会把 `/helpdesk` 当成 `/help` 的子页整个爬进来；前缀是根路径时补斜杠会拼出 `//` 一条都匹配不上。
- ⚠️ **`FetchResult.dom` 是剔噪前 `clone()` 的副本**：`extractFrom` 就地移除 `nav`/`header`/`footer`，而帮助中心的目录导航恰在 `nav` 里。交出残树的后果是链接发现几乎抓不到东西且**不报错**，表现为「爬了 1 页就结束」——与上面那三个边界的表象完全一致，排查时要分别验证。
- ⚠️ **爬取跑在 `@Async` 线程，四个 `TenantContext` 值必须由请求线程传入、`finally` 必须 clear**：不搬运则新建文档的 `tenant_id`/`app_id` 静默落 null（那批文档从此对所有租户可见）；不清理则泄漏给下一个复用该线程的任务（默认 `applicationTaskExecutor` core 8、线程复用，流式聊天改造时实测复现过平台管理员会话落进别人租户）。**新增在 `@Async` 里建实体的代码一律照此处理。**
- ⚠️ **`maxPages` 计「已尝试抓取」而非成功页数**，被 robots 拒绝的不计（没发请求）。计成功数的话，一个到处 404 的站点会让队列不断展开、永远碰不到上限。
- ⚠️ **爬取新建文档同样遵守「摄取成功才落 `contentHash`」**，且必须设 `lastSyncedAt`——不设会让 `findDueWebPageIds` 因 `last_synced_at IS NULL` 立刻判为到期，爬完 50 篇后下一轮调度把它们全部重抓一遍。
- **站点级互斥用内存 map，不能用第一期那把 `pg_try_advisory_xact_lock`**：后者是事务级的，与「几分钟长任务 + 逐页提交」直接冲突。多实例下内存互斥失效，最坏是重复文档——刻意**不加** `(kb_id, source_url)` 唯一约束兜底，因为第一期 `addWebPage` 无去重校验、存量库可能已有重复 URL，`ddl-auto: update` 建唯一索引会让应用起不来。
- ⚠️ **删站点勾选删文档时必须逐篇走 `knowledgeService.deleteDocument`**，不能 `repository.deleteAll`——只删库行会留下再也没有库行指向、**却仍参与检索**的孤儿向量（继续被命中并拼进 Prompt，而界面上已看不到这些文档）。同附件孤儿清理必须连 S3 一起删。
- **重爬时已存在的页会被抓两次**（一次为提链接、一次在 `syncDocument` 内部）。这是刻意的取舍：抽取 `syncDocument` 的三分支能省下这些请求，但那段代码的每条注释都是一个真实缺陷的墓碑。触发重新考虑的条件写在 `docs/superpowers/specs/2026-08-06-site-crawl-design.md` §5.2。
```

- [ ] **Step 2: 扩写手工验证项**

找到「### 改动网站知识库同步链路后必须手工验证」一节，把标题改成「### 改动网站知识库同步 / 站点爬取链路后必须手工验证」，并在既有三条之后追加：

```markdown

**站点爬取（第二期）另有七条，每条都是自动化测试覆盖不到的：**
4. **范围真的收窄**：给一个 `/help/` 入口，确认没有爬到 `/blog/` 或外域。
5. **选择器真的生效**：配与不配各爬一次，`SELECT content FROM document WHERE id=?` **人工读一遍**有没有导航混进来。这是启发式与选择器唯一的验收方式。
6. **租户字段非 NULL**：用**租户管理员**登录爬，确认 `tenant_id`/`app_id` 落库正确。⚠️ 与「平台管理员建档 `tenant_id` 落 NULL 是既有行为」区分——第一期真机冒烟已把这条既有行为误判成缺陷一次。
7. **上下文不泄漏**：连续爬**两个不同租户**的站点，确认第二个的文档租户字段仍正确。这是第 6 条的另一半，**只有真机连做两次才暴露**，单次爬取永远是绿的。
8. **重爬幂等**：第二次爬同一站点，`document` 行数只增新页、`vector_store` 不因重复建档暴涨。
9. **robots 真被遵守**：找一个有 `Disallow` 的站点，确认那些路径没进库。
10. **限速真的生效**：看日志时间戳确认相邻请求间隔 ≥ 1 秒（或站点声明的 `Crawl-delay`）。限速失效不会报错，只会让平台被目标站点封 IP。
```

- [ ] **Step 3: 更新规划文档**

在 `SaaS平台规划.md` 里：

1. 「### 5.2 中期」小节的开头插入一条已完成项（放在 MCP 接入之前）：

```markdown
- [x] **站点爬取（网站知识库第二期）**
  落地：`CrawlSite` 表存站点配置 + `SiteCrawlService` BFS 编排（`LinkExtractor` 纯函数做规范化/范围判定/抽链接，`RobotsChecker` 遵守 robots.txt 与 Crawl-delay）+ 正文 CSS 选择器（白名单，解决第一期启发式对 `<div>` 导航站点无效的已知局限）。默认 50 页/深度 3，硬顶 500/5。新页面靠手动重爬发现（内容更新仍由第一期周期同步覆盖，页面消失沿用既有熔断），故不做定期自动重爬。删站点默认保留文档。
  ⚠️ 已知取舍：重爬时已存在的页会被抓两次（避免动 `syncDocument` 那段注释最密的代码）；站点级互斥是单实例内存态。详见 `docs/superpowers/specs/2026-08-06-site-crawl-design.md` 与 CLAUDE.md 的十条要点。
```

2. 「## 当前状态」的「**待办**」列表里，把 Phase 5.2 那一条改成：

```markdown
- **Phase 5.2 中期**——站点爬取已完成（2026-08-06）；剩 MCP 接入、OpenAPI Schema 自动生成工具表单、多智能体编排（需先定产品形态）、消息已读/送达状态，见 5.2 小节。
```

3. 同一节的「**Phase 5.1 近期三项已全部合入**」段落之后加一行：

```markdown
**Phase 5.2 首项已合入**（2026-08-06）：站点爬取（网站知识库第二期）。
```

- [ ] **Step 4: 确认文档改动无笔误**

```bash
cd /d/Code/m/qingyu-care && git diff --stat CLAUDE.md SaaS平台规划.md
```
然后 `git diff CLAUDE.md` 通读一遍，确认 Markdown 结构没被破坏（列表缩进、代码围栏配对）。

- [ ] **Step 5: 提交**

```bash
git add CLAUDE.md SaaS平台规划.md
git commit -m "docs: 站点爬取收官——CLAUDE.md 架构要点与七条手工验证项、规划标记

架构要点并进既有「网站知识库」一节而非新开一节——同一条链路的知识分散
两处会让后人只读到一半。十条要点里五条是真机才能发现的坑：inScope 三边界、
FetchResult.dom 必须是剔噪前副本、@Async 租户上下文搬运与清理、maxPages 计
尝试数、删站点必须走 deleteDocument。

手工验证项七条，其中「连续爬两个不同租户」那条只有真机连做两次才暴露，
单次爬取永远是绿的。"
```

---

## 计划自查

**1. spec 覆盖检查**

| spec 章节 | 落在哪个任务 |
|:---|:---|
| §2.1 手动重爬 | Task 5（`recrawl`）、Task 6（端点）、Task 7（按钮） |
| §2.2 规模默认与硬顶 | Task 1（`SiteCrawlProperties`）、Task 5（`clamp` + 测试） |
| §2.3 正文选择器白名单 | Task 3（`extractBySelector` + 回退） |
| §2.4 不持久化任务 | Task 5（内存 `Progress`） |
| §2.5 删除默认不删文档 | Task 5（`deleteSite`）、Task 6（IT 两条）、Task 7（二次确认） |
| §3.1 `CrawlSite` 实体 | Task 1 |
| §3.2 `Document` 外键 | Task 1 |
| §3.3 前缀推导规则 | Task 5（`deriveScopePrefix` + 测试）、Task 7（前端预填） |
| §4.1 `LinkExtractor`（含 inScope 三边界） | Task 2 |
| §4.2 `RobotsChecker` | Task 4 |
| §4.3 `SiteCrawlService` | Task 5 |
| §4.4 `WebPageFetcher` 与 `syncDocument` 改动 | Task 3 |
| §5 执行流程（含 5.1/5.2/5.3） | Task 5 |
| §6.1 租户上下文 | Task 5（单测）+ Task 6（IT + 变异自证） |
| §6.2 互斥与并发上限 | Task 5 |
| §6.3 不用事务级咨询锁 | Task 5（类注释） |
| §6.4 剔噪前 DOM | Task 3 |
| §7.1 六个端点 | Task 6 |
| §7.2 前端两组件 | Task 7 |
| §8 配置项 | Task 1 |
| §9 测试策略 | 分散在各任务的测试步骤 |
| §9.4 手工验证项 | Task 8（写进 CLAUDE.md） |

无遗漏。

**2. 类型与签名一致性**

- `FetchResult` 五个组件在 Task 3 定义，Task 5 与 Task 6 的桩数据都按五参构造 ✓
- `findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc` 在 Task 1 定义，Task 5 调用、Task 5 测试打桩，三处同名 ✓
- `downloadRaw` 在 Task 3 提升为 public，Task 4 的 `RobotsChecker.fetch` 与其测试打桩一致 ✓
- `Downloaded` 在 Task 3 提升为 public，Task 4/5 的测试直接 `new WebPageFetcher.Downloaded(...)` ✓
- `Progress` 字段名（`crawled`/`created`/`updated`/`failed`/`maxPages`/`status`/`errorMessage`）在 Task 5 定义，Task 6 的 `CrawlProgressDTO.from` 与 Task 7 的 TS 接口逐一对应 ✓
- `newProgressForTest` / `markCrawlingForTest` 两个窄接口在 Task 5 定义，Task 5 单测与 Task 6 的 IT 都用它 ✓
- `CrawlStartResponse(siteId, jobId)` 在 Task 5 定义，Task 6 端点返回、Task 7 的 TS 类型 `{siteId, jobId}` 对应 ✓

**3. 执行顺序依赖**

Task 1（实体/配置）→ Task 2（纯函数，无依赖，可与 1 并行但按顺序更简单）→ Task 3（`WebPageFetcher`，需要 Task 1 的 `CrawlSite` 才能改 `syncDocument`）→ Task 4（需要 Task 3 的 `downloadRaw`）→ Task 5（需要 1-4 全部）→ Task 6（需要 5）→ Task 7（需要 6）→ Task 8（需要全部）。**必须串行执行。**

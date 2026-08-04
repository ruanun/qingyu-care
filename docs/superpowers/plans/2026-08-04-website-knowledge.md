# 网站知识库（URL 抓取 + 定期同步）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让运营能把官网 FAQ / 帮助中心页面加进知识库，并按配置的周期自动重抓同步，内容没变时不重复嵌入。

**Architecture:** 复用现有 `Document` 实体（`DocumentType.URL` 枚举早已占位）加 5 个字段。新增 `WebPageFetcher`（只做「URL → 干净正文」，不碰数据库）与 `WebPageSyncService`（编排落库与摄取），抓取走既有 `SsrfGuard` 的钉 IP 硬门槛，正文提取用 Jsoup 启发式剔除噪声，变更检测靠正文的 SHA-256。定时同步复用既有 scheduler 范式，但**查询必须用原生 SQL 绕过租户过滤器**。

**Tech Stack:** Spring Boot 4.1 / JDK 17 / Jsoup（新增）/ httpclient5（已有）/ Apache Tika（已有，本次不用）/ JUnit5 + Mockito + Testcontainers / React 19 + Vite

设计依据：`docs/superpowers/specs/2026-08-04-website-knowledge-design.md`

## Global Constraints

- 全部使用简体中文：代码注释、commit 信息；新建 Java 文件 javadoc 作者写 `@author ruan`。
- JDK 17。后端新增单测优先写成纯 Mockito 单测（Surefire，免 Docker）；`*IT.java` 放 `it/` 包、继承 `support/AbstractPostgresIT`（Failsafe，需 Docker Desktop）。
- ⚠️ **命名冲突**：Jsoup 的 `org.jsoup.nodes.Document` 与本项目实体 `com.qingyu.care.entity.Document` 同名。在同时出现两者的文件里**必须用全限定名**区分，别 import 混。
- ⚠️ **`@Builder` 会忽略字段初值**：`Document` 用了 Lombok `@Builder`，带默认值的新字段必须加 `@Builder.Default`，否则 builder 建出来是 null，读取时 NPE。本项目在 `ChatSession.noHitStreak` 上踩过这个坑。
- ⚠️ **新增列必须可空**：`ddl-auto: update` 对存量行加 NOT NULL 列会导致启动失败。列可空 + 读取时 null 归默认值。
- fail-soft 铁律：抓取失败只记录不抛穿，绝不清空既有内容与向量。
- 包管理器一律 **pnpm**。前端仓库**没有测试框架，不要引入**；lint 基线 **69 errors / 0 warnings**，新代码不得新增（别写 `any`）。
- 在当前分支提交；**未获授权不 `git push`**。

---

### Task 1: 数据模型（`SyncInterval` 枚举 + `Document` 扩展）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/enums/SyncInterval.java`
- Modify: `backend/src/main/java/com/qingyu/care/entity/Document.java`
- Test: `backend/src/test/java/com/qingyu/care/enums/SyncIntervalTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - `enum SyncInterval { MANUAL, HOURLY, DAILY, WEEKLY }`，方法 `Duration getInterval()`（`MANUAL` 返回 null）、`boolean isAutomatic()`
  - `Document` 新增 getter/setter：`getSourceUrl/setSourceUrl`、`getContentHash/setContentHash`、`getSyncInterval/setSyncInterval`、`getLastSyncedAt/setLastSyncedAt`、`getSyncFailureCount/setSyncFailureCount`
  - Task 2–5 全部依赖这些。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/qingyu/care/enums/SyncIntervalTest.java`：

```java
package com.qingyu.care.enums;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：同步周期枚举的间隔换算与自动调度判定。
 *
 * @author ruan
 */
class SyncIntervalTest {

    @Test
    void 各周期的间隔时长正确() {
        assertThat(SyncInterval.HOURLY.getInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(SyncInterval.DAILY.getInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(SyncInterval.WEEKLY.getInterval()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void MANUAL 不参与自动调度() {
        assertThat(SyncInterval.MANUAL.getInterval()).isNull();
        assertThat(SyncInterval.MANUAL.isAutomatic()).isFalse();
    }

    @Test
    void 其余周期都参与自动调度() {
        assertThat(SyncInterval.HOURLY.isAutomatic()).isTrue();
        assertThat(SyncInterval.DAILY.isAutomatic()).isTrue();
        assertThat(SyncInterval.WEEKLY.isAutomatic()).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=SyncIntervalTest`
Expected: 编译失败，`SyncInterval` 不存在。

- [ ] **Step 3: 实现枚举**

```java
package com.qingyu.care.enums;

import java.time.Duration;

/**
 * 网页文档的自动同步周期。{@code MANUAL} 表示不参与定时调度、只能手动触发。
 *
 * @author ruan
 */
public enum SyncInterval {

    MANUAL(null),
    HOURLY(Duration.ofHours(1)),
    DAILY(Duration.ofDays(1)),
    WEEKLY(Duration.ofDays(7));

    private final Duration interval;

    SyncInterval(Duration interval) {
        this.interval = interval;
    }

    /** 自动同步间隔；MANUAL 返回 null。 */
    public Duration getInterval() {
        return interval;
    }

    /** 是否参与定时调度。 */
    public boolean isAutomatic() {
        return interval != null;
    }
}
```

- [ ] **Step 4: 给 `Document` 加 5 个字段**

在 `Document.java` 的 `updatedAt` 字段之前插入（注意 `@Builder.Default` 与列可空，理由见 Global Constraints）：

```java
    /** 网页文档的源地址（仅 documentType=URL 有值）；长度上限与前端 safeUrl 一致。 */
    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    /** 正文的 SHA-256，用于同步时判断内容是否变化——没变就不重嵌，省 embedding 成本。 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** 自动同步周期；null 或 MANUAL 表示不参与定时调度。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_interval", length = 20)
    private SyncInterval syncInterval;

    /** 上次「成功」同步时间；抓取失败不更新，故它同时是「内容新鲜度」的下界。 */
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /**
     * 连续同步失败次数，任何一次成功清零。
     * 列可空：ddl-auto=update 对存量行加 NOT NULL 列会导致启动失败；读取时 null 归 0。
     * @Builder.Default 不可省：Lombok @Builder 会忽略字段初值，漏了它 builder 建出来就是 null。
     */
    @Builder.Default
    @Column(name = "sync_failure_count")
    private Integer syncFailureCount = 0;
```

补 import：`com.qingyu.care.enums.SyncInterval`、`jakarta.persistence.EnumType`、`jakarta.persistence.Enumerated`（若文件里尚无）。

- [ ] **Step 5: 运行测试并确认应用能启动**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=SyncIntervalTest && mvn test`
Expected: 新测试 3 个绿；全量单测无回归（基线 436 → 439）。

> 建列由 `ddl-auto: update` 在启动时自动完成，本任务不需要写迁移 SQL。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/enums/SyncInterval.java \
        backend/src/main/java/com/qingyu/care/entity/Document.java \
        backend/src/test/java/com/qingyu/care/enums/SyncIntervalTest.java
git commit -m "feat(backend): 网页文档的同步周期枚举与 Document 字段扩展"
```

---

### Task 2: `WebPageFetcher`（抓取 + SSRF + 正文提取）

**Files:**
- Modify: `backend/pom.xml`（新增 Jsoup 依赖）
- Create: `backend/src/main/java/com/qingyu/care/service/web/WebPageFetcher.java`
- Test: `backend/src/test/java/com/qingyu/care/service/web/WebPageFetcherTest.java`

**Interfaces:**
- Consumes: `com.qingyu.care.config.SsrfGuard.resolveAndValidate(String host)` → `InetAddress[]`（既有）
- Produces:
  - `WebPageFetcher.FetchResult`：`record FetchResult(String title, String content, String contentHash, int httpStatus)`
  - `FetchResult fetch(String url)` —— 失败抛 `BusinessException`
  - `static String extractFrom(String html, String baseUrl)` —— 从 HTML 提取干净正文（package-private 静态，便于免网络单测）
  - `static String extractTitle(String html, String fallback)`
  - `static String sha256(String text)`
  - Task 3 依赖 `fetch` 与 `FetchResult`。

**这个组件为什么这么拆**：网络下载与 HTML 解析分开——`extractFrom` / `extractTitle` / `sha256` 是纯静态函数，不需要起 HTTP 服务就能测「导航栏有没有被剔掉」；`fetch` 只负责把「安全下载」这一段串起来。

- [ ] **Step 1: 加 Jsoup 依赖**

`backend/pom.xml` 的 `<dependencies>` 中，在 httpclient5 依赖之后加入：

```xml
        <!-- Jsoup：网页正文提取（剔除导航/页脚等噪声）。轻量、无传递依赖。 -->
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
            <version>1.18.3</version>
        </dependency>
```

Run: `cd D:\Code\m\qingyu-care\backend && mvn -q dependency:resolve 2>&1 | tail -5`
Expected: 无报错（能下载到 jsoup）。

- [ ] **Step 2: 写失败的测试**

`backend/src/test/java/com/qingyu/care/service/web/WebPageFetcherTest.java`：

```java
package com.qingyu.care.service.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：网页正文提取的噪声剔除、标题回退与内容哈希。
 * 这三个都是静态纯函数，不发网络请求，可直接调用（同 ChatServiceImpl#meterContentStream 惯例）。
 *
 * @author ruan
 */
class WebPageFetcherTest {

    private static final String HTML = """
            <html>
              <head><title>退货政策 - 示例商城</title></head>
              <body>
                <nav>首页 商品 关于我们 联系我们</nav>
                <header>示例商城 购物车(0)</header>
                <aside>推荐商品 热销榜</aside>
                <script>var gtag = function(){};</script>
                <style>.hidden{display:none}</style>
                <main>
                  <h1>退货政策</h1>
                  <p>自购买之日起七天内可无理由退货。</p>
                </main>
                <footer>© 2026 示例商城 京ICP备12345号</footer>
              </body>
            </html>
            """;

    @Test
    void 剔除导航页脚侧边栏与脚本样式() {
        String content = WebPageFetcher.extractFrom(HTML, "https://example.com/policy");

        assertThat(content).contains("退货政策").contains("七天内可无理由退货");
        assertThat(content).doesNotContain("购物车");
        assertThat(content).doesNotContain("关于我们");
        assertThat(content).doesNotContain("热销榜");
        assertThat(content).doesNotContain("京ICP备");
        assertThat(content).doesNotContain("gtag");
        assertThat(content).doesNotContain("display:none");
    }

    @Test
    void 标题优先取title标签() {
        assertThat(WebPageFetcher.extractTitle(HTML, "https://example.com/policy"))
                .isEqualTo("退货政策 - 示例商城");
    }

    @Test
    void 无title时回退取首个h1() {
        String html = "<html><body><h1>帮助中心</h1><p>正文</p></body></html>";
        assertThat(WebPageFetcher.extractTitle(html, "https://example.com/help")).isEqualTo("帮助中心");
    }

    @Test
    void title与h1都无时回退用URL() {
        String html = "<html><body><p>只有正文</p></body></html>";
        assertThat(WebPageFetcher.extractTitle(html, "https://example.com/x")).isEqualTo("https://example.com/x");
    }

    @Test
    void 内容哈希对同一内容稳定对不同内容不同() {
        String a = WebPageFetcher.sha256("退货政策：七天无理由");
        String b = WebPageFetcher.sha256("退货政策：七天无理由");
        String c = WebPageFetcher.sha256("退货政策：十五天无理由");

        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void 空body不抛异常返回空串() {
        assertThat(WebPageFetcher.extractFrom("<html></html>", "https://example.com")).isEmpty();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=WebPageFetcherTest`
Expected: 编译失败，`WebPageFetcher` 不存在。

- [ ] **Step 4: 实现 `WebPageFetcher`**

```java
package com.qingyu.care.service.web;

import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * 网页抓取器：把一个 URL 变成「干净正文 + 标题 + 内容哈希」，不碰数据库。
 *
 * <p>安全：出站前经 {@link SsrfGuard} 解析校验，并把校验期的 IP 钉入连接期（同
 * {@code RestClientHttpToolExecutor} 的做法），杜绝 DNS rebinding；硬关重定向跟随，
 * 否则 302 能把抓取从公网带进内网。另有超时、响应体大小上限与 Content-Type 白名单。
 *
 * <p>正文提取用 Jsoup 启发式剔除导航/页脚等噪声——网页不比 PDF，这些文字混进知识库
 * 会稀释检索相似度。
 *
 * @author ruan
 */
@Component
@Slf4j
public class WebPageFetcher {

    private static final int TIMEOUT_MS = 10_000;
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("text/html", "text/plain");

    /** 噪声容器：这些标签下的文字不属于正文。 */
    private static final String NOISE_SELECTOR =
            "script, style, nav, header, footer, aside, noscript, iframe, form";

    public record FetchResult(String title, String content, String contentHash, int httpStatus) {}

    public FetchResult fetch(String url) {
        String html = download(url);
        String content = extractFrom(html, url);
        if (content.isBlank()) {
            throw new BusinessException("网页正文为空，可能是需要 JS 渲染的页面: " + url);
        }
        return new FetchResult(extractTitle(html, url), content, sha256(content), 200);
    }

    /** 安全下载：SSRF 钉 IP + 禁重定向 + 超时 + 限量 + Content-Type 白名单。 */
    private String download(String url) {
        final String host = URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException("URL 缺少主机名: " + url);
        }
        final InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);

        // 连接期只对该 host 返回已校验地址，其它 host 一律拒绝（fail-closed）
        DnsResolver pinnedResolver = new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String h) throws UnknownHostException {
                if (!host.equalsIgnoreCase(h)) {
                    throw new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + h);
                }
                return pinned;
            }
        };

        Timeout timeout = Timeout.ofMilliseconds(TIMEOUT_MS);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(timeout).setSocketTimeout(timeout).build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedResolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build();

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build()) {

            RestClient client = RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                    .build();

            return client.get().uri(url).exchange((request, response) -> {
                MediaType contentType = response.getHeaders().getContentType();
                if (contentType == null
                        || !ALLOWED_TYPES.contains(contentType.getType() + "/" + contentType.getSubtype())) {
                    throw new BusinessException("不支持的内容类型（只接受 HTML/纯文本）: " + contentType);
                }
                Charset charset = contentType.getCharset() != null
                        ? contentType.getCharset() : StandardCharsets.UTF_8;
                return new String(readLimited(response.getBody()), charset);
            });
        } catch (IOException e) {
            throw new BusinessException("网页抓取失败: " + e.getMessage());
        }
    }

    /** 边读边计数，超过上限立即中断——不能先全读进内存再判断。 */
    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BYTES) {
                throw new BusinessException("网页内容超过 5MB 上限");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 从 HTML 提取正文：先剔噪声容器，再取 body 文本。
     * static 免容器单测（同 ChatServiceImpl#meterContentStream 惯例）。
     * 注意 Jsoup 的 Document 与本项目实体 Document 同名，此处用全限定名区分。
     */
    static String extractFrom(String html, String baseUrl) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html, baseUrl);
        doc.select(NOISE_SELECTOR).remove();
        return doc.body() == null ? "" : doc.body().text();
    }

    /** 标题：优先 title 标签，其次首个 h1，都没有则回退用 URL。 */
    static String extractTitle(String html, String fallback) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        String title = doc.title();
        if (!title.isBlank()) {
            return title;
        }
        org.jsoup.nodes.Element h1 = doc.selectFirst("h1");
        return h1 != null && !h1.text().isBlank() ? h1.text() : fallback;
    }

    /** 正文的 SHA-256（小写十六进制，64 字符）。 */
    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("计算内容哈希失败", e);
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=WebPageFetcherTest`
Expected: 6 个测试全绿。

> `fetch` 本身依赖真实网络，不写单测；它的安全属性（SSRF 拒绝内网、限量、Content-Type）由 `SsrfGuard` 既有测试 + Task 6 的冒烟覆盖。

- [ ] **Step 6: 提交**

```bash
git add backend/pom.xml \
        backend/src/main/java/com/qingyu/care/service/web/WebPageFetcher.java \
        backend/src/test/java/com/qingyu/care/service/web/WebPageFetcherTest.java
git commit -m "feat(backend): 网页抓取器（SSRF 钉 IP + Jsoup 正文提取 + 内容哈希）"
```

---

### Task 3: `WebPageSyncService`（落库与摄取编排）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/web/WebPageSyncService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/web/WebPageSyncServiceTest.java`

**Interfaces:**
- Consumes: `WebPageFetcher.fetch(String)` → `FetchResult(title, content, contentHash, httpStatus)`（Task 2）；`KnowledgeService.reprocessDocument(Long)`（既有）；`SyncInterval`（Task 1）
- Produces:
  - `Document addWebPage(Long knowledgeBaseId, String url, SyncInterval interval)`
  - `void syncDocument(Long documentId)`
  - Task 4（调度器）与 Task 5（控制器）依赖这两个。

**核心是 `syncDocument` 的三条分支**（spec §4.2）：内容未变只更新时间戳、变了才重嵌、失败保留旧内容。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/qingyu/care/service/web/WebPageSyncServiceTest.java`：

```java
package com.qingyu.care.service.web;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：网页同步的三条分支——内容未变不重嵌、变了才重嵌、失败保留旧内容。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WebPageSyncServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock WebPageFetcher webPageFetcher;
    @InjectMocks WebPageSyncService service;

    private Document urlDoc(String hash) {
        Document doc = Document.builder()
                .sourceUrl("https://example.com/faq")
                .contentHash(hash)
                .content("旧正文")
                .syncInterval(SyncInterval.DAILY)
                .syncFailureCount(3)
                .build();
        doc.setId(1L);
        return doc;
    }

    @Test
    void 内容未变时不重嵌只更新同步时间() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("标题", "新抓到的正文", "HASH_A", 200));

        service.syncDocument(1L);

        // 这条是本特性最容易悄悄退化的地方：退化成「每次都重嵌」时功能完全正常，
        // 只是每轮同步都白烧一次 embedding 的钱，没有任何现象会提醒
        verify(knowledgeService, never()).reprocessDocument(anyLong());
        assertThat(doc.getContent()).isEqualTo("旧正文");   // 未被覆盖
        assertThat(doc.getLastSyncedAt()).isNotNull();
        assertThat(doc.getSyncFailureCount()).isZero();     // 成功清零
    }

    @Test
    void 内容变了才重嵌并更新正文与哈希() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("新标题", "改版后的正文", "HASH_B", 200));

        service.syncDocument(1L);

        verify(knowledgeService).reprocessDocument(1L);
        assertThat(doc.getContent()).isEqualTo("改版后的正文");
        assertThat(doc.getContentHash()).isEqualTo("HASH_B");
        assertThat(doc.getName()).isEqualTo("新标题");
        assertThat(doc.getSyncFailureCount()).isZero();
    }

    @Test
    void 抓取失败时保留旧内容与旧哈希只累加失败计数() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("网页抓取失败: 502"));

        service.syncDocument(1L);   // 不应抛穿

        verify(knowledgeService, never()).reprocessDocument(anyLong());
        assertThat(doc.getContent()).isEqualTo("旧正文");      // 旧内容仍在服务
        assertThat(doc.getContentHash()).isEqualTo("HASH_A");
        assertThat(doc.getSyncFailureCount()).isEqualTo(4);    // 3 → 4
        assertThat(doc.getErrorMessage()).contains("502");
        assertThat(doc.getLastSyncedAt()).isNull();            // 失败不更新成功时间
    }

    @Test
    void 连续失败十次后自动转为手动同步() {
        Document doc = urlDoc("HASH_A");
        doc.setSyncFailureCount(9);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("404"));

        service.syncDocument(1L);

        assertThat(doc.getSyncFailureCount()).isEqualTo(10);
        assertThat(doc.getSyncInterval()).isEqualTo(SyncInterval.MANUAL);
        assertThat(doc.getErrorMessage()).contains("已停止自动同步");
    }

    @Test
    void 失败计数为null的存量行按0处理不NPE() {
        Document doc = urlDoc("HASH_A");
        doc.setSyncFailureCount(null);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("超时"));

        service.syncDocument(1L);

        assertThat(doc.getSyncFailureCount()).isEqualTo(1);
    }

    @Test
    void 非URL类型的文档拒绝同步() {
        Document doc = Document.builder().sourceUrl(null).build();
        doc.setId(2L);
        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));

        service.syncDocument(2L);

        verify(webPageFetcher, never()).fetch(anyString());
        verify(knowledgeService, never()).reprocessDocument(anyLong());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=WebPageSyncServiceTest`
Expected: 编译失败，`WebPageSyncService` 不存在。

- [ ] **Step 3: 实现服务**

```java
package com.qingyu.care.service.web;

import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 网页文档的建档与同步编排。抓取本身在 {@link WebPageFetcher}，本类只管「结果怎么落到知识库」。
 *
 * <p>三条分支（见设计文档 §4.2）：内容未变只更新时间戳（不重嵌，省 embedding 成本）；
 * 内容变了才走既有 {@code reprocessDocument} 重切重嵌；抓取失败保留旧内容与旧向量，
 * 只记错误与失败计数——官网临时 502 不该让知识库瞬间失去这篇内容。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebPageSyncService {

    /** 连续失败到此次数后自动转手动，避免死链每小时重试一年。 */
    private static final int MAX_FAILURES = 10;

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeService knowledgeService;
    private final WebPageFetcher webPageFetcher;

    /**
     * 建档 + 异步首次抓取。同步返回是为了让「添加」按钮立刻有反馈，
     * 沿用 uploadDocument 的范式（同步建档 PENDING，抓取与摄取走异步）。
     */
    @Transactional
    public Document addWebPage(Long knowledgeBaseId, String url, SyncInterval interval) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new com.qingyu.care.exception.ResourceNotFoundException(
                        "知识库", String.valueOf(knowledgeBaseId)));

        Document doc = Document.builder()
                .knowledgeBase(kb)
                .name(url)                       // 首次抓取成功后会被网页标题覆盖
                .originalFilename(url)
                .documentType(DocumentType.URL)
                .status(DocumentStatus.PENDING)
                .sourceUrl(url)
                .syncInterval(interval == null ? SyncInterval.MANUAL : interval)
                .build();
        doc = documentRepository.save(doc);

        syncDocumentAsync(doc.getId());
        return doc;
    }

    /** 异步入口：手动「立即同步」用它，立刻返回、前端轮询文档状态看结果。 */
    @Async
    public void syncDocumentAsync(Long documentId) {
        syncDocument(documentId);
    }

    /**
     * 同步一篇网页文档。整体 fail-soft：任何失败都只记录在文档上，不抛穿——
     * 调度器批量调用时，一篇失败不能中断其余。
     */
    @Transactional
    public void syncDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("网页同步跳过：文档不存在 id={}", documentId);
            return;
        }
        if (doc.getSourceUrl() == null || doc.getSourceUrl().isBlank()) {
            log.warn("网页同步跳过：文档非网页类型 id={}", documentId);
            return;
        }

        try {
            WebPageFetcher.FetchResult result = webPageFetcher.fetch(doc.getSourceUrl());
            doc.setSyncFailureCount(0);
            doc.setErrorMessage(null);
            doc.setLastSyncedAt(LocalDateTime.now());

            if (result.contentHash().equals(doc.getContentHash())) {
                // 内容未变：不重嵌。这是省 embedding 成本的关键分支
                documentRepository.save(doc);
                log.debug("网页内容未变，跳过重嵌 id={} url={}", documentId, doc.getSourceUrl());
                return;
            }

            doc.setName(result.title());
            doc.setContent(result.content());
            doc.setContentHash(result.contentHash());
            documentRepository.save(doc);
            knowledgeService.reprocessDocument(documentId);
            log.info("网页内容已更新并重新嵌入 id={} url={}", documentId, doc.getSourceUrl());

        } catch (Exception e) {
            // 保留旧内容与旧向量：官网临时故障不该让这篇知识消失
            int failures = (doc.getSyncFailureCount() == null ? 0 : doc.getSyncFailureCount()) + 1;
            doc.setSyncFailureCount(failures);
            String message = "同步失败(" + failures + "/" + MAX_FAILURES + "): " + e.getMessage();
            if (failures >= MAX_FAILURES) {
                doc.setSyncInterval(SyncInterval.MANUAL);
                message += "；已停止自动同步，请检查 URL 是否有效";
            }
            doc.setErrorMessage(message);
            documentRepository.save(doc);
            log.warn("网页同步失败 id={} url={}: {}", documentId, doc.getSourceUrl(), e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=WebPageSyncServiceTest`
Expected: 6 个测试全绿。

- [ ] **Step 5: 变异自证（必做）**

把「内容未变」那条分支的提前 `return` 删掉（让它每次都走重嵌），确认 `内容未变时不重嵌只更新同步时间` **转红**；确认后改回。绿本身证明不了测试有效——本项目已两次抓到「测试全绿但把实现改坏也不红」。把变异过程写进报告。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/web/WebPageSyncService.java \
        backend/src/test/java/com/qingyu/care/service/web/WebPageSyncServiceTest.java
git commit -m "feat(backend): 网页同步编排（未变不重嵌/变更重嵌/失败保留旧内容）"
```

---

### Task 4: 调度器（含绕过租户过滤器的原生查询）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/scheduler/WebPageSyncScheduler.java`
- Modify: `backend/src/main/java/com/qingyu/care/repository/DocumentRepository.java`（新增原生查询）
- Test: `backend/src/test/java/com/qingyu/care/scheduler/WebPageSyncSchedulerTest.java`
- Test: `backend/src/test/java/com/qingyu/care/it/WebPageSyncSchedulerIT.java`

**Interfaces:**
- Consumes: `WebPageSyncService.syncDocument(Long)`（Task 3）
- Produces: `DocumentRepository.findDueWebPageIds(LocalDateTime now)` → `List<Long>`

**⚠️ 这个任务的全部难点在一处**：`Document extends BaseTenantEntity`，受 Hibernate `@Filter` 保护。调度器线程**没有 `TenantContext`**，`TenantDataAspect` 对「非平台管理员且无上下文」会填 `-1L`（拒绝一切）。所以到期查询**必须用原生 SQL 绕过过滤器**，否则一条都查不到——而且是**静默的**：任务照常跑、日志照常打，就是什么都不同步。这与「多级配置回退」是同一个坑。

- [ ] **Step 1: 加原生查询**

`DocumentRepository` 中新增：

```java
    /**
     * 查到期需要自动同步的网页文档 id。
     *
     * <p><b>必须用原生 SQL</b>：调度器线程无 TenantContext，普通 JPA 查询会被
     * Hibernate @Filter 用 tenant_id = -1 拒绝一切、静默返回空集（任务照常跑却什么都不同步）。
     * 这与多级配置回退取平台默认行是同一个坑，做法一致。
     *
     * <p>到期条件：类型为 URL、周期非 MANUAL、且（从未同步过 或 距上次成功同步已超过周期）。
     */
    @Query(value = """
            SELECT d.id FROM knowledge_document d
            WHERE d.document_type = 'URL'
              AND d.sync_interval IS NOT NULL
              AND d.sync_interval <> 'MANUAL'
              AND (
                    d.last_synced_at IS NULL
                 OR (d.sync_interval = 'HOURLY' AND d.last_synced_at <= :now - INTERVAL '1 hour')
                 OR (d.sync_interval = 'DAILY'  AND d.last_synced_at <= :now - INTERVAL '1 day')
                 OR (d.sync_interval = 'WEEKLY' AND d.last_synced_at <= :now - INTERVAL '7 days')
              )
            ORDER BY d.last_synced_at NULLS FIRST, d.id
            """, nativeQuery = true)
    List<Long> findDueWebPageIds(@Param("now") LocalDateTime now);
```

> 表名以 `Document` 实体上的 `@Table` 注解为准——实现前先打开 `Document.java` 确认，不要照抄这里的 `knowledge_document`。

- [ ] **Step 2: 写失败的单测**

`backend/src/test/java/com/qingyu/care/scheduler/WebPageSyncSchedulerTest.java`：

```java
package com.qingyu.care.scheduler;

import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.web.WebPageSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：调度器逐个同步到期文档，且单个失败不中断其余。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WebPageSyncSchedulerTest {

    @Mock DocumentRepository documentRepository;
    @Mock WebPageSyncService webPageSyncService;
    @InjectMocks WebPageSyncScheduler scheduler;

    @Test
    void 逐个同步到期文档() {
        when(documentRepository.findDueWebPageIds(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L, 3L));

        scheduler.syncDueWebPages();

        verify(webPageSyncService).syncDocument(1L);
        verify(webPageSyncService).syncDocument(2L);
        verify(webPageSyncService).syncDocument(3L);
    }

    @Test
    void 单个文档抛异常不中断其余() {
        when(documentRepository.findDueWebPageIds(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new RuntimeException("数据库连接断了")).when(webPageSyncService).syncDocument(2L);

        scheduler.syncDueWebPages();   // 不应抛穿

        verify(webPageSyncService, times(1)).syncDocument(1L);
        verify(webPageSyncService, times(1)).syncDocument(3L);   // 第 2 个失败后第 3 个仍执行
    }

    @Test
    void 无到期文档时不调用同步() {
        when(documentRepository.findDueWebPageIds(any(LocalDateTime.class))).thenReturn(List.of());

        scheduler.syncDueWebPages();

        verify(webPageSyncService, times(0)).syncDocument(any());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=WebPageSyncSchedulerTest`
Expected: 编译失败，`WebPageSyncScheduler` 不存在。

- [ ] **Step 4: 实现调度器**

```java
package com.qingyu.care.scheduler;

import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.web.WebPageSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 网页文档定时同步：每小时扫描一次到期文档，逐个重抓。
 *
 * <p>到期查询走 {@link DocumentRepository#findDueWebPageIds} 的原生 SQL——本线程没有
 * TenantContext，普通 JPA 查询会被租户过滤器静默拒绝（详见该方法的注释）。
 *
 * <p>逐个 fail-soft：一篇失败不影响其余；失败的处理（计数、转手动）在
 * {@link WebPageSyncService#syncDocument} 内部完成，此处只兜住意外异常。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebPageSyncScheduler {

    private final DocumentRepository documentRepository;
    private final WebPageSyncService webPageSyncService;

    /** 每小时执行一次（首次延迟 5 分钟，避开应用启动高峰）。 */
    @Scheduled(initialDelay = 5 * 60 * 1000L, fixedDelay = 60 * 60 * 1000L)
    public void syncDueWebPages() {
        List<Long> dueIds = documentRepository.findDueWebPageIds(LocalDateTime.now());
        if (dueIds.isEmpty()) {
            return;
        }
        log.info("网页定时同步：{} 篇到期", dueIds.size());
        for (Long id : dueIds) {
            try {
                webPageSyncService.syncDocument(id);
            } catch (Exception e) {
                log.warn("网页定时同步意外失败 id={}: {}", id, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 5: 写集成测试（本任务的关键守护）**

`backend/src/test/java/com/qingyu/care/it/WebPageSyncSchedulerIT.java`：

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：调度器的到期查询在<b>无 TenantContext</b> 时必须真能查到文档。
 *
 * <p>这是本特性最隐蔽的失败模式——普通 JPA 查询会被 Hibernate @Filter 用 tenant_id = -1
 * 静默拒绝，任务照常跑、日志照常打，就是什么都不同步。单测 mock 掉 repository 永远抓不到，
 * 只有真实 Spring 上下文 + 真实数据库能验证。
 *
 * @author ruan
 */
@Transactional
class WebPageSyncSchedulerIT extends AbstractPostgresIT {

    @Autowired DocumentRepository documentRepository;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void 无租户上下文时到期查询仍能查到网页文档() {
        // 播种：先设上下文建数据（实体经 TenantEntityListener 回填 tenant_id/app_id）
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);
        KnowledgeBase kb = knowledgeBaseRepository.save(
                KnowledgeBase.builder().name("网页同步 IT 知识库").enabled(true).build());
        Document doc = documentRepository.save(Document.builder()
                .knowledgeBase(kb)
                .name("https://example.com/faq")
                .documentType(DocumentType.URL)
                .status(DocumentStatus.COMPLETED)
                .sourceUrl("https://example.com/faq")
                .syncInterval(SyncInterval.DAILY)
                .lastSyncedAt(LocalDateTime.now().minusDays(2))   // 两天前同步过，已到期
                .build());

        // 关键：清掉上下文，模拟调度器线程
        TenantContext.clear();

        List<Long> dueIds = documentRepository.findDueWebPageIds(LocalDateTime.now());

        assertThat(dueIds).contains(doc.getId());
    }

    @Test
    void MANUAL 周期的文档不会被查出() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);
        KnowledgeBase kb = knowledgeBaseRepository.save(
                KnowledgeBase.builder().name("网页同步 IT 知识库2").enabled(true).build());
        Document manual = documentRepository.save(Document.builder()
                .knowledgeBase(kb)
                .name("https://example.com/about")
                .documentType(DocumentType.URL)
                .status(DocumentStatus.COMPLETED)
                .sourceUrl("https://example.com/about")
                .syncInterval(SyncInterval.MANUAL)
                .lastSyncedAt(LocalDateTime.now().minusYears(1))
                .build());
        TenantContext.clear();

        assertThat(documentRepository.findDueWebPageIds(LocalDateTime.now()))
                .doesNotContain(manual.getId());
    }
}
```

> IT 里直接设 `TenantContext` 需要类级 `@Transactional` 才能让 Hibernate Filter 在同一 Session 内生效（本项目 `HandoverRuleFallbackIT` 踩过这个坑，做法一致）。

- [ ] **Step 6: 运行测试确认通过**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test -Dtest=WebPageSyncSchedulerTest`（3 绿）
Run: `cd D:\Code\m\qingyu-care\backend && mvn verify -Dit.test=WebPageSyncSchedulerIT`（需 Docker Desktop + 本地 redis 容器；2 绿）

- [ ] **Step 7: 变异自证（必做）**

把 `findDueWebPageIds` 的 `nativeQuery = true` 改成普通 JPQL 查询（或去掉 native），确认 `无租户上下文时到期查询仍能查到网页文档` 这条 IT **转红**。这证明该 IT 真的守住了「绕过租户过滤器」这条规则，而不是碰巧通过。确认后改回，并把过程写进报告。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/scheduler/WebPageSyncScheduler.java \
        backend/src/main/java/com/qingyu/care/repository/DocumentRepository.java \
        backend/src/test/java/com/qingyu/care/scheduler/WebPageSyncSchedulerTest.java \
        backend/src/test/java/com/qingyu/care/it/WebPageSyncSchedulerIT.java
git commit -m "feat(backend): 网页定时同步调度器（原生查询绕过租户过滤器）"
```

---

### Task 5: 控制器 + 前端 UI

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/KnowledgeController.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/AddWebPageRequest.java`
- Modify: `frontend/src/api/knowledge.ts`
- Modify: `frontend/src/pages/KnowledgePage.tsx`
- Modify: `frontend/src/types/index.ts`

**Interfaces:**
- Consumes: `WebPageSyncService.addWebPage(Long, String, SyncInterval)`、`syncDocumentAsync(Long)`（Task 3）
- Produces: `POST /v1/knowledge/{kbId}/web-pages`、`POST /v1/knowledge/documents/{id}/sync`

- [ ] **Step 1: 后端 DTO 与端点**

`dto/AddWebPageRequest.java`：

```java
package com.qingyu.care.dto;

import com.qingyu.care.enums.SyncInterval;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 添加网页文档的请求体。
 *
 * @author ruan
 */
@Data
public class AddWebPageRequest {

    /** 只接受 http/https，与前端 safeUrl 白名单一致；其余协议直接被校验挡下。 */
    @NotBlank(message = "URL 不能为空")
    @Pattern(regexp = "^https?://.+", message = "只支持 http/https 开头的网址")
    private String url;

    /** 同步周期；不传按 MANUAL 处理。 */
    private SyncInterval syncInterval;
}
```

`KnowledgeController` 新增两个端点（注入 `WebPageSyncService`）：

```java
    @PostMapping("/{knowledgeBaseId}/web-pages")
    @Operation(summary = "添加网页文档", description = "抓取指定 URL 的正文入知识库，可配置自动同步周期")
    @AuditLog(action = "ADD_WEB_PAGE", targetType = "DOCUMENT")
    public ApiResponse<Document> addWebPage(@PathVariable Long knowledgeBaseId,
                                            @Valid @RequestBody AddWebPageRequest request) {
        return ApiResponse.success("网页已添加，正在抓取",
                webPageSyncService.addWebPage(knowledgeBaseId, request.getUrl(), request.getSyncInterval()));
    }

    @PostMapping("/documents/{documentId}/sync")
    @Operation(summary = "立即同步网页", description = "手动触发一次重新抓取（异步执行）")
    @AuditLog(action = "SYNC_WEB_PAGE", targetType = "DOCUMENT")
    public ApiResponse<Void> syncWebPage(@PathVariable Long documentId) {
        knowledgeService.getDocument(documentId);   // 归属校验：越权直接抛，避免跨租户触发同步
        webPageSyncService.syncDocumentAsync(documentId);
        return ApiResponse.success("已触发同步", null);
    }
```

> `getDocument` 内部有归属校验，先调它再触发异步——否则租户可以枚举 id 触发别人的文档同步。

- [ ] **Step 2: 后端编译与全量单测**

Run: `cd D:\Code\m\qingyu-care\backend && mvn test`
Expected: 全绿，无回归。

- [ ] **Step 3: 前端类型与 API**

`frontend/src/types/index.ts` 的 `Document` 类型加上可选字段（字段名与后端实体一致）：

```ts
  sourceUrl?: string | null;
  syncInterval?: 'MANUAL' | 'HOURLY' | 'DAILY' | 'WEEKLY' | null;
  lastSyncedAt?: string | null;
  syncFailureCount?: number | null;
```

`frontend/src/api/knowledge.ts` 新增两个方法（沿用该文件既有的 `api` 实例与写法）：

```ts
    // 添加网页文档（抓取 URL 正文入库）
    addWebPage: (knowledgeBaseId: number, url: string, syncInterval: string): Promise<ApiResponse<Document>> =>
        api.post(`/knowledge/${knowledgeBaseId}/web-pages`, { url, syncInterval }),

    // 手动触发网页重新抓取
    syncWebPage: (documentId: number): Promise<ApiResponse<void>> =>
        api.post(`/knowledge/documents/${documentId}/sync`),
```

- [ ] **Step 4: 前端 UI**

`KnowledgePage.tsx` 三处改动。该页已 import 了 `Dialog/DialogContent/DialogHeader/DialogTitle/DialogDescription/DialogTrigger`、`Button`、`Input`、`useToast`，本次只需补 `Globe` 与 `RefreshCw` 两个 lucide 图标。

**(a) 组件顶部加状态与两个处理函数**（放在既有的 `handleToggleEnabled` 之后）：

```tsx
    const [webPageOpen, setWebPageOpen] = useState(false);
    const [webPageUrl, setWebPageUrl] = useState('');
    const [webPageInterval, setWebPageInterval] = useState('DAILY');
    const [syncingDocId, setSyncingDocId] = useState<number | null>(null);

    const handleAddWebPage = async () => {
        if (!webPageUrl.trim() || !selectedKb) return;
        try {
            await knowledgeService.addWebPage(selectedKb.id, webPageUrl.trim(), webPageInterval);
            setWebPageOpen(false);
            setWebPageUrl('');
            toast({ title: '网页已添加', description: '正在后台抓取，稍后刷新查看结果' });
            loadDocuments(selectedKb.id);
        } catch (error: any) {
            toast({ variant: 'destructive', title: '添加失败', description: error?.message });
        }
    };

    const handleSyncWebPage = async (docId: number) => {
        setSyncingDocId(docId);
        try {
            await knowledgeService.syncWebPage(docId);
            toast({ title: '已触发同步', description: '正在后台重新抓取' });
            setTimeout(() => selectedKb && loadDocuments(selectedKb.id), 2000);
        } catch (error: any) {
            toast({ variant: 'destructive', title: '同步失败', description: error?.message });
        } finally {
            setSyncingDocId(null);
        }
    };
```

> `selectedKb` / `loadDocuments` / `toast` 都是该页既有的名字——实现前先确认实际变量名（比如选中知识库可能叫 `selectedKnowledgeBase`），按实际改。

**(b) 文档区工具栏加「添加网页」按钮 + 对话框**（放在既有「上传文档」按钮旁边）：

```tsx
                    <Dialog open={webPageOpen} onOpenChange={setWebPageOpen}>
                        <DialogTrigger asChild>
                            <Button variant="outline" className="gap-2">
                                <Globe className="w-4 h-4" /> 添加网页
                            </Button>
                        </DialogTrigger>
                        <DialogContent>
                            <DialogHeader>
                                <DialogTitle>添加网页到知识库</DialogTitle>
                                <DialogDescription>
                                    抓取网页正文入库（自动剔除导航、页脚等噪声）。只支持 http/https，且不能是内网地址。
                                </DialogDescription>
                            </DialogHeader>
                            <div className="space-y-4">
                                <Input
                                    placeholder="https://example.com/faq"
                                    value={webPageUrl}
                                    onChange={(e) => setWebPageUrl(e.target.value)}
                                />
                                <div className="space-y-1">
                                    <label className="text-xs text-muted-foreground">自动同步周期</label>
                                    <select
                                        className="w-full h-9 px-3 rounded-md border bg-background text-sm"
                                        value={webPageInterval}
                                        onChange={(e) => setWebPageInterval(e.target.value)}
                                    >
                                        <option value="MANUAL">不自动同步</option>
                                        <option value="HOURLY">每小时</option>
                                        <option value="DAILY">每天</option>
                                        <option value="WEEKLY">每周</option>
                                    </select>
                                </div>
                                <Button className="w-full" onClick={handleAddWebPage} disabled={!webPageUrl.trim()}>
                                    添加
                                </Button>
                            </div>
                        </DialogContent>
                    </Dialog>
```

**(c) 文档列表行补网页信息与同步按钮**。在既有那行 `<span>{new Date(doc.createdAt).toLocaleDateString()}</span>` 所在的 meta 区之后、`{doc.status === 'PROCESSING' && ...}` 之前插入：

```tsx
                                                    {doc.sourceUrl && (
                                                        <div className="flex items-center gap-3 mt-1 text-[10px]">
                                                            <a
                                                                href={doc.sourceUrl}
                                                                target="_blank"
                                                                rel="noopener noreferrer"
                                                                className="text-primary hover:underline truncate max-w-[240px]"
                                                            >
                                                                {doc.sourceUrl}
                                                            </a>
                                                            <span className="text-muted-foreground">
                                                                上次同步: {doc.lastSyncedAt
                                                                    ? new Date(doc.lastSyncedAt).toLocaleString()
                                                                    : '从未'}
                                                            </span>
                                                            {(doc.syncFailureCount ?? 0) > 0 && (
                                                                <span className="text-amber-600">
                                                                    连续失败 {doc.syncFailureCount} 次
                                                                </span>
                                                            )}
                                                        </div>
                                                    )}
```

在操作区（`handleToggleEnabled` 那个按钮与删除按钮之间）插入：

```tsx
                                                    {doc.sourceUrl && (
                                                        <Button
                                                            variant="ghost"
                                                            size="icon"
                                                            className="h-8 w-8"
                                                            title="立即重新抓取"
                                                            disabled={syncingDocId === doc.id}
                                                            onClick={() => handleSyncWebPage(doc.id)}
                                                        >
                                                            <RefreshCw className={`w-4 h-4 ${syncingDocId === doc.id ? 'animate-spin' : ''}`} />
                                                        </Button>
                                                    )}
```

- [ ] **Step 5: 前端构建与 lint**

Run: `cd D:\Code\m\qingyu-care\frontend && pnpm build && pnpm lint`
Expected: build 通过；lint **69 problems (69 errors, 0 warnings)**，与基线一致零新增（`pnpm lint` 因存在 error 会以非零码退出，这是既有基线状态，看计数不看退出码）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/KnowledgeController.java \
        backend/src/main/java/com/qingyu/care/dto/AddWebPageRequest.java \
        frontend/src/api/knowledge.ts frontend/src/pages/KnowledgePage.tsx frontend/src/types/index.ts
git commit -m "feat: 网页知识库的管理端接口与界面"
```

---

### Task 6: 全量验证 + 真机冒烟

**Files:**
- Modify: `CLAUDE.md`（在架构要点的「核心业务」小节后追加网页同步的说明）

**Interfaces:**
- Consumes: 全部前序任务
- Produces: 无（验收任务）

- [ ] **Step 1: 三端全量验证**

```bash
cd D:\Code\m\qingyu-care\backend && mvn verify        # 需 Docker Desktop + 本地 redis 容器
cd D:\Code\m\qingyu-care\frontend && pnpm build && pnpm lint
cd D:\Code\m\qingyu-care\widget   && pnpm test        # 本特性未动 widget，确认无连带破坏
```
Expected: 后端 BUILD SUCCESS；前端 build 通过、lint 69 与基线一致；widget 78 绿。

- [ ] **Step 2: 起环境**

```bash
cd D:\Code\m\qingyu-care\docker && docker compose up -d
cd D:\Code\m\qingyu-care\backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 需 DASHSCOPE_API_KEY
cd D:\Code\m\qingyu-care\frontend && pnpm dev
```

- [ ] **Step 3: 真机冒烟**

用**租户账号**登录（不能用平台管理员）。逐条验证：

1. **添加网页**：选一个真实的公开 FAQ 页（如 `https://docs.spring.io/spring-boot/index.html` 或任意服务端渲染的帮助页），周期选「每天」。等状态变 COMPLETED。
2. **正文干净**（本特性质量的核心）：查库 `select name, left(content, 500) from knowledge_document where source_url is not null order by id desc limit 1;` —— **人工读一遍**，确认没有混进导航栏、Cookie 提示、页脚版权、脚本代码。
3. **检索命中**：在智能问答页提一个只有该网页才有答案的问题，确认命中该文档。
4. **二次同步不重嵌**：记下 `chunkCount` 与 `select count(*) from vector_store where metadata->>'documentId' = '<id>'`，点「立即同步」，等完成后再查一次——**两个数字都不该变**（内容没变，不该重新嵌入）。
5. **内容变更触发重嵌**：手工把该文档的 `content_hash` 改成一个假值（`update knowledge_document set content_hash='FAKE' where id=<id>;`），再点「立即同步」，确认 `chunkCount` 被刷新（重嵌发生）。
6. **失败保留旧内容**：把 `source_url` 改成一个 404 地址，点「立即同步」，确认：`content` 仍是旧正文、`sync_failure_count` 递增、`error_message` 有值、`last_synced_at` 未变。
7. **定时任务真的能查到**（§5.1 那个坑的终验）：把某篇文档的 `last_synced_at` 改成很久以前（`update knowledge_document set last_synced_at = now() - interval '10 days' where id=<id>;`），把调度器的 `fixedDelay` 临时改小（如 30 秒）重启后端，观察日志出现「网页定时同步：N 篇到期」且该文档确实被同步。**验证完把 fixedDelay 改回 1 小时。**
8. **SSRF 拦截**：尝试添加 `http://127.0.0.1:8080/api/swagger-ui.html` 与 `http://169.254.169.254/`，确认都被拒绝且错误信息不泄露内部细节。

- [ ] **Step 4: 沉淀到 CLAUDE.md**

在「核心业务：RAG 对话链路」小节之后追加：

```markdown
### 网页知识库的定时同步
`WebPageSyncScheduler` 每小时扫描到期的网页文档重抓。两个易错点：

1. **到期查询必须走原生 SQL**（`DocumentRepository.findDueWebPageIds`）。调度器线程没有 `TenantContext`，普通 JPA 查询会被 `TenantDataAspect` 用 `tenant_id = -1` 静默拒绝——任务照常跑、日志照常打，就是什么都不同步。与多级配置回退取平台默认行是同一个坑。
2. **内容未变一定不能重嵌**（`WebPageSyncService.syncDocument` 比对 `contentHash`）。这条退化后功能完全正常，只是每轮同步都白烧一次 embedding 的钱，没有任何现象会提醒。改动该方法后务必确认对应单测仍在断言 `verify(knowledgeService, never()).reprocessDocument(...)`。

抓取失败时**保留旧内容与旧向量**（官网临时 502 不该让知识消失），连续失败 10 次自动转 `MANUAL`。
```

- [ ] **Step 5: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: 沉淀网页知识库定时同步的两个易错点"
```

---

## 自查记录

- **spec 覆盖**：§3 数据模型 → Task 1；§4.1 抓取器 → Task 2；§4.2 三分支 → Task 3；§5.1 绕过过滤器 → Task 4（含 IT 与变异自证）；§5.2 失败退避 → Task 3（`MAX_FAILURES`）+ Task 4（调度）；§6 安全 → Task 2；§7 UI → Task 5；§8 测试 → 各任务；§9 冒烟 → Task 6。
- **spec 之外补的一处**：Task 5 的「立即同步」端点先调 `getDocument` 做归属校验再触发异步——spec 未提，但不加的话租户可枚举 id 触发别人文档的同步。
- **类型一致性**：`SyncInterval`、`FetchResult(title, content, contentHash, httpStatus)`、`addWebPage(Long, String, SyncInterval)`、`syncDocument(Long)`、`syncDocumentAsync(Long)`、`findDueWebPageIds(LocalDateTime)` 在各任务间一致。
- **两处必做的变异自证**：Task 3 Step 5（删掉「未变不重嵌」的提前 return 必须让测试转红）、Task 4 Step 7（把原生查询改成 JPQL 必须让 IT 转红）。本项目已两次抓到「测试全绿但把实现改坏也不红」，这两条都属于同类高风险点。

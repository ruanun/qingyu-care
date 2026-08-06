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
        // ⚠️ 不能用 when(webPageFetcher.downloadRaw(anyString())).thenReturn(...) 重新打桩：
        // setUp() 已把同一 mock 的同一匹配器打成 thenThrow，when() 为了记录新桩必须先真的调用
        // 一次 downloadRaw(anyString())，而这一次调用会命中"当前仍生效"的旧桩直接抛出，
        // 异常在 .thenReturn(...) 链上去之前就已经飞出——本条断言把这坑现场炸给我们看了一次。
        // doReturn().when() 走的是另一条不经过"先调用"的打桩路径，能正确覆盖旧桩。
        org.mockito.Mockito.doReturn(new WebPageFetcher.Downloaded(robots, "UTF-8", 200))
                .when(webPageFetcher).downloadRaw(anyString());
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

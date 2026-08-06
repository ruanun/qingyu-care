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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
        //
        // ⚠️ 给下一个要在某条用例里让 downloadRaw 返回真实 robots.txt 内容的人：
        // 别用 when(webPageFetcher.downloadRaw(anyString())).thenReturn(...) 重新打桩。
        // 这个 mock 已经被本行打过 thenThrow，when() 为了记录新桩必须先真的调用一次
        // 该方法，而这次调用会命中当前仍生效的旧桩、直接把异常抛出来，
        // .thenReturn(...) 永远接不上去（本文件真的踩过一次）。
        // ⚠️ 把这一行的原始桩换成 doThrow(...).when(webPageFetcher).downloadRaw(...) 对
        // 这个坑没有帮助——已用一次性探针实测：无论原始桩用哪种语法配置，
        // when(mock.foo()) 这种"先调用以录制"的重新打桩方式都会在录制那一步就先把
        // mock 当前生效的异常炸出来，与原始桩的配置方式无关。
        // 唯一安全的重新打桩方式是 doReturn(...).when(webPageFetcher).downloadRaw(...)，
        // 用法见 robots_denied_pages_do_not_count_toward_max_pages。
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
    @DisplayName("同一页面内同一 URL 的不同写法折叠成一条链接——这条守的是 LinkExtractor 的 Set 语义，"
            + "不是跨页 visited 去重（页内四种写法本就在 extract() 里被 Set 折叠掉了，"
            + "跨页去重见 cross_page_revisits_are_deduplicated）")
    void visits_each_page_once() {
        CrawlSite s = site(50, 3);
        stubIngestSucceeds();
        stubPage("https://x.com/help", "/help/a", "/help/a/", "/help/a#top", "/help/a?utm_source=x");
        stubPage("https://x.com/help/a");

        service.runCrawl(newProgress(s), 9L, 1L, 2L, 3L, null);

        assertThat(fetched).containsExactly("https://x.com/help", "https://x.com/help/a");
    }

    @Test
    @DisplayName("互链两页（A→B→A）只各抓一次——跨页 visited 去重，防止无限重复访问烧光 maxPages 额度")
    void cross_page_revisits_are_deduplicated() {
        CrawlSite s = site(50, 3);
        stubIngestSucceeds();
        stubPage("https://x.com/help", "/help/b");
        stubPage("https://x.com/help/b", "/help");

        service.runCrawl(newProgress(s), 9L, 1L, 2L, 3L, null);

        assertThat(fetched).containsExactly("https://x.com/help", "https://x.com/help/b");
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
    @DisplayName("重新爬到已存在但未归属任何站点的文档时补上 crawlSite——否则该页永远享受不到站点的"
            + " contentSelector，删站点连带删文档时也会漏删它")
    void adopts_existing_unowned_document_into_crawl_site() {
        CrawlSite s = site(50, 0);
        stubPage("https://x.com/help");
        Document existing = Document.builder().id(77L).sourceUrl("https://x.com/help").build();
        when(documentRepository.findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc(100L, "https://x.com/help"))
                .thenReturn(Optional.of(existing));
        when(documentRepository.findById(77L)).thenReturn(Optional.of(existing));
        List<Document> saved = new ArrayList<>();
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        verify(webPageSyncService).syncDocument(77L);
        assertThat(saved).anySatisfy(d -> assertThat(d.getCrawlSite()).isSameAs(s));
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

    @Test
    @DisplayName("入口地址被 robots.txt 禁止抓取时任务必须 FAILED——不能悄悄报 COMPLETED、0 页，"
            + "让用户以为爬完了实际什么都没抓到")
    void entry_page_denied_by_robots_fails_the_whole_job() {
        CrawlSite s = site(50, 1);
        byte[] robots = "User-agent: *\nDisallow: /help\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.mockito.Mockito.doReturn(new WebPageFetcher.Downloaded(robots, "UTF-8", 200))
                .when(webPageFetcher).downloadRaw(anyString());

        SiteCrawlService.Progress p = newProgress(s);
        service.runCrawl(p, 9L, 1L, 2L, 3L, null);

        assertThat(p.status).isEqualTo("FAILED");
        assertThat(p.errorMessage).contains("robots.txt");
    }

    // ===== robots Crawl-delay 上限（第三方可控值，不能无上界占用共享线程池）=====

    @Test
    @DisplayName("robots 声明的 Crawl-delay 超过平台上限时被钳制到上限")
    void clamps_robots_crawl_delay_to_platform_limit() {
        assertThat(SiteCrawlService.resolveDelayMs(300_000L, 1000L, 10_000L)).isEqualTo(10_000L);
        assertThat(SiteCrawlService.resolveDelayMs(5000L, 1000L, 10_000L)).isEqualTo(5000L);
        assertThat(SiteCrawlService.resolveDelayMs(null, 1000L, 10_000L)).isEqualTo(1000L);
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
    @DisplayName("originOf 对缺协议/主机名的 URL 抛业务异常而非 NPE")
    void origin_of_rejects_url_without_scheme_or_host() {
        // new URI("x.com/help") 不抛 URISyntaxException（合法的无协议相对引用），
        // getScheme() 静默返回 null；不加守卫会在 new StringBuilder((String) null) 处
        // 抛 NPE，对外表现为无业务语义的 500
        assertThatThrownBy(() -> SiteCrawlService.originOf("x.com/help"))
                .isInstanceOf(BusinessException.class);
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
    @DisplayName("页数与深度被钳制到平台配置的硬顶——用非默认值验证，防止巧合等于硬编码默认值而测不出实现"
            + "到底钳的是配置还是字面量")
    void clamps_max_pages_and_depth_to_platform_limits() {
        props.setMaxPagesLimit(7);
        props.setMaxDepthLimit(2);
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

        assertThat(saved.get(0).getMaxPages()).isEqualTo(7);
        assertThat(saved.get(0).getMaxDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("clamp：未传取默认值；低于下界抬回下界而不是钳成 0——钳成 0 时 while 循环一次都不进，"
            + "任务照样报 COMPLETED、0 页，且没有任何异常或日志")
    void clamp_applies_defaults_and_lower_bound() {
        assertThat(SiteCrawlService.clamp(null, 50, 1, 500)).isEqualTo(50);
        assertThat(SiteCrawlService.clamp(null, 3, 0, 5)).isEqualTo(3);
        assertThat(SiteCrawlService.clamp(0, 50, 1, 500)).isEqualTo(1);
        assertThat(SiteCrawlService.clamp(-5, 3, 0, 5)).isEqualTo(0);
    }

    @Test
    @DisplayName("同一知识库下重复入口地址被拒绝——两个不同 id 的站点各自跑一遍 BFS 会互相看不见"
            + "对方未提交的建档，产出重复文档与重复向量")
    void rejects_duplicate_entry_url_in_same_knowledge_base() {
        CrawlSiteRequest req = new CrawlSiteRequest();
        req.setEntryUrl("https://x.com/help/");
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(100L);
        when(knowledgeService.getKnowledgeBase(100L)).thenReturn(kb);
        CrawlSite existing = CrawlSite.builder().id(5L).knowledgeBase(kb).entryUrl("https://x.com/help").build();
        // 查重键必须是 LinkExtractor.normalize 之后的值（本例即去掉尾斜杠）——若实现漏了
        // normalize、直接拿 req.getEntryUrl()（带尾斜杠的 "https://x.com/help/"）去查，
        // 这里打的桩不会命中，assertThatThrownBy 会因为没抛异常而失败，现场验证 N-1 未回退
        when(crawlSiteRepository.findFirstByKnowledgeBaseIdAndEntryUrlOrderByIdAsc(100L, "https://x.com/help"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.startCrawl(100L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重新爬取");
    }

    @Test
    @DisplayName("同一入口地址的不同写法（尾斜杠/片段/无意义查询参数）被认作同一站点——查重必须走"
            + " LinkExtractor.normalize 而非原始字符串比对，否则 /help、/help/、/help#top 各建一个"
            + "站点各跑一遍 BFS，绕开上面那条查重")
    void treats_different_spellings_of_the_same_entry_url_as_duplicates() {
        CrawlSiteRequest req = new CrawlSiteRequest();
        req.setEntryUrl("https://x.com/help#top");   // 规范化后与已存在站点同为 "https://x.com/help"
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(100L);
        when(knowledgeService.getKnowledgeBase(100L)).thenReturn(kb);
        CrawlSite existing = CrawlSite.builder().id(5L).knowledgeBase(kb).entryUrl("https://x.com/help").build();
        when(crawlSiteRepository.findFirstByKnowledgeBaseIdAndEntryUrlOrderByIdAsc(100L, "https://x.com/help"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.startCrawl(100L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重新爬取");
    }

    @Test
    @DisplayName("未显式传 scopePrefix 时，范围前缀必须按入口 URL 的原始写法（含尾斜杠）推导——"
            + "不能被查重/落库用的规范化 entryUrl 污染。normalize 会把 \"https://x.com/help/\" 的"
            + "尾斜杠去掉，deriveScopePrefix 正是靠尾斜杠区分目录/文件形态，喂规范化后的值会把明明"
            + "写的目录形态误判成文件形态，把范围从「只爬 /help/ 下面」错误放大成整站")
    void derives_scope_prefix_from_original_entry_url_not_the_normalized_one() {
        CrawlSiteRequest req = new CrawlSiteRequest();
        req.setEntryUrl("https://x.com/help/");
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

        assertThat(saved.get(0).getScopePrefix()).isEqualTo("https://x.com/help/");
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
    @DisplayName("异步投递失败必须回滚站点互斥与全局配额——否则站点永久无法再爬、"
            + "全局配额被僵尸 Progress 永久占满")
    void releases_site_mutex_and_job_slot_when_async_dispatch_fails() {
        CrawlSite s = site(50, 0);
        props.setMaxConcurrentJobs(2);
        SiteCrawlService throwingSelf = mock(SiteCrawlService.class);
        doThrow(new RuntimeException("线程池拒绝"))
                .when(throwingSelf).runCrawl(any(), any(), any(), any(), any(), any());
        service.setSelf(throwingSelf);

        // 连续两次投递失败：若站点互斥没回滚，第二次会被"该站点已有爬取在进行"拦下而不是
        // 复现投递异常本身——断言两次抛出的都是同一种异常，说明互斥每次都正确释放
        assertThatThrownBy(() -> service.recrawl(9L))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("线程池拒绝");
        assertThatThrownBy(() -> service.recrawl(9L))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("线程池拒绝");

        // 换回真实 self：若 jobs 里两条 RUNNING 的僵尸 Progress 没被回收，
        // maxConcurrentJobs=2 会让这次直接被"已有 2 个爬取任务在进行"拒绝
        service.setSelf(service);
        stubIngestSucceeds();
        stubPage("https://x.com/help");
        String jobId = service.recrawl(9L);
        assertThat(jobId).isNotBlank();
    }

    @Test
    @DisplayName("爬取中不能删站点——收尾的 merge 会把已删除的行当新行插回来，界面表现为删了又回来")
    void rejects_deleting_a_site_that_is_currently_crawling() {
        CrawlSite s = site(50, 0);
        service.markCrawlingForTest(9L);

        assertThatThrownBy(() -> service.deleteSite(9L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("爬取中");
    }

    @Test
    @DisplayName("进度不存在时抛 404 语义的业务异常")
    void unknown_job_id_throws() {
        assertThatThrownBy(() -> service.getProgress("nope"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ===== 越权（TenantOwnershipGuard）=====

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

    @Test
    @DisplayName("拿别的租户的 jobId 查进度：按「任务不存在」拒绝，防跨租户窥探爬取详情——"
            + "jobId 是 UUID 不可枚举，但一旦经日志/工单/截图等旁路泄漏给别的租户，"
            + "对方就能借它看到别人站点的 siteId/已爬页数/失败原因")
    void rejects_progress_query_for_another_tenants_site() {
        CrawlSite other = CrawlSite.builder().id(9L).build();
        other.setTenantId(999L);
        other.setAppId(888L);
        when(crawlSiteRepository.findById(9L)).thenReturn(Optional.of(other));
        SiteCrawlService.Progress p = service.newProgressForTest("job-x", 9L, 50);
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);

        assertThatThrownBy(() -> service.getProgress(p.jobId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("站点已被删除时进度仍可查询——进度是内存态，站点没了说明爬取早已结束，"
            + "此时没有任何还「活着」的站点信息可泄漏，不能按 requireOwnedSite 那套"
            + "「不存在即拒绝」的语义处理（那是给站点本身用的，这里要反过来放行）")
    void allows_progress_query_when_site_has_been_deleted() {
        when(crawlSiteRepository.findById(9L)).thenReturn(Optional.empty());
        SiteCrawlService.Progress p = service.newProgressForTest("job-y", 9L, 50);
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);

        assertThat(service.getProgress(p.jobId)).isSameAs(p);
    }
}

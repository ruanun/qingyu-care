package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.CrawlSiteRequest;
import com.qingyu.care.dto.CrawlStartResponse;
import com.qingyu.care.entity.Application;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集成测试：站点爬取产出的文档，租户字段必须正确落库。
 *
 * <p>本 IT 独有的价值在 <b>Hibernate 持久化那一层</b>：{@code TenantEntityListener} 是
 * Hibernate 生命周期回调（{@code @PrePersist}），纯 Mockito 环境里这个回调根本不会被触发，
 * 所以"{@code runCrawl} 里 {@code TenantContext} 搬没搬"这件事就算搬对了，纯 Mockito
 * 单测也验证不到它真的被 Hibernate 写进了 {@code tenant_id}/{@code app_id} 这两列——
 * 必须有真实的 Hibernate 会话才能验证"ThreadLocal 里的值 → 数据库列"这一跳。
 * ⚠️ 反过来，"{@code runCrawl} 开头有没有正确 {@code set} 上下文"这件事<b>不是</b>本 IT
 * 独有的覆盖——Task 5 的纯 Mockito 单测 {@code SiteCrawlServiceTest#carries_tenant_context_into_the_async_thread}
 * 已经覆盖了这一层（直接同步调用 {@code runCrawl} 断言线程内可见的 {@code TenantContext}
 * 值），若把 {@code runCrawl} 开头四行搬运删掉，那条单测会先于本 IT 报红
 * （已实测：{@code mvn verify -Dit.test=SiteCrawlIT} 在这种 mutation 下，Surefire 阶段
 * 就会被那条单测拦下、Maven 直接中止，根本轮不到 Failsafe 跑到本类）。
 *
 * <p>⚠️ <b>全程走公开入口</b>（{@code startCrawl}/{@code getProgress}/{@code deleteSite}），
 * 不用 {@code newProgressForTest}/{@code markCrawlingForTest}——那两个方法是包级可见（在
 * {@code com.qingyu.care.service.web} 包），本类在 {@code com.qingyu.care.it} 包够不到。
 * 这个选择顺带换来更高的真实度：{@code startCrawl} 真的经 {@code self} 代理触发
 * {@code @Async}，走的是生产代码实际会走的完整路径（含 {@code launch()} 里"四个
 * {@code TenantContext} 值必须在请求线程取好传进去"那一步），而不是绕开它直接摆一个
 * {@code Progress} 出来。
 *
 * <p>⚠️ <b>本 IT 不能证明覆盖了上一轮遗留问题 C-1</b>（{@code startCrawl} 已去掉
 * {@code @Transactional}——同一事务内投递 {@code @Async} 会让异步线程的 {@code findById}
 * 读不到未提交的站点行，首次开爬非确定性 FAILED「站点不存在」）。原以为"全程走
 * {@code startCrawl}"能顺带触发这条时序竞争，但<b>已实测证伪</b>：把 {@code @Transactional}
 * 临时加回 {@code startCrawl} 后重新编译，连跑 4 次本 IT，<b>4 次全绿，一次都没红</b>。
 * 说明当前写法（轮到终态后断言 {@code status != "FAILED"}）在本机环境下从未观测到那条
 * 竞争真正发生——异步线程被调度的时机稳定地晚于 {@code save()} 提交，这是常态而非巧合，
 * 靠"轮询到终态再断言"这种写法基本等于没测这条竞争。C-1 目前<b>只能靠真机验证</b>
 * （手工把 {@code @Transactional} 加回去再点一次"新建站点并开爬"复现非确定性 FAILED），
 * 没有可靠的自动化手段能确定性触发它——造确定性复现需要往生产代码注入测试专用钩子，
 * 代价大于收益，本次未做。
 *
 * <p>{@code webPageFetcher} 与 {@code knowledgeService} 用 {@code @MockitoBean} 换掉：
 * 本 IT 只关心租户字段与外键落库，不关心真实抓取与嵌入（S3+embedding 全链路本仓库已明确
 * 不测，见 knowledge-ingest-it-skipped 记录）。{@code getKnowledgeBase} 委托真实仓储以保住
 * 归属校验语义，{@code processDocument} 用 doAnswer 模拟"在自己的事务里把状态改完并提交"。
 *
 * <p>不加类级 {@code @Transactional}：异步线程的写入需要真正提交，且测试线程后续的轮询
 * 读取不能被同一个长事务的一级缓存挡住返回陈旧数据（同 {@code WebPageSyncTransactionIT} 的选择）。
 *
 * <p>⚠️ 每次调用 {@code startCrawl}/{@code deleteSite} 前后都显式 set/clear
 * {@code TenantContext}，模拟真实请求经 {@code TenantInterceptor} 的生命周期——服务层的
 * 归属校验（{@code TenantOwnershipGuard}）与 {@code CrawlSite}/{@code Document} 上的
 * Hibernate {@code @Filter} 都读它；测试线程本身不会自动获得任何上下文。
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

    /** 播种：租户 + 应用 + 知识库，全部带正确的租户上下文。 */
    private KnowledgeBase seedKnowledgeBase(String suffix) {
        Application app = seedApp(suffix, "https://example.com");
        TenantContext.setTenantId(app.getTenant().getId());
        TenantContext.setAppId(app.getId());
        try {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setName("库-" + suffix);
            return knowledgeBaseRepository.save(kb);
        } finally {
            TenantContext.clear();
        }
    }

    private CrawlSiteRequest requestFor(String suffix) {
        CrawlSiteRequest req = new CrawlSiteRequest();
        req.setEntryUrl("https://site-" + suffix + ".com/help/");
        req.setScopePrefix("https://site-" + suffix + ".com/help/");
        req.setMaxPages(1);
        req.setMaxDepth(0);
        req.setSyncInterval(SyncInterval.DAILY);
        return req;
    }

    /**
     * 让抓取返回一张固定页面；让 processDocument 把状态改成 COMPLETED 并提交；
     * 让 deleteDocument 真的删掉该文档行。
     *
     * <p>⚠️ {@code deleteDocument} 这个桩不是可有可无的样板——真实
     * {@code KnowledgeServiceImpl.deleteDocument} 最后一步是 {@code documentRepository.delete(doc)}。
     * 若像最初那样不给它打桩（Mockito 对未打桩的 void 方法默认什么都不做），
     * {@code deleteSite(deleteDocuments=true)} 里 {@code Document} 行会继续留在同一个持久化
     * 上下文里、其 {@code crawlSite} 关联仍指向即将被 {@code crawlSiteRepository.delete(site)}
     * 删除的那一行，flush 时 Hibernate 会拒绝并抛
     * {@code TransientPropertyValueException}——这在真实 Hibernate 会话里才会暴露，
     * 纯 Mockito 测不出来，也是本 IT 必须存在的另一个理由。
     */
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
        doAnswer(inv -> {
            documentRepository.deleteById(inv.<Long>getArgument(0));
            return null;
        }).when(knowledgeService).deleteDocument(anyLong());
    }

    /**
     * 建站开爬并轮到终态：set/调用/clear 三步模拟一次真实 HTTP 请求的上下文生命周期，
     * 再轮询到 {@code getProgress} 不再是 RUNNING。
     */
    private CrawlStartResponse startAndAwaitTerminal(KnowledgeBase kb, String suffix) {
        TenantContext.setTenantId(kb.getTenantId());
        TenantContext.setAppId(kb.getAppId());
        CrawlStartResponse resp;
        try {
            resp = siteCrawlService.startCrawl(kb.getId(), requestFor(suffix));
        } finally {
            TenantContext.clear();
        }
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(siteCrawlService.getProgress(resp.jobId()).status).isNotEqualTo("RUNNING"));
        return resp;
    }

    @Test
    @DisplayName("爬取产出的文档带正确的 tenant_id / app_id / crawl_site_id"
            + "（不是对 C-1 的可靠回归覆盖——本条只是走了 startCrawl 这条真实路径，"
            + "并未实测确认能稳定触发那条时序竞争，详见类注释）")
    void crawled_documents_carry_tenant_and_site_fields() {
        KnowledgeBase kb = seedKnowledgeBase("a");
        Long tenantId = kb.getTenantId();
        Long appId = kb.getAppId();
        assertThat(tenantId).isNotNull();
        stubCollaborators();

        CrawlStartResponse resp = startAndAwaitTerminal(kb, "a");

        assertThat(siteCrawlService.getProgress(resp.jobId()).status).isEqualTo("COMPLETED");

        TenantContext.setTenantId(tenantId);
        TenantContext.setAppId(appId);
        List<Document> docs = documentRepository.findByCrawlSiteId(resp.siteId());
        assertThat(docs).hasSize(1);
        Document doc = docs.get(0);
        assertThat(doc.getTenantId()).isEqualTo(tenantId);
        assertThat(doc.getAppId()).isEqualTo(appId);
        assertThat(doc.getCrawlSite().getId()).isEqualTo(resp.siteId());
        assertThat(doc.getContentHash()).isNotNull();
        assertThat(doc.getLastSyncedAt()).isNotNull();
        assertThat(doc.getSyncInterval()).isEqualTo(SyncInterval.DAILY);
    }

    @Test
    @DisplayName("连续爬两个不同租户的站点，第二个的文档租户字段仍正确——线程池会复用线程")
    void does_not_leak_tenant_context_between_consecutive_crawls() {
        KnowledgeBase kbB = seedKnowledgeBase("b");
        KnowledgeBase kbC = seedKnowledgeBase("c");
        assertThat(kbB.getTenantId()).isNotEqualTo(kbC.getTenantId());
        stubCollaborators();

        startAndAwaitTerminal(kbB, "b");
        CrawlStartResponse respC = startAndAwaitTerminal(kbC, "c");
        assertThat(siteCrawlService.getProgress(respC.jobId()).status).isEqualTo("COMPLETED");

        TenantContext.setTenantId(kbC.getTenantId());
        TenantContext.setAppId(kbC.getAppId());
        Document docOfC = documentRepository.findByCrawlSiteId(respC.siteId()).get(0);
        assertThat(docOfC.getTenantId()).isEqualTo(kbC.getTenantId());
        assertThat(docOfC.getAppId()).isEqualTo(kbC.getAppId());
    }

    @Test
    @DisplayName("删站点默认不删文档：文档保留、crawl_site_id 置 null")
    void delete_site_keeps_documents_by_default() {
        KnowledgeBase kb = seedKnowledgeBase("d");
        stubCollaborators();
        CrawlStartResponse resp = startAndAwaitTerminal(kb, "d");
        assertThat(siteCrawlService.getProgress(resp.jobId()).status).isEqualTo("COMPLETED");

        TenantContext.setTenantId(kb.getTenantId());
        TenantContext.setAppId(kb.getAppId());
        Long docId = documentRepository.findByCrawlSiteId(resp.siteId()).get(0).getId();

        try {
            siteCrawlService.deleteSite(resp.siteId(), false);
        } finally {
            TenantContext.clear();
        }

        assertThat(crawlSiteRepository.findById(resp.siteId())).isEmpty();
        Document kept = documentRepository.findById(docId).orElseThrow();
        assertThat(kept.getCrawlSite()).isNull();
        assertThat(kept.getSourceUrl()).isNotNull();   // 仍是可正常定期同步的网页文档
    }

    /**
     * ⚠️ 本条只验证「调了正确的删除入口」——{@code knowledgeService} 在本 IT 里是
     * {@code @MockitoBean}，{@code verify(knowledgeService).deleteDocument(docId)} 断言的是
     * "deleteSite 调用了它"，不是"它内部真的做了什么"。真正删向量与删 S3 文件的逻辑属于
     * {@code knowledgeService} 自己的职责，S3+embedding 全链路本仓库已明确不测
     * （见 knowledge-ingest-it-skipped 记录）。别把这条误读成"验证了级联删除"。
     *
     * <p>{@link #stubCollaborators()} 里 {@code deleteDocument} 的桩会额外删掉文档行
     * （对齐真实 {@code KnowledgeServiceImpl.deleteDocument} 最后一步的
     * {@code documentRepository.delete(doc)}）——这不是为了让断言更好看，而是必需的：
     * 若桩什么都不做（Mockito 对未打桩的 void 方法的默认行为），文档行会继续留在同一个
     * 持久化上下文里、其 {@code crawlSite} 关联仍指向即将被
     * {@code crawlSiteRepository.delete(site)} 删除的行，flush 时 Hibernate 会拒绝并抛
     * {@code TransientPropertyValueException}——这一步已被真实 IT 跑出来过。
     */
    @Test
    @DisplayName("删站点勾选删文档：逐篇走 knowledgeService.deleteDocument（它才会删向量）")
    void delete_site_with_documents_delegates_to_knowledge_service() {
        KnowledgeBase kb = seedKnowledgeBase("e");
        stubCollaborators();
        CrawlStartResponse resp = startAndAwaitTerminal(kb, "e");
        assertThat(siteCrawlService.getProgress(resp.jobId()).status).isEqualTo("COMPLETED");

        TenantContext.setTenantId(kb.getTenantId());
        TenantContext.setAppId(kb.getAppId());
        Long docId = documentRepository.findByCrawlSiteId(resp.siteId()).get(0).getId();

        try {
            siteCrawlService.deleteSite(resp.siteId(), true);
        } finally {
            TenantContext.clear();
        }

        verify(knowledgeService).deleteDocument(docId);
        assertThat(crawlSiteRepository.findById(resp.siteId())).isEmpty();
    }
}

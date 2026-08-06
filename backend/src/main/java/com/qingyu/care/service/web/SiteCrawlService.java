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

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
        /**
         * 归属租户，用于 {@link #launch} 按租户分桶全局并发额度。平台管理员为
         * {@code null}——见 {@link #launch} 关于「null 桶」取舍的说明。
         */
        public final Long tenantId;
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

        Progress(String jobId, Long tenantId, Long siteId, int maxPages) {
            this.jobId = jobId;
            this.tenantId = tenantId;
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
     *
     * <p>⚠️ <b>本方法刻意不带 {@code @Transactional}</b>：带上它会让 {@code save()} 只
     * flush 不 commit，而紧接着 {@code launch()} 就在同一未提交事务内把 {@code self.runCrawl}
     * 投给 {@code @Async} 执行器——异步线程走独立连接，READ COMMITTED 下看不见这次未提交
     * 的 INSERT，第一件事 {@code findById} 就 {@code orElseThrow}，进度非确定性地立刻
     * FAILED（时序纯属运气：异步线程只需被调度 + 四次 ThreadLocal set + 一次 SELECT，
     * 请求线程却要等 launch 返回、startCrawl 返回、再等 TransactionInterceptor 提交，
     * 热池下异步侧完全可能先到）。{@code crawlSiteRepository.save()} 自带迷你事务立即
     * 提交，与 {@code ReembedService.startReembed}（同样投递 {@code @Async} 前不建
     * 需要被异步读的行）一致。{@code updateSite}/{@code deleteSite} 不投递异步任务，
     * {@code @Transactional} 保留。
     *
     * <p>⚠️ 建站前查重：同一 {@code (知识库, 入口地址)} 建两个站点会各自跑一遍 BFS，
     * 两边的 {@code findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc} 互相看不见对方
     * 未提交的建档，产出重复文档与重复向量、互相挤占检索 top-K——而「点了没反应就再点一次」
     * 是最常见的用户行为，站点级互斥（按站点 id）拦不住两次点击各自建出的新 id。
     *
     * <p>⚠️ 查重键必须是 {@link LinkExtractor#normalize} 之后的值，不能是原始
     * {@code req.getEntryUrl()}：{@code https://x.com/help}、{@code https://x.com/help/}、
     * {@code https://x.com/help#top} 是同一页面的不同写法，裸字符串比对会把它们各自建成
     * 一个站点、绕过查重；若两个站点恰好并发跑（{@code maxConcurrentJobs} 默认 2 刚好够），
     * 同一网页会被建两篇文档、两份向量。{@link #applyRequest} 内部也会同样规范化后再落库，
     * 两处用同一个规范化结果，保证「查的键」与「存的键」一致。
     */
    public CrawlStartResponse startCrawl(Long knowledgeBaseId, CrawlSiteRequest req) {
        KnowledgeBase kb = knowledgeService.getKnowledgeBase(knowledgeBaseId);
        String normalizedEntryUrl = normalizeEntryUrl(req.getEntryUrl());
        requireNoDuplicateEntryUrl(knowledgeBaseId, normalizedEntryUrl, null);
        CrawlSite site = crawlSiteRepository.save(applyRequest(new CrawlSite(), req, kb));
        return new CrawlStartResponse(site.getId(), launch(site));
    }

    /** 重新爬取已有站点，复用其配置（已存在的 URL 会被跳过，只建新页）。 */
    public String recrawl(Long siteId) {
        return launch(requireOwnedSite(siteId));
    }

    /**
     * 改站点配置。下一轮定期同步就会用上新的 {@code contentSelector}。
     *
     * <p>⚠️ 设计文档 §7.1 里 PUT 的语义是「改配置」（前缀/深度/页数/选择器/周期），
     * {@code entryUrl} 本不在可改字段清单里；但实现与 {@link #startCrawl} 共用
     * {@link #applyRequest}，它会无条件套用 {@code req.getEntryUrl()}，等于顺带把入口
     * 地址也变成了可改字段。若不在这里补查重，运营能把 B 站点的入口改成与 A 站点相同
     * （规范化后），两个不同 {@code siteId} 互不受 {@link #crawlingSites} 站点级互斥
     * 约束，各点一次「重新爬取」就会并发跑两遍 BFS，各自的
     * {@code findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc} 看不见对方未提交的插入
     * ——同 {@link #startCrawl} 类文档点名过的重复建档/重复向量后果，只是从「建站」这扇门
     * 换成了「改配置」这扇门。
     */
    @Transactional
    public CrawlSite updateSite(Long siteId, CrawlSiteRequest req) {
        CrawlSite site = requireOwnedSite(siteId);
        String normalizedEntryUrl = normalizeEntryUrl(req.getEntryUrl());
        requireNoDuplicateEntryUrl(site.getKnowledgeBase().getId(), normalizedEntryUrl, siteId);
        return crawlSiteRepository.save(applyRequest(site, req, site.getKnowledgeBase()));
    }

    /**
     * 查重：同一知识库下不能有两个站点共用规范化后相同的入口地址。
     *
     * @param excludeSiteId 改配置时传本站点 id，排除自身（否则「没改入口」也会被判成
     *                       撞了自己）；建站时传 {@code null}，不排除任何行。
     */
    private void requireNoDuplicateEntryUrl(Long knowledgeBaseId, String normalizedEntryUrl, Long excludeSiteId) {
        crawlSiteRepository.findFirstByKnowledgeBaseIdAndEntryUrlOrderByIdAsc(knowledgeBaseId, normalizedEntryUrl)
                .filter(existing -> !existing.getId().equals(excludeSiteId))
                .ifPresent(existing -> {
                    throw new BusinessException("该入口地址已有爬取站点，请直接用「重新爬取」");
                });
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
     *
     * <p>⚠️ 爬取中的站点禁止删除：{@code crawl()} 收尾会重新 {@code findById} 该站点行
     * 并 {@code save()}，若这期间站点已被删，Hibernate 对"detached 实体、按 id 加载
     * 返回 null"会回落到当作 transient 处理——IDENTITY 策略下重新生成主键，等于把刚删的
     * 站点当新行插了回来，界面表现为「删不掉/删了又回来」。
     */
    @Transactional
    public void deleteSite(Long siteId, boolean deleteDocuments) {
        CrawlSite site = requireOwnedSite(siteId);
        if (crawlingSites.contains(siteId)) {
            throw new BusinessException("该站点正在爬取中，请等待完成后再删除");
        }
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

    /**
     * 查进度。{@code jobId} 是 UUID 不可枚举，但仍要挡跨租户查询——一旦 jobId 经日志/工单/
     * 截图等旁路泄漏给别的租户，对方就能借它看到别人站点的爬取详情（{@code siteId}/
     * 已爬页数/失败原因等）。
     *
     * <p>⚠️ 站点<b>已被删除</b>时不校验、直接放行：进度是内存态，站点都没了说明这轮爬取早已
     * 结束，此时仍把最后一次的进度给回调用方是合理的，且这条路径不会泄漏任何还"活着"的
     * 站点信息（没有活的站点可泄漏）。不能用 {@link #requireOwnedSite}——它对"站点不存在"
     * 会直接抛 404，语义与这里要的"不存在则放行"相反，必须单独处理。
     */
    public Progress getProgress(String jobId) {
        Progress p = jobs.get(jobId);
        if (p == null) {
            throw new BusinessException(404, "爬取任务不存在: " + jobId);
        }
        crawlSiteRepository.findById(p.siteId).ifPresent(site -> {
            if (!TenantOwnershipGuard.mutableInCurrentContext(site.getTenantId(), site.getAppId())) {
                // 按「不存在」同语义处理，防枚举探测——同 requireOwnedSite 的约定
                throw new BusinessException(404, "爬取任务不存在: " + jobId);
            }
        });
        return p;
    }

    // ===== 启动与互斥 =====

    /**
     * 建进度、抢互斥、投递异步任务。
     *
     * <p>⚠️ {@code self.runCrawl} 投递失败（线程池拒绝）必须回滚<b>两样东西</b>：
     * <ul>
     *   <li>站点互斥（{@code crawlingSites}）——不回滚该站点会永久无法再爬；</li>
     *   <li>刚建的 {@code Progress}（{@code jobs}）——不回滚它会永远停在 RUNNING，
     *       被 {@link #jobs} 从未有任何地方 remove 的 Progress 长期占着，
     *       {@code running} 计数只增不减，最终把全局并发额度永久占满，只能重启恢复。</li>
     * </ul>
     *
     * <p>⚠️ 并发额度<b>按租户分桶</b>而非全局共享：早期版本扫全局 {@code jobs} 计数，
     * 一个租户起两个 500 页 × 10s/页的任务就能占死约 83 分钟的额度，期间<b>同实例其他
     * 所有租户</b>点「爬取」都会被拒——既是跨租户可用性耦合，又把平台全局活动量回显给了
     * 租户（旧文案里直接带 {@code running} 数字）。改成按 {@code TenantContext.getTenantId()}
     * 分桶后各租户的额度互不干扰；桶键取自请求线程（{@code launch} 本就跑在请求线程，
     * 与四个 {@code TenantContext} 值搬运进 {@code @Async} 线程是两回事，不冲突）。
     *
     * <p>平台管理员的 {@code tenantId} 恒为 {@code null}，本方法让所有平台管理员发起的爬取
     * 共用同一个「null 桶」而不是彻底不设限：这等价于把「平台管理员」当成单一身份对待，
     * 与任意一个真实租户受同样的额度约束，既不会重新引入「一个身份占满全局额度」的旧问题
     * （因为它不再挤占真实租户的桶），也不需要为平台管理员发明新的分桶维度（如按被操作的
     * 知识库/租户二次拆分——收益不明显，且平台管理员本就是低频操作角色）。
     */
    private String launch(CrawlSite site) {
        Long tenantId = TenantContext.getTenantId();
        long running = jobs.values().stream()
                .filter(p -> "RUNNING".equals(p.status) && java.util.Objects.equals(p.tenantId, tenantId))
                .count();
        if (running >= props.getMaxConcurrentJobs()) {
            // 不回显 running 数字：那是平台全局并发活动量，租户不该看到
            throw new BusinessException("已有爬取任务在进行，请稍后再试");
        }
        if (!crawlingSites.add(site.getId())) {
            throw new BusinessException("该站点已有爬取在进行，请等待完成");
        }
        Progress p = new Progress(UUID.randomUUID().toString(), tenantId, site.getId(), site.getMaxPages());
        jobs.put(p.jobId, p);
        try {
            // 必须经 self 才能命中 Spring 代理让 @Async 真正生效（同类直调会退化成同步阻塞请求线程）。
            // 四个 TenantContext 值必须在请求线程取好传进去——@Async 线程没有上下文
            self.runCrawl(p, site.getId(), TenantContext.getTenantId(), TenantContext.getAppId(),
                    TenantContext.getUserId(), TenantContext.getAccessLevel());
            return p.jobId;
        } catch (RuntimeException e) {
            crawlingSites.remove(site.getId());
            jobs.remove(p.jobId);
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
            // 兜底：catch 只抓 Exception，抓不住 Error（如 OOM——5MB×N 页 DOM + 嵌入下
            // 并不遥远）。Error 逃逸时 catch 不执行，若 finally 不补一道终态就会把这条
            // Progress 永远留在 RUNNING，累加占满全局并发额度（同 launch 那两行的教训）
            if ("RUNNING".equals(p.status)) {
                p.status = "FAILED";
            }
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
        if (!robots.allows(entry)) {
            // 入口页专门检查、提前失败：BFS 循环里 robots 拒绝分支只是 continue（见下方），
            // 若入口页恰好被拒绝，队列会直接清空、循环正常退出、任务报 COMPLETED 且 0 页——
            // 用户拿不到任何提示、以为爬完了实际什么都没抓到
            throw new BusinessException("入口地址被该站点 robots.txt 禁止抓取: " + entry);
        }
        long delayMs = resolveDelayMs(robots.crawlDelayMs(), props.getDefaultDelayMs(), props.getMaxDelayMs());
        if (robots.crawlDelayMs() != null && robots.crawlDelayMs() > props.getMaxDelayMs()) {
            log.warn("站点声明的 robots Crawl-delay 超过平台上限，已钳制 origin={} 声明值ms={} 采用值ms={}",
                    originOf(entry), robots.crawlDelayMs(), delayMs);
        }

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

        // ⚠️ 收尾必须重新 findById 取当前行再改：本方法无环绕事务，最上面 findById 出来的
        // site 在这几分钟的 BFS 期间早已 detached，直接 save(site) 会走 em.merge()，
        // 把开爬那一刻的全部陈旧字段（含 contentSelector/maxPages/scopePrefix 等运营
        // 最想在爬取期间边看边调的字段）覆盖回库、静默抹掉爬取期间的任何配置修改，
        // 且无异常无日志——同 WebPageSyncService 类注释点名过的"陈旧字段 merge 回库"
        // 同一个坑。这里改用重新查询而不是给 crawl() 整体包一层长事务：见类文档
        // §站点级互斥的取舍，几分钟的长任务不能用一个大事务。
        // 顺带也是"爬取中删站点"场景的第二道防线（第一道是 deleteSite 显式拒绝正在爬取
        // 的站点）：万一还是被删了，站点已不存在时 findById 为空，直接跳过，不会把
        // merge 出的"新行"当成正常收尾。
        crawlSiteRepository.findById(siteId).ifPresentOrElse(fresh -> {
            fresh.setLastCrawledAt(LocalDateTime.now());
            crawlSiteRepository.save(fresh);
        }, () -> log.debug("爬取收尾时站点已不存在，跳过更新 lastCrawledAt siteId={}", siteId));
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
            if (existing.getCrawlSite() == null) {
                adoptIntoSite(existing.getId(), site);
            }
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
     * 把爬取前就已存在、尚未归属任何站点的文档收养进本站点（一期手工单页添加、
     * 后来被本站点的 BFS 发现到的场景）。不收养的后果：定期同步永远读不到站点的
     * {@code contentSelector}（{@code WebPageSyncService.syncDocument} 靠
     * {@code doc.getCrawlSite()} 取选择器）、{@code deleteSite(deleteDocuments=true)}
     * 按 {@code crawlSiteId} 查不到它而漏删、按站点统计的页数也会少算。
     *
     * <p>⚠️ 必须在 {@code syncDocument} 执行<b>之后</b>重新 {@code findById} 取当前行再改，
     * 不能复用调用方传入的、{@code syncDocument} 执行前就查出来的 {@code existing} 引用：
     * {@code syncDocument} 会更新同一行的 {@code content}/{@code lastSyncedAt}/
     * {@code contentHash} 等字段并提交，若这里拿旧引用 {@code save()}，会把
     * {@code syncDocument} 刚提交的新内容用旧值 merge 覆盖回去——比 {@code crawl()}
     * 收尾那处 detached merge 更隐蔽，因为覆盖的是"几毫秒前"而非"几分钟前"的旧值，
     * 但原理相同。
     */
    private void adoptIntoSite(Long documentId, CrawlSite site) {
        documentRepository.findById(documentId).ifPresent(fresh -> {
            if (fresh.getCrawlSite() == null) {
                fresh.setCrawlSite(site);
                documentRepository.save(fresh);
            }
        });
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

        // 合成一次查询：先前分两次 findById（一次取 status 判断、一次取实体改字段）在无
        // 环绕事务下是两次真查库；合成一次同时拿到状态判据与待改实体
        Document fresh = documentRepository.findById(doc.getId())
                .orElseThrow(() -> new BusinessException("文档摄取后丢失: " + doc.getId()));
        if (fresh.getStatus() != DocumentStatus.COMPLETED) {
            throw new BusinessException("摄取未成功（status=" + fresh.getStatus()
                    + "），contentHash 不落库以便定期同步重试");
        }
        fresh.setContentHash(result.contentHash());
        fresh.setLastSyncedAt(LocalDateTime.now());
        documentRepository.save(fresh);
    }

    // ===== 参数处理与归属校验 =====

    /**
     * 把请求里的字段套到站点上（建与改共用），含规范化、钳制与同域校验。
     *
     * <p>⚠️ {@link #deriveScopePrefix} 必须喂<b>原始</b> {@code req.getEntryUrl()}，
     * 不能喂规范化后的 {@code entryUrl}：{@link LinkExtractor#normalize} 会把
     * {@code https://x.com/help/} 的尾斜杠去掉，而 {@code deriveScopePrefix} 正是靠
     * 「路径是否以 / 结尾」区分目录形态与文件形态——喂规范化后的值会把用户明明写的目录形态
     * 误判成文件形态，推导出的范围从「只爬 /help/ 下面」错误地放大成「整站」。
     * 落库与查重键用规范化后的 {@code entryUrl} 没有这个问题：规范化只是让「同一页面的
     * 不同写法」折叠成同一个字符串，不改变语义。
     */
    private CrawlSite applyRequest(CrawlSite site, CrawlSiteRequest req, KnowledgeBase kb) {
        String entryUrl = normalizeEntryUrl(req.getEntryUrl());
        String scopePrefix = (req.getScopePrefix() == null || req.getScopePrefix().isBlank())
                ? deriveScopePrefix(req.getEntryUrl())
                : req.getScopePrefix();
        requireSameHost(entryUrl, scopePrefix);

        site.setKnowledgeBase(kb);
        site.setEntryUrl(entryUrl);
        site.setScopePrefix(scopePrefix);
        site.setMaxPages(clamp(req.getMaxPages(), 50, 1, props.getMaxPagesLimit()));
        site.setMaxDepth(clamp(req.getMaxDepth(), 3, 0, props.getMaxDepthLimit()));
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
     * 规范化入口 URL，非法输入抛业务异常。{@link LinkExtractor#normalize} 对非法/非
     * http(s)/无主机名一律返回 {@code null}——本方法把这个 {@code null} 收口成清爽的
     * 业务异常，调用方（{@link #startCrawl} 的查重、{@link #applyRequest} 的落库）不用
     * 各自重复判空。
     */
    private static String normalizeEntryUrl(String rawEntryUrl) {
        String normalized = LinkExtractor.normalize(rawEntryUrl);
        if (normalized == null) {
            throw new BusinessException("入口 URL 非法: " + rawEntryUrl);
        }
        return normalized;
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

    /**
     * {@code scheme://host[:port]}。
     *
     * <p>⚠️ 缺协议或主机名必须在这里显式拒绝：{@code new URI(...)} 对无协议的相对引用
     * （如 {@code "x.com/help"}）并不抛 {@code URISyntaxException}，{@code getScheme()}
     * 只是静默返回 null——而 {@code new StringBuilder((String) null)} 这个构造器会抛
     * {@code NullPointerException}（与 {@code append(null)} 不同，{@code append} 对 null
     * 是转成字面量 "null" 不抛，构造器却会先调 {@code str.length()}），未经处理时对外
     * 表现为一个无业务语义的 500 而非清爽的业务异常。调用方 {@link #deriveScopePrefix}
     * 在 Task 6 的控制器接线落地前拿到的 {@code entryUrl} 还没有经过任何格式校验，
     * 这条防线不能省。
     */
    static String originOf(String url) {
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new BusinessException("URL 非法: " + url);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new BusinessException("URL 非法（缺少协议或主机名）: " + url);
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

    /**
     * 入参为空取默认值，超硬顶钳到硬顶，低于 {@code min} 抬回下界。
     *
     * <p>{@code min} 按字段区分（页数下界 1、深度下界 0）：早期版本两者共用固定 0 下界，
     * 会让 {@code maxPages} 被钳成 0——{@code while} 循环一次都不进，任务照样报
     * COMPLETED、0 页，且没有任何异常或日志提示。
     */
    static int clamp(Integer value, int defaultValue, int min, int limit) {
        int v = value == null ? defaultValue : value;
        return Math.max(min, Math.min(v, limit));
    }

    /**
     * 解析实际采用的抓取间隔：优先站点声明的 {@code robots Crawl-delay}，否则用平台默认，
     * 但<b>钳到平台上限</b>——{@code Crawl-delay} 是被爬站点（第三方）可控的值，不设上限
     * 时对方声明 {@code Crawl-delay: 300} 就能让每页睡 5 分钟，乘以 500 页硬顶就是
     * 40 多小时，全程占着共享的 {@code applicationTaskExecutor} 一个线程。
     * 包级 static 免容器单测（同 {@link #clamp} 惯例）。
     */
    static long resolveDelayMs(Long robotsCrawlDelayMs, long defaultDelayMs, long maxDelayMs) {
        long candidate = robotsCrawlDelayMs != null ? robotsCrawlDelayMs : defaultDelayMs;
        return Math.min(candidate, maxDelayMs);
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

    /**
     * 单测直接构造进度，避免为了拿 Progress 去跑一遍 launch 的互斥与并发检查。
     * {@code tenantId} 取自调用时的 {@code TenantContext}（同 {@link #launch} 的取法），
     * 未显式设置上下文的既有用例会拿到 {@code null}，与它们历来的隐式假设一致。
     */
    Progress newProgressForTest(String jobId, Long siteId, int maxPages) {
        Progress p = new Progress(jobId, TenantContext.getTenantId(), siteId, maxPages);
        jobs.put(jobId, p);
        return p;
    }

    /** 单测模拟「该站点已有爬取在进行」。 */
    void markCrawlingForTest(Long siteId) {
        crawlingSites.add(siteId);
    }
}

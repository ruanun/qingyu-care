package com.qingyu.care.service.web;

import com.qingyu.care.service.LongTaskTenantLimiter;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import com.qingyu.care.config.AsyncConfig;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.qingyu.care.util.AfterCommit;

/**
 * 网页文档的建档与同步编排。抓取本身在 {@link WebPageFetcher}，本类只管「结果怎么落到知识库」。
 *
 * <p>三条分支（见设计文档 §4.2）：内容未变只更新时间戳（不重嵌，省 embedding 成本）；
 * 内容变了才走 {@code processDocument} 重切重嵌；抓取失败保留旧内容与旧向量，
 * 只记错误与失败计数——官网临时 502 不该让知识库瞬间失去这篇内容。
 *
 * <p>重嵌调用同步的 {@code processDocument} 而非异步的 {@code reprocessDocument}：一是避免
 * {@code reprocessDocument} 内部再起一个 {@code @Async} 线程、在本方法所在事务提交前就读到
 * 旧行的竞态（只拔掉了这一条，{@code addWebPage}/{@code syncDocumentAsync} 本身经
 * {@code @Async} 调用到本方法的竞态不在此列，靠 {@code self.syncDocumentAsync} 让
 * {@code @Async} 真正生效来保证）；二是让本方法能感知重嵌结果，据此决定 {@code contentHash}
 * 是否落库——写早了会把「重嵌失败」永久误判成「已处理，跳过」，下一轮同步再也不会重试
 * （这是本类曾经的一个 Critical 缺陷）。
 *
 * <p>⚠️ {@code syncDocumentAsync} 内部调用本方法必须经 {@code self}：同类直调不经代理会让
 * 本方法的 {@code @Transactional} 失效，{@code findById}/{@code save} 各自落进独立的迷你
 * 事务，方法体内持有的 {@code doc} 中途即变 detached，后续对它的 {@code save()} 会把陈旧
 * 字段 merge 回库、静默覆盖 {@code processDocument} 刚提交的结果（这是本类曾经的第二个
 * Critical 缺陷，只在 {@code syncDocumentAsync} 这条主入口上生效——调度器路径经注入 bean
 * 调用本方法、本身经代理，从未受影响）。
 *
 * @author ruan
 */
@Service
@Slf4j
public class WebPageSyncService {

    /** 连续失败到此次数后自动转手动，避免死链每小时重试一年。 */
    private static final int MAX_FAILURES = 10;

    /** {@code document.name} / {@code original_filename} 都是 varchar(255)。 */
    private static final int NAME_MAX_LENGTH = 255;

    private final DocumentRepository documentRepository;
    private final KnowledgeService knowledgeService;
    private final WebPageFetcher webPageFetcher;

    // 自注入解决 @Async 同类调用不生效问题（同 KnowledgeServiceImpl/EmbeddingMigrationService 范式）
    private WebPageSyncService self;

    public WebPageSyncService(DocumentRepository documentRepository,
                               KnowledgeService knowledgeService,
                               WebPageFetcher webPageFetcher) {
        this.documentRepository = documentRepository;
        this.knowledgeService = knowledgeService;
        this.webPageFetcher = webPageFetcher;
    }

    private LongTaskTenantLimiter longTaskLimiter;

    /** setter 注入：构造函数被多条单测手工调用，见 LongTaskTenantLimiter。没注入会 NPE 而不是静默跳过限流。 */
    @Autowired
    public void setLongTaskLimiter(LongTaskTenantLimiter longTaskLimiter) {
        this.longTaskLimiter = longTaskLimiter;
    }

    @Autowired
    @Lazy
    public void setSelf(WebPageSyncService self) {
        this.self = self;
    }

    /**
     * 建档 + 异步首次抓取。同步返回是为了让「添加」按钮立刻有反馈，
     * 沿用 uploadDocument 的范式（同步建档 PENDING，抓取与摄取走异步）。
     *
     * <p>⚠️ 知识库必须经 {@code knowledgeService.getKnowledgeBase(...)} 取得——它内部做了
     * {@code TenantOwnershipGuard.belongsToCurrentContext} 显式归属校验；直接用
     * {@code knowledgeBaseRepository.findById(...)} 不受 Hibernate {@code @Filter} 保护，
     * 会让任意租户遍历 {@code knowledgeBaseId} 把网页文档插进别人的知识库（曾经的 Critical 越权，
     * 同 {@code KnowledgeServiceImpl.uploadDocument} 的既有范式）。
     */
    @Transactional
    public Document addWebPage(Long knowledgeBaseId, String url, SyncInterval interval) {
        KnowledgeBase kb = knowledgeService.getKnowledgeBase(knowledgeBaseId);
        // 先占名额再建档，超限直接拒绝；事务回滚时名额自动归还（2026-09-30 安全审查 M6）
        LongTaskTenantLimiter.Permit permit = longTaskLimiter.acquire();

        Document doc = Document.builder()
                .knowledgeBase(kb)
                // 截断：name/original_filename 是 varchar(255)，而 URL 无长度约束（source_url
                // 列本身是 2048）。带 UTM 参数与锚点的长网址很日常，不截断时这里的 save 会
                // 立即 INSERT（IDENTITY 主键强制 flush）并抛 value too long，用户拿到一个
                // 不可读的 500
                .name(truncate(url, NAME_MAX_LENGTH))    // 首次抓取成功后会被网页标题覆盖
                .originalFilename(truncate(url, NAME_MAX_LENGTH))
                .documentType(DocumentType.URL)
                .status(DocumentStatus.PENDING)
                .sourceUrl(url)
                .syncInterval(interval == null ? SyncInterval.MANUAL : interval)
                .build();
        doc = documentRepository.save(doc);

        // 两件事都不能省：
        //   · 必须经 self 调用才命中 Spring 代理，@Async 才真正生效（同类直调会被隐式 this. 绕过代理）；
        //   · 必须经 AfterCommit 推迟到本事务提交之后再投递——本方法带 @Transactional，
        //     直接投递时异步线程可能早于提交被调度，READ COMMITTED 下 syncDocument 的 findById
        //     取不到刚建的行，只会留一句「网页同步跳过：文档不存在」的 warn，文档卡在 PENDING
        //     再无人处理（同站点爬取 C-1 那条竞态）。
        final Long docId = doc.getId();
        AfterCommit.run(() -> permit.launch(() -> self.syncDocumentAsync(docId)));
        return doc;
    }

    /** 异步入口：手动「立即同步」用它，立刻返回、前端轮询文档状态看结果。 */
    @Async(AsyncConfig.LONG_TASK_EXECUTOR)
    public java.util.concurrent.CompletableFuture<Void> syncDocumentAsync(Long documentId) {
        // 必须走 self：同类直调不经代理，syncDocument 的 @Transactional 会失效——
        // findById/save 各自落进自己的迷你事务，doc 在方法体内途中就已 detached，
        // 后面对它的 save() 会把陈旧字段 merge 回库，静默覆盖 processDocument 刚提交的结果
        // （同 KnowledgeServiceImpl.processDocumentAsync 范式，这里曾经漏掉）
        self.syncDocument(documentId);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * 同步一篇网页文档。整体 fail-soft：任何失败都只记录在文档上，不抛穿——
     * 调度器批量调用时，一篇失败不能中断其余。
     */
    @Transactional
    public void syncDocument(Long documentId) {
        // 互斥：同一文档并发重嵌会产出重复向量（详见 tryLockDocumentProcessing 的注释）。
        // 抢不到说明另一个事务正在处理这一篇，直接返回——它做的事和本轮完全一样，
        // 重复做既无必要又有害。锁随本方法的事务结束自动释放。
        // 这一次是"外层"互斥，把抓取+比对 hash+重嵌+落 contentHash 整段圈进临界区；
        // processDocument 内部还会再抢一次同一把锁（咨询锁同事务可重入，不会自锁），
        // 那一次才覆盖得住重嵌批任务/手动重新处理这两条不经本方法的入口。
        if (!documentRepository.tryLockDocumentProcessing(documentId)) {
            log.info("网页同步跳过：同一文档已有同步在进行 id={}", documentId);
            return;
        }

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
            // 选择器取自所属爬取站点（单页手动添加的文档 crawlSite 为 null，传 null 即走启发式，
            // 行为与第一期完全一致）。刻意读站点行而不冗余到文档上——改了站点选择器之后，
            // 已建文档的下一轮同步立刻用新值，不需要回填 N 行
            String selector = doc.getCrawlSite() == null ? null : doc.getCrawlSite().getContentSelector();
            WebPageFetcher.FetchResult result = webPageFetcher.fetch(doc.getSourceUrl(), selector);
            // 注意：失败计数/errorMessage 不在这里清零——本轮是否真的"成功"要等重嵌结果出来，
            // 提前清零会让「抓取总是成功、重嵌总是失败」的场景每轮都被重置回 1，永远碰不到
            // MAX_FAILURES 熔断（见下方 recordSyncFailure 的调用点与类注释）
            doc.setLastSyncedAt(LocalDateTime.now());

            // 跳过重嵌的条件是「内容没变」<b>且</b>「上一轮确实嵌成功了」，两个都要。
            // 只看 hash 会漏掉这种局面：运营跑了一次重嵌批任务或点了「重新处理」，
            // processDocument 先删光旧向量、随后 embedding 调用失败落 FAILED，而
            // contentHash 是在别处写的、不会被它清掉——此后每一轮同步都判「内容未变」
            // 直接返回，这篇网页 0 向量、永远检索不到、且不再有任何自动重试，全程无异常无日志
            if (result.contentHash().equals(doc.getContentHash())
                    && doc.getStatus() == DocumentStatus.COMPLETED) {
                // 内容未变：不重嵌，本轮视为成功
                doc.setSyncFailureCount(0);
                doc.setErrorMessage(null);
                documentRepository.save(doc);
                log.debug("网页内容未变，跳过重嵌 id={} url={}", documentId, doc.getSourceUrl());
                return;
            }

            // 截断同理（见 addWebPage）：网页标题同样没有长度约束。这条比长 URL 更隐蔽——
            // setName 之后的 save 此刻还没发 SQL，直到 processDocument 首行的原生删除
            // 强制 flush 整个 session 才报长度超限；而那一行在 processDocument 自己的 try
            // 之外，异常直穿到下面的 catch，recordSyncFailure 的 save 对托管实体是 no-op
            // 不报错、方法正常返回，最后事务提交时整体回滚——失败计数与 errorMessage 一次
            // 都没落库，这篇网页每小时重试、永远碰不到熔断，界面上还什么都看不到
            doc.setName(truncate(result.title(), NAME_MAX_LENGTH));
            doc.setContent(result.content());
            documentRepository.save(doc);

            // 同步调用：self.syncDocument 经代理让本方法的 @Transactional 真正生效，
            // knowledgeService.processDocument 因此与本方法共享同一持久化上下文——Hibernate
            // 一级缓存/identity map 下，下面这次显式回读实际拿到的还是同一个 doc 托管实例，
            // 与直接读 doc.getStatus() 等价，并不能真正独立于"同一托管实例"这个前提。
            // ⚠️ 訂正：若未来给 processDocument 加 REQUIRES_NEW，identity map 会挡在前面
            // 不查库，这次显式回读同样看不见内层提交的状态——到时候判据要换别的机制，不是
            // 靠这次显式查询就自动兜底。写成显式回读单纯是为了与（已移除的）批量重嵌 runReembed
            // 的既有写法（它本身没有环绕事务，回读是真的查库）保持同一种表达方式，便于统一
            // 理解，不是这里真的多一层保护
            knowledgeService.processDocument(documentId);
            DocumentStatus after = documentRepository.findById(documentId)
                    .map(Document::getStatus)
                    .orElse(null);

            if (after == DocumentStatus.COMPLETED) {
                // 重嵌成功才落 contentHash：写早了会让下一轮同步误判"内容未变"而永久跳过重试
                doc.setContentHash(result.contentHash());
                doc.setSyncFailureCount(0);
                doc.setErrorMessage(null);
                documentRepository.save(doc);
                log.info("网页内容已更新并重新嵌入 id={} url={}", documentId, doc.getSourceUrl());
            } else {
                // 重嵌失败也要计入失败计数、接入熔断阈值——否则一篇永远嵌不进去的网页
                // 会被每小时重试到天荒地老，永远碰不到 MAX_FAILURES 自动转手动。
                // reason 取 doc.getErrorMessage()（processDocument 刚在同一持久化上下文里
                // 写的真实原因，如 Tika 解析异常/embedding 调用失败的具体 message）而不是
                // 写死通用文案——直接覆盖会把根因冲掉，运营排障只能去翻服务器日志
                String reason = doc.getErrorMessage() != null ? doc.getErrorMessage() : "重新嵌入失败";
                recordSyncFailure(doc, reason);
                log.warn("网页内容已抓取但重新嵌入失败，contentHash 暂不落库以便下一轮同步重试 id={} url={}",
                        documentId, doc.getSourceUrl());
            }

        } catch (Exception e) {
            // 保留旧内容与旧向量：官网临时故障不该让这篇知识消失
            recordSyncFailure(doc, e.getMessage());
            log.warn("网页同步失败 id={} url={}: {}", documentId, doc.getSourceUrl(), e.getMessage());
        }
    }

    /**
     * 累加连续失败次数并落库；达到 {@link #MAX_FAILURES} 自动转手动同步，避免死链每小时
     * 重试一年。抓取失败、重嵌失败共用此逻辑——对本轮同步而言两者都是"没有成功"。
     *
     * <p>状态只在「从未成功过」时才置 FAILED：
     * <ul>
     *   <li>{@code contentHash == null} 说明这篇网页一次都没嵌进去过（哈希只与嵌入成功
     *       同时写），文档此刻停在 PENDING。不置 FAILED 的话，界面显示的是「等待处理」的
     *       时钟图标，而列表里的 errorMessage 只在 FAILED 时才渲染——加一个 404 的网址，
     *       运营看到的是一篇永远"处理中"、没有任何错误提示的文档。</li>
     *   <li>已成功过的<b>不动 status</b>：旧内容与旧向量还在正常服务，把它标成 FAILED 是
     *       撒谎（官网临时 502 不该让这篇知识在界面上"消失"）。这条路径靠 errorMessage +
     *       syncFailureCount 在界面上提示，前端展示条件已相应放宽。</li>
     * </ul>
     */
    private void recordSyncFailure(Document doc, String reason) {
        int failures = (doc.getSyncFailureCount() == null ? 0 : doc.getSyncFailureCount()) + 1;
        doc.setSyncFailureCount(failures);
        String message = "同步失败(" + failures + "/" + MAX_FAILURES + "): " + reason;
        if (failures >= MAX_FAILURES) {
            doc.setSyncInterval(SyncInterval.MANUAL);
            message += "；已停止自动同步，请检查 URL 是否有效";
        }
        doc.setErrorMessage(message);
        if (doc.getContentHash() == null) {
            doc.setStatus(DocumentStatus.FAILED);
        }
        documentRepository.save(doc);
    }

    /**
     * 按 UTF-16 码元截断到 {@code max}，供 varchar(255) 列使用。
     *
     * <p>PostgreSQL 的 varchar(n) 按<b>字符</b>计数而 Java 的 {@code length()} 按码元，
     * 故按码元截断是偏保守的一侧，绝不会超出列宽。末位若是高代理项则一并丢弃——切断代理对
     * 会留下一个孤立代理，落库时 PostgreSQL 直接报「invalid byte sequence for encoding UTF8」。
     */
    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}

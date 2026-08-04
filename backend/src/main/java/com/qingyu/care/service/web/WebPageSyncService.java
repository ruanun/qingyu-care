package com.qingyu.care.service.web;

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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

    private final DocumentRepository documentRepository;
    private final KnowledgeService knowledgeService;
    private final WebPageFetcher webPageFetcher;

    // 自注入解决 @Async 同类调用不生效问题（同 KnowledgeServiceImpl/ReembedService 范式）
    private WebPageSyncService self;

    public WebPageSyncService(DocumentRepository documentRepository,
                               KnowledgeService knowledgeService,
                               WebPageFetcher webPageFetcher) {
        this.documentRepository = documentRepository;
        this.knowledgeService = knowledgeService;
        this.webPageFetcher = webPageFetcher;
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

        // 必须经 self 调用才能命中 Spring 代理，@Async 才会真正生效（同类直调会被隐式 this. 绕过代理）
        self.syncDocumentAsync(doc.getId());
        return doc;
    }

    /** 异步入口：手动「立即同步」用它，立刻返回、前端轮询文档状态看结果。 */
    @Async
    public void syncDocumentAsync(Long documentId) {
        // 必须走 self：同类直调不经代理，syncDocument 的 @Transactional 会失效——
        // findById/save 各自落进自己的迷你事务，doc 在方法体内途中就已 detached，
        // 后面对它的 save() 会把陈旧字段 merge 回库，静默覆盖 processDocument 刚提交的结果
        // （同 KnowledgeServiceImpl.processDocumentAsync 范式，这里曾经漏掉）
        self.syncDocument(documentId);
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
            // 注意：失败计数/errorMessage 不在这里清零——本轮是否真的"成功"要等重嵌结果出来，
            // 提前清零会让「抓取总是成功、重嵌总是失败」的场景每轮都被重置回 1，永远碰不到
            // MAX_FAILURES 熔断（见下方 recordSyncFailure 的调用点与类注释）
            doc.setLastSyncedAt(LocalDateTime.now());

            if (result.contentHash().equals(doc.getContentHash())) {
                // 内容未变：不重嵌，本轮视为成功
                doc.setSyncFailureCount(0);
                doc.setErrorMessage(null);
                documentRepository.save(doc);
                log.debug("网页内容未变，跳过重嵌 id={} url={}", documentId, doc.getSourceUrl());
                return;
            }

            doc.setName(result.title());
            doc.setContent(result.content());
            documentRepository.save(doc);

            // 同步调用：self.syncDocument 经代理让本方法的 @Transactional 真正生效，
            // knowledgeService.processDocument 因此与本方法共享同一持久化上下文——Hibernate
            // 一级缓存/identity map 下，下面这次显式回读实际拿到的还是同一个 doc 托管实例，
            // 与直接读 doc.getStatus() 等价，并不能真正独立于"同一托管实例"这个前提。
            // ⚠️ 訂正：若未来给 processDocument 加 REQUIRES_NEW，identity map 会挡在前面
            // 不查库，这次显式回读同样看不见内层提交的状态——到时候判据要换别的机制，不是
            // 靠这次显式查询就自动兜底。写成显式回读单纯是为了与 ReembedService.runReembed
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
        documentRepository.save(doc);
    }
}

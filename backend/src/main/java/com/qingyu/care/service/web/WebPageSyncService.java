package com.qingyu.care.service.web;

import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
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
 * {@code @Async} 线程在本方法所在事务提交前就读到旧行的竞态；二是让本方法能感知重嵌结果，
 * 据此决定 {@code contentHash} 是否落库——写早了会把「重嵌失败」永久误判成「已处理，跳过」，
 * 下一轮同步再也不会重试（这是本类曾经的一个 Critical 缺陷）。
 *
 * @author ruan
 */
@Service
@Slf4j
public class WebPageSyncService {

    /** 连续失败到此次数后自动转手动，避免死链每小时重试一年。 */
    private static final int MAX_FAILURES = 10;

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeService knowledgeService;
    private final WebPageFetcher webPageFetcher;

    // 自注入解决 @Async 同类调用不生效问题（同 KnowledgeServiceImpl/ReembedService 范式）
    private WebPageSyncService self;

    public WebPageSyncService(DocumentRepository documentRepository,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               KnowledgeService knowledgeService,
                               WebPageFetcher webPageFetcher) {
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
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

        // 必须经 self 调用才能命中 Spring 代理，@Async 才会真正生效（同类直调会被隐式 this. 绕过代理）
        self.syncDocumentAsync(doc.getId());
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
            documentRepository.save(doc);

            // 同步调用：knowledgeService.processDocument 与本方法共享同一事务/持久化上下文，
            // 其内部 findById 拿到的就是同一个 doc 托管实例，调用返回后直接读 doc.getStatus()
            // 即可知道重嵌是否成功，无需额外查询（processDocument 内部吞异常、只落状态不重抛）
            knowledgeService.processDocument(documentId);

            if (doc.getStatus() == DocumentStatus.COMPLETED) {
                // 重嵌成功才落 contentHash：写早了会让下一轮同步误判"内容未变"而永久跳过重试
                doc.setContentHash(result.contentHash());
                documentRepository.save(doc);
                log.info("网页内容已更新并重新嵌入 id={} url={}", documentId, doc.getSourceUrl());
            } else {
                log.warn("网页内容已抓取但重新嵌入失败，contentHash 暂不落库以便下一轮同步重试 id={} url={}",
                        documentId, doc.getSourceUrl());
            }

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

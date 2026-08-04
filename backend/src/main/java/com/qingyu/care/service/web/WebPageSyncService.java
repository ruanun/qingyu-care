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

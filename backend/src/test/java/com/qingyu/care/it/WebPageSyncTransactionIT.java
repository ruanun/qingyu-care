package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.web.WebPageFetcher;
import com.qingyu.care.service.web.WebPageSyncService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 集成测试：{@code syncDocumentAsync} 里若不经 {@code self} 调用（而是同类直调
 * {@code syncDocument(documentId)}），会绕过 Spring 代理，让 {@code syncDocument} 的
 * {@code @Transactional} 在这条主入口（新增网页 / 手动"立即同步"）上完全失效——
 * {@code findById}/{@code save} 各自落进独立迷你事务，方法体内持有的 {@code doc} 中途即
 * detached；随后对它的 {@code save()} 会把 detached 时刻的陈旧字段（{@code status}/
 * {@code chunkCount}）merge 回库，静默覆盖 {@code knowledgeService.processDocument} 刚
 * 提交的结果。纯 Mockito 单测在同一个 {@code doc} 引用上打桩，天然验证不到这层 Spring AOP
 * 代理行为——只有真实 Spring 上下文、真实事务边界才能验证，这是本 IT 存在的唯一理由。
 *
 * <p>{@code knowledgeService}/{@code webPageFetcher} 用 {@code @MockitoBean} 换掉，只让
 * {@code WebPageSyncService} 自身走真实 Bean（含真实代理）。本 IT 只关心
 * {@code WebPageSyncService} 的事务/自调用正确性，不关心 {@code processDocument} 内部真实
 * 解析/嵌入了什么——S3+embedding 全链路本仓库已明确不测（见 knowledge-ingest-it-skipped
 * 记录），mock 内部用真实 repository 手动模拟它"自己的事务里把状态改完并提交"这一副作用。
 *
 * <p>不加类级 {@code @Transactional}：异步线程的写入需要真正提交到数据库，且测试线程
 * 后续的轮询读取不能被同一个长事务的 Hibernate 一级缓存挡住返回陈旧数据（同
 * {@link TokenMeteringIT} 的选择）。
 *
 * <p>⚠️ 本 IT 的全部区分度都押在 {@code status}/{@code chunkCount} 两条断言上——
 * {@code contentHash}/{@code syncFailureCount} 是 {@code syncDocument} 自己直接在
 * {@code doc} 上设置的字段，即便 Critical-1（自调用绕代理）复发，这两个字段依然会被正确
 * 写入（那正是它们抓不住这个回归的原因）。只有 {@code status}/{@code chunkCount} 这类
 * "由 {@code processDocument} 在别处提交"的字段，才会在 detached-merge 场景下被静默覆盖。
 * 后人如果为了"精简"删掉这两条断言，本 IT 会无声退化成假阳性——已用变异测试验证过
 * （临时把 {@code syncDocumentAsync} 里的 {@code self.syncDocument} 还原成裸调用，两个
 * 用例都会失败在这两条断言上），改动这两条前务必重新做一遍同样的验证。
 *
 * @author ruan
 */
class WebPageSyncTransactionIT extends AbstractPostgresIT {

    @Autowired
    private WebPageSyncService webPageSyncService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @MockitoBean
    private KnowledgeService knowledgeService;
    @MockitoBean
    private WebPageFetcher webPageFetcher;

    private Long docId;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        if (docId != null) {
            documentRepository.deleteById(docId);
        }
    }

    /** 播种一篇待同步网页文档：初始状态 PENDING、旧哈希 HASH_A、已有 3 次历史失败。 */
    private Document seedDoc() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);
        KnowledgeBase kb = knowledgeBaseRepository.save(
                KnowledgeBase.builder().name("网页同步事务 IT 知识库").enabled(true).build());
        Document doc = documentRepository.save(Document.builder()
                .knowledgeBase(kb)
                .name("https://example.com/faq")
                .documentType(DocumentType.URL)
                .status(DocumentStatus.PENDING)
                .sourceUrl("https://example.com/faq")
                .syncInterval(SyncInterval.DAILY)
                .contentHash("HASH_A")
                .content("旧正文")
                .syncFailureCount(3)
                .build());
        // 清掉上下文，模拟 @Async 线程没有 TenantContext（同 WebPageSyncSchedulerIT 的前提）
        TenantContext.clear();
        docId = doc.getId();
        return doc;
    }

    @Test
    void 重嵌成功时contentHash落库且status未被陈旧对象覆盖() throws Exception {
        Document doc = seedDoc();
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("新标题", "改版后的正文", "HASH_B", 200));
        // 模拟 processDocument 真实副作用：在它【自己的】findById 上把状态置 COMPLETED 并提交——
        // 如果 syncDocument 的事务/自调用被打破，这条更新对 syncDocument 里的旧 doc 引用不可见，
        // 之后旧引用的 save() 会把 status/chunkCount 覆盖回种子时的陈旧值
        doAnswer(inv -> {
            Document d = documentRepository.findById(doc.getId()).orElseThrow();
            d.setStatus(DocumentStatus.COMPLETED);
            d.setChunkCount(3);
            documentRepository.save(d);
            return null;
        }).when(knowledgeService).processDocument(doc.getId());

        webPageSyncService.syncDocumentAsync(doc.getId());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Document after = documentRepository.findById(doc.getId()).orElseThrow();
            assertThat(after.getContentHash()).isEqualTo("HASH_B");
            assertThat(after.getSyncFailureCount()).isZero();
            // 核心断言：status/chunkCount 必须是 processDocument 提交的值，不能被
            // syncDocumentAsync 里中途已 detached 的旧 doc 对象 save() 覆盖回 PENDING/null
            assertThat(after.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
            assertThat(after.getChunkCount()).isEqualTo(3);
        });
    }

    @Test
    void 重嵌失败时contentHash不落库_失败计数递增_status未被覆盖() throws Exception {
        Document doc = seedDoc();
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("新标题", "改版后的正文", "HASH_B", 200));
        // 模拟 processDocument 内部吞异常后落 FAILED（真实实现从不重抛，只能靠状态判定成败）
        doAnswer(inv -> {
            Document d = documentRepository.findById(doc.getId()).orElseThrow();
            d.setStatus(DocumentStatus.FAILED);
            d.setErrorMessage("模拟嵌入失败");
            documentRepository.save(d);
            return null;
        }).when(knowledgeService).processDocument(doc.getId());

        webPageSyncService.syncDocumentAsync(doc.getId());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Document after = documentRepository.findById(doc.getId()).orElseThrow();
            assertThat(after.getContentHash()).isEqualTo("HASH_A");    // 未被提前落库
            assertThat(after.getSyncFailureCount()).isEqualTo(4);      // 3 → 4，计入熔断阈值
            assertThat(after.getStatus()).isEqualTo(DocumentStatus.FAILED);
        });
    }
}

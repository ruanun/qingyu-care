package com.qingyu.care.service.web;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：网页同步的三条分支——内容未变不重嵌、变了才重嵌、失败保留旧内容。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WebPageSyncServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock WebPageFetcher webPageFetcher;
    @InjectMocks WebPageSyncService service;

    private Document urlDoc(String hash) {
        Document doc = Document.builder()
                .sourceUrl("https://example.com/faq")
                .contentHash(hash)
                .content("旧正文")
                .syncInterval(SyncInterval.DAILY)
                .syncFailureCount(3)
                .build();
        doc.setId(1L);
        return doc;
    }

    @Test
    void 内容未变时不重嵌只更新同步时间() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("标题", "新抓到的正文", "HASH_A", 200));

        service.syncDocument(1L);

        // 这条是本特性最容易悄悄退化的地方：退化成「每次都重嵌」时功能完全正常，
        // 只是每轮同步都白烧一次 embedding 的钱，没有任何现象会提醒
        verify(knowledgeService, never()).reprocessDocument(anyLong());
        assertThat(doc.getContent()).isEqualTo("旧正文");   // 未被覆盖
        assertThat(doc.getLastSyncedAt()).isNotNull();
        assertThat(doc.getSyncFailureCount()).isZero();     // 成功清零
    }

    @Test
    void 内容变了才重嵌并更新正文与哈希() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("新标题", "改版后的正文", "HASH_B", 200));

        service.syncDocument(1L);

        verify(knowledgeService).reprocessDocument(1L);
        assertThat(doc.getContent()).isEqualTo("改版后的正文");
        assertThat(doc.getContentHash()).isEqualTo("HASH_B");
        assertThat(doc.getName()).isEqualTo("新标题");
        assertThat(doc.getSyncFailureCount()).isZero();
    }

    @Test
    void 抓取失败时保留旧内容与旧哈希只累加失败计数() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("网页抓取失败: 502"));

        service.syncDocument(1L);   // 不应抛穿

        verify(knowledgeService, never()).reprocessDocument(anyLong());
        assertThat(doc.getContent()).isEqualTo("旧正文");      // 旧内容仍在服务
        assertThat(doc.getContentHash()).isEqualTo("HASH_A");
        assertThat(doc.getSyncFailureCount()).isEqualTo(4);    // 3 → 4
        assertThat(doc.getErrorMessage()).contains("502");
        assertThat(doc.getLastSyncedAt()).isNull();            // 失败不更新成功时间
    }

    @Test
    void 连续失败十次后自动转为手动同步() {
        Document doc = urlDoc("HASH_A");
        doc.setSyncFailureCount(9);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("404"));

        service.syncDocument(1L);

        assertThat(doc.getSyncFailureCount()).isEqualTo(10);
        assertThat(doc.getSyncInterval()).isEqualTo(SyncInterval.MANUAL);
        assertThat(doc.getErrorMessage()).contains("已停止自动同步");
    }

    @Test
    void 失败计数为null的存量行按0处理不NPE() {
        Document doc = urlDoc("HASH_A");
        doc.setSyncFailureCount(null);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("超时"));

        service.syncDocument(1L);

        assertThat(doc.getSyncFailureCount()).isEqualTo(1);
    }

    @Test
    void 非URL类型的文档拒绝同步() {
        Document doc = Document.builder().sourceUrl(null).build();
        doc.setId(2L);
        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));

        service.syncDocument(2L);

        // 注意：sourceUrl 是 null，anyString() 不匹配 null 调用，必须用 any()，
        // 否则删掉守卫后 fetch(null) 照样能通过 never() 断言，测试形同虚设
        verify(webPageFetcher, never()).fetch(any());
        verify(knowledgeService, never()).reprocessDocument(anyLong());
    }
}

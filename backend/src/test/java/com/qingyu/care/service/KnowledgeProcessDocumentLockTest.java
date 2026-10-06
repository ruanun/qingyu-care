package com.qingyu.care.service;

import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.qingyu.care.service.embedding.KnowledgeVectorWriter;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code processDocument} 的临界区互斥：锁被<b>别的事务</b>持有时，绝不能进入
 * 「先删旧向量、再插新向量」这一段。
 *
 * <p>存在的理由：产出重复向量的临界区在 {@code processDocument} 里，而它有三个入口——
 * 网页同步、切换向量模型的迁移任务、手动重新处理（{@code reprocessDocument}）。
 * 锁若只加在其中一个调用方（本特性最初就是只加在 {@code WebPageSyncService.syncDocument}
 * 上），另外两条照样能并发进来：换 embedding 模型后跑全量重嵌，批任务正处理某篇网页，
 * 调度器同一分钟命中同一篇且正文已变 → 两个事务各删各插 → {@code vector_store} 落 2N 行
 * 而 {@code chunk_count} 记 N，检索时同一段落重复占 topK，全程无异常无日志。
 *
 * <p>与 {@code WebPageSyncLockIT} 的分工：那条 IT 用两条真实数据库连接验证「锁本身真的
 * 互斥、且随事务结束释放」——那是 PostgreSQL 的行为，Mockito 看不见；本类验证「服务真的
 * 尊重锁的返回值」——那是 Java 的行为，两条合起来才构成完整防线（同
 * {@code WebPageSyncServiceTest} 与该 IT 的既有分工）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeProcessDocumentLockTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeVectorWriter vectorWriter;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock com.qingyu.care.repository.QaPairRepository qaPairRepository;
    @Mock com.qingyu.care.repository.CrawlSiteRepository crawlSiteRepository;

    private KnowledgeServiceImpl service() {
        KnowledgeServiceImpl service = new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorWriter, fileService, vectorStoreRepository,
                qaPairRepository, crawlSiteRepository);
        ReflectionTestUtils.setField(service, "chunkSize", 800);
        ReflectionTestUtils.setField(service, "chunkOverlap", 100);
        return service;
    }

    private Document urlDoc(Long id) {
        return Document.builder()
                .id(id)
                .name("示例网页")
                .knowledgeBase(KnowledgeBase.builder().id(10L).build())
                .documentType(DocumentType.URL)
                .sourceUrl("https://example.com/faq")
                .content("网页正文，用于验证临界区互斥。".repeat(20))
                .status(DocumentStatus.COMPLETED)
                .chunkCount(7)
                .build();
    }

    @Test
    void 锁被别的事务持有时_不进入删向量与写向量的临界区() {
        when(documentRepository.tryLockDocumentProcessing(6L)).thenReturn(false);

        service().processDocument(6L);

        // 临界区两端都不能沾：删旧向量会让持锁方刚插的向量凭空消失，插新向量则直接造出重复
        verify(vectorStoreRepository, never()).deleteByDocumentId(anyString());
        verify(vectorWriter, never()).write(any(), any());
        // 连文档都不该读：状态机与 chunkCount 全部保持原样，交给持锁方那一轮写
        verify(documentRepository, never()).findById(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void 抢到锁时正常走完摄取_跳过逻辑没有把主路一起废掉() {
        // 反面用例：只断言"抢不到就不做事"时，把实现改成【无条件 return】同样能过。
        // 有了这一条，那种改法会立刻转红
        Document doc = urlDoc(6L);
        when(documentRepository.tryLockDocumentProcessing(6L)).thenReturn(true);
        when(documentRepository.findById(6L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        service().processDocument(6L);

        verify(vectorStoreRepository).deleteByDocumentId("6");
        verify(vectorWriter).write(any(), any());
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }
}

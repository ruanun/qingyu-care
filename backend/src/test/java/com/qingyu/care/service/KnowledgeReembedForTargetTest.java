package com.qingyu.care.service;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.embedding.KnowledgeVectorWriter;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 切换向量模型时给目标模型补一份文档向量：只动目标标签、不碰文档状态与当前模型的向量，并尊重文档锁。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeReembedForTargetTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeVectorWriter vectorWriter;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock com.qingyu.care.repository.QaPairRepository qaPairRepository;
    @Mock com.qingyu.care.repository.CrawlSiteRepository crawlSiteRepository;

    private final AiModelConfig target = AiModelConfig.builder().id(2L).modelId("bge-m3").build();

    private KnowledgeServiceImpl service() {
        KnowledgeServiceImpl service = new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorWriter, fileService, vectorStoreRepository, qaPairRepository, crawlSiteRepository);
        ReflectionTestUtils.setField(service, "chunkSize", 800);
        ReflectionTestUtils.setField(service, "chunkOverlap", 100);
        return service;
    }

    private Document urlDoc(DocumentStatus status) {
        Document doc = Document.builder().id(6L).name("示例网页")
                .knowledgeBase(KnowledgeBase.builder().id(10L).build())
                .documentType(DocumentType.URL).status(status)
                .content("网页正文内容，用于验证迁移重嵌。".repeat(20)).build();
        doc.setTenantId(1L);
        doc.setAppId(2L);
        return doc;
    }

    @Test
    void 只删目标标签再只写目标模型_文档状态不变() throws Exception {
        Document doc = urlDoc(DocumentStatus.COMPLETED);
        when(documentRepository.tryLockDocumentProcessing(6L)).thenReturn(true);
        when(documentRepository.findById(6L)).thenReturn(Optional.of(doc));

        assertThat(service().reembedDocumentFor(6L, target)).isTrue();

        InOrder ord = inOrder(vectorStoreRepository, vectorWriter);
        ord.verify(vectorStoreRepository).deleteByDocumentIdAndTag("6", "bge-m3");
        ord.verify(vectorWriter).writeFor(eq(target), any());
        // 当前模型那份不动：不能走「按文档全删」，也不能按应用写入目标去双写
        verify(vectorStoreRepository, never()).deleteByDocumentId(any());
        verify(vectorWriter, never()).write(any(), any());
        // 迁移期间文档保持可检索，不能被打成 PROCESSING
        verify(documentRepository, never()).save(any());
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    void 文档锁被占_跳过并告知调用方() throws Exception {
        when(documentRepository.tryLockDocumentProcessing(6L)).thenReturn(false);

        assertThat(service().reembedDocumentFor(6L, target)).isFalse();

        verifyNoInteractions(vectorStoreRepository, vectorWriter);
    }

    @Test
    void 文档正在重新处理_不重复写() throws Exception {
        when(documentRepository.tryLockDocumentProcessing(6L)).thenReturn(true);
        when(documentRepository.findById(6L)).thenReturn(Optional.of(urlDoc(DocumentStatus.PROCESSING)));

        assertThat(service().reembedDocumentFor(6L, target)).isTrue();

        verifyNoInteractions(vectorStoreRepository, vectorWriter);
    }
}

package com.qingyu.care.service;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.qingyu.care.service.embedding.KnowledgeVectorWriter;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文档可见性/启用状态更新：文档行变更 + vector_store metadata 同步（同一事务）。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentAccessTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeVectorWriter vectorWriter;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock com.qingyu.care.repository.QaPairRepository qaPairRepository;
    @Mock com.qingyu.care.repository.CrawlSiteRepository crawlSiteRepository;

    private KnowledgeServiceImpl service() {
        return new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorWriter, fileService, vectorStoreRepository,
                qaPairRepository, crawlSiteRepository);
    }

    @Test
    void 更新可见性_文档行与chunk元数据同步() {
        Document doc = Document.builder().id(7L).name("说明书").build();
        when(documentRepository.findById(7L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(doc)).thenReturn(doc);

        Document out = service().updateDocumentVisibility(7L, DocumentVisibility.INTERNAL);

        assertThat(out.getVisibility()).isEqualTo(DocumentVisibility.INTERNAL);
        verify(vectorStoreRepository).updateVisibilityByDocumentId("7", "INTERNAL");
    }

    @Test
    void 设置启用状态_文档行与chunk元数据同步() {
        Document doc = Document.builder().id(7L).name("说明书").build();
        when(documentRepository.findById(7L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(doc)).thenReturn(doc);

        Document out = service().setDocumentEnabled(7L, false);

        assertThat(out.getEnabled()).isFalse();
        verify(vectorStoreRepository).updateDocEnabledByDocumentId("7", "false");
    }
}

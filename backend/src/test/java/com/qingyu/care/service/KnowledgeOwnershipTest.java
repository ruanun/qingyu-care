package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.DocumentStatusDTO;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库 / 文档按 id 加载的租户归属校验单测。
 * KnowledgeServiceImpl 的 getKnowledgeBase / getDocument / deleteDocument / reprocessDocument /
 * getDocumentStatus / deleteKnowledgeBase 均走 findById 或 deleteById 主键路径，不受 @Filter 保护——
 * 未校验归属时已登录租户可枚举 id 跨租户读、删、下载他人知识库与文档。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeOwnershipTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock VectorStore vectorStore;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock com.qingyu.care.repository.QaPairRepository qaPairRepository;
    @Mock com.qingyu.care.repository.CrawlSiteRepository crawlSiteRepository;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private KnowledgeServiceImpl service() {
        return new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorStore, fileService, vectorStoreRepository, embeddingModelProvider,
                qaPairRepository, crawlSiteRepository);
    }

    private KnowledgeBase kbOwnedBy(Long tenantId) {
        KnowledgeBase kb = KnowledgeBase.builder().id(1L).name("他租户库").enabled(true).build();
        kb.setTenantId(tenantId);
        return kb;
    }

    private Document docOwnedBy(Long tenantId) {
        Document doc = Document.builder().id(7L).name("他租户文档").filePath("s3://x").build();
        doc.setTenantId(tenantId);
        return doc;
    }

    @Test
    void getKnowledgeBase_他租户库_按不存在处理() {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kbOwnedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service().getKnowledgeBase(1L))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void getKnowledgeBase_本租户库_正常返回() {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kbOwnedBy(2L)));
        TenantContext.setTenantId(2L);

        assertThat(service().getKnowledgeBase(1L)).isNotNull();
    }

    @Test
    void deleteKnowledgeBase_他租户库_按不存在处理_不落删除() {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kbOwnedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service().deleteKnowledgeBase(1L))
                .isInstanceOf(RuntimeException.class);
        verify(knowledgeBaseRepository, never()).delete(any());
        verify(knowledgeBaseRepository, never()).deleteById(any());
    }

    @Test
    void getDocument_他租户文档_按不存在处理() {
        when(documentRepository.findById(7L)).thenReturn(Optional.of(docOwnedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service().getDocument(7L))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void deleteDocument_他租户文档_按不存在处理_不落删除() {
        when(documentRepository.findById(7L)).thenReturn(Optional.of(docOwnedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service().deleteDocument(7L))
                .isInstanceOf(RuntimeException.class);
        verify(documentRepository, never()).delete(any());
    }

    @Test
    void reprocessDocument_他租户文档_按不存在处理_不落更新() {
        when(documentRepository.findById(7L)).thenReturn(Optional.of(docOwnedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service().reprocessDocument(7L))
                .isInstanceOf(RuntimeException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void getDocumentStatus_他租户文档_按不存在处理() {
        when(documentRepository.findById(7L)).thenReturn(Optional.of(docOwnedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service().getDocumentStatus(7L))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void getDocuments_越权kbId_不查询文档且按不存在处理() {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kbOwnedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service().getDocuments(1L))
                .isInstanceOf(RuntimeException.class);
        verify(documentRepository, never()).findByKnowledgeBaseId(any());
    }

    @Test
    void getDocuments_本上下文kbId_正常查询() {
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kbOwnedBy(2L)));
        when(documentRepository.findByKnowledgeBaseId(1L)).thenReturn(List.of());
        TenantContext.setTenantId(2L);

        List<Document> result = service().getDocuments(1L);

        assertThat(result).isEmpty();
        verify(documentRepository).findByKnowledgeBaseId(1L);
    }

    @Test
    void getDocumentsStatus_按当前上下文归属过滤_剔除他租户文档() {
        Document ownDoc = Document.builder().id(1L).name("本租户文档").status(DocumentStatus.COMPLETED).build();
        ownDoc.setTenantId(2L);
        Document otherDoc = Document.builder().id(2L).name("他租户文档").status(DocumentStatus.COMPLETED).build();
        otherDoc.setTenantId(9L);
        when(documentRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(ownDoc, otherDoc));
        TenantContext.setTenantId(2L);

        List<DocumentStatusDTO> result = service().getDocumentsStatus(List.of(1L, 2L));

        assertThat(result).extracting(DocumentStatusDTO::getId).containsExactly(1L);
    }
}

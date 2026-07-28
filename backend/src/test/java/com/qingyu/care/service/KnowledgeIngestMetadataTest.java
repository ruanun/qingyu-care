package com.qingyu.care.service;

import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摄取（processDocument）写入 chunk metadata 时，租户/应用信息应取自 document 行自身，
 * 而不是 TenantContext——因为 processDocument 跑在 @Async 线程，请求线程的 TenantContext
 * 不会传递过去，读 TenantContext 会导致新上传文档的 chunk 写出 appId 空串（历史 bug 根因）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIngestMetadataTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock VectorStore vectorStore;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock EmbeddingModelProvider embeddingModelProvider;

    private KnowledgeServiceImpl service() {
        KnowledgeServiceImpl service = new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorStore, fileService, vectorStoreRepository, embeddingModelProvider);
        ReflectionTestUtils.setField(service, "chunkSize", 800);
        ReflectionTestUtils.setField(service, "chunkOverlap", 100);
        return service;
    }

    @Test
    void 摄取时chunk元数据取自document行而非TenantContext() {
        KnowledgeBase kb = KnowledgeBase.builder().id(10L).build();
        Document doc = Document.builder()
                .id(5L)
                .name("说明书.txt")
                .knowledgeBase(kb)
                .filePath("kb/说明书.txt")
                .visibility(DocumentVisibility.PUBLIC)
                .enabled(true)
                .build();
        doc.setTenantId(1L);
        doc.setAppId(2L);

        when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModelProvider.getModel()).thenReturn("qwen3.7-text-embedding");

        String content = "轻语客服知识内容，用于测试摄取元数据。".repeat(50);
        when(fileService.downloadFile(any())).thenReturn(content.getBytes(StandardCharsets.UTF_8));

        // 不设置 TenantContext，模拟 @Async 线程（请求线程的 ThreadLocal 不会传递过去）
        service().processDocument(5L);

        assertThat(doc.getStatus().name()).isEqualTo("COMPLETED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<org.springframework.ai.document.Document> chunks = captor.getValue();
        assertThat(chunks).isNotEmpty();
        for (org.springframework.ai.document.Document chunk : chunks) {
            assertThat(chunk.getMetadata().get("tenantId")).isEqualTo("1");
            assertThat(chunk.getMetadata().get("appId")).isEqualTo("2");
        }
    }
}

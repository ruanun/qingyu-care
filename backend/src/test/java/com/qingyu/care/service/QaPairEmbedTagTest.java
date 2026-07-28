package com.qingyu.care.service;

import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA 嵌入写入的向量 metadata 含 embeddingModel 标。 */
@ExtendWith(MockitoExtension.class)
class QaPairEmbedTagTest {

    @Mock com.qingyu.care.repository.QaPairRepository qaPairRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock VectorStore vectorStore;
    @Mock com.qingyu.care.repository.VectorStoreRepository vectorStoreRepository;
    @Mock EmbeddingModelProvider embeddingModelProvider;

    @Test
    void embed写入metadata含embeddingModel() {
        when(embeddingModelProvider.getModel()).thenReturn("qwen3.7-text-embedding");
        // 按 QaPairService 现有 @RequiredArgsConstructor 字段顺序传 mock
        // （qaPairRepository, knowledgeService, vectorStore, vectorStoreRepository, embeddingModelProvider）
        QaPairService service = new QaPairService(qaPairRepository, knowledgeService, vectorStore,
                vectorStoreRepository, embeddingModelProvider);

        QaPair qa = QaPair.builder().question("营业时间").answer("9-18")
                .knowledgeBase(KnowledgeBase.builder().id(7L).build()).build();
        qa.setId(3L); qa.setTenantId(1L); qa.setAppId(2L);

        service.embed(qa);

        ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(cap.capture());
        assertThat(cap.getValue().get(0).getMetadata()).containsEntry("embeddingModel", "qwen3.7-text-embedding");
    }
}

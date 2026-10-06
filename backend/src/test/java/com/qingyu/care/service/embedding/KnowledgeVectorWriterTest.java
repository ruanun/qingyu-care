package com.qingyu.care.service.embedding;

import com.qingyu.care.entity.AiModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 写入器：每个写入目标各写一份、各打自己的标签、行主键互不相同。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeVectorWriterTest {

    @Mock EmbeddingModelResolver resolver;
    @Mock EmbeddingModelRegistry registry;
    @Mock VectorStore storeA;
    @Mock VectorStore storeB;

    private static Document chunk() {
        Map<String, Object> md = new HashMap<>();
        md.put("documentId", "6");
        return Document.builder().text("正文").metadata(md).build();
    }

    @Test
    void 切换进行中_两个模型各写一份_标签各自正确_主键不同() {
        AiModelConfig a = AiModelConfig.builder().id(1L).modelId("qwen3.7-text-embedding").build();
        AiModelConfig b = AiModelConfig.builder().id(2L).modelId("bge-m3").build();
        when(resolver.writeTargets(5L)).thenReturn(List.of(a, b));
        when(registry.vectorStore(a)).thenReturn(storeA);
        when(registry.vectorStore(b)).thenReturn(storeB);
        Document original = chunk();

        new KnowledgeVectorWriter(resolver, registry).write(5L, List.of(original));

        ArgumentCaptor<List<Document>> capA = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Document>> capB = ArgumentCaptor.forClass(List.class);
        verify(storeA).add(capA.capture());
        verify(storeB).add(capB.capture());
        Document da = capA.getValue().get(0);
        Document db = capB.getValue().get(0);
        assertThat(da.getMetadata()).containsEntry(KnowledgeVectorWriter.TAG_KEY, "qwen3.7-text-embedding")
                .containsEntry("documentId", "6");
        assertThat(db.getMetadata()).containsEntry(KnowledgeVectorWriter.TAG_KEY, "bge-m3");
        // 行主键取自 Document.id：同一切块双写必须是两行，主键撞了第二份会写失败
        assertThat(da.getId()).isNotEqualTo(db.getId());
        // 调用方传进来的切块不被改写（同一批切块还要交给下一个目标）
        assertThat(original.getMetadata()).doesNotContainKey(KnowledgeVectorWriter.TAG_KEY);
    }

    @Test
    void 空切块_不触达向量库() {
        new KnowledgeVectorWriter(resolver, registry).writeFor(AiModelConfig.builder().id(1L).modelId("m").build(), List.of());

        verifyNoInteractions(registry);
    }
}

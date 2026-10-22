package com.qingyu.care.service;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.service.embedding.KnowledgeVectorWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QA 的向量写入：模型标签交给 {@link KnowledgeVectorWriter}；切换向量模型时只给目标模型补一份。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class QaPairEmbedTagTest {

    @Mock com.qingyu.care.repository.QaPairRepository qaPairRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock KnowledgeVectorWriter vectorWriter;
    @Mock com.qingyu.care.repository.VectorStoreRepository vectorStoreRepository;

    private QaPairService service() {
        return new QaPairService(qaPairRepository, knowledgeService, vectorWriter, vectorStoreRepository);
    }

    private QaPair qa(QaPairStatus status) {
        QaPair qa = QaPair.builder().question("营业时间").answer("9-18").status(status)
                .knowledgeBase(KnowledgeBase.builder().id(7L).build()).build();
        qa.setId(3L);
        qa.setTenantId(1L);
        qa.setAppId(2L);
        return qa;
    }

    @Test
    void embed_按行上的appId交给写入器_自己不打模型标签() {
        service().embed(qa(QaPairStatus.ACTIVE));

        ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
        verify(vectorWriter).write(eq(2L), cap.capture());
        // 标签由写入器按写入目标逐份打（切换期间双写两个不同的标签），这里打了反而会写错
        assertThat(cap.getValue().get(0).getMetadata()).doesNotContainKey(KnowledgeVectorWriter.TAG_KEY);
    }

    @Test
    void reembedFor_只删目标标签再只写目标模型_当前模型那份不动() {
        AiModelConfig target = AiModelConfig.builder().id(9L).modelId("bge-m3").build();
        when(qaPairRepository.findById(3L)).thenReturn(Optional.of(qa(QaPairStatus.DISABLED)));

        service().reembedFor(3L, target);

        InOrder ord = inOrder(vectorStoreRepository, vectorWriter);
        ord.verify(vectorStoreRepository).deleteByQaPairIdAndTag("3", "bge-m3");
        ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
        ord.verify(vectorWriter).writeFor(eq(target), cap.capture());
        // 停用的 QA 迁过去仍是停用
        assertThat(cap.getValue().get(0).getMetadata()).containsEntry("docEnabled", "false");
        verify(vectorStoreRepository, never()).deleteByQaPairId(any());
        verify(vectorWriter, never()).write(any(), any());
    }

    @Test
    void reembedFor_草稿没有向量_跳过() {
        when(qaPairRepository.findById(3L)).thenReturn(Optional.of(qa(QaPairStatus.DRAFT)));

        service().reembedFor(3L, AiModelConfig.builder().id(9L).modelId("bge-m3").build());

        verifyNoInteractions(vectorStoreRepository, vectorWriter);
    }
}

package com.qingyu.care.service;

import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import com.qingyu.care.service.embedding.KnowledgeVectorWriter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QaPairServiceTest {

    @Mock QaPairRepository qaPairRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock KnowledgeVectorWriter vectorWriter;
    @Mock VectorStoreRepository vectorStoreRepository;

    private QaPairService service() {
        return new QaPairService(qaPairRepository, knowledgeService, vectorWriter, vectorStoreRepository);
    }

    private KnowledgeBase kb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        return kb;
    }

    private QaPair active(long id) {
        QaPair qa = QaPair.builder().id(id).knowledgeBase(kb()).question("如何退货").answer("7天内订单页申请")
                .source(QaPairSource.MANUAL).status(QaPairStatus.ACTIVE).build();
        qa.setTenantId(11L);
        qa.setAppId(22L);
        return qa;
    }

    @Test
    void create_savesActiveAndEmbedsSingleUnsplitVectorWithRequiredKeys() {
        when(knowledgeService.getKnowledgeBase(7L)).thenReturn(kb());
        when(qaPairRepository.save(any())).thenAnswer(inv -> {
            QaPair q = inv.getArgument(0);
            q.setId(5L);
            q.setTenantId(11L);
            q.setAppId(22L);
            return q;
        });

        service().create(7L, "如何退货", "7天内订单页申请");

        ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
        verify(vectorWriter).write(org.mockito.ArgumentMatchers.eq(22L), cap.capture());   // 按 QA 行上的 appId 写
        assertThat(cap.getValue()).hasSize(1); // 不切分：单条
        Document d = cap.getValue().get(0);
        assertThat(d.getText()).isEqualTo("问：如何退货\n答：7天内订单页申请");
        // RetrievalScope 过滤 5 必需键 + qaPairId/contentType
        assertThat(d.getMetadata())
                .containsEntry("tenantId", "11").containsEntry("appId", "22")
                .containsEntry("knowledgeBaseId", "7").containsEntry("docEnabled", "true")
                .containsEntry("visibility", "PUBLIC").containsEntry("qaPairId", "5")
                .containsEntry("contentType", "qa")
                .containsEntry("source", "如何退货");
    }

    @Test
    void approve_draftToActive_embeds() {
        QaPair draft = active(5L);
        draft.setSource(QaPairSource.GENERATED);
        draft.setStatus(QaPairStatus.DRAFT);
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(draft));
        when(qaPairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        QaPair result = service().approve(5L);

        assertThat(result.getStatus()).isEqualTo(QaPairStatus.ACTIVE);
        verify(vectorWriter).write(any(), any());
    }

    @Test
    void setEnabled_false_flipsDocEnabledKeepsVector() {
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(active(5L)));
        when(qaPairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().setEnabled(5L, false);

        verify(vectorStoreRepository).updateDocEnabledByQaPairId("5", "false");
        verify(vectorWriter, never()).write(any(), any()); // 停用不重嵌
    }

    @Test
    void delete_removesVectorThenRow() {
        QaPair qa = active(5L);
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(qa));

        service().delete(5L);

        org.mockito.InOrder ord = org.mockito.Mockito.inOrder(vectorStoreRepository, qaPairRepository);
        ord.verify(vectorStoreRepository).deleteByQaPairId("5");
        ord.verify(qaPairRepository).delete(qa);
    }

    @Test
    void update_activeContentChanged_reEmbeds() {
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(active(5L)));
        when(qaPairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().update(5L, "怎么退货", "改后的答案");

        verify(vectorStoreRepository).deleteByQaPairId("5");
        verify(vectorWriter).write(any(), any());
    }

    @Test
    void update_disabledContentChanged_reEmbedsThenKeepsDisabled() {
        QaPair disabled = active(5L);
        disabled.setStatus(QaPairStatus.DISABLED);
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(disabled));
        when(qaPairRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().update(5L, "改后的问", "改后的答");

        org.mockito.InOrder ord = org.mockito.Mockito.inOrder(vectorStoreRepository, vectorWriter);
        ord.verify(vectorStoreRepository).deleteByQaPairId("5");
        ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
        ord.verify(vectorWriter).write(org.mockito.ArgumentMatchers.eq(22L), cap.capture());
        // 重嵌时直接按状态写 docEnabled=false（切换向量模型的迁移也走同一条组装路径），不再事后补一次翻转
        assertThat(cap.getValue().get(0).getMetadata()).containsEntry("docEnabled", "false");
    }

    @Test
    void setEnabled_onDraft_throws() {
        QaPair draft = active(5L);
        draft.setStatus(com.qingyu.care.enums.QaPairStatus.DRAFT);
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(draft));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().setEnabled(5L, true))
                .isInstanceOf(com.qingyu.care.exception.InvalidOperationException.class);
        verify(vectorStoreRepository, never()).updateDocEnabledByQaPairId(any(), any());
    }

    @Test
    void getQaPair_crossTenant_throwsNotFound() {
        QaPair other = active(5L);
        other.setTenantId(999L); // 他租户
        when(qaPairRepository.findById(5L)).thenReturn(java.util.Optional.of(other));
        com.qingyu.care.config.TenantContext.setTenantId(11L);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().getQaPair(5L))
                    .isInstanceOf(com.qingyu.care.exception.ResourceNotFoundException.class);
        } finally {
            com.qingyu.care.config.TenantContext.clear();
        }
    }
}

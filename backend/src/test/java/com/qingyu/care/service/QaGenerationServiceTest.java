package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.Document;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.QaPairRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class QaGenerationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseQaJson_extractsArrayEvenWithSurroundingText() {
        String raw = "好的，以下是问答对：\n[{\"question\":\"如何退货\",\"answer\":\"7天内申请\"}]\n希望有用";
        var list = QaGenerationService.parseQaJson(raw, mapper);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).question()).isEqualTo("如何退货");
        assertThat(list.get(0).answer()).isEqualTo("7天内申请");
    }

    @Test
    void parseQaJson_filtersBlankAndReturnsEmptyOnGarbage() {
        assertThat(QaGenerationService.parseQaJson("不是 JSON", mapper)).isEmpty();
        assertThat(QaGenerationService.parseQaJson(null, mapper)).isEmpty();
        assertThat(QaGenerationService.parseQaJson("[{\"question\":\"\",\"answer\":\"x\"}]", mapper)).isEmpty();
    }

    /**
     * 安全审查 H1 回归：异步线程里不得自己查模型。{@code getModelsForScenario} 靠 Hibernate 过滤器隔离，
     * 而异步线程无上下文、过滤器不生效，现查会取到全平台所有租户的模型。必须只用调用方传入的列表。
     */
    @Test
    void generateFromDocument_usesCallerResolvedModels_neverQueriesInAsyncThread() {
        DocumentRepository docRepo = mock(DocumentRepository.class);
        QaPairRepository qaRepo = mock(QaPairRepository.class);
        ModelRouterService router = mock(ModelRouterService.class);
        Document doc = new Document();
        doc.setContent("退货政策：7 天内可申请。");
        doc.setTenantId(7L);
        doc.setAppId(70L);
        when(docRepo.findById(1L)).thenReturn(Optional.of(doc));
        when(router.callWithModels(any(), any()))
                .thenReturn("[{\"question\":\"如何退货\",\"answer\":\"7天内申请\"}]");
        List<AiModelConfig> resolved = List.of(new AiModelConfig());

        new QaGenerationService(docRepo, qaRepo, router, mapper).generateFromDocument(1L, resolved);

        verify(router).callWithModels(same(resolved), any());
        verify(router, never()).getModelsForScenario(anyString());
        verify(router, never()).callWithFallback(anyString(), any());
        verify(qaRepo).save(argThat(qa -> qa.getTenantId() == 7L && qa.getAppId() == 70L));
    }
}

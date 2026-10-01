package com.qingyu.care.controller;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.QaGenerationService;
import com.qingyu.care.service.QaPairService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * 安全审查 H1 回归：QA 生成的模型列表必须在<b>请求线程</b>（有 TenantContext）解析，再交给异步方法。
 * 与 {@code QaGenerationServiceTest} 那条「异步线程里不得现查」合起来守住整条链路。
 *
 * @author ruan
 */
class QaPairControllerGenerateTest {

    @Test
    void generate_resolvesModelsInRequestThread_andPassesThemToAsync() {
        QaPairService qaPairService = mock(QaPairService.class);
        QaGenerationService qaGenerationService = mock(QaGenerationService.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        ModelRouterService router = mock(ModelRouterService.class);
        List<AiModelConfig> resolved = List.of(new AiModelConfig());
        when(router.getModelsForScenario("default")).thenReturn(resolved);

        new QaPairController(qaPairService, qaGenerationService, knowledgeService, router,
                new com.qingyu.care.service.LongTaskTenantLimiter()).generate(5L);

        verify(knowledgeService).getDocument(5L);
        verify(qaGenerationService).generateFromDocument(eq(5L), same(resolved));
    }
}

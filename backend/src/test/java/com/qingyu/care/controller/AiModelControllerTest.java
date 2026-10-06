package com.qingyu.care.controller;

import com.qingyu.care.dto.AiModelConfigUpdateRequest;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.service.ModelRouterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiModelController} 三个会把 endpoint 送去出站的入口（建 / 改 / 测试连接）都必须先过
 * {@link ModelRouterService#requireValidEndpoint}（安全审查 M4，2026-09-03）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AiModelControllerTest {

    private static final String INTERNAL = "http://127.0.0.1:6379/v1";

    @Mock
    ModelRouterService modelRouterService;

    @Mock
    com.qingyu.care.service.embedding.EmbeddingModelAdminService embeddingModelAdminService;

    @Mock
    com.qingyu.care.service.embedding.EmbeddingModelProbe embeddingModelProbe;

    @InjectMocks
    AiModelController controller;

    private static AiModelConfig config(String endpoint) {
        return AiModelConfig.builder().id(1L).name("m").provider("openai").modelId("x")
                .endpoint(endpoint).enabled(true).build();
    }

    @Test
    void 创建模型先校验端点再落库() {
        when(modelRouterService.saveModel(any())).thenAnswer(i -> i.getArgument(0));

        controller.createModel(config("https://8.8.8.8/v1"));

        InOrder order = inOrder(modelRouterService);
        order.verify(modelRouterService).requireValidEndpoint("https://8.8.8.8/v1");
        order.verify(modelRouterService).saveModel(any());
    }

    @Test
    void 创建模型端点非法则不落库() {
        doThrow(new InvalidOperationException("模型端点无效")).when(modelRouterService).requireValidEndpoint(INTERNAL);

        assertThatThrownBy(() -> controller.createModel(config(INTERNAL)))
                .isInstanceOf(InvalidOperationException.class);
        verify(modelRouterService, never()).saveModel(any());
    }

    @Test
    void 更新模型改了端点才校验且非法则不落库() {
        when(modelRouterService.getById(1L)).thenReturn(config("https://8.8.8.8/v1"));
        doThrow(new InvalidOperationException("模型端点无效")).when(modelRouterService).requireValidEndpoint(INTERNAL);
        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setEndpoint(INTERNAL);

        assertThatThrownBy(() -> controller.updateModel(1L, req))
                .isInstanceOf(InvalidOperationException.class);
        verify(modelRouterService, never()).saveModel(any());
    }

    @Test
    void 测试连接前校验存量端点且非法则不建客户端() {
        when(modelRouterService.getById(1L)).thenReturn(config(INTERNAL));
        doThrow(new InvalidOperationException("模型端点无效")).when(modelRouterService).requireValidEndpoint(INTERNAL);

        assertThatThrownBy(() -> controller.testModel(1L))
                .isInstanceOf(InvalidOperationException.class);
        verify(modelRouterService, never()).createChatClient(any());
    }
}

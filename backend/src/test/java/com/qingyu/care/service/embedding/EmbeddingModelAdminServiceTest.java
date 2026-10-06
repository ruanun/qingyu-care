package com.qingyu.care.service.embedding;

import com.qingyu.care.dto.AiModelConfigUpdateRequest;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.ApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 向量模型在模型管理各入口上的约束：被引用即不可改 modelId / 停用 / 删除；改端点、Key、modelId 重新探测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingModelAdminServiceTest {

    @Mock EmbeddingModelProbe probe;
    @Mock ApplicationRepository applicationRepository;
    @InjectMocks EmbeddingModelAdminService service;

    private static AiModelConfig existing() {
        return AiModelConfig.builder().id(7L).provider("dashscope").modelId("qwen3.7-text-embedding")
                .endpoint("https://dashscope.aliyuncs.com/compatible-mode/v1").apiKey("old-key")
                .modelType(ModelType.EMBEDDING).build();
    }

    @Test
    void 新建向量模型_补齐场景与推荐阈值并真实探测() {
        AiModelConfig config = AiModelConfig.builder().provider("siliconflow").modelId("BAAI/bge-m3")
                .modelType(ModelType.EMBEDDING).scenario("complaint").build();

        service.prepareCreate(config);

        assertThat(config.getScenario()).isEqualTo("default");
        assertThat(config.getSimilarityThreshold()).isNotNull();
        verify(probe).requireCompatible(config);
    }

    @Test
    void 新建对话模型_不探测_类型归一为CHAT() {
        AiModelConfig config = AiModelConfig.builder().modelId("gpt").modelType(null).build();

        service.prepareCreate(config);

        assertThat(config.getModelType()).isEqualTo(ModelType.CHAT);
        verifyNoInteractions(probe);
    }

    @Test
    void 新建_探测失败_异常原样抛出_不落库() {
        AiModelConfig config = AiModelConfig.builder().modelId("text-embedding-ada-002").modelType(ModelType.EMBEDDING).build();
        doThrow(new InvalidOperationException("向量维度不符")).when(probe).requireCompatible(config);

        assertThatThrownBy(() -> service.prepareCreate(config)).hasMessageContaining("向量维度不符");
    }

    @Test
    void 被引用的向量模型_不能改modelId() {
        when(applicationRepository.countEmbeddingModelReferences(7L)).thenReturn(2L);
        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setModelId("bge-m3");

        assertThatThrownBy(() -> service.beforeUpdate(existing(), req))
                .isInstanceOf(InvalidOperationException.class).hasMessageContaining("不能修改模型 ID");
        verifyNoInteractions(probe);
    }

    @Test
    void 被引用的向量模型_原样回传modelId不算修改_可以改名() {
        when(applicationRepository.countEmbeddingModelReferences(7L)).thenReturn(2L);
        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setModelId("qwen3.7-text-embedding");   // 编辑弹窗整份提交，modelId 原样带回
        req.setName("新名字");

        service.beforeUpdate(existing(), req);

        verifyNoInteractions(probe);
    }

    @Test
    void 被引用的向量模型_不能停用() {
        when(applicationRepository.countEmbeddingModelReferences(7L)).thenReturn(1L);
        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setEnabled(false);

        assertThatThrownBy(() -> service.beforeUpdate(existing(), req))
                .isInstanceOf(InvalidOperationException.class).hasMessageContaining("不能停用");
    }

    @Test
    void 未被引用时改modelId_用模型现有凭据探测新modelId() {
        when(applicationRepository.countEmbeddingModelReferences(7L)).thenReturn(0L);
        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setModelId("text-embedding-v4");

        service.beforeUpdate(existing(), req);

        // 端点与 Key 属于供应商连接（改它们的探测在 ModelProviderService）；这里只换 modelId
        ArgumentCaptor<AiModelConfig> cap = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(probe).requireCompatible(cap.capture());
        assertThat(cap.getValue().getModelId()).isEqualTo("text-embedding-v4");
        assertThat(cap.getValue().getApiKey()).isEqualTo("old-key");
        assertThat(cap.getValue().getEndpoint()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    @Test
    void 阈值超出范围_拒绝() {
        when(applicationRepository.countEmbeddingModelReferences(7L)).thenReturn(0L);
        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setSimilarityThreshold(1.5);

        assertThatThrownBy(() -> service.beforeUpdate(existing(), req)).isInstanceOf(InvalidOperationException.class);
    }

    @Test
    void 被引用的向量模型_不能删() {
        when(applicationRepository.countEmbeddingModelReferences(7L)).thenReturn(1L);

        assertThatThrownBy(() -> service.beforeDelete(existing())).isInstanceOf(InvalidOperationException.class);
    }

    @Test
    void 对话模型_更新与删除都不受向量约束() {
        AiModelConfig chat = AiModelConfig.builder().id(8L).modelId("gpt").modelType(ModelType.CHAT).build();
        AiModelConfigUpdateRequest req = new AiModelConfigUpdateRequest();
        req.setModelId("gpt-5");
        req.setEnabled(false);

        service.beforeUpdate(chat, req);
        service.beforeDelete(chat);

        verifyNoInteractions(probe, applicationRepository);
    }
}

package com.qingyu.care.service.embedding;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.ApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 「某应用该用哪个向量模型」的解析：钉默认、双写目标、可选集合、类型校验。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingModelResolverTest {

    @Mock ApplicationRepository applicationRepository;
    @Mock AiModelConfigRepository modelRepository;
    @Mock PlatformTransactionManager transactionManager;

    EmbeddingModelResolver resolver;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        resolver = new EmbeddingModelResolver(applicationRepository, modelRepository, transactionManager);
    }

    private static AiModelConfig embedding(long id, String modelId) {
        return AiModelConfig.builder().id(id).modelId(modelId).modelType(ModelType.EMBEDDING).build();
    }

    private static ApplicationRepository.EmbeddingPointers pointers(Long active, Long pending) {
        return new ApplicationRepository.EmbeddingPointers() {
            public Long getActive() { return active; }
            public Long getPending() { return pending; }
        };
    }

    @Test
    void active_未钉过_钉成平台默认并返回() {
        AiModelConfig def = embedding(1L, "qwen3.7-text-embedding");
        when(applicationRepository.findEmbeddingPointers(5L))
                .thenReturn(Optional.of(pointers(null, null)), Optional.of(pointers(1L, null)));
        when(modelRepository.findSharedEmbeddingModels()).thenReturn(List.of(def));
        when(modelRepository.findByIdAcrossTenants(1L)).thenReturn(Optional.of(def));

        assertThat(resolver.active(5L)).isSameAs(def);
        verify(applicationRepository).pinEmbeddingModelIfAbsent(5L, 1L);
    }

    @Test
    void active_平台没有任何向量模型_给出可读错误() {
        when(applicationRepository.findEmbeddingPointers(5L)).thenReturn(Optional.of(pointers(null, null)));
        when(modelRepository.findSharedEmbeddingModels()).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.active(5L))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("平台尚未配置可用的向量模型");
    }

    @Test
    void active_指针指向对话模型_拒绝而不是拿去打embeddings() {
        when(applicationRepository.findEmbeddingPointers(5L)).thenReturn(Optional.of(pointers(1L, null)));
        when(modelRepository.findByIdAcrossTenants(1L))
                .thenReturn(Optional.of(AiModelConfig.builder().id(1L).modelId("gpt").modelType(ModelType.CHAT).build()));

        assertThatThrownBy(() -> resolver.active(5L)).isInstanceOf(InvalidOperationException.class);
    }

    @Test
    void writeTargets_切换进行中且标签不同_双写当前与目标() {
        AiModelConfig a = embedding(1L, "qwen3.7-text-embedding");
        AiModelConfig b = embedding(2L, "bge-m3");
        when(applicationRepository.findEmbeddingPointers(5L)).thenReturn(Optional.of(pointers(1L, 2L)));
        when(modelRepository.findByIdAcrossTenants(1L)).thenReturn(Optional.of(a));
        when(modelRepository.findByIdAcrossTenants(2L)).thenReturn(Optional.of(b));

        assertThat(resolver.writeTargets(5L)).containsExactly(a, b);
    }

    @Test
    void writeTargets_目标与当前同标签_只写一份() {
        AiModelConfig a = embedding(1L, "bge-m3");
        AiModelConfig b = embedding(2L, "bge-m3");   // 同一个模型换了托管
        when(applicationRepository.findEmbeddingPointers(5L)).thenReturn(Optional.of(pointers(1L, 2L)));
        when(modelRepository.findByIdAcrossTenants(1L)).thenReturn(Optional.of(a));
        when(modelRepository.findByIdAcrossTenants(2L)).thenReturn(Optional.of(b));

        assertThat(resolver.writeTargets(5L)).containsExactly(a);
    }

    @Test
    void writeTargets_没有切换_只写当前() {
        AiModelConfig a = embedding(1L, "bge-m3");
        when(applicationRepository.findEmbeddingPointers(5L)).thenReturn(Optional.of(pointers(1L, null)));
        when(modelRepository.findByIdAcrossTenants(1L)).thenReturn(Optional.of(a));

        assertThat(resolver.writeTargets(5L)).containsExactly(a);
    }

    @Test
    void requireSelectable_别的应用自有的模型_按不存在拒绝() {
        when(modelRepository.findAppEmbeddingModels(5L)).thenReturn(List.of(embedding(3L, "own")));
        when(modelRepository.findSharedEmbeddingModels()).thenReturn(List.of(embedding(1L, "shared")));

        assertThat(resolver.requireSelectable(5L, 3L).getId()).isEqualTo(3L);
        assertThat(resolver.requireSelectable(5L, 1L).getId()).isEqualTo(1L);
        // 99 是别的应用的模型：可选集合里没有它，就算库里存在也拒绝（否则就是借别人的 Key 出站）
        assertThatThrownBy(() -> resolver.requireSelectable(5L, 99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 缺少应用_明确报错() {
        assertThatThrownBy(() -> resolver.active(null)).isInstanceOf(InvalidOperationException.class);
    }
}

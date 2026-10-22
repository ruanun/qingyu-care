package com.qingyu.care.service;

import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.repository.AiModelConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对话客户端缓存（{@code chatClientFor}）：与 {@code EmbeddingModelRegistry} 同一套 (id, updatedAt) 语义。
 *
 * <p>不缓存的代价是每条消息为降级列表里每个候选新建一个 SDK 客户端及其连接池，全部不复用——
 * 而这是逐条消息发生的，静默、无报错，只有延迟和内存曲线能看出来。所以把它钉成单测。
 *
 * @author ruan
 */
class ChatClientCacheTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final LocalDateTime T2 = T1.plusMinutes(1);

    private static AiModelConfig model(Long id, LocalDateTime updatedAt) {
        return AiModelConfig.builder()
                .id(id)
                .name("m" + id)
                .provider("dashscope")
                .modelId("qwen3.7-flash-2026-07-15")
                .endpoint("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey("sk-test")
                .scenario("default")
                .priority(1)
                .enabled(true)
                .createdAt(T1)
                .updatedAt(updatedAt)
                .build();
    }

    private static ModelRouterService service() {
        return new ModelRouterService(mock(AiModelConfigRepository.class), mock(SharedCredentialMasker.class));
    }

    @Test
    void 同一行且updatedAt未变_复用同一个客户端() {
        ModelRouterService svc = service();
        AiModelConfig m = model(1L, T1);

        // 两次拿到的是新查出来的实体，id 与 updatedAt 相同——这是热路径的真实形态
        assertThat(svc.chatClientFor(model(1L, T1))).isSameAs(svc.chatClientFor(m));
    }

    @Test
    void updatedAt变了_换成新客户端() {
        ModelRouterService svc = service();
        ChatClient before = svc.chatClientFor(model(1L, T1));

        // 改供应商连接的 Key 会由 syncFromProvider 刷新模型的 updated_at，缓存正是靠它失效
        assertThat(svc.chatClientFor(model(1L, T2))).isNotSameAs(before);
        assertThat(svc.chatClientFor(model(1L, T2))).isSameAs(svc.chatClientFor(model(1L, T2)));
    }

    @Test
    void 不同模型行各拿一个客户端_不串() {
        ModelRouterService svc = service();
        assertThat(svc.chatClientFor(model(1L, T1))).isNotSameAs(svc.chatClientFor(model(2L, T1)));
    }

    @Test
    void 未落库的行不进缓存_每次都新建() {
        ModelRouterService svc = service();
        // id 为 null 是保存前探测 / 单测里的临时对象，没有稳定的失效版本
        assertThat(svc.chatClientFor(model(null, T1))).isNotSameAs(svc.chatClientFor(model(null, T1)));
    }

    /**
     * 没有启用的平台共享对话模型时启动要打 WARN。
     *
     * <p>守的是「静默的空兜底」：`seedIfEmpty` 只在整表为空时跑，平台模型被删光后表里还有租户模型，
     * 于是永远不会重新播种，症状只是对话报「没有可用的 AI 模型配置」。
     */
    @Test
    void 平台无可用共享对话模型时报警() {
        AiModelConfigRepository repository = mock(AiModelConfigRepository.class);
        ModelRouterService svc = new ModelRouterService(repository, mock(SharedCredentialMasker.class));

        when(repository.count()).thenReturn(3L);
        when(repository.findSharedModelsByScenario("default")).thenReturn(java.util.List.of());

        assertThat(svc.sharedChatModelWarning()).isPresent();
    }

    @Test
    void 有平台共享对话模型时不报警() {
        AiModelConfigRepository repository = mock(AiModelConfigRepository.class);
        ModelRouterService svc = new ModelRouterService(repository, mock(SharedCredentialMasker.class));

        when(repository.count()).thenReturn(3L);
        when(repository.findSharedModelsByScenario("default")).thenReturn(java.util.List.of(model(9L, T1)));

        assertThat(svc.sharedChatModelWarning()).isEmpty();
    }

    @Test
    void 表为空时不报警_那是刚播种完的正常路径() {
        AiModelConfigRepository repository = mock(AiModelConfigRepository.class);
        ModelRouterService svc = new ModelRouterService(repository, mock(SharedCredentialMasker.class));

        when(repository.count()).thenReturn(0L);

        assertThat(svc.sharedChatModelWarning()).isEmpty();
        // 表为空时不该再去查共享模型
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findSharedModelsByScenario(anyString());
    }
}

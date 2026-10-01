package com.qingyu.care.service;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.AiModelConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelRouterServiceMergeTest {

    // 模型解析要求有租户上下文或处在请求线程里（后台线程兜底，见下方最后一条）；仓库是 mock，取值不影响合并逻辑
    @org.junit.jupiter.api.BeforeEach
    void setTenant() {
        com.qingyu.care.config.TenantContext.setTenantId(1L);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenant() {
        com.qingyu.care.config.TenantContext.clear();
    }

    @Mock AiModelConfigRepository repository;
    @InjectMocks ModelRouterService service;

    private AiModelConfig model(String name, String provider, String modelId, int priority, OwnerScope scope) {
        return AiModelConfig.builder()
                .name(name).provider(provider).modelId(modelId)
                .endpoint("http://x").scenario("default").priority(priority)
                .enabled(true).scope(scope).build();
    }

    private AiModelConfig modelWithEndpoint(String name, String provider, String modelId,
                                            String endpoint, OwnerScope scope) {
        return AiModelConfig.builder()
                .name(name).provider(provider).modelId(modelId)
                .endpoint(endpoint).scenario("default").priority(1)
                .enabled(true).scope(scope).build();
    }

    @Test
    void mergesTenantOwnBeforeShared() {
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default"))
                .thenReturn(List.of(model("own", "openai", "gpt-a", 1, OwnerScope.TENANT)));
        when(repository.findSharedModelsByScenario("default"))
                .thenReturn(List.of(model("shared", "dashscope", "qwen", 1, OwnerScope.PLATFORM)));

        List<AiModelConfig> result = service.getModelsForScenario("default");

        assertThat(result).extracting(AiModelConfig::getName).containsExactly("own", "shared");
    }

    @Test
    void dedupsByProviderModelEndpoint_keepingTenantOwn() {
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default"))
                .thenReturn(List.of(model("own", "openai", "gpt-a", 1, OwnerScope.TENANT)));
        when(repository.findSharedModelsByScenario("default"))
                .thenReturn(List.of(model("shared-dup", "openai", "gpt-a", 1, OwnerScope.PLATFORM)));

        List<AiModelConfig> result = service.getModelsForScenario("default");

        assertThat(result).extracting(AiModelConfig::getName).containsExactly("own");
    }

    @Test
    void fallsBackToDefaultScenario_whenScenarioEmpty() {
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("complaint"))
                .thenReturn(List.of());
        when(repository.findSharedModelsByScenario("complaint"))
                .thenReturn(List.of());
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default"))
                .thenReturn(List.of());
        when(repository.findSharedModelsByScenario("default"))
                .thenReturn(List.of(model("shared-default", "dashscope", "qwen", 1, OwnerScope.PLATFORM)));

        List<AiModelConfig> result = service.getModelsForScenario("complaint");

        assertThat(result).extracting(AiModelConfig::getName).containsExactly("shared-default");
    }

    @Test
    void dedupsWhenTenantEndpointNull_matchesSharedDefaultEndpoint() {
        // 租户模型 endpoint 为 null，运行时会走 openai 的默认端点 https://api.openai.com/v1；
        // 共享模型显式配置为该默认端点。归一化后二者 key 相同，应只保留租户那条。
        // 此处字面量须与 getDefaultEndpoint("openai") 逐字一致——Spring AI 2.0 起该端点带 /v1。
        when(repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default"))
                .thenReturn(List.of(modelWithEndpoint("own", "openai", "gpt-a", null, OwnerScope.TENANT)));
        when(repository.findSharedModelsByScenario("default"))
                .thenReturn(List.of(modelWithEndpoint("shared-dup", "openai", "gpt-a",
                        "https://api.openai.com/v1", OwnerScope.PLATFORM)));

        List<AiModelConfig> result = service.getModelsForScenario("default");

        assertThat(result).extracting(AiModelConfig::getName).containsExactly("own");
    }

    /**
     * 2026-09-30 安全审查 H1 的机制层兜底：没有租户上下文的后台线程里，过滤器不生效、会查到全平台所有租户的模型。
     * 直接拒绝，别让新的 @Async / @Scheduled 调用方把泄漏带回来。
     */
    @Test
    void 后台线程没有租户上下文时拒绝解析模型() {
        com.qingyu.care.config.TenantContext.clear();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getModelsForScenario("default"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户上下文");
    }
}

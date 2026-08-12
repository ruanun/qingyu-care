package com.qingyu.care.service;

import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.AiModelConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModelRouterService.getAllModels() 的应用级收窄单测：TenantDataAspect 对 PLATFORM_ADMIN
 * 整体不启用 @Filter，原先裸 findAllByOrderByScenarioAscPriorityAsc 会让平台管理员跨应用/
 * 跨租户读全平台模型配置（含明文 apiKey）。收窄为「当前应用自有 ∪ 平台共享(scope=PLATFORM)」，
 * 按 id 去重；平台共享模型（多级回退的关键一环）必须始终可见，不受 appId 是否设置影响。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ModelRouterServiceListScopeTest {

    @Mock AiModelConfigRepository repository;
    @Mock SharedCredentialMasker credentialMasker;
    @InjectMocks ModelRouterService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AiModelConfig model(Long id, OwnerScope scope) {
        return AiModelConfig.builder().id(id).name("m" + id).provider("openai").modelId("gpt-4o")
                .scenario("default").enabled(true).scope(scope).build();
    }

    @Test
    void getAllModels_有appId_含自有与平台共享() {
        TenantContext.setAppId(10L);
        AiModelConfig own = model(1L, OwnerScope.TENANT);
        AiModelConfig shared = model(2L, OwnerScope.PLATFORM);
        when(repository.findByAppIdOrderByScenarioAscPriorityAsc(10L)).thenReturn(List.of(own));
        when(repository.findAllSharedModels()).thenReturn(List.of(shared));
        when(credentialMasker.mask(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        List<AiModelConfig> result = service.getAllModels();

        assertThat(result).extracting(AiModelConfig::getId).containsExactly(1L, 2L);
        verify(repository, never()).findAllByOrderByScenarioAscPriorityAsc();
    }

    @Test
    void getAllModels_无appId_平台共享仍可见_自有查询不被调用() {
        AiModelConfig shared = model(2L, OwnerScope.PLATFORM);
        when(repository.findAllSharedModels()).thenReturn(List.of(shared));
        when(credentialMasker.mask(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        List<AiModelConfig> result = service.getAllModels();

        assertThat(result).extracting(AiModelConfig::getId).containsExactly(2L);
        verify(repository, never()).findByAppIdOrderByScenarioAscPriorityAsc(any());
        verify(repository, never()).findAllByOrderByScenarioAscPriorityAsc();
    }

    @Test
    void getAllModels_按id去重() {
        TenantContext.setAppId(10L);
        AiModelConfig own = model(1L, OwnerScope.TENANT);
        AiModelConfig dup = model(1L, OwnerScope.PLATFORM); // 同 id 极端场景，防御性去重
        when(repository.findByAppIdOrderByScenarioAscPriorityAsc(10L)).thenReturn(List.of(own));
        when(repository.findAllSharedModels()).thenReturn(List.of(dup));
        when(credentialMasker.mask(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        List<AiModelConfig> result = service.getAllModels();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(own);
    }
}

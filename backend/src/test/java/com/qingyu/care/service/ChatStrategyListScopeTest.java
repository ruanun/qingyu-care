package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
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
 * ChatStrategyService.getAllStrategies() 的应用级收窄单测：TenantDataAspect 对 PLATFORM_ADMIN
 * 整体不启用 @Filter，原先裸 findAll 会让平台管理员跨租户读全平台策略。收窄为
 * 「租户自有(按 tenantId) ∪ 平台默认(tenant_id IS NULL，原生查询)」，按 id 去重；
 * 平台默认策略必须始终可见——这是多级配置回退的关键一环，无租户上下文也不能被过滤掉。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatStrategyListScopeTest {

    @Mock ChatStrategyRepository repository;
    @Mock AbExperimentRepository abExperimentRepository;
    @InjectMocks ChatStrategyService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatStrategy strategy(Long id, Long tenantId) {
        ChatStrategy s = ChatStrategy.builder().id(id).name("s" + id).scenario("default").enabled(true).build();
        s.setTenantId(tenantId);
        return s;
    }

    @Test
    void getAllStrategies_有tenantId_含自有与平台默认() {
        TenantContext.setTenantId(2L);
        ChatStrategy own = strategy(1L, 2L);
        ChatStrategy platformDefault = strategy(2L, null);
        when(repository.findByTenantId(2L)).thenReturn(List.of(own));
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(platformDefault));

        List<ChatStrategy> result = service.getAllStrategies();

        assertThat(result).extracting(ChatStrategy::getId).containsExactly(1L, 2L);
        verify(repository, never()).findAll();
    }

    @Test
    void getAllStrategies_无tenantId_平台默认仍可见_自有查询不被调用() {
        ChatStrategy platformDefault = strategy(2L, null);
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(platformDefault));

        List<ChatStrategy> result = service.getAllStrategies();

        assertThat(result).extracting(ChatStrategy::getId).containsExactly(2L);
        verify(repository, never()).findByTenantId(any());
        verify(repository, never()).findAll();
    }

    @Test
    void getAllStrategies_按id去重() {
        TenantContext.setTenantId(2L);
        ChatStrategy own = strategy(1L, 2L);
        ChatStrategy dup = strategy(1L, null);
        when(repository.findByTenantId(2L)).thenReturn(List.of(own));
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(dup));

        List<ChatStrategy> result = service.getAllStrategies();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(own);
    }
}

package com.qingyu.care.service;

import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.PromptTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * getActiveTemplate 的作用域解析：经 {@link ConfigScopeResolver} 显式按有效 (tenantId, appId)
 * 收窄取自有激活模板，避免平台管理员绕过 Hibernate @Filter 时 NonUniqueResultException。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateServiceTest {

    @Mock
    private PromptTemplateRepository repository;

    @Mock
    private ConfigScopeResolver configScopeResolver;

    @InjectMocks
    private PromptTemplateService service;

    @Test
    void getActiveTemplate_有有效作用域_取自有激活模板() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        PromptTemplate own = PromptTemplate.builder().name("租户4").content("C").isActive(true).build();
        when(repository.findActiveScoped(4L, 3L)).thenReturn(java.util.List.of(own));

        assertThat(service.getActiveTemplate().getName()).isEqualTo("租户4");
        verify(repository, never()).findPlatformDefaultActive();
    }

    @Test
    void getActiveTemplate_作用域无租户_跳过自有落平台默认() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(null, 3L));
        PromptTemplate platform = PromptTemplate.builder().name("平台").content("P").isActive(true).build();
        when(repository.findPlatformDefaultActive()).thenReturn(java.util.Optional.of(platform));

        assertThat(service.getActiveTemplate().getName()).isEqualTo("平台");
        verify(repository, never()).findActiveScoped(any(), any());
    }

    @Test
    void getActiveTemplate_均无_抛ResourceNotFound() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(null, 3L));
        when(repository.findPlatformDefaultActive()).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getActiveTemplate())
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getActiveTemplate_自有多行_findFirst不抛NonUnique() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        when(repository.findActiveScoped(4L, 3L)).thenReturn(java.util.List.of(
                PromptTemplate.builder().name("第一").content("c1").isActive(true).build(),
                PromptTemplate.builder().name("第二").content("c2").isActive(true).build()));

        assertThat(service.getActiveTemplate().getName()).isEqualTo("第一"); // ORDER BY id → findFirst
    }
}

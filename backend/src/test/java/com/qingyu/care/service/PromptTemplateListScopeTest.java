package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.repository.PromptTemplateRepository;
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
 * PromptTemplateService.getAllVersions() 的应用级收窄单测：TenantDataAspect 对 PLATFORM_ADMIN
 * 整体不启用 @Filter，原先裸 findAllByOrderByVersionDesc 会让平台管理员跨租户读全平台模板版本。
 * 收窄为「租户自有(按 tenantId) ∪ 平台默认(tenant_id IS NULL，原生查询)」，按 id 去重；
 * 平台默认模板必须始终可见，保住多级配置回退语义。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateListScopeTest {

    @Mock PromptTemplateRepository repository;
    @InjectMocks PromptTemplateService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private PromptTemplate template(Long id, Long tenantId) {
        PromptTemplate t = PromptTemplate.builder().id(id).name("t" + id).content("c").version(1).isActive(false).build();
        t.setTenantId(tenantId);
        return t;
    }

    @Test
    void getAllVersions_有tenantId_含自有与平台默认() {
        TenantContext.setTenantId(2L);
        PromptTemplate own = template(1L, 2L);
        PromptTemplate platformDefault = template(2L, null);
        when(repository.findByTenantIdOrderByVersionDesc(2L)).thenReturn(List.of(own));
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(platformDefault));

        List<PromptTemplate> result = service.getAllVersions();

        assertThat(result).extracting(PromptTemplate::getId).containsExactly(1L, 2L);
        verify(repository, never()).findAllByOrderByVersionDesc();
    }

    @Test
    void getAllVersions_无tenantId_平台默认仍可见_自有查询不被调用() {
        PromptTemplate platformDefault = template(2L, null);
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(platformDefault));

        List<PromptTemplate> result = service.getAllVersions();

        assertThat(result).extracting(PromptTemplate::getId).containsExactly(2L);
        verify(repository, never()).findByTenantIdOrderByVersionDesc(any());
        verify(repository, never()).findAllByOrderByVersionDesc();
    }

    @Test
    void getAllVersions_按id去重() {
        TenantContext.setTenantId(2L);
        PromptTemplate own = template(1L, 2L);
        PromptTemplate dup = template(1L, null);
        when(repository.findByTenantIdOrderByVersionDesc(2L)).thenReturn(List.of(own));
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(dup));

        List<PromptTemplate> result = service.getAllVersions();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(own);
    }
}

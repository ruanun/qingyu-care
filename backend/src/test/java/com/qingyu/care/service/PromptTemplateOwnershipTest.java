package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.PromptTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * PromptTemplateService.getById 的租户归属校验单测。
 * getById 走 findById（不受 @Filter 保护），未校验归属时租户可枚举 id 跨租户读/改/删他人模板。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateOwnershipTest {

    @Mock PromptTemplateRepository repository;
    @InjectMocks PromptTemplateService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private PromptTemplate ownedBy(Long tenantId) {
        PromptTemplate t = PromptTemplate.builder().id(1L).name("他租户模板").content("c").isActive(false).build();
        t.setTenantId(tenantId);
        return t;
    }

    @Test
    void getById_他租户模板_按不存在处理() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_本租户模板_正常返回() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(2L)));
        TenantContext.setTenantId(2L);

        assertThat(service.getById(1L)).isNotNull();
    }

    @Test
    void getById_平台共享模板_租户可见() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));
        TenantContext.setTenantId(2L);

        assertThat(service.getById(1L)).isNotNull();
    }
}

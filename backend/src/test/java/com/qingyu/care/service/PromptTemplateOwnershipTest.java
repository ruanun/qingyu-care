package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.exception.BusinessException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // ---- 写路径守卫：读放行平台行是为保多级回退，激活/删除必须收紧 ----

    @Test
    void activateVersion_租户激活平台模板_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.activateVersion(1L))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).deactivateAllPlatform();
        verify(repository, never()).deactivateAllForTenant(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void deleteVersion_租户删平台模板_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.deleteVersion(1L))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void activateVersion_租户激活自有模板_只清本租户应用范围() {
        // bulk UPDATE 不受 @Filter 保护：激活前的「取消旧激活位」必须显式限定租户/应用范围，
        // 否则会把全库（含平台默认与其他租户）的激活模板一并置为非激活
        PromptTemplate own = ownedBy(2L);
        own.setAppId(10L);
        when(repository.findById(1L)).thenReturn(Optional.of(own));
        TenantContext.setTenantId(2L);
        TenantContext.setAppId(10L);

        service.activateVersion(1L);

        verify(repository).deactivateAllForTenant(2L, 10L);
        verify(repository, never()).deactivateAllPlatform();
        assertThat(own.getIsActive()).isTrue();
    }

    @Test
    void activateVersion_平台管理员激活平台模板_只清平台范围() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));

        service.activateVersion(1L);

        verify(repository).deactivateAllPlatform();
        verify(repository, never()).deactivateAllForTenant(any(), any());
    }
}

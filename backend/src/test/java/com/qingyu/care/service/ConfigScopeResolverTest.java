package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.repository.ApplicationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigScopeResolverTest {

    @Mock ApplicationRepository applicationRepository;
    @InjectMocks ConfigScopeResolver resolver;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 有租户上下文_原样返回且不查Application() {
        TenantContext.setTenantId(7L);
        TenantContext.setAppId(3L);

        ConfigScopeResolver.Scope scope = resolver.resolve();

        assertThat(scope.tenantId()).isEqualTo(7L);
        assertThat(scope.appId()).isEqualTo(3L);
        verifyNoInteractions(applicationRepository);
    }

    @Test
    void 平台管理员无租户但有应用_反查应用归属租户() {
        TenantContext.setAppId(3L);   // tenantId 为空
        when(applicationRepository.findTenantIdById(3L)).thenReturn(Optional.of(4L));

        ConfigScopeResolver.Scope scope = resolver.resolve();

        assertThat(scope.tenantId()).isEqualTo(4L);
        assertThat(scope.appId()).isEqualTo(3L);
    }

    @Test
    void 无租户无应用_返回全空() {
        ConfigScopeResolver.Scope scope = resolver.resolve();

        assertThat(scope.tenantId()).isNull();
        assertThat(scope.appId()).isNull();
        verifyNoInteractions(applicationRepository);
    }

    @Test
    void 有应用但应用查不到归属租户_租户仍为空() {
        TenantContext.setAppId(99L);
        when(applicationRepository.findTenantIdById(99L)).thenReturn(Optional.empty());

        ConfigScopeResolver.Scope scope = resolver.resolve();

        assertThat(scope.tenantId()).isNull();
        assertThat(scope.appId()).isEqualTo(99L);
    }
}

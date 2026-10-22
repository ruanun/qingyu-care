package com.qingyu.care.service;

import com.qingyu.care.entity.SysConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.SysConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock SysConfigRepository repository;
    @InjectMocks ConfigService service;

    @Test
    void getBoolean_returnsDefault_whenKeyMissing() {
        when(repository.findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope.PLATFORM, "platform.registration.enabled"))
                .thenReturn(Optional.empty());

        boolean v = service.getBoolean(OwnerScope.PLATFORM, null, "platform.registration.enabled", true);

        assertThat(v).isTrue();
    }

    @Test
    void getBoolean_parsesStoredValue() {
        when(repository.findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope.PLATFORM, "platform.registration.enabled"))
                .thenReturn(Optional.of(SysConfig.builder().configValue("false").build()));

        boolean v = service.getBoolean(OwnerScope.PLATFORM, null, "platform.registration.enabled", true);

        assertThat(v).isFalse();
    }

    @Test
    void getAll_platform_readsByTenantIdIsNull() {
        when(repository.findByScopeAndTenantIdIsNull(OwnerScope.PLATFORM))
                .thenReturn(List.of(
                        SysConfig.builder().configKey("a").configValue("1").build(),
                        SysConfig.builder().configKey("b").configValue("2").build()));

        var map = service.getAll(OwnerScope.PLATFORM, null);

        assertThat(map).containsEntry("a", "1").containsEntry("b", "2");
        verify(repository).findByScopeAndTenantIdIsNull(OwnerScope.PLATFORM);
    }

    @Test
    void getAll_tenant_readsByTenantId() {
        when(repository.findByScopeAndTenantId(OwnerScope.TENANT, 7L))
                .thenReturn(List.of(SysConfig.builder().configKey("x").configValue("y").build()));

        var map = service.getAll(OwnerScope.TENANT, 7L);

        assertThat(map).containsEntry("x", "y");
        verify(repository).findByScopeAndTenantId(OwnerScope.TENANT, 7L);
    }

    @Test
    void set_insertsNewConfig_whenAbsent() {
        when(repository.findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope.PLATFORM, "k"))
                .thenReturn(Optional.empty());

        service.set(OwnerScope.PLATFORM, null, "k", "v", "desc");

        verify(repository).save(argThat(c ->
                c.getScope() == OwnerScope.PLATFORM
                        && c.getTenantId() == null
                        && "k".equals(c.getConfigKey())
                        && "v".equals(c.getConfigValue())));
    }

    @Test
    void set_updatesExisting_whenPresent() {
        SysConfig existing = SysConfig.builder().id(3L).scope(OwnerScope.TENANT)
                .tenantId(5L).configKey("k").configValue("old").build();
        when(repository.findByScopeAndTenantIdAndConfigKey(OwnerScope.TENANT, 5L, "k"))
                .thenReturn(Optional.of(existing));

        service.set(OwnerScope.TENANT, 5L, "k", "new", null);

        verify(repository).save(argThat(c -> c.getId().equals(3L) && "new".equals(c.getConfigValue())));
    }

    @Test
    void getInt_parsesStoredNumber() {
        when(repository.findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope.PLATFORM, "platform.session.timeout"))
                .thenReturn(Optional.of(SysConfig.builder().configValue("30").build()));

        int v = service.getInt(OwnerScope.PLATFORM, null, "platform.session.timeout", 10);

        assertThat(v).isEqualTo(30);
    }

    @Test
    void getInt_returnsDefault_whenValueNotNumeric() {
        when(repository.findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope.PLATFORM, "platform.session.timeout"))
                .thenReturn(Optional.of(SysConfig.builder().configValue("abc").build()));

        int v = service.getInt(OwnerScope.PLATFORM, null, "platform.session.timeout", 10);

        assertThat(v).isEqualTo(10);
    }
}

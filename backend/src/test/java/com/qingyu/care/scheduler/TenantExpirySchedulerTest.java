package com.qingyu.care.scheduler;

import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.SessionRevocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户到期自动停用。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class TenantExpirySchedulerTest {

    @Mock TenantRepository tenantRepository;
    @Mock SessionRevocationService sessionRevocationService;
    @InjectMocks TenantExpiryScheduler scheduler;

    private static Tenant tenant(long id) {
        return Tenant.builder().id(id).name("t" + id).status(TenantStatus.ACTIVE)
                .expireTime(LocalDateTime.now().minusDays(1)).build();
    }

    /**
     * 条件更新返回 0 表示别人（另一个实例、或恰好操作的平台管理员）已经改过状态：
     * 只有真正改成功的一方去踢人，否则多实例部署下每个租户会被踢 N 次、审计日志也跟着重复。
     */
    @Test
    void 只有条件更新成功的租户才吊销登录态() {
        when(tenantRepository.findByStatusAndExpireTimeBefore(eq(TenantStatus.ACTIVE), any())).thenReturn(List.of(tenant(1), tenant(2)));
        when(tenantRepository.transitionStatus(1L, TenantStatus.ACTIVE, TenantStatus.EXPIRED)).thenReturn(1);
        when(tenantRepository.transitionStatus(2L, TenantStatus.ACTIVE, TenantStatus.EXPIRED)).thenReturn(0);

        scheduler.expireOverdueTenants();

        verify(sessionRevocationService).revokeTenant(1L, "租户套餐到期");
        verify(sessionRevocationService, never()).revokeTenant(eq(2L), anyString());
    }

    @Test
    void 单个租户失败不影响其余() {
        when(tenantRepository.findByStatusAndExpireTimeBefore(eq(TenantStatus.ACTIVE), any())).thenReturn(List.of(tenant(1), tenant(2)));
        when(tenantRepository.transitionStatus(1L, TenantStatus.ACTIVE, TenantStatus.EXPIRED)).thenThrow(new RuntimeException("db down"));
        when(tenantRepository.transitionStatus(2L, TenantStatus.ACTIVE, TenantStatus.EXPIRED)).thenReturn(1);

        scheduler.expireOverdueTenants();

        verify(sessionRevocationService).revokeTenant(2L, "租户套餐到期");
    }
}

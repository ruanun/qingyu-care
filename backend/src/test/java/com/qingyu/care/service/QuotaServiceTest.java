package com.qingyu.care.service;

import com.qingyu.care.dto.QuotaCheckResult;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.QuotaUsageRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock ConfigService configService;
    @Mock TenantRepository tenantRepository;
    @Mock SysApiUsageRepository usageRepository;
    @InjectMocks QuotaService service;

    private QuotaUsageRow usage(long calls, long tokens) {
        return new QuotaUsageRow() {
            public long getCalls() { return calls; }
            public long getTokens() { return tokens; }
        };
    }

    private void stubPlan(Long tenantId, TenantPlan plan) {
        when(tenantRepository.findById(tenantId))
                .thenReturn(Optional.of(Tenant.builder().plan(plan).build()));
    }

    private void stubQuota(TenantPlan plan, long calls, long tokens) {
        when(configService.getLong(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota." + plan.name() + ".calls"), anyLong())).thenReturn(calls);
        when(configService.getLong(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota." + plan.name() + ".tokens"), anyLong())).thenReturn(tokens);
    }

    private void stubWarn(int pct) {
        when(configService.getInt(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota.warn-threshold"), anyInt())).thenReturn(pct);
    }

    @Test
    void 未超额_未近阈值() {
        stubPlan(1L, TenantPlan.FREE);
        stubQuota(TenantPlan.FREE, 100, 10000);
        stubWarn(80);
        when(usageRepository.sumSince(eq(1L), any(LocalDate.class))).thenReturn(usage(10, 1000));

        QuotaCheckResult r = service.check(1L);
        assertThat(r.exceeded()).isFalse();
        assertThat(r.nearThreshold()).isFalse();
        assertThat(r.callsUsed()).isEqualTo(10);
        assertThat(r.callsLimit()).isEqualTo(100);
    }

    @Test
    void 调用超额即exceeded() {
        stubPlan(1L, TenantPlan.FREE);
        stubQuota(TenantPlan.FREE, 100, 10000);
        stubWarn(80);
        when(usageRepository.sumSince(eq(1L), any(LocalDate.class))).thenReturn(usage(100, 500));

        assertThat(service.check(1L).exceeded()).isTrue();
    }

    @Test
    void token超额即exceeded() {
        stubPlan(1L, TenantPlan.PRO);
        stubQuota(TenantPlan.PRO, 100000, 1000);
        stubWarn(80);
        when(usageRepository.sumSince(eq(1L), any(LocalDate.class))).thenReturn(usage(5, 1000));

        assertThat(service.check(1L).exceeded()).isTrue();
    }

    @Test
    void 近阈值_放行但标记() {
        stubPlan(1L, TenantPlan.FREE);
        stubQuota(TenantPlan.FREE, 100, 10000);
        stubWarn(80);
        when(usageRepository.sumSince(eq(1L), any(LocalDate.class))).thenReturn(usage(85, 100));

        QuotaCheckResult r = service.check(1L);
        assertThat(r.exceeded()).isFalse();
        assertThat(r.nearThreshold()).isTrue();
    }

    @Test
    void 额度0表示不限_直接放行() {
        stubPlan(1L, TenantPlan.ENTERPRISE);
        stubQuota(TenantPlan.ENTERPRISE, 0, 0);
        // 两维度都不限 → 快路径，不查用量
        QuotaCheckResult r = service.check(1L);
        assertThat(r.exceeded()).isFalse();
        assertThat(r.nearThreshold()).isFalse();
    }

    @Test
    void 检查异常_failOpen放行() {
        when(tenantRepository.findById(1L)).thenThrow(new RuntimeException("db down"));
        QuotaCheckResult r = service.check(1L);
        assertThat(r.exceeded()).isFalse();
    }
}

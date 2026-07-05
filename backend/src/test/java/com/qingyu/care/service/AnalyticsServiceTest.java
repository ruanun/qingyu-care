package com.qingyu.care.service;

import com.qingyu.care.dto.UsageAnalyticsDTO;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.SessionSummaryRow;
import com.qingyu.care.repository.projection.UsageDailyRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock SysApiUsageRepository usageRepository;
    @Mock ChatSessionRepository sessionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock AuthService authService;
    @InjectMocks AnalyticsService service;

    private UsageDailyRow usageRow(LocalDate d, long calls, long tokens, long messages) {
        return new UsageDailyRow() {
            public LocalDate getDate() { return d; }
            public long getCalls() { return calls; }
            public long getTokens() { return tokens; }
            public long getMessages() { return messages; }
        };
    }

    private SessionSummaryRow summaryRow(long total, long ended, long ai, long handover) {
        return new SessionSummaryRow() {
            public long getTotal() { return total; }
            public long getEnded() { return ended; }
            public long getAiResolved() { return ai; }
            public long getHandover() { return handover; }
        };
    }

    @Test
    void 平台管理员_跨租户聚合_排行按租户_且appId被忽略() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(usageRepository.aggregateDaily(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateDailySessions(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(0, 0, 0, 0));
        when(usageRepository.rankByTenant(any(), any())).thenReturn(List.of());

        UsageAnalyticsDTO dto = service.getUsage(30, 999L); // 传 appId 也应被忽略

        assertThat(dto.scope()).isEqualTo("PLATFORM");
        verify(usageRepository).aggregateDaily(any(), isNull(), isNull());
        verify(usageRepository).rankByTenant(any(), any());
        verify(usageRepository, never()).rankByApp(any(), anyLong(), any());
    }

    @Test
    void 租户视角_限本租户_排行按应用_支持appId下钻() {
        when(authService.isPlatformAdmin()).thenReturn(false);
        when(authService.getCurrentTenantId()).thenReturn(7L);
        when(usageRepository.aggregateDaily(any(), eq(7L), eq(3L))).thenReturn(List.of());
        when(sessionRepository.aggregateDailySessions(any(), eq(7L), eq(3L))).thenReturn(List.of());
        when(sessionRepository.aggregateSessionSummary(any(), eq(7L), eq(3L)))
                .thenReturn(summaryRow(0, 0, 0, 0));
        when(usageRepository.rankByApp(any(), eq(7L), any())).thenReturn(List.of());

        UsageAnalyticsDTO dto = service.getUsage(7, 3L);

        assertThat(dto.scope()).isEqualTo("TENANT");
        verify(usageRepository).rankByApp(any(), eq(7L), any());
        verify(usageRepository, never()).rankByTenant(any(), any());
    }

    @Test
    void 趋势补零_区间内每天一个点_缺失天为0() {
        LocalDate today = LocalDate.now();
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(usageRepository.aggregateDaily(any(), isNull(), isNull()))
                .thenReturn(List.of(usageRow(today, 10, 100, 8)));
        when(sessionRepository.aggregateDailySessions(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(0, 0, 0, 0));
        when(usageRepository.rankByTenant(any(), any())).thenReturn(List.of());

        UsageAnalyticsDTO dto = service.getUsage(30, null);

        assertThat(dto.trend()).hasSize(30);
        assertThat(dto.trend().get(29).date()).isEqualTo(today);
        assertThat(dto.trend().get(29).calls()).isEqualTo(10);
        assertThat(dto.trend().get(0).calls()).isEqualTo(0); // 最早一天无数据补零
        assertThat(dto.summary().totalCalls()).isEqualTo(10);
        assertThat(dto.summary().totalMessages()).isEqualTo(8);
    }

    @Test
    void 口径_解决率与转人工率_分母为0不除零() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(usageRepository.aggregateDaily(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateDailySessions(any(), isNull(), isNull())).thenReturn(List.of());
        when(usageRepository.rankByTenant(any(), any())).thenReturn(List.of());

        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(10, 4, 3, 2));
        UsageAnalyticsDTO dto = service.getUsage(30, null);
        assertThat(dto.summary().aiResolutionRate()).isEqualTo(75.0);  // 3/4
        assertThat(dto.summary().handoverRate()).isEqualTo(20.0);      // 2/10
        assertThat(dto.summary().totalSessions()).isEqualTo(10);

        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(0, 0, 0, 0));
        UsageAnalyticsDTO empty = service.getUsage(30, null);
        assertThat(empty.summary().aiResolutionRate()).isEqualTo(0.0);
        assertThat(empty.summary().handoverRate()).isEqualTo(0.0);
    }

    @Test
    void days非法值回落30() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(usageRepository.aggregateDaily(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateDailySessions(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(0, 0, 0, 0));
        when(usageRepository.rankByTenant(any(), any())).thenReturn(List.of());

        assertThat(service.getUsage(5, null).trend()).hasSize(30);
        assertThat(service.getUsage(90, null).trend()).hasSize(90);
    }
}

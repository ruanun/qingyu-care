package com.qingyu.care.service;

import com.qingyu.care.dto.QualityAnalyticsDTO;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.StatDocHitDailyRepository;
import com.qingyu.care.repository.StatHotKeywordDailyRepository;
import com.qingyu.care.repository.StatQualityDailyRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.QualityDailyRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：质量看板的角色视角、趋势补零与率值口径。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsQualityServiceTest {

    @Mock SysApiUsageRepository usageRepository;
    @Mock ChatSessionRepository sessionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock AuthService authService;
    @Mock StatQualityDailyRepository qualityRepository;
    @Mock StatHotKeywordDailyRepository hotKeywordRepository;
    @Mock StatDocHitDailyRepository docHitRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock DocumentRepository documentRepository;
    @InjectMocks AnalyticsService service;

    /** projection 行桩（接口 getter 无法用 record 实现，用 Mockito 逐一打桩） */
    private QualityDailyRow row(LocalDate date, long aiMsg, Double confSum, long lowConf, long fallback,
                                long question, long hit, long up, long down,
                                long sessions, long handover, long resolved, long ended) {
        QualityDailyRow r = mock(QualityDailyRow.class);
        lenient().when(r.getDate()).thenReturn(date);
        lenient().when(r.getAiMsgCount()).thenReturn(aiMsg);
        lenient().when(r.getConfSum()).thenReturn(confSum);
        lenient().when(r.getLowConfidenceCount()).thenReturn(lowConf);
        lenient().when(r.getFallbackCount()).thenReturn(fallback);
        lenient().when(r.getQuestionCount()).thenReturn(question);
        lenient().when(r.getHitQuestionCount()).thenReturn(hit);
        lenient().when(r.getFeedbackUp()).thenReturn(up);
        lenient().when(r.getFeedbackDown()).thenReturn(down);
        lenient().when(r.getSessionCount()).thenReturn(sessions);
        lenient().when(r.getHandoverCount()).thenReturn(handover);
        lenient().when(r.getResolvedCount()).thenReturn(resolved);
        lenient().when(r.getEndedCount()).thenReturn(ended);
        return r;
    }

    @Test
    void 租户视角_按租户过滤_趋势补零_汇总口径正确() {
        when(authService.isPlatformAdmin()).thenReturn(false);
        when(authService.getCurrentTenantId()).thenReturn(7L);
        LocalDate cutoff = LocalDate.now().minusDays(1);
        // 先求值 row()（内部对新 mock 做 when/thenReturn），避免与外层 when() 的未完成打桩产生冲突
        QualityDailyRow todayRow = row(cutoff, 10, 8.0, 2, 1, 12, 6, 3, 1, 5, 1, 2, 3);
        when(qualityRepository.aggregateDaily(cutoff.minusDays(6), 7L, null))
                .thenReturn(List.of(todayRow));

        QualityAnalyticsDTO dto = service.getQuality(7, null);

        assertThat(dto.scope()).isEqualTo("TENANT");
        assertThat(dto.statCutoff()).isEqualTo(cutoff);
        assertThat(dto.trend()).hasSize(7);                              // 补零后每天一个点
        assertThat(dto.trend().get(6).aiMsgCount()).isEqualTo(10);
        assertThat(dto.trend().get(0).aiMsgCount()).isZero();
        assertThat(dto.summary().avgConfidence()).isEqualTo(0.8);        // 8.0 / 10
        assertThat(dto.summary().lowConfidenceRate()).isEqualTo(20.0);   // 2/10
        assertThat(dto.summary().fallbackRate()).isEqualTo(10.0);        // 1/10
        assertThat(dto.summary().aiResolutionRate()).isEqualTo(66.7);    // 2/3 ended
        assertThat(dto.summary().handoverRate()).isEqualTo(20.0);        // 1/5 sessions
    }

    @Test
    void 平台视角_跨租户聚合_忽略appId下钻() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(qualityRepository.aggregateDaily(any(), isNull(), isNull())).thenReturn(List.of());

        QualityAnalyticsDTO dto = service.getQuality(30, 123L);

        assertThat(dto.scope()).isEqualTo("PLATFORM");
        verify(qualityRepository).aggregateDaily(any(), isNull(), isNull());
    }

    @Test
    void 热点问句实时窗口_从今日起算_热词窗口_从昨日截止推算() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(hotKeywordRepository.topKeywords(any(), isNull(), isNull(), any())).thenReturn(List.of());
        when(messageRepository.topQuestions(any(), isNull(), isNull(), anyInt())).thenReturn(List.of());

        service.getHotTopics(7, null);

        LocalDate cutoff = LocalDate.now().minusDays(1);
        // 热词（rollup）窗口：[cutoff-6, cutoff] 共 7 天
        verify(hotKeywordRepository).topKeywords(eq(cutoff.minusDays(6)), isNull(), isNull(), any());
        // 高频问句（实时）窗口：[今日-6, 今日] 共 7 天，不复用 rollup 起点
        verify(messageRepository).topQuestions(eq(LocalDate.now().minusDays(6).atStartOfDay()),
                isNull(), isNull(), anyInt());
    }
}

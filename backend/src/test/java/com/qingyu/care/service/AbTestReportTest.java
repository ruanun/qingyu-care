package com.qingyu.care.service;

import com.qingyu.care.dto.AbTestReportDTO;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.AbTestReportDao;
import com.qingyu.care.repository.ChatStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * A/B 报表合并与派生率单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AbTestReportTest {

    @Mock AbExperimentRepository repository;
    @Mock ChatStrategyRepository strategyRepository;
    @Mock AbTestReportDao reportDao;
    @InjectMocks AbTestService service;

    @Test
    void rate_正常计算保留4位() {
        assertThat(AbTestService.rate(1, 3)).isEqualTo(0.3333);
        assertThat(AbTestService.rate(1, 1)).isEqualTo(1.0);
    }

    @Test
    void rate_分母为0_返回null() {
        assertThat(AbTestService.rate(0, 0)).isNull();
    }

    @Test
    void getReport_合并两侧数据_缺数据变体全0() {
        AbExperiment exp = AbExperiment.builder().id(7L).name("实验")
                .status(ExperimentStatus.RUNNING)
                .variants(new ArrayList<>(List.of(
                        AbVariant.builder().name("A").strategyId(1L).weight(50).build(),
                        AbVariant.builder().name("B").strategyId(2L).weight(50).build())))
                .build();
        when(repository.findById(7L)).thenReturn(Optional.of(exp));
        // 仅 A 有数据：2 会话 1 转人工；4 提问 4 AI 回复 2 兜底 1 赞 1 踩
        when(reportDao.aggregateSessions(7L)).thenReturn(List.of(
                new AbTestReportDao.SessionRow("A", 2, 1, 1, 1)));
        when(reportDao.aggregateMessages(7L)).thenReturn(List.of(
                new AbTestReportDao.MessageRow("A", 4, 4, 0.72, 1, 2, 3, 1, 1)));

        AbTestReportDTO report = service.getReport(7L);

        assertThat(report.variants()).hasSize(2);
        AbTestReportDTO.VariantReport a = report.variants().get(0);
        assertThat(a.variantName()).isEqualTo("A");
        assertThat(a.sessionCount()).isEqualTo(2);
        assertThat(a.handoverRate()).isEqualTo(0.5);   // 1/2
        assertThat(a.fallbackRate()).isEqualTo(0.5);   // 2/4
        assertThat(a.likeRate()).isEqualTo(0.5);       // 1/(1+1)
        assertThat(a.hitRate()).isEqualTo(0.75);       // 3/4
        assertThat(a.resolvedRate()).isEqualTo(1.0);   // 1/1
        assertThat(a.avgConfidence()).isEqualTo(0.72);

        AbTestReportDTO.VariantReport b = report.variants().get(1);
        assertThat(b.variantName()).isEqualTo("B");
        assertThat(b.sessionCount()).isZero();
        assertThat(b.likeRate()).isNull();
        assertThat(b.fallbackRate()).isNull();
        assertThat(b.avgConfidence()).isNull();
    }
}

package com.qingyu.care.service;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AnalyticsRollupDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 单元测试：rollup 编排（先删后插顺序、区间校验、单日失败不阻断）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsRollupServiceTest {

    @Mock
    private AnalyticsRollupDao rollupDao;
    @Mock
    private TransactionTemplate transactionTemplate;
    @InjectMocks
    private AnalyticsRollupService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void passThroughTx() {
        // TransactionTemplate 打桩为直通执行（单测不验证事务语义）
        lenient().doAnswer(inv -> {
            inv.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void 单日聚合_先删后插() {
        LocalDate day = LocalDate.of(2026, 7, 8);
        service.rollupDay(day);
        var order = inOrder(rollupDao);
        order.verify(rollupDao).deleteDay(day);
        order.verify(rollupDao).insertQuality(day);
        order.verify(rollupDao).insertHotKeywords(eq(day), anyInt());
        order.verify(rollupDao).insertDocHits(day);
    }

    @Test
    void 回填_单日失败继续后续日期() {
        // lenient：仅 7/2 精确参数命中抛异常，7/1、7/3 走未打桩的默认返回值——
        // 严格打桩模式下会把“同方法不同参数的未打桩调用”误判为疑似写错参数，需放宽
        lenient().doThrow(new RuntimeException("boom")).when(rollupDao).insertQuality(LocalDate.of(2026, 7, 2));
        int ok = service.backfill(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3));
        assertThat(ok).isEqualTo(2);   // 7/1、7/3 成功，7/2 失败跳过
        verify(rollupDao, times(3)).deleteDay(any());
    }

    @Test
    void 回填_区间非法或过长拒绝() {
        assertThatThrownBy(() -> service.backfill(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.backfill(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 回填_区间边界_366天放行_367天拒绝() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        // 闭区间 366 天（between=365）应放行
        assertThat(service.backfill(from, from.plusDays(365))).isEqualTo(366);
        // 闭区间 367 天（between=366）应拒绝
        assertThatThrownBy(() -> service.backfill(from, from.plusDays(366)))
                .isInstanceOf(BusinessException.class);
    }
}

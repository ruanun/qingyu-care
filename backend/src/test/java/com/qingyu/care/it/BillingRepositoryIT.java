package com.qingyu.care.it;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.projection.QuotaUsageRow;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 租户当月用量累计：只统计当月、按租户隔离。@Transactional 回滚 seed。 */
@Transactional
class BillingRepositoryIT extends AbstractPostgresIT {

    private static final Long T1 = 80001L, T2 = 80002L,
            A1 = 80011L, A2 = 80012L, A3 = 80013L, B1 = 80021L;

    @Autowired SysApiUsageRepository usageRepository;

    private void seed(Long tenant, Long app, LocalDate date, long calls, long tokens) {
        usageRepository.save(SysApiUsage.builder()
                .tenantId(tenant).appId(app).usageDate(date)
                .callCount(calls).messageCount(calls).tokenCount(tokens).build());
    }

    @Test
    void 当月累计_只算本月本租户() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        seed(T1, A1, LocalDate.now(), 5, 50);
        seed(T1, A2, LocalDate.now(), 3, 30);           // 同租户另一应用，计入
        seed(T1, A3, monthStart, 2, 20);                // 月初第一天（== monthStart 边界），应计入
        seed(T1, A1, monthStart.minusDays(1), 9, 90);   // 上月，不计入
        seed(T2, B1, LocalDate.now(), 7, 70);           // 他租户，不计入

        QuotaUsageRow row = usageRepository.sumSince(T1, monthStart);
        assertThat(row.getCalls()).isEqualTo(10);   // 5+3+2（含月初边界）
        assertThat(row.getTokens()).isEqualTo(100); // 50+30+20
    }

    @Test
    void 无用量时返回0而非null() {
        QuotaUsageRow row = usageRepository.sumSince(T2, LocalDate.now().withDayOfMonth(1));
        assertThat(row.getCalls()).isEqualTo(0);
        assertThat(row.getTokens()).isEqualTo(0);
    }
}

package com.qingyu.care.it;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.projection.SessionSummaryRow;
import com.qingyu.care.repository.projection.UsageDailyRow;
import com.qingyu.care.repository.projection.UsageRankRow;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 聚合查询 SQL 正确性（无登录上下文，native 手写过滤直接生效）。@Transactional 回滚 seed。 */
@Transactional
class AnalyticsRepositoryIT extends AbstractPostgresIT {

    private static final Long T1 = 70001L, T2 = 70002L, A1 = 70011L, A2 = 70012L, A3 = 70021L;

    @Autowired SysApiUsageRepository usageRepository;
    @Autowired ChatSessionRepository sessionRepository;

    private void seedUsage(Long tenant, Long app, LocalDate date, long calls, long tokens) {
        SysApiUsage u = SysApiUsage.builder()
                .tenantId(tenant).appId(app).usageDate(date)
                .callCount(calls).messageCount(calls).tokenCount(tokens).build();
        usageRepository.save(u);
    }

    private void seedSession(Long tenant, Long app, SessionStatus status, Long agentId, boolean handover) {
        ChatSession s = ChatSession.builder()
                .sessionId("it-" + tenant + "-" + app + "-" + System.nanoTime())
                .userId("u").status(status).agentId(agentId)
                .handoverTime(handover ? LocalDateTime.now() : null).build();
        s.setTenantId(tenant);
        s.setAppId(app);
        sessionRepository.save(s);
    }

    @Test
    void sys用量按天聚合_可按租户与应用过滤() {
        LocalDate today = LocalDate.now();
        // sys_api_usage 有唯一约束 (app_id, usage_date)，不能同 app+date 两行；
        // 用不同应用 A1/A2 分两天来模拟「同租户两行按天聚合」
        seedUsage(T1, A1, today, 3, 30);
        seedUsage(T1, A2, today, 2, 20);   // 同租户不同应用同天 → 聚合到一天两条 → calls 总计 5
        seedUsage(T2, A3, today, 9, 90);

        List<UsageDailyRow> t1 = usageRepository.aggregateDaily(today.minusDays(29), T1, null);
        assertThat(t1).hasSize(1);  // 同一天聚合后只有 1 行
        assertThat(t1.get(0).getCalls()).isEqualTo(5);
        assertThat(t1.get(0).getTokens()).isEqualTo(50);

        List<UsageDailyRow> all = usageRepository.aggregateDaily(today.minusDays(29), null, null);
        assertThat(all.get(0).getCalls()).isEqualTo(14);
    }

    @Test
    void 排行_平台按租户_租户按应用_降序取前N() {
        LocalDate today = LocalDate.now();
        seedUsage(T1, A1, today, 5, 50);
        seedUsage(T1, A2, today, 1, 10);
        seedUsage(T2, A3, today, 8, 80);

        List<UsageRankRow> byTenant = usageRepository.rankByTenant(today.minusDays(29), PageRequest.of(0, 10));
        assertThat(byTenant.get(0).getRefId()).isEqualTo(T2);   // T2=8 > T1=6
        assertThat(byTenant.get(0).getCalls()).isEqualTo(8);

        List<UsageRankRow> byApp = usageRepository.rankByApp(today.minusDays(29), T1, PageRequest.of(0, 10));
        assertThat(byApp.get(0).getRefId()).isEqualTo(A1);      // A1=5 > A2=1
    }

    @Test
    void 会话汇总_native绕过过滤_口径正确() {
        seedSession(T1, A1, SessionStatus.ENDED, null, false);   // AI 解决
        seedSession(T1, A2, SessionStatus.ENDED, 999L, true);    // 人工介入 + 转人工
        seedSession(T1, A1, SessionStatus.AI_PROCESSING, null, false);

        LocalDateTime from = LocalDate.now().minusDays(29).atStartOfDay();
        SessionSummaryRow s = sessionRepository.aggregateSessionSummary(from, T1, null);
        assertThat(s.getTotal()).isEqualTo(3);
        assertThat(s.getEnded()).isEqualTo(2);
        assertThat(s.getAiResolved()).isEqualTo(1);
        assertThat(s.getHandover()).isEqualTo(1);
    }
}

package com.qingyu.care.it;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.StatQualityDaily;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.StatDocHitDailyRepository;
import com.qingyu.care.repository.StatHotKeywordDailyRepository;
import com.qingyu.care.repository.StatQualityDailyRepository;
import com.qingyu.care.service.AnalyticsRollupService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * rollup 聚合正确性 + 多租户隔离 + 幂等重跑。
 * 种子 tenantId+appId 双设（NULL 行不参与统计）；saveAndFlush 保证 native SQL 可见。
 *
 * @author ruan
 */
@Transactional
class AnalyticsRollupIT extends AbstractPostgresIT {

    private static final Long T1 = 91001L, T2 = 91002L, A1 = 91011L, A2 = 91021L;

    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired StatQualityDailyRepository qualityRepository;
    @Autowired StatHotKeywordDailyRepository hotKeywordRepository;
    @Autowired StatDocHitDailyRepository docHitRepository;
    @Autowired AnalyticsRollupService rollupService;

    private ChatSession seedSession(Long tenantId, Long appId, SessionStatus status,
                                    Long agentId, boolean handover) {
        ChatSession s = ChatSession.builder()
                .sessionId("roll-" + tenantId + "-" + System.nanoTime())
                .userId("u").status(status).agentId(agentId)
                .handoverTime(handover ? LocalDateTime.now() : null).build();
        s.setTenantId(tenantId);
        s.setAppId(appId);
        return sessionRepository.saveAndFlush(s);
    }

    private void seedExchange(ChatSession s, String question, double confidence,
                              String hitDocIds, Boolean fallback, Integer feedback) {
        messageRepository.saveAndFlush(ChatMessage.builder()
                .session(s).role(MessageRole.USER).content(question).build());
        messageRepository.saveAndFlush(ChatMessage.builder()
                .session(s).role(MessageRole.ASSISTANT).content("答复")
                .confidenceScore(confidence).hitDocumentIds(hitDocIds)
                .fallback(fallback).feedback(feedback).build());
    }

    @Test
    void 质量文档热词聚合_口径与隔离与幂等() {
        ChatSession t1 = seedSession(T1, A1, SessionStatus.ENDED, null, false);
        seedExchange(t1, "怎么申请退货", 0.9, "[5]", null, 1);
        seedExchange(t1, "发票如何开具", 0.2, null, null, -1);
        seedExchange(t1, "退货运费谁承担", 0.0, null, true, null);
        ChatSession t2 = seedSession(T2, A2, SessionStatus.AI_PROCESSING, null, true);
        seedExchange(t2, "如何退货", 0.8, "[5]", null, null);

        rollupService.rollupDay(LocalDate.now());

        // ===== 质量：T1 口径 =====
        List<StatQualityDaily> t1Rows = qualityRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList();
        assertThat(t1Rows).hasSize(1);
        StatQualityDaily q = t1Rows.get(0);
        assertThat(q.getAiMsgCount()).isEqualTo(3);
        assertThat(q.getQuestionCount()).isEqualTo(3);
        assertThat(q.getHitQuestionCount()).isEqualTo(1);
        assertThat(q.getLowConfidenceCount()).isEqualTo(2);      // 0.2、0.0 < 0.5
        assertThat(q.getFallbackCount()).isEqualTo(1);
        assertThat(q.getFeedbackUp()).isEqualTo(1);
        assertThat(q.getFeedbackDown()).isEqualTo(1);
        assertThat(q.getAvgConfidence()).isCloseTo(0.3667, within(0.01));
        assertThat(q.getSessionCount()).isEqualTo(1);
        assertThat(q.getEndedCount()).isEqualTo(1);
        assertThat(q.getResolvedCount()).isEqualTo(1);
        assertThat(q.getHandoverCount()).isZero();

        // ===== 隔离：T2 独立成行 =====
        assertThat(qualityRepository.findAll().stream()
                .filter(r -> T2.equals(r.getTenantId())).toList()).hasSize(1);

        // ===== 文档命中 =====
        var t1Docs = docHitRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList();
        assertThat(t1Docs).hasSize(1);
        assertThat(t1Docs.get(0).getDocumentId()).isEqualTo(5L);
        assertThat(t1Docs.get(0).getHitCount()).isEqualTo(1);

        // ===== 热词（zhparser 分词，双字以上）=====
        assertThat(hotKeywordRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList()).isNotEmpty();

        // ===== 幂等：重跑不翻倍 =====
        rollupService.rollupDay(LocalDate.now());
        assertThat(qualityRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList()).hasSize(1);
        assertThat(docHitRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList()).hasSize(1);
    }
}

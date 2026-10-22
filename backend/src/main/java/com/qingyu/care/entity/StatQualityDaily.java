package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 对话质量日统计（rollup 预聚合）。不继承 BaseTenantEntity：
 * 定时任务线程无 TenantContext，显式持有租户/应用字段（同 SysApiUsage 模式），查询侧手写 where。
 *
 * @author ruan
 */
@Entity
@Table(name = "stat_quality_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_quality_tenant_app_date",
                columnNames = {"tenant_id", "app_id", "stat_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatQualityDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    /** AI 回复数（role=ASSISTANT 且置信度非空，排除人工客服消息） */
    @Column(name = "ai_msg_count", nullable = false)
    @Builder.Default
    private long aiMsgCount = 0;

    /** 当日平均置信度（无 AI 回复时 null） */
    @Column(name = "avg_confidence")
    private Double avgConfidence;

    /** 低置信回复数（confidence < 0.5 固定口径，保证历史可比） */
    @Column(name = "low_confidence_count", nullable = false)
    @Builder.Default
    private long lowConfidenceCount = 0;

    /** 兜底回复数（fallback = TRUE） */
    @Column(name = "fallback_count", nullable = false)
    @Builder.Default
    private long fallbackCount = 0;

    /** 用户提问数（role=USER） */
    @Column(name = "question_count", nullable = false)
    @Builder.Default
    private long questionCount = 0;

    /** 命中知识的 AI 回复数（hit_document_ids 非空） */
    @Column(name = "hit_question_count", nullable = false)
    @Builder.Default
    private long hitQuestionCount = 0;

    @Column(name = "feedback_up", nullable = false)
    @Builder.Default
    private long feedbackUp = 0;

    @Column(name = "feedback_down", nullable = false)
    @Builder.Default
    private long feedbackDown = 0;

    /** 当日新建会话数 */
    @Column(name = "session_count", nullable = false)
    @Builder.Default
    private long sessionCount = 0;

    /** 转人工会话数（handover_time 非空） */
    @Column(name = "handover_count", nullable = false)
    @Builder.Default
    private long handoverCount = 0;

    /** AI 解决数（ENDED 且无人工 agent_id） */
    @Column(name = "resolved_count", nullable = false)
    @Builder.Default
    private long resolvedCount = 0;

    @Column(name = "ended_count", nullable = false)
    @Builder.Default
    private long endedCount = 0;
}

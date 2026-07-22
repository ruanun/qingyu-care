package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.SessionTag;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话实体
 */
@Entity
@Table(name = "chat_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ChatSession extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 会话唯一标识
     */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    /**
     * 用户标识
     */
    @Column(name = "user_id", length = 64)
    private String userId;

    /**
     * 会话状态
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status = SessionStatus.AI_PROCESSING;

    /**
     * 会话标签（支持多标签）
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "chat_session_tags", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "tag")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<SessionTag> tags = new HashSet<>();

    /**
     * 会话摘要（AI生成）
     */
    @Column(length = 500)
    private String summary;

    /**
     * 人工客服ID
     */
    @Column(name = "agent_id")
    private Long agentId;

    /**
     * 人工接管时间
     */
    @Column(name = "handover_time")
    private LocalDateTime handoverTime;

    /**
     * 会话消息
     */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @JsonIgnore
    private List<ChatMessage> messages = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 会话结束时间
     */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /** 参与的 A/B 实验 ID（null = 未参与实验） */
    @Column(name = "experiment_id")
    private Long experimentId;

    /** A/B 变体名（报表分组键） */
    @Column(name = "variant_name", length = 50)
    private String variantName;

    /** 变体绑定的策略 ID（对话取策略免 join 实验定义） */
    @Column(name = "variant_strategy_id")
    private Long variantStrategyId;
}

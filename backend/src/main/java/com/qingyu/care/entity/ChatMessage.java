package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qingyu.care.enums.MessageRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 */
@Entity
@Table(name = "chat_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属会话
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @JsonIgnore
    private ChatSession session;

    /**
     * 消息角色
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    /**
     * 消息内容
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 命中的知识片段IDs（JSON数组格式）
     */
    @Column(name = "hit_chunk_ids", length = 500)
    private String hitChunkIds;

    /**
     * 置信度分数
     */
    @Column(name = "confidence_score")
    private Double confidenceScore;

    /**
     * Token 使用量
     */
    @Column(name = "token_usage")
    private Integer tokenUsage;

    /**
     * 命中的知识文档 IDs（JSON 数组，如 [3,12]；null = 未命中/未检索。覆盖率口径：非空即命中）
     */
    @Column(name = "hit_document_ids", length = 1000)
    private String hitDocumentIds;

    /**
     * 用户反馈：1=赞 / -1=踩 / null=未评价（重复提交覆盖）
     */
    @Column(name = "feedback")
    private Integer feedback;

    /**
     * 兜底回复标记（知识未命中且策略不允许自由发挥时返回 fallbackMessage 的 AI 消息为 TRUE，其余为 null）
     */
    @Column(name = "fallback")
    private Boolean fallback;

    /**
     * 是否已读
     */
    @Column(name = "is_read")
    private Boolean isRead = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

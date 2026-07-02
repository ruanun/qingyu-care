package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 问答策略实体
 * 用于动态控制 RAG 参数和模型行为
 */
@Entity
@Table(name = "chat_strategy")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStrategy extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 策略名称
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 适用场景
     * default: 默认场景
     * complaint: 投诉场景
     * faq: 基础问答
     */
    @Column(nullable = false, length = 50)
    private String scenario;

    /**
     * 检索文档数量 (TopK)
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer topK = 5;

    /**
     * 相似度阈值
     */
    @Column(nullable = false)
    @Builder.Default
    private Double similarityThreshold = 0.7;

    /**
     * 是否允许在无知识库命中时由 AI 自由发挥
     * true: AI 依据自身知识回答
     * false: 返回兜底回复
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean allowNoKnowledge = true;

    /**
     * 无知识命中时的兜底回复
     */
    @Column(length = 500)
    @Builder.Default
    private String fallbackMessage = "抱歉，由于未能在知识库中找到相关信息，我暂时无法回答该问题。您可以尝试换种问法，或者咨询人工客服。";

    /**
     * 绑定的 Prompt 模板 ID（可选，若不指定则使用场景默认 Prompt）
     */
    private Long promptTemplateId;

    /**
     * 是否启用
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 是否为该场景的默认策略
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

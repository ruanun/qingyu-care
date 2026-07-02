package com.qingyu.care.entity;

import com.qingyu.care.enums.HandoverRuleType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 转人工规则实体
 */
@Entity
@Table(name = "handover_rules")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandoverRule extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 规则类型
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HandoverRuleType ruleType;

    /**
     * 规则名称
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 规则描述
     */
    @Column(length = 500)
    private String description;

    /**
     * 是否启用
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * JSON 配置
     * KEYWORD: {"keywords": ["转人工", "找客服"]}
     * LOW_CONFIDENCE: {"threshold": 0.3}
     * NO_HIT_STREAK: {"count": 3}
     */
    @Column(columnDefinition = "TEXT")
    private String config;

    /**
     * 优先级（数字越小优先级越高）
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

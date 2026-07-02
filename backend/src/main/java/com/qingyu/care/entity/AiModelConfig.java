package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 模型配置实体
 */
@Entity
@Table(name = "ai_model_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModelConfig extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 模型名称（用于显示）
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 模型提供商
     * openai, dashscope (阿里通义), zhipu (智谱), moonshot, deepseek 等
     */
    @Column(nullable = false, length = 50)
    private String provider;

    /**
     * 模型 ID
     * 如: gpt-4o-mini, qwen-max, glm-4 等
     */
    @Column(nullable = false, length = 100)
    private String modelId;

    /**
     * API 端点
     */
    @Column(length = 500)
    private String endpoint;

    /**
     * API Key（生产环境应加密存储）
     */
    @Column(length = 500)
    private String apiKey;

    /**
     * 适用场景
     * default: 默认场景
     * complaint: 投诉场景
     * faq: 简单问答场景
     */
    @Column(nullable = false, length = 50)
    @Builder.Default
    private String scenario = "default";

    /**
     * 优先级（数字越小优先级越高）
     * 用于同场景多模型时的降级
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 100;

    /**
     * 是否启用
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 备注
     */
    @Column(length = 500)
    private String remark;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

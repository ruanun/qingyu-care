package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.enums.OwnerScope;
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
// 无参构造显式标 @JsonCreator：Jackson 3 会把 @AllArgsConstructor 当隐式创建器，
// 请求体缺省的属性会以 null 传入并覆盖 @Builder.Default 初值（详见 ChatRequest 注释）。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
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
     *
     * <p>{@code WRITE_ONLY}：只读入、不写出（同 {@code User.password}）。实体直接当响应体，此前明文随列表发给
     * 全体登录用户——坐席也能读到（2026-09-25 安全扫描 H2）。前端编辑弹窗本就不回显凭据，不依赖它。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
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

    /**
     * 归属范围：TENANT=租户/应用自有；PLATFORM=平台共享模型
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OwnerScope scope = OwnerScope.TENANT;

    /**
     * 模型类型：对话 / 向量。创建后不可改（不在 {@code AiModelConfigUpdateRequest} 里）。
     *
     * <p>列可空：列是后加的，{@code ddl-auto: update} 给已有数据的表加 NOT NULL 列会直接失败。
     * NULL 按 {@link ModelType#CHAT} 理解，见 {@link ModelType#orDefault}。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private ModelType modelType = ModelType.CHAT;

    /**
     * 向量模型的推荐相似度阈值（仅 EMBEDDING 有意义）。
     *
     * <p>相似度的刻度随 embedding 模型整体平移（qwen3.7-text-embedding 下真实命中 0.57~0.73、
     * 噪声 0.25~0.30），所以阈值跟着模型走：问答策略默认「跟随向量模型」，换模型后不必再去改策略。
     */
    private Double similarityThreshold;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

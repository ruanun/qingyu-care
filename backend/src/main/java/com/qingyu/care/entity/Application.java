package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 应用实体
 */
@Entity
@Table(name = "sys_application")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@org.hibernate.annotations.Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属租户
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /**
     * 应用名称
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 应用标识 (用于 API 调用)
     */
    @Column(nullable = false, unique = true, length = 64)
    private String appKey;

    /**
     * 应用密钥
     */
    @Column(nullable = false, length = 128)
    private String appSecret;

    /**
     * 应用描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 头像 URL
     */
    @Column(length = 255)
    private String avatar;

    /**
     * 欢迎语
     */
    @Column(columnDefinition = "TEXT")
    private String welcomeMessage;

    /**
     * 主题色
     */
    @Column(length = 20)
    @Builder.Default
    private String themeColor = "#6366f1";

    /**
     * 是否启用
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 开放 API 每分钟请求上限（为空则用平台默认）
     */
    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

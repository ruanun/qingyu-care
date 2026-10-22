package com.qingyu.care.entity;

import com.qingyu.care.enums.WebhookDeliveryStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Webhook 投递记录（按事件一行）。参照 SysApiUsage 不继承 BaseTenantEntity——
 * 投递发生在异步/定时线程，无租户过滤上下文，显式存 tenantId/appId 更稳。
 *
 * @author ruan
 */
@Entity
@Table(name = "sys_webhook_delivery")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    /** 事件类型，如 session.handover_requested。 */
    @Column(nullable = false, length = 64)
    private String event;

    /** 投递目标 URL 快照。 */
    @Column(name = "target_url", columnDefinition = "TEXT")
    private String targetUrl;

    /** 事件信封 JSON。 */
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

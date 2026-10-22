package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 开放 API 用量（按 应用+日期 聚合）。不继承 BaseTenantEntity。
 *
 * @author ruan
 */
@Entity
@Table(name = "sys_api_usage",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_api_usage_app_date", columnNames = {"app_id", "usage_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysApiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    /** 调用次数（每次 completions 请求 +1） */
    @Column(name = "call_count", nullable = false)
    @Builder.Default
    private long callCount = 0;

    /** 消息数（通常与调用次数相同） */
    @Column(name = "message_count", nullable = false)
    @Builder.Default
    private long messageCount = 0;

    /** Token 用量（来自 ChatResponse.tokenUsage） */
    @Column(name = "token_count", nullable = false)
    @Builder.Default
    private long tokenCount = 0;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

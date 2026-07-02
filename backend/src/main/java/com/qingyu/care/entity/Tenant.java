package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 租户实体
 */
@Entity
@Table(name = "sys_tenant")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 租户名称
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 租户编码（唯一标识）
     */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /**
     * 联系人姓名
     */
    @Column(length = 50)
    private String contactName;

    /**
     * 联系电话
     */
    @Column(length = 20)
    private String contactPhone;

    /**
     * 联系邮箱
     */
    @Column(length = 100)
    private String contactEmail;

    /**
     * 租户状态
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TenantStatus status = TenantStatus.ACTIVE;

    /**
     * 套餐类型
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TenantPlan plan = TenantPlan.FREE;

    /**
     * 过期时间
     */
    private LocalDateTime expireTime;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

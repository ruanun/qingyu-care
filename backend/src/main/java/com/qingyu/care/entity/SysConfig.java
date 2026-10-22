package com.qingyu.care.entity;

import com.qingyu.care.enums.OwnerScope;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 系统配置（键值）。scope 区分平台级(tenant_id 为 null)与租户级。
 * 不继承 BaseTenantEntity：租户隔离由 Service 显式按 scope+tenantId 控制。
 */
@Entity
@Table(name = "sys_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sys_config_scope_tenant_key",
                columnNames = {"scope", "tenant_id", "config_key"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerScope scope;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    @Column(name = "config_value", length = 2000)
    private String configValue;

    @Column(length = 500)
    private String description;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

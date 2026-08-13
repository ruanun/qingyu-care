package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qingyu.care.config.TenantEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * 多租户基础实体
 * 所有需要租户隔离的实体都应继承此类
 */
@MappedSuperclass
@Data
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@FilterDef(name = "appFilter", parameters = @ParamDef(name = "appId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Filter(name = "appFilter", condition = "app_id = :appId")
@EntityListeners(TenantEntityListener.class)
public abstract class BaseTenantEntity {

    /**
     * 租户ID
     *
     * <p>⚠️ {@code READ_ONLY} 表示「只写出、不读入」：响应里照常返回，但<b>请求体给了也绑不上</b>。
     * 少了它，租户管理员 POST {@code {"tenantId": <他人租户id>}} 就能把行建进别人租户——
     * {@code TenantEntityListener.prePersist} 只在为 null 时才回填，非 null 时不覆盖。
     * 归属只能由服务端从 {@code TenantContext} 决定，绝不能由客户端指定。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "tenant_id")
    private Long tenantId;

    /**
     * 应用ID
     *
     * <p>⚠️ 同 {@link #tenantId}：只写出、不读入。应用归属由 {@code X-App-Id} 请求头决定。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "app_id")
    private Long appId;
}

package com.qingyu.care.entity;

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
     */
    @Column(name = "tenant_id")
    private Long tenantId;

    /**
     * 应用ID
     */
    @Column(name = "app_id")
    private Long appId;
}

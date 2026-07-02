package com.qingyu.care.config;

import com.qingyu.care.entity.BaseTenantEntity;
import jakarta.persistence.PrePersist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 租户实体监听器
 * 在实体保存前自动填充 tenantId 和 appId
 */
@Component
@Slf4j
public class TenantEntityListener {

    /**
     * 在实体持久化（INSERT）前自动填充租户信息
     */
    @PrePersist
    public void prePersist(Object entity) {
        if (entity instanceof BaseTenantEntity base) {
            // 如果实体没有设置 tenantId，从上下文获取
            if (base.getTenantId() == null) {
                Long tenantId = TenantContext.getTenantId();
                if (tenantId != null) {
                    base.setTenantId(tenantId);
                    log.debug("Auto-filled tenantId={} for entity {}", tenantId, entity.getClass().getSimpleName());
                }
            }
            
            // 如果实体没有设置 appId，从上下文获取
            if (base.getAppId() == null) {
                Long appId = TenantContext.getAppId();
                if (appId != null) {
                    base.setAppId(appId);
                    log.debug("Auto-filled appId={} for entity {}", appId, entity.getClass().getSimpleName());
                }
            }
        }
    }
}

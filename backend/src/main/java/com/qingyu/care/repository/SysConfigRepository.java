package com.qingyu.care.repository;

import com.qingyu.care.entity.SysConfig;
import com.qingyu.care.enums.OwnerScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {

    // 平台级（tenant_id 为 null）
    List<SysConfig> findByScopeAndTenantIdIsNull(OwnerScope scope);
    Optional<SysConfig> findByScopeAndConfigKeyAndTenantIdIsNull(OwnerScope scope, String configKey);

    // 租户级
    List<SysConfig> findByScopeAndTenantId(OwnerScope scope, Long tenantId);
    Optional<SysConfig> findByScopeAndTenantIdAndConfigKey(OwnerScope scope, Long tenantId, String configKey);

    /** 某配置键在指定范围内的全部行（跨租户，供定时任务收集各租户覆盖值） */
    List<SysConfig> findByScopeAndConfigKey(OwnerScope scope, String configKey);
}

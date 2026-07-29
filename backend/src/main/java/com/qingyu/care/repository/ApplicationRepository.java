package com.qingyu.care.repository;

import com.qingyu.care.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<Application> findByAppKey(String appKey);

    boolean existsByAppKey(String appKey);

    List<Application> findByTenantIdAndEnabledTrue(Long tenantId);

    /**
     * 反查应用的归属租户 ID（native 直取 FK，避开 lazy Tenant 关联）。
     * 供平台管理员(无租户上下文)按当前 X-App-Id 解析配置作用域。
     */
    @Query(value = "SELECT tenant_id FROM sys_application WHERE id = :appId", nativeQuery = true)
    Optional<Long> findTenantIdById(@Param("appId") Long appId);
}

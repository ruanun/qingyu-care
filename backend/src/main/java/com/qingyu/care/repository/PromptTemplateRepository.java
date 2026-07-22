package com.qingyu.care.repository;

import com.qingyu.care.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {
    
    /**
     * 获取当前激活的模板
     */
    Optional<PromptTemplate> findByIsActiveTrue();

    /**
     * 平台级默认激活模板（tenant_id 为空）。
     * 用原生查询绕过 Hibernate 租户过滤，供某租户/应用未配置自己的激活模板时回退。
     */
    @Query(value = "SELECT * FROM prompt_templates WHERE is_active = true AND tenant_id IS NULL ORDER BY id LIMIT 1",
            nativeQuery = true)
    Optional<PromptTemplate> findPlatformDefaultActive();
    
    /**
     * 获取所有版本，按版本号降序
     */
    List<PromptTemplate> findAllByOrderByVersionDesc();
    
    /**
     * 获取最新版本号
     */
    @Query("SELECT COALESCE(MAX(p.version), 0) FROM PromptTemplate p")
    Integer findMaxVersion();
    
    /**
     * 将指定租户范围内的模板全部设为非激活（appId 为空时覆盖该租户全部应用）。
     * 注意：Hibernate 的 @Filter 不作用于 bulk UPDATE 语句，租户/应用范围必须显式写进谓词，
     * 否则任一租户激活模板会把全库（含平台默认与其他租户）的激活位清掉。
     */
    @Modifying
    @Query("UPDATE PromptTemplate p SET p.isActive = false "
            + "WHERE p.tenantId = :tenantId AND (:appId IS NULL OR p.appId = :appId)")
    void deactivateAllForTenant(@Param("tenantId") Long tenantId, @Param("appId") Long appId);

    /**
     * 将平台级模板（tenant_id IS NULL）全部设为非激活。范围化原因同上。
     */
    @Modifying
    @Query("UPDATE PromptTemplate p SET p.isActive = false WHERE p.tenantId IS NULL")
    void deactivateAllPlatform();
}

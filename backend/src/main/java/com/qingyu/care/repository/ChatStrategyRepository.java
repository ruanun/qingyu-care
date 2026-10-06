package com.qingyu.care.repository;

import com.qingyu.care.entity.ChatStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatStrategyRepository extends JpaRepository<ChatStrategy, Long> {

    /**
     * 获取指定场景的默认启用策略（走 Hibernate 租户过滤器，返回租户自有策略）
     */
    Optional<ChatStrategy> findByScenarioAndEnabledTrueAndIsDefaultTrue(String scenario);

    /**
     * 查询平台默认策略（tenant_id IS NULL）——使用原生 SQL 绕过 Hibernate 租户过滤器，
     * 确保租户上下文中也能命中 @PostConstruct 播种的平台兜底行。
     */
    @Query(value = "SELECT * FROM chat_strategy " +
                   "WHERE scenario = :scenario AND enabled = true AND is_default = true " +
                   "AND tenant_id IS NULL ORDER BY id LIMIT 1",
           nativeQuery = true)
    Optional<ChatStrategy> findPlatformDefaultByScenario(@Param("scenario") String scenario);

    /**
     * 显式按 (tenantId, appId) 收窄取场景默认启用策略——不依赖 Hibernate @Filter，
     * 使平台管理员(过滤器绕过)也能确定性取到目标租户的策略。返回 List + ORDER BY id 杜绝 NonUnique。
     */
    @Query("SELECT s FROM ChatStrategy s WHERE s.scenario = :scenario AND s.enabled = true AND s.isDefault = true "
         + "AND s.tenantId = :tenantId AND (:appId IS NULL OR s.appId = :appId) ORDER BY s.id")
    List<ChatStrategy> findActiveDefaultScoped(@Param("scenario") String scenario,
                                               @Param("tenantId") Long tenantId,
                                               @Param("appId") Long appId);

    /**
     * 获取指定租户自有的所有策略（管理面用，不受启用/默认过滤）
     */
    List<ChatStrategy> findByTenantId(Long tenantId);

    /**
     * 查询全部平台默认策略（tenant_id IS NULL）——原生 SQL 绕过 Hibernate 租户过滤器，
     * 管理面 list 用，确保平台默认在收窄后仍可见。
     */
    @Query(value = "SELECT * FROM chat_strategy WHERE tenant_id IS NULL ORDER BY scenario, id",
           nativeQuery = true)
    List<ChatStrategy> findAllPlatformDefaults();

    /**
     * 获取指定场景的所有策略
     */
    List<ChatStrategy> findByScenarioOrderByIsDefaultDescCreatedAtDesc(String scenario);

    /**
     * 获取所有启用的策略
     */
    List<ChatStrategy> findByEnabledTrue();

    /**
     * 取消该场景下所有策略的默认状态（用于切换默认策略）
     */
    void deleteByScenario(String scenario); // 暂不直接使用，通过 Service 处理

    /**
     * 对某应用生效、且<b>没有</b>跟随向量模型阈值的策略（应用自有 + 平台默认）。切换向量模型时点名提醒：
     * 它们的阈值是按旧模型的刻度定的，换完模型不改就会失准。
     */
    @Query(value = "SELECT * FROM chat_strategy WHERE enabled = true AND follow_model_threshold = false "
            + "AND (app_id = :appId OR tenant_id IS NULL) ORDER BY id", nativeQuery = true)
    List<ChatStrategy> findManualThresholdStrategies(@Param("appId") Long appId);
}

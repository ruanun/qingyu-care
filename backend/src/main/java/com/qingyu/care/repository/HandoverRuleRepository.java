package com.qingyu.care.repository;

import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HandoverRuleRepository extends JpaRepository<HandoverRule, Long> {

    /**
     * 获取所有启用的规则，按优先级排序
     */
    List<HandoverRule> findByEnabledTrueOrderByPriorityAsc();

    /**
     * 按规则类型查找
     */
    Optional<HandoverRule> findByRuleType(HandoverRuleType ruleType);

    /**
     * 获取所有规则，按优先级排序
     */
    List<HandoverRule> findAllByOrderByPriorityAsc();

    /**
     * 获取指定租户自有的所有规则，按优先级排序（管理面用）
     */
    List<HandoverRule> findByTenantIdOrderByPriorityAsc(Long tenantId);

    /**
     * 查询全部平台默认规则（tenant_id IS NULL）——原生 SQL 绕过 Hibernate 租户过滤器，
     * 管理面 list 用，确保平台默认在收窄后仍可见（顺带修复租户此前看不到平台默认规则的 bug）。
     */
    @Query(value = "SELECT * FROM handover_rules WHERE tenant_id IS NULL ORDER BY priority ASC",
            nativeQuery = true)
    List<HandoverRule> findAllPlatformDefaults();

    /**
     * 按类型取平台默认规则（tenant_id IS NULL，绕过 Hibernate @Filter）；仿 findAllPlatformDefaults。
     * rule_type 列存枚举 name 字符串，故传 HandoverRuleType.name()。
     */
    @Query(value = "SELECT * FROM handover_rules WHERE rule_type = :ruleType AND tenant_id IS NULL LIMIT 1",
            nativeQuery = true)
    Optional<HandoverRule> findPlatformDefaultByRuleType(@Param("ruleType") String ruleType);

    /**
     * 显式按 (tenantId, appId) 收窄取指定类型规则——不依赖 Hibernate @Filter，
     * 使平台管理员(过滤器绕过)也能确定性取到目标租户的规则。List + ORDER BY id 杜绝 NonUnique。
     */
    @Query("SELECT r FROM HandoverRule r WHERE r.ruleType = :ruleType "
         + "AND r.tenantId = :tenantId AND (:appId IS NULL OR r.appId = :appId) ORDER BY r.id")
    List<HandoverRule> findByRuleTypeScoped(@Param("ruleType") HandoverRuleType ruleType,
                                            @Param("tenantId") Long tenantId,
                                            @Param("appId") Long appId);
}

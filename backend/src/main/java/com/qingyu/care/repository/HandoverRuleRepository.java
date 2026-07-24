package com.qingyu.care.repository;

import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}

package com.qingyu.care.repository;

import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import org.springframework.data.jpa.repository.JpaRepository;
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
}

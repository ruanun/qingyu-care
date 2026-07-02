package com.qingyu.care.repository;

import com.qingyu.care.entity.ChatStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatStrategyRepository extends JpaRepository<ChatStrategy, Long> {
    
    /**
     * 获取指定场景的默认启用策略
     */
    Optional<ChatStrategy> findByScenarioAndEnabledTrueAndIsDefaultTrue(String scenario);
    
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
}

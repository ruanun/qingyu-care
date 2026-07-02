package com.qingyu.care.repository;

import com.qingyu.care.entity.AiModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, Long> {
    
    /**
     * 获取指定场景的启用模型，按优先级排序
     */
    List<AiModelConfig> findByScenarioAndEnabledTrueOrderByPriorityAsc(String scenario);
    
    /**
     * 获取所有启用的模型，按优先级排序
     */
    List<AiModelConfig> findByEnabledTrueOrderByPriorityAsc();
    
    /**
     * 获取所有模型，按场景和优先级排序
     */
    List<AiModelConfig> findAllByOrderByScenarioAscPriorityAsc();
}

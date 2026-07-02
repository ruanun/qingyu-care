package com.qingyu.care.repository;

import com.qingyu.care.entity.AiModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 查询启用的平台共享模型（scope=PLATFORM），按优先级排序。
     * 使用原生 SQL，绕过 Hibernate 的租户过滤器（@Filter 不作用于原生查询），
     * 从而让租户用户也能命中平台共享模型。
     */
    @Query(
        value = "SELECT * FROM ai_model_config " +
                "WHERE scope = 'PLATFORM' AND enabled = true AND scenario = :scenario " +
                "ORDER BY priority ASC",
        nativeQuery = true)
    List<AiModelConfig> findSharedModelsByScenario(@Param("scenario") String scenario);

    /** 查询所有平台共享模型（超管管理用） */
    @Query(
        value = "SELECT * FROM ai_model_config WHERE scope = 'PLATFORM' ORDER BY scenario ASC, priority ASC",
        nativeQuery = true)
    List<AiModelConfig> findAllSharedModels();
}

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
     * 获取指定应用自有的模型配置，按场景和优先级排序（管理面用，不带 enabled 过滤，
     * 须见禁用模型才能重新启用）
     */
    List<AiModelConfig> findByAppIdOrderByScenarioAscPriorityAsc(Long appId);

    /**
     * 查询启用的平台共享模型（scope=PLATFORM），按优先级排序。
     * 使用原生 SQL，绕过 Hibernate 的租户过滤器（@Filter 不作用于原生查询），
     * 从而让租户用户也能命中平台共享模型。
     *
     * <p>⚠️ <b>{@code tenant_id IS NULL} 不能去掉。</b>同 AiToolRepository，{@code scope}
     * 是客户端可控字段——租户在 POST /v1/ai-models 的请求体里就能自己填
     * {@code "scope":"PLATFORM"}，只判 {@code scope} 等于让租户自己决定要不要被当成平台
     * 共享模型解析（既有漏洞：租户自封后能把所有租户的对话流量导向自己的 endpoint）。
     * {@code tenant_id IS NULL} 才是「平台所有」的真凭据，由服务层强制、请求体改不动。
     */
    @Query(
        value = "SELECT * FROM ai_model_config " +
                "WHERE scope = 'PLATFORM' AND tenant_id IS NULL AND enabled = true AND scenario = :scenario " +
                "ORDER BY priority ASC",
        nativeQuery = true)
    List<AiModelConfig> findSharedModelsByScenario(@Param("scenario") String scenario);

    /**
     * 查询所有平台共享模型（超管管理用）。
     *
     * <p>⚠️ <b>{@code tenant_id IS NULL} 不能去掉。</b>理由同上：{@code scope} 客户端可控，
     * 只判它会把租户自封的行也当成平台共享模型列出来。
     */
    @Query(
        value = "SELECT * FROM ai_model_config WHERE scope = 'PLATFORM' AND tenant_id IS NULL "
              + "ORDER BY scenario ASC, priority ASC",
        nativeQuery = true)
    List<AiModelConfig> findAllSharedModels();
}

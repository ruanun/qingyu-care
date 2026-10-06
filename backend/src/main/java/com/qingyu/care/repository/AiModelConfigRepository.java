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
     * 获取指定场景的启用<b>对话</b>模型，按优先级排序。
     *
     * <p>⚠️ 必须排除向量模型：向量模型行的 scenario 列同样是 default，不排除就会被当成对话模型
     * 选中，对话请求打到 embeddings 端点上（只报「所有 AI 模型调用失败」，看不出原因）。
     * 存量行 model_type 为 NULL，按 CHAT 算。
     */
    @Query("SELECT m FROM AiModelConfig m WHERE m.scenario = :scenario AND m.enabled = true "
            + "AND (m.modelType IS NULL OR m.modelType = com.qingyu.care.enums.ModelType.CHAT) "
            + "ORDER BY m.priority ASC")
    List<AiModelConfig> findByScenarioAndEnabledTrueOrderByPriorityAsc(@Param("scenario") String scenario);

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
                // 只取对话模型（理由同 findByScenarioAndEnabledTrueOrderByPriorityAsc）
                "AND (model_type IS NULL OR model_type = 'CHAT') " +
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

    // ===== 向量模型 =====

    /**
     * 启用的平台共享向量模型，按优先级排序——第一条即「平台默认向量模型」，新应用钉它。
     * 原生 SQL 绕过租户过滤器；{@code tenant_id IS NULL} 不能去掉，理由同 {@link #findSharedModelsByScenario}。
     */
    @Query(value = "SELECT * FROM ai_model_config WHERE scope = 'PLATFORM' AND tenant_id IS NULL "
            + "AND enabled = true AND model_type = 'EMBEDDING' ORDER BY priority ASC, id ASC",
            nativeQuery = true)
    List<AiModelConfig> findSharedEmbeddingModels();

    /** 某应用自有的启用向量模型（请求线程用，appId 显式收窄，不依赖过滤器）。 */
    @Query("SELECT m FROM AiModelConfig m WHERE m.appId = :appId AND m.enabled = true "
            + "AND m.modelType = com.qingyu.care.enums.ModelType.EMBEDDING ORDER BY m.priority ASC, m.id ASC")
    List<AiModelConfig> findAppEmbeddingModels(@Param("appId") Long appId);

    /**
     * 按 id 读，<b>绕过租户过滤器</b>。只给「已经由应用钉住的向量模型」这种 id 来源可信的场景用：
     * 应用钉的可能是平台共享行（tenant_id IS NULL，过滤器下租户看不到），而摄取跑在没有上下文的
     * {@code @Async} 线程里。别拿它处理客户端传来的 id——那必须走 {@code ModelRouterService.getById} 的归属校验。
     */
    @Query(value = "SELECT * FROM ai_model_config WHERE id = :id", nativeQuery = true)
    java.util.Optional<AiModelConfig> findByIdAcrossTenants(@Param("id") Long id);

    /** 平台是否已有向量模型行（不论启停），播种用。 */
    @Query(value = "SELECT COUNT(*) FROM ai_model_config WHERE model_type = 'EMBEDDING' AND tenant_id IS NULL",
            nativeQuery = true)
    long countPlatformEmbeddingModels();

    // ===== 供应商连接 =====
    // 连接是租户级、模型是应用级：下面这些都按连接 id 跨应用操作，原生 SQL 绕开 appFilter。
    // 调用方（ModelProviderService）已先校验过连接归属，连接 id 来源可信。

    /**
     * 把连接的端点 / Key / 厂商同步到其下全部模型行，并<b>刷新 updated_at</b>——
     * EmbeddingModelRegistry 按 (id, updatedAt) 缓存客户端，时间戳不动的话改了 Key 也继续用旧客户端。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE ai_model_config SET provider = :providerKey, endpoint = :endpoint, api_key = :apiKey, "
            + "updated_at = now() WHERE provider_id = :providerId", nativeQuery = true)
    int syncFromProvider(@Param("providerId") Long providerId, @Param("providerKey") String providerKey,
                         @Param("endpoint") String endpoint, @Param("apiKey") String apiKey);

    /** 连接下的全部模型（跨应用）。 */
    @Query(value = "SELECT * FROM ai_model_config WHERE provider_id = :providerId ORDER BY id", nativeQuery = true)
    List<AiModelConfig> findByProviderIdAcrossApps(@Param("providerId") Long providerId);

    /** 连接下某个应用的模型（租户侧界面只展示当前应用的）。 */
    @Query(value = "SELECT * FROM ai_model_config WHERE provider_id = :providerId AND app_id = :appId "
            + "ORDER BY model_type, scenario, priority, id", nativeQuery = true)
    List<AiModelConfig> findByProviderIdAndAppId(@Param("providerId") Long providerId, @Param("appId") Long appId);

    /** 还没挂到连接上的模型（存量行 / 启动播种），供启动回填归组。 */
    @Query(value = "SELECT * FROM ai_model_config WHERE provider_id IS NULL ORDER BY id", nativeQuery = true)
    List<AiModelConfig> findWithoutProvider();

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE ai_model_config SET provider_id = :providerId WHERE id IN (:ids) AND provider_id IS NULL",
            nativeQuery = true)
    int attachToProvider(@Param("ids") java.util.Collection<Long> ids, @Param("providerId") Long providerId);
}

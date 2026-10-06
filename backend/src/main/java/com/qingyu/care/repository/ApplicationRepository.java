package com.qingyu.care.repository;

import com.qingyu.care.entity.Application;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    /**
     * 开放 API 鉴权按 appKey 取应用，并一起取出租户：鉴权在拦截器阶段执行、没有 OSIV 的 EntityManager，
     * 懒加载的 {@code tenant} 一碰非 id 字段（状态 / 到期时间）就是 LazyInitializationException。
     */
    @EntityGraph(attributePaths = "tenant")
    Optional<Application> findByAppKey(String appKey);

    boolean existsByAppKey(String appKey);

    List<Application> findByTenantIdAndEnabledTrue(Long tenantId);

    /**
     * 反查应用的归属租户 ID（native 直取 FK，避开 lazy Tenant 关联）。
     * 供平台管理员(无租户上下文)按当前 X-App-Id 解析配置作用域。
     */
    @Query(value = "SELECT tenant_id FROM sys_application WHERE id = :appId", nativeQuery = true)
    Optional<Long> findTenantIdById(@Param("appId") Long appId);

    // ===== 向量模型指针 =====
    // 两列在实体上是 insertable/updatable=false，只经下面这些原生 SQL 读写（理由见 Application 字段注释）。
    // 原生 SQL 同时绕开了租户过滤器：调用方多是没有 TenantContext 的 @Async 摄取 / 迁移线程，appId 来自数据行自身。

    /** 应用当前的向量模型指针（active / pending）。应用不存在时为空。 */
    @Query(value = "SELECT embedding_model_id AS active, pending_embedding_model_id AS pending "
            + "FROM sys_application WHERE id = :appId", nativeQuery = true)
    Optional<EmbeddingPointers> findEmbeddingPointers(@Param("appId") Long appId);

    interface EmbeddingPointers {
        Long getActive();

        Long getPending();
    }

    /** 首次使用时钉住平台默认向量模型；已钉过则不动（CAS，并发首用只有一个生效）。 */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE sys_application SET embedding_model_id = :modelId "
            + "WHERE id = :appId AND embedding_model_id IS NULL", nativeQuery = true)
    int pinEmbeddingModelIfAbsent(@Param("appId") Long appId, @Param("modelId") Long modelId);

    /** 开始影子重建：只在没有进行中的切换时置 pending（CAS）。 */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE sys_application SET pending_embedding_model_id = :modelId "
            + "WHERE id = :appId AND pending_embedding_model_id IS NULL", nativeQuery = true)
    int startPendingEmbeddingModel(@Param("appId") Long appId, @Param("modelId") Long modelId);

    /** 重建完成：pending 翻成 active（CAS：pending 必须仍是预期值，被取消过就不翻）。 */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE sys_application SET embedding_model_id = pending_embedding_model_id, "
            + "pending_embedding_model_id = NULL WHERE id = :appId AND pending_embedding_model_id = :modelId",
            nativeQuery = true)
    int promotePendingEmbeddingModel(@Param("appId") Long appId, @Param("modelId") Long modelId);

    /** 取消切换：清空 pending（CAS 同上）。 */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE sys_application SET pending_embedding_model_id = NULL "
            + "WHERE id = :appId AND pending_embedding_model_id = :modelId", nativeQuery = true)
    int clearPendingEmbeddingModel(@Param("appId") Long appId, @Param("modelId") Long modelId);

    /** 直接改 active：只给「目标与当前同一个向量空间（同标签）」的切换用，不需要重建。 */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE sys_application SET embedding_model_id = :modelId "
            + "WHERE id = :appId AND pending_embedding_model_id IS NULL", nativeQuery = true)
    int setActiveEmbeddingModel(@Param("appId") Long appId, @Param("modelId") Long modelId);

    /** 有多少应用正在用（active 或 pending）这个向量模型——被引用的模型不能删、不能停用、不能改 modelId。 */
    @Query(value = "SELECT COUNT(*) FROM sys_application "
            + "WHERE embedding_model_id = :modelId OR pending_embedding_model_id = :modelId", nativeQuery = true)
    long countEmbeddingModelReferences(@Param("modelId") Long modelId);

    /** 还没钉向量模型的应用（启动回填用）。 */
    @Query(value = "SELECT id FROM sys_application WHERE embedding_model_id IS NULL", nativeQuery = true)
    List<Long> findIdsWithoutEmbeddingModel();
}

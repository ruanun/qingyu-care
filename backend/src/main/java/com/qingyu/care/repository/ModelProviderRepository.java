package com.qingyu.care.repository;

import com.qingyu.care.entity.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 供应商连接。实体不受租户过滤器管理（见 ModelProvider 类注释），所有查询都显式带归属条件。
 *
 * @author ruan
 */
@Repository
public interface ModelProviderRepository extends JpaRepository<ModelProvider, Long> {

    List<ModelProvider> findByTenantIdOrderByIdAsc(Long tenantId);

    /** 平台连接：{@code tenant_id IS NULL} 才是平台所有的真凭据（scope 曾是客户端可控字段） */
    @Query(value = "SELECT * FROM model_provider WHERE tenant_id IS NULL AND scope = 'PLATFORM' ORDER BY id",
            nativeQuery = true)
    List<ModelProvider> findPlatformProviders();
}

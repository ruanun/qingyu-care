package com.qingyu.care.repository;

import com.qingyu.care.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    
    List<KnowledgeBase> findByTenantIdAndEnabledTrue(Long tenantId);

    List<KnowledgeBase> findByEnabledTrue();

    /** 显式按 appId 过滤（不只依赖 TenantDataAspect 隐式过滤——appId 隔离双重保险第一道）。检索范围专用：只让启用库参与 RAG。 */
    List<KnowledgeBase> findByAppIdAndEnabledTrue(Long appId);

    /** 管理列表专用：不带 enabled 过滤，禁用库仍需在管理面可见（否则禁用后无法重新启用）。 */
    List<KnowledgeBase> findByAppId(Long appId);
}

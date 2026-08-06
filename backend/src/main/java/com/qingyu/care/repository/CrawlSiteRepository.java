package com.qingyu.care.repository;

import com.qingyu.care.entity.CrawlSite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 爬取站点仓储。
 *
 * <p>普通派生查询受 Hibernate {@code @Filter}（tenant/app）保护；但 {@code findById}
 * 不受保护（Hibernate 6 的已知行为），故服务层取站点后仍要过
 * {@code TenantOwnershipGuard.mutableInCurrentContext} 显式校验。
 *
 * @author ruan
 */
@Repository
public interface CrawlSiteRepository extends JpaRepository<CrawlSite, Long> {

    /** 列某知识库下的站点，最新创建的在前。 */
    List<CrawlSite> findByKnowledgeBaseIdOrderByIdDesc(Long knowledgeBaseId);
}

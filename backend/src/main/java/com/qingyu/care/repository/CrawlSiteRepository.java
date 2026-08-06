package com.qingyu.care.repository;

import com.qingyu.care.entity.CrawlSite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    /**
     * 按知识库 + 入口地址查是否已建过站点，供 {@code startCrawl} 拒绝重复建站——
     * 同一入口建两个站点会各自跑一遍 BFS，互相看不见对方未提交的建档，产出重复文档与重复向量。
     *
     * <p>⚠️ 用 {@code findFirst} 而不是单结果查询：理由同
     * {@code DocumentRepository#findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc}——
     * 不能让历史脏数据（若曾经允许过重复）把整个建站入口 500。
     */
    Optional<CrawlSite> findFirstByKnowledgeBaseIdAndEntryUrlOrderByIdAsc(Long knowledgeBaseId, String entryUrl);
}

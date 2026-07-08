package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：searchKnowledge 在 hybrid 开启时融合关键词路（真实 FTS）与向量路（真实 PgVectorStore），
 * 关键词能召回向量路漏掉的精确术语；hybrid 关闭时仅走向量路（不含关键词 chunk）。
 *
 * <p>不 mock VectorStore：与其它 IT 共享同一 ApplicationContext，vector_store 表由真实
 * PgVectorStore 的 initialize-schema 建全（含 embedding 列），故不会破坏共享表结构
 * （早前 mock 版会让 JPA 的 VectorStoreEntity 建出缺 embedding 列的表污染共享容器，已根治）。
 * 向量路在无 embedding key 的测试环境会 401 / 或对零向量种子 cosine 低于阈值不命中——
 * 经 HybridRetrievalServiceImpl 的向量路 fail-soft 吞成空列表，两个用例都稳定。
 *
 * <p>@Transactional：本 IT 直接用 TenantContext 设置租户上下文调用 service（不经真实 HTTP 请求）。
 * TenantDataAspect 在 Hibernate Session 上 enableFilter，但若不在同一个事务内，种子写入与后续
 * 检索查询可能各自绑定不同的 EntityManager/Session，导致过滤器未作用到真正执行查询的 Session
 * （真实 HTTP 请求下 open-in-view 提前绑定了同一个 EntityManager，故无此问题）。类级
 * @Transactional 让整个测试方法内的写入与查询共享同一个事务绑定的 Session，同时顺带回滚种子数据。
 * 表结构在上下文初始化时已建好并提交、不在测试事务内，@Transactional 只回滚种子数据、不动表结构。
 *
 * <p>检索范围要求应用上下文 + enabled 库行，种子相应配套：先在租户+应用上下文内种一条 enabled
 * 知识库行拿到 kbId，再把 chunk 的 metadata（appId/knowledgeBaseId）与之对齐，否则会被
 * RetrievalScopeResolver 判定为空范围而短路。
 *
 * @author ruan
 */
@Transactional
class HybridRetrievalIT extends AbstractPostgresIT {

    private static final Long TID = 60001L;
    private static final Long APP = 60002L;

    @Autowired ChatService chatService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ChatStrategyRepository chatStrategyRepository;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;

    /** 在租户+应用上下文内种 enabled 知识库行（TenantEntityListener 回填 tenant/app）。 */
    private Long seedKb() {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);
        try {
            return knowledgeBaseRepository.save(KnowledgeBase.builder()
                    .name("混合检索IT库").enabled(true).build()).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private void seedChunk(String content, Long kbId) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + TID + "\",\"appId\":\"" + APP
                        + "\",\"knowledgeBaseId\":\"" + kbId
                        + "\",\"visibility\":\"PUBLIC\",\"docEnabled\":\"true\"}");
    }

    /**
     * 播种租户自有 default 场景策略（hybridEnabled=false），覆盖平台默认 true。
     * 必须同时设置 appId：ChatStrategy 继承 BaseTenantEntity，TenantDataAspect 在应用上下文非空时
     * 会启用 appFilter（app_id = :appId 精确匹配，无 OR app_id IS NULL 兜底）；若种子行 app_id 为
     * NULL，会被查询端（search() 已设置 appId）的 appFilter 过滤掉，导致 getActiveStrategy 找不到
     * 本条而回退到平台默认（hybridEnabled=true），使该用例误入混合检索路径。
     */
    private void seedTenantStrategyHybridDisabled(Long tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setAppId(APP);
        try {
            chatStrategyRepository.save(ChatStrategy.builder()
                    .name("hybrid关闭测试策略")
                    .scenario("default")
                    .topK(5)
                    .similarityThreshold(0.7)
                    .allowNoKnowledge(true)
                    .enabled(true)
                    .isDefault(true)
                    .hybridEnabled(false)
                    .build());
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", String.valueOf(TID));
        jdbcTemplate.update("DELETE FROM chat_strategy WHERE tenant_id = ?", TID);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE tenant_id = ?", TID);
    }

    /** 设置租户+应用上下文调用检索入口（检索范围现在要求应用上下文）。 */
    private List<Document> search(String q) {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);
        try {
            return chatService.searchKnowledge(q);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 混合检索召回向量漏掉的精确术语() {
        // 向量路：真实 PgVectorStore；零向量种子 cosine 低于阈值 / 无 key 时 401 → 向量路 fail-soft 空。
        // 关键词路：真实中文 FTS 命中精确术语 XZ-9000。
        Long kbId = seedKb();
        seedChunk("售后：产品型号XZ-9000 的保修政策说明", kbId);

        List<Document> docs = search("产品型号XZ-9000");

        assertThat(docs).extracting(Document::getContent).anyMatch(c -> c.contains("XZ-9000"));
    }

    @Test
    void hybrid关闭_仅向量路_不含关键词chunk() {
        seedTenantStrategyHybridDisabled(TID);
        Long kbId = seedKb();
        seedChunk("售后：产品型号XZ-9000 的保修政策说明", kbId);   // 关键词路种子，关闭时不应参与

        List<Document> docs = search("产品型号XZ-9000");

        // hybrid 关闭 → 只走向量路（fail-soft 空）；关键词 chunk 不应被召回
        assertThat(docs).extracting(Document::getContent).noneMatch(c -> c.contains("XZ-9000"));
    }
}

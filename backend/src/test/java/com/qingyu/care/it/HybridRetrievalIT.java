package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * 端到端：searchKnowledge 在 hybrid 开启时融合关键词路（真实 FTS）与向量路（mock），
 * 关键词能召回向量路漏掉的精确术语；hybrid 关闭时仅走向量路。
 *
 * @Transactional：本 IT 直接用 TenantContext 设置租户上下文调用 service（不经真实 HTTP 请求）。
 * TenantDataAspect 在 Hibernate Session 上 enableFilter，但若不在同一个事务内，种子写入与后续
 * 检索查询可能各自绑定不同的 EntityManager/Session，导致过滤器未作用到真正执行查询的 Session
 * （真实 HTTP 请求下 open-in-view 提前绑定了同一个 EntityManager，故无此问题）。类级
 * @Transactional 让整个测试方法内的写入与查询共享同一个事务绑定的 Session，同时顺带回滚测试数据。
 *
 * @author ruan
 */
@Transactional
class HybridRetrievalIT extends AbstractPostgresIT {

    private static final Long TID = 60001L;

    @Autowired ChatService chatService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ChatStrategyRepository chatStrategyRepository;
    @MockitoBean VectorStore vectorStore;

    /**
     * 本 IT 用 @MockitoBean 整体替换 VectorStore（避免真调用 embedding API 触发 401），
     * 因此真实 PgVectorStore 的 initialize-schema（含建 embedding 向量列）不会执行——
     * Hibernate（ddl-auto=create-drop）已按 VectorStoreEntity 建了一张缺 embedding 列的
     * vector_store 表。这里手动补列，使种子行可插入（幂等，仅测试基础设施，不影响生产代码）。
     */
    @BeforeEach
    void ensureEmbeddingColumn() {
        jdbcTemplate.execute("ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS embedding vector(1536)");
    }

    private void seedChunk(String content) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + TID + "\"}");
    }

    /** 播种租户自有 default 场景策略（hybridEnabled=false），覆盖平台默认 true。 */
    private void seedTenantStrategyHybridDisabled(Long tenantId) {
        TenantContext.setTenantId(tenantId);
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
    }

    /** 设置租户上下文调用检索入口。 */
    private List<Document> search(String q) {
        TenantContext.setTenantId(TID);
        try {
            return chatService.searchKnowledge(q);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 混合检索召回向量漏掉的精确术语() {
        Mockito.when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("v1", "一些语义相近但无型号的内容", Map.of())));
        seedChunk("售后：产品型号XZ-9000 的保修政策说明");

        List<Document> docs = search("产品型号XZ-9000");

        assertThat(docs).extracting(Document::getContent).anyMatch(c -> c.contains("XZ-9000"));
    }

    @Test
    void hybrid关闭_仅向量路_不含关键词chunk() {
        seedTenantStrategyHybridDisabled(TID);
        Mockito.when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("v1", "纯向量内容", Map.of())));
        seedChunk("售后：产品型号XZ-9000 的保修政策说明");

        List<Document> docs = search("产品型号XZ-9000");

        assertThat(docs).extracting(Document::getContent).containsExactly("纯向量内容");
        assertThat(docs).extracting(Document::getContent).noneMatch(c -> c.contains("XZ-9000"));
    }
}

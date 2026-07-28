package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
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
 * 文档权限控制端到端：PUBLIC 级检不到 INTERNAL 文档、INTERNAL 级全见、禁用文档不参与、
 * 变更同步（jsonb_set）实时生效。命中均来自关键词路（向量路测试环境 fail-soft），与既有 IT 同手法。
 * 类级 @Transactional：非 HTTP 直调 service 下保证种子与查询共享 Session（见 HybridRetrievalIT 注释）。
 *
 * @author ruan
 */
@Transactional
class DocVisibilityRetrievalIT extends AbstractPostgresIT {

    private static final Long TID = 63001L;
    private static final Long APP = 63002L;
    /** 与 test profile 的 spring.ai.openai.embedding.options.model 一致（application.yml 默认值），
     * 护栏加 embeddingModel 等值过滤后，播种 chunk 须补齐该键，否则被护栏静默排除、断言失败。 */
    private static final String EMBEDDING_MODEL = "qwen3.7-text-embedding";

    @Autowired ChatService chatService;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired VectorStoreRepository vectorStoreRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long seedKb() {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);
        try {
            return knowledgeBaseRepository.save(KnowledgeBase.builder()
                    .name("可见性IT库").enabled(true).build()).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private void seedChunk(String content, Long kbId, String docId, String visibility, String docEnabled) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + TID + "\",\"appId\":\"" + APP
                        + "\",\"knowledgeBaseId\":\"" + kbId + "\",\"documentId\":\"" + docId
                        + "\",\"visibility\":\"" + visibility + "\",\"docEnabled\":\"" + docEnabled
                        + "\",\"embeddingModel\":\"" + EMBEDDING_MODEL + "\"}");
    }

    /** 以指定访问级别发起检索（模拟鉴权拦截器写入的级别）。 */
    private List<Document> search(String q, DocumentVisibility level) {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);
        TenantContext.setAccessLevel(level);
        try {
            return chatService.searchKnowledge(q);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'tenantId' = ?", String.valueOf(TID));
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE tenant_id = ?", TID);
    }

    @Test
    void PUBLIC级_只见公开文档_INTERNAL级_全见() {
        Long kb = seedKb();
        seedChunk("公开FAQ 产品型号XV-1111 说明", kb, "801", "PUBLIC", "true");
        seedChunk("内部价格表 产品型号XV-1111 说明", kb, "802", "INTERNAL", "true");

        List<Document> anonymous = search("产品型号XV-1111", DocumentVisibility.PUBLIC);
        assertThat(anonymous).extracting(Document::getText)
                .anyMatch(c -> c.contains("公开FAQ"))
                .noneMatch(c -> c.contains("内部价格表"));

        List<Document> trusted = search("产品型号XV-1111", DocumentVisibility.INTERNAL);
        assertThat(trusted).extracting(Document::getText)
                .anyMatch(c -> c.contains("公开FAQ"))
                .anyMatch(c -> c.contains("内部价格表"));
    }

    @Test
    void 未设访问级别_按PUBLIC兜底() {
        Long kb = seedKb();
        seedChunk("内部知识 产品型号XV-2222", kb, "803", "INTERNAL", "true");

        TenantContext.setTenantId(TID);
        TenantContext.setAppId(APP);   // 不设 accessLevel
        try {
            assertThat(chatService.searchKnowledge("产品型号XV-2222")).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 禁用文档不参与_重新启用后恢复() {
        Long kb = seedKb();
        seedChunk("可开关知识 产品型号XV-3333", kb, "804", "PUBLIC", "true");

        // 禁用（直接走同步仓库方法，与管理端同一条路径）
        vectorStoreRepository.updateDocEnabledByDocumentId("804", "false");
        assertThat(search("产品型号XV-3333", DocumentVisibility.INTERNAL)).isEmpty();

        // 重新启用 → 恢复命中（chunk 未删、免重嵌入）
        vectorStoreRepository.updateDocEnabledByDocumentId("804", "true");
        assertThat(search("产品型号XV-3333", DocumentVisibility.INTERNAL))
                .extracting(Document::getText).anyMatch(c -> c.contains("可开关知识"));
    }

    @Test
    void 可见性变更同步_实时生效() {
        Long kb = seedKb();
        seedChunk("待收紧知识 产品型号XV-4444", kb, "805", "PUBLIC", "true");
        assertThat(search("产品型号XV-4444", DocumentVisibility.PUBLIC)).isNotEmpty();

        vectorStoreRepository.updateVisibilityByDocumentId("805", "INTERNAL");

        assertThat(search("产品型号XV-4444", DocumentVisibility.PUBLIC)).isEmpty();
        assertThat(search("产品型号XV-4444", DocumentVisibility.INTERNAL)).isNotEmpty();
    }
}

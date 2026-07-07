package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
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
 * 多知识库联合检索端到端：enabled 库联合命中、disabled 不参与、应用隔离、
 * 无应用上下文/无 enabled 库时空命中。向量路在测试环境 fail-soft 为空，
 * 命中均来自关键词路（真实中文 FTS），与 HybridRetrievalIT 同手法。
 * 类级 @Transactional：非 HTTP 直调 service 下保证种子与查询共享 Session（见 HybridRetrievalIT 注释）。
 *
 * @author ruan
 */
@Transactional
class MultiKbRetrievalIT extends AbstractPostgresIT {

    private static final Long TID = 61001L;
    private static final Long APP_A = 61002L;
    private static final Long APP_B = 61003L;

    @Autowired ChatService chatService;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long seedKb(Long appId, boolean enabled) {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(appId);
        try {
            return knowledgeBaseRepository.save(KnowledgeBase.builder()
                    .name("多库IT-" + appId + "-" + enabled).enabled(enabled).build()).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private void seedChunk(String content, Long appId, Long kbId) {
        jdbcTemplate.update(
                "INSERT INTO vector_store(id, content, metadata, embedding) " +
                        "VALUES (gen_random_uuid(), ?, ?::jsonb, array_fill(0::real, ARRAY[1536])::vector)",
                content, "{\"tenantId\":\"" + TID + "\",\"appId\":\"" + appId
                        + "\",\"knowledgeBaseId\":\"" + kbId + "\"}");
    }

    private List<Document> search(String q, Long appId) {
        TenantContext.setTenantId(TID);
        TenantContext.setAppId(appId);
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
    void 多enabled库联合命中_disabled库不参与() {
        Long kb1 = seedKb(APP_A, true);
        Long kb2 = seedKb(APP_A, true);
        Long kb3 = seedKb(APP_A, false);
        seedChunk("知识一 产品型号XK-1111 说明", APP_A, kb1);
        seedChunk("知识二 产品型号XK-1111 说明", APP_A, kb2);
        seedChunk("禁用库 产品型号XK-1111 说明", APP_A, kb3);

        List<Document> docs = search("产品型号XK-1111", APP_A);

        assertThat(docs).extracting(Document::getContent)
                .anyMatch(c -> c.contains("知识一"))
                .anyMatch(c -> c.contains("知识二"))
                .noneMatch(c -> c.contains("禁用库"));
    }

    @Test
    void 应用隔离_A应用检索不到B应用知识() {
        Long kbB = seedKb(APP_B, true);
        seedChunk("B应用内部 产品型号XK-2222 说明", APP_B, kbB);
        seedKb(APP_A, true);   // A 应用有 enabled 库（非空范围）但无内容

        List<Document> docs = search("产品型号XK-2222", APP_A);

        assertThat(docs).isEmpty();
    }

    @Test
    void 无应用上下文_不检索返回空() {
        Long kb = seedKb(APP_A, true);
        seedChunk("知识 产品型号XK-3333 说明", APP_A, kb);

        TenantContext.setTenantId(TID);   // 只设租户、不设应用
        try {
            assertThat(chatService.searchKnowledge("产品型号XK-3333")).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 无enabled库_返回空() {
        Long kb = seedKb(APP_A, false);   // 只有 disabled 库
        seedChunk("知识 产品型号XK-4444 说明", APP_A, kb);

        assertThat(search("产品型号XK-4444", APP_A)).isEmpty();
    }
}

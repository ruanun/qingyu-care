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
 * appId 子句由「错标 chunk」用例独立守护（kbIds IN 因 kbId 全局唯一通常已足够，appId 是第二道防线）。
 * 类级 @Transactional：非 HTTP 直调 service 下保证种子与查询共享 Session（见 HybridRetrievalIT 注释）。
 *
 * @author ruan
 */
@Transactional
class MultiKbRetrievalIT extends AbstractPostgresIT {

    private static final Long TID = 61001L;
    private static final Long APP_A = 61002L;
    private static final Long APP_B = 61003L;

    /** 检索要按应用读它钉住的向量模型：写死的应用 id 必须对应真实的应用行，否则检索 fail-soft 为空、反向断言永真 */
    @org.junit.jupiter.api.BeforeEach
    void ensureApps() {
        ensureAppRow(TID, APP_A);
        ensureAppRow(TID, APP_B);
    }

    @Autowired ChatService chatService;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * 当前应用实际生效的向量模型标签——与检索护栏同源，但<b>不触发钉模型</b>（只读两列）。
     *
     * <p>⚠️ 以前这里写死 "qwen3.7-text-embedding"（启动播种的值）。但整个 IT 套件共用一个 PG，
     * 多个 Spring 上下文各自 create-drop 重建 schema、重跑 @PostConstruct 播种，哪个平台默认模型生效
     * 取决于上下文创建顺序；failsafe 的 runOrder=filesystem 在 Linux 与 Windows 上顺序不同，
     * 写死的标签就会在 CI 上变成「向量被护栏静默过滤 → 检索返回空」。改为现场读。
     */
    private String embeddingTag(Long appId) {
        String pinned = jdbcTemplate.query(
                "SELECT m.model_id FROM sys_application a JOIN ai_model_config m ON m.id = a.embedding_model_id "
                        + "WHERE a.id = ?",
                rs -> rs.next() ? rs.getString(1) : null, appId);
        if (pinned != null) {
            return pinned;
        }
        return jdbcTemplate.queryForObject(
                "SELECT model_id FROM ai_model_config WHERE scope = 'PLATFORM' AND tenant_id IS NULL "
                        + "AND enabled = true AND model_type = 'EMBEDDING' ORDER BY priority ASC, id ASC LIMIT 1",
                String.class);
    }

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
                        + "\",\"knowledgeBaseId\":\"" + kbId
                        + "\",\"visibility\":\"PUBLIC\",\"docEnabled\":\"true\""
                        + ",\"embeddingModel\":\"" + embeddingTag(appId) + "\"}");
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

        assertThat(docs).extracting(Document::getText)
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
    void appId子句独立生效_错标他应用的chunk不命中() {
        Long kbA = seedKb(APP_A, true);
        // 脏数据：knowledgeBaseId 命中 A 的库、但 appId 错标为 B（模拟跨应用误写/数据错标）
        seedChunk("错标数据 产品型号XK-5555 说明", APP_B, kbA);
        seedChunk("正常数据 产品型号XK-5555 说明", APP_A, kbA);

        List<Document> docs = search("产品型号XK-5555", APP_A);

        assertThat(docs).extracting(Document::getText)
                .anyMatch(c -> c.contains("正常数据"))
                .noneMatch(c -> c.contains("错标数据"));
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

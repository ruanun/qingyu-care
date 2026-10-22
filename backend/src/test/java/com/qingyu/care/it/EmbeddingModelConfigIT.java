package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.embedding.EmbeddingModelResolver;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量模型后台化依赖的原生 SQL 与跨线程解析，在真实 PG 上验证——这些全是「错了不报错」的地方：
 * 对话路由误选向量模型、无上下文线程解析不到平台默认、指针 CAS 写错列、补漏扫描查空、清理误删当前模型的向量。
 *
 * <p>刻意不加 {@code @Transactional}：解析器钉默认模型走 REQUIRES_NEW，外层测试事务里播种的应用行它看不见，
 * 会让「钉住」那条断言在错误的路径上偶然通过。
 *
 * @author ruan
 */
class EmbeddingModelConfigIT extends AbstractPostgresIT {

    private static final String TAG_A = "qwen3.7-text-embedding";
    private static final String TAG_B = "bge-m3-it";

    @Autowired AiModelConfigRepository modelRepository;
    @Autowired EmbeddingModelResolver resolver;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired QaPairRepository qaPairRepository;
    @Autowired VectorStoreRepository vectorStoreRepository;
    @Autowired ChatStrategyRepository chatStrategyRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    private final List<Long> modelIds = new ArrayList<>();
    private final List<Long> appIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'appId' = ?", appId.toString());
            jdbcTemplate.update("DELETE FROM qa_pair WHERE app_id = ?", appId);
            jdbcTemplate.update("DELETE FROM document WHERE app_id = ?", appId);
            jdbcTemplate.update("DELETE FROM knowledge_base WHERE app_id = ?", appId);
            jdbcTemplate.update("DELETE FROM chat_strategy WHERE app_id = ?", appId);
            jdbcTemplate.update("UPDATE sys_application SET embedding_model_id = NULL, pending_embedding_model_id = NULL WHERE id = ?", appId);
        }
        for (Long id : modelIds) {
            jdbcTemplate.update("DELETE FROM ai_model_config WHERE id = ?", id);
        }
    }

    private Application app(String suffix) {
        Application app = seedApp("emb-" + suffix + "-" + System.nanoTime(), null);
        appIds.add(app.getId());
        return app;
    }

    private AiModelConfig saveModel(AiModelConfig m) {
        AiModelConfig saved = modelRepository.save(m);
        modelIds.add(saved.getId());
        return saved;
    }

    private AiModelConfig embeddingModel(Long tenantId, Long appId, String modelId) {
        AiModelConfig m = AiModelConfig.builder().name("IT向量-" + modelId).provider("openai").modelId(modelId)
                .scenario("default").priority(1).enabled(true).modelType(ModelType.EMBEDDING)
                .scope(tenantId == null ? OwnerScope.PLATFORM : OwnerScope.TENANT).build();
        m.setTenantId(tenantId);
        m.setAppId(appId);
        return m;
    }

    @Test
    void 平台默认向量模型已在启动时播种() {
        assertThat(modelRepository.countPlatformEmbeddingModels()).isGreaterThanOrEqualTo(1);
        AiModelConfig def = resolver.platformDefault();
        assertThat(def).isNotNull();
        assertThat(def.getModelType()).isEqualTo(ModelType.EMBEDDING);
        assertThat(def.getTenantId()).isNull();
        assertThat(def.getModelId()).isEqualTo(TAG_A);
    }

    @Test
    void 对话路由的两条查询_都不会选中向量模型() {
        Application app = app("route");
        Long tenantId = app.getTenant().getId();
        // 应用自有一条向量模型（scenario 同为 default）+ 平台共享一条向量模型
        saveModel(embeddingModel(tenantId, app.getId(), "own-emb"));
        saveModel(embeddingModel(null, null, "shared-emb"));

        assertThat(modelRepository.findSharedModelsByScenario("default"))
                .extracting(m -> ModelType.orDefault(m.getModelType()))
                .doesNotContain(ModelType.EMBEDDING);
        // 无上下文且未登录时过滤器不启用：这条查询看得到全平台的行，正好检验类型条件本身
        assertThat(modelRepository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default"))
                .extracting(AiModelConfig::getModelId)
                .doesNotContain("own-emb", "shared-emb");
    }

    @Test
    void 无租户上下文的线程_解析应用向量模型_钉成平台默认() {
        Application app = app("pin");
        TenantContext.clear();   // 模拟 @Async 摄取线程

        AiModelConfig active = resolver.active(app.getId());

        assertThat(active.getId()).isEqualTo(resolver.platformDefault().getId());
        Long pinned = jdbcTemplate.queryForObject(
                "SELECT embedding_model_id FROM sys_application WHERE id = ?", Long.class, app.getId());
        assertThat(pinned).isEqualTo(active.getId());
    }

    @Test
    void 指针CAS_同时只能有一次切换_翻转与取消只认预期的目标() {
        Application app = app("cas");
        Long appId = app.getId();
        Long def = resolver.active(appId).getId();
        AiModelConfig b = saveModel(embeddingModel(null, null, TAG_B));
        AiModelConfig c = saveModel(embeddingModel(null, null, "other-it"));

        Integer first = transactionTemplate.execute(s -> applicationRepository.startPendingEmbeddingModel(appId, b.getId()));
        Integer second = transactionTemplate.execute(s -> applicationRepository.startPendingEmbeddingModel(appId, c.getId()));
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();

        Integer wrongPromote = transactionTemplate.execute(s -> applicationRepository.promotePendingEmbeddingModel(appId, c.getId()));
        assertThat(wrongPromote).isZero();
        assertThat(resolver.writeTargets(appId)).extracting(AiModelConfig::getId).containsExactly(def, b.getId());

        Integer promote = transactionTemplate.execute(s -> applicationRepository.promotePendingEmbeddingModel(appId, b.getId()));
        assertThat(promote).isEqualTo(1);
        var pointers = applicationRepository.findEmbeddingPointers(appId).orElseThrow();
        assertThat(pointers.getActive()).isEqualTo(b.getId());
        assertThat(pointers.getPending()).isNull();
        assertThat(applicationRepository.countEmbeddingModelReferences(b.getId())).isEqualTo(1);
    }

    @Test
    void 补漏扫描只报缺目标标签的内容_清理只留当前标签() {
        Application app = app("sweep");
        Long tenantId = app.getTenant().getId();
        Long appId = app.getId();
        TenantContext.setTenantId(tenantId);
        TenantContext.setAppId(appId);
        KnowledgeBase kb;
        Document done;
        Document missing;
        Document processing;
        QaPair qaDone;
        QaPair qaMissing;
        QaPair draft;
        try {
            kb = knowledgeBaseRepository.save(KnowledgeBase.builder().name("补漏IT").enabled(true).build());
            done = documentRepository.save(doc(kb, DocumentStatus.COMPLETED, 2));
            missing = documentRepository.save(doc(kb, DocumentStatus.COMPLETED, 3));
            processing = documentRepository.save(doc(kb, DocumentStatus.PROCESSING, 0));
            qaDone = qaPairRepository.save(qa(kb, QaPairStatus.ACTIVE));
            qaMissing = qaPairRepository.save(qa(kb, QaPairStatus.DISABLED));
            draft = qaPairRepository.save(qa(kb, QaPairStatus.DRAFT));
        } finally {
            TenantContext.clear();
        }
        seedVector(appId, "documentId", done.getId(), TAG_A);
        seedVector(appId, "documentId", done.getId(), TAG_B);
        seedVector(appId, "documentId", missing.getId(), TAG_A);
        seedVector(appId, "qaPairId", qaDone.getId(), TAG_A);
        seedVector(appId, "qaPairId", qaDone.getId(), TAG_B);
        seedVector(appId, "qaPairId", qaMissing.getId(), TAG_A);

        assertThat(vectorStoreRepository.findDocumentIdsMissingTag(appId, TAG_B))
                .containsExactly(missing.getId());   // PROCESSING 的不算：那一轮处理会按写入目标双写
        assertThat(vectorStoreRepository.findQaPairIdsMissingTag(appId, TAG_B))
                .containsExactly(qaMissing.getId()); // 草稿没有向量，不算缺
        assertThat(documentRepository.findCompletedIdsByAppId(appId)).containsExactlyInAnyOrder(done.getId(), missing.getId());
        assertThat(qaPairRepository.findEmbeddedIdsByAppId(appId))
                .containsExactlyInAnyOrder(qaDone.getId(), qaMissing.getId())
                .doesNotContain(draft.getId());
        assertThat(processing.getId()).isNotNull();

        Integer removed = transactionTemplate.execute(s -> vectorStoreRepository.deleteByAppIdExceptTag(appId.toString(), TAG_B));
        assertThat(removed).isEqualTo(4);   // 两篇文档 + 两条 QA 各一条旧标签向量
        List<String> left = jdbcTemplate.queryForList(
                "SELECT metadata->>'embeddingModel' FROM vector_store WHERE metadata->>'appId' = ?", String.class, appId.toString());
        assertThat(left).containsOnly(TAG_B).hasSize(2);
    }

    @Test
    void 按标签删除_只删目标模型那份() {
        Application app = app("tagdel");
        Long appId = app.getId();
        seedVector(appId, "documentId", 990001L, TAG_A);
        seedVector(appId, "documentId", 990001L, TAG_B);

        transactionTemplate.executeWithoutResult(s -> vectorStoreRepository.deleteByDocumentIdAndTag("990001", TAG_B));

        List<String> left = jdbcTemplate.queryForList(
                "SELECT metadata->>'embeddingModel' FROM vector_store WHERE metadata->>'appId' = ?", String.class, appId.toString());
        assertThat(left).containsExactly(TAG_A);
    }

    @Test
    void 手动阈值策略_含应用自有与平台默认_跟随的不算() {
        Application app = app("strategy");
        TenantContext.setTenantId(app.getTenant().getId());
        TenantContext.setAppId(app.getId());
        try {
            chatStrategyRepository.save(ChatStrategy.builder().name("IT手动").scenario("faq")
                    .similarityThreshold(0.6).followModelThreshold(false).enabled(true).isDefault(false).build());
            chatStrategyRepository.save(ChatStrategy.builder().name("IT跟随").scenario("faq2")
                    .similarityThreshold(0.6).enabled(true).isDefault(false).build());
        } finally {
            TenantContext.clear();
        }

        assertThat(chatStrategyRepository.findManualThresholdStrategies(app.getId()))
                .extracting(ChatStrategy::getName)
                .contains("IT手动", "严谨投诉处理")   // 平台默认的投诉策略播种为不跟随
                .doesNotContain("IT跟随");
    }

    private static Document doc(KnowledgeBase kb, DocumentStatus status, int chunks) {
        return Document.builder().knowledgeBase(kb).name("IT文档-" + System.nanoTime())
                .documentType(DocumentType.TEXT).status(status).chunkCount(chunks).build();
    }

    private static QaPair qa(KnowledgeBase kb, QaPairStatus status) {
        return QaPair.builder().knowledgeBase(kb).question("问" + System.nanoTime()).answer("答")
                .status(status).source(com.qingyu.care.enums.QaPairSource.MANUAL).build();
    }

    private void seedVector(Long appId, String key, Long id, String tag) {
        jdbcTemplate.update("INSERT INTO vector_store(id, content, metadata, embedding) VALUES (gen_random_uuid(), ?, ?::json, "
                        + "array_fill(0::real, ARRAY[1536])::vector)",
                "内容", "{\"appId\":\"" + appId + "\",\"" + key + "\":\"" + id + "\",\"embeddingModel\":\"" + tag + "\"}");
    }
}

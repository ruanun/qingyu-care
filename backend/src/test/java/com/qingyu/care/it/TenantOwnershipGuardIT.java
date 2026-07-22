package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.HandoverRuleType;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.HandoverRuleRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.PromptTemplateRepository;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按 id 加载路径的跨租户越权守卫 IT。
 * Hibernate 6 的 @Filter 不作用于 findById（未设 applyToLoadByKey），若服务层未补显式归属校验，
 * 已登录租户可枚举 id 跨租户读到他人配置/知识库。此处真实 DB + 真实 TenantContext 端到端验证守卫生效：
 * 他租户上下文按「不存在」拒绝，本租户上下文正常可读。
 *
 * @author ruan
 */
class TenantOwnershipGuardIT extends AbstractPostgresIT {

    @Autowired AiModelConfigRepository modelConfigRepository;
    @Autowired ChatStrategyRepository strategyRepository;
    @Autowired PromptTemplateRepository promptTemplateRepository;
    @Autowired HandoverRuleRepository handoverRuleRepository;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired DocumentRepository documentRepository;

    @Autowired ModelRouterService modelRouterService;
    @Autowired ChatStrategyService chatStrategyService;
    @Autowired PromptTemplateService promptTemplateService;
    @Autowired HandoverRuleService handoverRuleService;
    @Autowired KnowledgeService knowledgeService;

    /** IT 线程内以指定应用的租户上下文执行（服务层归属校验读 TenantContext） */
    private <T> T withTenantContext(Application app, Supplier<T> action) {
        try {
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void ownBy(com.qingyu.care.entity.BaseTenantEntity entity, Application app) {
        entity.setTenantId(app.getTenant().getId());
        entity.setAppId(app.getId());
    }

    @Test
    void 模型配置_跨租户按id不可见_本租户可读() {
        Application appA = seedApp("guard-m1-" + System.nanoTime(), "http://m1.example.com");
        Application appB = seedApp("guard-m2-" + System.nanoTime(), "http://m2.example.com");
        AiModelConfig cfg = AiModelConfig.builder()
                .name("A租户模型").provider("openai").modelId("gpt-4o").endpoint("http://x")
                .apiKey("sk-a-secret").scenario("default").priority(1)
                .enabled(true).scope(OwnerScope.TENANT).build();
        ownBy(cfg, appA);
        Long id = modelConfigRepository.saveAndFlush(cfg).getId();

        assertThatThrownBy(() -> withTenantContext(appB, () -> modelRouterService.getById(id)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(withTenantContext(appA, () -> modelRouterService.getById(id)).getApiKey())
                .isEqualTo("sk-a-secret");
    }

    @Test
    void 问答策略_跨租户按id不可见_本租户可读() {
        Application appA = seedApp("guard-s1-" + System.nanoTime(), "http://s1.example.com");
        Application appB = seedApp("guard-s2-" + System.nanoTime(), "http://s2.example.com");
        ChatStrategy s = ChatStrategy.builder()
                .name("A租户策略").scenario("faq").topK(3).similarityThreshold(0.7)
                .allowNoKnowledge(true).enabled(true).isDefault(false).build();
        ownBy(s, appA);
        Long id = strategyRepository.saveAndFlush(s).getId();

        assertThatThrownBy(() -> withTenantContext(appB, () -> chatStrategyService.getById(id)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(withTenantContext(appA, () -> chatStrategyService.getById(id))).isNotNull();
    }

    @Test
    void Prompt模板_跨租户按id不可见_本租户可读() {
        Application appA = seedApp("guard-p1-" + System.nanoTime(), "http://p1.example.com");
        Application appB = seedApp("guard-p2-" + System.nanoTime(), "http://p2.example.com");
        PromptTemplate t = PromptTemplate.builder()
                .name("A租户模板").content("内容").version(1).isActive(false).description("d").build();
        ownBy(t, appA);
        Long id = promptTemplateRepository.saveAndFlush(t).getId();

        assertThatThrownBy(() -> withTenantContext(appB, () -> promptTemplateService.getById(id)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(withTenantContext(appA, () -> promptTemplateService.getById(id))).isNotNull();
    }

    @Test
    void 转接规则_跨租户按id不可见_本租户可读() {
        Application appA = seedApp("guard-h1-" + System.nanoTime(), "http://h1.example.com");
        Application appB = seedApp("guard-h2-" + System.nanoTime(), "http://h2.example.com");
        HandoverRule r = HandoverRule.builder()
                .ruleType(HandoverRuleType.KEYWORD).name("A租户规则").description("d")
                .enabled(true).config("{\"keywords\":[\"x\"]}").priority(1).build();
        ownBy(r, appA);
        Long id = handoverRuleRepository.saveAndFlush(r).getId();

        assertThatThrownBy(() -> withTenantContext(appB, () -> handoverRuleService.getById(id)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(withTenantContext(appA, () -> handoverRuleService.getById(id))).isNotNull();
    }

    @Test
    void 知识库_跨租户按id不可见_本租户可读() {
        Application appA = seedApp("guard-k1-" + System.nanoTime(), "http://k1.example.com");
        Application appB = seedApp("guard-k2-" + System.nanoTime(), "http://k2.example.com");
        KnowledgeBase kb = KnowledgeBase.builder().name("A租户库").description("d").enabled(true).build();
        ownBy(kb, appA);
        Long id = knowledgeBaseRepository.saveAndFlush(kb).getId();

        assertThatThrownBy(() -> withTenantContext(appB, () -> knowledgeService.getKnowledgeBase(id)))
                .isInstanceOf(RuntimeException.class);
        assertThat(withTenantContext(appA, () -> knowledgeService.getKnowledgeBase(id))).isNotNull();
    }

    @Test
    void 文档_跨租户按id不可见_本租户可读() {
        Application appA = seedApp("guard-d1-" + System.nanoTime(), "http://d1.example.com");
        Application appB = seedApp("guard-d2-" + System.nanoTime(), "http://d2.example.com");
        KnowledgeBase kb = KnowledgeBase.builder().name("A租户库").description("d").enabled(true).build();
        ownBy(kb, appA);
        KnowledgeBase savedKb = knowledgeBaseRepository.saveAndFlush(kb);
        Document doc = Document.builder()
                .knowledgeBase(savedKb).name("A租户文档").originalFilename("a.txt")
                .documentType(DocumentType.TEXT).status(DocumentStatus.PENDING)
                .filePath("s3://a").storageKey("a").fileSize(1L).build();
        ownBy(doc, appA);
        Long id = documentRepository.saveAndFlush(doc).getId();

        assertThatThrownBy(() -> withTenantContext(appB, () -> knowledgeService.getDocument(id)))
                .isInstanceOf(RuntimeException.class);
        assertThat(withTenantContext(appA, () -> knowledgeService.getDocument(id))).isNotNull();
    }
}

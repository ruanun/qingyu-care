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
import com.qingyu.care.exception.BusinessException;
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
    @Autowired com.qingyu.care.repository.AiSkillRepository aiSkillRepository;
    @Autowired com.qingyu.care.service.ChatToolService chatToolService;

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
    void 平台共享模型_租户按id读取拒绝_平台上下文可读() {
        // 实体含明文 apiKey，读路径用写级守卫：租户继承共享模型走场景查询，无需按 id 读平台行
        Application appA = seedApp("guard-pm-" + System.nanoTime(), "http://pm.example.com");
        AiModelConfig cfg = AiModelConfig.builder()
                .name("平台共享模型").provider("openai").modelId("gpt-4o").endpoint("http://x")
                .apiKey("sk-platform-secret").scenario("default").priority(1)
                .enabled(true).scope(OwnerScope.PLATFORM).build();
        Long id = modelConfigRepository.saveAndFlush(cfg).getId(); // 无上下文播种 → tenant_id IS NULL

        assertThatThrownBy(() -> withTenantContext(appA, () -> modelRouterService.getById(id)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(modelRouterService.getById(id).getApiKey()).isEqualTo("sk-platform-secret");
        // 自己播的平台行自己删：它没有 provider_id，会污染 ModelProviderIT 的「平台模型都已挂到连接」断言
        modelConfigRepository.deleteById(id);
    }

    @Test
    void 平台默认策略_租户改删被拒_行保持不变() {
        // 读放行平台行是为保多级回退；写路径若同样放行，租户即可篡改/删除平台默认，殃及全部租户
        Application appA = seedApp("guard-ps-" + System.nanoTime(), "http://ps.example.com");
        ChatStrategy platform = ChatStrategy.builder()
                .name("平台默认策略").scenario("guard-plat-" + System.nanoTime())
                .topK(5).similarityThreshold(0.7).allowNoKnowledge(true)
                .enabled(true).isDefault(false).build();
        Long id = strategyRepository.saveAndFlush(platform).getId(); // 无上下文播种 → tenant_id IS NULL

        // 读可见（回退语义）
        assertThat(withTenantContext(appA, () -> chatStrategyService.getById(id))).isNotNull();
        // 改被拒
        assertThatThrownBy(() -> withTenantContext(appA, () -> {
            ChatStrategy loaded = chatStrategyService.getById(id);
            loaded.setTopK(99);
            return chatStrategyService.saveStrategy(loaded);
        })).isInstanceOf(BusinessException.class);
        // 删被拒
        assertThatThrownBy(() -> withTenantContext(appA, () -> {
            chatStrategyService.deleteStrategy(id);
            return null;
        })).isInstanceOf(BusinessException.class);
        // 行仍在且未被篡改
        assertThat(strategyRepository.findById(id).orElseThrow().getTopK()).isEqualTo(5);
    }

    @Test
    void 激活模板_只影响本租户应用范围() {
        // deactivate 是 bulk UPDATE，不受 @Filter 保护——必须显式范围化，
        // 否则任一租户激活模板会清掉全库（含平台默认与其他租户）的激活位
        Application appA = seedApp("guard-pa-" + System.nanoTime(), "http://pa.example.com");
        Application appB = seedApp("guard-pb-" + System.nanoTime(), "http://pb.example.com");

        PromptTemplate oldA = PromptTemplate.builder()
                .name("A旧激活").content("c").version(101).isActive(true).build();
        ownBy(oldA, appA);
        PromptTemplate freshA = PromptTemplate.builder()
                .name("A新版本").content("c").version(102).isActive(false).build();
        ownBy(freshA, appA);
        PromptTemplate activeB = PromptTemplate.builder()
                .name("B激活中").content("c").version(103).isActive(true).build();
        ownBy(activeB, appB);
        PromptTemplate platformT = PromptTemplate.builder()
                .name("平台默认激活").content("c").version(104).isActive(true).build(); // 无上下文 → tenant NULL
        Long oldAId = promptTemplateRepository.saveAndFlush(oldA).getId();
        Long freshAId = promptTemplateRepository.saveAndFlush(freshA).getId();
        Long activeBId = promptTemplateRepository.saveAndFlush(activeB).getId();
        Long platformId = promptTemplateRepository.saveAndFlush(platformT).getId();

        withTenantContext(appA, () -> promptTemplateService.activateVersion(freshAId));

        assertThat(promptTemplateRepository.findById(freshAId).orElseThrow().getIsActive()).isTrue();
        assertThat(promptTemplateRepository.findById(oldAId).orElseThrow().getIsActive()).isFalse();
        // B 租户与平台默认的激活位不受殃及
        assertThat(promptTemplateRepository.findById(activeBId).orElseThrow().getIsActive()).isTrue();
        assertThat(promptTemplateRepository.findById(platformId).orElseThrow().getIsActive()).isTrue();
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

    // ===== 无归属资源（tenant_id / app_id 均 NULL）不得对租户开放 =====
    //
    // 上面那批覆盖的是「A 租户 vs B 租户」，这批覆盖的是另一维：**NULL 归属**。
    // 知识库/文档/技能都没有「平台默认」这一共享语义（无播种逻辑），库里出现 NULL 行只可能是
    // 平台管理员在无租户上下文时建的，或「tenantId 静默落 NULL」事故。用读级守卫会对其无条件
    // 放行——2026-09-02 实测：另一租户的管理员可读到平台私有文档正文、可将其**真的删除**、
    // 还能往平台私有知识库里插文档。判据见 TenantOwnershipGuard 类注释。
    //
    // ⚠️ 每条都同时断言「平台上下文（无 TenantContext）仍可读」——只钉「租户读不到」会让人
    // 把守卫改过头（比如无条件拒绝 NULL），那样平台管理员自己也进不去，而没有任何测试会红。

    @Test
    void 无归属知识库_租户不可见_平台上下文可读() {
        Application appA = seedApp("guard-nk-" + System.nanoTime(), "http://nk.example.com");
        // 刻意不 ownBy：模拟平台管理员无租户上下文时建库，tenant_id / app_id 双 NULL
        KnowledgeBase kb = KnowledgeBase.builder().name("平台私有库").description("d").enabled(true).build();
        Long id = knowledgeBaseRepository.saveAndFlush(kb).getId();

        assertThatThrownBy(() -> withTenantContext(appA, () -> knowledgeService.getKnowledgeBase(id)))
                .isInstanceOf(RuntimeException.class);
        assertThat(knowledgeService.getKnowledgeBase(id)).isNotNull();
    }

    @Test
    void 无归属文档_租户不可读也不可删_平台上下文可读() {
        Application appA = seedApp("guard-nd-" + System.nanoTime(), "http://nd.example.com");
        KnowledgeBase kb = KnowledgeBase.builder().name("平台私有库").description("d").enabled(true).build();
        KnowledgeBase savedKb = knowledgeBaseRepository.saveAndFlush(kb);
        Document doc = Document.builder()
                .knowledgeBase(savedKb).name("平台私有文档").originalFilename("p.txt")
                .documentType(DocumentType.TEXT).status(DocumentStatus.PENDING)
                .filePath("s3://p").storageKey("p").fileSize(1L).build();
        Long id = documentRepository.saveAndFlush(doc).getId();

        assertThatThrownBy(() -> withTenantContext(appA, () -> knowledgeService.getDocument(id)))
                .isInstanceOf(RuntimeException.class);
        // ⚠️ 删除单独钉一次：requireDocumentOwnership 被 get/delete/reprocess/status 四处共用，
        // 一处读级守卫等于同时放开读与删，而「能删」比「能读」严重得多。
        assertThatThrownBy(() -> withTenantContext(appA, () -> {
            knowledgeService.deleteDocument(id);
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(documentRepository.findById(id)).isPresent();
        assertThat(knowledgeService.getDocument(id)).isNotNull();
    }

    @Test
    void 无归属技能_租户不可见_平台上下文可读() {
        Application appA = seedApp("guard-ns-" + System.nanoTime(), "http://ns.example.com");
        // skill 的 content 注入每一轮系统提示，本仓库刻意不共享 skill，故 NULL 归属绝非共享意图
        com.qingyu.care.entity.AiSkill skill = new com.qingyu.care.entity.AiSkill();
        skill.setName("平台私有技能");
        skill.setContent("平台的提示词资产");
        skill.setEnabled(true);
        Long id = aiSkillRepository.saveAndFlush(skill).getId();

        assertThatThrownBy(() -> withTenantContext(appA, () -> chatToolService.getSkill(id)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(chatToolService.getSkill(id)).isNotNull();
    }
}

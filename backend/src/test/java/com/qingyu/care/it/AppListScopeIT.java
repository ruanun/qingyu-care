package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「应用级 list 越权收口」真实 DB 回归：验证 {@link ModelRouterService#getAllModels()} 与
 * {@link KnowledgeService#getAllKnowledgeBases()} 这两处管理面列表接口在真实 pgvector 容器 +
 * Hibernate {@code @Filter} 下确实按 appId 收窄，不会把他应用/他租户的数据带出来。
 *
 * <p>播种数据一律显式 set 实体的 tenantId/appId 字段后 saveAndFlush（不依赖 TenantContext 回填），
 * 只在调用被测方法前才设置 TenantContext，避免 Hibernate Filter 影响播种阶段的可见性判断。
 *
 * @author ruan
 */
class AppListScopeIT extends AbstractPostgresIT {

    @Autowired
    private ModelRouterService modelRouterService;

    @Autowired
    private AiModelConfigRepository aiModelConfigRepository;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @AfterEach
    void 清理上下文() {
        TenantContext.clear();
    }

    /**
     * getAllModels() = 当前应用自有(TENANT，@Filter 收窄) ∪ 平台共享(PLATFORM，原生查询绕过 @Filter)，
     * 且不能带出他应用/他租户的自有模型（含明文 apiKey，越权风险最高）。
     */
    @Test
    void getAllModels收窄至应用自有与平台共享不越权() {
        Long tenantA = 97101L;
        Long appA = 97102L;
        Long tenantB = 97103L;
        Long appB = 97104L;

        // ① 应用A自有模型（scope=TENANT）
        AiModelConfig ownA = AiModelConfig.builder()
                .name("IT-应用A自有模型")
                .provider("openai")
                .modelId("gpt-4o-mini")
                .endpoint("https://api.openai.com")
                .apiKey("key-app-a")
                .scenario("default")
                .priority(1)
                .enabled(true)
                .scope(OwnerScope.TENANT)
                .build();
        ownA.setTenantId(tenantA);
        ownA.setAppId(appA);
        aiModelConfigRepository.saveAndFlush(ownA);

        // ② 平台共享模型（scope=PLATFORM，tenant_id/app_id 均为 NULL）
        AiModelConfig shared = AiModelConfig.builder()
                .name("IT-平台共享模型-AppListScope")
                .provider("openai")
                .modelId("gpt-4o-mini")
                .endpoint("https://api.openai.com")
                .apiKey("key-shared")
                .scenario("default")
                .priority(2)
                .enabled(true)
                .scope(OwnerScope.PLATFORM)
                .build();
        aiModelConfigRepository.saveAndFlush(shared);

        // ③ 应用B（另一租户）自有模型，不应出现在应用A的结果中
        AiModelConfig ownB = AiModelConfig.builder()
                .name("IT-应用B自有模型")
                .provider("openai")
                .modelId("gpt-4o-mini")
                .endpoint("https://api.openai.com")
                .apiKey("key-app-b")
                .scenario("default")
                .priority(1)
                .enabled(true)
                .scope(OwnerScope.TENANT)
                .build();
        ownB.setTenantId(tenantB);
        ownB.setAppId(appB);
        aiModelConfigRepository.saveAndFlush(ownB);

        TenantContext.setTenantId(tenantA);
        TenantContext.setAppId(appA);

        List<AiModelConfig> models = modelRouterService.getAllModels();

        assertThat(models).extracting(AiModelConfig::getId)
                .contains(ownA.getId(), shared.getId())
                .doesNotContain(ownB.getId());
    }

    /**
     * getAllKnowledgeBases() 应按 appId 收窄，且管理面列表须包含禁用知识库
     * （禁用是可逆操作，禁用后仍要能在列表里看到并重新启用）。
     */
    @Test
    void getAllKnowledgeBases收窄至应用维度且含禁用库() {
        Long tenantA = 97201L;
        Long appA = 97202L;
        Long tenantB = 97203L;
        Long appB = 97204L;

        // 应用A：一个启用 + 一个禁用的知识库
        KnowledgeBase kbEnabled = KnowledgeBase.builder()
                .name("IT-应用A知识库-启用")
                .description("AppListScopeIT")
                .enabled(true)
                .build();
        kbEnabled.setTenantId(tenantA);
        kbEnabled.setAppId(appA);
        knowledgeBaseRepository.saveAndFlush(kbEnabled);

        KnowledgeBase kbDisabled = KnowledgeBase.builder()
                .name("IT-应用A知识库-禁用")
                .description("AppListScopeIT")
                .enabled(false)
                .build();
        kbDisabled.setTenantId(tenantA);
        kbDisabled.setAppId(appA);
        knowledgeBaseRepository.saveAndFlush(kbDisabled);

        // 应用B（另一租户）知识库，不应出现在应用A的结果中
        KnowledgeBase kbOther = KnowledgeBase.builder()
                .name("IT-应用B知识库")
                .description("AppListScopeIT")
                .enabled(true)
                .build();
        kbOther.setTenantId(tenantB);
        kbOther.setAppId(appB);
        knowledgeBaseRepository.saveAndFlush(kbOther);

        TenantContext.setTenantId(tenantA);
        TenantContext.setAppId(appA);

        List<KnowledgeBase> knowledgeBases = knowledgeService.getAllKnowledgeBases();

        assertThat(knowledgeBases).extracting(KnowledgeBase::getId)
                .contains(kbEnabled.getId(), kbDisabled.getId())
                .doesNotContain(kbOther.getId());
    }
}

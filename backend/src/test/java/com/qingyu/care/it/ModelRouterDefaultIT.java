package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计：ModelRouter @PostConstruct 种子行（scope=PLATFORM, tenant_id=NULL）在租户上下文中是否可见。
 *
 * 结论：AiModelConfig 继承 BaseTenantEntity，理论上 Hibernate 过滤器会过滤 tenant_id=NULL 的行。
 * 但 AiModelConfigRepository.findSharedModelsByScenario 已使用原生 SQL（@Query nativeQuery=true），
 * 原生查询不受 Hibernate @Filter 影响，因此 scope=PLATFORM 的种子行在租户上下文中也能被查到。
 * getModelsForScenario 的合并逻辑 mergeTenantAndShared 将平台共享模型包含在结果中。
 * 此机制在 Phase 2 设计时已经过修复，无需额外修改——本测试仅锁定该正确行为。
 *
 * 注意：本测试不使用 @MockitoBean，与 PromptTemplateFallbackIT/ChatStrategyFallbackIT 位于不同
 * Spring 上下文。两个上下文共用同一 Testcontainer 数据库，若另一上下文以 create-drop 重建 schema，
 * @PostConstruct 种子可能已消失。因此在 @BeforeEach 中直接确保平台种子存在，使测试自包含。
 *
 * @author ruan
 */
class ModelRouterDefaultIT extends AbstractPostgresIT {

    @Autowired
    private ModelRouterService modelRouterService;

    @Autowired
    private AiModelConfigRepository aiModelConfigRepository;

    /**
     * 确保平台默认模型种子存在。
     * 多 Spring 上下文共用同一 DB（create-drop）时，@PostConstruct 的种子可能已被另一上下文的
     * DDL 清除；在此直接写入，令本测试完全自包含，不依赖上下文初始化顺序。
     */
    @BeforeEach
    void 确保平台默认模型存在() {
        // 无租户上下文时原生查询可正常检索
        List<AiModelConfig> existing = aiModelConfigRepository.findSharedModelsByScenario("default");
        if (existing.isEmpty()) {
            aiModelConfigRepository.saveAndFlush(AiModelConfig.builder()
                    .name("IT-平台默认模型")
                    .provider("openai")
                    .modelId("gpt-4o-mini")
                    .endpoint("https://api.openai.com")
                    .apiKey("test-key")
                    .scenario("default")
                    .priority(1)
                    .enabled(true)
                    .scope(OwnerScope.PLATFORM)
                    .remark("ModelRouterDefaultIT 自播种的平台共享模型")
                    .build());
        }
    }

    @AfterEach
    void 清理上下文() {
        TenantContext.clear();
    }

    @Test
    void 租户无自有模型时平台默认模型可见() {
        // 租户 90401 / 应用 90402 没有自己的模型配置
        TenantContext.setTenantId(90401L);
        TenantContext.setAppId(90402L);

        List<AiModelConfig> models = modelRouterService.getModelsForScenario("default");

        // 平台共享模型通过原生查询绕过 Hibernate 过滤器，结果不应为空
        assertThat(models).isNotEmpty();
        // 种子行的 scope 应为 PLATFORM
        assertThat(models).anyMatch(m -> "PLATFORM".equals(m.getScope() == null ? null : m.getScope().name()));
    }

    @Test
    void 未知场景降级到default仍能获取平台模型() {
        TenantContext.setTenantId(90401L);
        TenantContext.setAppId(90402L);

        // "unknown" 场景无任何策略，getModelsForScenario 降级到 "default"
        List<AiModelConfig> models = modelRouterService.getModelsForScenario("unknown_scenario");

        assertThat(models).isNotEmpty();
    }
}

package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
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
 * @author ruan
 */
class ModelRouterDefaultIT extends AbstractPostgresIT {

    @Autowired
    private ModelRouterService modelRouterService;

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

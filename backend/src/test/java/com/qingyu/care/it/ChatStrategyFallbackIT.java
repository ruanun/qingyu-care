package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计：ChatStrategy @PostConstruct 种子行（tenant_id=NULL）在租户上下文中是否可见。
 *
 * 结论：ChatStrategy 继承 BaseTenantEntity，生产环境中 TenantDataAspect 启用
 * {@code tenant_id = :tenantId} 过滤器，无法匹配 NULL，种子行会被硬过滤掉。
 * 修复：ChatStrategyRepository 新增原生查询 findPlatformDefaultByScenario（绕过过滤器），
 * getActiveStrategy 在租户自有策略缺失时回退到平台默认（tenant_id IS NULL）。
 *
 * 回归锁定：findPlatformDefaultByScenario 直接验证原生查询能找到 tenant_id=NULL 的种子行；
 * 此查询方法一旦从 Repository 中删除，该测试立刻无法编译（硬性门卫）。
 *
 * @author ruan
 */
class ChatStrategyFallbackIT extends AbstractPostgresIT {

    @Autowired
    private ChatStrategyService chatStrategyService;

    @Autowired
    private ChatStrategyRepository chatStrategyRepository;

    @AfterEach
    void 清理上下文() {
        TenantContext.clear();
    }

    /**
     * 直接验证 findPlatformDefaultByScenario 原生查询能绕过 Hibernate 过滤器找到种子行。
     * 这是修复的核心方法：删除它则测试无法编译（编译期门卫）。
     */
    @Test
    void 原生查询findPlatformDefaultByScenario在租户上下文中可见平台种子() {
        TenantContext.setTenantId(90201L);
        TenantContext.setAppId(90202L);

        // 直接调���原生查询 — 此方法只有在修复存在时才能编译/运行
        Optional<ChatStrategy> result = chatStrategyRepository.findPlatformDefaultByScenario("default");

        assertThat(result).isPresent();
        assertThat(result.get().getTenantId()).isNull();          // 平台种子 tenant_id IS NULL
        assertThat(result.get().getScenario()).isEqualTo("default");
    }

    @Test
    void 租户无自有策略时回退平台默认_default场景() {
        // 租户 90201 / 应用 90202 没有自己的策略
        TenantContext.setTenantId(90201L);
        TenantContext.setAppId(90202L);

        ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");

        assertThat(strategy).isNotNull();
        // 命中平台种子行（tenant_id IS NULL），不是运行时硬编码兜底
        assertThat(strategy.getId()).isNotNull();
        assertThat(strategy.getScenario()).isEqualTo("default");
    }

    @Test
    void 租户无自有策略时回退平台默认_complaint场景() {
        TenantContext.setTenantId(90201L);
        TenantContext.setAppId(90202L);

        ChatStrategy strategy = chatStrategyService.getActiveStrategy("complaint");

        assertThat(strategy).isNotNull();
        assertThat(strategy.getId()).isNotNull();
        assertThat(strategy.getScenario()).isEqualTo("complaint");
    }

    @Test
    void 未知场景降级到default平台种子() {
        TenantContext.setTenantId(90201L);
        TenantContext.setAppId(90202L);

        // "unknown" 场景无任何策略，应最终降级到 default 平台种子
        ChatStrategy strategy = chatStrategyService.getActiveStrategy("unknown");

        assertThat(strategy).isNotNull();
        // 最终命中 default 场景（平台种子行 id 不为 null）
        assertThat(strategy.getScenario()).isEqualTo("default");
    }
}

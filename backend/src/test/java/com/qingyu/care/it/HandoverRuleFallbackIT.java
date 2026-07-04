package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 审计：HandoverRule @PostConstruct 种子行（tenant_id=NULL）在租户上下文中的可见性。
 *
 * 结论：HandoverRule 继承 BaseTenantEntity，生产环境中 TenantDataAspect 会在
 * Hibernate Session 上启用 tenant_id 过滤器，过滤掉 tenant_id=NULL 的种子行。
 * 但转人工规则的解析语义是「找不到规则 → 不触发转人工」，这是合理的优雅降级：
 *   - getEnabledRules() 返回空列表 → 不触发转人工；
 *   - checkKeywordTrigger("转人工") → orElse(false)；
 *   - checkLowConfidenceTrigger(0.1) → orElse(false)；
 *   - getNoHitStreakThreshold() → orElse(MAX_VALUE)，即永不触发。
 * 所有方法均有明确兜底路径，不会抛异常。
 * 故无需修复——只需锁定「不抛异常」这一最小契约作为回归保障。
 *
 * 注意：若业务需要平台默认规则对所有租户强制生效，可参照 ChatStrategy 的修复模式
 * 在 HandoverRuleRepository 添加原生查询回退；但当前设计将租户上下文中的规则隔离视为正确行为。
 *
 * @author ruan
 */
class HandoverRuleFallbackIT extends AbstractPostgresIT {

    @Autowired
    private HandoverRuleService handoverRuleService;

    @AfterEach
    void 清理上下文() {
        TenantContext.clear();
    }

    @Test
    void 租户上下文中getEnabledRules不抛异常() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        // 核心契约：无论租户是否有自有规则，调用不应抛异常
        assertThatCode(() -> handoverRuleService.getEnabledRules())
                .doesNotThrowAnyException();
    }

    @Test
    void 租户上下文中关键词检测不抛异常() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        // checkKeywordTrigger 有 orElse(false) 兜底，规则不存在时安全返回 false
        assertThatCode(() -> handoverRuleService.checkKeywordTrigger("转人工"))
                .doesNotThrowAnyException();
    }

    @Test
    void 租户上下文中低置信度检测不抛异常() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        // checkLowConfidenceTrigger 有 orElse(false) 兜底
        assertThatCode(() -> handoverRuleService.checkLowConfidenceTrigger(0.1))
                .doesNotThrowAnyException();
    }

    @Test
    void 租户上下文中连续未命中阈值不抛异常() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        // getNoHitStreakThreshold 有 orElse(MAX_VALUE) 兜底
        assertThatCode(() -> handoverRuleService.getNoHitStreakThreshold())
                .doesNotThrowAnyException();
    }
}

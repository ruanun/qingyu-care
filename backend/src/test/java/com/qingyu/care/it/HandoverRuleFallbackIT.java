package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import com.qingyu.care.repository.HandoverRuleRepository;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转人工触发规则平台默认回退（真库）：验证租户上下文下，三个运行时触发读取
 * （关键词/低置信/连续未命中）能经 native 回退看到 tenant_id IS NULL 的平台默认规则、
 * 正确触发——而非被多租户 @Filter 挡掉后静默降级为「不触发」。
 *
 * 背景：此前审计曾把「租户上下文查不到平台默认 → 不触发」当作合理优雅降级、判定无需修复；
 * 但真机冒烟证明这使「转人工」关键词对所有租户从不生效（打转人工不转接、落 AI）。故本 IT
 * 断言修正后的正确行为，作为该缺陷的回归网（原 IT 只测「不抛异常」，测了安全降级而非正确触发）。
 *
 * @Transactional（与 ChatStrategyFallbackIT/其「平台默认hybrid字段_对租户可见_经native回退」用例同一手法）：
 * 本 IT 不经 MockMvc/真实 HTTP，仅手动设置 TenantContext 后直调 service；TenantDataAspect 在
 * repository 调用前才 enableFilter，若此前线程没有已绑定的 Hibernate Session/事务，Spring Data
 * 各 repository 方法各自开临时事务，enableFilter 落到的 Session 与实际执行查询的 Session 不是
 * 同一个，过滤器形同虚设（曾实测：不加 @Transactional 时 findByRuleType 因未过滤命中 2 行 NULL
 * 种子——@PostConstruct 播种的默认行 + 本 IT 播种的行——抛 NonUniqueResultException）。加
 * @Transactional 使调用前先绑定好事务/Session，enableFilter 与后续查询同一 Session，过滤器才能
 * 真正生效、走到 native 回退，这才是本 IT 要验证的目标路径；真实 HTTP 请求下 Spring 的
 * Open-Session-In-View 提前绑定了 Session，不受此限。
 *
 * @author ruan
 */
@Transactional
class HandoverRuleFallbackIT extends AbstractPostgresIT {

    private static final String MARK = "IT回退平台默认";

    @Autowired
    private HandoverRuleService handoverRuleService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private HandoverRuleRepository handoverRuleRepository;

    /** native 播种一条 tenant_id IS NULL 平台默认规则（绕过 JPA 的 TenantEntityListener 回填）。 */
    private void seedPlatformDefault(String ruleType, String config) {
        jdbcTemplate.update(
                "INSERT INTO handover_rules(rule_type, name, description, enabled, config, priority, " +
                        "tenant_id, app_id, created_at, updated_at) " +
                        "VALUES (?, ?, 'it', true, ?::text, 100, NULL, NULL, now(), now())",
                ruleType, MARK + ruleType, config);
    }

    @BeforeEach
    void seed() {
        seedPlatformDefault("KEYWORD", "{\"keywords\": [\"转人工\"]}");
        seedPlatformDefault("LOW_CONFIDENCE", "{\"threshold\": 0.3}");
        seedPlatformDefault("NO_HIT_STREAK", "{\"count\": 3}");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbcTemplate.update("DELETE FROM handover_rules WHERE name LIKE ?", MARK + "%");
    }

    @Test
    void 租户上下文_关键词经平台默认正确触发() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        assertThat(handoverRuleService.checkKeywordTrigger("我要转人工")).isTrue();
    }

    @Test
    void 租户上下文_低置信经平台默认正确触发() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        assertThat(handoverRuleService.checkLowConfidenceTrigger(0.1)).isTrue();
    }

    @Test
    void 租户上下文_连续未命中阈值经平台默认为3而非禁用() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        assertThat(handoverRuleService.getNoHitStreakThreshold()).isEqualTo(3);
    }

    /**
     * 平台管理员场景（无过滤器）：handover_rules 表内同类型（rule_type='KEYWORD'）存在多个租户
     * 各一行 + 一行平台默认（@BeforeEach 播种），裸 findByRuleType 会因过滤器缺失同时看到全部
     * 行而 NonUniqueResultException。修复后经 ConfigScopeResolver 按 X-App-Id 反查出应用 A
     * 归属的租户 T，用 findByRuleTypeScoped 显式收窄，checkKeywordTrigger 必须命中租户 T 自己
     * 的规则——而非另一租户 T2 的规则，也不是平台默认（验证「解析到正确租户」而非仅「不抛
     * 异常」，避免重蹈本 IT 曾把静默失效误判为合理降级、只测"不抛"的教训）。
     */
    @Test
    void 平台管理员无过滤器_多租户同类型规则_精确解析到应用归属租户() {
        Application appA = seedApp("handover_scope_a", "http://it.example.com");
        Long tenantT = appA.getTenant().getId();

        // 另一租户 T2，同 rule_type='KEYWORD' 键值，制造 NonUnique 触发条件
        Application appB = seedApp("handover_scope_b", "http://it.example.com");
        Long tenantT2 = appB.getTenant().getId();

        HandoverRule ownT = HandoverRule.builder()
                .ruleType(HandoverRuleType.KEYWORD)
                .name("租户T自有关键词规则")
                .enabled(true)
                .config("{\"keywords\":[\"退货\"]}")
                .build();
        ownT.setTenantId(tenantT);
        ownT.setAppId(appA.getId());
        handoverRuleRepository.saveAndFlush(ownT);

        HandoverRule ownT2 = HandoverRule.builder()
                .ruleType(HandoverRuleType.KEYWORD)
                .name("租户T2自有关键词规则")
                .enabled(true)
                .config("{\"keywords\":[\"退款\"]}")
                .build();
        ownT2.setTenantId(tenantT2);
        ownT2.setAppId(appB.getId());
        handoverRuleRepository.saveAndFlush(ownT2);

        // 平台管理员：无租户上下文，仅带 X-App-Id（模拟平台管理员选中应用 A 后的会话态）
        TenantContext.setTenantId(null);
        TenantContext.setAppId(appA.getId());

        // 命中租户 T 自有规则关键词「退货」，不抛 NonUniqueResultException
        assertThat(handoverRuleService.checkKeywordTrigger("我要退货")).isTrue();
        // 租户 T2 的关键词「退款」不应被命中——证明解析到的确实是租户 T 的规则而非误配/合并
        assertThat(handoverRuleService.checkKeywordTrigger("我要退款")).isFalse();
    }
}

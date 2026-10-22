package com.qingyu.care.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.PlatformSeeder;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.HandoverRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HandoverRuleService {

    private final HandoverRuleRepository repository;
    private final ObjectMapper objectMapper;
    private final ConfigScopeResolver configScopeResolver;

    /**
     * 初始化默认规则
     */
    @PostConstruct
    @Transactional
    public void init() {
        PlatformSeeder.seedIfEmpty(repository.count(), "转人工规则", () -> {
            // 关键词规则
            repository.save(HandoverRule.builder()
                    .ruleType(HandoverRuleType.KEYWORD)
                    .name("关键词触发")
                    .description("用户消息包含特定关键词时触发转人工")
                    .enabled(true)
                    .config("{\"keywords\": [\"转人工\", \"找客服\", \"人工服务\", \"人工客服\"]}")
                    .priority(1)
                    .build());

            // 低置信度规则
            repository.save(HandoverRule.builder()
                    .ruleType(HandoverRuleType.LOW_CONFIDENCE)
                    .name("低置信度触发")
                    .description("AI 回答置信度低于阈值时触发转人工")
                    .enabled(true)
                    .config("{\"threshold\": 0.3}")
                    .priority(2)
                    .build());

            // 连续未命中规则
            repository.save(HandoverRule.builder()
                    .ruleType(HandoverRuleType.NO_HIT_STREAK)
                    .name("连续未命中触发")
                    .description("连续多次知识库无匹配内容时触发转人工")
                    .enabled(true)
                    .config("{\"count\": 3}")
                    .priority(3)
                    .build());
        });
    }

    /**
     * 获取所有规则（管理面）：租户自有(按 tenantId) ∪ 平台默认(tenant_id IS NULL)，按 id 去重。
     * TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，禁止裸 findAll；无 tenantId
     * （平台管理员）时自有部分为空，但平台默认规则仍须可见——顺带修复租户此前看不到平台默认
     * 规则的 bug（NULL 行不匹配 tenantFilter）。
     */
    public List<HandoverRule> getAllRules() {
        Long tenantId = TenantContext.getTenantId();
        List<HandoverRule> own = tenantId == null ? List.of() : repository.findByTenantIdOrderByPriorityAsc(tenantId);
        List<HandoverRule> platformDefaults = repository.findAllPlatformDefaults();
        return mergeById(own, platformDefaults);
    }

    private List<HandoverRule> mergeById(List<HandoverRule> own, List<HandoverRule> platformDefaults) {
        java.util.LinkedHashMap<Long, HandoverRule> byId = new java.util.LinkedHashMap<>();
        for (HandoverRule r : own) byId.putIfAbsent(r.getId(), r);
        for (HandoverRule r : platformDefaults) byId.putIfAbsent(r.getId(), r);
        return new java.util.ArrayList<>(byId.values());
    }

    /**
     * 获取启用的规则
     */
    public List<HandoverRule> getEnabledRules() {
        return repository.findByEnabledTrueOrderByPriorityAsc();
    }

    /**
     * 获取指定规则
     */
    public HandoverRule getById(Long id) {
        HandoverRule rule = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("转人工规则", id.toString()));
        // findById 不受 @Filter 保护，显式校验归属，不匹配按「不存在」同语义处理防枚举探测
        if (!TenantOwnershipGuard.belongsToCurrentContext(rule.getTenantId(), rule.getAppId())) {
            throw new ResourceNotFoundException("转人工规则", id.toString());
        }
        return rule;
    }

    /**
     * 更新规则
     */
    @Transactional
    public HandoverRule updateRule(Long id, Boolean enabled, String config) {
        HandoverRule rule = getById(id);
        // 写路径守卫：平台默认规则（tenant_id IS NULL 种子行）仅平台管理员可改，
        // 否则租户可枚举 id 篡改平台默认触发条件，殃及全部租户
        if (!TenantOwnershipGuard.mutableInCurrentContext(rule.getTenantId(), rule.getAppId())) {
            throw new BusinessException(403, "平台默认规则仅平台管理员可修改");
        }
        if (enabled != null) {
            rule.setEnabled(enabled);
        }
        if (config != null) {
            rule.setConfig(config);
        }
        log.info("Updated handover rule: {} (enabled={}, config={})", rule.getName(), enabled, config);
        return repository.save(rule);
    }

    /**
     * 解析生效规则：显式按 {@link ConfigScopeResolver} 解析出的有效 (tenantId, appId) 收窄取
     * 租户自有规则（不依赖 Hibernate @Filter，避免平台管理员绕过过滤器裸扫全库 NonUnique）；
     * 无自有则回退平台默认（native 绕过 @Filter 取 tenant_id IS NULL）。
     * 闭合「平台默认被多租户过滤器挡掉致触发对租户静默失效」缺陷（同 getAllRules 范式）。
     */
    private java.util.Optional<HandoverRule> resolveActiveRule(HandoverRuleType type) {
        ConfigScopeResolver.Scope scope = configScopeResolver.resolve();
        java.util.Optional<HandoverRule> own = scope.tenantId() == null
                ? java.util.Optional.empty()
                : repository.findByRuleTypeScoped(type, scope.tenantId(), scope.appId()).stream().findFirst();
        return own.isPresent() ? own : repository.findPlatformDefaultByRuleType(type.name());
    }

    /**
     * 检查关键词触发
     */
    public boolean checkKeywordTrigger(String message) {
        return resolveActiveRule(HandoverRuleType.KEYWORD)
                .filter(HandoverRule::getEnabled)
                .map(rule -> {
                    try {
                        JsonNode config = objectMapper.readTree(rule.getConfig());
                        JsonNode keywords = config.get("keywords");
                        if (keywords != null && keywords.isArray()) {
                            for (JsonNode keyword : keywords) {
                                if (message.contains(keyword.asString())) {
                                    log.info("Keyword trigger matched: {}", keyword.asString());
                                    return true;
                                }
                            }
                        }
                    } catch (JacksonException e) {
                        log.error("Failed to parse keyword config", e);
                    }
                    return false;
                })
                .orElse(false);
    }

    /**
     * 检查低置信度触发
     */
    public boolean checkLowConfidenceTrigger(double confidence) {
        return resolveActiveRule(HandoverRuleType.LOW_CONFIDENCE)
                .filter(HandoverRule::getEnabled)
                .map(rule -> {
                    try {
                        JsonNode config = objectMapper.readTree(rule.getConfig());
                        double threshold = config.get("threshold").asDouble(0.3);
                        if (confidence < threshold) {
                            log.info("Low confidence trigger: {} < {}", confidence, threshold);
                            return true;
                        }
                    } catch (JacksonException e) {
                        log.error("Failed to parse low confidence config", e);
                    }
                    return false;
                })
                .orElse(false);
    }

    /**
     * 获取连续未命中触发阈值
     */
    public int getNoHitStreakThreshold() {
        return resolveActiveRule(HandoverRuleType.NO_HIT_STREAK)
                .filter(HandoverRule::getEnabled)
                .map(rule -> {
                    try {
                        JsonNode config = objectMapper.readTree(rule.getConfig());
                        return config.get("count").asInt(3);
                    } catch (JacksonException e) {
                        log.error("Failed to parse no hit streak config", e);
                        return 3;
                    }
                })
                .orElse(Integer.MAX_VALUE); // 禁用时返回极大值
    }
}

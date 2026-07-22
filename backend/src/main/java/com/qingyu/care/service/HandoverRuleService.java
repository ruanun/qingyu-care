package com.qingyu.care.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * 初始化默认规则
     */
    @PostConstruct
    @Transactional
    public void init() {
        if (repository.count() == 0) {
            log.info("Initializing default handover rules...");
            
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
            
            log.info("Default handover rules created");
        }
    }

    /**
     * 获取所有规则
     */
    public List<HandoverRule> getAllRules() {
        return repository.findAllByOrderByPriorityAsc();
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
     * 检查关键词触发
     */
    public boolean checkKeywordTrigger(String message) {
        return repository.findByRuleType(HandoverRuleType.KEYWORD)
                .filter(HandoverRule::getEnabled)
                .map(rule -> {
                    try {
                        JsonNode config = objectMapper.readTree(rule.getConfig());
                        JsonNode keywords = config.get("keywords");
                        if (keywords != null && keywords.isArray()) {
                            for (JsonNode keyword : keywords) {
                                if (message.contains(keyword.asText())) {
                                    log.info("Keyword trigger matched: {}", keyword.asText());
                                    return true;
                                }
                            }
                        }
                    } catch (JsonProcessingException e) {
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
        return repository.findByRuleType(HandoverRuleType.LOW_CONFIDENCE)
                .filter(HandoverRule::getEnabled)
                .map(rule -> {
                    try {
                        JsonNode config = objectMapper.readTree(rule.getConfig());
                        double threshold = config.get("threshold").asDouble(0.3);
                        if (confidence < threshold) {
                            log.info("Low confidence trigger: {} < {}", confidence, threshold);
                            return true;
                        }
                    } catch (JsonProcessingException e) {
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
        return repository.findByRuleType(HandoverRuleType.NO_HIT_STREAK)
                .filter(HandoverRule::getEnabled)
                .map(rule -> {
                    try {
                        JsonNode config = objectMapper.readTree(rule.getConfig());
                        return config.get("count").asInt(3);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to parse no hit streak config", e);
                        return 3;
                    }
                })
                .orElse(Integer.MAX_VALUE); // 禁用时返回极大值
    }
}

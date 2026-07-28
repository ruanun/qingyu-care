package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import com.qingyu.care.repository.HandoverRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转人工触发规则平台默认回退单测：租户上下文下 findByRuleType 被 @Filter 挡空时，
 * 三个触发方法须经 native 平台默认回退正确触发（原 bug：静默不触发）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class HandoverTriggerFallbackTest {

    @Mock HandoverRuleRepository repository;
    HandoverRuleService service;

    @BeforeEach
    void setUp() {
        // 用真 ObjectMapper 解析 config（服务内部 readTree），只 mock 仓库
        service = new HandoverRuleService(repository, new ObjectMapper());
    }

    private HandoverRule rule(HandoverRuleType type, boolean enabled, String config) {
        return HandoverRule.builder().ruleType(type).name("t").enabled(enabled).config(config).build();
    }

    @Test
    void 自有空_回退平台默认_关键词触发() {
        when(repository.findByRuleType(HandoverRuleType.KEYWORD)).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultByRuleType("KEYWORD"))
                .thenReturn(Optional.of(rule(HandoverRuleType.KEYWORD, true, "{\"keywords\":[\"转人工\"]}")));

        assertThat(service.checkKeywordTrigger("我要转人工")).isTrue();
    }

    @Test
    void 自有存在_用自有_不查平台默认() {
        when(repository.findByRuleType(HandoverRuleType.KEYWORD))
                .thenReturn(Optional.of(rule(HandoverRuleType.KEYWORD, true, "{\"keywords\":[\"人工客服\"]}")));

        assertThat(service.checkKeywordTrigger("请转人工客服")).isTrue();
        verify(repository, never()).findPlatformDefaultByRuleType(any());
    }

    @Test
    void 平台默认停用_安全默认不触发() {
        when(repository.findByRuleType(HandoverRuleType.KEYWORD)).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultByRuleType("KEYWORD"))
                .thenReturn(Optional.of(rule(HandoverRuleType.KEYWORD, false, "{\"keywords\":[\"转人工\"]}")));

        assertThat(service.checkKeywordTrigger("我要转人工")).isFalse();
    }

    @Test
    void 自有空_回退平台默认_低置信触发() {
        when(repository.findByRuleType(HandoverRuleType.LOW_CONFIDENCE)).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultByRuleType("LOW_CONFIDENCE"))
                .thenReturn(Optional.of(rule(HandoverRuleType.LOW_CONFIDENCE, true, "{\"threshold\":0.3}")));

        assertThat(service.checkLowConfidenceTrigger(0.1)).isTrue();
    }

    @Test
    void 自有空_回退平台默认_连续未命中阈值() {
        when(repository.findByRuleType(HandoverRuleType.NO_HIT_STREAK)).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultByRuleType("NO_HIT_STREAK"))
                .thenReturn(Optional.of(rule(HandoverRuleType.NO_HIT_STREAK, true, "{\"count\":3}")));

        assertThat(service.getNoHitStreakThreshold()).isEqualTo(3);
    }
}

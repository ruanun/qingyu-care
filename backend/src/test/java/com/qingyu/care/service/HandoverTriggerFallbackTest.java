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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转人工触发规则平台默认回退单测：租户上下文下 findByRuleTypeScoped 被有效作用域收窄挡空时，
 * 三个触发方法须经 native 平台默认回退正确触发（原 bug：静默不触发）；同时验证平台管理员
 * （有有效租户作用域）能确定性取到自有规则，不再依赖 filter-bound findByRuleType 裸扫全库
 * （原 bug：NonUniqueResultException）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class HandoverTriggerFallbackTest {

    @Mock HandoverRuleRepository repository;
    @Mock ConfigScopeResolver configScopeResolver;
    HandoverRuleService service;

    @BeforeEach
    void setUp() {
        // 用真 ObjectMapper 解析 config（服务内部 readTree），只 mock 仓库与作用域解析器
        service = new HandoverRuleService(repository, new ObjectMapper(), configScopeResolver);
    }

    private HandoverRule rule(HandoverRuleType type, boolean enabled, String config) {
        return HandoverRule.builder().ruleType(type).name("t").enabled(enabled).config(config).build();
    }

    @Test
    void 自有空_回退平台默认_关键词触发() {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(null, null));
        when(repository.findPlatformDefaultByRuleType("KEYWORD"))
                .thenReturn(Optional.of(rule(HandoverRuleType.KEYWORD, true, "{\"keywords\":[\"转人工\"]}")));

        assertThat(service.checkKeywordTrigger("我要转人工")).isTrue();
    }

    @Test
    void 自有存在_用自有_不查平台默认() {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        when(repository.findByRuleTypeScoped(HandoverRuleType.KEYWORD, 4L, 3L))
                .thenReturn(java.util.List.of(rule(HandoverRuleType.KEYWORD, true, "{\"keywords\":[\"人工客服\"]}")));

        assertThat(service.checkKeywordTrigger("请转人工客服")).isTrue();
        verify(repository, never()).findPlatformDefaultByRuleType(any());
    }

    @Test
    void 平台默认停用_安全默认不触发() {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(null, null));
        when(repository.findPlatformDefaultByRuleType("KEYWORD"))
                .thenReturn(Optional.of(rule(HandoverRuleType.KEYWORD, false, "{\"keywords\":[\"转人工\"]}")));

        assertThat(service.checkKeywordTrigger("我要转人工")).isFalse();
    }

    @Test
    void 自有空_回退平台默认_低置信触发() {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(null, null));
        when(repository.findPlatformDefaultByRuleType("LOW_CONFIDENCE"))
                .thenReturn(Optional.of(rule(HandoverRuleType.LOW_CONFIDENCE, true, "{\"threshold\":0.3}")));

        assertThat(service.checkLowConfidenceTrigger(0.1)).isTrue();
    }

    @Test
    void 自有空_回退平台默认_连续未命中阈值() {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(null, null));
        when(repository.findPlatformDefaultByRuleType("NO_HIT_STREAK"))
                .thenReturn(Optional.of(rule(HandoverRuleType.NO_HIT_STREAK, true, "{\"count\":3}")));

        assertThat(service.getNoHitStreakThreshold()).isEqualTo(3);
    }

    @Test
    void checkKeywordTrigger_有作用域_按tenantId取自有规则命中() {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        HandoverRule own = HandoverRule.builder().ruleType(HandoverRuleType.KEYWORD)
                .enabled(true).config("{\"keywords\":[\"转人工\"]}").build();
        when(repository.findByRuleTypeScoped(HandoverRuleType.KEYWORD, 4L, 3L))
                .thenReturn(java.util.List.of(own));

        assertThat(service.checkKeywordTrigger("我要转人工")).isTrue();
        verify(repository, never()).findPlatformDefaultByRuleType(anyString());
    }

    @Test
    void checkKeywordTrigger_作用域无租户_落平台默认() {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(null, 3L));
        HandoverRule platform = HandoverRule.builder().ruleType(HandoverRuleType.KEYWORD)
                .enabled(true).config("{\"keywords\":[\"人工\"]}").build();
        when(repository.findPlatformDefaultByRuleType("KEYWORD"))
                .thenReturn(Optional.of(platform));

        assertThat(service.checkKeywordTrigger("请转人工")).isTrue();
        verify(repository, never()).findByRuleTypeScoped(any(), any(), any());
    }
}

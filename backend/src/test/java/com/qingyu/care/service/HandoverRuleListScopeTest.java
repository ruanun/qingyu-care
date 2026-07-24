package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import com.qingyu.care.repository.HandoverRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HandoverRuleService.getAllRules() 的应用级收窄单测：TenantDataAspect 对 PLATFORM_ADMIN
 * 整体不启用 @Filter，原先裸 findAllByOrderByPriorityAsc 会让平台管理员跨租户读全平台规则；
 * 同时顺带修复一个既有 bug——租户管理员此前看不到平台默认规则（NULL 行不匹配 tenantFilter）。
 * 收窄为「租户自有(按 tenantId) ∪ 平台默认(tenant_id IS NULL，原生查询)」，按 id 去重。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class HandoverRuleListScopeTest {

    @Mock HandoverRuleRepository repository;
    @Mock ObjectMapper objectMapper;
    @InjectMocks HandoverRuleService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private HandoverRule rule(Long id, Long tenantId) {
        HandoverRule r = HandoverRule.builder().id(id).name("r" + id)
                .ruleType(HandoverRuleType.KEYWORD).enabled(true).build();
        r.setTenantId(tenantId);
        return r;
    }

    @Test
    void getAllRules_有tenantId_含自有与平台默认() {
        TenantContext.setTenantId(2L);
        HandoverRule own = rule(1L, 2L);
        HandoverRule platformDefault = rule(2L, null);
        when(repository.findByTenantIdOrderByPriorityAsc(2L)).thenReturn(List.of(own));
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(platformDefault));

        List<HandoverRule> result = service.getAllRules();

        assertThat(result).extracting(HandoverRule::getId).containsExactly(1L, 2L);
        verify(repository, never()).findAllByOrderByPriorityAsc();
    }

    @Test
    void getAllRules_无tenantId_平台默认仍可见_自有查询不被调用() {
        HandoverRule platformDefault = rule(2L, null);
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(platformDefault));

        List<HandoverRule> result = service.getAllRules();

        assertThat(result).extracting(HandoverRule::getId).containsExactly(2L);
        verify(repository, never()).findByTenantIdOrderByPriorityAsc(any());
        verify(repository, never()).findAllByOrderByPriorityAsc();
    }

    @Test
    void getAllRules_按id去重() {
        TenantContext.setTenantId(2L);
        HandoverRule own = rule(1L, 2L);
        HandoverRule dup = rule(1L, null);
        when(repository.findByTenantIdOrderByPriorityAsc(2L)).thenReturn(List.of(own));
        when(repository.findAllPlatformDefaults()).thenReturn(List.of(dup));

        List<HandoverRule> result = service.getAllRules();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(own);
    }
}

package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.HandoverRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * HandoverRuleService.getById 的租户归属校验单测。
 * getById 走 findById（不受 @Filter 保护），未校验归属时租户可枚举 id 跨租户读/改他人转接规则。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class HandoverRuleOwnershipTest {

    @Mock HandoverRuleRepository repository;
    @Mock ObjectMapper objectMapper;
    @InjectMocks HandoverRuleService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private HandoverRule ownedBy(Long tenantId) {
        HandoverRule r = HandoverRule.builder().id(1L).name("他租户规则")
                .ruleType(HandoverRuleType.KEYWORD).enabled(true).build();
        r.setTenantId(tenantId);
        return r;
    }

    @Test
    void getById_他租户规则_按不存在处理() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_本租户规则_正常返回() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(2L)));
        TenantContext.setTenantId(2L);

        assertThat(service.getById(1L)).isNotNull();
    }

    @Test
    void getById_平台共享规则_租户可见() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));
        TenantContext.setTenantId(2L);

        assertThat(service.getById(1L)).isNotNull();
    }
}

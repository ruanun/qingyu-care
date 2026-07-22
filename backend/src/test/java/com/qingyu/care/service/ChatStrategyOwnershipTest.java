package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
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
 * ChatStrategyService.getById 的租户归属校验单测。
 * getById 走 findById（不受 @Filter 保护），未校验归属时租户可枚举 id 跨租户读/改/删他人策略。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatStrategyOwnershipTest {

    @Mock ChatStrategyRepository repository;
    @Mock AbExperimentRepository abExperimentRepository;
    @InjectMocks ChatStrategyService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatStrategy ownedBy(Long tenantId) {
        ChatStrategy s = ChatStrategy.builder().id(1L).name("他租户策略").scenario("faq").enabled(true).build();
        s.setTenantId(tenantId);
        return s;
    }

    @Test
    void getById_他租户策略_按不存在处理() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(9L)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_本租户策略_正常返回() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(2L)));
        TenantContext.setTenantId(2L);

        assertThat(service.getById(1L)).isNotNull();
    }

    @Test
    void getById_平台共享策略_租户可见() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));
        TenantContext.setTenantId(2L);

        assertThat(service.getById(1L)).isNotNull();
    }
}

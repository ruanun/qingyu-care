package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.exception.BusinessException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // ---- 写路径守卫：读放行平台行是为保多级回退，改/删必须收紧 ----

    @Test
    void saveStrategy_租户改平台默认策略_拒绝() {
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.saveStrategy(ownedBy(null)))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void deleteStrategy_租户删平台默认策略_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(ownedBy(null)));
        TenantContext.setTenantId(2L);

        assertThatThrownBy(() -> service.deleteStrategy(1L))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void saveStrategy_平台管理员设默认_只清平台范围默认位() {
        // 平台管理员绕过租户过滤器，findAll 可见全库策略；
        // 取消默认位必须限定在被保存策略自身的租户范围内，不得殃及其他租户
        ChatStrategy saving = ownedBy(null);
        saving.setIsDefault(true);

        ChatStrategy platformPeer = ChatStrategy.builder().id(2L).name("平台同场景旧默认")
                .scenario("faq").isDefault(true).enabled(true).build();
        ChatStrategy tenantPeer = ChatStrategy.builder().id(3L).name("某租户同场景默认")
                .scenario("faq").isDefault(true).enabled(true).build();
        tenantPeer.setTenantId(5L);
        when(repository.findAll()).thenReturn(java.util.List.of(platformPeer, tenantPeer));

        service.saveStrategy(saving);

        assertThat(platformPeer.getIsDefault()).isFalse();
        assertThat(tenantPeer.getIsDefault()).isTrue();
    }
}

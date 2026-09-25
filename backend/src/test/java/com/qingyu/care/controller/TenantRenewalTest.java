package com.qingyu.care.controller;

import com.qingyu.care.dto.TenantUpdateRequest;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.SessionRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到期租户的续费体验：到期任务会把租户置为 EXPIRED，平台管理员续费时不该还要多一步、也不该「点了没用」。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class TenantRenewalTest {

    @Mock TenantRepository tenantRepository;
    @Mock AuthService authService;
    @Mock SessionRevocationService sessionRevocationService;
    @InjectMocks TenantController controller;

    @BeforeEach
    void setUp() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        lenient().when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Tenant expired() {
        return Tenant.builder().id(5L).name("过期租户").status(TenantStatus.EXPIRED)
                .expireTime(LocalDateTime.now().minusDays(3)).build();
    }

    @Test
    void 延长到期时间后自动恢复为正常_且不吊销登录态() {
        when(tenantRepository.findById(5L)).thenReturn(Optional.of(expired()));
        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setExpireTime(LocalDateTime.now().plusYears(1));

        Tenant saved = controller.update(5L, req).getData();

        assertThat(saved.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        verify(sessionRevocationService, never()).revokeTenant(any(), any());
    }

    @Test
    void 请求里显式给了状态时以请求为准() {
        when(tenantRepository.findById(5L)).thenReturn(Optional.of(expired()));
        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setExpireTime(LocalDateTime.now().plusYears(1));
        req.setStatus(TenantStatus.FROZEN);

        assertThat(controller.update(5L, req).getData().getStatus()).isEqualTo(TenantStatus.FROZEN);
    }

    @Test
    void 只改联系人等字段不会让过期租户复活() {
        when(tenantRepository.findById(5L)).thenReturn(Optional.of(expired()));
        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setContactName("新联系人");

        assertThat(controller.update(5L, req).getData().getStatus()).isEqualTo(TenantStatus.EXPIRED);
    }

    /** 不拦的话恢复后下一轮到期扫描又把它停掉，管理员会以为按钮没生效 */
    @Test
    void 到期时间未延长时不允许恢复激活() {
        when(tenantRepository.findById(5L)).thenReturn(Optional.of(expired()));

        assertThatThrownBy(() -> controller.toggleStatus(5L))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("延长到期时间");
        verify(tenantRepository, never()).save(any());
    }
}

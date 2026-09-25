package com.qingyu.care.service;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.dto.LoginRequest;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.exception.BadCredentialsException;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 登录必须校验租户到期时间，不只校验 {@code status}（安全审查 M6，2026-09-03）：
 * 套餐 {@code expireTime} 过了却仍能登录，只有平台管理员手动切 status 才生效。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceLoginTest {

    @Mock
    UserRepository userRepository;
    @Mock
    TenantRepository tenantRepository;
    @Mock
    ConfigService configService;
    @Mock
    AuditLogService auditLogService;
    @InjectMocks
    AuthService authService;

    private static User userOf(Tenant tenant) {
        return User.builder().id(1L).username("u").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).tenant(tenant).build();
    }

    private static LoginRequest request() {
        LoginRequest r = new LoginRequest();
        r.setUsername("u");
        r.setPassword("pwd123456");
        return r;
    }

    @Test
    void 租户已到期时拒绝登录() {
        Tenant expired = Tenant.builder().id(2L).status(TenantStatus.ACTIVE)
                .expireTime(LocalDateTime.now().minusDays(1)).build();
        when(userRepository.findByUsernameAcrossTenants("u")).thenReturn(Optional.of(userOf(expired)));

        assertThatThrownBy(() -> authService.login(request()))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void 租户已冻结时拒绝登录() {
        Tenant frozen = Tenant.builder().id(2L).status(TenantStatus.FROZEN).build();
        when(userRepository.findByUsernameAcrossTenants("u")).thenReturn(Optional.of(userOf(frozen)));

        assertThatThrownBy(() -> authService.login(request()))
                .isInstanceOf(InvalidOperationException.class)
                // 密码是对的：不能是 BadCredentialsException，否则会被计入失败次数、把人锁在本就进不去的门外
                .isNotInstanceOf(BadCredentialsException.class);
    }

    /**
     * 登录防爆破（2026-09-25 安全扫描 H1）靠这个异常类型区分「凭据错」。改回普通的 InvalidOperationException
     * 时对外响应一字不差，但控制器从此不再计数、锁定整个失效——没有任何现象，所以钉成测试。
     */
    @Test
    void 密码错与用户不存在都抛BadCredentialsException() {
        when(userRepository.findByUsernameAcrossTenants("u")).thenReturn(Optional.of(userOf(null)));
        LoginRequest wrong = request();
        wrong.setPassword("wrong-password");
        assertThatThrownBy(() -> authService.login(wrong))
                .isInstanceOf(BadCredentialsException.class).hasMessage("用户名或密码错误");

        when(userRepository.findByUsernameAcrossTenants("nobody")).thenReturn(Optional.empty());
        LoginRequest nobody = request();
        nobody.setUsername("nobody");
        assertThatThrownBy(() -> authService.login(nobody))
                .isInstanceOf(BadCredentialsException.class).hasMessage("用户名或密码错误");
    }
}

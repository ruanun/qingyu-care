package com.qingyu.care.service;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService#init()} 播种平台管理员的口令来源。
 *
 * <p>安全审查 H1（2026-09-03）：此前口令写死 {@code admin123} 且打进 INFO 日志，生产首启即带着
 * 众所周知的平台管理员口令上线。修法：口令来自配置项 {@code qingyu.care.platform.admin-initial-password}
 * （环境变量 {@code QINGYU_ADMIN_INITIAL_PASSWORD}），未配置时生成随机口令、只打一次 WARN。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceInitTest {

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

    private User runInitAndCaptureAdmin(String configuredPassword) {
        when(userRepository.existsByUsernameAcrossTenants("admin")).thenReturn(false);
        ReflectionTestUtils.setField(authService, "adminInitialPassword", configuredPassword);

        authService.init();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void 配置了初始口令时按配置创建平台管理员() {
        User admin = runInitAndCaptureAdmin("Configured-Pw-2026");

        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getRole()).isEqualTo(UserRole.PLATFORM_ADMIN);
        assertThat(BCrypt.checkpw("Configured-Pw-2026", admin.getPassword())).isTrue();
    }

    /**
     * 变异实验：把随机口令分支改回 {@code "admin123"}，本条必须转红。
     */
    @Test
    void 未配置初始口令时不再使用admin123() {
        User admin = runInitAndCaptureAdmin("");

        assertThat(admin.getPassword()).startsWith("$2a$");
        assertThat(BCrypt.checkpw("admin123", admin.getPassword()))
                .as("未配置口令时必须生成随机口令，绝不能回落到众所周知的 admin123")
                .isFalse();
    }

    @Test
    void 已存在admin时不重复创建() {
        when(userRepository.existsByUsernameAcrossTenants("admin")).thenReturn(true);
        ReflectionTestUtils.setField(authService, "adminInitialPassword", "whatever");

        authService.init();

        verify(userRepository, never()).save(any(User.class));
    }
}

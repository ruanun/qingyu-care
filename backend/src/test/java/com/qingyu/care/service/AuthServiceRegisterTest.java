package com.qingyu.care.service;

import com.qingyu.care.dto.RegisterRequest;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService#register} 的「加入已有租户」路径。
 *
 * <p>安全审查 H2（2026-09-03）：此前带 {@code tenantCode} 注册即可落入该租户、且身份是
 * {@code TENANT_ADMIN}——租户 code 不是秘密（平台管理员自己填的、演示租户的写在 README 里），
 * 等于任何人都能自封任意租户的管理员。加入已有租户只能走租户管理员在「成员管理」里创建账号。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRegisterTest {

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

    /**
     * 变异实验：把 register 里「tenantCode 非空则拒绝」这一段删掉、恢复 findByCode 加入分支，
     * 本条必须转红。
     */
    @Test
    void 带租户编码的自助注册被拒绝且不触碰租户与用户表() {
        lenient().when(configService.getBoolean(eq(OwnerScope.PLATFORM), any(), eq("platform.registration.enabled"), anyBoolean()))
                .thenReturn(true);
        lenient().when(userRepository.existsByUsernameAcrossTenants(anyString())).thenReturn(false);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("intruder");
        req.setPassword("pwd123456");
        req.setTenantCode("demo-cloudboat");

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));

        verify(tenantRepository, never()).findByCode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * 2026-09-25 安全扫描 M2：平台配置里没设过「开放注册」时，此前默认开启——任何人都能无限开租户。
     * 现在取部署配置的默认值（基线 false）。configService 桩成「原样返回调用方给的默认值」，模拟库里没配。
     */
    @Test
    void 平台未配置开放注册时取部署默认值_基线关闭即拒绝() {
        when(configService.getBoolean(eq(OwnerScope.PLATFORM), any(), eq("platform.registration.enabled"), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(3));
        org.springframework.test.util.ReflectionTestUtils.setField(authService, "selfRegisterDefault", false);

        assertThat(authService.isSelfRegisterEnabled()).isFalse();
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newcomer");
        req.setPassword("pwd123456");
        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(userRepository, never()).save(any(User.class));

        org.springframework.test.util.ReflectionTestUtils.setField(authService, "selfRegisterDefault", true);
        assertThat(authService.isSelfRegisterEnabled()).as("dev/test profile 打开时放行").isTrue();
    }
}

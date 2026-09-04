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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        lenient().when(configService.getBoolean(eq(OwnerScope.PLATFORM), any(), eq("platform.registration.enabled"), eq(true)))
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
}

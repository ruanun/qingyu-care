package com.qingyu.care.service;

import com.qingyu.care.dto.RegisterRequest;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.captcha.CaptchaService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    CaptchaService captchaService;
    @Mock
    org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @InjectMocks
    AuthService authService;

    /**
     * 验证码校验必须在事务之外：Turnstile 模式要同步请求 Cloudflare（最坏 13 秒），包进事务就一直占着一个数据库连接，
     * Cloudflare 一慢就能把连接池占满、拖垮全站。这里断言「校验时还没有开事务，事务回调里也不再校验」。
     * 变异实验：给 register 加回方法级 {@code @Transactional} 或把 verify 挪进 executeWithoutResult 回调，本条转红
     * （前者对 Mockito 单测无效，由这条的注释与类注释约束；后者本条直接抓到）。
     */
    @Test
    void 验证码在事务外校验_事务只包建租户与建用户() {
        when(configService.getBoolean(eq(OwnerScope.PLATFORM), any(), eq("platform.registration.enabled"), anyBoolean()))
                .thenReturn(true);
        lenient().when(configService.get(any(), any(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(3));
        when(userRepository.existsByUsernameAcrossTenants("newcomer")).thenReturn(false);
        java.util.concurrent.atomic.AtomicInteger verifyCallsInsideTx = new java.util.concurrent.atomic.AtomicInteger(-1);
        org.mockito.Mockito.doAnswer(inv -> {
            int before = org.mockito.Mockito.mockingDetails(captchaService).getInvocations().size();
            when(tenantRepository.save(any())).thenAnswer(a -> a.getArgument(0));
            when(userRepository.save(any(User.class))).thenAnswer(a -> a.getArgument(0));
            inv.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(null);
            verifyCallsInsideTx.set(org.mockito.Mockito.mockingDetails(captchaService).getInvocations().size() - before);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newcomer");
        req.setPassword("pwd123456");
        // 自动登录那一步在桩环境里查不到用户，会抛凭据错误——这里只关心它之前的顺序
        assertThatThrownBy(() -> authService.register(req, "1.2.3.4")).isInstanceOf(com.qingyu.care.exception.BadCredentialsException.class);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(captchaService, transactionTemplate);
        order.verify(captchaService).verify(null, null, null, "1.2.3.4");
        order.verify(transactionTemplate).executeWithoutResult(any());
        assertThat(verifyCallsInsideTx.get()).as("事务回调里不应再有验证码调用").isZero();
        verify(tenantRepository).save(any());
        verify(userRepository).save(any(User.class));
    }

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

        assertThatThrownBy(() -> authService.register(req, "1.2.3.4"))
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
        assertThatThrownBy(() -> authService.register(req, "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(userRepository, never()).save(any(User.class));

        org.springframework.test.util.ReflectionTestUtils.setField(authService, "selfRegisterDefault", true);
        assertThat(authService.isSelfRegisterEnabled()).as("dev/test profile 打开时放行").isTrue();
    }

    /**
     * 验证码必须在查重名之前：否则不过验证码也能拿「用户名已存在」逐个探测账号。
     * 变异实验：把 captchaService.verify 挪到 existsByUsernameAcrossTenants 之后，本条转红。
     */
    @Test
    void 验证码不通过时不查重名也不建租户() {
        when(configService.getBoolean(eq(OwnerScope.PLATFORM), any(), eq("platform.registration.enabled"), anyBoolean()))
                .thenReturn(true);
        doThrow(new BusinessException(400, "验证码错误或已过期"))
                .when(captchaService).verify("cid", "XXXX", null, "1.2.3.4");

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newcomer");
        req.setPassword("pwd123456");
        req.setCaptchaId("cid");
        req.setCaptchaCode("XXXX");

        assertThatThrownBy(() -> authService.register(req, "1.2.3.4")).hasMessage("验证码错误或已过期");
        verify(userRepository, never()).existsByUsernameAcrossTenants(anyString());
        verifyNoInteractions(tenantRepository);
    }

    /** 注册关闭时先报「已关闭」，不消耗验证码（否则用户看到的是一句误导的「验证码错误」） */
    @Test
    void 注册关闭时不触碰验证码() {
        when(configService.getBoolean(eq(OwnerScope.PLATFORM), any(), eq("platform.registration.enabled"), anyBoolean()))
                .thenReturn(false);
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newcomer");
        req.setPassword("pwd123456");

        assertThatThrownBy(() -> authService.register(req, "1.2.3.4")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(captchaService);
    }
}

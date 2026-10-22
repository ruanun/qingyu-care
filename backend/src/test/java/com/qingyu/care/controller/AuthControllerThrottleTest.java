package com.qingyu.care.controller;

import com.qingyu.care.dto.LoginRequest;
import com.qingyu.care.dto.LoginResponse;
import com.qingyu.care.exception.BadCredentialsException;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.LoginThrottleService;
import com.qingyu.care.service.captcha.CaptchaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录端点什么时候计失败、什么时候清零（2026-09-25 安全扫描 H1）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerThrottleTest {

    @Mock AuthService authService;
    @Mock LoginThrottleService throttle;
    @Mock CaptchaService captchaService;
    @InjectMocks AuthController controller;

    private static LoginRequest req() {
        LoginRequest r = new LoginRequest();
        r.setUsername("alice");
        r.setPassword("pw");
        return r;
    }

    private static MockHttpServletRequest http() {
        MockHttpServletRequest h = new MockHttpServletRequest();
        h.setRemoteAddr("203.0.113.7");
        return h;
    }

    @Test
    void 凭据错计一次失败_名额不归还() {
        when(throttle.acquireAttempt("alice")).thenReturn(true);
        when(authService.login(any())).thenThrow(new BadCredentialsException());

        assertThatThrownBy(() -> controller.login(req(), http())).isInstanceOf(BadCredentialsException.class);

        verify(throttle).acquireAttempt("alice");
        verify(throttle, never()).releaseAttempt(any());
        verify(throttle, never()).clearFailures(any());
    }

    /** 账号停用 / 租户冻结时密码其实是对的，计进去会把人锁在一个本就进不去的门外 */
    @Test
    void 凭据对但账号不可用时归还名额() {
        when(throttle.acquireAttempt("alice")).thenReturn(true);
        when(authService.login(any())).thenThrow(new InvalidOperationException("账号已被禁用"));

        assertThatThrownBy(() -> controller.login(req(), http())).hasMessage("账号已被禁用");

        verify(throttle).releaseAttempt("alice");
    }

    @Test
    void 成功后清零() {
        when(authService.login(any())).thenReturn(LoginResponse.builder().token("t").build());

        controller.login(req(), http());

        verify(throttle).clearFailures("alice");
    }

    /** 占名额必须在校验密码之前：否则锁定期内猜中密码照样放行，并发请求也会在计数落地前全部挤进来 */
    @Test
    void 先查IP与占名额_再校验密码_锁定时根本不校验() {
        doThrow(new BusinessException(429, "锁定")).when(throttle).acquireAttempt("alice");

        assertThatThrownBy(() -> controller.login(req(), http())).hasMessage("锁定");

        InOrder order = inOrder(throttle);
        order.verify(throttle).checkLoginIp("203.0.113.7");
        order.verify(throttle).acquireAttempt("alice");
        verify(authService, never()).login(any());
    }

    /** 出图端点免登录、要占 CPU 与 Redis：先限流，超限时根本不出图 */
    @Test
    void 取图形验证码先按IP限流_超限不出图() {
        doThrow(new BusinessException(429, "操作过于频繁")).when(throttle).checkCaptchaIp("203.0.113.7");

        assertThatThrownBy(() -> controller.captcha(http())).hasMessage("操作过于频繁");

        verify(captchaService, never()).generateImage();
    }

    @Test
    void 注册把客户端IP一路传给校验() {
        com.qingyu.care.dto.RegisterRequest r = new com.qingyu.care.dto.RegisterRequest();
        when(authService.register(r, "203.0.113.7")).thenReturn(LoginResponse.builder().token("t").build());

        controller.register(r, http());

        InOrder order = inOrder(throttle, authService);
        order.verify(throttle).checkRegisterIp("203.0.113.7");
        order.verify(authService).register(r, "203.0.113.7");
    }
}

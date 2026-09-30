package com.qingyu.care.controller;

import com.qingyu.care.config.OpenApiAuthInterceptor;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.LoginRequest;
import com.qingyu.care.dto.LoginResponse;
import com.qingyu.care.dto.RegisterRequest;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.CaptchaMode;
import com.qingyu.care.exception.BadCredentialsException;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.LoginThrottleService;
import com.qingyu.care.service.captcha.CaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 认证 API
 */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    
    private final AuthService authService;
    private final LoginThrottleService loginThrottleService;
    private final CaptchaService captchaService;
    
    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Validated @RequestBody LoginRequest request, HttpServletRequest http) {
        loginThrottleService.checkLoginIp(OpenApiAuthInterceptor.resolveClientIp(http));
        loginThrottleService.assertNotLocked(request.getUsername());
        LoginResponse response;
        try {
            response = authService.login(request);
        } catch (BadCredentialsException e) {
            // 只有凭据错才计数：账号停用 / 租户冻结时密码其实是对的，计进去会把用户锁在一个本就进不去的门外
            loginThrottleService.recordFailure(request.getUsername());
            throw e;
        }
        loginThrottleService.clearFailures(request.getUsername());
        return ApiResponse.success("登录成功", response);
    }
    
    /**
     * 用户注册
     */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Validated @RequestBody RegisterRequest request, HttpServletRequest http) {
        String clientIp = OpenApiAuthInterceptor.resolveClientIp(http);
        loginThrottleService.checkRegisterIp(clientIp);
        LoginResponse response = authService.register(request, clientIp);
        return ApiResponse.success("注册成功", response);
    }
    
    /**
     * 登录页需要的公开配置（免登录）：是否开放自助注册（决定显不显示注册入口）、注册用哪种人机验证。
     * Turnstile 的 site key 本就是要下发给浏览器的公开值；secret key 绝不出现在这里。
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> publicConfig() {
        CaptchaMode mode = captchaService.currentMode();
        return ApiResponse.success(Map.of(
                "selfRegisterEnabled", authService.isSelfRegisterEnabled(),
                "captchaMode", mode.name(),
                "turnstileSiteKey", mode == CaptchaMode.TURNSTILE ? captchaService.turnstileSiteKey() : ""));
    }

    /**
     * 取一张图形验证码（免登录）。按 IP 限流：出图要占 CPU、还要写 Redis。
     */
    @GetMapping("/captcha")
    public ApiResponse<CaptchaService.ImageCaptcha> captcha(HttpServletRequest http) {
        loginThrottleService.checkCaptchaIp(OpenApiAuthInterceptor.resolveClientIp(http));
        return ApiResponse.success(captchaService.generateImage());
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success("退出成功", null);
    }
    
    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public ApiResponse<LoginResponse> getCurrentUser() {
        User user = authService.getCurrentUser();
        LoginResponse response = LoginResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .tenantId(user.getTenant() != null ? user.getTenant().getId() : null)
                .tenantName(user.getTenant() != null ? user.getTenant().getName() : null)
                .build();
        return ApiResponse.success(response);
    }
}

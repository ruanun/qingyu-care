package com.qingyu.care.dto;

import com.qingyu.care.enums.UserRole;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {
    
    @NotBlank(message = "用户名不能为空")
    private String username;
    
    @NotBlank(message = "密码不能为空")
    private String password;
    
    private String nickname;
    
    private String email;
    
    private String phone;
    
    /**
     * 租户编码。⚠️ 自助注册已不支持凭编码加入已有租户（安全审查 H2，2026-09-03）：
     * 非空一律 403。字段保留只为兼容旧客户端的请求体形状，服务端不再读取它做任何事。
     */
    private String tenantCode;
    
    /**
     * 租户名称 (创建新租户时使用)
     */
    private String tenantName;

    /** 图形验证码 id（平台启用图形验证码时必填，取自 {@code GET /v1/auth/captcha}） */
    private String captchaId;

    /** 用户输入的图形验证码 */
    private String captchaCode;

    /** Cloudflare Turnstile 令牌（平台启用 Turnstile 时必填） */
    private String turnstileToken;
}

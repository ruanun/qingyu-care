package com.qingyu.care.enums;

/**
 * 注册入口的人机验证方式，由平台管理员在「平台配置」里选择（配置键 {@code platform.registration.captcha}）。
 *
 * @author ruan
 */
public enum CaptchaMode {
    /** 不启用 */
    NONE,
    /** 服务端生成的图形验证码 */
    IMAGE,
    /** Cloudflare Turnstile */
    TURNSTILE
}

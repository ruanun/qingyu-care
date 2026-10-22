package com.qingyu.care.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.config.OpenApiAuthInterceptor;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.captcha.CaptchaService;
import com.qingyu.care.service.captcha.TurnstileVerifier;
import com.qingyu.care.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 平台级配置（仅平台管理员） */
@RestController
@RequestMapping("/v1/platform/config")
@RequiredArgsConstructor
public class PlatformConfigController {

    private final ConfigService configService;
    private final CaptchaService captchaService;

    /**
     * 凭据类配置只写不读（安全基线「凭据字段只写不读」）：读取时已设置的返回 {@link #SECRET_MASK}、未设置返回空串；
     * 保存时空串或原样回传的掩码都表示「不修改」——前端编辑表单不回显凭据，照样会把这个字段提交上来。
     */
    static final Set<String> SECRET_KEYS = Set.of(CaptchaService.TURNSTILE_SECRET_KEY);
    static final String SECRET_MASK = "******";

    @GetMapping
    public ApiResponse<Map<String, String>> get() {
        SecurityUtil.requirePlatformAdmin();
        Map<String, String> values = new LinkedHashMap<>(configService.getAll(OwnerScope.PLATFORM, null));
        SECRET_KEYS.forEach(k -> values.computeIfPresent(k, (key, v) -> v.isBlank() ? "" : SECRET_MASK));
        return ApiResponse.success(values);
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody Map<String, String> values) {
        SecurityUtil.requirePlatformAdmin();
        Map<String, String> toSave = new LinkedHashMap<>(values);
        SECRET_KEYS.forEach(k -> {
            String v = toSave.get(k);
            if (v == null || v.isBlank() || SECRET_MASK.equals(v)) {
                toSave.remove(k);
            }
        });
        captchaService.validateConfigUpdate(toSave);
        configService.setAll(OwnerScope.PLATFORM, null, toSave);
        return ApiResponse.success("平台配置已更新", null);
    }

    /**
     * 测试 Turnstile 配置：前端用表单当前的 site key 渲染组件拿到令牌，连同表单当前的 secret（留空用已保存的）
     * 交到这里，服务端真实请求一次 Cloudflare。可在保存前先测，确认无误再启用。
     */
    @PostMapping("/turnstile/test")
    public ApiResponse<TurnstileVerifier.Result> testTurnstile(@RequestBody Map<String, String> body, HttpServletRequest http) {
        SecurityUtil.requirePlatformAdmin();
        String secret = body.get("secretKey");
        if (SECRET_MASK.equals(secret)) {
            secret = null;
        }
        return ApiResponse.success(captchaService.testTurnstile(
                body.get("token"), secret, OpenApiAuthInterceptor.resolveClientIp(http)));
    }

    /**
     * 系统公告（platform.announcement）：任意登录用户可读，供管理后台顶部横幅展示；
     * 空串表示无公告。
     */
    @GetMapping("/announcement")
    public ApiResponse<String> announcement() {
        StpUtil.checkLogin();
        return ApiResponse.success(configService.get(OwnerScope.PLATFORM, null, "platform.announcement", ""));
    }
}

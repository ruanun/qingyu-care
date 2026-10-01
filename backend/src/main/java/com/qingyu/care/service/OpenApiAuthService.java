package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.repository.ApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 开放 API 认证：校验 appKey/appSecret，返回对应应用。
 */
@Service
@RequiredArgsConstructor
public class OpenApiAuthService {

    private final ApplicationRepository applicationRepository;

    public Application authenticate(String appId, String apiKey) {
        if (appId == null || appId.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new OpenApiException(401, "缺少 X-App-Id / X-Api-Key");
        }
        Application app = applicationRepository.findByAppKey(appId)
                .orElseThrow(() -> new OpenApiException(401, "无效的凭据"));
        // 先判停用（403）再比对密钥（401）：停用应用短路更早、语义更清晰
        if (Boolean.FALSE.equals(app.getEnabled())) {
            throw new OpenApiException(403, "应用已停用");
        }
        requireTenantActive(app);
        // 常量时间比较：String.equals 遇到第一个不同字符就返回，耗时随匹配前缀长度变化（2026-09-30 安全审查）
        if (app.getAppSecret() == null || !java.security.MessageDigest.isEqual(
                apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                app.getAppSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new OpenApiException(401, "无效的凭据");
        }
        return app;
    }

    /**
     * Widget 浏览器认证：公开 appKey + Origin 域名白名单（不校验密钥）。
     * appId 缺失 → 401；appKey 不存在 → 401；应用停用 → 403；
     * 未配置 allowedOrigins → 403；origin 为空或不在白名单 → 403。
     */
    public Application authenticateByOrigin(String appId, String origin) {
        if (appId == null || appId.isBlank()) {
            throw new OpenApiException(401, "缺少 X-App-Id");
        }
        Application app = applicationRepository.findByAppKey(appId)
                .orElseThrow(() -> new OpenApiException(401, "无效的凭据"));
        if (Boolean.FALSE.equals(app.getEnabled())) {
            throw new OpenApiException(403, "应用已停用");
        }
        requireTenantActive(app);
        String allowed = app.getAllowedOrigins();
        if (allowed == null || allowed.isBlank()) {
            throw new OpenApiException(403, "该应用未开放 Widget 接入");
        }
        if (origin == null || origin.isBlank() || !isOriginAllowed(allowed, origin)) {
            throw new OpenApiException(403, "来源域名未授权");
        }
        return app;
    }

    /**
     * 租户被冻结或套餐到期后，其 widget 与开放 API 必须一起停（安全审查 M6，2026-09-03）：
     * 此前只判 {@code app.enabled}，冻结的租户照常消耗模型费用。
     */
    private static void requireTenantActive(Application app) {
        Tenant tenant = app.getTenant();
        if (tenant == null) {
            return;
        }
        if (tenant.getStatus() != null && tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new OpenApiException(403, "租户已被冻结");
        }
        if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new OpenApiException(403, "租户套餐已到期");
        }
    }

    /** 逗号拆分、trim 后精确匹配（首版不支持通配子域）。 */
    private boolean isOriginAllowed(String allowed, String origin) {
        for (String s : allowed.split(",")) {
            if (s.trim().equals(origin)) {
                return true;
            }
        }
        return false;
    }
}

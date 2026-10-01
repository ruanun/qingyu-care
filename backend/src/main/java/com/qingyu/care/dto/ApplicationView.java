package com.qingyu.care.dto;

import com.qingyu.care.entity.Application;

import java.time.LocalDateTime;

/**
 * 应用的响应视图。
 *
 * <p>为什么不直接返回 {@code Application} 实体：实体上挂着 {@code appSecret}（服务端凭据，
 * 可见 INTERNAL 文档）与 {@code webhookSecret}（签名密钥），而应用列表是<b>全体租户成员</b>
 * 都要拉的（顶部选应用靠它）。实体一旦当响应体，坐席就拿到了两把密钥（安全审查 H4，2026-09-03）。
 * 这里按调用方角色决定是否带密钥：只有租户管理员 / 平台管理员拿得到。
 *
 * <p>⚠️ 不要图省事在实体上 setter 置空再返回——本项目 OSIV 开着，受管实体置空会被脏检查
 * 刷回库（{@code SharedCredentialMasker} 那次就是这么把凭据真的删掉的）。
 *
 * @author ruan
 */
public record ApplicationView(
        Long id,
        Long tenantId,
        String name,
        String appKey,
        String appSecret,
        String description,
        String avatar,
        String welcomeMessage,
        String themeColor,
        Boolean enabled,
        Integer rateLimitPerMinute,
        String allowedOrigins,
        String imageAllowedDomains,
        String webhookUrl,
        String webhookSecret,
        String webhookEvents,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * @param includeSecrets 是否带 {@code appSecret} / {@code webhookSecret}；非管理员一律 false
     */
    public static ApplicationView of(Application app, boolean includeSecrets) {
        return new ApplicationView(
                app.getId(),
                app.getTenant() != null ? app.getTenant().getId() : null,
                app.getName(),
                app.getAppKey(),
                includeSecrets ? app.getAppSecret() : null,
                app.getDescription(),
                app.getAvatar(),
                app.getWelcomeMessage(),
                app.getThemeColor(),
                app.getEnabled(),
                app.getRateLimitPerMinute(),
                app.getAllowedOrigins(),
                app.getImageAllowedDomains(),
                app.getWebhookUrl(),
                includeSecrets ? app.getWebhookSecret() : null,
                app.getWebhookEvents(),
                app.getCreatedAt(),
                app.getUpdatedAt());
    }
}

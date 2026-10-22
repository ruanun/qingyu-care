package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.WebhookProperties;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.ApplicationUpdateRequest;
import com.qingyu.care.dto.ApplicationView;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.dto.WebhookTestResult;
import com.qingyu.care.service.web.LinkExtractor;
import com.qingyu.care.service.webhook.WebhookPayloadBuilder;
import com.qingyu.care.service.webhook.WebhookSender;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 应用管理 API。
 *
 * <p>角色边界（安全审查 H4，2026-09-03）：list/get 全体租户成员可用（顶部选应用靠它），但
 * <b>密钥只给租户管理员 / 平台管理员</b>；create/update/delete/重置密钥/测试 Webhook 一律
 * {@code requireTenantAdmin}。此前 {@code checkAppAccess} 只比对租户，坐席能读到 appSecret、
 * 改 Origin 白名单与回调地址、重置密钥、删应用。
 *
 * <p>响应体一律 {@link ApplicationView} 而非实体：按角色脱敏不能靠 setter 置空（OSIV 下会刷回库）。
 */
@RestController
@RequestMapping("/v1/applications")
@RequiredArgsConstructor
@Slf4j
public class ApplicationController {

    private final ApplicationRepository applicationRepository;
    private final TenantRepository tenantRepository;
    private final AuthService authService;
    private final WebhookSender webhookSender;
    private final WebhookPayloadBuilder webhookPayloadBuilder;
    private final WebhookProperties webhookProperties;

    /**
     * 获取应用列表
     * - 平台管理员: 返回所有应用
     * - 租户用户: 返回当前租户的应用（成员不带密钥）
     */
    @GetMapping
    public ApiResponse<List<ApplicationView>> getAll() {
        boolean secrets = canSeeSecrets();
        if (authService.isPlatformAdmin()) {
            // 平台管理员查看所有应用
            return ApiResponse.success(applicationRepository.findAll().stream()
                    .map(a -> ApplicationView.of(a, secrets)).toList());
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new InvalidOperationException("无法获取租户信息");
        }
        List<Application> apps = applicationRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        return ApiResponse.success(apps.stream().map(a -> ApplicationView.of(a, secrets)).toList());
    }

    /**
     * 获取指定应用（成员不带密钥）
     */
    @GetMapping("/{id}")
    public ApiResponse<ApplicationView> getById(@PathVariable Long id) {
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);
        return ApiResponse.success(ApplicationView.of(app, canSeeSecrets()));
    }

    /**
     * 创建应用
     * - 平台管理员: 需在 application.tenant.id 中指定租户
     * - 租户管理员: 自动绑定当前租户
     */
    @AuditLog(action = "APP_CREATE", targetType = "Application")
    @PostMapping
    public ApiResponse<ApplicationView> create(@RequestBody Application application) {
        SecurityUtil.requireTenantAdmin();
        Tenant tenant;
        if (authService.isPlatformAdmin()) {
            // 平台管理员需指定租户
            if (application.getTenant() == null || application.getTenant().getId() == null) {
                throw new InvalidOperationException("平台管理员创建应用需指定租户 ID");
            }
            tenant = tenantRepository.findById(application.getTenant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("租户", application.getTenant().getId().toString()));
        } else {
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                throw new InvalidOperationException("无法获取租户信息");
            }
            tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("租户", tenantId.toString()));
        }

        application.setId(null);
        application.setTenant(tenant);
        application.setAppKey("app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        application.setAppSecret(generateSecret());
        // 建应用即生成签名密钥：此前默认为空，而 WebhookSender 在无密钥时不带签名头照发——
        // 用户不点一次「重置 Webhook 密钥」，接收方就收不到签名、也无从校验来源。请求体里给的值一律不采用。
        application.setWebhookSecret(generateSecret());
        // 建应用时给的回调地址同样要过形态与 SSRF 校验（update 路径见下）
        requireValidWebhookUrl(application.getWebhookUrl());
        requireValidAvatarUrl(application.getAvatar());
        requireValidThemeColor(application.getThemeColor());
        requireValidRateLimit(application.getRateLimitPerMinute());
        application.setImageAllowedDomains(normalizeImageDomains(application.getImageAllowedDomains()));

        return ApiResponse.success("应用已创建", ApplicationView.of(applicationRepository.save(application), true));
    }

    /**
     * 更新应用
     */
    @AuditLog(action = "APP_UPDATE", targetType = "Application")
    @PutMapping("/{id}")
    public ApiResponse<ApplicationView> update(@PathVariable Long id, @RequestBody ApplicationUpdateRequest application) {
        SecurityUtil.requireTenantAdmin();
        Application existing = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(existing);

        if (application.getName() != null) existing.setName(application.getName());
        if (application.getDescription() != null) existing.setDescription(application.getDescription());
        if (application.getAvatar() != null) {
            requireValidAvatarUrl(application.getAvatar());
            existing.setAvatar(application.getAvatar().trim());
        }
        if (application.getWelcomeMessage() != null) existing.setWelcomeMessage(application.getWelcomeMessage());
        if (application.getThemeColor() != null) {
            requireValidThemeColor(application.getThemeColor());
            existing.setThemeColor(application.getThemeColor().trim());
        }
        if (application.getEnabled() != null) existing.setEnabled(application.getEnabled());
        if (application.getRateLimitPerMinute() != null) {
            requireValidRateLimit(application.getRateLimitPerMinute());
            existing.setRateLimitPerMinute(application.getRateLimitPerMinute());
        }
        if (application.getAllowedOrigins() != null) existing.setAllowedOrigins(application.getAllowedOrigins());
        if (application.getImageAllowedDomains() != null) {
            existing.setImageAllowedDomains(normalizeImageDomains(application.getImageAllowedDomains()));
        }
        if (application.getWebhookUrl() != null) {
            // 落库前先拦：协议限 http(s)、主机须解析到公网地址。空串表示关闭 Webhook，放行。
            requireValidWebhookUrl(application.getWebhookUrl());
            existing.setWebhookUrl(application.getWebhookUrl());
        }
        if (application.getWebhookEvents() != null) existing.setWebhookEvents(application.getWebhookEvents());

        return ApiResponse.success("应用已更新", ApplicationView.of(applicationRepository.save(existing), true));
    }

    /**
     * 删除应用
     */
    @AuditLog(action = "APP_DELETE", targetType = "Application")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        SecurityUtil.requireTenantAdmin();
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);
        applicationRepository.deleteById(id);
        return ApiResponse.success("应用已删除", null);
    }

    /**
     * 重新生成应用密钥
     */
    @AuditLog(action = "APP_REGENERATE_SECRET", targetType = "Application")
    @PostMapping("/{id}/regenerate-secret")
    public ApiResponse<ApplicationView> regenerateSecret(@PathVariable Long id) {
        SecurityUtil.requireTenantAdmin();
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);

        app.setAppSecret(generateSecret());
        return ApiResponse.success("密钥已重新生成", ApplicationView.of(applicationRepository.save(app), true));
    }

    /**
     * 重新生成 Webhook 签名密钥
     */
    @AuditLog(action = "APP_REGENERATE_WEBHOOK_SECRET", targetType = "Application")
    @PostMapping("/{id}/regenerate-webhook-secret")
    public ApiResponse<ApplicationView> regenerateWebhookSecret(@PathVariable Long id) {
        SecurityUtil.requireTenantAdmin();
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);

        app.setWebhookSecret(generateSecret());
        return ApiResponse.success("Webhook 密钥已重新生成", ApplicationView.of(applicationRepository.save(app), true));
    }

    /**
     * 发送一条测试事件，同步投递并返回结果（仅验连通性，不落投递表、不受订阅过滤）。
     *
     * <p>失败原因只回一句固定文案：连接异常的原始文本（{@code Connection refused: /10.0.0.5:6379}）
     * 会把内网拓扑回显给调用方（安全审查 H5），细节只进日志。
     */
    @PostMapping("/{id}/webhook/test")
    public ApiResponse<WebhookTestResult> testWebhook(@PathVariable Long id) {
        SecurityUtil.requireTenantAdmin();
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);
        if (app.getWebhookUrl() == null || app.getWebhookUrl().isBlank()) {
            throw new InvalidOperationException("请先配置 Webhook 回调 URL");
        }

        String deliveryId = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appId", app.getId());
        data.put("message", "测试事件");
        String payload = webhookPayloadBuilder.build(deliveryId, "webhook.test", LocalDateTime.now(), data);
        try {
            int status = webhookSender.send(app.getWebhookUrl(), app.getWebhookSecret(),
                    "webhook.test", deliveryId, payload);
            boolean ok = status >= 200 && status < 300;
            return ApiResponse.success(new WebhookTestResult(ok, status, ok ? null : "HTTP " + status));
        } catch (Exception e) {
            // 只记主机：飞书 / 钉钉 / Slack 这类回调的 access_token 就在 URL 里（2026-09-30 安全审查）
            log.warn("Webhook 测试投递失败 appId={} host={}: {}", app.getId(), LinkExtractor.hostOf(app.getWebhookUrl()),
                    e.getClass().getSimpleName());
            return ApiResponse.success(new WebhookTestResult(false, null,
                    "无法连接回调地址（请检查地址是否公网可达、协议是否为 http/https、是否超时）"));
        }
    }

    /** 密钥只给租户管理员 / 平台管理员看；坐席拿到的视图两把密钥都是 null。 */
    private boolean canSeeSecrets() {
        return SecurityUtil.isPlatformAdmin() || SecurityUtil.isTenantAdmin();
    }

    private static String generateSecret() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 空/空白表示未启用 Webhook，放行；否则必须是 http(s) 且主机解析到公网地址。
     *
     * <p>⚠️ 必须与出站那一关（{@code RestClientWebhookHttpClient}）看同一个开关
     * （真机冒烟发现，2026-09-03）：原先这里是硬拦的，打开 {@code allow-private-targets} 后
     * 出站放行了、落库仍拒绝，本地联调根本配不上地址，开关等于半失效。集成测试发现不了——
     * 它们直接 {@code setWebhookUrl} 写库，绕过了控制器。云元数据段两种模式下都拦。
     */
    /**
     * 头像会被每个访客的浏览器加载，只收 http(s)；空串表示清除。widget 渲染时还会再过一道 safeUrl，
     * 这里拦是为了让配错的地址在保存时就报出来，而不是在访客那边静默不显示。
     * 不做 SSRF 校验：服务端从不请求这个地址，加载它的是访客浏览器。
     */
    private static void requireValidAvatarUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String u = url.trim();
        if (u.length() > 255) {
            throw new InvalidOperationException("头像地址过长（最多 255 个字符）");
        }
        try {
            java.net.URI uri = new java.net.URI(u);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new InvalidOperationException("头像地址必须是 http:// 或 https:// 开头的图片链接");
            }
        } catch (java.net.URISyntaxException e) {
            throw new InvalidOperationException("头像地址格式不正确");
        }
    }

    private static final Pattern THEME_COLOR = Pattern.compile("^#[0-9a-fA-F]{3,8}$");

    /** 应用级限流上限的硬顶：租户可调低或适度调高，但不能配成 Integer.MAX_VALUE 把平台限流整个关掉 */
    static final int MAX_RATE_LIMIT_PER_MINUTE = 6000;

    /**
     * 主题色只收十六进制颜色值（2026-09-30 安全审查）：它被原样拼进 widget Shadow DOM 里的 {@code <style>}，
     * 不校验时可以注入任意 CSS——在宿主页上铺一层全屏覆盖、伪造输入框，或用 {@code url()} 追踪访客。
     */
    static void requireValidThemeColor(String color) {
        if (color != null && !THEME_COLOR.matcher(color.trim()).matches()) {
            throw new InvalidOperationException("主题色格式不正确，请填 #RRGGBB 形式的颜色值");
        }
    }

    /** null 表示沿用平台默认，放行 */
    static void requireValidRateLimit(Integer perMinute) {
        if (perMinute != null && (perMinute < 1 || perMinute > MAX_RATE_LIMIT_PER_MINUTE)) {
            throw new InvalidOperationException("每分钟请求上限需在 1 到 " + MAX_RATE_LIMIT_PER_MINUTE + " 之间");
        }
    }

    /** 单个域名：可选的 {@code *.} 前缀 + 普通主机名字符。不收协议、路径与端口——只按主机名匹配。 */
    private static final Pattern IMAGE_DOMAIN =
            Pattern.compile("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    /**
     * 卡片图片域名白名单：去空白、转小写、去重，逐条校验格式（2026-09-30 安全审查 M7）。空串表示清空白名单。
     * 不允许裸 {@code *}——那等于关掉这道防线。
     */
    static String normalizeImageDomains(String raw) {
        if (raw == null) {
            return null;
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (String part : raw.split("[,，\\s]+")) {
            String d = part.trim().toLowerCase(Locale.ROOT);
            if (d.isEmpty()) {
                continue;
            }
            if (!IMAGE_DOMAIN.matcher(d).matches()) {
                throw new InvalidOperationException("图片域名格式不正确：" + part.trim()
                        + "（只填域名，如 img.example.com 或 *.example.com）");
            }
            domains.add(d);
        }
        if (domains.size() > 50) {
            throw new InvalidOperationException("图片域名最多 50 个");
        }
        return String.join(",", domains);
    }

    private void requireValidWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            if (webhookProperties.isAllowPrivateTargets()) {
                SsrfGuard.assertAllowedHttpUrlAllowingPrivate(url.trim());
            } else {
                SsrfGuard.assertAllowedHttpUrl(url.trim());
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("Webhook 回调地址无效：" + e.getMessage());
        }
    }

    private void checkAppAccess(Application app) {
        if (authService.isPlatformAdmin()) {
            return; // 平台管理员可访问所有应用
        }
        Long tenantId = TenantContext.getTenantId();
        if (!app.getTenant().getId().equals(tenantId)) {
            throw new InvalidOperationException("无权访问该应用");
        }
    }
}

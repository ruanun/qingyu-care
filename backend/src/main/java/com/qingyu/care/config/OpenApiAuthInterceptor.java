package com.qingyu.care.config;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.entity.Application;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.entity.SysAuditLog;
import com.qingyu.care.service.AuditLogService;
import com.qingyu.care.service.OpenApiAuthService;
import com.qingyu.care.service.QuotaService;
import com.qingyu.care.service.RateLimitService;
import com.qingyu.care.dto.QuotaCheckResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 开放 API 拦截器：校验 appKey/appSecret，将 tenantId/appId 写入 TenantContext，
 * 供下游 ChatService 复用多租户隔离。认证失败直接写出标准 HTTP 状态码。
 * 认证成功后执行分布式限流检查，超限返回 429 + Retry-After。
 */
@Component
@RequiredArgsConstructor
public class OpenApiAuthInterceptor implements AsyncHandlerInterceptor {

    public static final String ATTR_APP = "openApiApplication";

    private final OpenApiAuthService openApiAuthService;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final QuotaService quotaService;
    private final AuditLogService auditLogService;

    /** 平台默认每分钟限额，可在配置中覆盖；@Value 不能注入 final 字段，用普通字段 */
    @Value("${qingyu.care.openapi.default-rate-limit-per-minute:60}")
    private int defaultRateLimit;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 浏览器 Widget 跨域调用会先发 CORS 预检（OPTIONS，不带 X-App-Id/凭据），
        // 必须放行让 CORS 处理器回 2xx，否则预检 401 → 浏览器拦截所有开放 API 请求。
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        try {
            String appId = request.getHeader("X-App-Id");
            String apiKey = request.getHeader("X-Api-Key");
            Application app;
            if (apiKey != null && !apiKey.isBlank()) {
                // 服务端到服务端：appKey + secret（持密钥即受信，可见 INTERNAL 文档）
                app = openApiAuthService.authenticate(appId, apiKey);
                TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);
            } else {
                // 浏览器 Widget：appKey + Origin 白名单（匿名访客，仅见 PUBLIC 文档）
                app = openApiAuthService.authenticateByOrigin(appId, resolveOrigin(request));
                TenantContext.setAccessLevel(DocumentVisibility.PUBLIC);
            }
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            request.setAttribute(ATTR_APP, app);

            // 认证成功后执行限流检查
            int limit = RateLimitService.resolveLimit(app, defaultRateLimit);
            if (!rateLimitService.tryConsume(app.getAppKey(), limit)) {
                TenantContext.clear();
                response.setHeader("Retry-After", "60");
                writeError(response, 429, "请求过于频繁");
                return false;
            }

            // 额度检查（开放 API 专属）：超额 403，近阈值放行 + 软提示 + 审计
            QuotaCheckResult quota = quotaService.check(app.getTenant().getId());
            if (quota.exceeded()) {
                TenantContext.clear();
                writeError(response, 403, "本月额度已用尽，请升级套餐", "QUOTA_EXCEEDED");
                return false;
            }
            if (quota.nearThreshold()) {
                // 某维度 limit<=0（不限）时显示 unlimited，避免 used/0 的误导
                String usage = "calls=" + formatUsage(quota.callsUsed(), quota.callsLimit())
                        + ";tokens=" + formatUsage(quota.tokensUsed(), quota.tokensLimit());
                response.setHeader("X-Quota-Usage", usage);
                auditLogService.saveAsync(SysAuditLog.builder()
                        .tenantId(app.getTenant().getId())
                        .action("QUOTA_WARN").targetType("Tenant")
                        .targetId(String.valueOf(app.getTenant().getId()))
                        .detail("接近额度: " + usage)
                        .build());
            }

            return true;
        } catch (OpenApiException e) {
            // preHandle 返回 false 时本拦截器的 afterCompletion 不会触发，此处防御性清理上下文
            TenantContext.clear();
            writeError(response, e.getStatus(), e.getMessage());
            return false;
        } catch (Exception e) {
            // 兜底：认证过程中的其它异常统一返回 500，同样先清理上下文
            TenantContext.clear();
            writeError(response, 500, "服务器内部错误");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    /**
     * 同 {@link TenantInterceptor#afterConcurrentHandlingStarted}：异步分发（本拦截器守护的
     * `/open/v1/chat/stream` 正是 widget 主流量）时 DispatcherServlet 不会调 afterCompletion，
     * 原始 Tomcat 线程若不在此清理，会带着这次开放 API 请求的 tenantId/appId 回池，泄漏给
     * 复用该线程的下一个请求——包括管理端请求（TenantInterceptor 也注册在 `/**`，理论上会兜底
     * 清理，但那份保护依赖它的 path pattern 覆盖 `/open/**` 这个隐式前提，不该只靠邻居兜底）。
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        TenantContext.clear();
    }

    /** 取 Origin 头；缺失时从 Referer 解析出 scheme://host[:port]。 */
    private String resolveOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin;
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                java.net.URI u = java.net.URI.create(referer);
                if (u.getScheme() != null && u.getHost() != null) {
                    String o = u.getScheme() + "://" + u.getHost();
                    if (u.getPort() != -1) {
                        o += ":" + u.getPort();
                    }
                    return o;
                }
            } catch (IllegalArgumentException ignored) {
                // Referer 非法 URI，忽略，回退 null → 认证按未授权处理
            }
        }
        return null;
    }

    /** 额度用量展示：limit&lt;=0（不限）显示 unlimited，避免 used/0 的误导。 */
    private static String formatUsage(long used, long limit) {
        return used + "/" + (limit > 0 ? String.valueOf(limit) : "unlimited");
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        writeError(response, status, message, null);
    }

    private void writeError(HttpServletResponse response, int status, String message, String reason) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(OpenErrorResponse.of(status, message, reason)));
    }
}

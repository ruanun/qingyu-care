package com.qingyu.care.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.entity.Application;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.service.OpenApiAuthService;
import com.qingyu.care.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 开放 API 拦截器：校验 appKey/appSecret，将 tenantId/appId 写入 TenantContext，
 * 供下游 ChatService 复用多租户隔离。认证失败直接写出标准 HTTP 状态码。
 * 认证成功后执行分布式限流检查，超限返回 429 + Retry-After。
 */
@Component
@RequiredArgsConstructor
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_APP = "openApiApplication";

    private final OpenApiAuthService openApiAuthService;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;

    /** 平台默认每分钟限额，可在配置中覆盖；@Value 不能注入 final 字段，用普通字段 */
    @Value("${qingyu.care.openapi.default-rate-limit-per-minute:60}")
    private int defaultRateLimit;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        try {
            String appId = request.getHeader("X-App-Id");
            String apiKey = request.getHeader("X-Api-Key");
            Application app;
            if (apiKey != null && !apiKey.isBlank()) {
                // 服务端到服务端：appKey + secret
                app = openApiAuthService.authenticate(appId, apiKey);
            } else {
                // 浏览器 Widget：appKey + Origin 白名单（浏览器强制带 Origin，不可被页面脚本伪造）
                app = openApiAuthService.authenticateByOrigin(appId, resolveOrigin(request));
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

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(OpenErrorResponse.of(status, message)));
    }
}

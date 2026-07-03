package com.qingyu.care.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.entity.Application;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.service.OpenApiAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 开放 API 拦截器：校验 appKey/appSecret，将 tenantId/appId 写入 TenantContext，
 * 供下游 ChatService 复用多租户隔离。认证失败直接写出标准 HTTP 状态码。
 */
@Component
@RequiredArgsConstructor
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_APP = "openApiApplication";

    private final OpenApiAuthService openApiAuthService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        try {
            Application app = openApiAuthService.authenticate(
                    request.getHeader("X-App-Id"), request.getHeader("X-Api-Key"));
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            request.setAttribute(ATTR_APP, app);
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

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(OpenErrorResponse.of(status, message)));
    }
}

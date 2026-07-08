package com.qingyu.care.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.QuotaCheckResult;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.service.AuditLogService;
import com.qingyu.care.service.OpenApiAuthService;
import com.qingyu.care.service.QuotaService;
import com.qingyu.care.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 开放 API 鉴权拦截器的访问级别分支守护测试（文档权限控制的安全关键分支）。
 * 覆盖：apiKey 路径（服务端持密钥）写入 INTERNAL；Origin/Widget 路径（匿名访客）写入 PUBLIC；
 * 鉴权失败时不残留级别（{@link TenantContext#clear()} 兜底）。
 *
 * @author ruan
 */
class OpenApiAuthInterceptorAccessLevelTest {

    private final OpenApiAuthService openApiAuthService = mock(OpenApiAuthService.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    private final OpenApiAuthInterceptor interceptor = new OpenApiAuthInterceptor(
            openApiAuthService, objectMapper, rateLimitService, quotaService, auditLogService);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Application app() {
        Tenant tenant = Tenant.builder().id(1L).build();
        return Application.builder()
                .id(10L)
                .tenant(tenant)
                .appKey("app-key-1")
                .appSecret("app-secret-1")
                .enabled(true)
                .build();
    }

    /** 鉴权成功后限流/额度检查都放行，避免这两个横切分支干扰访问级别断言。 */
    private void stubDownstreamPass() {
        when(rateLimitService.tryConsume(anyString(), anyInt())).thenReturn(true);
        when(quotaService.check(anyLong())).thenReturn(new QuotaCheckResult(false, false, 0, 0, 0, 0));
    }

    @Test
    void apiKey路径_访问级别INTERNAL() throws Exception {
        Application app = app();
        when(request.getHeader("X-App-Id")).thenReturn("app-1");
        when(request.getHeader("X-Api-Key")).thenReturn("secret-1");
        when(openApiAuthService.authenticate("app-1", "secret-1")).thenReturn(app);
        stubDownstreamPass();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(TenantContext.getAccessLevel()).isEqualTo(DocumentVisibility.INTERNAL);
    }

    @Test
    void origin路径_访问级别PUBLIC() throws Exception {
        Application app = app();
        when(request.getHeader("X-App-Id")).thenReturn("app-1");
        when(request.getHeader("X-Api-Key")).thenReturn(null);
        when(request.getHeader("Origin")).thenReturn("https://a.com");
        when(openApiAuthService.authenticateByOrigin("app-1", "https://a.com")).thenReturn(app);
        stubDownstreamPass();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(TenantContext.getAccessLevel()).isEqualTo(DocumentVisibility.PUBLIC);
    }

    @Test
    void 鉴权失败_不留访问级别() throws Exception {
        when(request.getHeader("X-App-Id")).thenReturn("app-1");
        when(request.getHeader("X-Api-Key")).thenReturn("bad-key");
        when(openApiAuthService.authenticate("app-1", "bad-key"))
                .thenThrow(new OpenApiException(401, "无效的凭据"));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(TenantContext.getAccessLevel()).isNull();
    }
}

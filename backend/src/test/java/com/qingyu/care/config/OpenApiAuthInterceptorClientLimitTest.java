package com.qingyu.care.config;

import com.qingyu.care.dto.QuotaCheckResult;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.service.AuditLogService;
import com.qingyu.care.service.OpenApiAuthService;
import com.qingyu.care.service.QuotaService;
import com.qingyu.care.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放 API 在应用级限流之外再按「应用 + 客户端 IP」分桶（安全审查 M5，2026-09-03）。
 *
 * <p>appKey 明写在宿主页里，Origin 头非浏览器客户端随便填：此前一个滥用者就能用满整个应用的
 * 桶，让该应用所有真实访客一起吃 429，或以应用级上限持续烧租户的模型额度。子桶把单个来源的
 * 上限压到更低。IP 取 {@code X-Forwarded-For} 的<b>最后一段</b>——nginx 是追加模式
 * （{@code $proxy_add_x_forwarded_for}），最后一段才是 nginx 自己看到的直连地址，前面的段
 * 都是客户端可自填的。
 *
 * @author ruan
 */
class OpenApiAuthInterceptorClientLimitTest {

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

    private void stubAuthenticated() throws Exception {
        Application app = Application.builder().id(10L).tenant(Tenant.builder().id(1L).build())
                .appKey("app-key-1").appSecret("s").enabled(true).build();
        when(request.getHeader("X-App-Id")).thenReturn("app-key-1");
        when(request.getHeader("X-Api-Key")).thenReturn("s");
        when(openApiAuthService.authenticate("app-key-1", "s")).thenReturn(app);
        when(quotaService.check(anyLong())).thenReturn(new QuotaCheckResult(false, false, 0, 0, 0, 0));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        ReflectionTestUtils.setField(interceptor, "perClientRateLimit", 20);
    }

    @Test
    void 客户端子桶用满时429_且键含最后一跳IP() throws Exception {
        stubAuthenticated();
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 203.0.113.9");
        when(rateLimitService.tryConsume(eq("app-key-1"), anyInt())).thenReturn(true);
        when(rateLimitService.tryConsume(eq("client:app-key-1:203.0.113.9"), eq(20))).thenReturn(false);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setStatus(429);
        assertThat(TenantContext.getAppId()).isNull();
    }

    @Test
    void 没有转发头时按直连地址分桶() throws Exception {
        stubAuthenticated();
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");
        when(rateLimitService.tryConsume(anyString(), anyInt())).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(rateLimitService).tryConsume("client:app-key-1:198.51.100.7", 20);
    }

    @Test
    void 子桶上限为0时不做客户端限流() throws Exception {
        stubAuthenticated();
        ReflectionTestUtils.setField(interceptor, "perClientRateLimit", 0);
        when(rateLimitService.tryConsume(eq("app-key-1"), anyInt())).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(rateLimitService).tryConsume(eq("app-key-1"), anyInt());
        verify(rateLimitService, org.mockito.Mockito.never()).tryConsume(org.mockito.ArgumentMatchers.startsWith("client:"), anyInt());
    }
}

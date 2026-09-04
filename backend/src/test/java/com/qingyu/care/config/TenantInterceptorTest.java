package com.qingyu.care.config;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.repository.ApplicationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code X-App-Id} 必须属于当前登录租户才写进 {@link TenantContext}。
 *
 * <p>安全审查 M3（2026-09-03）：此前请求头给什么就写什么。读路径被 tenantFilter+appFilter 双重
 * 过滤挡住，但写路径 {@code TenantEntityListener} 会把新建行写成「tenant_id=自己, app_id=别人」，
 * 随后 {@code WebhookDispatcher} 按 app_id 反查出<b>别的租户</b>的回调地址与签名密钥，向对方发出
 * 签名正确的伪造事件。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class TenantInterceptorTest {

    @Mock
    ApplicationRepository applicationRepository;
    @InjectMocks
    TenantInterceptor interceptor;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 登录态：userId=7，Session 里的 tenantId 可为 null（平台管理员）。 */
    private static MockedStatic<StpUtil> stubLogin(Long tenantId) {
        SaSession session = mock(SaSession.class);
        lenient().when(session.get("tenantId")).thenReturn(tenantId);
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
        stp.when(StpUtil::getSession).thenReturn(session);
        return stp;
    }

    private static MockHttpServletRequest requestWithAppId(String appId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-App-Id", appId);
        return req;
    }

    @Test
    void 应用属于本租户时写入上下文() {
        when(applicationRepository.findTenantIdById(5L)).thenReturn(Optional.of(2L));
        try (MockedStatic<StpUtil> ignored = stubLogin(2L)) {
            interceptor.preHandle(requestWithAppId("5"), new MockHttpServletResponse(), new Object());
        }
        assertThat(TenantContext.getTenantId()).isEqualTo(2L);
        assertThat(TenantContext.getAppId()).isEqualTo(5L);
    }

    /** 变异实验：把归属校验删掉、改回「请求头给什么写什么」，本条转红。 */
    @Test
    void 应用属于别的租户时忽略请求头() {
        when(applicationRepository.findTenantIdById(5L)).thenReturn(Optional.of(3L));
        try (MockedStatic<StpUtil> ignored = stubLogin(2L)) {
            interceptor.preHandle(requestWithAppId("5"), new MockHttpServletResponse(), new Object());
        }
        assertThat(TenantContext.getTenantId()).isEqualTo(2L);
        assertThat(TenantContext.getAppId()).as("他租户的 appId 不得进入上下文").isNull();
    }

    @Test
    void 不存在的应用同样忽略() {
        when(applicationRepository.findTenantIdById(404L)).thenReturn(Optional.empty());
        try (MockedStatic<StpUtil> ignored = stubLogin(2L)) {
            interceptor.preHandle(requestWithAppId("404"), new MockHttpServletResponse(), new Object());
        }
        assertThat(TenantContext.getAppId()).isNull();
    }

    /** 平台管理员没有租户上下文，可以在任意应用间切换——不查库、直接写入。 */
    @Test
    void 平台管理员不校验归属() {
        try (MockedStatic<StpUtil> ignored = stubLogin(null)) {
            interceptor.preHandle(requestWithAppId("5"), new MockHttpServletResponse(), new Object());
        }
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getAppId()).isEqualTo(5L);
        verify(applicationRepository, never()).findTenantIdById(anyLong());
    }
}

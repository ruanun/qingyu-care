package com.qingyu.care.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析走<b>真实的</b> {@code ForwardedHeaderFilter}（{@code forward-headers-strategy: framework}
 * 在生产注册的就是它），守 2026-09-30 安全审查 H2。
 *
 * <p>此前的单测直接 mock {@code getHeader("X-Forwarded-For")}，而真实过滤器会把这个头藏掉，
 * 于是「取最后一段」那段代码只在单测里成立、生产上永远走不到，实际生效的是客户端可自填的第一段。
 * 这里不 mock 请求头，让请求真的穿过过滤器再交给 {@link OpenApiAuthInterceptor#resolveClientIp}。
 *
 * <p>后两条钉的是过滤器本身的语义——它们正是 nginx 必须「覆盖 XFF、清空 Forwarded」的理由
 * （见 {@code NginxForwardedHeadersTest}）。哪天 Spring 改了取段规则，这两条会先红，提醒重新评估反代配置。
 *
 * @author ruan
 */
class ClientIpResolutionTest {

    private static String resolveThroughFilter(MockHttpServletRequest raw) throws Exception {
        AtomicReference<String> ip = new AtomicReference<>();
        AtomicReference<String> visibleXff = new AtomicReference<>();
        new ForwardedHeaderFilter().doFilter(raw, new MockHttpServletResponse(), (req, res) -> {
            HttpServletRequest http = (HttpServletRequest) req;
            visibleXff.set(http.getHeader("X-Forwarded-For"));
            ip.set(OpenApiAuthInterceptor.resolveClientIp(http));
        });
        // 过滤器会把转发头藏起来：业务代码自己读 XFF 恒为 null，别再写那种逻辑
        assertThat(visibleXff.get()).isNull();
        return ip.get();
    }

    private static MockHttpServletRequest fromProxy() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        req.setRemoteAddr("172.18.0.5"); // nginx 容器
        return req;
    }

    @Test
    void nginx覆盖后的XFF就是客户端IP() throws Exception {
        MockHttpServletRequest req = fromProxy();
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(resolveThroughFilter(req)).isEqualTo("203.0.113.9");
    }

    @Test
    void 没有转发头时用直连地址() throws Exception {
        assertThat(resolveThroughFilter(fromProxy())).isEqualTo("172.18.0.5");
    }

    @Test
    void 过滤器取XFF第一段_所以反代不能用追加模式() throws Exception {
        MockHttpServletRequest req = fromProxy();
        req.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9"); // 追加模式：第一段是客户端自填的
        assertThat(resolveThroughFilter(req)).isEqualTo("6.6.6.6");
    }

    @Test
    void Forwarded头优先于XFF_所以反代必须清空它() throws Exception {
        MockHttpServletRequest req = fromProxy();
        req.addHeader("Forwarded", "for=7.7.7.7");
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(resolveThroughFilter(req)).isEqualTo("7.7.7.7");
    }
}

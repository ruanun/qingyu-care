package com.qingyu.care.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反代配置哨兵（2026-09-30 安全审查 H2）：后端按 IP 限流的可信度完全取决于 nginx 怎么传转发头。
 *
 * <p>后端的 {@code ForwardedHeaderFilter} 取 {@code X-Forwarded-For} 第一段、且 {@code Forwarded: for=}
 * 优先（{@code ClientIpResolutionTest} 钉着）。nginx 若用追加模式 {@code $proxy_add_x_forwarded_for}
 * 或放过客户端自带的 {@code Forwarded}，第一段就是攻击者自填的值，登录 / 注册 / 访客限流全部可绕过——
 * 而这类失效没有任何报错，正常流量下第一段恰好就是真实 IP，一切看起来都对。
 *
 * <p>两份都要守：实际镜像用的 {@code frontend/nginx.conf}，以及部署文档里供宿主机 nginx 照抄的样例。
 *
 * @author ruan
 */
class NginxForwardedHeadersTest {

    @ParameterizedTest
    @ValueSource(strings = {"../frontend/nginx.conf", "../docs/部署文档.md"})
    void 反代覆盖XFF并清空客户端自带的转发头(String file) throws Exception {
        String conf = Files.readString(Path.of(file), StandardCharsets.UTF_8);

        assertThat(conf).doesNotContain("$proxy_add_x_forwarded_for");
        assertThat(conf).contains("proxy_set_header X-Forwarded-For $remote_addr;");
        assertThat(conf).contains("proxy_set_header Forwarded \"\";");
        // 覆盖成 nginx 看到的带端口的 Host，而不是清空：清空会让非 80/443 端口下同源被判跨域
        assertThat(conf).contains("proxy_set_header X-Forwarded-Host $http_host;");
        assertThat(conf).contains("proxy_set_header Host $http_host;");
        assertThat(conf).contains("proxy_set_header X-Forwarded-Port \"\";");
    }
}

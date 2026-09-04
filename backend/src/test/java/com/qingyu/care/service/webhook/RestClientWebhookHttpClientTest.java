package com.qingyu.care.service.webhook;

import com.qingyu.care.config.WebhookProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Webhook 投递客户端必须走与 HTTP 工具同款的 SSRF 防线。
 *
 * <p>安全审查 H5（2026-09-03）：租户把回调地址填成 {@code http://169.254.169.254/}、
 * {@code http://redis:6379} 即可借平台身份探测内网；「测试 Webhook」还回显连接错误文本。
 * 本类钉住：默认拒绝内网/环回目标、拒绝非 http(s) 协议；{@code allowPrivateTargets}
 * 只给本地联调与 IT（它们的接收端就起在 127.0.0.1）用，打开后才会真的去连。
 *
 * @author ruan
 */
class RestClientWebhookHttpClientTest {

    private static RestClientWebhookHttpClient client(boolean allowPrivate) {
        WebhookProperties props = new WebhookProperties();
        props.setTimeoutMs(500);
        props.setAllowPrivateTargets(allowPrivate);
        return new RestClientWebhookHttpClient(props);
    }

    /** 变异实验：把 post() 里的 SsrfGuard 调用删掉，本条转红（会真的去连 127.0.0.1:9）。 */
    @Test
    void 默认拒绝指向环回地址的回调() {
        assertThatThrownBy(() -> client(false).post("http://127.0.0.1:9/hook", Map.of(), "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }

    @Test
    void 拒绝非http协议的回调() {
        assertThatThrownBy(() -> client(false).post("ftp://8.8.8.8/hook", Map.of(), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 放开私网目标时不再被守卫拦下，而是真的去连（端口 1 无人监听 → 传输层异常，非 IAE）。 */
    @Test
    void 放开私网目标时才会真的尝试连接() {
        assertThatThrownBy(() -> client(true).post("http://127.0.0.1:1/hook", Map.of(), "{}"))
                .isNotInstanceOf(IllegalArgumentException.class);
    }
    /** 同 endpoint 守卫：放开私网目标是给本地接收器用的，云元数据永远拒绝（真机冒烟发现，2026-09-03）。 */
    @Test
    void 放开私网目标时仍拒绝云元数据() {
        assertThatThrownBy(() -> client(true).post("http://169.254.169.254/latest/meta-data/", Map.of(), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client(true).post("http://100.100.100.200/hook", Map.of(), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 出站的私网豁免开关（{@code qingyu.care.mcp.allow-private-servers}）。
 *
 * <p>补这个开关的直接原因：模型端点有 {@code allow-private-endpoints}、Webhook 有
 * {@code allow-private-targets}，唯独 MCP 没有，本地自建 MCP server 根本连不上——而那是
 * 与「本地跑 Ollama」同样正当的开发场景。三处现在语义一致：放行环回与普通私网，
 * <b>云元数据段任何开关都不放行</b>。
 *
 * <p>用 IP 字面量而非域名，避免单测走真实 DNS。
 *
 * @author ruan
 */
class McpConnectionFactoryPrivateGuardTest {

    private McpConnectionFactory factory(boolean allowPrivate) {
        McpConnectionFactory f = new McpConnectionFactory();
        ReflectionTestUtils.setField(f, "allowPrivateServers", allowPrivate);
        return f;
    }

    private static McpServer server(String url) {
        McpServer s = new McpServer();
        s.setCode("smoke");
        s.setUrl(url);
        return s;
    }

    /** 变异实验：把 resolvePinnedAddresses 里的开关判断删掉、恒走 resolveAndValidate，本条转红。 */
    @Test
    void 开关关闭时拒绝私网地址() {
        assertThatThrownBy(() -> factory(false).resolvePinnedAddresses(server("http://127.0.0.1:3000/mcp")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }

    @Test
    void 开关打开时放行本地自建server() throws Exception {
        InetAddress[] pinned = factory(true).resolvePinnedAddresses(server("http://127.0.0.1:3000/mcp"));

        assertThat(pinned).isNotEmpty();
        assertThat(pinned[0]).isEqualTo(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    void 开关打开时仍拒绝云元数据() {
        assertThatThrownBy(() -> factory(true)
                .resolvePinnedAddresses(server("http://169.254.169.254/mcp")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory(true)
                .resolvePinnedAddresses(server("http://100.100.100.200/mcp")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 开关打开时仍要求http协议() {
        assertThatThrownBy(() -> factory(true).resolvePinnedAddresses(server("ftp://127.0.0.1/mcp")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 公网地址两种模式下都放行() {
        assertThatCode(() -> factory(false).resolvePinnedAddresses(server("https://8.8.8.8/mcp")))
                .doesNotThrowAnyException();
        assertThatCode(() -> factory(true).resolvePinnedAddresses(server("https://8.8.8.8/mcp")))
                .doesNotThrowAnyException();
    }
}

package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF 守卫。这两条守的是「静默失效」——漏掉 resolver 或 followRedirect(false) 后
 * 代码照常编译、连接照常建立、功能照常可用，只是防护没了。
 *
 * @author ruan
 */
class McpConnectionFactoryTest {

    private final McpConnectionFactory factory = new McpConnectionFactory();

    @Test
    @DisplayName("出站客户端必须钉住已校验 IP 并禁用重定向")
    void buildHttpClient_pinsResolverAndDisablesRedirect() throws Exception {
        InetAddress[] pinned = {InetAddress.getByName("93.184.216.34")};

        var client = McpConnectionFactory.buildHttpClient("example.com", pinned, 10000);

        // configuration() 暴露实际生效的传输配置：resolver 非默认即说明自定义解析器已挂上
        assertThat(client.configuration().resolver()).isNotNull();
        assertThat(client.configuration().followRedirectPredicate()).isNull();
    }

    @Test
    @DisplayName("指向内网地址的 server 在建连接前就被拒")
    void create_rejectsInternalAddress() {
        McpServer server = McpServer.builder()
                .name("恶意")
                .code("evil")
                .url("http://169.254.169.254/latest/meta-data")
                .timeoutMs(5000)
                .build();

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

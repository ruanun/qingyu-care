package com.qingyu.care.entity;

import com.qingyu.care.enums.McpToolStatus;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.enums.OwnerScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体默认值。readOnly 默认 false 是安全默认，被改成 true 会让未标注的工具直接可挂载。
 *
 * @author ruan
 */
class McpEntityDefaultsTest {

    @Test
    @DisplayName("McpTool 默认不可挂载：readOnly=false、status=ACTIVE")
    void mcpTool_defaults_areFailClosed() {
        McpTool tool = McpTool.builder().mcpServerId(1L).name("search").build();

        assertThat(tool.getReadOnly()).isFalse();
        assertThat(tool.getStatus()).isEqualTo(McpToolStatus.ACTIVE);
    }

    @Test
    @DisplayName("McpServer 默认走 STREAMABLE_HTTP、归属 TENANT")
    void mcpServer_defaults() {
        McpServer server = McpServer.builder().name("订单系统").code("order").url("https://x/mcp").build();

        assertThat(server.getTransport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        assertThat(server.getScope()).isEqualTo(OwnerScope.TENANT);
        assertThat(server.getTimeoutMs()).isEqualTo(10000);
        assertThat(server.getEnabled()).isTrue();
    }
}

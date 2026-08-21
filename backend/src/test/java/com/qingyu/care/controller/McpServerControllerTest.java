package com.qingyu.care.controller;

import com.qingyu.care.dto.McpServerUpdateRequest;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.service.mcp.McpServerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpServerControllerTest {

    @Mock
    private McpServerService mcpServerService;

    @InjectMocks
    private McpServerController controller;

    private McpServer existing() {
        return McpServer.builder().name("订单系统").code("order")
                .url("https://x/mcp").authHeader("Authorization: Bearer real").build();
    }

    @Test
    @DisplayName("局部更新只改传了的字段，没传的保持原值")
    void update_isPartial() {
        McpServer server = existing();
        when(mcpServerService.getServer(1L)).thenReturn(server);
        when(mcpServerService.saveServer(any())).thenAnswer(inv -> inv.getArgument(0));
        McpServerUpdateRequest req = new McpServerUpdateRequest();
        req.setName("新名字");

        controller.updateServer(1L, req);

        assertThat(server.getName()).isEqualTo("新名字");
        assertThat(server.getUrl()).isEqualTo("https://x/mcp");
        assertThat(server.getTimeoutMs()).isEqualTo(10000);
    }

    @Test
    @DisplayName("凭据传空串时保留原值——前端不回显凭据、编辑弹窗会把它初始化为空串照样提交")
    void update_blankCredential_keepsExisting() {
        McpServer server = existing();
        when(mcpServerService.getServer(1L)).thenReturn(server);
        when(mcpServerService.saveServer(any())).thenAnswer(inv -> inv.getArgument(0));
        McpServerUpdateRequest req = new McpServerUpdateRequest();
        req.setAuthHeader("   ");

        controller.updateServer(1L, req);

        assertThat(server.getAuthHeader()).isEqualTo("Authorization: Bearer real");
    }
}

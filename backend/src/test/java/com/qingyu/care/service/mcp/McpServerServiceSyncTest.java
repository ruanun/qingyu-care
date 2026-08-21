package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.McpToolStatus;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 同步的三条分支与两条不变量。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceSyncTest {

    @Mock
    private McpServerRepository serverRepository;
    @Mock
    private McpToolRepository toolRepository;
    @Mock
    private McpToolExecutor executor;

    @InjectMocks
    private McpServerService service;

    private McpServer server;

    @BeforeEach
    void setUp() {
        server = McpServer.builder().name("订单系统").code("order").url("https://x/mcp").build();
        server.setTenantId(1L);
        server.setAppId(1L);
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);
        lenient().when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        lenient().when(toolRepository.save(any(McpTool.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("远端有、库里无 → 新建，且默认不可挂载")
    void sync_newTool_isCreatedFailClosed() {
        when(executor.listTools(server)).thenReturn(List.of(
                new McpToolDescriptor("query_order", "查订单", "{\"type\":\"object\"}", true)));
        when(toolRepository.findByMcpServerIdAndName(1L, "query_order")).thenReturn(Optional.empty());
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of());

        service.sync(1L);

        var saved = org.mockito.ArgumentCaptor.forClass(McpTool.class);
        verify(toolRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("query_order");
        assertThat(saved.getValue().getStatus()).isEqualTo(McpToolStatus.ACTIVE);
        // 远端自报 readOnlyHint=true，但落库的安全判据仍是 false
        assertThat(saved.getValue().getReadOnlyHint()).isTrue();
        assertThat(saved.getValue().getReadOnly()).isFalse();
    }

    @Test
    @DisplayName("⚠️ 同步绝不覆盖租户标注的 readOnly")
    void sync_neverOverwritesReadOnly() {
        McpTool existing = McpTool.builder()
                .mcpServerId(1L).name("query_order").description("旧描述")
                .inputSchema("{\"old\":true}").readOnly(true).build();
        when(executor.listTools(server)).thenReturn(List.of(
                new McpToolDescriptor("query_order", "新描述", "{\"new\":true}", false)));
        when(toolRepository.findByMcpServerIdAndName(1L, "query_order")).thenReturn(Optional.of(existing));
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of(existing));

        service.sync(1L);

        assertThat(existing.getDescription()).isEqualTo("新描述");
        assertThat(existing.getInputSchema()).isEqualTo("{\"new\":true}");
        // 这一条是安全关键：被冲掉会让一个可写工具重新出现在 resolve() 结果里，且完全静默
        assertThat(existing.getReadOnly()).isTrue();
    }

    @Test
    @DisplayName("库里有、远端无 → 标 DEPRECATED 而不是删行")
    void sync_vanishedTool_isDeprecatedNotDeleted() {
        McpTool stale = McpTool.builder().mcpServerId(1L).name("old_tool").build();
        when(executor.listTools(server)).thenReturn(List.of());
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of(stale));

        service.sync(1L);

        assertThat(stale.getStatus()).isEqualTo(McpToolStatus.DEPRECATED);
        verify(toolRepository, never()).delete(any());
        verify(toolRepository, never()).deleteByMcpServerId(any());
    }

    @Test
    @DisplayName("重新出现的工具从 DEPRECATED 复活成 ACTIVE")
    void sync_reappearedTool_isReactivated() {
        McpTool revived = McpTool.builder()
                .mcpServerId(1L).name("query_order").status(McpToolStatus.DEPRECATED).build();
        when(executor.listTools(server)).thenReturn(List.of(
                new McpToolDescriptor("query_order", "查订单", "{}", null)));
        when(toolRepository.findByMcpServerIdAndName(1L, "query_order")).thenReturn(Optional.of(revived));
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of(revived));

        service.sync(1L);

        assertThat(revived.getStatus()).isEqualTo(McpToolStatus.ACTIVE);
    }

    @Test
    @DisplayName("⚠️ 同步失败只记 lastSyncError，一行工具都不动")
    void sync_failure_leavesToolsUntouched() {
        McpTool existing = McpTool.builder().mcpServerId(1L).name("query_order").build();
        when(executor.listTools(server)).thenThrow(new IllegalStateException("连接超时"));

        assertThatThrownBy(() -> service.sync(1L)).isInstanceOf(RuntimeException.class);

        // 一次网络抖动不该让整套工具集体变 DEPRECATED 进而从对话里静默消失
        assertThat(existing.getStatus()).isEqualTo(McpToolStatus.ACTIVE);
        verify(toolRepository, never()).save(any());
        assertThat(server.getLastSyncError()).contains("连接超时");
        assertThat(server.getLastSyncedAt()).isNull();
    }
}

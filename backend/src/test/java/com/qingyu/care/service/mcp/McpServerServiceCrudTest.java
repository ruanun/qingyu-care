package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceCrudTest {

    @Mock
    private McpServerRepository serverRepository;
    @Mock
    private McpToolRepository toolRepository;
    @Mock
    private McpToolExecutor executor;

    @InjectMocks
    private McpServerService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("未选应用时服务器列表为空，而不是裸查全表")
    void listServers_withoutApp_returnsEmpty() {
        assertThat(service.listServers()).isEmpty();
        verify(serverRepository, never()).findAll();
    }

    @Test
    @DisplayName("越权取他人 server 按「不存在」处理，防枚举探测")
    void getServer_crossTenant_throwsNotFound() {
        McpServer other = McpServer.builder().name("别人的").code("other").url("https://x/mcp").build();
        other.setTenantId(999L);
        other.setAppId(999L);
        when(serverRepository.findById(7L)).thenReturn(Optional.of(other));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);

        assertThatThrownBy(() -> service.getServer(7L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("code 非法时拒绝保存——它要参与 LLM 可见的工具名")
    void saveServer_rejectsIllegalCode() {
        McpServer server = McpServer.builder().name("订单系统").code("订单").url("https://x/mcp").build();

        assertThatThrownBy(() -> service.saveServer(server)).isInstanceOf(BusinessException.class);
        verify(serverRepository, never()).save(any());
    }

    @Test
    @DisplayName("批量标注只读逐条过租户守卫，越权的那条被跳过")
    void markReadOnly_guardsEveryId() {
        McpTool mine = McpTool.builder().mcpServerId(1L).name("a").build();
        mine.setTenantId(1L);
        mine.setAppId(1L);
        McpTool other = McpTool.builder().mcpServerId(2L).name("b").build();
        other.setTenantId(999L);
        other.setAppId(999L);
        when(toolRepository.findById(1L)).thenReturn(Optional.of(mine));
        when(toolRepository.findById(2L)).thenReturn(Optional.of(other));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);

        int updated = service.markReadOnly(List.of(1L, 2L), true);

        assertThat(updated).isEqualTo(1);
        assertThat(mine.getReadOnly()).isTrue();
        assertThat(other.getReadOnly()).isFalse();
    }

    // ===== R5：同一应用内 code 不可重复 =====

    @Test
    @DisplayName("R5：同一应用内 code 重复时拒绝保存")
    void saveServer_rejectsDuplicateCodeInSameApp() {
        McpServer existing = McpServer.builder().id(1L).name("订单系统").code("order_sys").url("https://x/mcp").build();
        existing.setTenantId(1L);
        existing.setAppId(1L);
        when(serverRepository.findByAppId(1L)).thenReturn(List.of(existing));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);

        McpServer duplicate = McpServer.builder().name("订单系统2号").code("order_sys").url("https://y/mcp").build();

        assertThatThrownBy(() -> service.saveServer(duplicate))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order_sys");
        verify(serverRepository, never()).save(any());
    }

    @Test
    @DisplayName("R5：更新自己（不改 code）不会被判成重复")
    void saveServer_updatingSelfWithUnchangedCode_isAllowed() {
        McpServer existing = McpServer.builder().id(1L).name("订单系统").code("order_sys").url("https://x/mcp").build();
        existing.setTenantId(1L);
        existing.setAppId(1L);
        when(serverRepository.findByAppId(1L)).thenReturn(List.of(existing));
        when(serverRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);

        McpServer updated = McpServer.builder().id(1L).name("订单系统（改名）").code("order_sys").url("https://x/mcp").build();

        assertThatCode(() -> service.saveServer(updated)).doesNotThrowAnyException();
        verify(serverRepository, times(1)).save(updated);
    }
}

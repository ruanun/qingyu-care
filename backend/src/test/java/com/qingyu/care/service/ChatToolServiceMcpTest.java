package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.McpToolStatus;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.service.mcp.McpToolExecutor;
import com.qingyu.care.service.tool.HttpToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * resolve() 对 MCP 工具的合并。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatToolServiceMcpTest {

    @Mock private AiToolRepository toolRepository;
    @Mock private AiSkillRepository skillRepository;
    @Mock private McpToolRepository mcpToolRepository;
    @Mock private McpServerRepository mcpServerRepository;
    @Mock private HttpToolExecutor httpToolExecutor;
    @Mock private McpToolExecutor mcpToolExecutor;
    @Mock private com.qingyu.care.config.SharedCredentialMasker masker;

    private ChatToolService service() {
        return new ChatToolService(toolRepository, skillRepository, httpToolExecutor,
                new ObjectMapper(), masker, mcpToolRepository, mcpServerRepository, mcpToolExecutor);
    }

    private McpServer server(String code) {
        McpServer s = McpServer.builder().name("订单系统").code(code).url("https://x/mcp").build();
        s.setId(1L);
        return s;
    }

    private McpTool mcpTool() {
        McpTool tool = McpTool.builder().mcpServerId(1L).name("query_order").inputSchema("{}").readOnly(true).build();
        tool.setId(10L);
        return tool;
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("skill 绑定的 MCP 工具被解析成带前缀的 callback")
    void resolve_includesMcpTools() {
        AiSkill skill = AiSkill.builder().name("售后").content("SOP").mcpToolIds(Set.of(10L)).build();
        McpTool tool = McpTool.builder().mcpServerId(1L).name("query_order").inputSchema("{}").readOnly(true).build();
        tool.setId(10L);
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(anyCollection(), eq(McpToolStatus.ACTIVE)))
                .thenReturn(List.of(tool));
        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(server("order")));

        var bundle = service().resolve();

        assertThat(bundle.toolCallbacks()).hasSize(1);
        assertThat(bundle.toolCallbacks().get(0).getToolDefinition().name()).isEqualTo("order__query_order");
    }

    @Test
    @DisplayName("没有绑定 MCP 工具时完全不打 mcp 相关的库")
    void resolve_withoutMcpBinding_skipsQuery() {
        AiSkill skill = AiSkill.builder().name("售后").content("SOP").build();
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));

        service().resolve();

        verify(mcpToolRepository, never()).findByIdInAndStatusAndReadOnlyTrue(anyCollection(), any());
    }

    @Test
    @DisplayName("MCP 工具与 HTTP 工具重名时跳过后来者，不把同名工具一起交给 LLM")
    void resolve_skipsDuplicateNames() {
        AiSkill skill = AiSkill.builder().name("售后").content("SOP")
                .toolIds(Set.of(1L)).mcpToolIds(Set.of(10L)).build();
        AiTool http = AiTool.builder().name("order__query_order").description("d")
                .urlTemplate("https://x").httpMethod("GET").build();
        http.setId(1L);
        McpTool mcp = McpTool.builder().mcpServerId(1L).name("query_order").inputSchema("{}").readOnly(true).build();
        mcp.setId(10L);
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of(http));
        when(mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(anyCollection(), eq(McpToolStatus.ACTIVE)))
                .thenReturn(List.of(mcp));
        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(server("order")));

        var bundle = service().resolve();

        assertThat(bundle.toolCallbacks()).hasSize(1);
    }

    @Test
    @DisplayName("server 已停用时该工具不进 callbacks")
    void resolve_skipsWhenServerDisabled() {
        AiSkill skill = AiSkill.builder().name("售后").content("SOP").mcpToolIds(Set.of(10L)).build();
        McpServer disabled = server("order");
        disabled.setEnabled(false);
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(anyCollection(), eq(McpToolStatus.ACTIVE)))
                .thenReturn(List.of(mcpTool()));
        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(disabled));

        var bundle = service().resolve();

        assertThat(bundle.toolCallbacks()).isEmpty();
    }

    @Test
    @DisplayName("server 查不到时跳过且不抛异常")
    void resolve_skipsWhenServerMissing() {
        AiSkill skill = AiSkill.builder().name("售后").content("SOP").mcpToolIds(Set.of(10L)).build();
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(anyCollection(), eq(McpToolStatus.ACTIVE)))
                .thenReturn(List.of(mcpTool()));
        when(mcpServerRepository.findById(1L)).thenReturn(Optional.empty());

        var bundle = service().resolve();

        assertThat(bundle.toolCallbacks()).isEmpty();
    }

    @Test
    @DisplayName("server 不属于当前租户上下文时该工具不进 callbacks（findById 不受 @Filter 保护，须显式校验）")
    void resolve_skipsWhenServerBelongsToAnotherTenant() {
        TenantContext.setTenantId(1L);
        AiSkill skill = AiSkill.builder().name("售后").content("SOP").mcpToolIds(Set.of(10L)).build();
        McpServer other = server("order");
        other.setTenantId(2L); // 属于别的租户
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(anyCollection(), eq(McpToolStatus.ACTIVE)))
                .thenReturn(List.of(mcpTool()));
        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(other));

        var bundle = service().resolve();

        assertThat(bundle.toolCallbacks()).isEmpty();
    }
}

package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceDeleteTest {

    @Mock private McpServerRepository serverRepository;
    @Mock private McpToolRepository toolRepository;
    @Mock private McpToolExecutor executor;
    @Mock private AiSkillRepository skillRepository;

    @InjectMocks
    private McpServerService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("删 server 时把 skill 里指向它的工具 id 一并摘掉，不留悬空绑定")
    void deleteServer_cleansSkillBindings() {
        McpServer server = McpServer.builder().name("订单").code("order").url("https://x/mcp").build();
        server.setTenantId(1L);
        server.setAppId(1L);
        McpTool t1 = McpTool.builder().mcpServerId(1L).name("a").build();
        t1.setId(10L);
        AiSkill skill = AiSkill.builder().name("售后")
                .mcpToolIds(new LinkedHashSet<>(Set.of(10L, 99L))).build();
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of(t1));
        when(skillRepository.findAll()).thenReturn(List.of(skill));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);

        service.deleteServer(1L);

        // 悬空的 10 被摘掉，属于别的 server 的 99 保留
        assertThat(skill.getMcpToolIds()).containsExactly(99L);
        verify(skillRepository).save(skill);
        verify(toolRepository).deleteByMcpServerId(1L);
        verify(serverRepository).delete(server);
    }
}

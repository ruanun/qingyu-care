package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.service.tool.HttpToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台共享工具的运行时解析。
 *
 * <p>核心守护：租户的 skill 里填上别的租户的工具 id 时，解析不出任何东西——
 * 两条查询（filter-bound 的自有 + native 的平台共享）都覆盖不到它。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatToolServiceSharedTest {

    @Mock
    private AiToolRepository toolRepository;
    @Mock
    private AiSkillRepository skillRepository;
    @Mock
    private HttpToolExecutor httpToolExecutor;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private SharedCredentialMasker credentialMasker;

    @InjectMocks
    private ChatToolService chatToolService;

    private AiSkill skillBinding(Long... toolIds) {
        return AiSkill.builder()
                .name("s").content("# SOP")
                .toolIds(new java.util.LinkedHashSet<>(List.of(toolIds)))
                .enabled(true).build();
    }

    private AiTool tool(Long id, String name) {
        AiTool t = AiTool.builder().name(name).description("d")
                .urlTemplate("http://example.com/{q}").build();
        t.setId(id);
        return t;
    }

    @Test
    void 能解析到平台共享工具() {
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skillBinding(9L)));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of());
        when(toolRepository.findSharedByIdInAndEnabledTrue(anyCollection()))
                .thenReturn(List.of(tool(9L, "shared_weather")));

        ChatToolService.ToolBundle bundle = chatToolService.resolve();

        assertThat(bundle.toolCallbacks()).hasSize(1);
    }

    @Test
    void 解析不到其他租户的工具() {
        // 租户在自己的 skill 里填了别人的工具 id 99：
        // 自有查询（filter-bound）看不到它，共享查询（钉了 scope=PLATFORM）也看不到它
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skillBinding(99L)));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of());
        when(toolRepository.findSharedByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of());

        ChatToolService.ToolBundle bundle = chatToolService.resolve();

        assertThat(bundle.toolCallbacks()).isEmpty();
    }

    @Test
    void 自有工具全部命中时不再发起共享查询() {
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skillBinding(1L)));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection()))
                .thenReturn(List.of(tool(1L, "own_tool")));

        ChatToolService.ToolBundle bundle = chatToolService.resolve();

        assertThat(bundle.toolCallbacks()).hasSize(1);
        verify(toolRepository, never()).findSharedByIdInAndEnabledTrue(anyCollection());
    }

    @Test
    void 自有与共享混绑时两者都在() {
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skillBinding(1L, 9L)));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection()))
                .thenReturn(List.of(tool(1L, "own_tool")));
        when(toolRepository.findSharedByIdInAndEnabledTrue(Set.of(9L)))
                .thenReturn(List.of(tool(9L, "shared_weather")));

        ChatToolService.ToolBundle bundle = chatToolService.resolve();

        assertThat(bundle.toolCallbacks()).hasSize(2);
    }

    @Test
    void 管理面列表把自有与平台共享合并() {
        TenantContext.setAppId(3L);
        try {
            when(toolRepository.findByAppId(3L)).thenReturn(List.of(tool(1L, "own")));
            when(toolRepository.findAllSharedTools()).thenReturn(List.of(tool(9L, "shared")));
            when(credentialMasker.mask(any(), any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            List<AiTool> list = chatToolService.listTools();

            assertThat(list).extracting(AiTool::getId).containsExactly(1L, 9L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 管理面列表经过脱敏助手() {
        TenantContext.setAppId(3L);
        try {
            when(toolRepository.findByAppId(3L)).thenReturn(List.of());
            when(toolRepository.findAllSharedTools()).thenReturn(List.of(tool(9L, "shared")));
            when(credentialMasker.mask(any(), any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            chatToolService.listTools();

            verify(credentialMasker).mask(any(), any(), any(), any());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 保存共享工具强制平台归属() {
        AiTool t = tool(null, "shared_new");
        t.setTenantId(7L);          // 请求体里带了租户归属，必须被覆盖
        t.setAppId(3L);
        when(toolRepository.save(any(AiTool.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTool saved = chatToolService.saveSharedTool(t);

        assertThat(saved.getScope()).isEqualTo(OwnerScope.PLATFORM);
        assertThat(saved.getTenantId()).isNull();
        assertThat(saved.getAppId()).isNull();
    }
}

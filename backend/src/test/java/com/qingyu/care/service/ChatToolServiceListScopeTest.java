package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiTool/AiSkill 管理面 list 的应用级收窄单测：TenantDataAspect 对 PLATFORM_ADMIN 整体不启用
 * @Filter，裸 findAll 会让平台管理员跨应用读全平台数据；listTools/listSkills 须显式按
 * TenantContext.getAppId() 收窄自有部分，无 appId 时自有部分返空且不查 findByAppId/findAll。
 *
 * <p>listTools 额外并入平台共享工具（Task 5），故无 appId 时返回值并非整体为空——
 * 平台共享工具仍须可见，租户才能在 skill 里勾选它们；listSkills 无此语义，仍是整体返空。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatToolServiceListScopeTest {

    @Mock AiToolRepository toolRepository;
    @Mock AiSkillRepository skillRepository;
    @Mock SharedCredentialMasker credentialMasker;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatToolService service() {
        return new ChatToolService(toolRepository, skillRepository,
                (m, u, a, b, t) -> "", new ObjectMapper(), credentialMasker);
    }

    @Test
    void listTools_有appId_按appId收窄() {
        TenantContext.setAppId(10L);
        AiTool tool = AiTool.builder().id(1L).name("t").build();
        when(toolRepository.findByAppId(10L)).thenReturn(List.of(tool));
        when(credentialMasker.mask(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        List<AiTool> result = service().listTools();

        assertThat(result).containsExactly(tool);
        verify(toolRepository, never()).findAll();
    }

    @Test
    void listTools_无appId_平台共享仍可见_自有查询不被调用() {
        // 无 appId（平台管理员未选定应用）时自有部分不查库，但平台共享工具仍须返回——
        // 与 listSkills 的「整体返空」不是同一契约，别混为一谈（见类注释）。
        AiTool shared = AiTool.builder().id(9L).name("shared").build();
        when(toolRepository.findAllSharedTools()).thenReturn(List.of(shared));
        when(credentialMasker.mask(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        List<AiTool> result = service().listTools();

        assertThat(result).extracting(AiTool::getId).containsExactly(9L);
        verify(toolRepository, never()).findByAppId(org.mockito.ArgumentMatchers.any());
        verify(toolRepository, never()).findAll();
    }

    @Test
    void listSkills_有appId_按appId收窄() {
        TenantContext.setAppId(10L);
        AiSkill skill = AiSkill.builder().id(2L).name("s").build();
        when(skillRepository.findByAppId(10L)).thenReturn(List.of(skill));

        List<AiSkill> result = service().listSkills();

        assertThat(result).containsExactly(skill);
        verify(skillRepository, never()).findAll();
    }

    @Test
    void listSkills_无appId_返空且不查库() {
        List<AiSkill> result = service().listSkills();

        assertThat(result).isEmpty();
        verify(skillRepository, never()).findByAppId(org.mockito.ArgumentMatchers.any());
        verify(skillRepository, never()).findAll();
    }
}

package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiTool/AiSkill 管理面 list 的应用级收窄单测：TenantDataAspect 对 PLATFORM_ADMIN 整体不启用
 * @Filter，裸 findAll 会让平台管理员跨应用读全平台数据；listTools/listSkills 须显式按
 * TenantContext.getAppId() 收窄，无 appId 返空且不查库。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatToolServiceListScopeTest {

    @Mock AiToolRepository toolRepository;
    @Mock AiSkillRepository skillRepository;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatToolService service() {
        return new ChatToolService(toolRepository, skillRepository,
                (m, u, a, b, t) -> "", new ObjectMapper());
    }

    @Test
    void listTools_有appId_按appId收窄() {
        TenantContext.setAppId(10L);
        AiTool tool = AiTool.builder().id(1L).name("t").build();
        when(toolRepository.findByAppId(10L)).thenReturn(List.of(tool));

        List<AiTool> result = service().listTools();

        assertThat(result).containsExactly(tool);
        verify(toolRepository, never()).findAll();
    }

    @Test
    void listTools_无appId_返空且不查库() {
        List<AiTool> result = service().listTools();

        assertThat(result).isEmpty();
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

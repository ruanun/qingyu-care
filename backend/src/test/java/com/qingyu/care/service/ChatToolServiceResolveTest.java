package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatToolServiceResolveTest {

    @Mock AiToolRepository toolRepository;
    @Mock AiSkillRepository skillRepository;

    private ChatToolService service() {
        return new ChatToolService(toolRepository, skillRepository,
                (m, u, a, b, t) -> "", new ObjectMapper());
    }

    @Test
    void emptyWhenNoEnabledSkills() {
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of());
        assertThat(service().resolve().isEmpty()).isTrue();
    }

    @Test
    void collectsMarkdownAndBoundToolCallbacks() {
        AiSkill skill = AiSkill.builder().name("退货").content("处理退货的步骤...")
                .toolIds(Set.of(1L)).enabled(true).build();
        AiTool tool = AiTool.builder().name("query_order").description("查订单")
                .urlTemplate("http://8.8.8.8/o/{id}").httpMethod("GET")
                .timeoutMs(3000).maxResponseChars(100).enabled(true).build();
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(toolRepository.findByIdInAndEnabledTrue(Set.of(1L))).thenReturn(List.of(tool));

        ChatToolService.ToolBundle bundle = service().resolve();

        assertThat(bundle.skillMarkdowns()).containsExactly("处理退货的步骤...");
        assertThat(bundle.toolCallbacks()).hasSize(1);
        assertThat(bundle.toolCallbacks().get(0).getToolDefinition().name()).isEqualTo("query_order");
    }

    @Test
    void skipsSkillWithNoTools_stillInjectsMarkdown() {
        AiSkill skill = AiSkill.builder().name("礼貌用语").content("始终礼貌")
                .toolIds(Set.of()).enabled(true).build();
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));

        ChatToolService.ToolBundle bundle = service().resolve();

        assertThat(bundle.skillMarkdowns()).containsExactly("始终礼貌");
        assertThat(bundle.toolCallbacks()).isEmpty();
    }
}

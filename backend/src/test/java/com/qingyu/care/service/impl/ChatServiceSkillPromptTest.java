package com.qingyu.care.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：appendSkillMarkdowns 纯方法（static package-private，同 meterContentStream 惯例）。
 *
 * @author ruan
 */
class ChatServiceSkillPromptTest {

    @Test
    void appendsEachSkillMarkdownAfterBasePrompt() {
        String out = ChatServiceImpl.appendSkillMarkdowns("你是客服。", List.of("退货流程A", "改签流程B"));
        assertThat(out).startsWith("你是客服。");
        assertThat(out).contains("退货流程A").contains("改签流程B");
    }

    @Test
    void returnsPromptUnchangedWhenNoMarkdowns() {
        assertThat(ChatServiceImpl.appendSkillMarkdowns("你是客服。", List.of())).isEqualTo("你是客服。");
        assertThat(ChatServiceImpl.appendSkillMarkdowns("你是客服。", null)).isEqualTo("你是客服。");
    }
}

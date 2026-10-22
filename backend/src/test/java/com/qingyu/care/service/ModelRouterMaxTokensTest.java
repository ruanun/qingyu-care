package com.qingyu.care.service;

import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.repository.AiModelConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 输出 token 上限（2026-09-30 安全审查）：防访客诱导超长输出。OpenAI 官方的推理模型拒绝 max_tokens、只认
 * max_completion_tokens，国内兼容模式普遍只认 max_tokens——给错字段要么 400、要么上限静默不生效。
 *
 * @author ruan
 */
class ModelRouterMaxTokensTest {

    private OpenAiChatOptions build(int limit, String provider) {
        ModelRouterService svc = new ModelRouterService(mock(AiModelConfigRepository.class), mock(SharedCredentialMasker.class));
        ReflectionTestUtils.setField(svc, "maxOutputTokens", limit);
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model("m");
        svc.applyMaxOutputTokens(b, provider);
        return b.build();
    }

    @Test
    void 国内兼容厂商用max_tokens() {
        OpenAiChatOptions o = build(4096, "dashscope");
        assertThat(o.getMaxTokens()).isEqualTo(4096);
        assertThat(o.getMaxCompletionTokens()).isNull();
    }

    @Test
    void OpenAI官方用max_completion_tokens() {
        OpenAiChatOptions o = build(4096, "openai");
        assertThat(o.getMaxCompletionTokens()).isEqualTo(4096);
        assertThat(o.getMaxTokens()).isNull();
    }

    @Test
    void 配成0不设上限() {
        OpenAiChatOptions o = build(0, "dashscope");
        assertThat(o.getMaxTokens()).isNull();
        assertThat(o.getMaxCompletionTokens()).isNull();
    }
}

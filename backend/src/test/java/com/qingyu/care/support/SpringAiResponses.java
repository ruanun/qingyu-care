package com.qingyu.care.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

/**
 * 测试辅助：构造带 usage 的 Spring AI ChatResponse，供 @MockitoBean 打桩 callWithFallback。
 *
 * Spring AI 1.x GA 适配说明（javap 核实）：
 * - DefaultUsage 构造为 (Integer promptTokens, Integer completionTokens, Integer totalTokens)
 * - ChatResponseMetadata.Builder 方法为 usage(Usage)
 * - getTotalTokens() 返回 Integer
 *
 * @author ruan
 */
public final class SpringAiResponses {
    private SpringAiResponses() {}

    /**
     * 构建带 usage 的 Spring AI ChatResponse。
     *
     * @param text        AI 回复文本
     * @param totalTokens 总 token 数（用于断言 token_count）
     */
    public static ChatResponse of(String text, int totalTokens) {
        Generation gen = new Generation(new AssistantMessage(text));
        // DefaultUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens)
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(0, totalTokens, totalTokens))
                .build();
        return new ChatResponse(List.of(gen), meta);
    }
}

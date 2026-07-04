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
 * M4 适配说明（javap 核实）：
 * - DefaultUsage 构造为 (Long promptTokens, Long generationTokens, Long totalTokens)
 * - ChatResponseMetadata.Builder 方法为 withUsage(Usage) 而非 usage(Usage)
 * - getTotalTokens() 返回 Long
 *
 * @author ruan
 */
public final class SpringAiResponses {
    private SpringAiResponses() {}

    /**
     * 按 M4 实际构造签名构建带 usage 的 Spring AI ChatResponse。
     *
     * @param text        AI 回复文本
     * @param totalTokens 总 token 数（用于断言 token_count）
     */
    public static ChatResponse of(String text, int totalTokens) {
        Generation gen = new Generation(new AssistantMessage(text));
        // M4: DefaultUsage(Long promptTokens, Long generationTokens, Long totalTokens)
        // M4: ChatResponseMetadata.Builder.withUsage(Usage) 而非 usage(Usage)
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .withUsage(new DefaultUsage(0L, (long) totalTokens, (long) totalTokens))
                .build();
        return new ChatResponse(List.of(gen), meta);
    }
}

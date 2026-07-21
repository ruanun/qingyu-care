package com.qingyu.care.dto.open;

import com.qingyu.care.dto.ChatResponse;
import lombok.Builder;
import lombok.Data;

/**
 * 开放对话响应（由内部 ChatResponse 映射，仅暴露对外必要字段）
 *
 * @author ruan
 */
@Data
@Builder
public class OpenChatResponse {

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * AI 消息 ID（提交赞/踩反馈时使用）
     */
    private Long messageId;

    /**
     * AI 回复内容
     */
    private String reply;

    /**
     * 是否建议转人工
     */
    private boolean handoverSuggested;

    /**
     * 从内部 ChatResponse 映射为对外响应
     */
    public static OpenChatResponse from(ChatResponse r) {
        return OpenChatResponse.builder()
                .sessionId(r.getSessionId())
                .messageId(r.getMessageId())
                .reply(r.getContent())
                .handoverSuggested(r.isSuggestHandover())
                .build();
    }
}

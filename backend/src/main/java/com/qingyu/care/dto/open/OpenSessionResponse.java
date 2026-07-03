package com.qingyu.care.dto.open;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开放会话响应：会话状态 + 消息历史
 *
 * @author ruan
 */
@Data
@Builder
public class OpenSessionResponse {

    /**
     * 会话唯一标识
     */
    private String sessionId;

    /**
     * 会话状态（枚举名称字符串，如 AI_PROCESSING / HUMAN_SERVICE / CLOSED）
     */
    private String status;

    /**
     * 消息列表
     */
    private List<Msg> messages;

    /**
     * 单条消息
     */
    @Data
    @Builder
    public static class Msg {
        /**
         * 消息角色（枚举名称字符串，如 USER / AI / AGENT）
         */
        private String role;

        /**
         * 消息内容
         */
        private String content;

        /**
         * 创建时间
         */
        private LocalDateTime createdAt;
    }

    /**
     * 从会话实体和消息列表构建响应。
     * ChatSession.status 为 SessionStatus 枚举，ChatMessage.role 为 MessageRole 枚举，
     * 均调用 .name() 转为字符串暴露给外部。
     */
    public static OpenSessionResponse of(ChatSession session, List<ChatMessage> messages) {
        return OpenSessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(session.getStatus() != null ? session.getStatus().name() : null)
                .messages(messages.stream()
                        .map(m -> Msg.builder()
                                .role(m.getRole() != null ? m.getRole().name() : null)
                                .content(m.getContent())
                                .createdAt(m.getCreatedAt())
                                .build())
                        .toList())
                .build();
    }
}

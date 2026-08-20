package com.qingyu.care.dto.open;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.AttachmentDTO;
import com.qingyu.care.dto.UiPart;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 开放会话响应：会话状态 + 消息历史
 *
 * @author ruan
 */
@Data
@Builder
@Slf4j
public class OpenSessionResponse {

    /**
     * 会话唯一标识
     */
    private String sessionId;

    /**
     * 会话状态（枚举名称字符串，如 AI_PROCESSING / WAITING_AGENT / AGENT_PROCESSING / ENDED / TIMEOUT）
     */
    private String status;

    /**
     * 消息列表
     */
    private List<Msg> messages;

    /**
     * 坐席读到哪了（对方的已读水位线）。⚠️ 只带对方（坐席）的，不带访客自己的——把自己的
     * 也返回会诱使前端拿它当本地状态的事实源，而本地水位线的推进条件（widget 是否真的展开且
     * 页面可见）只有客户端自己知道，服务端那份必然滞后。会话尚无坐席接管、或坐席还未产生任何
     * 已读回执时为 {@code null}。见
     * docs/superpowers/specs/2026-08-19-message-read-receipt-design.md §4.4。
     */
    private Long agentReadUpToMessageId;

    /**
     * 单条消息
     */
    @Data
    @Builder
    public static class Msg {
        /**
         * 消息 id（对应 ChatMessage.id）。恢复历史时用来给消息附件、赞踩反馈定位到具体一条消息。
         */
        private Long id;

        /**
         * 消息角色（枚举名称字符串，如 USER / ASSISTANT / SYSTEM / AGENT）
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

        /**
         * 结构化卡片片段：历史 ASSISTANT 消息含卡片时非空，由 ui_spec 反序列化而来（fail-soft，解析失败为 null）。
         */
        private List<UiPart> uiParts;

        /**
         * 该消息挂载的附件（图片/文件），无附件为空列表。由调用方一次性批量查出按 messageId 分组传入
         * （同 {@code ChatController#getSessionMessages} 的做法），避免逐条 LAZY 查询的 N+1。
         */
        private List<AttachmentDTO> attachments;
    }

    /**
     * 从会话实体和消息列表构建响应（不含附件，兼容既有调用方）。
     * ChatSession.status 为 SessionStatus 枚举，ChatMessage.role 为 MessageRole 枚举，
     * 均调用 .name() 转为字符串暴露给外部。
     */
    public static OpenSessionResponse of(ChatSession session, List<ChatMessage> messages,
                                         ObjectMapper objectMapper) {
        return of(session, messages, objectMapper, Map.of());
    }

    /**
     * 从会话实体和消息列表构建响应，附带按 messageId 分组好的附件。不含已读水位线
     * （{@code agentReadUpToMessageId} 为 null）——兼容不需要该字段的既有调用方。
     */
    public static OpenSessionResponse of(ChatSession session, List<ChatMessage> messages,
                                         ObjectMapper objectMapper,
                                         Map<Long, List<AttachmentDTO>> attachmentsByMessageId) {
        return of(session, messages, objectMapper, attachmentsByMessageId, null);
    }

    /**
     * 从会话实体和消息列表构建响应，附带按 messageId 分组好的附件与对方（坐席）的已读水位线。
     * Widget 断线补拉历史（GET /open/v1/sessions/{sessionId}）靠这份 attachments 恢复历史消息里的图片/文件——
     * 原先这里没有 attachments 字段，历史消息的附件恢复不出来，刷新即丢图；
     * agentReadUpToMessageId 同理恢复已读标记（见 spec §4.4）。
     */
    public static OpenSessionResponse of(ChatSession session, List<ChatMessage> messages,
                                         ObjectMapper objectMapper,
                                         Map<Long, List<AttachmentDTO>> attachmentsByMessageId,
                                         Long agentReadUpToMessageId) {
        return OpenSessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(session.getStatus() != null ? session.getStatus().name() : null)
                .agentReadUpToMessageId(agentReadUpToMessageId)
                .messages(messages.stream()
                        .map(m -> Msg.builder()
                                .id(m.getId())
                                .role(m.getRole() != null ? m.getRole().name() : null)
                                .content(m.getContent())
                                .createdAt(m.getCreatedAt())
                                .uiParts(parseUiParts(m.getUiSpec(), objectMapper))
                                // m.getId() 可能为 null（理论上不会出现在已持久化消息上，防御一下）；
                                // Map.of() 等不可变 Map 对 null key 会直接抛 NPE，故先判空再查
                                .attachments(m.getId() != null
                                        ? attachmentsByMessageId.getOrDefault(m.getId(), List.of())
                                        : List.of())
                                .build())
                        .toList())
                .build();
    }

    /** 反序列化 ui_spec JSON 为 uiParts；空/非法一律返回 null（fail-soft，历史卡片解析失败不阻断会话恢复）。 */
    private static List<UiPart> parseUiParts(String uiSpec, ObjectMapper objectMapper) {
        if (uiSpec == null || uiSpec.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(uiSpec, new TypeReference<List<UiPart>>() {});
        } catch (Exception e) {
            // fail-soft：解析失败该条消息不渲染卡片，不阻断整个会话恢复；记 warn 供运维排查（如 schema 漂移）
            log.warn("历史消息 ui_spec 反序列化失败，该条不渲染卡片: {}", e.getMessage());
            return null;
        }
    }
}

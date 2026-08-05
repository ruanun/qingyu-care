package com.qingyu.care.dto;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.enums.MessageRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史消息出参。原先直接返回 ChatMessage 实体，附件无处挂载；
 * 走 DTO 后附件由 Controller 层批量查出来组装（避免逐条 LAZY 的 N+1）。
 *
 * @author ruan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {

    private Long id;
    private MessageRole role;
    private String content;
    private String uiSpec;
    private Integer feedback;
    private Boolean fallback;
    private LocalDateTime createdAt;
    private List<AttachmentDTO> attachments;

    public static ChatMessageDTO of(ChatMessage m, List<AttachmentDTO> attachments) {
        return ChatMessageDTO.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .uiSpec(m.getUiSpec())
                .feedback(m.getFeedback())
                .fallback(m.getFallback())
                .createdAt(m.getCreatedAt())
                .attachments(attachments)
                .build();
    }
}

package com.qingyu.care.dto.open;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 开放 API 消息反馈请求。
 *
 * @author ruan
 */
@Data
public class OpenFeedbackRequest {

    /** 会话 ID */
    @NotBlank
    private String sessionId;

    /** AI 消息 ID（completions 响应 / SSE meta 事件返回） */
    @NotNull
    private Long messageId;

    /** 1=赞 / -1=踩 */
    @NotNull
    private Integer feedback;
}

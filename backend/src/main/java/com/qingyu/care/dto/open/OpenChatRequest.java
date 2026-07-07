package com.qingyu.care.dto.open;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 开放对话请求 DTO
 *
 * @author ruan
 */
@Data
public class OpenChatRequest {

    /**
     * 会话 ID（可选，不传则创建新会话）
     */
    private String sessionId;

    /**
     * 用户标识（可选）
     */
    private String userId;

    /**
     * 消息内容（必填）
     */
    @NotBlank(message = "消息内容不能为空")
    private String message;
}

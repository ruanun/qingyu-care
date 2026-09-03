package com.qingyu.care.dto.open;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

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
    @Size(max = 8000, message = "消息内容不能超过 8000 字")
    private String message;

    /**
     * 本条消息携带的附件 id（先经 /open/v1/chat/files 上传拿到）。可空。
     */
    private List<Long> attachmentIds;
}

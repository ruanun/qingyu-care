package com.qingyu.care.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    
    /**
     * 会话ID（可选，不传则创建新会话）
     */
    private String sessionId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 用户消息
     */
    @NotBlank(message = "消息内容不能为空")
    private String message;
    
    /**
     * 是否启用流式响应
     */
    private boolean stream = false;
}

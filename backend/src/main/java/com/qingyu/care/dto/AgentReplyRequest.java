package com.qingyu.care.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客服回复请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentReplyRequest {
    
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;
    
    @NotBlank(message = "消息内容不能为空")
    private String message;
    
    @NotNull(message = "客服ID不能为空")
    private Long agentId;
}

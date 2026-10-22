package com.qingyu.care.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 客服回复请求 DTO。
 * agentId 不再由请求体传入，控制器一律取登录态（StpUtil.getLoginIdAsLong()），
 * 防止坐席冒充他人身份回复。
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

    /**
     * 本条回复携带的附件 id（先经 /v1/chat/files 上传拿到）。可空。
     */
    private List<Long> attachmentIds;
}

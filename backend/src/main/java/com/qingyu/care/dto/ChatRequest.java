package com.qingyu.care.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO
 *
 * <p>无参构造显式标 {@link JsonCreator}：Jackson 3（Boot 4 起）会把 Lombok 的
 * {@code @AllArgsConstructor} 当成隐式属性创建器（靠 -parameters 参数名），
 * 请求体里缺省的属性会被当作 null 传进构造器——原始类型直接抛
 * {@code Cannot map null into type boolean}（Jackson 3 默认开启
 * FAIL_ON_NULL_FOR_PRIMITIVES），字段初值也会被 null 覆盖。
 * 显式指定无参构造为创建器可恢复 Jackson 2 的「无参构造 + setter」填充语义。
 * 本类尤其致命：{@code openChannel} 带 {@code @JsonIgnore} 永远无法从请求体提供，
 * 不修则 /v1/chat 与 /v1/chat/stream 对任何请求体都必然 400。
 */
@Data
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
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

    /**
     * 是否来自开放渠道（/open/v1，参与 A/B 分流）。内部字段：
     * @JsonIgnore 禁止从请求体注入，防手工构造 JSON 混入分流污染实验样本。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Builder.Default
    private boolean openChannel = false;
}

package com.qingyu.care.dto;

import lombok.Data;

/**
 * 应用局部更新请求。
 *
 * <p><b>为什么不直接用 {@code Application} 实体收请求体</b>：实体字段带 Lombok
 * {@code @Builder.Default}（{@code themeColor}/{@code enabled}），而 Lombok 会把默认值也
 * 应用进无参构造，Jackson 反序列化走的正是那个无参构造——于是请求体里<b>没给</b>的字段拿到的是
 * 类默认值而非 {@code null}，{@code if (x != null)} 恒真，把库里的值覆盖掉。前端点一下启用开关
 * （只发 {@code {"enabled": false}}）就会把 themeColor 打回 {@code "#6366f1"}。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器、不加 {@code @Builder.Default}、不加
 * {@code @Builder}</b>——{@code null} 必须真正表示「请求体没给这个字段」。加了 builder 就会
 * 诱使下一个人写 {@code @Builder.Default}，把坑重新种回来。反例见
 * {@code KnowledgeBaseRequest.enabled}（它现在没出事只因为前端建/改知识库都发整份对象）。
 *
 * @author ruan
 */
@Data
public class ApplicationUpdateRequest {
    private String name;
    private String description;
    private String avatar;
    private String welcomeMessage;
    private String themeColor;
    private Boolean enabled;
    private Integer rateLimitPerMinute;
    private String allowedOrigins;
    private String webhookUrl;
    private String webhookEvents;
}

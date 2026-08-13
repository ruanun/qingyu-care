package com.qingyu.care.dto;

import lombok.Data;

/**
 * AI 模型配置局部更新请求。
 *
 * <p><b>为什么不直接用 {@code AiModelConfig} 实体收请求体</b>：实体字段带 Lombok
 * {@code @Builder.Default}（{@code scenario}/{@code priority}/{@code enabled}/{@code scope}），
 * 而 Lombok 会把默认值也应用进无参构造，Jackson 反序列化走的正是那个无参构造——于是请求体里
 * <b>没给</b>的字段拿到的是类默认值而非 {@code null}，{@code if (x != null)} 恒真，把库里的值
 * 覆盖掉。前端点一下启用开关（只发 {@code {"enabled": false}}）就会把 scenario 打回
 * {@code "default"}、priority 打回 100。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器、不加 {@code @Builder.Default}、不加
 * {@code @Builder}</b>——{@code null} 必须真正表示「请求体没给这个字段」。加了 builder 就会
 * 诱使下一个人写 {@code @Builder.Default}，把坑重新种回来。反例见
 * {@code KnowledgeBaseRequest.enabled}（它现在没出事只因为前端建/改知识库都发整份对象）。
 *
 * <p>⚠️ <b>刻意不含 {@code scope} 字段</b>，理由同 {@code AiToolUpdateRequest}：{@code scope}
 * 是客户端可控字段，租户曾可在请求体里自封 {@code PLATFORM} 让自己的模型（连同自己的 apiKey）
 * 出现在所有租户列表里。不放进 DTO 让这条路径在结构上不可能。平台共享模型只能走
 * {@code /v1/platform/models}。
 *
 * @author ruan
 */
@Data
public class AiModelConfigUpdateRequest {
    private String name;
    private String provider;
    private String modelId;
    private String endpoint;
    /** 留空表示不修改（前端不回显凭据） */
    private String apiKey;
    private String scenario;
    private Integer priority;
    private Boolean enabled;
    private String remark;
}

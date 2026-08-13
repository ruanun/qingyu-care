package com.qingyu.care.dto;

import lombok.Data;

/**
 * 问答策略局部更新请求。
 *
 * <p><b>为什么不直接用 {@code ChatStrategy} 实体收请求体</b>：实体字段带 Lombok
 * {@code @Builder.Default}，而 Lombok 会把默认值也应用进无参构造，Jackson 反序列化走的
 * 正是那个无参构造——于是请求体里<b>没给</b>的字段拿到的是类默认值而非 {@code null}，
 * {@code if (x != null)} 恒真，把库里的值覆盖掉。
 *
 * <p>⚠️ <b>{@code similarityThreshold} 实体默认值是 0.45</b>（2026-08-11 按当前 embedding
 * 模型实测标定：真实命中落在 0.57~0.73、无关噪声落在 0.25~0.30，0.45 取两者之间的空档）。
 * 局部 PUT 若沿用实体收请求体，会把租户自己调好的阈值静默打回这个值，且无任何提示。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器、不加 {@code @Builder.Default}、不加
 * {@code @Builder}</b>——{@code null} 必须真正表示「请求体没给这个字段」。加了 builder 就会
 * 诱使下一个人写 {@code @Builder.Default}，把坑重新种回来。反例见
 * {@code KnowledgeBaseRequest.enabled}（它现在没出事只因为前端建/改知识库都发整份对象）。
 *
 * @author ruan
 */
@Data
public class ChatStrategyUpdateRequest {
    private String name;
    private String scenario;
    private Integer topK;
    private Double similarityThreshold;
    private Boolean allowNoKnowledge;
    private String fallbackMessage;
    private Long promptTemplateId;
    private Boolean enabled;
    private Boolean isDefault;
}

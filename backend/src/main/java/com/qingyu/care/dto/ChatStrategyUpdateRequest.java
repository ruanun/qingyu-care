package com.qingyu.care.dto;

import lombok.Data;

/**
 * 问答策略局部更新请求。
 *
 * <p><b>为什么不直接用 {@code ChatStrategy} 实体收请求体</b>：实体字段写了初始化器（如
 * {@code private Double similarityThreshold = 0.45;}），Jackson 反序列化用的无参构造会原样
 * 执行这些初始化器——于是请求体里<b>没给</b>的字段拿到的是类默认值而非 {@code null}，
 * {@code if (x != null)} 恒真，把库里的值覆盖掉。实体上这些字段还额外标了
 * {@code @Builder.Default}，但<b>真正起效的是字段初始化器本身，不是 {@code @Builder.Default}
 * 注解</b>——见本类末段。
 *
 * <p>⚠️ <b>{@code similarityThreshold} 实体默认值是 0.45</b>（2026-08-11 按当前 embedding
 * 模型实测标定：真实命中落在 0.57~0.73、无关噪声落在 0.25~0.30，0.45 取两者之间的空档）。
 * 局部 PUT 若沿用实体收请求体，会把租户自己调好的阈值静默打回这个值，且无任何提示。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器</b>——这才是真正的铁律，{@code null} 必须真正表示
 * 「请求体没给这个字段」。<b>删掉 {@code @Builder.Default} 或不加 {@code @Builder} 都不能替代
 * 这条</b>：Jackson 走的无参构造只看字段是否有初始化器，与 {@code @Builder.Default} 无关——
 * {@code @Builder.Default} 只决定 {@code builder().build()} 这条路径是否也应用该默认值，不加
 * 它时 Lombok 会警告「初始化表达式被忽略」，但 {@code new ChatStrategy()} 依旧会应用初始化器。
 * 反证见 {@code KnowledgeBaseRequest.enabled}：它有 {@code @Builder} +
 * {@code private Boolean enabled = true;} 却<b>没有</b> {@code @Builder.Default}，
 * {@code new KnowledgeBaseRequest().getEnabled()} 依然是 {@code true}（它现在没出事只因为
 * 前端建/改知识库都发整份对象，不代表这条路径安全）。
 *
 * <p>⚠️ <b>反过来，{@code ChatStrategy} 实体上那些初始化器不能被删掉</b>：{@code create} 端点
 * （{@code ChatStrategyController.create}）仍然直接收实体作为请求体，依赖这些初始化器在字段
 * 缺省时提供默认值——{@code RequestBodyDeserializationTest.chatStrategy_缺省字段保留检索参数默认值}
 * 正向断言它们存活，删掉会让该测试转红。本类要解决的只是「局部更新不该沿用同一份默认值」，
 * 不是「实体的默认值本身不该存在」。
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

    /**
     * 是否启用混合检索（向量 + 关键词）。
     *
     * <p>⚠️ 这里的 {@code null} 与本类其它字段的 {@code null}（「请求体没给这个字段，局部更新
     * 不覆盖」）含义不同——它同时还要与 {@code ChatServiceImpl.searchKnowledge} 的
     * {@code !Boolean.FALSE.equals(strategy.getHybridEnabled())} 契约保持一致：库里的
     * {@code null}（含全部历史存量行，从未 backfill 过这一列）在**运行时**被视为「开启」。
     * 两层 null 语义叠在同一个字段上：局部更新层面「没传」与业务层面「未配置＝默认开启」
     * 恰好都指向"不覆盖/不改变现状"，因此不冲突，但改这个字段前务必想清楚这一点。
     */
    private Boolean hybridEnabled;

    /** 关键词路取回条数 */
    private Integer keywordTopK;

    /** RRF 融合常数 k */
    private Integer rrfK;
}

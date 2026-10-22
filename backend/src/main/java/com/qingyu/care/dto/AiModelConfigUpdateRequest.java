package com.qingyu.care.dto;

import lombok.Data;

/**
 * AI 模型配置局部更新请求。
 *
 * <p><b>为什么不直接用 {@code AiModelConfig} 实体收请求体</b>：实体字段写了初始化器（如
 * {@code private String scenario = "default";}），Jackson 反序列化用的无参构造会原样执行这些
 * 初始化器——于是请求体里<b>没给</b>的字段拿到的是类默认值而非 {@code null}，
 * {@code if (x != null)} 恒真，把库里的值覆盖掉。前端点一下启用开关（只发
 * {@code {"enabled": false}}）就会把 scenario 打回 {@code "default"}、priority 打回 100。
 * 实体上 {@code scenario}/{@code priority}/{@code enabled}/{@code scope} 还额外标了
 * {@code @Builder.Default}，但<b>真正起效的是字段初始化器本身，不是 {@code @Builder.Default}
 * 注解</b>——见下一段。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器</b>——这才是真正的铁律，{@code null} 必须真正表示
 * 「请求体没给这个字段」。<b>删掉 {@code @Builder.Default} 或不加 {@code @Builder} 都不能替代
 * 这条</b>：Jackson 走的无参构造只看字段是否有初始化器，与 {@code @Builder.Default} 无关——
 * {@code @Builder.Default} 只决定 {@code builder().build()} 这条路径是否也应用该默认值，不加
 * 它时 Lombok 会警告「初始化表达式被忽略」，但 {@code new AiModelConfig()} 依旧会应用初始化器。
 * 反证见 {@code KnowledgeBaseRequest.enabled}：它有 {@code @Builder} +
 * {@code private Boolean enabled = true;} 却<b>没有</b> {@code @Builder.Default}，
 * {@code new KnowledgeBaseRequest().getEnabled()} 依然是 {@code true}（它现在没出事只因为
 * 前端建/改知识库都发整份对象，不代表这条路径安全）。
 *
 * <p>⚠️ <b>反过来，{@code AiModelConfig} 实体上那些初始化器不能被删掉</b>：{@code create} 端点
 * （{@code AiModelController.createModel}）仍然直接收实体作为请求体，依赖这些初始化器在字段
 * 缺省时提供默认值——{@code RequestBodyDeserializationTest.aiModelConfig_缺省字段保留场景与归属默认值}
 * 正向断言它们存活，删掉会让该测试转红。本类要解决的只是「局部更新不该沿用同一份默认值」，
 * 不是「实体的默认值本身不该存在」。
 *
 * <p>⚠️ <b>刻意不含 {@code scope} 字段</b>，理由同 {@code AiToolUpdateRequest}：{@code scope}
 * 是客户端可控字段，租户曾可在请求体里自封 {@code PLATFORM} 让自己的模型（连同自己的 apiKey）
 * 出现在所有租户列表里。不放进 DTO 让这条路径在结构上不可能。平台共享模型只能走
 * {@code /v1/platform/models}。
 *
 * <p>⚠️ <b>同样刻意不含 {@code modelType}</b>：类型创建后不可改。把向量模型改成对话模型，它就会进对话路由、
 * 对话请求打到 embeddings 端点；反过来，应用钉着的向量模型变成对话模型，摄取与检索全部失败。
 *
 * @author ruan
 */
@Data
// ⚠️ 不含 provider / endpoint / apiKey：它们是供应商连接的同步副本，在 ModelProviderUpdateRequest 上改，
// 改一次同步到连接下全部模型。模型单独改的话，下次改连接就会被覆盖回去，两边各说各话。
public class AiModelConfigUpdateRequest {
    private String name;
    private String modelId;
    private String scenario;
    private Integer priority;
    private Boolean enabled;
    private String remark;
    /** 向量模型的推荐相似度阈值（对话模型忽略） */
    private Double similarityThreshold;
}

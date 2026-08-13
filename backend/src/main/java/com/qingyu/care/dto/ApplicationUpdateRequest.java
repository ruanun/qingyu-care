package com.qingyu.care.dto;

import lombok.Data;

/**
 * 应用局部更新请求。
 *
 * <p><b>为什么不直接用 {@code Application} 实体收请求体</b>：实体字段写了初始化器（如
 * {@code private String themeColor = "#6366f1";}），Jackson 反序列化用的无参构造会原样执行
 * 这些初始化器——于是请求体里<b>没给</b>的字段拿到的是类默认值而非 {@code null}，
 * {@code if (x != null)} 恒真，把库里的值覆盖掉。前端点一下启用开关（只发
 * {@code {"enabled": false}}）就会把 themeColor 打回 {@code "#6366f1"}。实体上
 * {@code themeColor}/{@code enabled} 还额外标了 {@code @Builder.Default}，但<b>真正起效的是
 * 字段初始化器本身，不是 {@code @Builder.Default} 注解</b>——见下一段。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器</b>——这才是真正的铁律，{@code null} 必须真正表示
 * 「请求体没给这个字段」。<b>删掉 {@code @Builder.Default} 或不加 {@code @Builder} 都不能替代
 * 这条</b>：Jackson 走的无参构造只看字段是否有初始化器，与 {@code @Builder.Default} 无关——
 * {@code @Builder.Default} 只决定 {@code builder().build()} 这条路径是否也应用该默认值，不加
 * 它时 Lombok 会警告「初始化表达式被忽略」，但 {@code new Application()} 依旧会应用初始化器。
 * 反证见 {@code KnowledgeBaseRequest.enabled}：它有 {@code @Builder} +
 * {@code private Boolean enabled = true;} 却<b>没有</b> {@code @Builder.Default}，
 * {@code new KnowledgeBaseRequest().getEnabled()} 依然是 {@code true}（它现在没出事只因为
 * 前端建/改知识库都发整份对象，不代表这条路径安全）。
 *
 * <p>⚠️ <b>反过来，{@code Application} 实体上那些初始化器不能被删掉</b>：{@code create} 端点
 * （{@code ApplicationController.create}）仍然直接收实体作为请求体，依赖这些初始化器在字段
 * 缺省时提供默认值——{@code RequestBodyDeserializationTest.application_缺省字段保留BuilderDefault初值}
 * 正向断言它们存活，删掉会让该测试转红。本类要解决的只是「局部更新不该沿用同一份默认值」，
 * 不是「实体的默认值本身不该存在」。
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

package com.qingyu.care.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 知识库局部更新请求。
 *
 * <p><b>为什么从 {@code KnowledgeBaseRequest} 拆出来</b>：那个类建库、改库共用，而它的
 * {@code enabled} 写了初始化器（{@code private Boolean enabled = true;}）。Jackson 走无参
 * 构造照样应用初始化器，于是请求体<b>不带</b> {@code enabled} 时它也是 {@code true} 而非
 * {@code null}，服务层的 {@code if (request.getEnabled() != null)} 恒真——
 * <b>改一次名字就把已停用的知识库重新启用了</b>，无提示、无日志。
 *
 * <p>⚠️ 这条缺陷被放过两次，都因为同一句错误的安全依据：{@code AiSkillUpdateRequest} 的类注释
 * 里曾写「它现在没出事只因为前端建/改知识库都发整份对象」。实际上前端<b>根本没有调用过</b>更新
 * 知识库这个端点（全前端零命中），该依据从一开始就不成立；而即便成立，端点对任何 API 调用方
 * 都是开放的，安全性不该建立在「前端碰巧发了全量字段」之上。真正的原因是它躲开了两道哨兵——
 * {@code UpdateRequestNoInitializerTest} 与 {@code UpdateRequestFieldCoverageTest} 都按
 * {@code *UpdateRequest} 这个类名后缀发现目标，而它叫 {@code KnowledgeBaseRequest}。
 *
 * <p><b>本类所有字段一律不写初始化器</b>：{@code null} 必须真正表示「请求体没给这个字段」。
 *
 * <p><b>刻意不含 {@code enabled}</b>：启用状态有 {@code PUT /{id}/toggle} 与
 * {@code PUT /{id}/enabled} 两个专用端点，不该再由改名字这条路径顺带设置。该字段在
 * {@code UpdateRequestFieldCoverageTest} 的白名单里有对应声明。
 *
 * <p>⚠️ 反过来，{@code KnowledgeBaseRequest} 上的初始化器<b>不要顺手删掉</b>：它现在是建库
 * 专用（{@code createKnowledgeBase} 仍收它），而 {@code PartialUpdateSemanticsTest} 拿它当
 * 「无 {@code @Builder.Default} 也照样应用初始化器」的反向对照标本。
 *
 * @author ruan
 */
@Data
public class KnowledgeBaseUpdateRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    private String description;
}

package com.qingyu.care.dto;

import lombok.Data;

import java.util.Set;

/**
 * AI Skill 局部更新请求。
 *
 * <p><b>为什么不直接用 {@code AiSkill} 实体收请求体</b>：实体字段写了初始化器（如
 * {@code private Set<Long> toolIds = new LinkedHashSet<>();}），Jackson 反序列化用的无参
 * 构造会原样执行这些初始化器——于是请求体里<b>没给</b>的字段拿到的是类默认值而非
 * {@code null}，{@code if (x != null)} 恒真，把库里的值覆盖掉。
 *
 * <p>⚠️ <b>{@code toolIds} 是本次修复里后果最重的一个</b>：实体上它写的是
 * {@code @Builder.Default private Set<Long> toolIds = new LinkedHashSet<>();}，所以点一下
 * 技能启用开关（只发 {@code {"enabled": false}}）就会把绑定的工具全部清空，技能变空壳且无
 * 任何提示。<b>真正起效的是 {@code = new LinkedHashSet<>()} 这个初始化器，不是
 * {@code @Builder.Default} 注解本身</b>——见下一段。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器</b>——这才是真正的铁律，{@code null} 必须真正表示
 * 「请求体没给这个字段」。<b>删掉 {@code @Builder.Default} 或不加 {@code @Builder} 都不能替代
 * 这条</b>：Jackson 走的无参构造只看字段是否有初始化器，与 {@code @Builder.Default} 无关——
 * {@code @Builder.Default} 只决定 {@code builder().build()} 这条路径是否也应用该默认值，不加
 * 它时 Lombok 会警告「初始化表达式被忽略」，但 {@code new AiSkill()} 依旧会应用初始化器。反证见
 * {@code KnowledgeBaseRequest.enabled}：它有 {@code @Builder} + {@code private Boolean enabled
 * = true;} 却<b>没有</b> {@code @Builder.Default}，{@code new KnowledgeBaseRequest().getEnabled()}
 * 依然是 {@code true}。
 *
 * <p>⚠️ 这里原先写着「它现在没出事只因为前端建/改知识库都发整份对象」——<b>那句话是错的，而且
 * 正是它让缺陷被放过了两次</b>。前端<b>根本没有调用过</b>更新知识库这个端点（全前端零命中），
 * 该依据从一开始就不成立；改名字这条路径当时确实在把已停用的知识库静默启用。现已拆出
 * {@link KnowledgeBaseUpdateRequest}（无初始化器、且不含 {@code enabled}）修掉。
 * <b>教训</b>：「前端碰巧发了全量字段」永远不能作为端点安全的依据——端点对任何 API 调用方都是
 * 开放的，而前端的提交形状会在无人察觉时改变。
 *
 * <p>⚠️ <b>反过来，{@code AiSkill} 实体上 {@code toolIds} 的初始化器不能被删掉</b>：
 * {@code AiSkillController.create} 仍然直接收 {@code AiSkill} 实体作为请求体，请求体不带
 * {@code toolIds} 时依赖这个初始化器给出空集合而非 {@code null}。{@code RequestBodyDeserializationTest}
 * 目前没有覆盖 {@code AiSkill} 这条（只覆盖了 Application/ChatStrategy/AiModelConfig/AiTool/Tenant
 * 五个），但风险同源：删掉会让新建 skill 时 {@code toolIds} 变成 {@code null}，任何后续
 * {@code .add(...)}/{@code .contains(...)} 都会 NPE。本类要解决的只是「局部更新不该沿用同一份
 * 默认值」，不是「实体的默认值本身不该存在」。
 *
 * @author ruan
 */
@Data
public class AiSkillUpdateRequest {
    private String name;
    private String description;
    private String content;
    private Set<Long> toolIds;
    /** ⚠️ 不写初始化器：UpdateRequestNoInitializerTest 会强制这一条 */
    private Set<Long> mcpToolIds;
    private Boolean enabled;
}

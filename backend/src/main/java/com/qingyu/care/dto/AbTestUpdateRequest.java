package com.qingyu.care.dto;

import com.qingyu.care.entity.AbVariant;
import lombok.Data;

import java.util.List;

/**
 * A/B 实验局部更新请求。
 *
 * <p>全分支终审指出 {@code AbTestController.update} 是本次修复完成前<b>唯一幸存</b>的「实体
 * 收请求体」端点（映射在 {@code AbTestService.update}）。它此前无害纯属侥幸——
 * {@code AbExperiment.status} 有初始化器 {@code = ExperimentStatus.DRAFT}，但 update 方法体
 * 里从未读取 {@code patch.getStatus()}，这个字段的默认值无从生效；{@code variants} 有初始化器
 * {@code = new ArrayList<>()}，但映射代码恰好多带了一个 {@code && !patch.getVariants().isEmpty()}
 * 判断，空列表不会覆盖已有变体。这两个巧合都不是设计出来的安全网——下一个人给
 * {@code AbExperiment} 加一个带初始化器的新字段、并在 update 里读它，缺陷会原样复活且没有任何
 * 提示。改用专用 DTO 让这条路径与仓库里其余 7 个 update 端点统一成同一种范式。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器</b>——这是本次修复的铁律，{@code null} 必须真正表示
 * 「请求体没给这个字段」。{@code UpdateRequestNoInitializerTest} 反射扫描 {@code dto} 包下所有
 * {@code *UpdateRequest} 类强制这一点，新增字段带了初始化器会直接转红。
 *
 * <p>⚠️ <b>反过来，{@code AbExperiment} 实体上那些初始化器不能被删掉</b>：{@code create} 路径
 * （{@code AbTestController.create}）仍然直接收实体作为请求体，依赖这些初始化器在字段缺省时
 * 提供默认值——{@code RequestBodyDeserializationTest} 没有专门覆盖 {@code AbExperiment}，但
 * {@code AbTestServiceTest.create_强制置为草稿并清运行时字段} 依赖 {@code status} 的
 * {@code @Builder.Default} 语义与 service 层的显式 {@code setStatus(DRAFT)} 双重保证；本类要
 * 解决的只是「局部更新不该沿用同一份默认值」，不是「实体的默认值本身不该存在」。
 *
 * <p>⚠️ <b>刻意不含 {@code status} 字段</b>：生命周期由 {@code start}/{@code end} 两个专用端点
 * 驱动（{@code DRAFT → RUNNING → ENDED}），{@code update} 只允许 {@code DRAFT} 状态下改
 * 名称/描述/变体，不该给客户端开一条绕过生命周期校验直接改状态的口子。
 *
 * @author ruan
 */
@Data
public class AbTestUpdateRequest {
    private String name;
    private String description;
    private List<AbVariant> variants;
}

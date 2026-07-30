package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A/B 实验变体（嵌入表 ab_experiment_variant）。
 *
 * @author ruan
 */
@Embeddable
@Data
@Builder
// 无参构造显式标 @JsonCreator：Jackson 3 会把 @AllArgsConstructor 当隐式创建器，
// 请求体缺省的属性会以 null 传入并覆盖初值（详见 ChatRequest 注释）。当前三个字段
// 均为无初值的包装类型，行为等价、非缺陷；但本类经 AbExperiment.variants 独立反序列化
// （父类的 @JsonCreator 不传染给嵌套类型），一旦将来加 @Builder.Default 或改用原始类型
// 就会重蹈覆辙，故与其余 8 个受影响类保持一致，纯防御性标注。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class AbVariant {

    /** 变体名（实验内唯一，报表分组键） */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 绑定的问答策略 ID */
    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    /** 流量权重（0~100 整数，实验内合计 100；0 = 不接流量/软下线） */
    @Column(name = "weight", nullable = false)
    private Integer weight;
}

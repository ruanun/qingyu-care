package com.qingyu.care.entity;

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
@NoArgsConstructor
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

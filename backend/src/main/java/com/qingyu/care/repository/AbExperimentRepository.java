package com.qingyu.care.repository;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.enums.ExperimentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * A/B 实验数据访问（实体继承 BaseTenantEntity，租户/应用过滤自动生效）。
 *
 * @author ruan
 */
public interface AbExperimentRepository extends JpaRepository<AbExperiment, Long> {

    /** 当前租户/应用范围内是否存在指定状态实验（范围由 Hibernate 过滤器限定） */
    boolean existsByStatus(ExperimentStatus status);

    /** 取当前范围内一个指定状态实验（同应用最多一个 RUNNING，findFirst 足够） */
    Optional<AbExperiment> findFirstByStatus(ExperimentStatus status);

    List<AbExperiment> findAllByOrderByCreatedAtDesc();

    /** 指定策略是否被指定状态的实验引用（策略删除保护用；派生查询跨 @ElementCollection 属性路径） */
    boolean existsByStatusAndVariants_StrategyId(ExperimentStatus status, Long strategyId);
}

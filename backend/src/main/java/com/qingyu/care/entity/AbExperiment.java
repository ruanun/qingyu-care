package com.qingyu.care.entity;

import com.qingyu.care.enums.ExperimentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A/B 实验实体（应用级归属；同应用同时最多一个 RUNNING，服务层校验；RUNNING 后变体锁定）。
 *
 * @author ruan
 */
@Entity
@Table(name = "ab_experiment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbExperiment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ExperimentStatus status = ExperimentStatus.DRAFT;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ab_experiment_variant", joinColumns = @JoinColumn(name = "experiment_id"))
    @Builder.Default
    private List<AbVariant> variants = new ArrayList<>();

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

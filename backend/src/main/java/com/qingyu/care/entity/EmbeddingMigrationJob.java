package com.qingyu.care.entity;

import com.qingyu.care.enums.EmbeddingMigrationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 切换向量模型的迁移任务（影子重建）。进度落库：任务动辄几十分钟，存内存的话重启即丢、界面也无从恢复。
 *
 * <p>⚠️ 不继承 {@code BaseTenantEntity}、持显式的 tenantId / appId：进度由 {@code @Async} 线程写，那里没有
 * TenantContext（同 {@code WebhookDelivery}、{@code SysAuditLog} 的约定）。读取时的归属校验由服务层按 appId 做。
 *
 * @author ruan
 */
@Entity
@Table(name = "embedding_migration_job", indexes = @Index(name = "idx_emb_migration_app", columnList = "app_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingMigrationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    /** 任务种类；NULL 按 SWITCH 理解（列是后加的） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private com.qingyu.care.enums.EmbeddingJobKind kind;

    /** 切换前的模型（id 与标签都记下：旧模型行之后可能被删，标签用于收尾清理与排查） */
    private Long fromModelId;

    @Column(length = 100)
    private String fromTag;

    @Column(nullable = false)
    private Long toModelId;

    @Column(nullable = false, length = 100)
    private String toTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmbeddingMigrationStatus status;

    /** 需要重嵌的条目数（文档 + QA），开始扫描后才知道 */
    private Integer total;

    private Integer done;

    private Integer failed;

    @Column(length = 500)
    private String message;

    @CreationTimestamp
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}

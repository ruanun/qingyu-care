package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 文档命中日统计（rollup 预聚合，来自 AI 消息 hit_document_ids 展开）。
 * 不继承 BaseTenantEntity（同 SysApiUsage 模式）。
 *
 * @author ruan
 */
@Entity
@Table(name = "stat_doc_hit_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_doc_hit_tenant_app_date_doc",
                columnNames = {"tenant_id", "app_id", "stat_date", "document_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatDocHitDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 当日命中次数 */
    @Column(name = "hit_count", nullable = false)
    private long hitCount;
}

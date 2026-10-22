package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 热点关键词日统计（rollup 预聚合，每租户+应用+日仅存 TopN）。
 * 不继承 BaseTenantEntity（同 SysApiUsage 模式）。
 *
 * @author ruan
 */
@Entity
@Table(name = "stat_hot_keyword_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_hot_keyword_tenant_app_date_kw",
                columnNames = {"tenant_id", "app_id", "stat_date", "keyword"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatHotKeywordDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    /** zhparser 分词后的词元（单字词与超长词已在聚合 SQL 过滤） */
    @Column(name = "keyword", nullable = false, length = 100)
    private String keyword;

    /** 当日词频 */
    @Column(name = "freq", nullable = false)
    private long freq;
}

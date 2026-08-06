package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qingyu.care.enums.SyncInterval;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 爬取站点配置。一个站点爬出 N 篇独立的 {@link Document}（每页一篇），
 * 配置持久化是为了让运营能一键「重新爬取」以发现新页面，而不必重填入口/范围/深度/选择器。
 *
 * <p>显式声明按 {@code id} 判等：不声明会直接继承 {@link BaseTenantEntity} 上
 * {@code @Data} 生成的 equals/hashCode，那个版本只比较 {@code tenantId}/{@code appId}——
 * 两个内容完全不同、但都还没落租户上下文（两者皆为 null）的站点实例会被判定为"相等"，
 * 用在 {@code Set}/{@code Map} 去重时会静默错误合并（{@code ChatAttachment} 踩过同一个坑）。
 *
 * @author ruan
 */
@Entity
@Table(name = "crawl_site")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class CrawlSite extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** 爬出来的页都进这个知识库。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    /** 入口 URL；列宽对齐 {@code document.source_url}。 */
    @Column(name = "entry_url", nullable = false, length = 2048)
    private String entryUrl;

    /**
     * 范围前缀：只爬以此为前缀的 URL。创建时由入口 URL 推导预填，用户可改，
     * 但 host 必须与 {@link #entryUrl} 一致（服务端强制校验，安全边界）。
     */
    @Column(name = "scope_prefix", nullable = false, length = 2048)
    private String scopePrefix;

    /** 单次爬取的页数上限（已按平台硬顶钳制）。 */
    @Column(name = "max_pages", nullable = false)
    private Integer maxPages;

    /** 链接发现深度上限（已按平台硬顶钳制）；入口页为深度 0。 */
    @Column(name = "max_depth", nullable = false)
    private Integer maxDepth;

    /**
     * 正文 CSS 选择器（白名单）。为空则走 {@code WebPageFetcher} 的启发式噪声剔除。
     * 刻意不冗余到 {@code Document} 上——读站点行意味着改了选择器之后，
     * 已建文档的下一轮定期同步立刻用新值。
     */
    @Column(name = "content_selector", length = 500)
    private String contentSelector;

    /** 爬出的每篇文档写入这个同步周期。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_interval", nullable = false, length = 20)
    private SyncInterval syncInterval;

    /** 上次爬取完成时间，列表展示用。 */
    @Column(name = "last_crawled_at")
    private LocalDateTime lastCrawledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

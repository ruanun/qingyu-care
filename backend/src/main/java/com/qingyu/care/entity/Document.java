package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.enums.SyncInterval;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 文档实体
 */
@Entity
@Table(name = "document")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Document extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属知识库
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    /**
     * 文档名称
     */
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * 原始文件名
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * 文档类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private DocumentType documentType;

    /**
     * 文档状态
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status = DocumentStatus.PENDING;

    /**
     * 文件路径
     */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /**
     * 文件大小（字节）
     */
    @Column(name = "file_size")
    private Long fileSize;

    /**
     * 存储键（S3 文件键）
     */
    @Column(name = "storage_key", length = 500)
    private String storageKey;

    /**
     * 文档内容（用于全文搜索备份）
     */
    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 可见性级别（null 视为 PUBLIC，存量零迁移）
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DocumentVisibility visibility = DocumentVisibility.PUBLIC;

    /**
     * 是否参与检索（null 视为启用；禁用不删 chunk，重新启用免重嵌入）
     */
    @Column(name = "enabled")
    private Boolean enabled = true;

    /**
     * 切片数量
     */
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    /**
     * 处理错误信息
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 网页文档的源地址（仅 documentType=URL 有值）；长度上限与前端 safeUrl 一致。 */
    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    /** 正文的 SHA-256，用于同步时判断内容是否变化——没变就不重嵌，省 embedding 成本。 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** 自动同步周期；null 或 MANUAL 表示不参与定时调度。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_interval", length = 20)
    private SyncInterval syncInterval;

    /**
     * 上次成功<b>抓取</b>时间：抓取失败不更新；抓取成功但后续重嵌失败<b>会</b>更新
     * （那一轮确实拿到了新鲜内容，只是没嵌进去，重试由 contentHash 未落库来保证）。
     * 调度器的到期判定读的就是它。
     */
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /**
     * 连续同步失败次数，任何一次成功清零。
     * 列可空：ddl-auto=update 对存量行加 NOT NULL 列会导致启动失败；读取时 null 归 0。
     * @Builder.Default 不可省：Lombok @Builder 会忽略字段初值，漏了它 builder 建出来就是 null。
     */
    @Builder.Default
    @Column(name = "sync_failure_count")
    private Integer syncFailureCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

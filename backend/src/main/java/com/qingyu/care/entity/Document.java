package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
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
     * 切片数量
     */
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    /**
     * 处理错误信息
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

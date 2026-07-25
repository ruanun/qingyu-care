package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * QA 问答对：挂现有知识库；整条「问+答」不切分嵌入共享向量库，供 FAQ 检索。
 */
@Entity
@Table(name = "qa_pair")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaPair extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库（检索范围=enabled 库集合，天然含之） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBase knowledgeBase;

    @Column(columnDefinition = "text", nullable = false)
    private String question;

    @Column(columnDefinition = "text", nullable = false)
    private String answer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private QaPairSource source = QaPairSource.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private QaPairStatus status = QaPairStatus.ACTIVE;

    /** LLM 生成来源文档 id（GENERATED 时记录） */
    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

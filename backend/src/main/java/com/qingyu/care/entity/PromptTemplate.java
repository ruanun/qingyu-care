package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Prompt 模板实体
 * 支持版本管理和激活/回滚
 */
@Entity
@Table(name = "prompt_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptTemplate extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 模板名称（如：default, formal, casual）
     */
    @Column(nullable = false)
    private String name;

    /**
     * Prompt 内容
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 版本号
     */
    @Column(nullable = false)
    private Integer version;

    /**
     * 是否激活（同一时间只能有一个激活版本）
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /**
     * 版本描述/备注
     */
    @Column(length = 500)
    private String description;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

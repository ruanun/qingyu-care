package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AI Skill：Markdown 能力包。content 命中时注入系统提示，并激活其绑定的工具。
 */
@Entity
@Table(name = "ai_skill")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSkill extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 用途描述（本期文档用途，为未来命中触发预留） */
    @Column(length = 500)
    private String description;

    /** Markdown 正文：注入系统提示的 SOP/指令 */
    @Column(columnDefinition = "text")
    private String content;

    /** 绑定的 AiTool id 集合（元素集合，避开 join 表与租户过滤器纠缠） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ai_skill_tool", joinColumns = @JoinColumn(name = "skill_id"))
    @Column(name = "tool_id")
    @Builder.Default
    private Set<Long> toolIds = new LinkedHashSet<>();

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

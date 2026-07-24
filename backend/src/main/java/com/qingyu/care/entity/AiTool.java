package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 工具（HTTP）：模型可调用的可执行原语。urlTemplate 由租户管理员固定，模型只填参数不选 URL。
 */
@Entity
@Table(name = "ai_tool")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTool extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 暴露给 LLM 的工具名（合法标识符，如 query_order） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 给 LLM 判断何时调用的用途描述（命中率关键） */
    @Column(nullable = false, length = 500)
    private String description;

    /** JSON Schema 字符串，描述参数；LLM 据此填参 */
    @Column(columnDefinition = "text")
    private String inputSchema;

    /** HTTP 方法：GET / POST */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String httpMethod = "GET";

    /** 端点模板，含占位符，如 https://api.example.com/orders/{orderId} */
    @Column(nullable = false, length = 1000)
    private String urlTemplate;

    /** 静态鉴权头（如 "Authorization: Bearer xxx"），明文存储（技术债，同 AiModelConfig.apiKey） */
    @Column(length = 1000)
    private String authHeader;

    /** 出站超时（毫秒） */
    @Column(nullable = false)
    @Builder.Default
    private Integer timeoutMs = 5000;

    /** 回灌 LLM 的响应体截断长度 */
    @Column(nullable = false)
    @Builder.Default
    private Integer maxResponseChars = 4000;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

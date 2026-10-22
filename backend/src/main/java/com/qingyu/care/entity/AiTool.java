package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qingyu.care.enums.OwnerScope;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
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
// 无参构造显式标 @JsonCreator：Jackson 3 会把 @AllArgsConstructor 当隐式创建器，
// 请求体缺省的属性会以 null 传入并覆盖 @Builder.Default 初值（详见 ChatRequest 注释）。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
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

    /** 静态鉴权头（如 "Authorization: Bearer xxx"），明文存储（技术债，同 AiModelConfig.apiKey）。
     * {@code WRITE_ONLY}：只读入、不写出，理由同 {@code AiModelConfig.apiKey}（2026-09-25 安全扫描 H2） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
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

    /**
     * 归属范围：TENANT=租户自建；PLATFORM=平台共享（tenant_id/app_id 均为 null，所有租户可绑定使用）。
     * 平台共享行的凭据对租户不可见——见 SharedCredentialMasker。
     *
     * @ColumnDefault 是必需的，不是可选的美化：ai_tool 是非空表，Hibernate 对既有行加
     * 不带 default 的 NOT NULL 列时，单条 DDL（"add column scope varchar(20) not null
     * check (...)"）会被 PostgreSQL 整体拒绝（ERROR: column "scope" ... contains null
     * values），而 SchemaUpdate 对单条 DDL 失败只打 WARN、不阻断启动——后果是列压根没
     * 建出来，应用照常起来，直到运行时查询 ai_tool 才炸，且无任何现象提醒（已真机验证
     * 复现）。带上 default
     * 后 PostgreSQL 用它一次性回填存量行，同一条 DDL 就能成功，不需要额外的手工迁移脚本。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'TENANT'")
    @Builder.Default
    private OwnerScope scope = OwnerScope.TENANT;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

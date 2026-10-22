package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.enums.OwnerScope;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 远端 MCP server 的连接配置。一个 server 提供 N 个工具（{@link McpTool}）。
 *
 * @author ruan
 */
@Entity
@Table(name = "mcp_server")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
// 无参构造显式标 @JsonCreator：Jackson 3 会把 @AllArgsConstructor 当隐式创建器，
// 请求体缺省的属性会以 null 传入并覆盖 @Builder.Default 初值（同 AiTool）。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class McpServer extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名，可中文（如「订单系统」）。仅界面展示，不参与任何标识。 */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 标识符，参与 LLM 可见的工具名（{@code {code}__{toolName}}）。
     *
     * <p>与 name 分开是刻意的：工具名必须是合法标识符，但不该因此逼租户把服务器叫
     * order_system 而不是「订单系统」——那是把数据模型的便利转嫁给用户。范式同 Tenant.code。
     */
    @Column(nullable = false, length = 50)
    private String code;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'STREAMABLE_HTTP'")
    @Builder.Default
    private McpTransport transport = McpTransport.STREAMABLE_HTTP;

    @Column(nullable = false, length = 1000)
    private String url;

    /** 静态鉴权头（如 "Authorization: Bearer xxx"），明文存储（技术债，同 AiTool.authHeader）。
     * {@code WRITE_ONLY}：只读入、不写出，理由同 {@code AiModelConfig.apiKey}（2026-09-25 安全扫描 H2） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 1000)
    private String authHeader;

    /**
     * 出站超时（毫秒）。⚠️ 它是**单次 MCP 请求的响应总时长上限**，不是整次工具调用的预算：
     * 一次 callTool 含 initialize 与 tools/call 两次请求，各自独立享有这一份（≈2×）。
     * 远端持续吐进度也不豁免——慢工具（深度检索类）只能靠调大它救，见
     * {@code McpTimeoutSemanticsTest}。默认给到 10000 而非 HTTP 工具的 5000，是因为
     * MCP 工具普遍比一次 REST 调用重。
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer timeoutMs = 10000;

    /** 回灌 LLM 的响应体截断长度 */
    @Column(nullable = false)
    @Builder.Default
    private Integer maxResponseChars = 4000;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 归属范围。本期恒为 TENANT，为将来的平台共享预留。
     *
     * <p>@ColumnDefault 是必需的而非美化：非空表加不带 default 的 NOT NULL 列时，
     * 单条 DDL 会被 PostgreSQL 拒绝，而 SchemaUpdate 对单条 DDL 失败只打 WARN 不阻断启动
     * ——后果是列压根没建出来、应用照常起、运行时查询才炸（见 AiTool.scope 注释记录的事故）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'TENANT'")
    @Builder.Default
    private OwnerScope scope = OwnerScope.TENANT;

    /** 最后一次成功同步时间 */
    private LocalDateTime lastSyncedAt;

    /** 最后一次同步的失败原因，成功时置 null */
    @Column(length = 1000)
    private String lastSyncError;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

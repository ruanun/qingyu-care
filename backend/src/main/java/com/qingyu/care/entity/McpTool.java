package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.qingyu.care.enums.McpToolStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 从远端 MCP server 同步下来的单个工具。
 *
 * @author ruan
 */
@Entity
@Table(name = "mcp_tool",
        uniqueConstraints = @UniqueConstraint(name = "uk_mcp_tool_server_name",
                columnNames = {"mcp_server_id", "name"}))
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class McpTool extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属 server。
     *
     * <p>⚠️ 存 Long 而不是 @ManyToOne：detached 的 LAZY 代理上除 getId() 外任何取值都会在
     * 非请求线程抛 LazyInitializationException，把代理塞进 Set/调 equals 也会触发
     * （CrawlSite.knowledgeBase 踩过）。省下的一次查询不值得留这颗雷。
     */
    @Column(name = "mcp_server_id", nullable = false)
    private Long mcpServerId;

    /** 远端工具名，同步时写入，不可编辑 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 来自远端，每次同步覆盖 */
    @Column(length = 1000)
    private String description;

    /**
     * 来自远端的 JSON Schema 文本，每次同步覆盖。
     *
     * <p>⚠️ MCP SDK 的 {@code Tool.inputSchema()} 返回的是 Map 而非 String，落库前必须序列化。
     */
    @Column(columnDefinition = "text")
    private String inputSchema;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'ACTIVE'")
    @Builder.Default
    private McpToolStatus status = McpToolStatus.ACTIVE;

    /**
     * 租户标注的只读位，**唯一的安全判据**。默认 false（未知一律当可写、不挂载）。
     *
     * <p>⚠️ 同步绝不能覆盖它：一旦被远端的下一次 schema 更新冲掉，一个可写工具会重新
     * 出现在 resolve() 结果里被 LLM 调用，且完全静默。
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private Boolean readOnly = false;

    /**
     * 远端自报的 readOnlyHint annotation，**仅作界面上的默认建议值，不参与任何安全判定**。
     * 服务端自报、可选不填，不可信。
     */
    private Boolean readOnlyHint;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

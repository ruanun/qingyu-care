package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.qingyu.care.enums.PackageStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 能力包：平台管理员把一组调通的工具 + skill 导出成的可安装单元。
 *
 * <p>⚠️ <b>刻意不继承 {@link BaseTenantEntity}</b>（spec §2.4）：它没有租户维度——平台建、
 * 所有租户可读。继承了反而要为「租户读平台行」再写一遍绕过 {@code @Filter} 的 native 查询
 * （「多级配置回退」那套的成本）。写入侧的隔离靠<b>端点分离</b>：平台端点
 * {@code /v1/platform/packages/**} 要 PLATFORM_ADMIN（由 {@code PlatformEndpointGuardTest}
 * 强制），租户端点只读 + 安装。沿用 {@code SysAuditLog}/{@code WebhookDelivery}/
 * {@code SysApiUsage} 那批显式不继承、自己带字段的先例。
 *
 * <p>⚠️ <b>刻意没有 {@code version} 字段</b>（spec §3.1）：安装是一次性的、装完与包断开，
 * 没有任何逻辑会消费版本号，而一个纯展示的版本号会诱使人以为存在版本管理——「我装的是哪
 * 一版」恰恰是查不出来的，因为不记安装记录（spec §2.3）。
 *
 * @author ruan
 */
@Entity
@Table(name = "tool_package",
        uniqueConstraints = @UniqueConstraint(name = "uk_tool_package_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
// 无参构造显式标 @JsonCreator：Jackson 3 会把 @AllArgsConstructor 当隐式创建器，
// 请求体缺省的属性会以 null 传入并覆盖 @Builder.Default 初值（同 AiTool）。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class ToolPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 唯一标识，租户安装端点用它寻址（{@code POST /v1/packages/{code}/install}）。
     *
     * <p>全局唯一（不分租户），故 {@code findByCode} 返回 {@code Optional} 是安全的。
     * ⚠️ 别照搬 {@code McpServerRepository} 那条「不要加回 findByCode」的警告——那条针对的是
     * {@code McpServer.code}，它只在同一 appId 范围内唯一，全局按 code 查天生返回 0~N 行。
     */
    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * 上架状态。⚠️ 只能经专用端点 {@code publish}/{@code archive} 修改，不走通用 update
     * （同 {@code AbExperiment.status} 由 start/end 驱动的既有取舍）。
     *
     * <p>{@code @ColumnDefault} 对这张<b>新表</b>本不是必需的（建表时没有存量行要回填），
     * 保留是因为下一个往本表加 NOT NULL 列的人会照着现有列写：非空表加不带 default 的
     * NOT NULL 列时，单条 DDL 会被 PostgreSQL 拒绝，而 SchemaUpdate 对单条 DDL 失败只打
     * WARN 不阻断启动——列压根没建出来、应用照常起、运行时查询才炸（见 {@code AiTool.scope}
     * 注释记录的真实事故）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'DRAFT'")
    @Builder.Default
    private PackageStatus status = PackageStatus.DRAFT;

    /**
     * manifest 的 JSON 序列化结果。
     *
     * <p>⚠️ <b>必须是 {@code text}，不能设列宽</b>：长度由包里有多少工具、skill 的 markdown
     * 有多长决定，没有可预估的上限。{@code McpTool.description} 就是设成 {@code varchar(1000)}
     * 之后在真机接入 context7 时整个 server 接不进来（CLAUDE.md「MCP 接入」第 10 条）。
     * 由 {@code PackageEntityIT} 守护；⚠️ 存量库另需迁移 SQL——{@code ddl-auto: update}
     * 只加列、不改已有列的类型。
     */
    @Column(columnDefinition = "text", nullable = false)
    private String manifest;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

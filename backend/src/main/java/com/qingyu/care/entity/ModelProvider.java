package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qingyu.care.enums.OwnerScope;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 模型供应商连接：一家厂商的一套端点 + Key。对话模型与向量模型都挂在连接下面，Key 只在这里填一次。
 *
 * <p>归属是<b>租户级</b>（平台连接 tenant_id 为 NULL）：同一租户的多个应用共用一个连接，模型仍按应用添加。
 * ⚠️ 所以它<b>刻意不继承 {@code BaseTenantEntity}</b>——那上面的 appFilter 是 {@code app_id = :appId}，
 * 带着 X-App-Id 查询时会把这些没有 app_id 的行全部滤掉。租户隔离改由 {@code ModelProviderService}
 * 显式按 tenantId 收窄、按 id 取时显式校验归属。
 *
 * <p>模型行上的 endpoint / apiKey / provider 是本连接的<b>同步副本</b>（改连接时一条 SQL 刷到其下全部模型），
 * 这样对话路由、向量模型注册表、出站守卫等运行时代码一行都不用改。
 *
 * @author ruan
 */
@Entity
@Table(name = "model_provider", indexes = @Index(name = "idx_model_provider_tenant", columnList = "tenant_id"))
@Data
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class ModelProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属租户；NULL = 平台连接。只写出不读入，由服务端决定（同 BaseTenantEntity.tenantId 的理由） */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "tenant_id")
    private Long tenantId;

    /** 归属范围：PLATFORM / TENANT。只写出不读入：客户端自封 PLATFORM 是既有漏洞的老路数 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerScope scope;

    /** 显示名，如「通义千问 · 生产 Key」 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 厂商标识（ModelProviderCatalog 的 key，或自定义），同步到模型行的 provider 列 */
    @Column(name = "provider_key", nullable = false, length = 50)
    private String providerKey;

    /** OpenAI 兼容 base URL；空表示用该厂商的默认端点 */
    @Column(length = 500)
    private String endpoint;

    /** 只写不读，同 AiModelConfig.apiKey */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(length = 500)
    private String remark;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** 界面用：是否已配置 Key（Key 本身永不下发） */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean isHasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}

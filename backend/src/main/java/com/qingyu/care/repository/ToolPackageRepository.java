package com.qingyu.care.repository;

import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 能力包仓库。
 *
 * <p>⚠️ 这里<b>不需要</b> native 查询绕 {@code @Filter}：{@link ToolPackage} 不继承
 * {@code BaseTenantEntity}，压根没有过滤器作用在它身上，普通 JPA 查询对平台与租户是同一份
 * 结果。这正是 spec §2.4 选择不继承的收益——否则「租户读平台行」又要写一遍
 * 「多级配置回退」那套 native 查询。
 *
 * <p>⚠️ {@link #findByCode} 返回 {@code Optional} 是安全的：{@code code} 上有唯一约束
 * {@code uk_tool_package_code} 且不分租户，全局唯一。别照搬 {@code McpServerRepository}
 * 那条「不要加回 findByCode」的警告，那条针对的是只在同 appId 内唯一的 {@code McpServer.code}。
 *
 * @author ruan
 */
@Repository
public interface ToolPackageRepository extends JpaRepository<ToolPackage, Long> {

    Optional<ToolPackage> findByCode(String code);

    /** 租户侧列表：只给已发布的（DRAFT 文案没补完、ARCHIVED 已下架）。 */
    List<ToolPackage> findByStatusOrderByNameAsc(PackageStatus status);

    /** 平台侧列表：三种状态全给，最近导出的排前面。 */
    List<ToolPackage> findAllByOrderByCreatedAtDesc();

    boolean existsByCode(String code);
}

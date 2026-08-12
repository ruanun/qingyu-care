package com.qingyu.care.repository;

import com.qingyu.care.entity.AiTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AiToolRepository extends JpaRepository<AiTool, Long> {

    /** 按 id 集合取启用工具（skill 激活时用）。受 @Filter 保护，只看得到当前租户/应用自有的行。 */
    List<AiTool> findByIdInAndEnabledTrue(Collection<Long> ids);

    /** 显式按 appId 过滤（管理面 list 用，防平台管理员裸 findAll 跨应用读取） */
    List<AiTool> findByAppId(Long appId);

    /**
     * 全部平台共享工具（平台管理面 + 租户列表合并用）。
     *
     * <p>必须用 native SQL：平台共享行 tenant_id IS NULL，Hibernate @Filter 根本看不见它们
     * （同「多级配置回退」那个坑，见 CLAUDE.md）。
     *
     * <p>⚠️ <b>WHERE 里的 {@code tenant_id IS NULL} 不能去掉。</b>{@code scope} 是客户端
     * 可控字段——租户在 POST /v1/ai-tools 的请求体里就能自己填 {@code "scope":"PLATFORM"}，
     * 只判 {@code scope} 等于让租户自己决定要不要被全平台挂载（真机复现过：租户自封后落库
     * scope=PLATFORM + 自己的 tenant_id，随即出现在所有租户的共享工具列表里，带着攻击者的
     * authHeader 被别的租户绑进 skill 调用）。{@code tenant_id IS NULL} 才是「平台所有」的
     * 真凭据，由服务层强制写入（见 AiToolController.create）、请求体改不动。
     */
    @Query(value = "SELECT * FROM ai_tool WHERE scope = 'PLATFORM' AND tenant_id IS NULL ORDER BY name",
           nativeQuery = true)
    List<AiTool> findAllSharedTools();

    /**
     * 按 id 集合取启用的平台共享工具（运行时解析用）。
     *
     * <p>⚠️ <b>WHERE 里的 {@code scope = 'PLATFORM'} 不能去掉。</b>上面的
     * {@code findByIdInAndEnabledTrue} 是 filter-bound 的，别的租户的 id 天然查不出来；
     * 这条是 native 查询、不受 @Filter 保护，少了 scope 条件就变成「任意 id 都能查出来」——
     * 租户只要在自己的 skill 里填上别人的工具 id，就能调用他人工具（连带用掉他人的鉴权头）。
     *
     * <p>⚠️ <b>{@code tenant_id IS NULL} 同样不能去掉。</b>{@code scope} 是客户端可控字段，
     * 租户能在建工具的请求体里自封 {@code PLATFORM}；只判 {@code scope} 等于让租户自己决定
     * 要不要被当成平台共享工具解析出来。{@code tenant_id IS NULL} 才是「平台所有」的真凭据，
     * 由服务层强制、请求体改不动。
     *
     * <p>⚠️ <b>调用方必须在 {@code ids} 为空时短路，不要调用本方法。</b>Spring Data JPA
     * 只对 JPQL 的 {@code IN (:ids)} 做空集合改写，native 查询不享受这个待遇——空集合会被
     * 原样展开成 {@code IN ()}，PostgreSQL 直接语法报错（不是返回空结果，是直接炸）。
     * 仓库里已有同款先例：{@code DocumentRepository.findNamesByIds} 同样是 native
     * {@code IN (:ids)}，其调用方 {@code AnalyticsService} 就写了
     * {@code rankIds.isEmpty() ? Map.of() : documentRepository.findNamesByIds(rankIds)}
     * 短路空集合，本方法的调用方须照此写法处理。
     */
    @Query(value = "SELECT * FROM ai_tool WHERE scope = 'PLATFORM' AND tenant_id IS NULL "
                 + "AND enabled = true AND id IN (:ids)",
           nativeQuery = true)
    List<AiTool> findSharedByIdInAndEnabledTrue(@Param("ids") Collection<Long> ids);
}

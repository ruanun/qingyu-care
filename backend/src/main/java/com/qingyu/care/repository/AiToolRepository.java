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
     */
    @Query(value = "SELECT * FROM ai_tool WHERE scope = 'PLATFORM' ORDER BY name", nativeQuery = true)
    List<AiTool> findAllSharedTools();

    /**
     * 按 id 集合取启用的平台共享工具（运行时解析用）。
     *
     * <p>⚠️ <b>WHERE 里的 {@code scope = 'PLATFORM'} 不能去掉。</b>上面的
     * {@code findByIdInAndEnabledTrue} 是 filter-bound 的，别的租户的 id 天然查不出来；
     * 这条是 native 查询、不受 @Filter 保护，少了 scope 条件就变成「任意 id 都能查出来」——
     * 租户只要在自己的 skill 里填上别人的工具 id，就能调用他人工具（连带用掉他人的鉴权头）。
     */
    @Query(value = "SELECT * FROM ai_tool WHERE scope = 'PLATFORM' AND enabled = true AND id IN (:ids)",
           nativeQuery = true)
    List<AiTool> findSharedByIdInAndEnabledTrue(@Param("ids") Collection<Long> ids);
}

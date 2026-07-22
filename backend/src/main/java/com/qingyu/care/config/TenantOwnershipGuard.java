package com.qingyu.care.config;

/**
 * 租户归属守卫：统一「按 id 加载后」的显式归属校验。
 *
 * <p>Hibernate 6 的 {@code @Filter} 只拦截查询（HQL/派生查询），不拦截 {@code findById}
 * （未设 {@code applyToLoadByKey}）。因此凡是走 {@code findById} 主键加载业务实体的服务，
 * 已登录租户都能枚举 id 跨租户读到他人数据——必须在加载后显式补这道校验，与「查不到」
 * 同语义处理（抛资源不存在），既堵越权也防枚举探测。
 *
 * <p>比对采用对称空容忍语义（范式同 86f6892 ChatFeedbackService / 3469510 AbTestService）：
 * <ul>
 *   <li>上下文租户与实体租户均非空且不相等 → 越权，返回 {@code false}；
 *   <li>上下文为空（平台管理员登录后 tenantId 恒为 null）→ 放行，天然豁免；
 *   <li>实体字段为空（{@code tenant_id IS NULL} 的平台共享资源）→ 放行，保住多级回退语义；
 *   <li>应用维度同理。
 * </ul>
 *
 * @author ruan
 */
public final class TenantOwnershipGuard {

    private TenantOwnershipGuard() {
    }

    /**
     * 校验实体归属是否落在当前租户/应用上下文内。
     *
     * @param entityTenantId 实体的 tenant_id（可空）
     * @param entityAppId    实体的 app_id（可空）
     * @return 属于当前上下文或应放行时 true，明确越权时 false
     */
    public static boolean belongsToCurrentContext(Long entityTenantId, Long entityAppId) {
        Long ctxTenantId = TenantContext.getTenantId();
        if (ctxTenantId != null && entityTenantId != null && !ctxTenantId.equals(entityTenantId)) {
            return false;
        }
        Long ctxAppId = TenantContext.getAppId();
        return ctxAppId == null || entityAppId == null || ctxAppId.equals(entityAppId);
    }

    /**
     * 校验实体是否可在当前上下文中被修改/删除（写路径守卫）。
     *
     * <p>与 {@link #belongsToCurrentContext} 只差一点：平台共享行（tenant_id IS NULL）
     * 仅平台上下文（ctxTenantId 为空——平台管理员或系统内部线程）可写。读路径为保
     * 多级回退语义放行平台行，但写路径若同样放行，租户即可枚举 id 篡改/删除平台
     * 共享模型、平台默认策略/模板/规则，殃及全部租户。
     *
     * <p>含密钥的资源（如 AiModelConfig 的明文 apiKey）读路径也应使用本守卫，
     * 否则租户可经按 id 读取拿到平台凭据。
     *
     * @param entityTenantId 实体的 tenant_id（可空）
     * @param entityAppId    实体的 app_id（可空）
     * @return 当前上下文可写该实体时 true
     */
    public static boolean mutableInCurrentContext(Long entityTenantId, Long entityAppId) {
        Long ctxTenantId = TenantContext.getTenantId();
        if (ctxTenantId == null) {
            return true;
        }
        if (entityTenantId == null || !ctxTenantId.equals(entityTenantId)) {
            return false;
        }
        Long ctxAppId = TenantContext.getAppId();
        return ctxAppId == null || entityAppId == null || ctxAppId.equals(entityAppId);
    }
}

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
}

package com.qingyu.care.config;

import com.qingyu.care.enums.UserRole;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import cn.dev33.satoken.stp.StpUtil;

/**
 * 多租户数据切面
 * 自动为 Hibernate Session 启用过滤器
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantDataAspect {

    private final EntityManager entityManager;

    /**
     * 在 Repository 执行前启用过滤器
     * 拦截 com.qingyu.care.repository 包下的所有方法
     */
    @Before("execution(* com.qingyu.care.repository..*.*(..))")
    public void enableFilters() {
        Long tenantId = TenantContext.getTenantId();
        Long appId = TenantContext.getAppId();

        // 平台管理员且未指定租户上下文：可查看全部数据，不启用过滤
        // （若平台管理员正在操作某个特定租户，Context 中会有 tenantId，走下方过滤逻辑）
        if (isPlatformAdmin() && tenantId == null) {
            return;
        }

        Session session = entityManager.unwrap(Session.class);

        if (tenantId != null) {
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);

            if (appId != null) {
                session.enableFilter("appFilter").setParameter("appId", appId);
            }
        } else {
            // 非平台管理员却缺少租户上下文：属于异常状态，
            // 强制以不可能匹配的租户 ID 过滤，避免越权看到全部数据（安全默认拒绝）
            session.enableFilter("tenantFilter").setParameter("tenantId", -1L);
        }
    }

    private boolean isPlatformAdmin() {
        try {
            if (!StpUtil.isLogin()) return false;
            Object role = StpUtil.getSession().get("role");
            return role != null && UserRole.PLATFORM_ADMIN.name().equals(role.toString());
        } catch (Exception e) {
            return false;
        }
    }
}

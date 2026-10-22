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

        // 有明确租户上下文（管理端已登录并选定租户，或开放 API 鉴权后注入）：按上下文过滤
        if (tenantId != null) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
            if (appId != null) {
                session.enableFilter("appFilter").setParameter("appId", appId);
            }
            return;
        }

        // 无租户上下文时：
        // 1) 平台管理员：可查看全部数据，不过滤；
        // 2) 未登录：登录/注册/开放 API 鉴权等均发生在“未登录”态，需按唯一键
        //    （findByUsername / findByAppKey）跨租户查找。若在此施加过滤，会把待认证的
        //    用户/应用挡在门外——历史 bug：平台管理员 tenant_id=NULL 被 -1 过滤掉而无法登录。
        if (isPlatformAdmin() || !isLoggedIn()) {
            return;
        }

        // 3) 已登录的非管理员却缺少租户上下文：属于异常状态，
        //    强制以不可能匹配的租户 ID 过滤，避免越权看到全部数据（安全默认拒绝）。
        entityManager.unwrap(Session.class)
                .enableFilter("tenantFilter").setParameter("tenantId", -1L);
    }

    /** Sa-Token 是否已登录；未初始化上下文等异常一律按未登录处理。 */
    private boolean isLoggedIn() {
        try {
            return StpUtil.isLogin();
        } catch (Exception e) {
            return false;
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

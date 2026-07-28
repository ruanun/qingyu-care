package com.qingyu.care.util;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.exception.BusinessException;

/**
 * 权限工具：读取当前登录用户角色并做守卫校验。
 * 角色写入方式与 TenantDataAspect 一致（Sa-Token Session 的 "role" 字段）。
 */
public final class SecurityUtil {

    private SecurityUtil() {}

    /** 返回当前登录角色名，未登录或无角色返回 null */
    public static String getRole() {
        try {
            if (!StpUtil.isLogin()) return null;
            Object role = StpUtil.getSession().get("role");
            return role == null ? null : role.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isPlatformAdmin() {
        return UserRole.PLATFORM_ADMIN.name().equals(getRole());
    }

    public static boolean isTenantAdmin() {
        return UserRole.TENANT_ADMIN.name().equals(getRole());
    }

    public static boolean isTenantMember() {
        return UserRole.TENANT_MEMBER.name().equals(getRole());
    }

    /** 要求坐席身份（本租户管理员或成员），否则抛 403。平台管理员无租户上下文，不作为坐席。 */
    public static void requireAgent() {
        if (!isTenantAdmin() && !isTenantMember()) {
            throw new BusinessException(403, "无权限：该操作仅限本租户坐席");
        }
    }

    /** 要求平台管理员，否则抛 403 业务异常（由 GlobalExceptionHandler 返回 403 响应） */
    public static void requirePlatformAdmin() {
        if (!isPlatformAdmin()) {
            throw new BusinessException(403, "无权限：该操作仅限平台管理员");
        }
    }

    /** 要求租户管理员或平台管理员 */
    public static void requireTenantAdmin() {
        if (!isTenantAdmin() && !isPlatformAdmin()) {
            throw new BusinessException(403, "无权限：该操作仅限租户管理员");
        }
    }
}

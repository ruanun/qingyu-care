package com.qingyu.care.enums;

/**
 * 用户角色枚举
 */
public enum UserRole {
    /**
     * 平台管理员 - 可管理所有租户
     */
    PLATFORM_ADMIN,
    
    /**
     * 租户管理员 - 可管理租户下所有应用和成员
     */
    TENANT_ADMIN,
    
    /**
     * 租户成员 - 只能操作被授权的应用
     */
    TENANT_MEMBER
}

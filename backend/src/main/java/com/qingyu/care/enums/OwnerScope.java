package com.qingyu.care.enums;

/**
 * 归属范围：平台级（共享/全局）或租户级
 */
public enum OwnerScope {
    /** 平台级：由平台管理员维护，tenant_id 为 null */
    PLATFORM,
    /** 租户级：归属具体租户 */
    TENANT
}

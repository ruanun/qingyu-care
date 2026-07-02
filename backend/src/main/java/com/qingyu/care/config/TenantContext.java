package com.qingyu.care.config;

/**
 * 租户上下文
 * 用于在请求处理过程中传递租户和应用信息
 */
public class TenantContext {
    
    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> APP_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    
    public static void setTenantId(Long tenantId) {
        TENANT_ID.set(tenantId);
    }
    
    public static Long getTenantId() {
        return TENANT_ID.get();
    }
    
    public static void setAppId(Long appId) {
        APP_ID.set(appId);
    }
    
    public static Long getAppId() {
        return APP_ID.get();
    }
    
    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }
    
    public static Long getUserId() {
        return USER_ID.get();
    }
    
    /**
     * 清理上下文，避免内存泄漏
     */
    public static void clear() {
        TENANT_ID.remove();
        APP_ID.remove();
        USER_ID.remove();
    }
}

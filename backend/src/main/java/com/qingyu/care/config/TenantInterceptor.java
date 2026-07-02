package com.qingyu.care.config;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器
 * 从 Sa-Token Session 中读取租户信息，写入 TenantContext
 */
@Component
@Slf4j
public class TenantInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 如果用户已登录，设置租户上下文
        if (StpUtil.isLogin()) {
            Long userId = StpUtil.getLoginIdAsLong();
            TenantContext.setUserId(userId);
            
            Object tenantId = StpUtil.getSession().get("tenantId");
            if (tenantId != null) {
                TenantContext.setTenantId(Long.valueOf(tenantId.toString()));
            }
            
            // 从请求头获取当前操作的应用 ID (可选)
            String appIdHeader = request.getHeader("X-App-Id");
            if (appIdHeader != null && !appIdHeader.isEmpty()) {
                try {
                    TenantContext.setAppId(Long.valueOf(appIdHeader));
                } catch (NumberFormatException e) {
                    log.warn("Invalid X-App-Id header: {}", appIdHeader);
                }
            }
        }
        
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清理 ThreadLocal，避免内存泄漏
        TenantContext.clear();
    }
}

package com.qingyu.care.config;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.enums.DocumentVisibility;
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
        if (isLoggedIn()) {
            Long userId = StpUtil.getLoginIdAsLong();
            TenantContext.setUserId(userId);

            // 管理后台已登录成员：受信，全见（含 INTERNAL 文档）
            TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);

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

    /**
     * Sa-Token 是否已登录；上下文未初始化等异常一律按未登录处理（与 TenantDataAspect 同款守卫）。
     * Sa-Token 1.42+ 的上下文过滤器只覆盖 REQUEST 分发，SSE 等 ASYNC 二次分发没有上下文。
     */
    private boolean isLoggedIn() {
        try {
            return StpUtil.isLogin();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清理 ThreadLocal，避免内存泄漏
        TenantContext.clear();
    }
}

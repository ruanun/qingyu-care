package com.qingyu.care.config;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 租户拦截器
 * 从 Sa-Token Session 中读取租户信息，写入 TenantContext
 */
@Component
@Slf4j
public class TenantInterceptor implements AsyncHandlerInterceptor {
    
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

    /**
     * 异步请求（DeferredResult/流式响应等）转入 ASYNC 分发时，DispatcherServlet 只调用本方法，
     * 不调用 afterCompletion —— 原始 Tomcat 工作线程上的 ThreadLocal 若不在此清理，会带着
     * 上一个请求的租户上下文回到线程池，被复用它的下一个请求（例如无租户上下文的平台管理员）
     * 静默继承脏值。afterCompletion 仍然覆盖同步请求与 ASYNC 二次分发完成后的清理，二者互补。
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantContext.clear();
    }
}

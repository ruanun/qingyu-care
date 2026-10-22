package com.qingyu.care.config;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.ApplicationRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 租户拦截器
 * 从 Sa-Token Session 中读取租户信息，写入 TenantContext
 *
 * <p>⚠️ {@code X-App-Id} 必须属于当前登录租户才写入上下文（安全审查 M3，2026-09-03）。此前请求头
 * 给什么写什么：读路径被 tenantFilter+appFilter 双重过滤挡住看似无害，但写路径
 * {@code TenantEntityListener} 会把新建行写成「tenant_id=自己, app_id=别人」，随后
 * {@code WebhookDispatcher} 按 app_id 反查出<b>别的租户</b>的回调地址与签名密钥，向对方发出签名
 * 正确的伪造事件。平台管理员没有租户上下文，可在任意应用间切换，不查库。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TenantInterceptor implements AsyncHandlerInterceptor {

    private final ApplicationRepository applicationRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 如果用户已登录，设置租户上下文
        if (isLoggedIn()) {
            Long userId = StpUtil.getLoginIdAsLong();
            TenantContext.setUserId(userId);

            // 管理后台已登录成员：受信，全见（含 INTERNAL 文档）
            TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);

            Long tenantId = null;
            Object tenantIdRaw = StpUtil.getSession().get("tenantId");
            if (tenantIdRaw != null) {
                tenantId = Long.valueOf(tenantIdRaw.toString());
                TenantContext.setTenantId(tenantId);
            }

            // 从请求头获取当前操作的应用 ID (可选)
            String appIdHeader = request.getHeader("X-App-Id");
            if (appIdHeader != null && !appIdHeader.isEmpty()) {
                try {
                    Long appId = Long.valueOf(appIdHeader);
                    if (belongsToTenant(appId, tenantId)) {
                        TenantContext.setAppId(appId);
                    } else {
                        log.warn("X-App-Id={} 不属于当前租户 {}，已忽略 userId={}", appId, tenantId, userId);
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid X-App-Id header: {}", appIdHeader);
                }
            }
        }

        return true;
    }

    /**
     * 平台管理员（无租户上下文）放行；租户用户须查到应用且归属一致。
     * 用 native 反查 FK，不受租户过滤器影响（此时 tenantId 已写入上下文，过滤器会启用）。
     */
    private boolean belongsToTenant(Long appId, Long tenantId) {
        if (tenantId == null) {
            return true;
        }
        return applicationRepository.findTenantIdById(appId)
                .map(tenantId::equals)
                .orElse(false);
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

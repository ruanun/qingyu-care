package com.qingyu.care.config;

import com.qingyu.care.util.SecurityUtil;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 强制 {@link com.qingyu.care.annotation.AdminOnlyWrites}：写端点执行前要求租户管理员或平台管理员。
 *
 * <p>须在 {@link AuditLogAspect}（未设 @Order，即最低优先级）之前拦下：越权请求不进业务方法、也不留
 * 「操作成功」的审计记录。⚠️ 但**不能用 {@code HIGHEST_PRECEDENCE}**：那会排到 Spring 的
 * {@code ExposeInvocationInterceptor}（{@code HIGHEST_PRECEDENCE + 1}）前面，AspectJ 风格的通知拿不到
 * 调用上下文，每次调用直接抛 IllegalStateException——管理员的写操作也会全部 500。
 * {@code AdminOnlyWritesAspectTest} 用真实代理织入，当初正是它抓到的。
 *
 * @author ruan
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminOnlyWritesAspect {

    @Before("@within(com.qingyu.care.annotation.AdminOnlyWrites) && ("
            + "@annotation(org.springframework.web.bind.annotation.PostMapping)"
            + " || @annotation(org.springframework.web.bind.annotation.PutMapping)"
            + " || @annotation(org.springframework.web.bind.annotation.DeleteMapping)"
            + " || @annotation(org.springframework.web.bind.annotation.PatchMapping))"
            + " && !@annotation(com.qingyu.care.annotation.ReadOnlyEndpoint)")
    public void requireAdminForWrites() {
        SecurityUtil.requireTenantAdmin();
    }
}

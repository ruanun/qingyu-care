package com.qingyu.care.config;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.entity.SysAuditLog;
import com.qingyu.care.entity.User;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 审计切面：拦截标注 @AuditLog 的方法，在请求线程内组装日志后异步落库。
 * 任何异常均不影响主流程。
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final UserRepository userRepository;

    /**
     * 脱敏正则：键名含 password / secret / apikey / api_key / token / authheader / accesskey 的字段（覆盖
     * {@code oldPassword}、{@code secretKey}、{@code webhookSecret}、{@code accessToken} 等变体），
     * 值一直遮到「下一个 {@code , 键名=}」或右括号为止——此前只遮到第一个逗号，{@code password=ab,cd} 会漏出 {@code cd}，
     * 且 {@code secretKey=}、{@code token=} 这类键名根本不匹配（2026-09-30 安全审查）。
     */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(\\b[a-z_]*(?:password|secret|apikey|api_key|token|authheader|accesskey)[a-z_]*)="
            + "(.*?)(?=, ?[A-Za-z_][A-Za-z0-9_]*=|[)}\\]]|$)");

    /** 详情最大长度 */
    private static final int MAX_DETAIL = 1000;

    /**
     * 纯函数：脱敏 + 截断，可独立单测。
     * - null 返回 ""
     * - 敏感字段值替换为 ***
     * - 超过 1000 字符截断
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String masked = SENSITIVE.matcher(raw).replaceAll("$1=***");
        return masked.length() > MAX_DETAIL ? masked.substring(0, MAX_DETAIL) : masked;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        // 先执行业务，成功后再记录；失败不记录也不影响主流程
        Object result = pjp.proceed();
        try {
            SysAuditLog record = build(pjp, auditLog);
            auditLogService.saveAsync(record);
        } catch (Exception e) {
            log.warn("组装审计日志失败: {}", e.getMessage());
        }
        return result;
    }

    private SysAuditLog build(ProceedingJoinPoint pjp, AuditLog auditLog) {
        Long userId = null;
        String username = null;
        Long tenantId = TenantContext.getTenantId();
        try {
            if (StpUtil.isLogin()) {
                userId = StpUtil.getLoginIdAsLong();
                Long uid = userId;
                username = userRepository.findById(uid)
                        .map(User::getUsername)
                        .orElse("user#" + uid);
            }
        } catch (Exception e) {
            log.debug("获取审计用户名失败, userId={}: {}", userId, e.getMessage());
        }

        return SysAuditLog.builder()
                .tenantId(tenantId)
                .userId(userId)
                .username(username)
                .action(auditLog.action())
                .targetType(auditLog.targetType())
                .targetId(extractTargetId(pjp))
                .detail(sanitize(Arrays.toString(pjp.getArgs())))
                .ip(clientIp())
                .build();
    }

    /**
     * 约定：若方法首个参数为 Long/String（通常是 @PathVariable id），取之作为 targetId。
     */
    private String extractTargetId(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args.length > 0 && (args[0] instanceof Long || args[0] instanceof String)) {
            return String.valueOf(args[0]);
        }
        return null;
    }

    /**
     * 从 RequestContextHolder 提取客户端 IP，与限流同一口径（{@link OpenApiAuthInterceptor#resolveClientIp}），
     * 别在这里另行解析转发头——原因见那个方法的注释（2026-09-30 安全审查 H2）。
     */
    private String clientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            var req = attrs.getRequest();
            return OpenApiAuthInterceptor.resolveClientIp(req);
        } catch (Exception e) {
            return null;
        }
    }
}

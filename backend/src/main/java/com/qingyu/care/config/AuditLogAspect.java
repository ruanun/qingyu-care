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

    /** 脱敏正则：匹配 apiKey/password/secret/appsecret/authHeader 等敏感字段（到下一个逗号、括号或行尾） */
    private static final Pattern SENSITIVE =
            Pattern.compile("(?i)(apikey|password|secret|appsecret|authheader)=([^,}\\])]*)");

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

    /** 从 RequestContextHolder 提取客户端 IP，X-Forwarded-For 优先。 */
    private String clientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            var req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}

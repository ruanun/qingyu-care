package com.qingyu.care.service;

import com.qingyu.care.entity.SysAuditLog;
import com.qingyu.care.repository.SysAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志服务：异步落库 + 分页动态查询。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final SysAuditLogRepository repository;

    /**
     * 异步落库；任何异常不得影响主流程。
     */
    @Async
    public void saveAsync(SysAuditLog auditLog) {
        try {
            repository.save(auditLog);
        } catch (Exception e) {
            log.warn("写审计日志失败: {}", e.getMessage());
        }
    }

    /**
     * 分页查询。platformAdmin=true 查全部；否则仅本租户。
     * action/username 为空则不过滤。
     */
    public Page<SysAuditLog> query(boolean platformAdmin, Long tenantId,
                                   String action, String username, Pageable pageable) {
        Specification<SysAuditLog> spec = (root, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (!platformAdmin) {
                ps.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (action != null && !action.isBlank()) {
                ps.add(cb.equal(root.get("action"), action));
            }
            if (username != null && !username.isBlank()) {
                // 转义 LIKE 通配符，避免用户输入的 % _ \ 被当作模式字符
                String escaped = username.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                ps.add(cb.like(root.get("username"), "%" + escaped + "%"));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return repository.findAll(spec, pageable);
    }
}

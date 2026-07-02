package com.qingyu.care.repository;

import com.qingyu.care.entity.SysAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * 审计日志仓库，支持分页和动态 Specification 过滤。
 */
@Repository
public interface SysAuditLogRepository
        extends JpaRepository<SysAuditLog, Long>, JpaSpecificationExecutor<SysAuditLog> {
}

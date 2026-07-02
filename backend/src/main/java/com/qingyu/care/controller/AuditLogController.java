package com.qingyu.care.controller;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.PageDTO;
import com.qingyu.care.entity.SysAuditLog;
import com.qingyu.care.service.AuditLogService;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作审计日志查询接口（分页 + action/username 过滤）。
 * - 平台管理员：查全量日志。
 * - 租户管理员：仅查本租户日志。
 */
@RestController
@RequestMapping("/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * 分页查询审计日志。
     *
     * @param action   动作过滤（可选，精确匹配）
     * @param username 操作人过滤（可选，模糊匹配）
     * @param page     页码（从 0 开始，默认 0）
     * @param size     每页大小（默认 20）
     */
    @GetMapping
    public ApiResponse<PageDTO<SysAuditLog>> query(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // 租户管理员或平台管理员均可访问
        SecurityUtil.requireTenantAdmin();

        boolean platformAdmin = SecurityUtil.isPlatformAdmin();
        Long tenantId = platformAdmin ? null : TenantContext.getTenantId();

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageDTO<SysAuditLog> result = PageDTO.from(
                auditLogService.query(platformAdmin, tenantId, action, username, pageable));

        return ApiResponse.success(result);
    }
}

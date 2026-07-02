package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户管理 API (仅平台管理员可用)
 */
@RestController
@RequestMapping("/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;
    private final AuthService authService;

    /**
     * 获取所有租户
     */
    @GetMapping
    public ApiResponse<List<Tenant>> getAll() {
        checkPlatformAdmin();
        return ApiResponse.success(tenantRepository.findAll());
    }

    /**
     * 获取指定租户
     */
    @GetMapping("/{id}")
    public ApiResponse<Tenant> getById(@PathVariable Long id) {
        checkPlatformAdmin();
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("租户", id.toString()));
        return ApiResponse.success(tenant);
    }

    /**
     * 创建租户
     */
    @AuditLog(action = "TENANT_CREATE", targetType = "Tenant")
    @PostMapping
    public ApiResponse<Tenant> create(@RequestBody Tenant tenant) {
        checkPlatformAdmin();
        if (tenantRepository.existsByCode(tenant.getCode())) {
            throw new InvalidOperationException("租户编码已存在");
        }
        tenant.setId(null);
        return ApiResponse.success("租户已创建", tenantRepository.save(tenant));
    }

    /**
     * 更新租户
     */
    @AuditLog(action = "TENANT_UPDATE", targetType = "Tenant")
    @PutMapping("/{id}")
    public ApiResponse<Tenant> update(@PathVariable Long id, @RequestBody Tenant tenant) {
        checkPlatformAdmin();
        Tenant existing = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("租户", id.toString()));
        
        if (tenant.getName() != null) existing.setName(tenant.getName());
        if (tenant.getContactName() != null) existing.setContactName(tenant.getContactName());
        if (tenant.getContactPhone() != null) existing.setContactPhone(tenant.getContactPhone());
        if (tenant.getContactEmail() != null) existing.setContactEmail(tenant.getContactEmail());
        if (tenant.getStatus() != null) existing.setStatus(tenant.getStatus());
        if (tenant.getPlan() != null) existing.setPlan(tenant.getPlan());
        if (tenant.getExpireTime() != null) existing.setExpireTime(tenant.getExpireTime());
        
        return ApiResponse.success("租户已更新", tenantRepository.save(existing));
    }

    /**
     * 删除租户
     */
    @AuditLog(action = "TENANT_DELETE", targetType = "Tenant")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        checkPlatformAdmin();
        tenantRepository.deleteById(id);
        return ApiResponse.success("租户已删除", null);
    }

    /**
     * 冻结/解冻租户
     */
    @AuditLog(action = "TENANT_TOGGLE_STATUS", targetType = "Tenant")
    @PostMapping("/{id}/toggle-status")
    public ApiResponse<Tenant> toggleStatus(@PathVariable Long id) {
        checkPlatformAdmin();
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("租户", id.toString()));
        
        tenant.setStatus(tenant.getStatus() == TenantStatus.ACTIVE ? TenantStatus.FROZEN : TenantStatus.ACTIVE);
        return ApiResponse.success("状态已更新", tenantRepository.save(tenant));
    }

    private void checkPlatformAdmin() {
        if (!authService.isPlatformAdmin()) {
            throw new InvalidOperationException("仅平台管理员可操作");
        }
    }
}

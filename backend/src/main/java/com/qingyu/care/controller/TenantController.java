package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.TenantUpdateRequest;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.SessionRevocationService;
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
    private final SessionRevocationService sessionRevocationService;

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
    public ApiResponse<Tenant> update(@PathVariable Long id, @RequestBody TenantUpdateRequest tenant) {
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
        // 续费：已被到期任务停用的租户，延长到期时间后自动恢复——否则管理员改完日期还得再去点一次「恢复激活」，
        // 而列表上仍显示「已过期」，看起来像续费没生效。请求里显式给了 status 时以请求为准。
        if (tenant.getStatus() == null && existing.getStatus() == TenantStatus.EXPIRED
                && existing.getExpireTime() != null && existing.getExpireTime().isAfter(java.time.LocalDateTime.now())) {
            existing.setStatus(TenantStatus.ACTIVE);
        }

        Tenant saved = tenantRepository.save(existing);
        // 冻结或改成已过期：该租户已登录的用户立即下线，而不是等 token 自然过期（2026-09-25 安全扫描 M1）
        boolean inactive = saved.getStatus() != TenantStatus.ACTIVE;
        boolean expired = saved.getExpireTime() != null && saved.getExpireTime().isBefore(java.time.LocalDateTime.now());
        if (inactive || expired) {
            sessionRevocationService.revokeTenant(saved.getId(), inactive ? "租户停用" : "租户已到期");
        }
        return ApiResponse.success("租户已更新", saved);
    }

    /**
     * 删除租户
     */
    @AuditLog(action = "TENANT_DELETE", targetType = "Tenant")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        checkPlatformAdmin();
        sessionRevocationService.revokeTenant(id, "租户删除"); // 先踢：删完就查不到该租户有哪些用户了
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
        
        if (tenant.getStatus() != TenantStatus.ACTIVE && tenant.getExpireTime() != null
                && tenant.getExpireTime().isBefore(java.time.LocalDateTime.now())) {
            // 不拦的话恢复后最多 10 分钟就被到期任务停回去，管理员会以为按钮没生效
            throw new InvalidOperationException("该租户套餐已到期，请先在编辑里延长到期时间");
        }
        tenant.setStatus(tenant.getStatus() == TenantStatus.ACTIVE ? TenantStatus.FROZEN : TenantStatus.ACTIVE);
        Tenant saved = tenantRepository.save(tenant);
        if (saved.getStatus() != TenantStatus.ACTIVE) {
            sessionRevocationService.revokeTenant(saved.getId(), "租户冻结");
        }
        return ApiResponse.success("状态已更新", saved);
    }

    private void checkPlatformAdmin() {
        if (!authService.isPlatformAdmin()) {
            throw new InvalidOperationException("仅平台管理员可操作");
        }
    }
}

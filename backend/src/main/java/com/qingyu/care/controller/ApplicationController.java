package com.qingyu.care.controller;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 应用管理 API
 */
@RestController
@RequestMapping("/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationRepository applicationRepository;
    private final TenantRepository tenantRepository;
    private final AuthService authService;

    /**
     * 获取应用列表
     * - 平台管理员: 返回所有应用
     * - 租户用户: 返回当前租户的应用
     */
    @GetMapping
    public ApiResponse<List<Application>> getAll() {
        if (authService.isPlatformAdmin()) {
            // 平台管理员查看所有应用
            return ApiResponse.success(applicationRepository.findAll());
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new InvalidOperationException("无法获取租户信息");
        }
        List<Application> apps = applicationRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        return ApiResponse.success(apps);
    }

    /**
     * 获取指定应用
     */
    @GetMapping("/{id}")
    public ApiResponse<Application> getById(@PathVariable Long id) {
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);
        return ApiResponse.success(app);
    }

    /**
     * 创建应用
     * - 平台管理员: 需在 application.tenant.id 中指定租户
     * - 租户用户: 自动绑定当前租户
     */
    @PostMapping
    public ApiResponse<Application> create(@RequestBody Application application) {
        Tenant tenant;
        if (authService.isPlatformAdmin()) {
            // 平台管理员需指定租户
            if (application.getTenant() == null || application.getTenant().getId() == null) {
                throw new InvalidOperationException("平台管理员创建应用需指定租户 ID");
            }
            tenant = tenantRepository.findById(application.getTenant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("租户", application.getTenant().getId().toString()));
        } else {
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                throw new InvalidOperationException("无法获取租户信息");
            }
            tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("租户", tenantId.toString()));
        }
        
        application.setId(null);
        application.setTenant(tenant);
        application.setAppKey("app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        application.setAppSecret(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        
        return ApiResponse.success("应用已创建", applicationRepository.save(application));
    }

    /**
     * 更新应用
     */
    @PutMapping("/{id}")
    public ApiResponse<Application> update(@PathVariable Long id, @RequestBody Application application) {
        Application existing = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(existing);
        
        if (application.getName() != null) existing.setName(application.getName());
        if (application.getDescription() != null) existing.setDescription(application.getDescription());
        if (application.getAvatar() != null) existing.setAvatar(application.getAvatar());
        if (application.getWelcomeMessage() != null) existing.setWelcomeMessage(application.getWelcomeMessage());
        if (application.getThemeColor() != null) existing.setThemeColor(application.getThemeColor());
        if (application.getEnabled() != null) existing.setEnabled(application.getEnabled());
        
        return ApiResponse.success("应用已更新", applicationRepository.save(existing));
    }

    /**
     * 删除应用
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);
        applicationRepository.deleteById(id);
        return ApiResponse.success("应用已删除", null);
    }

    /**
     * 重新生成应用密钥
     */
    @PostMapping("/{id}/regenerate-secret")
    public ApiResponse<Application> regenerateSecret(@PathVariable Long id) {
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);
        
        app.setAppSecret(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        return ApiResponse.success("密钥已重新生成", applicationRepository.save(app));
    }

    private Long getCurrentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // 平台管理员没有租户
            if (authService.isPlatformAdmin()) {
                throw new InvalidOperationException("平台管理员请使用租户管理接口");
            }
            throw new InvalidOperationException("无法获取租户信息");
        }
        return tenantId;
    }

    private void checkAppAccess(Application app) {
        if (authService.isPlatformAdmin()) {
            return; // 平台管理员可访问所有应用
        }
        Long tenantId = TenantContext.getTenantId();
        if (!app.getTenant().getId().equals(tenantId)) {
            throw new InvalidOperationException("无权访问该应用");
        }
    }
}

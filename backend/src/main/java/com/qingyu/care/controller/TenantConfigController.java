package com.qingyu.care.controller;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 租户级配置（租户管理员，作用于当前登录租户） */
@RestController
@RequestMapping("/v1/tenant/config")
@RequiredArgsConstructor
public class TenantConfigController {

    private final ConfigService configService;

    @GetMapping
    public ApiResponse<Map<String, String>> get() {
        SecurityUtil.requireTenantAdmin();
        Long tenantId = requireTenantId();
        return ApiResponse.success(configService.getAll(OwnerScope.TENANT, tenantId));
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody Map<String, String> values) {
        SecurityUtil.requireTenantAdmin();
        Long tenantId = requireTenantId();
        configService.setAll(OwnerScope.TENANT, tenantId, values);
        return ApiResponse.success("租户配置已更新", null);
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(400, "当前账号无所属租户，无法读写租户配置");
        }
        return tenantId;
    }
}

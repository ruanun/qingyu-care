package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.QuotaViewDTO;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费/额度查询。面向租户用户；平台管理员无租户上下文返回空视图。
 * 鉴权由全局 SaInterceptor 兜底（/v1/** 需登录），无需 @SaCheckLogin。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/billing")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "计费与额度")
public class BillingController {

    private final QuotaService quotaService;
    private final AuthService authService;

    /**
     * 查看本租户本月额度使用情况。
     * 平台管理员调用时 tenantId=null，返回空视图（plan=null）。
     */
    @GetMapping("/quota")
    @Operation(summary = "查看本租户本月额度使用")
    public ApiResponse<QuotaViewDTO> quota() {
        return ApiResponse.success(quotaService.view(authService.getCurrentTenantId()));
    }
}

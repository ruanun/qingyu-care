package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.pkg.PackageDetailDTO;
import com.qingyu.care.dto.pkg.PackageInstallRequest;
import com.qingyu.care.dto.pkg.PackageInstallResult;
import com.qingyu.care.service.pkg.PackageInstallService;
import com.qingyu.care.service.pkg.ToolPackageService;
import com.qingyu.care.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工具商店的租户侧：浏览已发布的能力包、填自己的凭据一键安装。
 *
 * <p>⚠️ 返回的是 {@link PackageDetailDTO} 而<b>不是</b> {@code ToolPackage} 实体：后者带着
 * manifest 全文，里面的 {@code urlTemplate} 与 MCP 地址是平台侧的实现细节（可能含内网地址），
 * 租户拿到既无用又扩大暴露面。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/packages")
@RequiredArgsConstructor
@Tag(name = "工具商店（租户侧）", description = "浏览与安装能力包")
public class ToolPackageController {

    private final ToolPackageService packageService;
    private final PackageInstallService installService;

    @GetMapping
    @Operation(summary = "已发布的能力包列表")
    public ApiResponse<List<PackageDetailDTO>> list() {
        SecurityUtil.requireTenantAdmin();
        return ApiResponse.success(packageService.listPublishedDetails());
    }

    @GetMapping("/{code}")
    @Operation(summary = "包详情（含要填的变量表）")
    public ApiResponse<PackageDetailDTO> detail(@PathVariable String code) {
        SecurityUtil.requireTenantAdmin();
        return ApiResponse.success(packageService.detailOf(packageService.getPublished(code)));
    }

    /**
     * 安装。
     *
     * <p>⚠️ 必须打 {@code @AuditLog}：它一次性在租户名下建出一批<b>带凭据</b>的工具与 MCP 服务，
     * 事后要能查是谁在什么时候装的（spec §4.2）。
     */
    @PostMapping("/{code}/install")
    @Operation(summary = "安装到当前应用")
    @AuditLog(action = "PACKAGE_INSTALL", targetType = "ToolPackage")
    public ApiResponse<PackageInstallResult> install(@PathVariable String code,
                                                     @RequestBody PackageInstallRequest request) {
        SecurityUtil.requireTenantAdmin();
        PackageInstallResult result = installService.install(code, request.getVariables());
        return ApiResponse.success("安装完成", result);
    }
}

package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.pkg.PackageExportRequest;
import com.qingyu.care.dto.pkg.PackageExportResult;
import com.qingyu.care.dto.pkg.ToolPackageUpdateRequest;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.service.pkg.ToolPackageService;
import com.qingyu.care.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 能力包的平台侧管理（仅平台管理员）：导出上架、编辑文案、发布/下架。
 *
 * <p>⚠️ <b>每个端点方法都必须第一行调 {@link SecurityUtil#requirePlatformAdmin()}</b>——
 * 本仓库没有声明式角色注解，授权就是这一句。{@code PlatformEndpointGuardTest} 会扫描强制，
 * 漏写当场红。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/platform/packages")
@RequiredArgsConstructor
@Tag(name = "能力包（平台侧）", description = "把调通的工具与技能导出成可安装的能力包")
public class PlatformPackageController {

    private final ToolPackageService packageService;

    @GetMapping
    @Operation(summary = "包列表（含草稿与已下架）")
    public ApiResponse<List<ToolPackage>> list() {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(packageService.listAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "包详情（含 manifest 全文，供编辑与发布前逐字核对）")
    public ApiResponse<ToolPackage> getById(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(packageService.getById(id));
    }

    @PostMapping("/export")
    @Operation(summary = "从现成配置导出成草稿包")
    @AuditLog(action = "PACKAGE_EXPORT", targetType = "ToolPackage")
    public ApiResponse<PackageExportResult> export(@RequestBody PackageExportRequest request) {
        SecurityUtil.requirePlatformAdmin();
        PackageExportResult result = packageService.export(request);
        return ApiResponse.success("已导出为草稿包，请补完文案与变量说明后发布", result);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新包文案或 manifest")
    @AuditLog(action = "PACKAGE_UPDATE", targetType = "ToolPackage")
    public ApiResponse<ToolPackage> update(@PathVariable Long id, @RequestBody ToolPackageUpdateRequest request) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success("已更新", packageService.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "发布（发布前会重新校验一遍 manifest）")
    @AuditLog(action = "PACKAGE_PUBLISH", targetType = "ToolPackage")
    public ApiResponse<ToolPackage> publish(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success("已发布，租户现在可以看到并安装它", packageService.publish(id));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "下架（已装出去的副本不受影响）")
    @AuditLog(action = "PACKAGE_ARCHIVE", targetType = "ToolPackage")
    public ApiResponse<ToolPackage> archive(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success("已下架，已安装的副本不受影响", packageService.archive(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除包")
    @AuditLog(action = "PACKAGE_DELETE", targetType = "ToolPackage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        packageService.delete(id);
        return ApiResponse.success("已删除", null);
    }
}

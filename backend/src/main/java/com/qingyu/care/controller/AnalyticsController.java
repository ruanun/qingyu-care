package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.UsageAnalyticsDTO;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.AnalyticsRollupService;
import com.qingyu.care.service.AnalyticsService;
import com.qingyu.care.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 用量统计看板 API。需登录（/v1/** 由 Sa-Token 拦截器要求登录）；
 * 聚合范围按角色在 service 内强制，不信任前端传参。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "用量统计看板")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final AnalyticsRollupService rollupService;
    private final AuthService authService;

    @GetMapping("/usage")
    @Operation(summary = "用量统计概览", description = "按角色自适应返回汇总/趋势/排行；days∈{7,30,90}，appId 仅租户视角有效")
    public ApiResponse<UsageAnalyticsDTO> usage(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long appId) {
        return ApiResponse.success(analyticsService.getUsage(days, appId));
    }

    @PostMapping("/rollup/backfill")
    @AuditLog(action = "ANALYTICS_BACKFILL", targetType = "ANALYTICS")
    @Operation(summary = "手动回填分析统计", description = "平台管理员专用；逐日幂等重算 [from, to]，返回成功天数")
    public ApiResponse<Integer> backfill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!authService.isPlatformAdmin()) {
            throw new BusinessException(403, "仅平台管理员可执行回填");
        }
        return ApiResponse.success(rollupService.backfill(from, to));
    }
}

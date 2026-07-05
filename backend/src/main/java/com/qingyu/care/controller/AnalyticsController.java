package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.UsageAnalyticsDTO;
import com.qingyu.care.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/usage")
    @Operation(summary = "用量统计概览", description = "按角色自适应返回汇总/趋势/排行；days∈{7,30,90}，appId 仅租户视角有效")
    public ApiResponse<UsageAnalyticsDTO> usage(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long appId) {
        return ApiResponse.success(analyticsService.getUsage(days, appId));
    }
}

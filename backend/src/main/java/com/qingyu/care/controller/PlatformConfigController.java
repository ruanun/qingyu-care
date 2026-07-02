package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 平台级配置（仅平台管理员） */
@RestController
@RequestMapping("/v1/platform/config")
@RequiredArgsConstructor
public class PlatformConfigController {

    private final ConfigService configService;

    @GetMapping
    public ApiResponse<Map<String, String>> get() {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(configService.getAll(OwnerScope.PLATFORM, null));
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody Map<String, String> values) {
        SecurityUtil.requirePlatformAdmin();
        configService.setAll(OwnerScope.PLATFORM, null, values);
        return ApiResponse.success("平台配置已更新", null);
    }
}

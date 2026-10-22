package com.qingyu.care.controller;

import com.qingyu.care.annotation.AdminOnlyWrites;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.service.HandoverRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 转人工规则管理 API
 */
@RestController
@AdminOnlyWrites
@RequestMapping("/v1/handover-rules")
@RequiredArgsConstructor
public class HandoverRuleController {

    private final HandoverRuleService ruleService;

    /**
     * 获取所有规则
     */
    @GetMapping
    public ApiResponse<List<HandoverRule>> getAllRules() {
        return ApiResponse.success(ruleService.getAllRules());
    }

    /**
     * 获取指定规则
     */
    @GetMapping("/{id}")
    public ApiResponse<HandoverRule> getById(@PathVariable Long id) {
        return ApiResponse.success(ruleService.getById(id));
    }

    /**
     * 更新规则
     */
    @PutMapping("/{id}")
    public ApiResponse<HandoverRule> updateRule(
            @PathVariable Long id,
            @RequestBody UpdateRuleRequest request) {
        HandoverRule rule = ruleService.updateRule(id, request.enabled(), request.config());
        return ApiResponse.success("规则已更新", rule);
    }

    /**
     * 更新规则请求
     */
    public record UpdateRuleRequest(
            Boolean enabled,
            String config
    ) {}
}

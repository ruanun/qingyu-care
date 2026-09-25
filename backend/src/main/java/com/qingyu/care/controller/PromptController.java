package com.qingyu.care.controller;

import com.qingyu.care.annotation.AdminOnlyWrites;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Prompt 模板管理 API
 */
@RestController
@AdminOnlyWrites
@RequestMapping("/v1/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptTemplateService promptService;

    /**
     * 获取所有版本
     */
    @GetMapping
    public ApiResponse<List<PromptTemplate>> getAllVersions() {
        return ApiResponse.success(promptService.getAllVersions());
    }

    /**
     * 获取当前激活版本
     */
    @GetMapping("/active")
    public ApiResponse<PromptTemplate> getActiveVersion() {
        return ApiResponse.success(promptService.getActiveTemplate());
    }

    /**
     * 获取指定版本
     */
    @GetMapping("/{id}")
    public ApiResponse<PromptTemplate> getById(@PathVariable Long id) {
        return ApiResponse.success(promptService.getById(id));
    }

    /**
     * 创建新版本
     */
    @PostMapping
    public ApiResponse<PromptTemplate> createVersion(@RequestBody CreatePromptRequest request) {
        PromptTemplate template = promptService.createVersion(
                request.name(),
                request.content(),
                request.description()
        );
        return ApiResponse.success(template);
    }

    /**
     * 激活指定版本
     */
    @PutMapping("/{id}/activate")
    public ApiResponse<PromptTemplate> activateVersion(@PathVariable Long id) {
        return ApiResponse.success(promptService.activateVersion(id));
    }

    /**
     * 删除版本
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteVersion(@PathVariable Long id) {
        promptService.deleteVersion(id);
        return ApiResponse.success(null);
    }

    /**
     * 创建 Prompt 请求
     */
    public record CreatePromptRequest(
            String name,
            String content,
            String description
    ) {}
}

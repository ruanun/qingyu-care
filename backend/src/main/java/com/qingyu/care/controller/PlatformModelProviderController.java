package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.ModelProviderUpdateRequest;
import com.qingyu.care.entity.ModelProvider;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.provider.ModelProviderService;
import com.qingyu.care.service.provider.RemoteModelLister;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 平台供应商连接（仅平台管理员）。其下的模型即平台共享模型，所有租户可用、Key 对租户不可见。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/platform/model-providers")
@RequiredArgsConstructor
public class PlatformModelProviderController {

    private final ModelProviderService service;

    @GetMapping
    public ApiResponse<List<ModelProviderService.ProviderWithModels>> list() {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(service.list(OwnerScope.PLATFORM));
    }

    @PostMapping
    @AuditLog(action = "SHARED_MODEL_PROVIDER_CREATE", targetType = "ModelProvider")
    public ApiResponse<ModelProvider> create(@RequestBody ModelProvider req) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success("连接已创建", service.create(req, OwnerScope.PLATFORM));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "SHARED_MODEL_PROVIDER_UPDATE", targetType = "ModelProvider")
    public ApiResponse<ModelProvider> update(@PathVariable Long id, @RequestBody ModelProviderUpdateRequest req) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success("连接已更新", service.update(id, req, OwnerScope.PLATFORM));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "SHARED_MODEL_PROVIDER_DELETE", targetType = "ModelProvider")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        service.delete(id, OwnerScope.PLATFORM);
        return ApiResponse.success("连接已删除", null);
    }

    @PostMapping("/{id}/remote-models")
    public ApiResponse<List<RemoteModelLister.RemoteModel>> remoteModels(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(service.remoteModels(id, OwnerScope.PLATFORM));
    }

    @PostMapping("/{id}/models")
    @AuditLog(action = "SHARED_MODEL_BATCH_CREATE", targetType = "AiModelConfig")
    public ApiResponse<List<ModelProviderService.AddResult>> addModels(@PathVariable Long id, @RequestBody List<ModelProviderService.NewModel> models) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(service.addModels(id, models, OwnerScope.PLATFORM));
    }
}

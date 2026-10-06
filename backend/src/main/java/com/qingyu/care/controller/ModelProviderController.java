package com.qingyu.care.controller;

import com.qingyu.care.annotation.AdminOnlyWrites;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.ModelProviderUpdateRequest;
import com.qingyu.care.entity.ModelProvider;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.provider.ModelProviderService;
import com.qingyu.care.service.provider.RemoteModelLister;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户的供应商连接（租户级，同租户各应用共用）。模型添加到 X-App-Id 指定的当前应用。
 *
 * @author ruan
 */
@RestController
@AdminOnlyWrites
@RequestMapping("/v1/model-providers")
@RequiredArgsConstructor
@Tag(name = "ModelProvider", description = "供应商连接")
public class ModelProviderController {

    private final ModelProviderService service;

    @GetMapping
    @Operation(summary = "本租户的供应商连接，附当前应用在每个连接下的模型")
    public ApiResponse<List<ModelProviderService.ProviderWithModels>> list() {
        return ApiResponse.success(service.list(OwnerScope.TENANT));
    }

    @PostMapping
    @AuditLog(action = "MODEL_PROVIDER_CREATE", targetType = "ModelProvider")
    public ApiResponse<ModelProvider> create(@RequestBody ModelProvider req) {
        return ApiResponse.success("连接已创建", service.create(req, OwnerScope.TENANT));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "MODEL_PROVIDER_UPDATE", targetType = "ModelProvider")
    public ApiResponse<ModelProvider> update(@PathVariable Long id, @RequestBody ModelProviderUpdateRequest req) {
        return ApiResponse.success("连接已更新", service.update(id, req, OwnerScope.TENANT));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "MODEL_PROVIDER_DELETE", targetType = "ModelProvider")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id, OwnerScope.TENANT);
        return ApiResponse.success("连接已删除", null);
    }

    /**
     * 拉远端模型列表（兼作测试连接）。用 POST 而不是 GET：它会拿租户的 Key 真实出站，只给管理员
     * （{@code @AdminOnlyWrites} 只拦写方法）。
     */
    @PostMapping("/{id}/remote-models")
    public ApiResponse<List<RemoteModelLister.RemoteModel>> remoteModels(@PathVariable Long id) {
        return ApiResponse.success(service.remoteModels(id, OwnerScope.TENANT));
    }

    @PostMapping("/{id}/models")
    @AuditLog(action = "MODEL_BATCH_CREATE", targetType = "AiModelConfig")
    @Operation(summary = "在连接下批量添加模型到当前应用", description = "向量模型逐个真实探测维度，单项失败不影响其他项")
    public ApiResponse<List<ModelProviderService.AddResult>> addModels(@PathVariable Long id, @RequestBody List<ModelProviderService.NewModel> models) {
        return ApiResponse.success(service.addModels(id, models, OwnerScope.TENANT));
    }
}

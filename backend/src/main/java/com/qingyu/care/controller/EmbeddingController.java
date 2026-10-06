package com.qingyu.care.controller;

import com.qingyu.care.annotation.AdminOnlyWrites;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.EmbeddingStateDTO;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.service.embedding.EmbeddingMigrationService;
import com.qingyu.care.service.embedding.EmbeddingModelRegistry;
import com.qingyu.care.service.embedding.EmbeddingModelResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 应用级向量模型：查看当前模型、切换（影子重建）、取消 / 继续迁移任务。
 * 作用于请求头 X-App-Id 指定的应用。
 *
 * @author ruan
 */
@RestController
@AdminOnlyWrites
@RequestMapping("/v1/embedding")
@RequiredArgsConstructor
@Tag(name = "Embedding", description = "应用向量模型")
public class EmbeddingController {

    private final EmbeddingModelResolver resolver;
    private final EmbeddingModelRegistry registry;
    private final EmbeddingMigrationService migrationService;
    private final ChatStrategyRepository chatStrategyRepository;

    @GetMapping("/state")
    @Operation(summary = "当前应用的向量模型状态")
    public ApiResponse<EmbeddingStateDTO> state() {
        Long appId = requireAppId();
        var pending = resolver.pending(appId);
        com.qingyu.care.entity.AiModelConfig active;
        try {
            active = resolver.active(appId);
        } catch (InvalidOperationException e) {
            // 平台还没有任何向量模型：界面照常展示（当前模型为空 + 提示），而不是整块报错
            active = null;
        }
        return ApiResponse.success(new EmbeddingStateDTO(
                EmbeddingStateDTO.ModelSummary.of(active),
                EmbeddingStateDTO.ModelSummary.of(pending),
                EmbeddingStateDTO.JobView.of(migrationService.latestJob(appId)),
                resolver.selectable(appId).stream().map(EmbeddingStateDTO.ModelSummary::of).toList(),
                registry.dimensions(),
                chatStrategyRepository.findManualThresholdStrategies(appId).stream()
                        .map(EmbeddingStateDTO.StrategyThreshold::of).toList()));
    }

    @PostMapping("/switch")
    @AuditLog(action = "EMBEDDING_MODEL_SWITCH", targetType = "Application")
    @Operation(summary = "切换向量模型", description = "同一模型换托管直接生效；否则在后台重建向量，期间检索不中断")
    public ApiResponse<EmbeddingMigrationService.SwitchResult> switchModel(@RequestBody Map<String, Long> body) {
        Long modelId = body == null ? null : body.get("modelId");
        if (modelId == null) {
            throw new InvalidOperationException("请选择向量模型");
        }
        EmbeddingMigrationService.SwitchResult result = migrationService.switchModel(modelId);
        String message = switch (result.outcome()) {
            case SWITCHED -> "已切换（同一模型，无需重建向量）";
            case MIGRATING -> "已开始重建向量，完成后自动切换";
            case UNCHANGED -> "已是当前向量模型";
        };
        return ApiResponse.success(message, result);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @AuditLog(action = "EMBEDDING_MIGRATION_CANCEL", targetType = "Application")
    @Operation(summary = "取消切换")
    public ApiResponse<Void> cancel(@PathVariable Long jobId) {
        migrationService.cancel(jobId);
        return ApiResponse.success("正在取消", null);
    }

    @PostMapping("/jobs/{jobId}/resume")
    @AuditLog(action = "EMBEDDING_MIGRATION_RESUME", targetType = "Application")
    @Operation(summary = "继续失败或被中断的切换")
    public ApiResponse<Void> resume(@PathVariable Long jobId) {
        migrationService.resume(jobId);
        return ApiResponse.success("已继续", null);
    }

    private Long requireAppId() {
        Long appId = TenantContext.getAppId();
        if (appId == null) {
            throw new InvalidOperationException("请先选择应用");
        }
        return appId;
    }
}

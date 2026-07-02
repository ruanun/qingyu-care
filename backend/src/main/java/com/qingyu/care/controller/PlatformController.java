package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.PlatformOverviewDTO;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 平台级管理（仅平台管理员）：共享模型池、系统概览 */
@RestController
@RequestMapping("/v1/platform")
@RequiredArgsConstructor
@Slf4j
public class PlatformController {

    private final ModelRouterService modelRouterService;
    private final TenantRepository tenantRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final HealthEndpoint healthEndpoint;

    @GetMapping("/models")
    public ApiResponse<List<AiModelConfig>> listSharedModels() {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(modelRouterService.getSharedModels());
    }

    @AuditLog(action = "SHARED_MODEL_CREATE", targetType = "AiModelConfig")
    @PostMapping("/models")
    public ApiResponse<AiModelConfig> createSharedModel(@RequestBody AiModelConfig config) {
        SecurityUtil.requirePlatformAdmin();
        config.setId(null);
        return ApiResponse.success("共享模型已创建", modelRouterService.saveSharedModel(config));
    }

    @AuditLog(action = "SHARED_MODEL_UPDATE", targetType = "AiModelConfig")
    @PutMapping("/models/{id}")
    public ApiResponse<AiModelConfig> updateSharedModel(@PathVariable Long id, @RequestBody AiModelConfig config) {
        SecurityUtil.requirePlatformAdmin();
        AiModelConfig existing = modelRouterService.getById(id);
        // 防越权：只允许操作平台共享模型，避免用租户私有模型的 id 越权提升/篡改
        if (existing.getScope() != OwnerScope.PLATFORM) {
            throw new BusinessException(404, "共享模型不存在");
        }
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getProvider() != null) existing.setProvider(config.getProvider());
        if (config.getModelId() != null) existing.setModelId(config.getModelId());
        if (config.getEndpoint() != null) existing.setEndpoint(config.getEndpoint());
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) existing.setApiKey(config.getApiKey());
        if (config.getScenario() != null) existing.setScenario(config.getScenario());
        if (config.getPriority() != null) existing.setPriority(config.getPriority());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (config.getRemark() != null) existing.setRemark(config.getRemark());
        return ApiResponse.success("共享模型已更新", modelRouterService.saveSharedModel(existing));
    }

    @AuditLog(action = "SHARED_MODEL_DELETE", targetType = "AiModelConfig")
    @DeleteMapping("/models/{id}")
    public ApiResponse<Void> deleteSharedModel(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        // 防越权：只允许删除平台共享模型，避免用租户私有模型的 id 越权删除
        AiModelConfig existing = modelRouterService.getById(id);
        if (existing.getScope() != OwnerScope.PLATFORM) {
            throw new BusinessException(404, "共享模型不存在");
        }
        modelRouterService.deleteModel(id);
        return ApiResponse.success("共享模型已删除", null);
    }

    /**
     * 平台概览：系统健康状态 + 核心计数。
     * 仅平台管理员可访问。
     */
    @GetMapping("/overview")
    public ApiResponse<PlatformOverviewDTO> overview() {
        SecurityUtil.requirePlatformAdmin();

        // 读取 Actuator 健康状态，失败时兜底为 UNKNOWN，避免概览整体 500
        String health;
        try {
            health = healthEndpoint.health().getStatus().getCode();
        } catch (Exception e) {
            log.warn("获取系统健康状态失败: {}", e.getMessage());
            health = "UNKNOWN";
        }

        // 统计今日会话（从当天零点起）
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();

        PlatformOverviewDTO dto = PlatformOverviewDTO.builder()
                .health(health)
                .tenantCount(tenantRepository.count())
                .applicationCount(applicationRepository.count())
                .userCount(userRepository.count())
                .sessionCount(chatSessionRepository.count())
                .todaySessionCount(chatSessionRepository.countTodaySessions(startOfToday))
                .build();

        return ApiResponse.success(dto);
    }
}

package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.service.ModelRouterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 模型配置管理 API
 */
@RestController
@RequestMapping("/v1/ai-models")
@RequiredArgsConstructor
public class AiModelController {

    private final ModelRouterService modelRouterService;

    /**
     * 获取所有模型配置
     */
    @GetMapping
    public ApiResponse<List<AiModelConfig>> getAllModels() {
        return ApiResponse.success(modelRouterService.getAllModels());
    }

    /**
     * 获取指定模型配置
     */
    @GetMapping("/{id}")
    public ApiResponse<AiModelConfig> getById(@PathVariable Long id) {
        return ApiResponse.success(modelRouterService.getById(id));
    }

    /**
     * 创建模型配置
     */
    @PostMapping
    public ApiResponse<AiModelConfig> createModel(@RequestBody AiModelConfig config) {
        config.setId(null); // 确保是新建
        AiModelConfig saved = modelRouterService.saveModel(config);
        return ApiResponse.success("模型配置已创建", saved);
    }

    /**
     * 更新模型配置
     */
    @PutMapping("/{id}")
    public ApiResponse<AiModelConfig> updateModel(@PathVariable Long id, @RequestBody AiModelConfig config) {
        AiModelConfig existing = modelRouterService.getById(id);
        
        // 更新字段
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getProvider() != null) existing.setProvider(config.getProvider());
        if (config.getModelId() != null) existing.setModelId(config.getModelId());
        if (config.getEndpoint() != null) existing.setEndpoint(config.getEndpoint());
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) existing.setApiKey(config.getApiKey());
        if (config.getScenario() != null) existing.setScenario(config.getScenario());
        if (config.getPriority() != null) existing.setPriority(config.getPriority());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (config.getRemark() != null) existing.setRemark(config.getRemark());
        
        AiModelConfig saved = modelRouterService.saveModel(existing);
        return ApiResponse.success("模型配置已更新", saved);
    }

    /**
     * 删除模型配置
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteModel(@PathVariable Long id) {
        modelRouterService.deleteModel(id);
        return ApiResponse.success("模型配置已删除", null);
    }

    /**
     * 测试模型连接
     */
    @PostMapping("/{id}/test")
    public ApiResponse<String> testModel(@PathVariable Long id) {
        try {
            AiModelConfig config = modelRouterService.getById(id);
            var client = modelRouterService.createChatClient(config);
            String response = client.prompt()
                    .user("你好，请回复 \"连接正常\"")
                    .call()
                    .content();
            return ApiResponse.success("连接测试成功", response);
        } catch (Exception e) {
            return ApiResponse.error("连接测试失败: " + e.getMessage());
        }
    }
}

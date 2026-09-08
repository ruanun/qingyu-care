package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.AiModelConfigUpdateRequest;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.ModelProviderCatalog;
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
     * 厂商预设清单：界面用它渲染提供商下拉与徽章，并在选中预设时自动填入默认端点。
     *
     * <p>只是静态元数据，不含任何凭据，登录后即可读。清单本身与「为什么各家端点后缀
     * 不能想当然地补 /v1」的说明都在 {@link com.qingyu.care.service.ModelProviderCatalog}，
     * 全仓只此一份——前端不再自带一份会漂移的副本。
     *
     * <p>⚠️ 路径必须排在 {@code /{id}} 之前理解：字面量路径优先于模板路径匹配，否则
     * "providers" 会被当成 id 去转 Long。
     */
    @GetMapping("/providers")
    public ApiResponse<List<ModelProviderCatalog.ProviderPreset>> listProviders() {
        return ApiResponse.success(ModelProviderCatalog.presets());
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
    @AuditLog(action = "MODEL_CREATE", targetType = "AiModelConfig")
    @PostMapping
    public ApiResponse<AiModelConfig> createModel(@RequestBody AiModelConfig config) {
        config.setId(null); // 确保是新建
        modelRouterService.requireValidEndpoint(config.getEndpoint());   // 出站地址落库前过 SSRF 守卫（安全审查 M4）
        // 租户侧建的模型一律是租户级：scope 是客户端可控字段，不清掉的话租户可以在请求体里
        // 自封 PLATFORM，把所有租户的对话流量导向自己的 endpoint。
        // 平台共享模型只能走 /v1/platform/models（那边强制 tenantId=null）。
        config.setScope(OwnerScope.TENANT);
        AiModelConfig saved = modelRouterService.saveModel(config);
        return ApiResponse.success("模型配置已创建", saved);
    }

    /**
     * 更新模型配置
     */
    @AuditLog(action = "MODEL_UPDATE", targetType = "AiModelConfig")
    @PutMapping("/{id}")
    public ApiResponse<AiModelConfig> updateModel(@PathVariable Long id, @RequestBody AiModelConfigUpdateRequest config) {
        AiModelConfig existing = modelRouterService.getById(id);
        
        // 更新字段
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getProvider() != null) existing.setProvider(config.getProvider());
        if (config.getModelId() != null) existing.setModelId(config.getModelId());
        if (config.getEndpoint() != null) {
            modelRouterService.requireValidEndpoint(config.getEndpoint());
            existing.setEndpoint(config.getEndpoint());
        }
        // isBlank 而非 isEmpty：全空格凭据同样无意义，不该被当成「有意修改」写入
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) existing.setApiKey(config.getApiKey());
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
    @AuditLog(action = "MODEL_DELETE", targetType = "AiModelConfig")
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
        // 存量行也可能带着非法端点（校验是后来加的），出站前再过一次守卫；校验失败直接抛 400，不进下面的 catch
        AiModelConfig config = modelRouterService.getById(id);
        modelRouterService.requireValidEndpoint(config.getEndpoint());
        try {
            var client = modelRouterService.createChatClient(config);
            String response = client.prompt()
                    .user("你好，请回复 \"连接正常\"")
                    .call()
                    .content();
            return ApiResponse.success("连接测试成功", response);
        } catch (Exception e) {
            // 502 而非 500：这里是平台代租户向**上游模型端点**发了一次真实请求，失败原因混在
            // 一起且这个 catch-all 区分不了（apiKey/baseUrl 填错、对方服务挂了、超时……），
            // 但有一点是确定的——不是本服务内部出错。502「上游没给出有效响应」不预设是谁的错，
            // 比 500 诚实，也让排查的人一眼看出该去查出站而不是查本服务。
            return ApiResponse.error(502, "连接测试失败: " + e.getMessage());
        }
    }
}

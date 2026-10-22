package com.qingyu.care.controller;

import com.qingyu.care.annotation.AdminOnlyWrites;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.AiModelConfigUpdateRequest;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.ModelProvider;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.embedding.EmbeddingModelAdminService;
import com.qingyu.care.service.embedding.EmbeddingModelProbe;
import com.qingyu.care.service.ModelProviderCatalog;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.ModelUpstreamErrors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 模型配置管理 API
 */
@RestController
@AdminOnlyWrites
@RequestMapping("/v1/ai-models")
@RequiredArgsConstructor
public class AiModelController {

    private final ModelRouterService modelRouterService;
    private final EmbeddingModelAdminService embeddingModelAdminService;
    private final EmbeddingModelProbe embeddingModelProbe;
    private final com.qingyu.care.service.provider.ModelProviderService modelProviderService;

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
        // 模型必须挂在本租户的供应商连接下：端点与 Key 从连接复制（请求体里给的一律不用），
        // 端点在建连接时已过 SSRF 守卫（安全审查 M4）。
        // 租户侧建的模型一律是租户级：scope 是客户端可控字段，不清掉的话租户可以在请求体里
        // 自封 PLATFORM，把所有租户的对话流量导向自己的 endpoint——createModel 内部强制 TENANT。
        // 平台共享模型只能走 /v1/platform/models（那边强制 tenantId=null）。
        if (config.getProviderId() == null) {
            throw new InvalidOperationException("请先选择供应商连接");
        }
        ModelProvider provider = modelProviderService.requireOwned(config.getProviderId(), OwnerScope.TENANT);
        AiModelConfig saved = modelProviderService.createModel(config, provider, OwnerScope.TENANT);
        return ApiResponse.success("模型配置已创建", saved);
    }

    /**
     * 更新模型配置
     */
    @AuditLog(action = "MODEL_UPDATE", targetType = "AiModelConfig")
    @PutMapping("/{id}")
    public ApiResponse<AiModelConfig> updateModel(@PathVariable Long id, @RequestBody AiModelConfigUpdateRequest config) {
        AiModelConfig existing = modelRouterService.getById(id);
        embeddingModelAdminService.beforeUpdate(existing, config);

        // 更新字段（端点 / Key / 厂商属于供应商连接，在连接上改，见 ModelProviderController）
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getModelId() != null) existing.setModelId(config.getModelId());
        if (config.getScenario() != null) existing.setScenario(config.getScenario());
        if (config.getPriority() != null) existing.setPriority(config.getPriority());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (config.getRemark() != null) existing.setRemark(config.getRemark());
        if (config.getSimilarityThreshold() != null) existing.setSimilarityThreshold(config.getSimilarityThreshold());
        
        AiModelConfig saved = modelRouterService.saveModel(existing);
        return ApiResponse.success("模型配置已更新", saved);
    }

    /**
     * 删除模型配置
     */
    @AuditLog(action = "MODEL_DELETE", targetType = "AiModelConfig")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteModel(@PathVariable Long id) {
        embeddingModelAdminService.beforeDelete(modelRouterService.getById(id));
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
        if (ModelType.orDefault(config.getModelType()) == ModelType.EMBEDDING) {
            // 向量模型测 embeddings 而不是对话；连不上或维度不符抛 400，消息已是分类后的人话
            EmbeddingModelProbe.Result r = embeddingModelProbe.requireCompatible(config);
            return ApiResponse.success("连接测试成功", "向量维度 " + r.dimensions() + "，耗时 " + r.elapsedMs() + " ms");
        }
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
            return ApiResponse.error(502, "连接测试失败: " + ModelUpstreamErrors.describe(e));
        }
    }
}

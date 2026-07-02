package com.qingyu.care.service;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiModelConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模型路由服务
 * 负责按场景选择模型、创建 ChatClient、失败降级
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelRouterService {

    private final AiModelConfigRepository repository;
    
    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;
    
    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String defaultBaseUrl;
    
    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String defaultModel;

    /**
     * 初始化默认模型配置
     */
    @PostConstruct
    @Transactional
    public void init() {
        if (repository.count() == 0) {
            log.info("Initializing default AI model config...");
            
            // 创建默认模型配置
            repository.save(AiModelConfig.builder()
                    .name("默认模型")
                    .provider("openai")
                    .modelId(defaultModel)
                    .endpoint(defaultBaseUrl)
                    .apiKey(defaultApiKey)
                    .scenario("default")
                    .priority(1)
                    .enabled(true)
                    .remark("从 application.yml 初始化的默认模型")
                    .build());
            
            log.info("Default AI model config created");
        }
    }

    /**
     * 获取所有模型配置
     */
    public List<AiModelConfig> getAllModels() {
        return repository.findAllByOrderByScenarioAscPriorityAsc();
    }

    /**
     * 获取指定模型配置
     */
    public AiModelConfig getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AI模型配置", id.toString()));
    }

    /**
     * 保存模型配置
     */
    @Transactional
    public AiModelConfig saveModel(AiModelConfig config) {
        log.info("Saving AI model config: {}", config.getName());
        return repository.save(config);
    }

    /**
     * 删除模型配置
     */
    @Transactional
    public void deleteModel(Long id) {
        repository.deleteById(id);
        log.info("Deleted AI model config: {}", id);
    }

    /**
     * 获取指定场景的模型列表（按优先级排序）
     */
    public List<AiModelConfig> getModelsForScenario(String scenario) {
        List<AiModelConfig> models = repository.findByScenarioAndEnabledTrueOrderByPriorityAsc(scenario);
        if (models.isEmpty()) {
            // 降级到 default 场景
            models = repository.findByScenarioAndEnabledTrueOrderByPriorityAsc("default");
        }
        return models;
    }

    /**
     * 根据模型配置创建 ChatClient
     */
    public ChatClient createChatClient(AiModelConfig config) {
        String provider = config.getProvider().toLowerCase();
        
        switch (provider) {
            case "openai":
            case "dashscope":
            case "moonshot":
            case "deepseek":
                // 这些都兼容 OpenAI API 格式
                return createOpenAiCompatibleClient(config);
            default:
                log.warn("Unknown provider: {}, using OpenAI compatible client", provider);
                return createOpenAiCompatibleClient(config);
        }
    }

    /**
     * 创建 OpenAI 兼容的 ChatClient
     */
    private ChatClient createOpenAiCompatibleClient(AiModelConfig config) {
        String baseUrl = config.getEndpoint();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getDefaultEndpoint(config.getProvider());
        }
        
        // Spring AI 1.0.0-M4 中 OpenAiApi 的构造方式
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, config.getApiKey());
        
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(config.getModelId())
                .build();
        
        OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi, options);
        
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 获取提供商的默认端点
     */
    private String getDefaultEndpoint(String provider) {
        return switch (provider.toLowerCase()) {
            case "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "moonshot" -> "https://api.moonshot.cn/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            default -> "https://api.openai.com";
        };
    }

    /**
     * 调用模型（带降级）
     * @param scenario 场景
     * @param promptRunner 执行 prompt 的函数
     * @return AI 回复内容
     */
    public String callWithFallback(String scenario, java.util.function.Function<ChatClient, String> promptRunner) {
        List<AiModelConfig> models = getModelsForScenario(scenario);
        
        if (models.isEmpty()) {
            throw new RuntimeException("没有可用的 AI 模型配置");
        }
        
        Exception lastException = null;
        
        for (AiModelConfig model : models) {
            try {
                log.debug("Calling model: {} ({})", model.getName(), model.getModelId());
                ChatClient client = createChatClient(model);
                String result = promptRunner.apply(client);
                log.debug("Model {} returned successfully", model.getName());
                return result;
            } catch (Exception e) {
                log.warn("Model {} failed: {}, trying next...", model.getName(), e.getMessage());
                lastException = e;
            }
        }
        
        // 所有模型都失败
        log.error("All models failed for scenario: {}", scenario);
        throw new RuntimeException("所有 AI 模型调用失败", lastException);
    }

    /**
     * 获取默认场景的 ChatClient（简单场景使用）
     */
    public ChatClient getDefaultChatClient() {
        List<AiModelConfig> models = getModelsForScenario("default");
        if (models.isEmpty()) {
            throw new RuntimeException("没有配置默认 AI 模型");
        }
        return createChatClient(models.get(0));
    }
}

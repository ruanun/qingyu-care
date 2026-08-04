package com.qingyu.care.service;

import com.qingyu.care.config.PlatformSeeder;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiModelConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

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
    
    // 兜底值须自带 /v1：Spring AI 2.0 起 SDK 只补 chat/completions，不再补 /v1（详见 getDefaultEndpoint）。
    // 该值会被 init() 播种为平台默认模型行的 endpoint，所有租户默认走它，缺 /v1 将导致全线 404。
    @Value("${spring.ai.openai.base-url:https://api.openai.com/v1}")
    private String defaultBaseUrl;
    
    @Value("${spring.ai.openai.chat.model:gpt-4o-mini}")
    private String defaultModel;

    /**
     * 初始化默认模型配置
     */
    @PostConstruct
    @Transactional
    public void init() {
        PlatformSeeder.seedIfEmpty(repository.count(), "AI 模型配置", () -> {
            // 创建默认模型配置（平台共享模型）
            repository.save(AiModelConfig.builder()
                    .name("默认模型")
                    .provider("openai")
                    .modelId(defaultModel)
                    .endpoint(defaultBaseUrl)
                    .apiKey(defaultApiKey)
                    .scenario("default")
                    .priority(1)
                    .enabled(true)
                    .scope(OwnerScope.PLATFORM)
                    .remark("从 application.yml 初始化的平台共享默认模型")
                    .build());
        });
    }

    /**
     * 获取所有模型配置（管理面）：当前应用自有 ∪ 平台共享(scope=PLATFORM)，按 id 去重。
     * TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，禁止裸 findAll；无 appId
     * （平台管理员未选定应用）时自有部分为空，但平台共享模型仍须可见——保住多级回退可管理性。
     */
    public List<AiModelConfig> getAllModels() {
        Long appId = TenantContext.getAppId();
        List<AiModelConfig> own = appId == null
                ? List.of()
                : repository.findByAppIdOrderByScenarioAscPriorityAsc(appId);
        List<AiModelConfig> shared = repository.findAllSharedModels();
        return mergeOwnAndSharedById(own, shared);
    }

    private List<AiModelConfig> mergeOwnAndSharedById(List<AiModelConfig> own, List<AiModelConfig> shared) {
        java.util.LinkedHashMap<Long, AiModelConfig> byId = new java.util.LinkedHashMap<>();
        for (AiModelConfig m : own) byId.putIfAbsent(m.getId(), m);
        for (AiModelConfig m : shared) byId.putIfAbsent(m.getId(), m);
        return new java.util.ArrayList<>(byId.values());
    }

    /**
     * 获取指定模型配置。
     * findById 不受 Hibernate @Filter 保护，显式补租户归属校验——否则已登录租户可枚举 id
     * 跨租户读到他人模型配置（含明文 apiKey）。不匹配按「不存在」处理，与查不到同语义防探测。
     * 因实体含明文 apiKey，读路径采用写级守卫：平台共享行（tenant_id IS NULL）也仅平台
     * 管理员可按 id 读取——租户对共享模型的继承走 getModelsForScenario 的场景查询，不经此处。
     */
    public AiModelConfig getById(Long id) {
        AiModelConfig config = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AI模型配置", id.toString()));
        if (!TenantOwnershipGuard.mutableInCurrentContext(config.getTenantId(), config.getAppId())) {
            throw new ResourceNotFoundException("AI模型配置", id.toString());
        }
        return config;
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
     * 删除模型配置。经 getById 显式归属校验后再删——deleteById 直连主键同样绕 @Filter，
     * 否则租户可枚举 id 删他人模型配置。
     */
    @Transactional
    public void deleteModel(Long id) {
        AiModelConfig config = getById(id);
        repository.delete(config);
        log.info("Deleted AI model config: {}", id);
    }

    /** 列出全部平台共享模型 */
    public List<AiModelConfig> getSharedModels() {
        return repository.findAllSharedModels();
    }

    /** 保存平台共享模型（强制平台归属，避免被 TenantEntityListener 填充租户） */
    @Transactional
    public AiModelConfig saveSharedModel(AiModelConfig config) {
        config.setScope(OwnerScope.PLATFORM);
        config.setTenantId(null);
        config.setAppId(null);
        return repository.save(config);
    }

    /**
     * 获取指定场景的可用模型：租户自有 ∪ 平台共享，租户自有优先，
     * 按 (provider, modelId, endpoint) 去重；为空则降级到 default 场景。
     */
    public List<AiModelConfig> getModelsForScenario(String scenario) {
        List<AiModelConfig> merged = mergeTenantAndShared(scenario);
        if (merged.isEmpty() && !"default".equals(scenario)) {
            merged = mergeTenantAndShared("default");
        }
        return merged;
    }

    private List<AiModelConfig> mergeTenantAndShared(String scenario) {
        List<AiModelConfig> own = repository.findByScenarioAndEnabledTrueOrderByPriorityAsc(scenario);
        List<AiModelConfig> shared = repository.findSharedModelsByScenario(scenario);
        java.util.LinkedHashMap<String, AiModelConfig> byKey = new java.util.LinkedHashMap<>();
        for (AiModelConfig m : own) byKey.putIfAbsent(keyOf(m), m);
        for (AiModelConfig m : shared) byKey.putIfAbsent(keyOf(m), m);
        return new java.util.ArrayList<>(byKey.values());
    }

    private String keyOf(AiModelConfig m) {
        // endpoint 为空时归一化为该 provider 的默认端点，避免运行时同一模型被当成两条
        String endpoint = m.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = getDefaultEndpoint(m.getProvider());
        }
        return m.getProvider() + "|" + m.getModelId() + "|" + endpoint;
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
     * 创建 OpenAI 兼容的 ChatClient。
     *
     * <p>Spring AI 2.0 起 spring-ai-openai 改用官方 openai-java SDK，
     * {@code OpenAiApi} 已移除。baseUrl / apiKey 下沉到 {@link OpenAiChatOptions}：
     * {@code OpenAiChatModel.Builder#build()} 在未显式给 openAiClient 时，
     * 会用 options 上的 baseUrl/apiKey 自行装配 SDK 客户端——正好承载我们
     * 按租户动态换端点的路由需求。
     *
     * <p><b>⚠️ 不要在调用方传 per-request options</b>：Spring AI 2.0 起
     * OpenAiChatModel#buildRequestPrompt() 对 prompt 携带的 options 是「替换」而非「合并」，
     * 一旦调用方设了任何 options，这里的 model / baseUrl / apiKey / streamUsage(true) 会被整体丢弃
     * —— streamUsage 丢失是静默的（token 计量记 0 不抛），直接打到计费面。
     * 确需 per-request 覆盖时，必须从本方法的 options 复制一份再改。
     */
    private ChatClient createOpenAiCompatibleClient(AiModelConfig config) {
        String baseUrl = config.getEndpoint();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getDefaultEndpoint(config.getProvider());
        }

        // streamUsage(true)：让 OpenAI 兼容端点在流式末尾 chunk 携带 usage 信息，
        // 供 ChatServiceImpl.meterContentStream() 的 doOnNext 捕获 total_tokens。
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getModelId())
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .streamUsage(true)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .build();

        return ChatClient.builder(chatModel).build();
    }

    /**
     * 获取提供商的默认端点。
     *
     * <p><b>四个分支必须统一自带 {@code /v1}</b>：Spring AI 2.0 起改用官方 openai-java SDK，
     * 它只往 baseUrl 后拼 {@code chat/completions} / {@code embeddings}，**不再补 {@code /v1}**
     * （1.x 的 {@code OpenAiApi} 拼的是 {@code /v1/chat/completions}，故当年要求 baseUrl 不带 /v1，
     * 契约在 2.0 正好翻转）。且 {@code OpenAiSetup.calculateBaseUrl()} 对 OPEN_AI provider 原样
     * 返回、不做补全，端点缺 /v1 会直接 404。
     *
     * <p>刻意不做「缺 /v1 就自动补全」的归一化：404 是可见错误，自动补全只会掩盖配置错误。
     */
    private String getDefaultEndpoint(String provider) {
        return switch (provider.toLowerCase()) {
            case "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "moonshot" -> "https://api.moonshot.cn/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            default -> "https://api.openai.com/v1";
        };
    }

    /**
     * 调用模型（带降级），泛型化以透出完整响应类型
     * @param scenario 场景
     * @param promptRunner 执行 prompt 的函数
     * @return AI 回复（类型由调用方决定：String 或 Spring AI ChatResponse）
     */
    public <T> T callWithFallback(String scenario, java.util.function.Function<ChatClient, T> promptRunner) {
        List<AiModelConfig> models = getModelsForScenario(scenario);

        if (models.isEmpty()) {
            throw new RuntimeException("没有可用的 AI 模型配置");
        }

        Exception lastException = null;

        for (AiModelConfig model : models) {
            try {
                log.debug("Calling model: {} ({})", model.getName(), model.getModelId());
                ChatClient client = createChatClient(model);
                T result = promptRunner.apply(client);
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
     * 获取指定场景的 ChatClient（取该场景优先级最高的模型；
     * 场景无模型时 getModelsForScenario 已内置回退 default 场景）
     */
    public ChatClient getChatClientForScenario(String scenario) {
        List<AiModelConfig> models = getModelsForScenario(scenario);
        if (models.isEmpty()) {
            throw new RuntimeException("没有配置默认 AI 模型");
        }
        return createChatClient(models.get(0));
    }

    /**
     * 获取默认场景的 ChatClient（简单场景使用）
     */
    public ChatClient getDefaultChatClient() {
        return getChatClientForScenario("default");
    }

    /**
     * 流式调用 + 模型故障回退：按场景取模型列表依次尝试，
     * <b>只在首个 chunk 尚未发出时</b>才切换下一个模型（已经开吐的流无法重放）。
     * 与非流式 {@link #callWithFallback} 对应，让流式路径不再是单模型裸奔。
     */
    public Flux<org.springframework.ai.chat.model.ChatResponse> streamWithFallback(
            String scenario,
            Function<ChatClient, Flux<org.springframework.ai.chat.model.ChatResponse>> streamRunner) {
        List<AiModelConfig> models = getModelsForScenario(scenario);
        if (models.isEmpty()) {
            throw new RuntimeException("没有可用的 AI 模型配置");
        }
        // 模型列表与 ChatClient 的创建都在调用线程完成（依赖 TenantContext），
        // 只把「发起请求」延后到订阅时——订阅发生在 Reactor 线程，那时上下文已可能被清理。
        List<Supplier<Flux<org.springframework.ai.chat.model.ChatResponse>>> attempts = new ArrayList<>();
        for (AiModelConfig model : models) {
            ChatClient client = createChatClient(model);
            attempts.add(() -> {
                log.debug("Streaming with model: {} ({})", model.getName(), model.getModelId());
                return streamRunner.apply(client);
            });
        }
        return streamWithFallbackChain(attempts);
    }

    /**
     * 纯方法：按顺序尝试各次流式调用，仅在「尚未发出任何元素」时才回退到下一个。
     * static 免容器单测（同 ChatServiceImpl#meterContentStream 惯例）。
     */
    static Flux<org.springframework.ai.chat.model.ChatResponse> streamWithFallbackChain(
            List<Supplier<Flux<org.springframework.ai.chat.model.ChatResponse>>> attempts) {
        return attemptStream(attempts, 0);
    }

    private static Flux<org.springframework.ai.chat.model.ChatResponse> attemptStream(
            List<Supplier<Flux<org.springframework.ai.chat.model.ChatResponse>>> attempts, int index) {
        // emitted 建在 Flux.defer 内部，每次订阅各持一份，避免重复订阅（如未来加 retry()）串状态；
        // 同 ChatServiceImpl#streamChat 里 splitter/acc/emitted 挪进 Flux.defer 的惯例
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            return attempts.get(index).get()
                    .doOnNext(x -> emitted.set(true))
                    .onErrorResume(e -> {
                        if (emitted.get() || index + 1 >= attempts.size()) {
                            return Flux.error(e);
                        }
                        log.warn("流式模型调用失败，切换下一个模型: {}", e.getMessage());
                        return attemptStream(attempts, index + 1);
                    });
        });
    }
}

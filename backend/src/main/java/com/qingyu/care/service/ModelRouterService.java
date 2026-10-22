package com.qingyu.care.service;

import com.qingyu.care.config.PlatformSeeder;
import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.config.ModelEndpointGuardInterceptor;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
    private final SharedCredentialMasker credentialMasker;

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;
    
    // 兜底值须自带 /v1：Spring AI 2.0 起 SDK 只补 chat/completions，不再补 /v1（详见 getDefaultEndpoint）。
    // 该值会被 init() 播种为平台默认模型行的 endpoint，所有租户默认走它，缺 /v1 将导致全线 404。
    @Value("${spring.ai.openai.base-url:https://api.openai.com/v1}")
    private String defaultBaseUrl;
    
    @Value("${spring.ai.openai.chat.model:gpt-4o-mini}")
    private String defaultModel;

    // 平台默认向量模型的播种来源（只在平台还没有任何向量模型时用一次，之后以后台配置为准）。
    // 端点与 Key 缺省回落对话那一份（yml 里用占位符嵌套表达），想分开就设 AI_EMBEDDING_BASE_URL / AI_EMBEDDING_API_KEY。
    @Value("${qingyu.care.embedding.seed.model:}")
    private String seedEmbeddingModel;

    @Value("${qingyu.care.embedding.seed.base-url:}")
    private String seedEmbeddingBaseUrl;

    @Value("${qingyu.care.embedding.seed.api-key:}")
    private String seedEmbeddingApiKey;

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
                    .provider(ModelProviderCatalog.keyForEndpoint(defaultBaseUrl).orElse("openai"))
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
        seedPlatformEmbeddingModel();
        sharedChatModelWarning().ifPresent(log::warn);
    }

    /**
     * 共享模型池里一条可用的对话模型都没有时的警告文案；正常情况返回 empty。
     *
     * <p>为什么不直接补播：播种语义是「只播一次，之后以库为准」，否则管理员删掉不想用的模型会被重启后逐回来。
     * 但「删光后就永久没有兜底」是个静默故障：表里还有租户模型、{@code count != 0}，
     * 所以 {@link PlatformSeeder#seedIfEmpty} 永远不会再播；症状只是对话报「没有可用的 AI 模型配置」，
     * 日志里没有任何线索。启动时喊一声，把静默变成可见。
     *
     * <p>拆成返回 Optional 而不是内部直接 log，是为了让单测能断言这件事情发生了——
     * 否则那条测试无论在不在报警都是绿的。
     *
     * <p>查的是 {@code scenario='default'}：模型回退的最后一档就是 default 场景，
     * 别的场景里的共享模型摇不到底。
     */
    java.util.Optional<String> sharedChatModelWarning() {
        if (repository.count() == 0) {
            return java.util.Optional.empty();   // 空库刚被上面播种，不可能走到
        }
        if (!repository.findSharedModelsByScenario("default").isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("共享模型池里没有启用的对话模型（scenario=default）：没有自建模型的租户将无模型可用。"
                + "播种只在整表为空时跑一次，所以不会自动重建；请到「共享模型池」添加或启用一个对话模型");
    }

    /**
     * 平台还没有任何向量模型时，按 yml 播种一条平台共享向量模型——新应用都钉它。
     *
     * <p>与对话模型分开判断（不能复用上面「表为空才播种」）：存量库里早有对话模型行，按表计数就永远不会播。
     * 不在这里探测连通性：启动时网络不通不该阻止服务起来，后台的「测试」按钮与摄取失败会把问题暴露出来。
     */
    void seedPlatformEmbeddingModel() {
        if (seedEmbeddingModel == null || seedEmbeddingModel.isBlank()
                || repository.countPlatformEmbeddingModels() > 0) {
            return;
        }
        String baseUrl = seedEmbeddingBaseUrl == null || seedEmbeddingBaseUrl.isBlank() ? defaultBaseUrl : seedEmbeddingBaseUrl;
        String apiKey = seedEmbeddingApiKey == null || seedEmbeddingApiKey.isBlank() ? defaultApiKey : seedEmbeddingApiKey;
        String provider = ModelProviderCatalog.keyForEndpoint(baseUrl).orElse("openai");
        repository.save(AiModelConfig.builder()
                .name("默认向量模型")
                .provider(provider)
                .modelId(seedEmbeddingModel.trim())
                .endpoint(baseUrl)
                .apiKey(apiKey)
                .scenario("default")
                .priority(1)
                .enabled(true)
                .scope(OwnerScope.PLATFORM)
                .modelType(com.qingyu.care.enums.ModelType.EMBEDDING)
                .similarityThreshold(ModelProviderCatalog.DEFAULT_EMBEDDING_THRESHOLD)
                .remark("从 application.yml 初始化的平台默认向量模型")
                .build());
        log.info("已播种平台默认向量模型 {}（{}）", seedEmbeddingModel, baseUrl);
    }

    /**
     * 获取所有模型配置（管理面）：当前应用自有 ∪ 平台共享(scope=PLATFORM)，按 id 去重。
     * TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，禁止裸 findAll；无 appId
     * （平台管理员未选定应用）时自有部分为空，但平台共享模型仍须可见——保住多级回退可管理性。
     *
     * <p>⚠️ 这里的去重键是 <b>id</b>，与运行面 {@link #getModelsForScenario}（去重键是
     * {@code provider|modelId|endpoint}）<b>刻意不同</b>：管理面只要求「同一个行不重复出现」，
     * 两条 id 不同但凭据相同的行在界面上本就该各自可见、各自可删；
     * 而运行面要保证同一模型不被当成两个候选重复尝试降级。两者不要合并成一个方法。
     */
    public List<AiModelConfig> getAllModels() {
        Long appId = TenantContext.getAppId();
        List<AiModelConfig> own = appId == null
                ? List.of()
                : repository.findByAppIdOrderByScenarioAscPriorityAsc(appId);
        List<AiModelConfig> shared = repository.findAllSharedModels();
        List<AiModelConfig> merged = mergeOwnAndSharedById(own, shared);
        // 平台共享模型的 apiKey 是平台凭据，租户可用不可见。
        // 这条此前缺失，导致租户管理员调 GET /v1/ai-models 能读到平台的明文 key。
        return credentialMasker.mask(merged, AiModelConfig::getTenantId, AiModelConfig::getAppId,
                m -> m.setApiKey(null));
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
     * 是否允许模型 endpoint 指向内网/环回（本地跑 Ollama 等自托管模型的开发环境用；生产保持 false）。
     */
    @org.springframework.beans.factory.annotation.Value("${qingyu.care.model.allow-private-endpoints:false}")
    private boolean allowPrivateEndpoints;

    /**
     * 单次回复的输出 token 上限；0 = 不设（交给模型默认）。
     *
     * <p>2026-09-30 安全审查：此前全仓没有设置，匿名访客能诱导模型输出超长回复——按 IP 的限流只限请求次数，
     * 限不了单次的输出长度，而 token 费用由租户（最终是平台）承担。默认 4096 远高于客服场景的正常回答长度。
     */
    @org.springframework.beans.factory.annotation.Value("${qingyu.care.model.max-output-tokens:4096}")
    private int maxOutputTokens;

    /**
     * ⚠️ OpenAI 官方的推理模型（o 系列、gpt-5）<b>拒绝</b> {@code max_tokens}、只认 {@code max_completion_tokens}；
     * 而国内各家 OpenAI 兼容模式普遍只认 {@code max_tokens}。按厂商分开给，别统一成一个。
     */
    void applyMaxOutputTokens(OpenAiChatOptions.Builder builder, String provider) {
        if (maxOutputTokens <= 0) {
            return;
        }
        // 哪家只认 max_completion_tokens 由 ModelProviderCatalog 统一持有，别在这里写厂商名
        if (ModelProviderCatalog.usesMaxCompletionTokens(provider)) {
            builder.maxCompletionTokens(maxOutputTokens);
        } else {
            builder.maxTokens(maxOutputTokens);
        }
    }

    private ModelEndpointGuardInterceptor endpointGuard;

    /**
     * 懒建：它内部持有一个 OkHttp 连接池，所有模型客户端共用一份；放 @PostConstruct 会让手工 new 的单测拿到 null。
     * 向量模型客户端（{@code EmbeddingModelRegistry}）也挂这同一个实例。
     */
    public synchronized ModelEndpointGuardInterceptor endpointGuardInterceptor() {
        if (endpointGuard == null) {
            endpointGuard = new ModelEndpointGuardInterceptor(allowPrivateEndpoints);
        }
        return endpointGuard;
    }

    /**
     * 校验租户填的模型 endpoint（安全审查 M4，2026-09-03）：协议限 http(s)、主机须解析到公网地址。
     * 只在管理面入口（建 / 改 / 测试连接）调用，不放每轮对话的热路径；空值表示沿用默认 base-url。
     * {@code allowPrivateEndpoints} 打开时只校验形态。
     *
     * @throws InvalidOperationException 端点非法，消息可直接给用户看
     */
    public void requireValidEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        try {
            if (allowPrivateEndpoints) {
                // 放开私网（本地 Ollama 等），但云元数据段永不放行——见该方法注释
                SsrfGuard.assertAllowedHttpUrlAllowingPrivate(endpoint.trim());
            } else {
                SsrfGuard.assertAllowedHttpUrl(endpoint.trim());
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException("模型端点无效：" + e.getMessage());
        }
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
     *
     * <p>去重键与 {@link #getAllModels} 不同，理由写在那边。
     */
    public List<AiModelConfig> getModelsForScenario(String scenario) {
        List<AiModelConfig> merged = mergeOwnAndSharedForRouting(scenario);
        if (merged.isEmpty() && !"default".equals(scenario)) {
            merged = mergeOwnAndSharedForRouting("default");
        }
        return merged;
    }

    /** 运行面合并：同一模型（厂商 + 模型 ID + 端点）在自有与共享里各存一份时只留自有那条。 */
    private List<AiModelConfig> mergeOwnAndSharedForRouting(String scenario) {
        // 「租户自有」那半靠 Hibernate 过滤器隔离，而 TenantDataAspect 对「无上下文 + 未登录」一律不加过滤：
        // 在没有租户上下文的后台线程里查，拿到的是全平台所有租户的模型（2026-09-30 安全审查 H1，QA 生成曾如此）。
        // 这里直接拒绝，别让下一个 @Async / @Scheduled 调用方再把它带回来。请求线程（含平台管理员）与
        // 自己设好上下文的后台任务（如非流式回退）不受影响。
        if (TenantContext.getTenantId() == null
                && org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null) {
            throw new IllegalStateException("后台线程没有租户上下文，不能解析模型——请在请求线程解析好后传入（callWithModels）");
        }
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
     * 按模型配置创建 ChatClient（<b>不进缓存</b>）。
     *
     * <p>热路径不要直接调它，走 {@link #chatClientFor}：每次 build 都会新建一个 SDK 客户端及其连接池，
     * 对话是逐条消息调用的，不复用的话每条消息都要重新 TLS 握手到模型端点。
     * 这里保留无缓存的版本给「测试连接」用——那个场景本意就是验证当下的配置能不能打通。
     */
    public ChatClient createChatClient(AiModelConfig config) {
        // 刻意没有 provider 分派：本项目目前只接 OpenAI 兼容协议，所有 provider 走同一条路径，
        // provider 字段的实际作用只是查 ModelProviderCatalog 拿默认端点、以及界面展示。
        //
        // ⚠️ 这里曾是个 switch，四个已知 provider 各一个 case、default 分支打 WARN
        // 「Unknown provider」。厂商预设扩到 12 家之后那条 WARN 就错了：12 家里有 8 家
        // 会落进 default，而 createChatClient 在每轮对话都被调用（callWithFallback /
        // streamWithFallback 各一次），等于给合法配置刷热路径日志。
        // 将来真要接非 OpenAI 协议的厂商（Anthropic 原生 messages、Gemini 原生
        // generateContent），分派在这里重新长出来，那时 default 分支才重新有意义。
        return createOpenAiCompatibleClient(config);
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
        var optionsBuilder = OpenAiChatOptions.builder()
                .model(config.getModelId())
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .streamUsage(true);
        applyMaxOutputTokens(optionsBuilder, config.getProvider());
        OpenAiChatOptions options = optionsBuilder.build();

        // 出站防线：禁重定向 + 连接期 DNS 校验（2026-09-30 安全审查 M1，见 ModelEndpointGuardInterceptor）
        ModelEndpointGuardInterceptor guard = endpointGuardInterceptor();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(b -> b.interceptor(guard))
                .build();

        return ChatClient.builder(chatModel).build();
    }

    /**
     * 对话客户端缓存：键 = 模型行 id，失效判据 = {@code updatedAt}。
     *
     * <p>与 {@code EmbeddingModelRegistry} 完全同一套语义（那边缓存向量模型与 VectorStore）。
     * 两边都按 {@code updatedAt} 失效，是因为后台改端点 / Key 走的是供应商连接，
     * {@code AiModelConfigRepository.syncFromProvider} 会一并刷新模型的 {@code updated_at}——
     * 时间戳不动就会继续用旧客户端，改了 Key 也不生效。
     */
    private final ConcurrentHashMap<Long, CachedChatClient> chatClients = new ConcurrentHashMap<>();

    private record CachedChatClient(LocalDateTime version, ChatClient client) {
    }

    /**
     * 取该模型行的 ChatClient（带缓存）。<b>热路径一律走它</b>，不要直接调 {@link #createChatClient}。
     *
     * <p>不缓存会怎样：{@link #streamWithFallback} 会把降级列表里每个候选都建一遍、
     * {@link #callWithModels} 每次尝试再建一次——每条消息 N 个 SDK 客户端、N 个连接池，
     * 全部不复用，且旧的不 close 只能等 GC。
     *
     * <p>未落库的行（id 为 null）不进缓存：那是保存前探测 / 单测里的临时对象，没有稳定的失效版本。
     */
    ChatClient chatClientFor(AiModelConfig config) {
        Long id = config.getId();
        if (id == null) {
            return createChatClient(config);
        }
        CachedChatClient cached = chatClients.get(id);
        if (cached != null && Objects.equals(cached.version(), config.getUpdatedAt())) {
            return cached.client();
        }
        ChatClient fresh = createChatClient(config);
        chatClients.put(id, new CachedChatClient(config.getUpdatedAt(), fresh));
        return fresh;
    }

    /**
     * 获取提供商的默认端点。清单与「为什么每一项都不能想当然地补 /v1」的完整说明
     * 都在 {@link ModelProviderCatalog}——那里是唯一一份，前端经
     * {@code GET /v1/ai-models/providers} 拉同一份数据渲染下拉与徽章。
     *
     * <p><b>清单里每一项都必须自带正确后缀</b>：Spring AI 2.0 起改用官方 openai-java SDK，
     * 它只往 baseUrl 后拼 {@code chat/completions} / {@code embeddings}，**不再补 {@code /v1}**
     * （1.x 的 {@code OpenAiApi} 拼的是 {@code /v1/chat/completions}，故当年要求 baseUrl 不带 /v1，
     * 契约在 2.0 正好翻转）。且 {@code OpenAiSetup.calculateBaseUrl()} 对 OPEN_AI provider 原样
     * 返回、不做补全，端点缺 /v1 会直接 404。
     *
     * <p>刻意不做「缺 /v1 就自动补全」的归一化：404 是可见错误，自动补全只会掩盖配置错误。
     */
    private String getDefaultEndpoint(String provider) {
        return ModelProviderCatalog.defaultEndpointFor(provider);
    }

    /**
     * 调用模型（带降级），泛型化以透出完整响应类型
     * @param scenario 场景
     * @param promptRunner 执行 prompt 的函数
     * @return AI 回复（类型由调用方决定：String 或 Spring AI ChatResponse）
     */
    public <T> T callWithFallback(String scenario, java.util.function.Function<ChatClient, T> promptRunner) {
        return callWithModels(getModelsForScenario(scenario), promptRunner);
    }

    /**
     * 用调用方事先解析好的模型列表依次尝试（带降级）。
     *
     * <p>⚠️ 给 {@code @Async} 等<b>没有 TenantContext 的线程</b>用：{@link #getModelsForScenario} 的
     * 「租户自有」那半靠 Hibernate 过滤器隔离，而 {@code TenantDataAspect} 对「无上下文 + 未登录」
     * 一律不加过滤——在异步线程里直接调 {@link #callWithFallback} 会取到<b>全平台所有租户</b>的模型，
     * 把本租户的数据发往别的租户配置的 endpoint（2026-09-30 安全审查 H1，QA 生成曾如此）。
     * 正确做法是在请求线程调 {@link #getModelsForScenario} 解析好，再把列表传进异步方法。
     */
    public <T> T callWithModels(List<AiModelConfig> models, java.util.function.Function<ChatClient, T> promptRunner) {
        if (models == null || models.isEmpty()) {
            throw new RuntimeException("没有可用的 AI 模型配置");
        }

        Exception lastException = null;

        for (AiModelConfig model : models) {
            try {
                log.debug("Calling model: {} ({})", model.getName(), model.getModelId());
                ChatClient client = chatClientFor(model);
                T result = promptRunner.apply(client);
                log.debug("Model {} returned successfully", model.getName());
                return result;
            } catch (Exception e) {
                log.warn("Model {} failed: {}, trying next...", model.getName(), e.getMessage());
                lastException = e;
            }
        }

        // 所有模型都失败
        log.error("All {} models failed", models.size());
        throw new RuntimeException("所有 AI 模型调用失败", lastException);
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
        // 模型列表在调用线程解析（依赖 TenantContext）；客户端改走 chatClientFor 缓存，
        // 不再为降级列表里每个候选各建一个（每条消息 N 个连接池且都不复用）。
        // 「发起请求」仍延后到订阅时——订阅发生在 Reactor 线程，那时上下文已可能被清理。
        List<Supplier<Flux<org.springframework.ai.chat.model.ChatResponse>>> attempts = new ArrayList<>();
        for (AiModelConfig model : models) {
            ChatClient client = chatClientFor(model);
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

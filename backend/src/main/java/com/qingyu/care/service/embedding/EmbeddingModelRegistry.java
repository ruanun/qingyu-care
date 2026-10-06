package com.qingyu.care.service.embedding;

import com.qingyu.care.config.ModelEndpointGuardInterceptor;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.service.ModelProviderCatalog;
import com.qingyu.care.service.ModelRouterService;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按模型配置行构建（并缓存）向量模型客户端与对应的 {@link VectorStore}。
 *
 * <p>取代 Spring AI 的自动装配（yml 已关掉 {@code spring.ai.model.embedding} 与
 * {@code spring.ai.vectorstore.type}）：向量模型改由后台配置、每个应用钉一条，全局单例装不下。
 * 所有 {@link PgVectorStore} 实例共用同一张 {@code vector_store} 表，彼此只差「用哪个模型给查询/正文做向量」，
 * 不同模型的向量靠 metadata 里的 {@code embeddingModel} 标签区分（见 {@link EmbeddingTag}）。
 *
 * <p>缓存键是 (id, updatedAt)：后台改了端点或 Key，下一次取到的就是新客户端，不必重启。
 *
 * @author ruan
 */
@Component
public class EmbeddingModelRegistry {

    private final JdbcTemplate jdbcTemplate;
    private final ModelRouterService modelRouterService;
    private final int dimensions;

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(LocalDateTime version, EmbeddingModel model, VectorStore store) {
    }

    public EmbeddingModelRegistry(JdbcTemplate jdbcTemplate,
                                  ModelRouterService modelRouterService,
                                  @Value("${qingyu.care.embedding.dimensions:1024}") int dimensions) {
        this.jdbcTemplate = jdbcTemplate;
        this.modelRouterService = modelRouterService;
        this.dimensions = dimensions;
    }

    /** 全局固定的向量维度：建表、探测校验、请求参数共用这一个值。 */
    public int dimensions() {
        return dimensions;
    }

    public EmbeddingModel embeddingModel(AiModelConfig config) {
        return entry(config).model();
    }

    public VectorStore vectorStore(AiModelConfig config) {
        return entry(config).store();
    }

    /** 不进缓存的一次性客户端：给「保存前探测」用，那时配置还没落库、没有 id。 */
    public EmbeddingModel buildUncached(AiModelConfig config) {
        return buildModel(config);
    }

    private Entry entry(AiModelConfig config) {
        Objects.requireNonNull(config.getId(), "向量模型配置必须已落库");
        Entry cached = cache.get(config.getId());
        if (cached != null && Objects.equals(cached.version(), config.getUpdatedAt())) {
            return cached;
        }
        EmbeddingModel model = buildModel(config);
        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, model)
                // 显式给维度：不给的话 PgVectorStore 会调一次 embeddingModel.dimensions()，那是一次真实的出站请求
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                // 表结构由 VectorStoreSchemaInitializer 统一维护，这里只读写
                .initializeSchema(false)
                .build();
        Entry fresh = new Entry(config.getUpdatedAt(), model, store);
        cache.put(config.getId(), fresh);
        return fresh;
    }

    private EmbeddingModel buildModel(AiModelConfig config) {
        String baseUrl = config.getEndpoint();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = ModelProviderCatalog.defaultEndpointFor(config.getProvider());
        }
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(config.getModelId())
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .dimensions(dimensions)
                .build();
        // 租户可以自带向量模型，端点是租户填的——与对话模型挂同一道出站防线（禁重定向 + 连接期 DNS 校验，
        // 安全基线第 41 条）。此前只有平台默认的那一个向量模型、端点由平台配置，所以那条写着「不在此列」。
        ModelEndpointGuardInterceptor guard = modelRouterService.endpointGuardInterceptor();
        return OpenAiEmbeddingModel.builder()
                .options(options)
                // EMBED 与此前 Spring AI 自动装配的默认值一致：metadata 一起参与向量化。改成 NONE 会让新旧向量
                // 的输入文本不同，存量向量与新写入的向量不再严格同口径。
                .metadataMode(MetadataMode.EMBED)
                .httpClientBuilderCustomizer(b -> b.interceptor(guard))
                .build();
    }
}

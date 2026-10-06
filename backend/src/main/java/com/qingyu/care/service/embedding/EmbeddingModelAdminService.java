package com.qingyu.care.service.embedding;

import com.qingyu.care.dto.AiModelConfigUpdateRequest;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.service.ModelProviderCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 向量模型在「模型管理」各入口（租户侧 / 平台共享侧的建、改、删）上的额外约束。对话模型直接放行。
 *
 * <p>两个控制器各有一份映射代码（同一个 DTO 喂两个端点），约束放在这里由两边共同调用，免得各写一份再各自漂移。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class EmbeddingModelAdminService {

    private final EmbeddingModelProbe probe;
    private final ApplicationRepository applicationRepository;

    /** 新建：向量模型补齐不适用的字段、填推荐阈值，并真实探测一次（连不上或维度不符直接拒）。 */
    public void prepareCreate(AiModelConfig config) {
        if (ModelType.orDefault(config.getModelType()) != ModelType.EMBEDDING) {
            config.setModelType(ModelType.CHAT);
            return;
        }
        // 场景与优先级只对对话路由有意义；向量模型的 scenario 统一 default（列非空），由对话查询按类型排除
        config.setScenario("default");
        if (config.getPriority() == null) {
            config.setPriority(100);
        }
        if (config.getSimilarityThreshold() == null) {
            config.setSimilarityThreshold(ModelProviderCatalog.recommendedEmbeddingThreshold(config.getModelId()));
        }
        requireValidThreshold(config.getSimilarityThreshold());
        probe.requireCompatible(config);
    }

    /**
     * 更新前校验（在把请求字段写进实体<b>之前</b>调用，需要比较新旧值）。
     * 被应用引用的向量模型：不能改 modelId（标签会变，检索一条都对不上）、不能停用。
     * 端点 / Key / modelId 任一变化都重新探测（Key 换成别家的、端点换成另一种模型，维度可能就变了）。
     */
    public void beforeUpdate(AiModelConfig existing, AiModelConfigUpdateRequest req) {
        if (ModelType.orDefault(existing.getModelType()) != ModelType.EMBEDDING) {
            return;
        }
        boolean modelIdChanged = req.getModelId() != null && !req.getModelId().trim().equals(EmbeddingTag.of(existing));
        boolean referenced = applicationRepository.countEmbeddingModelReferences(existing.getId()) > 0;
        if (referenced && modelIdChanged) {
            throw new InvalidOperationException("该向量模型正被应用使用，不能修改模型 ID（已有向量按模型 ID 标记，改了会全部检索不到）。"
                    + "请新建一个向量模型，再在知识库页切换过去");
        }
        if (referenced && Boolean.FALSE.equals(req.getEnabled())) {
            throw new InvalidOperationException("该向量模型正被应用使用，不能停用。请先把这些应用切换到其他向量模型");
        }
        if (req.getSimilarityThreshold() != null) {
            requireValidThreshold(req.getSimilarityThreshold());
        }
        boolean keyChanged = req.getApiKey() != null && !req.getApiKey().isBlank();
        boolean endpointChanged = req.getEndpoint() != null && !Objects.equals(req.getEndpoint(), existing.getEndpoint());
        if (modelIdChanged || keyChanged || endpointChanged) {
            AiModelConfig candidate = AiModelConfig.builder()
                    .provider(req.getProvider() != null ? req.getProvider() : existing.getProvider())
                    .modelId(modelIdChanged ? req.getModelId() : existing.getModelId())
                    .endpoint(req.getEndpoint() != null ? req.getEndpoint() : existing.getEndpoint())
                    .apiKey(keyChanged ? req.getApiKey() : existing.getApiKey())
                    .modelType(ModelType.EMBEDDING)
                    .build();
            probe.requireCompatible(candidate);
        }
    }

    /** 删除前：被应用引用（当前或切换目标）的向量模型不能删。 */
    public void beforeDelete(AiModelConfig existing) {
        if (ModelType.orDefault(existing.getModelType()) == ModelType.EMBEDDING
                && applicationRepository.countEmbeddingModelReferences(existing.getId()) > 0) {
            throw new InvalidOperationException("该向量模型正被应用使用，不能删除。请先把这些应用切换到其他向量模型");
        }
    }

    private static void requireValidThreshold(Double threshold) {
        if (threshold != null && (threshold < 0 || threshold > 1)) {
            throw new InvalidOperationException("相似度阈值须在 0 ~ 1 之间");
        }
    }
}

package com.qingyu.care.dto;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.EmbeddingMigrationJob;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库页「向量模型」卡片所需的全部状态：当前模型、切换进行中的目标与进度、可选模型、需要留意的策略。
 *
 * @param dimensions                平台固定的向量维度
 * @param manualThresholdStrategies 对本应用生效、但没有跟随向量模型阈值的问答策略——切换模型时提醒去调
 * @author ruan
 */
public record EmbeddingStateDTO(ModelSummary active,
                                ModelSummary pending,
                                JobView job,
                                List<ModelSummary> options,
                                int dimensions,
                                List<StrategyThreshold> manualThresholdStrategies) {

    /** 模型摘要（不含端点与凭据）。platform = 平台共享模型。 */
    public record ModelSummary(Long id, String name, String provider, String modelId,
                               Double similarityThreshold, boolean platform) {
        public static ModelSummary of(AiModelConfig m) {
            return m == null ? null : new ModelSummary(m.getId(), m.getName(), m.getProvider(), m.getModelId(),
                    m.getSimilarityThreshold(), m.getTenantId() == null);
        }
    }

    public record JobView(Long id, String status, String fromTag, String toTag, Integer total, Integer done,
                          Integer failed, String message, LocalDateTime startedAt, LocalDateTime finishedAt) {
        public static JobView of(EmbeddingMigrationJob j) {
            return j == null ? null : new JobView(j.getId(), j.getStatus().name(), j.getFromTag(), j.getToTag(),
                    j.getTotal(), j.getDone(), j.getFailed(), j.getMessage(), j.getStartedAt(), j.getFinishedAt());
        }
    }

    public record StrategyThreshold(Long id, String name, String scenario, Double similarityThreshold, boolean platform) {
        public static StrategyThreshold of(ChatStrategy s) {
            return new StrategyThreshold(s.getId(), s.getName(), s.getScenario(), s.getSimilarityThreshold(),
                    s.getTenantId() == null);
        }
    }
}

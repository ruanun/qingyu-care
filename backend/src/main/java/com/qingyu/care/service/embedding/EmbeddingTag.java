package com.qingyu.care.service.embedding;

import com.qingyu.care.entity.AiModelConfig;

/**
 * 向量的「模型标签」：写进 {@code vector_store.metadata.embeddingModel}，检索只认当前模型的标签。
 *
 * <p>标签取 {@code modelId} 而不是配置行 id：同一个模型换个托管 / 换把 Key 建成另一行，向量空间并没变，
 * 切换时只换指针、不必重嵌（{@code EmbeddingMigrationService} 据此判断）。代价是要求
 * 「同名即同模型」——各厂商的向量模型名都带版本号，实际不冲突。
 *
 * <p>⚠️ 也正因为标签是 modelId，<b>被应用引用的向量模型不能改 modelId</b>：改了，应用钉着的还是这一行，
 * 检索却开始按新名字找向量，库里一条都对不上——不报错，只是什么都检索不到。
 *
 * @author ruan
 */
public final class EmbeddingTag {

    private EmbeddingTag() {
    }

    public static String of(AiModelConfig config) {
        return config.getModelId() == null ? "" : config.getModelId().trim();
    }
}

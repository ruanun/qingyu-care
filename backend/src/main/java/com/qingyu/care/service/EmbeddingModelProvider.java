package com.qingyu.care.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 当前 embedding 模型名的单一来源（读 spring.ai.openai.embedding.options.model）。
 * 写入向量打标、检索护栏、重嵌共用，避免各处散读配置漂移。
 *
 * @author ruan
 */
@Component
public class EmbeddingModelProvider {

    private final String model;

    public EmbeddingModelProvider(@Value("${spring.ai.openai.embedding.options.model}") String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
    }
}

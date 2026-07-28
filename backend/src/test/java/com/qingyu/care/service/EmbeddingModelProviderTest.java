package com.qingyu.care.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingModelProviderTest {
    @Test
    void 返回构造注入的模型名() {
        assertThat(new EmbeddingModelProvider("qwen3.7-text-embedding").getModel())
                .isEqualTo("qwen3.7-text-embedding");
    }
}

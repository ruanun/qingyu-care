package com.qingyu.care.service;

import com.qingyu.care.enums.DocumentVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RetrievalScope.toFilterExpression(String) 护栏单测：追加 embeddingModel 等值条件。
 *
 * @author ruan
 */
class RetrievalScopeFilterTest {

    @Test
    void 过滤表达式追加embeddingModel等值() {
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L), DocumentVisibility.INTERNAL);

        String expr = scope.toFilterExpression("qwen3.7-text-embedding");

        assertThat(expr).contains("appId == '2'")
                .contains("knowledgeBaseId in ['10']")
                .contains("docEnabled == 'true'")
                .contains("embeddingModel == 'qwen3.7-text-embedding'");
    }
}

package com.qingyu.care.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 相似度阈值默认跟随向量模型：刻度随模型平移，写死在策略上换完模型就失准。
 *
 * @author ruan
 */
class ChatStrategyThresholdTest {

    @Test
    void 未设置跟随_视为跟随_用模型推荐值() {
        ChatStrategy s = ChatStrategy.builder().similarityThreshold(0.45).build();

        assertThat(s.effectiveSimilarityThreshold(0.6)).isEqualTo(0.6);
    }

    @Test
    void 显式关掉跟随_用策略自己的值() {
        ChatStrategy s = ChatStrategy.builder().similarityThreshold(0.55).followModelThreshold(false).build();

        assertThat(s.effectiveSimilarityThreshold(0.6)).isEqualTo(0.55);
    }

    @Test
    void 模型没给推荐值_退回策略的值() {
        ChatStrategy s = ChatStrategy.builder().similarityThreshold(0.5).build();

        assertThat(s.effectiveSimilarityThreshold(null)).isEqualTo(0.5);
    }
}

package com.qingyu.care.service;

import com.qingyu.care.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 置信度解析纯函数：Spring AI M4 PgVectorStore 把余弦距离写入 metadata 键 "distance"（Float），
 * 从不写 "score"——历史代码读 "score" 导致置信度恒 0、低置信转人工恒触发。
 * 语义：score = 1 - distance（COSINE_DISTANCE），钳位 [0,1]；"score" 键存在时优先（兼容自定义写入）。
 *
 * @author ruan
 */
class ChatServiceConfidenceScoreTest {

    private Document doc(Map<String, Object> metadata) {
        return new Document("id-1", "内容", metadata);
    }

    @Test
    void distance为Float_按1减distance换算() {
        // M4 DocumentRowMapper 写入的是 Float.valueOf(rs.getFloat(...))
        double score = ChatServiceImpl.resolveConfidenceScore(doc(Map.of("distance", 0.25f)));
        assertThat(score).isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void distance为Double_同样兼容() {
        double score = ChatServiceImpl.resolveConfidenceScore(doc(Map.of("distance", 0.4d)));
        assertThat(score).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void score键存在时优先_且按Number读取() {
        double score = ChatServiceImpl.resolveConfidenceScore(
                doc(Map.of("score", 0.9f, "distance", 0.5d)));
        assertThat(score).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void 两键皆无_返回0() {
        // 关键词路命中无向量距离信息，置信度语义未知 → 0（已知局限）
        double score = ChatServiceImpl.resolveConfidenceScore(doc(Map.of("source", "手册")));
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void distance大于1_钳位到0() {
        // 余弦距离对反向向量可达 2，1-distance 为负须钳位
        double score = ChatServiceImpl.resolveConfidenceScore(doc(Map.of("distance", 1.4d)));
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void 非数值类型_安全返回0() {
        double score = ChatServiceImpl.resolveConfidenceScore(doc(Map.of("distance", "坏数据")));
        assertThat(score).isEqualTo(0.0);
    }
}

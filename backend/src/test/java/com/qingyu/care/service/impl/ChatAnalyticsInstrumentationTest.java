package com.qingyu.care.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：extractHitDocumentIds 纯方法（static package-private，同 meterContentStream 惯例）。
 *
 * @author ruan
 */
class ChatAnalyticsInstrumentationTest {

    @Test
    void 提取命中文档ID_去重升序为JSON数组() {
        Document d1 = new Document("c1", Map.of("documentId", "12"));
        Document d2 = new Document("c2", Map.of("documentId", "3"));
        Document d3 = new Document("c3", Map.of("documentId", "12"));
        assertThat(ChatServiceImpl.extractHitDocumentIds(List.of(d1, d2, d3))).isEqualTo("[3,12]");
    }

    @Test
    void 空命中或缺键返回null() {
        assertThat(ChatServiceImpl.extractHitDocumentIds(List.of())).isNull();
        Document noKey = new Document("c", Map.of("source", "x"));
        assertThat(ChatServiceImpl.extractHitDocumentIds(List.of(noKey))).isNull();
    }

    @Test
    void 非数字documentId被忽略() {
        Document bad = new Document("c", Map.of("documentId", "abc"));
        Document good = new Document("c", Map.of("documentId", "7"));
        assertThat(ChatServiceImpl.extractHitDocumentIds(List.of(bad, good))).isEqualTo("[7]");
    }
}

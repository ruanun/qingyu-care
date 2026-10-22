package com.qingyu.care.service.impl;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TikaTextExtractor}：流式解析 + 文本输出上限（安全审查 M8 第二段，2026-09-03）。
 *
 * <p>此前走 Spring AI 的 {@code TikaDocumentReader}：整个文件先读成 {@code byte[]}，再用
 * {@code BodyContentHandler(-1)}（无上限）抽文本。100MB 的文件 × 长任务池 6 线程就能把堆打爆，
 * 抽出多少文本就切多少块、嵌多少次，embedding 费用没有封顶。
 *
 * @author ruan
 */
class TikaTextExtractorTest {

    private final TikaTextExtractor extractor = new TikaTextExtractor();

    @Test
    void 纯文本按原样抽出() {
        String content = "轻语客服知识内容，用于验证流式抽取。";

        TikaTextExtractor.ExtractedText result = extractor.extract(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), 10_000);

        assertThat(result.text()).contains("轻语客服知识内容");
        assertThat(result.truncated()).isFalse();
    }

    /** 变异实验：把 {@code BodyContentHandler} 的写入上限改回 -1，本条转红。 */
    @Test
    void 超过上限时截断并标记() {
        String content = "abcdefghij".repeat(1000);   // 10_000 字符

        TikaTextExtractor.ExtractedText result = extractor.extract(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), 500);

        assertThat(result.truncated()).isTrue();
        assertThat(result.text().length()).isLessThanOrEqualTo(500);
        assertThat(result.text()).startsWith("abcdefghij");
    }

    @Test
    void 空输入返回空文本() {
        TikaTextExtractor.ExtractedText result = extractor.extract(
                new ByteArrayInputStream(new byte[0]), 100);

        assertThat(result.text()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }
}

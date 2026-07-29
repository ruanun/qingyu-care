package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** UI-spec 围栏解析：纯文本/单围栏交错/多围栏/非法JSON容错/未闭合围栏 fail-soft。 */
class UiSpecServiceTest {

    private final UiSpecService service = new UiSpecService(new ObjectMapper());

    @Test
    void 无围栏_单text片段_plainText等于原文() {
        var r = service.parse("你好，有什么可以帮您？");
        assertThat(r.uiParts()).hasSize(1);
        assertThat(r.uiParts().get(0).getKind()).isEqualTo("text");
        assertThat(r.plainText()).isEqualTo("你好，有什么可以帮您？");
    }

    @Test
    void 单围栏交错前后文本_解析为text_ui_text() {
        String raw = "这是您的订单：\n```spec\n{\"type\":\"card\",\"props\":{\"title\":\"订单\"}}\n```\n还需要别的吗？";
        var r = service.parse(raw);
        assertThat(r.uiParts()).extracting("kind").containsExactly("text", "ui", "text");
        assertThat(r.plainText()).doesNotContain("card").contains("这是您的订单").contains("还需要别的吗");
    }

    @Test
    void 多围栏() {
        String raw = "```spec\n{\"type\":\"badge\"}\n```中间\n```spec\n{\"type\":\"divider\"}\n```";
        var r = service.parse(raw);
        assertThat(r.uiParts()).extracting("kind").containsExactly("ui", "text", "ui");
    }

    @Test
    void 非法JSON围栏_fail_soft并入文本_不抛() {
        String raw = "看这个：\n```spec\n{不是合法json\n```";
        var r = service.parse(raw);
        // 非法围栏内容并入文本，整体不抛
        assertThat(r.uiParts()).allMatch(p -> p.getKind().equals("text"));
        assertThat(r.plainText()).contains("不是合法json");
    }

    @Test
    void 未闭合围栏_整体当文本_不抛() {
        String raw = "开始\n```spec\n{\"type\":\"card\"}";
        var r = service.parse(raw);
        assertThat(r.uiParts()).allMatch(p -> p.getKind().equals("text"));
    }

    @Test
    void null或空_返回单空text不抛() {
        assertThat(service.parse(null).uiParts()).hasSize(1);
        assertThat(service.parse("").uiParts()).hasSize(1);
    }
}

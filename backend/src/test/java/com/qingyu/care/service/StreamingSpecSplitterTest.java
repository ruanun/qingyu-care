package com.qingyu.care.service;

import com.qingyu.care.dto.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：流式 ```spec 围栏增量切分。无 Spring 容器依赖，可直接 new。
 *
 * 核心不变量：无论 chunk 怎么切，产出的文本拼接结果都与 UiSpecService.parse(全文) 一致，
 * 卡片节点也一一对应——落库走 parse、渲染走 splitter，两者不可漂移。
 *
 * @author ruan
 */
class StreamingSpecSplitterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 把一串 chunk 喂进去，收集全部事件（含 flush）。 */
    private List<ChatStreamEvent> feed(List<String> chunks) {
        StreamingSpecSplitter splitter = new StreamingSpecSplitter(objectMapper);
        List<ChatStreamEvent> events = new ArrayList<>();
        for (String c : chunks) {
            events.addAll(splitter.accept(c));
        }
        events.addAll(splitter.flush());
        return events;
    }

    /** 把全文按单字符切开喂入——最严苛的切割方式。 */
    private List<ChatStreamEvent> feedCharByChar(String full) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < full.length(); i++) {
            chunks.add(String.valueOf(full.charAt(i)));
        }
        return feed(chunks);
    }

    private String textOf(List<ChatStreamEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (ChatStreamEvent e : events) {
            if (e.type() == ChatStreamEvent.Type.DELTA) {
                sb.append(((ChatStreamEvent.Delta) e.payload()).text());
            }
        }
        return sb.toString();
    }

    private long cardCount(List<ChatStreamEvent> events) {
        return events.stream().filter(e -> e.type() == ChatStreamEvent.Type.CARD).count();
    }

    @Test
    void 无围栏时原样吐文本() {
        List<ChatStreamEvent> events = feed(List.of("你好", "，世界"));

        assertThat(textOf(events)).isEqualTo("你好，世界");
        assertThat(cardCount(events)).isZero();
    }

    @Test
    void 纯空白chunk也要吐出不能被丢弃() {
        // 空白在流式里是有意义的（词间空格），不能照搬 UiSpecService 的 isBlank 过滤
        List<ChatStreamEvent> events = feed(List.of("hello", " ", "world"));

        assertThat(textOf(events)).isEqualTo("hello world");
    }

    @Test
    void 完整围栏产出卡片且围栏标记不进文本() {
        String full = "请看：\n```spec\n{\"type\":\"card\"}\n```\n还有别的吗";

        List<ChatStreamEvent> events = feed(List.of(full));

        assertThat(cardCount(events)).isEqualTo(1);
        assertThat(textOf(events)).doesNotContain("```").doesNotContain("spec");
        assertThat(textOf(events)).contains("请看：").contains("还有别的吗");
    }

    @Test
    void 逐字符喂入与整块喂入结果一致() {
        String full = "请看：\n```spec\n{\"type\":\"card\",\"props\":{\"title\":\"订单\"}}\n```\n还有别的吗";

        List<ChatStreamEvent> whole = feed(List.of(full));
        List<ChatStreamEvent> byChar = feedCharByChar(full);

        assertThat(textOf(byChar)).isEqualTo(textOf(whole));
        assertThat(cardCount(byChar)).isEqualTo(cardCount(whole));
    }

    @Test
    void 起始标记被chunk切断仍能识别() {
        // "```spec" 被切成三段，中间还夹着换行
        List<ChatStreamEvent> events = feed(List.of("看这里``", "`spe", "c\n{\"type\":\"text\"}\n``", "`尾巴"));

        assertThat(cardCount(events)).isEqualTo(1);
        assertThat(textOf(events)).isEqualTo("看这里尾巴");
    }

    @Test
    void 多个围栏依次产出多张卡片() {
        String full = "A\n```spec\n{\"type\":\"card\"}\n```\nB\n```spec\n{\"type\":\"text\"}\n```\nC";

        List<ChatStreamEvent> events = feed(List.of(full));

        assertThat(cardCount(events)).isEqualTo(2);
        assertThat(textOf(events)).contains("A").contains("B").contains("C");
    }

    @Test
    void 未闭合围栏在flush时整段回退为文本() {
        // LLM 被截断：残余必须原样（含 ```spec 标记）回退为文本，与 UiSpecService 处理 after 同语义
        String full = "开头\n```spec\n{\"type\":\"car";

        List<ChatStreamEvent> events = feed(List.of(full));

        assertThat(cardCount(events)).isZero();
        assertThat(textOf(events)).isEqualTo(full);
    }

    @Test
    void 围栏内JSON非法时回退为文本不抛异常() {
        String full = "开头\n```spec\n这不是JSON\n```\n结尾";

        List<ChatStreamEvent> events = feed(List.of(full));

        assertThat(cardCount(events)).isZero();
        assertThat(textOf(events)).contains("这不是JSON").contains("开头").contains("结尾");
    }

    @Test
    void spec后面不是换行时不当作围栏() {
        // "```specification" 只是普通文本，不能误判为围栏起始
        List<ChatStreamEvent> events = feed(List.of("见 ```specification 文档"));

        assertThat(cardCount(events)).isZero();
        assertThat(textOf(events)).isEqualTo("见 ```specification 文档");
    }

    @Test
    void 与UiSpecService解析结果保持一致() {
        // 不变量守护：渲染（splitter）与落库（UiSpecService.parse）不可漂移。
        // 说明：parse 会丢弃 isBlank 片段并对 plainText 做 trim，逐块流式不可能做全局 trim，
        // 故断言在 trim 后比较。
        UiSpecService uiSpecService = new UiSpecService(objectMapper);
        String full = "前言\n```spec\n{\"type\":\"card\",\"children\":[]}\n```\n后记";

        UiSpecService.ParseResult parsed = uiSpecService.parse(full);
        List<ChatStreamEvent> events = feedCharByChar(full);

        assertThat(textOf(events).trim()).isEqualTo(parsed.plainText());
        assertThat(cardCount(events))
                .isEqualTo(parsed.uiParts().stream().filter(p -> "ui".equals(p.getKind())).count());
    }
}

package com.qingyu.care.service;

import com.qingyu.care.dto.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：ChatStreamEvent → ServerSentEvent 的事件名与 payload 序列化契约。
 * 协议见 docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3。
 *
 * @author ruan
 */
class ChatStreamSseMapperTest {

    private final ChatStreamSseMapper mapper = new ChatStreamSseMapper(new ObjectMapper());

    @Test
    void delta事件名小写且payload为JSON对象() {
        ServerSentEvent<String> sse = mapper.toSse(ChatStreamEvent.delta("你好"));

        assertThat(sse.event()).isEqualTo("delta");
        assertThat(sse.data()).isEqualTo("{\"text\":\"你好\"}");
    }

    @Test
    void delta中的换行被JSON转义为单行() {
        // 关键性质：delta 用 JSON 而非裸文本，正是为了让含换行的 chunk 在 SSE 层永远单行
        ServerSentEvent<String> sse = mapper.toSse(ChatStreamEvent.delta("第一行\n第二行"));

        assertThat(sse.data()).doesNotContain("\n");
        assertThat(sse.data()).isEqualTo("{\"text\":\"第一行\\n第二行\"}");
    }

    @Test
    void start携带会话与转人工建议与知识命中() {
        ServerSentEvent<String> sse = mapper.toSse(
                ChatStreamEvent.start("s_abc", true, List.of()));

        assertThat(sse.event()).isEqualTo("start");
        assertThat(sse.data()).contains("\"sessionId\":\"s_abc\"")
                .contains("\"suggestHandover\":true")
                .contains("\"knowledgeHits\":[]");
    }

    @Test
    void done的messageId为空时序列化为null() {
        ServerSentEvent<String> sse = mapper.toSse(ChatStreamEvent.done(null));

        assertThat(sse.event()).isEqualTo("done");
        assertThat(sse.data()).isEqualTo("{\"messageId\":null}");
    }

    @Test
    void 序列化失败时降级为空对象不抛异常() {
        // fail-soft：payload 里塞一个无法序列化的对象，不能让整条流因序列化崩掉
        Object bad = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() { throw new IllegalStateException("boom"); }
        };
        ServerSentEvent<String> sse = mapper.toSse(ChatStreamEvent.card(bad));

        assertThat(sse.event()).isEqualTo("card");
        assertThat(sse.data()).isEqualTo("{}");
    }
}

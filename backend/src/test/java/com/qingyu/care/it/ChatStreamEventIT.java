package com.qingyu.care.it;

import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.Application;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试：流式端点的 SSE 线格式契约（事件名 + JSON payload + 顺序）。
 *
 * ChatService 被 mock 成有限事件流——本 IT 只验证「控制器 + ChatStreamSseMapper + Spring MVC
 * SSE 编码」这一段协议契约，聚焦 {@link StreamPersistenceIT} 未覆盖的部分：card 事件的事件名/
 * payload 形状、delta 里的换行必须被 JSON 转义成单行（"delta 用 JSON 而非裸文本"这一设计决策的
 * 唯一守护）、以及 start→delta、card→done 的事件顺序。真实对话语义（落库、模型回退）由
 * StreamPersistenceIT 覆盖，token 计量细节由 StreamChatParityTest 等单测覆盖。
 *
 * 有限流会自然完成，不会像 RealtimeSseIT 那样挂起（那是无限流场景，MockMvc 不适用）。
 *
 * @author ruan
 */
class ChatStreamEventIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;

    @MockitoBean ChatService chatService;

    @Test
    void 开放端流式返回start_delta_card_done四类事件_顺序正确且delta换行已转义() throws Exception {
        Application app = seedApp("streamevt" + System.nanoTime(), "http://streamevt.example.com");

        Mockito.when(chatService.streamChat(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Flux.just(
                        ChatStreamEvent.start("s_it", true, List.of(), 7L),
                        ChatStreamEvent.delta("你好\n世界"),
                        ChatStreamEvent.card(Map.of("type", "card")),
                        ChatStreamEvent.done(42L)));

        MvcResult started = mockMvc.perform(post("/open/v1/chat/stream")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:start").contains("\"sessionId\":\"s_it\"")
                .contains("\"userMessageId\":7")
                .contains("event:delta")
                .contains("event:card").contains("\"node\":{\"type\":\"card\"}")
                .contains("event:done").contains("\"messageId\":42");
        // delta 里的换行必须已被 JSON 转义（SSE 层保持单行，不能出现裸换行拆成两条 data:）
        assertThat(body).contains("\\n世界");
        assertThat(body).doesNotContain("你好\n世界");
        // 事件顺序：start 先于 delta，card 先于 done
        assertThat(body.indexOf("event:start")).isLessThan(body.indexOf("event:delta"));
        assertThat(body.indexOf("event:card")).isLessThan(body.indexOf("event:done"));
    }
}

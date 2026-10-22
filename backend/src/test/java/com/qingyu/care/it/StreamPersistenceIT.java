package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式对话补落库 + SSE 事件协议（start/delta/done）。ModelRouterService 整体 mock，
 * 直接桩 streamWithFallback 的返回值（不再需要深桩 ChatClient——streamChat 已改走
 * streamWithFallback，不再经 getChatClientForScenario）；不 mock VectorStore——
 * 会破坏共享表，见多知识库检索 IT 的教训。
 *
 * @author ruan
 */
class StreamPersistenceIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatMessageRepository messageRepository;

    @MockitoBean
    private ModelRouterService modelRouterService;

    @Test
    void 流式对话_消息落库并携带DONE事件() throws Exception {
        Application app = seedApp("stream" + System.nanoTime(), "http://st.example.com");
        // 流式路径按租户配置解析模型场景（tenant.default.model.scenario，缺省 default）；
        // streamChat 内部把「发起请求」的 streamRunner 交给 streamWithFallback 执行，
        // 直接桩它的返回值即可，无需再深桩 ChatClient
        Mockito.when(modelRouterService.streamWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(Flux.just(SpringAiResponses.of("你好", 5),
                        SpringAiResponses.of("！", 7)));

        MvcResult mvcResult = mockMvc.perform(post("/open/v1/chat/stream")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"流式落库验证\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:start");
        assertThat(body).contains("event:delta");
        assertThat(body).contains("\"text\":\"你好\"");
        assertThat(body).contains("event:done");
        assertThat(body).contains("messageId");

        // 落库：用户提问 + 聚合后的完整 AI 回复（"你好！"）
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var saved = messageRepository.findAll().stream()
                    .filter(m -> "流式落库验证".equals(m.getContent())
                            || "你好！".equals(m.getContent()))
                    .toList();
            assertThat(saved).hasSize(2);
        });
    }
}

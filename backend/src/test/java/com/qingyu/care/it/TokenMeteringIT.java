package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 非流式开放 API 对话后，sys_api_usage.token_count 应为真实 total_tokens（回归 token_count 恒 0）。
 *
 * @author ruan
 */
class TokenMeteringIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SysApiUsageRepository usageRepository;

    @MockitoBean
    private ModelRouterService modelRouterService;

    private Application app;

    @AfterEach
    void cleanup() {
        if (app != null) {
            usageRepository.findAll().stream()
                    .filter(u -> app.getId().equals(u.getAppId()))
                    .forEach(usageRepository::delete);
        }
    }

    @Test
    void 非流式对话计量真实token() throws Exception {
        app = seedApp("tokmeter", "http://it.example.com");
        // 打桩：任意模型调用返回带 usage(total=42) 的 Spring AI 响应
        Mockito.when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("MOCK_AI_REPLY", 42));

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey()).header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        await().atMost(5, SECONDS).untilAsserted(() -> {
            var rows = usageRepository.findAll().stream()
                    .filter(u -> app.getId().equals(u.getAppId())).toList();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTokenCount()).isEqualTo(42);
        });
    }
}

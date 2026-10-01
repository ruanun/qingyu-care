package com.qingyu.care.service;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 模型 endpoint 的出站防线走<b>真实的</b> Spring AI → openai-java → OkHttp 调用链（2026-09-30 安全审查 M1）。
 *
 * <p>租户把 endpoint 填成自己的公网服务器，让它回 {@code 307 Location: http://169.254.169.254/...}——此前 OkHttp
 * 默认跟随，平台带着请求体去打云元数据 / 内网，而落库校验只看第一跳。这里起两台本地服务器：「攻击者」只回 307，
 * 「内网目标」记录被访问次数，断言第二跳一次都没被打到。私网开关打开（相当于 dev profile），否则连第一跳都连不上。
 *
 * @author ruan
 */
class ModelEndpointRedirectGuardTest {

    private HttpServer attacker;
    private HttpServer internal;
    private final AtomicInteger internalHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        internal = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        internal.createContext("/", ex -> {
            internalHits.incrementAndGet();
            byte[] body = "{\"secret\":\"内网数据\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        internal.start();

        attacker = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        attacker.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location",
                    "http://localhost:" + internal.getAddress().getPort() + "/latest/meta-data/");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        attacker.start();
    }

    @AfterEach
    void stop() {
        attacker.stop(0);
        internal.stop(0);
    }

    private ChatClient clientFor(boolean allowPrivate) {
        ModelRouterService service = new ModelRouterService(mock(AiModelConfigRepository.class), mock(com.qingyu.care.config.SharedCredentialMasker.class));
        ReflectionTestUtils.setField(service, "allowPrivateEndpoints", allowPrivate);
        AiModelConfig config = new AiModelConfig();
        config.setProvider("openai");
        config.setModelId("x");
        config.setApiKey("k");
        config.setEndpoint("http://localhost:" + attacker.getAddress().getPort() + "/v1");
        return service.createChatClient(config);
    }

    @Test
    void 模型端点回重定向时不跟随_内网目标零访问() {
        ChatClient client = clientFor(true);

        assertThatThrownBy(() -> client.prompt().user("你好").call().content())
                .hasStackTraceContaining("重定向");
        assertThat(internalHits.get()).isZero();
    }

    @Test
    void 未开私网开关时连环回地址都不连() {
        ChatClient client = clientFor(false);

        assertThatThrownBy(() -> client.prompt().user("你好").call().content())
                .hasStackTraceContaining("模型端点指向内网或元数据地址");
        assertThat(internalHits.get()).isZero();
    }
}

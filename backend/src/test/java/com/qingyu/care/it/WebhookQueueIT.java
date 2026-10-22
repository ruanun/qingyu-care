package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.WebhookProperties;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook 队列化端到端 IT（真实 Redis 容器）：
 * 触发 handover → 落投递行 → XADD 入流 → 消费组拉取 → HTTP 投递成功。
 * 与 {@link WebhookDeliveryIT}（无 Redis 上下文，验证 fail-open 直投兜底）互补，
 * 二者合起来守护「队列优先、直投兜底」两条路径。
 * 注意：本类自带 Redis 动态属性，会另起一个 Spring 上下文，不与其它 IT 共享。
 *
 * @author ruan
 */
class WebhookQueueIT extends AbstractPostgresIT {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired MockMvc mockMvc;
    @Autowired ChatSessionRepository chatSessionRepository;
    @Autowired WebhookDeliveryRepository webhookDeliveryRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired WebhookProperties webhookProperties;

    private HttpServer receiver;
    private final AtomicInteger received = new AtomicInteger();
    private Application app;
    private ChatSession session;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            received.incrementAndGet();
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        receiver.start();
        int port = receiver.getAddress().getPort();

        app = seedApp("whq", "http://itq.example.com");
        app.setWebhookUrl("http://127.0.0.1:" + port + "/hook");
        app.setWebhookSecret("whsec_it_queue");
        app.setWebhookEvents("session.handover_requested");
        app = applicationRepository.save(app);

        sessionId = "it-queue-sess-" + port;
        try {
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            ChatSession s = new ChatSession();
            s.setSessionId(sessionId);
            s.setStatus(SessionStatus.AI_PROCESSING);
            s.setUserId("it-queue-user");
            session = chatSessionRepository.save(s);
        } finally {
            TenantContext.clear();
        }
    }

    // 与 WebhookDeliveryIT 同理：不加 @Transactional（AFTER_COMMIT 只在真实提交触发），须自清理种子数据
    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.stop(0);
        }
        try {
            webhookDeliveryRepository.findAll().stream()
                    .filter(d -> app != null && app.getId().equals(d.getAppId()))
                    .forEach(webhookDeliveryRepository::delete);
        } catch (Exception ignored) {
            // 清理失败不阻断
        }
        deleteQuietly(session, chatSessionRepository::delete);
        deleteQuietly(app, applicationRepository::delete);
        deleteQuietly(app == null ? null : app.getTenant(), tenantRepository::delete);
    }

    private static <T> void deleteQuietly(T entity, java.util.function.Consumer<T> deleter) {
        if (entity == null) {
            return;
        }
        try {
            deleter.accept(entity);
        } catch (Exception ignored) {
            // 单步清理失败不影响其它清理
        }
    }

    @Test
    void 队列路径_入流消费并投递成功() throws Exception {
        mockMvc.perform(post("/open/v1/sessions/" + sessionId + "/handover")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret()))
                .andExpect(status().isOk());

        // 投递成功（经消费组拉取执行）
        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(received.get()).isGreaterThanOrEqualTo(1);
            assertThat(webhookDeliveryRepository.findAll().stream()
                    .anyMatch(d -> app.getId().equals(d.getAppId())
                            && d.getStatus() == WebhookDeliveryStatus.SUCCESS)).isTrue();
        });

        // 消息确实经过了 Redis Stream（XADD 已发生，流长度 ≥ 1）
        Long streamLen = redisTemplate.opsForStream()
                .size(webhookProperties.getQueue().getStreamKey());
        assertThat(streamLen).isNotNull();
        assertThat(streamLen).isGreaterThanOrEqualTo(1L);
    }
}

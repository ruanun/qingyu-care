package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
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
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 触发 handover → 事务提交后落 sys_webhook_delivery(REQUIRES_NEW) → 异步投递到本地桩 → HMAC 验签。
 *
 * @author ruan
 */
class WebhookDeliveryIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    private HttpServer receiver;
    private int port;
    private final ConcurrentMap<String, String> captured = new ConcurrentHashMap<>();
    private Application app;
    private ChatSession session;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                captured.put("body", body);
                String sig = exchange.getRequestHeaders().getFirst("X-Qingyu-Signature");
                if (sig != null) captured.put("signature", sig);
                String ts = exchange.getRequestHeaders().getFirst("X-Qingyu-Timestamp");
                if (ts != null) captured.put("timestamp", ts);
            }
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(ok); }
        });
        receiver.start();
        port = receiver.getAddress().getPort();

        app = seedApp("wh", "http://it.example.com");
        app.setWebhookUrl("http://127.0.0.1:" + port + "/hook");
        app.setWebhookSecret("whsec_it_001");
        app.setWebhookEvents("session.ended,session.handover_requested");
        app = applicationRepository.save(app);

        sessionId = "it-sess-" + port;
        try {
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            ChatSession s = new ChatSession();
            s.setSessionId(sessionId);
            s.setStatus(SessionStatus.AI_PROCESSING);
            s.setUserId("it-user");
            session = chatSessionRepository.save(s);
        } finally {
            TenantContext.clear();
        }
    }

    // 本 IT 不加 @Transactional（AFTER_COMMIT 监听器只在真实提交时触发），播种行会真实入库。
    // 容器是 JVM 内单例、跨 IT 复用，故必须自清理：否则残留会累积，且 seedApp("wh") 的
    // app_key 固定为 app_it_wh，同 JVM 重跑该 IT 会撞唯一约束。按外键安全顺序删（子→父），
    // 每步 null 判断 + try/catch，保证清理不因单步失败中断、也不影响测试结果。
    @AfterEach
    void tearDown() {
        if (receiver != null) receiver.stop(0);
        try {
            webhookDeliveryRepository.findAll().stream()
                    .filter(d -> app != null && app.getId().equals(d.getAppId()))
                    .forEach(webhookDeliveryRepository::delete);
        } catch (Exception ignored) {
            // 投递行清理失败不阻断后续父行清理
        }
        deleteQuietly(session, chatSessionRepository::delete);
        deleteQuietly(app, applicationRepository::delete);
        deleteQuietly(app == null ? null : app.getTenant(), tenantRepository::delete);
    }

    /** 按主键删除单个实体（不受租户过滤影响），null 跳过、异常吞掉，避免清理链中断。 */
    private static <T> void deleteQuietly(T entity, java.util.function.Consumer<T> deleter) {
        if (entity == null) {
            return;
        }
        try {
            deleter.accept(entity);
        } catch (Exception ignored) {
            // 单步清理失败不影响其它清理与测试结果
        }
    }

    @Test
    void 转人工触发webhook落库投递并验签() throws Exception {
        mockMvc.perform(post("/open/v1/sessions/" + sessionId + "/handover")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret()))
                .andExpect(status().isOk());

        await().atMost(10, SECONDS).untilAsserted(() -> {
            boolean delivered = webhookDeliveryRepository.findAll().stream()
                    .anyMatch(d -> app.getId().equals(d.getAppId())
                            && d.getStatus() == WebhookDeliveryStatus.SUCCESS);
            assertThat(delivered).isTrue();
            assertThat(captured).containsKey("body").containsKey("signature");
        });

        String body = captured.get("body");
        // 签名覆盖「时间戳.请求体」（安全审查 L6）：接收方按同法复算
        String expected = "sha256=" + hmacSha256("whsec_it_001", captured.get("timestamp") + "." + body);
        assertThat(captured.get("signature")).isEqualTo(expected);
        assertThat(body).contains("session.handover_requested");
    }

    private static String hmacSha256(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}

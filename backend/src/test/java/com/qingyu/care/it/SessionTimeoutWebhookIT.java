package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import com.qingyu.care.scheduler.SessionTimeoutScheduler;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话超时 → 发布 session.ended（trigger=timeout）→ 事务提交后落投递行。
 *
 * <p>单测里 {@code ApplicationEventPublisher} 是 mock，只能证明「发了事件」。真正承重的是后半段：
 * {@code WebhookDispatcher} 是 {@code @TransactionalEventListener(AFTER_COMMIT)}，要在调度器的事务
 * 提交后被触发，并按应用订阅过滤、带上 trigger 落投递行——这一段只有真实 Spring 容器里走得到。
 *
 * @author ruan
 */
class SessionTimeoutWebhookIT extends AbstractPostgresIT {

    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;
    @Autowired
    private SessionTimeoutScheduler scheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Application app;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        app = seedApp("timeout-wh", "http://it.example.com");
        // 投递本身不是本条关注点：指向本机 discard 端口，连接被拒只会让投递行进入重试，不影响断言
        app.setWebhookUrl("http://127.0.0.1:9/hook");
        app.setWebhookSecret("whsec_it_timeout");
        app.setWebhookEvents("session.ended");
        app = applicationRepository.save(app);
        try {
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            ChatSession s = new ChatSession();
            s.setSessionId("it-timeout-" + app.getId());
            s.setStatus(SessionStatus.AI_PROCESSING);
            s.setUserId("it-user");
            session = chatSessionRepository.save(s);
        } finally {
            TenantContext.clear();
        }
        // updated_at 由 @UpdateTimestamp 维护，只能用原生 SQL 回拨到超时阈值之前
        jdbcTemplate.update("UPDATE chat_session SET updated_at = now() - interval '2 days' WHERE id = ?", session.getId());
    }

    @AfterEach
    void tearDown() {
        try {
            webhookDeliveryRepository.findAll().stream()
                    .filter(d -> app != null && app.getId().equals(d.getAppId()))
                    .forEach(webhookDeliveryRepository::delete);
        } catch (Exception ignored) {
            // 清理失败不影响后续
        }
        try {
            chatSessionRepository.deleteById(session.getId());
        } catch (Exception ignored) {
            // 同上
        }
        try {
            applicationRepository.delete(app);
            tenantRepository.delete(app.getTenant());
        } catch (Exception ignored) {
            // 同上
        }
    }

    @Test
    void 超时关闭的会话发出session_ended投递_trigger为timeout() {
        scheduler.checkTimeoutSessions();

        assertThat(chatSessionRepository.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.TIMEOUT);
        List<WebhookDelivery> deliveries = webhookDeliveryRepository.findAll().stream()
                .filter(d -> app.getId().equals(d.getAppId()))
                .toList();
        assertThat(deliveries).singleElement().satisfies(d -> {
            assertThat(d.getEvent()).isEqualTo("session.ended");
            assertThat(d.getPayload())
                    .contains("\"trigger\":\"timeout\"")
                    .contains(session.getSessionId());
        });
        // 等异步投递至少尝试过一次再结束：否则 tearDown 删行与投递线程更新同一行会打一条 StaleObjectState 噪声
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                webhookDeliveryRepository.findById(deliveries.getFirst().getId())
                        .map(d -> d.getAttempts() > 0).orElse(true));
    }
}

package com.qingyu.care.service.webhook;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.event.WebhookDomainEvent;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    @Mock ApplicationRepository applicationRepository;
    @Mock WebhookDeliveryRepository deliveryRepository;
    @Mock WebhookPayloadBuilder payloadBuilder;
    @Mock WebhookQueue queue;
    @InjectMocks WebhookDispatcher dispatcher;

    private WebhookDomainEvent event() {
        return new WebhookDomainEvent("session.ended", 9L, 1L, "s-1", null, LocalDateTime.now());
    }

    private Application app(String url, String events) {
        return app(url, events, 1L);
    }

    private Application app(String url, String events, Long tenantId) {
        return Application.builder().id(9L).webhookUrl(url).webhookEvents(events)
                .tenant(Tenant.builder().id(tenantId).build()).build();
    }

    @Test
    void skips_whenAppIdNull() {
        dispatcher.onDomainEvent(new WebhookDomainEvent("session.ended", null, 1L, "s-1", null, LocalDateTime.now()));
        verifyNoInteractions(deliveryRepository, queue);
    }

    @Test
    void skips_whenNoWebhookUrl() {
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(app(null, "session.ended")));
        dispatcher.onDomainEvent(event());
        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(queue);
    }

    @Test
    void skips_whenEventNotSubscribed() {
        when(applicationRepository.findById(9L)).thenReturn(
                Optional.of(app("https://u", "session.handover_requested")));
        dispatcher.onDomainEvent(event());
        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(queue);
    }

    @Test
    void persistsAndDispatches_whenSubscribed() {
        when(applicationRepository.findById(9L)).thenReturn(
                Optional.of(app("https://u", "session.ended,session.handover_requested")));
        when(deliveryRepository.save(any())).thenAnswer(inv -> {
            WebhookDelivery d = inv.getArgument(0);
            if (d.getId() == null) d.setId(100L);
            return d;
        });
        when(payloadBuilder.build(any(), any(), any(), any())).thenReturn("{\"id\":\"100\"}");

        dispatcher.onDomainEvent(event());

        verify(deliveryRepository, atLeastOnce()).save(any());
        verify(queue).dispatch(100L);
    }
    /**
     * 安全审查 M3（2026-09-03）：事件的 appId 来自会话行的 app_id，而那一列曾可被 X-App-Id 请求头
     * 写成别的租户的应用。拦截器已校验归属，这里再做一道纵深防御——应用的租户与事件的租户
     * 不一致时绝不投递（否则会用别的租户的密钥向对方回调地址发出签名正确的伪造事件）。
     */
    @Test
    void skips_whenAppBelongsToAnotherTenant() {
        when(applicationRepository.findById(9L)).thenReturn(
                Optional.of(app("https://u", "session.ended", 2L)));   // 事件 tenantId=1，应用属于租户 2

        dispatcher.onDomainEvent(event());

        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(queue);
    }
}

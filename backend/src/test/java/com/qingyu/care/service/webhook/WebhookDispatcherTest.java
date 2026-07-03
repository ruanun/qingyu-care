package com.qingyu.care.service.webhook;

import com.qingyu.care.entity.Application;
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
    @Mock WebhookSender sender;
    @InjectMocks WebhookDispatcher dispatcher;

    private WebhookDomainEvent event() {
        return new WebhookDomainEvent("session.ended", 9L, 1L, "s-1", null, LocalDateTime.now());
    }

    private Application app(String url, String events) {
        return Application.builder().id(9L).webhookUrl(url).webhookEvents(events).build();
    }

    @Test
    void skips_whenAppIdNull() {
        dispatcher.onDomainEvent(new WebhookDomainEvent("session.ended", null, 1L, "s-1", null, LocalDateTime.now()));
        verifyNoInteractions(deliveryRepository, sender);
    }

    @Test
    void skips_whenNoWebhookUrl() {
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(app(null, "session.ended")));
        dispatcher.onDomainEvent(event());
        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(sender);
    }

    @Test
    void skips_whenEventNotSubscribed() {
        when(applicationRepository.findById(9L)).thenReturn(
                Optional.of(app("https://u", "session.handover_requested")));
        dispatcher.onDomainEvent(event());
        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(sender);
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
        verify(sender).deliverAsync(100L);
    }
}

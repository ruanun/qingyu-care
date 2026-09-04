package com.qingyu.care.service.webhook;

import com.qingyu.care.config.WebhookProperties;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookSenderTest {

    @Mock WebhookDeliveryRepository deliveryRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock WebhookHttpClient httpClient;
    @Mock WebhookSignature signature;
    WebhookProperties properties;
    WebhookSender sender;

    @BeforeEach
    void setup() {
        properties = new WebhookProperties(); // maxAttempts=5, backoff=[1,5,15,30,60]
        sender = new WebhookSender(deliveryRepository, applicationRepository, httpClient, signature, properties);
    }

    private WebhookDelivery pending(long id, int attempts) {
        return WebhookDelivery.builder().id(id).appId(9L).event("session.ended")
                .targetUrl("https://cb.example.com/hook").payload("{}")
                .status(WebhookDeliveryStatus.PROCESSING).attempts(attempts).build();
    }

    @Test
    void deliverAsync_skips_whenClaimFails() {
        when(deliveryRepository.claim(1L)).thenReturn(0);
        sender.deliverAsync(1L);
        verify(deliveryRepository, never()).findById(anyLong());
        verify(httpClient, never()).post(any(), any(), any());
    }

    @Test
    void deliverAsync_marksSuccess_on2xx() {
        when(deliveryRepository.claim(1L)).thenReturn(1);
        when(deliveryRepository.findById(1L)).thenReturn(Optional.of(pending(1L, 0)));
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(
                Application.builder().id(9L).webhookSecret("sec").build()));
        when(signature.sign(eq("sec"), anyLong(), anyString())).thenReturn("sha256=x");
        when(httpClient.post(any(), any(), any())).thenReturn(200);

        sender.deliverAsync(1L);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(cap.getValue().getResponseStatus()).isEqualTo(200);
    }

    @Test
    void deliverAsync_reschedules_onNon2xx_whenAttemptsRemain() {
        when(deliveryRepository.claim(1L)).thenReturn(1);
        when(deliveryRepository.findById(1L)).thenReturn(Optional.of(pending(1L, 0)));
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(
                Application.builder().id(9L).webhookSecret("sec").build()));
        when(httpClient.post(any(), any(), any())).thenReturn(500);

        sender.deliverAsync(1L);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().getAttempts()).isEqualTo(1);
        assertThat(cap.getValue().getNextAttemptAt()).isNotNull();
    }

    @Test
    void deliverAsync_marksFailed_whenAttemptsExhausted() {
        when(deliveryRepository.claim(1L)).thenReturn(1);
        when(deliveryRepository.findById(1L)).thenReturn(Optional.of(pending(1L, 4))); // 第 5 次失败
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(
                Application.builder().id(9L).webhookSecret("sec").build()));
        when(httpClient.post(any(), any(), any())).thenReturn(500);

        sender.deliverAsync(1L);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(cap.getValue().getAttempts()).isEqualTo(5);
    }

    @Test
    void deliverAsync_reschedules_onTransportException() {
        when(deliveryRepository.claim(1L)).thenReturn(1);
        when(deliveryRepository.findById(1L)).thenReturn(Optional.of(pending(1L, 0)));
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(
                Application.builder().id(9L).webhookSecret("sec").build()));
        when(httpClient.post(any(), any(), any())).thenThrow(new RuntimeException("connect timeout"));

        sender.deliverAsync(1L);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().getLastError()).contains("connect timeout");
    }

    @Test
    void send_setsSignatureAndEventHeaders() {
        when(signature.sign(eq("sec"), anyLong(), eq("{}"))).thenReturn("sha256=abc");
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        when(httpClient.post(eq("https://u"), headers.capture(), eq("{}"))).thenReturn(204);

        int status = sender.send("https://u", "sec", "session.ended", "d-1", "{}");

        assertThat(status).isEqualTo(204);
        assertThat(headers.getValue()).containsEntry("X-Qingyu-Event", "session.ended");
        assertThat(headers.getValue()).containsEntry("X-Qingyu-Delivery-Id", "d-1");
        assertThat(headers.getValue()).containsEntry("X-Qingyu-Signature", "sha256=abc");
        // 时间戳必须随请求发出，接收方靠它复算签名并拒绝过期投递（安全审查 L6）
        assertThat(headers.getValue()).containsKey("X-Qingyu-Timestamp");
        assertThat(Long.parseLong(headers.getValue().get("X-Qingyu-Timestamp"))).isPositive();
    }
}

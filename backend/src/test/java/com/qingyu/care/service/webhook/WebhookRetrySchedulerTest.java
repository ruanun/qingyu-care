package com.qingyu.care.service.webhook;

import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Webhook 重试扫描调度单测：到期 PENDING 逐条经队列派发（队列内部再做直投兜底）。
 * 此前该调度器无专门测试，重试链路只被 SenderTest 间接覆盖。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WebhookRetrySchedulerTest {

    @Mock WebhookDeliveryRepository deliveryRepository;
    @Mock WebhookQueue queue;
    @InjectMocks WebhookRetryScheduler scheduler;

    @Test
    void 到期PENDING_逐条经队列派发() {
        WebhookDelivery d1 = WebhookDelivery.builder().id(1L).appId(9L).build();
        WebhookDelivery d2 = WebhookDelivery.builder().id(2L).appId(9L).build();
        when(deliveryRepository.findByStatusAndNextAttemptAtLessThanEqual(
                eq(WebhookDeliveryStatus.PENDING), any(), any()))
                .thenReturn(List.of(d1, d2));

        scheduler.scanAndRetry();

        verify(queue).dispatch(1L);
        verify(queue).dispatch(2L);
    }

    @Test
    void 无到期投递_不派发() {
        when(deliveryRepository.findByStatusAndNextAttemptAtLessThanEqual(
                eq(WebhookDeliveryStatus.PENDING), any(), any()))
                .thenReturn(List.of());

        scheduler.scanAndRetry();

        verifyNoInteractions(queue);
    }
}

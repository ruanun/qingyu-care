package com.qingyu.care.service.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook 队列消费者单测：先 ACK 后投递；非法消息跳过投递但仍 ACK。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WebhookStreamConsumerTest {

    @Mock WebhookQueue queue;
    @Mock WebhookSender sender;
    @InjectMocks WebhookStreamConsumer consumer;

    private MapRecord<String, Object, Object> record(String deliveryId) {
        return StreamRecords.newRecord().in("k")
                .ofMap(Map.of(WebhookQueue.FIELD_DELIVERY_ID, deliveryId));
    }

    @Test
    void 消费批量消息_逐条ACK并异步投递() {
        MapRecord<String, Object, Object> r1 = record("100");
        MapRecord<String, Object, Object> r2 = record("101");
        when(queue.poll()).thenReturn(List.of(r1, r2));
        when(queue.deliveryIdOf(r1)).thenReturn(100L);
        when(queue.deliveryIdOf(r2)).thenReturn(101L);

        consumer.pollAndDeliver();

        verify(queue).ack(r1);
        verify(queue).ack(r2);
        verify(sender).deliverAsync(100L);
        verify(sender).deliverAsync(101L);
    }

    @Test
    void 空批次_无任何投递() {
        when(queue.poll()).thenReturn(List.of());

        consumer.pollAndDeliver();

        verify(sender, never()).deliverAsync(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 非法deliveryId_仍ACK但跳过投递() {
        MapRecord<String, Object, Object> bad = record("abc");
        when(queue.poll()).thenReturn(List.of(bad));
        when(queue.deliveryIdOf(bad)).thenReturn(null);

        consumer.pollAndDeliver();

        verify(queue).ack(bad);
        verify(sender, never()).deliverAsync(org.mockito.ArgumentMatchers.anyLong());
    }
}

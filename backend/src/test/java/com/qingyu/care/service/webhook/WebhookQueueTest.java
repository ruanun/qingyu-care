package com.qingyu.care.service.webhook;

import com.qingyu.care.config.WebhookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Webhook 队列（Redis Stream）单测：入队优先、关闭/故障回退直投、冷却熔断。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WebhookQueueTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock StreamOperations<String, Object, Object> streamOps;
    @Mock WebhookSender sender;

    WebhookProperties properties;
    WebhookQueue queue;

    @BeforeEach
    void setUp() {
        properties = new WebhookProperties();
        queue = new WebhookQueue(redisTemplate, properties, sender);
    }

    private void stubStreamOps() {
        doReturn(streamOps).when(redisTemplate).opsForStream();
    }

    @Test
    void dispatch_入队成功_不直投() {
        stubStreamOps();

        queue.dispatch(100L);

        verify(streamOps).add(any(MapRecord.class));
        verifyNoInteractions(sender);
    }

    @Test
    void dispatch_队列关闭_直接直投不碰Redis() {
        properties.getQueue().setEnabled(false);

        queue.dispatch(100L);

        verifyNoInteractions(redisTemplate);
        verify(sender).deliverAsync(100L);
    }

    @Test
    void dispatch_Redis异常_直投兜底且冷却期内短路() {
        stubStreamOps();
        doThrow(new RuntimeException("connection refused")).when(streamOps).add(any(MapRecord.class));

        queue.dispatch(100L);
        queue.dispatch(101L); // 冷却期内：不再触碰 Redis，直接直投

        verify(streamOps, times(1)).add(any(MapRecord.class));
        verify(sender).deliverAsync(100L);
        verify(sender).deliverAsync(101L);
    }

    @Test
    void ensureGroup_组已存在BUSYGROUP_视为成功继续入队() {
        stubStreamOps();
        doThrow(new RuntimeException("BUSYGROUP Consumer Group name already exists"))
                .when(streamOps).createGroup(any(), any(), any());

        queue.dispatch(100L);

        verify(streamOps).add(any(MapRecord.class));
        verifyNoInteractions(sender);
    }

    @Test
    void poll_Redis异常_返回空并进入冷却() {
        stubStreamOps();
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThat(queue.poll()).isEmpty();
        // 冷却期内二次 poll 不再触碰 Redis
        assertThat(queue.poll()).isEmpty();
        verify(streamOps, times(1)).read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));
    }

    @Test
    void deliveryIdOf_解析消息字段_非法值返回null() {
        MapRecord<String, Object, Object> ok = StreamRecords.newRecord()
                .in("k").ofMap(Map.of(WebhookQueue.FIELD_DELIVERY_ID, "100"));
        MapRecord<String, Object, Object> bad = StreamRecords.newRecord()
                .in("k").ofMap(Map.of(WebhookQueue.FIELD_DELIVERY_ID, "abc"));
        MapRecord<String, Object, Object> missing = StreamRecords.newRecord()
                .in("k").ofMap(Map.of("other", "1"));

        assertThat(queue.deliveryIdOf(ok)).isEqualTo(100L);
        assertThat(queue.deliveryIdOf(bad)).isNull();
        assertThat(queue.deliveryIdOf(missing)).isNull();
    }
}

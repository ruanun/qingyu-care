package com.qingyu.care.service.impl;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单元测试：withStreamPersistence 纯方法（内容透传 + 完整文本仅在正常完成时回调）。
 *
 * @author ruan
 */
class ChatStreamPersistenceTest {

    @Test
    void 流正常完成_以完整聚合文本回调() {
        AtomicReference<String> full = new AtomicReference<>();
        List<String> out = ChatServiceImpl.withStreamPersistence(Flux.just("你", "好"), full::set)
                .collectList().block();
        assertThat(out).containsExactly("你", "好");
        assertThat(full.get()).isEqualTo("你好");
    }

    @Test
    void 流错误_不回调() {
        AtomicReference<String> full = new AtomicReference<>();
        Flux<String> f = ChatServiceImpl.withStreamPersistence(
                Flux.concat(Flux.just("半"), Flux.error(new RuntimeException("模型中断"))), full::set);
        assertThatThrownBy(f::blockLast).hasMessageContaining("模型中断");
        assertThat(full.get()).isNull();
    }

    @Test
    void 空流完成_以空串回调() {
        AtomicReference<String> full = new AtomicReference<>();
        ChatServiceImpl.withStreamPersistence(Flux.empty(), full::set).collectList().block();
        assertThat(full.get()).isEmpty();
    }
}

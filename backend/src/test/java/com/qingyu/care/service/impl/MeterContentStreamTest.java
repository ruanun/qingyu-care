package com.qingyu.care.service.impl;

import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：ChatServiceImpl.meterContentStream() 的计量回调行为。
 *
 * 该方法为 static package-private，位于 impl 包，无 Spring 容器依赖，可直接调用。
 * 核心断言：流结束 → doFinally 回调以末尾 chunk 的 total_tokens 触发 → 记账 tokens 正确。
 *
 * @author ruan
 */
class MeterContentStreamTest {

    /**
     * 正常流：末尾 chunk 携带 usage(totalTokens=99)，回调应以 99 触发；
     * 内容应正确映射（取 getResult().getOutput().getContent()）。
     */
    @Test
    void 正常流_回调携带末尾total_tokens() {
        AtomicInteger captured = new AtomicInteger(-1);

        // 构造 Flux<Spring AI ChatResponse>，totalTokens=99
        Flux<org.springframework.ai.chat.model.ChatResponse> source =
                Flux.just(SpringAiResponses.of("hi", 99));

        List<String> contents = ChatServiceImpl.meterContentStream(source, captured::set)
                .collectList()
                .block();

        assertThat(contents).containsExactly("hi");
        assertThat(captured.get()).isEqualTo(99);
    }

    /**
     * 空流：无任何 chunk，回调应以 0 触发（AtomicInteger 初始值）。
     */
    @Test
    void 空流_回调携带0() {
        AtomicInteger captured = new AtomicInteger(-1);

        Flux<org.springframework.ai.chat.model.ChatResponse> source = Flux.empty();

        List<String> contents = ChatServiceImpl.meterContentStream(source, captured::set)
                .collectList()
                .block();

        assertThat(contents).isEmpty();
        assertThat(captured.get()).isEqualTo(0);
    }

    /**
     * 多 chunk 流：只有末尾 chunk 带 usage，中间 chunk 不含 usage（tokens 保持 0）；
     * 末尾 chunk usage=42，回调应以 42 触发。
     */
    @Test
    void 多chunk流_以末尾usage回调() {
        AtomicInteger captured = new AtomicInteger(-1);

        // 构造两个 chunk：第一个无 usage（text="hello "），第二个带 usage(42)（text="world"）
        org.springframework.ai.chat.model.ChatResponse chunkNoUsage =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage("hello "))));
        org.springframework.ai.chat.model.ChatResponse chunkWithUsage =
                SpringAiResponses.of("world", 42);

        Flux<org.springframework.ai.chat.model.ChatResponse> source =
                Flux.just(chunkNoUsage, chunkWithUsage);

        List<String> contents = ChatServiceImpl.meterContentStream(source, captured::set)
                .collectList()
                .block();

        assertThat(contents).containsExactly("hello ", "world");
        assertThat(captured.get()).isEqualTo(42);
    }

    /**
     * 错误流：流中发生错误，doFinally 仍应触发回调（tokens=0，因无 chunk 携带 usage）。
     */
    @Test
    void 错误流_doFinally仍触发回调() {
        AtomicInteger captured = new AtomicInteger(-1);

        Flux<org.springframework.ai.chat.model.ChatResponse> source =
                Flux.error(new RuntimeException("LLM 调用失败"));

        // 错误流：collectList().block() 会抛异常，用 onErrorReturn 兜底
        List<String> contents = ChatServiceImpl.meterContentStream(source, captured::set)
                .onErrorReturn("__error__")
                .collectList()
                .block();

        // 内容包含错误兜底值（由 onErrorReturn 注入）
        assertThat(contents).containsExactly("__error__");
        // 回调必须已触发（tokens=0，因为没有 chunk 带 usage）
        assertThat(captured.get()).isEqualTo(0);
    }
}

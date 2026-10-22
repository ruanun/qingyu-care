package com.qingyu.care.service;

import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单元测试：流式模型故障回退链（static 纯方法，免容器，同 meterContentStream 惯例）。
 *
 * 核心约束：只有在「首个 chunk 尚未发出」时才允许切换下一个模型——
 * 已经开吐的流无法重放，中途换模型会让用户看到两段互相矛盾的回复。
 *
 * @author ruan
 */
class StreamWithFallbackChainTest {

    private static String textOf(List<ChatResponse> responses) {
        StringBuilder sb = new StringBuilder();
        for (ChatResponse r : responses) {
            sb.append(r.getResult().getOutput().getText());
        }
        return sb.toString();
    }

    @Test
    void 首个模型立即失败时切换下一个() {
        AtomicInteger secondCalled = new AtomicInteger();
        List<Supplier<Flux<ChatResponse>>> attempts = List.of(
                () -> Flux.error(new RuntimeException("模型A挂了")),
                () -> {
                    secondCalled.incrementAndGet();
                    return Flux.just(SpringAiResponses.of("来自B", 10));
                });

        List<ChatResponse> got = ModelRouterService.streamWithFallbackChain(attempts)
                .collectList().block();

        assertThat(textOf(got)).isEqualTo("来自B");
        assertThat(secondCalled.get()).isEqualTo(1);
    }

    @Test
    void 已经吐出内容后失败不再切换而是透传错误() {
        List<Supplier<Flux<ChatResponse>>> attempts = List.of(
                () -> Flux.concat(
                        Flux.just(SpringAiResponses.of("半截", 0)),
                        Flux.error(new RuntimeException("中途断了"))),
                () -> Flux.just(SpringAiResponses.of("不该出现", 10)));

        assertThatThrownBy(() -> ModelRouterService.streamWithFallbackChain(attempts)
                .collectList().block())
                .hasMessageContaining("中途断了");
    }

    @Test
    void 所有模型都失败时抛最后一个错误() {
        List<Supplier<Flux<ChatResponse>>> attempts = List.of(
                () -> Flux.error(new RuntimeException("A挂")),
                () -> Flux.error(new RuntimeException("B挂")));

        assertThatThrownBy(() -> ModelRouterService.streamWithFallbackChain(attempts)
                .collectList().block())
                .hasMessageContaining("B挂");
    }

    @Test
    void 首个模型正常时不触碰后续模型() {
        AtomicInteger secondCalled = new AtomicInteger();
        List<Supplier<Flux<ChatResponse>>> attempts = List.of(
                () -> Flux.just(SpringAiResponses.of("来自A", 5)),
                () -> {
                    secondCalled.incrementAndGet();
                    return Flux.just(SpringAiResponses.of("来自B", 5));
                });

        List<ChatResponse> got = ModelRouterService.streamWithFallbackChain(attempts)
                .collectList().block();

        assertThat(textOf(got)).isEqualTo("来自A");
        assertThat(secondCalled.get()).isZero();
    }
}

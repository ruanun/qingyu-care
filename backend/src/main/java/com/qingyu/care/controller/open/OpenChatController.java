package com.qingyu.care.controller.open;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.open.OpenChatRequest;
import com.qingyu.care.dto.open.OpenChatResponse;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.service.ApiUsageService;
import com.qingyu.care.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 开放对话 API（认证/限流由 OpenApiAuthInterceptor 处理；租户上下文已注入）。
 * completions/stream 两端点均在对话结束后异步记录用量。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/open/v1/chat")
@RequiredArgsConstructor
public class OpenChatController {

    private final ChatService chatService;
    private final ApiUsageService apiUsageService;

    /**
     * 普通对话（同步）。
     * 对话成功后异步累加 call_count/message_count/token_count。
     * POST /open/v1/chat/completions
     */
    @PostMapping("/completions")
    public OpenChatResponse completions(@Valid @RequestBody OpenChatRequest req) {
        // 传了 sessionId 但服务端已不存在/过期 → 显式 404，供 Widget 清本地会话后自动重开
        if (req.getSessionId() != null && !req.getSessionId().isBlank()
                && !chatService.sessionExists(req.getSessionId())) {
            throw new OpenApiException(404, "会话不存在或已过期");
        }
        ChatResponse resp = chatService.chat(toInternal(req, false));
        long tokens = resp.getTokenUsage() != null ? resp.getTokenUsage() : 0L;
        apiUsageService.record(
                TenantContext.getTenantId(),
                TenantContext.getAppId(),
                1L, tokens);
        return OpenChatResponse.from(resp);
    }

    /**
     * 流式对话（SSE）。正文为默认（message）事件、payload 不变；流末尾追加 event:meta
     * 携带落库后的 messageId（旧客户端不识别 meta 事件自动忽略，向后兼容）。
     * 流结束时经计量回调异步记录用量（计量保持开放 API 专属）。
     * POST /open/v1/chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody OpenChatRequest req) {
        // 必须在请求线程（Reactor 订阅前）捕获上下文，回调运行在非 HTTP 线程中无上下文
        final Long tid = TenantContext.getTenantId();
        final Long aid = TenantContext.getAppId();
        // Sinks.one 缓存落库回调的 messageId；正文完成后 concat 出 meta 事件。
        // 回调必达（service 失败时回 -1），filter 挡掉无效 ID → meta Mono 完成为空，SSE 不悬挂
        Sinks.One<Long> messageIdSink = Sinks.one();
        Flux<String> content = chatService.streamChat(toInternal(req, true),
                tokens -> apiUsageService.record(tid, aid, 1L, tokens),
                messageIdSink::tryEmitValue);
        Flux<ServerSentEvent<String>> body = content.map(c -> ServerSentEvent.builder(c).build());
        Mono<ServerSentEvent<String>> meta = messageIdSink.asMono()
                .filter(id -> id > 0)
                .map(id -> ServerSentEvent.<String>builder("{\"messageId\":" + id + "}")
                        .event("meta").build());
        return Flux.concat(body, meta);
    }

    /**
     * 将开放请求映射为内部 ChatRequest
     */
    private ChatRequest toInternal(OpenChatRequest req, boolean stream) {
        return ChatRequest.builder()
                .sessionId(req.getSessionId())
                .userId(req.getUserId())
                .message(req.getMessage())
                .stream(stream)
                .build();
    }
}

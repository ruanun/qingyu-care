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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 开放对话 API（认证/限流由 OpenApiAuthInterceptor 处理；租户上下文已注入）。
 * completions 端点在对话成功后异步记录用量；stream 端点暂不计量。
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
     * 流式对话（SSE）。流结束时经 doFinally 回调异步记录用量（计量保持开放 API 专属）。
     * POST /open/v1/chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody OpenChatRequest req) {
        // 必须在请求线程（Reactor 订阅前）捕获上下文，doFinally 回调运行在非 HTTP 线程中无上下文
        final Long tid = TenantContext.getTenantId();
        final Long aid = TenantContext.getAppId();
        return chatService.streamChat(toInternal(req, true),
                tokens -> apiUsageService.record(tid, aid, 1L, tokens));
    }

    /**
     * 将开放请求映射为内部 ChatRequest
     */
    private ChatRequest toInternal(OpenChatRequest req, boolean stream) {
        return ChatRequest.builder()
                .sessionId(req.getSessionId())
                .userId(req.getUserId())
                .message(req.getMessage())
                .knowledgeBaseId(req.getKnowledgeBaseId())
                .stream(stream)
                .build();
    }
}

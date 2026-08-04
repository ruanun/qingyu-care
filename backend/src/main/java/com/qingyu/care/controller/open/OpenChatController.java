package com.qingyu.care.controller.open;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.open.OpenChatRequest;
import com.qingyu.care.dto.open.OpenChatResponse;
import com.qingyu.care.dto.open.OpenFeedbackRequest;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.service.ApiUsageService;
import com.qingyu.care.service.ChatFeedbackService;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.ChatStreamSseMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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
    private final ChatFeedbackService chatFeedbackService;
    private final ChatStreamSseMapper sseMapper;

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
     * 流式对话（SSE）。事件协议见 docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3：
     * start（会话/转人工建议/知识命中）→ delta|card（正文增量与卡片）→ done（messageId）；失败以 error 收尾。
     * 流结束时经计量回调异步记录用量（计量保持开放 API 专属）；挂了工具触发降级为非流式的这一轮，
     * 首次以 0 token 记账后由 {@code onFallbackTokensUsed} 补记回退实际用掉的 token（见
     * {@link com.qingyu.care.service.impl.ChatServiceImpl#fallbackToNonStreaming}）。
     * POST /open/v1/chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody OpenChatRequest req) {
        // 与 completions 一致：传了 sessionId 但服务端已不存在/过期 → 显式 404，
        // 供 Widget 清本地会话后自动重开（改造前流式端点漏了这一步）
        if (req.getSessionId() != null && !req.getSessionId().isBlank()
                && !chatService.sessionExists(req.getSessionId())) {
            throw new OpenApiException(404, "会话不存在或已过期");
        }
        // 必须在请求线程（Reactor 订阅前）捕获上下文，回调运行在非 HTTP 线程中无上下文
        final Long tid = TenantContext.getTenantId();
        final Long aid = TenantContext.getAppId();
        return chatService.streamChat(toInternal(req, true),
                        tokens -> apiUsageService.record(tid, aid, 1L, tokens),
                        tokens -> apiUsageService.recordFallbackTokens(aid, tokens))
                .map(sseMapper::toSse);
    }

    /**
     * 消息反馈（赞/踩，重复提交覆盖）。POST /open/v1/chat/feedback
     */
    @PostMapping("/feedback")
    public java.util.Map<String, Object> feedback(@Valid @RequestBody OpenFeedbackRequest req) {
        chatFeedbackService.submitFeedback(req.getSessionId(), req.getMessageId(), req.getFeedback());
        return java.util.Map.of("success", true);
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
                .openChannel(true)
                .build();
    }
}

package com.qingyu.care.controller.open;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.open.OpenChatRequest;
import com.qingyu.care.dto.open.OpenChatResponse;
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
 * 注：用量计量将在 Task 11 接入，本版本不含计量逻辑。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/open/v1/chat")
@RequiredArgsConstructor
public class OpenChatController {

    private final ChatService chatService;

    /**
     * 普通对话（同步）
     * POST /open/v1/chat/completions
     */
    @PostMapping("/completions")
    public OpenChatResponse completions(@Valid @RequestBody OpenChatRequest req) {
        ChatResponse resp = chatService.chat(toInternal(req, false));
        return OpenChatResponse.from(resp);
    }

    /**
     * 流式对话（SSE）
     * POST /open/v1/chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody OpenChatRequest req) {
        return chatService.streamChat(toInternal(req, true));
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

package com.qingyu.care.controller.open;

import com.qingyu.care.dto.open.OpenSessionResponse;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 开放会话 API：查询会话 + 请求转人工。
 * 会话归属隔离：ChatService 内部经 TenantContext（拦截器已按应用注入）过滤，跨应用会话不可见。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/open/v1/sessions")
@RequiredArgsConstructor
public class OpenSessionController {

    private final ChatService chatService;

    /**
     * 查询会话详情（含消息历史）
     * GET /open/v1/sessions/{sessionId}
     */
    @GetMapping("/{sessionId}")
    public OpenSessionResponse getSession(@PathVariable String sessionId) {
        ChatSession session = chatService.getSession(sessionId);
        return OpenSessionResponse.of(session, chatService.getSessionMessages(sessionId));
    }

    /**
     * 请求转人工
     * POST /open/v1/sessions/{sessionId}/handover
     */
    @PostMapping("/{sessionId}/handover")
    public Map<String, Object> handover(@PathVariable String sessionId) {
        chatService.requestHandover(sessionId);
        return Map.of("sessionId", sessionId, "status", "handover_requested");
    }
}

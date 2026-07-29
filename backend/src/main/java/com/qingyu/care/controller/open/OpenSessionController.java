package com.qingyu.care.controller.open;

import com.qingyu.care.dto.open.OpenSessionResponse;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.service.realtime.RealtimeKeys;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 开放会话 API：查询会话 + 请求转人工 + 用户端实时通道（inbox / typing）。
 * 会话归属隔离：ChatService 内部经 TenantContext（拦截器已按应用注入）过滤，跨应用会话不可见。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/open/v1/sessions")
@RequiredArgsConstructor
public class OpenSessionController {

    private final ChatService chatService;
    private final RealtimeHub realtimeHub;
    private final RealtimeNotifier realtimeNotifier;
    private final ObjectMapper objectMapper;

    /**
     * 查询会话详情（含消息历史）
     * GET /open/v1/sessions/{sessionId}
     */
    @GetMapping("/{sessionId}")
    public OpenSessionResponse getSession(@PathVariable String sessionId) {
        ChatSession session = chatService.getSession(sessionId);
        return OpenSessionResponse.of(session, chatService.getSessionMessages(sessionId), objectMapper);
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

    /**
     * 用户端实时收件箱（SSE）：消息 / 会话状态 / typing / presence 事件。
     * 鉴权由 OpenApiAuthInterceptor 完成（appId + Origin/appKey）；订阅该会话 inbox。
     * GET /open/v1/sessions/{sessionId}/inbox
     */
    @GetMapping(value = "/{sessionId}/inbox", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> inbox(@PathVariable String sessionId) {
        chatService.getSession(sessionId); // 归属校验：越权/不存在抛 404，阻止跨租户订阅
        return realtimeHub.subscribe(RealtimeKeys.session(sessionId));
    }

    /**
     * 用户正在输入：推给该会话当前接管坐席（未接管 / 无 agentId 则不推）。
     * POST /open/v1/sessions/{sessionId}/typing
     */
    @PostMapping("/{sessionId}/typing")
    public Map<String, Object> userTyping(@PathVariable String sessionId) {
        ChatSession s = chatService.getSession(sessionId);
        if (s.getAgentId() != null) {
            realtimeNotifier.typing(
                    RealtimeKeys.agent(s.getTenantId(), s.getAgentId()),
                    sessionId, "user");
        }
        return Map.of("success", true);
    }
}

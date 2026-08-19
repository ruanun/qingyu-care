package com.qingyu.care.controller.open;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.AttachmentDTO;
import com.qingyu.care.dto.ReadReceiptRequest;
import com.qingyu.care.dto.open.OpenSessionResponse;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.ReadReceiptService;
import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.service.realtime.RealtimeKeys;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import jakarta.validation.Valid;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 开放会话 API：查询会话 + 请求转人工 + 用户端实时通道（inbox / typing）+ 已读上报。
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
    private final ChatAttachmentRepository chatAttachmentRepository;
    private final ReadReceiptService readReceiptService;

    /**
     * 查询会话详情（含消息历史 + 每条消息的附件 + 坐席的已读水位线）。
     * 附件按 messageId 一次性批量查出分组塞回去（同 {@code ChatController#getSessionMessages}
     * 的做法），不在循环里逐条查，避免 N+1。
     * <p>agentReadUpToMessageId 只带对方（坐席）的水位线，不带访客自己的——见
     * {@link OpenSessionResponse#getAgentReadUpToMessageId()}。会话尚无坐席接管
     * （{@code agentId} 为 null）时不查、直接为 null。
     * GET /open/v1/sessions/{sessionId}
     */
    @GetMapping("/{sessionId}")
    public OpenSessionResponse getSession(@PathVariable String sessionId) {
        ChatSession session = chatService.getSession(sessionId);
        List<ChatMessage> messages = chatService.getSessionMessages(sessionId);
        List<Long> messageIds = messages.stream().map(ChatMessage::getId).filter(java.util.Objects::nonNull).toList();
        Map<Long, List<AttachmentDTO>> attachmentsByMessageId = messageIds.isEmpty()
                ? Map.of()
                : chatAttachmentRepository.findByMessageIdInOrderById(messageIds).stream()
                        .collect(Collectors.groupingBy(ChatAttachment::getMessageId,
                                Collectors.mapping(AttachmentDTO::of, Collectors.toList())));
        Long agentReadUpToMessageId = session.getAgentId() != null
                ? readReceiptService.currentWatermark(session.getId(), ReaderType.AGENT, session.getAgentId())
                : null;
        return OpenSessionResponse.of(session, messages, objectMapper, attachmentsByMessageId,
                agentReadUpToMessageId);
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
     * 访客上报已读。开放 API 的身份是「这个 app」，service 层额外校验会话属于当前 app；
     * {@code sessionId}(UUID) 本身即第二重能力凭证，与附件下载端点要求 {@code ?sessionId=}
     * 同一定级。
     * POST /open/v1/sessions/{sessionId}/read
     */
    @PostMapping("/{sessionId}/read")
    public ApiResponse<Long> markVisitorRead(@PathVariable String sessionId,
                                              @Valid @RequestBody ReadReceiptRequest request) {
        // ⚠️ @Valid 不可省：ReadReceiptRequest.upToMessageId 的 @NotNull 靠它才生效，
        // 缺了它 null 会一路传进 service 撞 NPE，对客户端表现为 500 而非语义正确的 400。
        return ApiResponse.success(chatService.markVisitorRead(sessionId, request.getUpToMessageId()));
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

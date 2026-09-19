package com.qingyu.care.controller.open;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.controller.support.AttachmentResponses;
import com.qingyu.care.dto.AttachmentDTO;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.open.OpenChatRequest;
import com.qingyu.care.dto.open.OpenChatResponse;
import com.qingyu.care.dto.open.OpenFeedbackRequest;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.service.ApiUsageService;
import com.qingyu.care.service.ChatFeedbackService;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.ChatStreamSseMapper;
import com.qingyu.care.service.FileService;
import com.qingyu.care.service.media.ChatAttachmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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
    private final ChatAttachmentService chatAttachmentService;
    private final FileService fileService;

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
     * 流式对话（SSE）。事件协议：
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

    // ===== 附件（多媒体消息）=====

    /** Widget 用户上传附件 */
    @PostMapping("/files")
    public AttachmentDTO uploadAttachment(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sessionId") String sessionId) {
        ChatAttachment a = chatAttachmentService.upload(sessionId, file, MessageRole.USER);
        return AttachmentDTO.of(a);
    }

    /**
     * 读取附件，同管理端一样经私有桶代理下发。{@code getForRead} 不做归属校验，交给这里补上。
     *
     * <p>⚠️ 三道校验缺一不可：
     * <ol>
     *   <li>{@code sessionId} 非空白——{@code chat_attachment.session_id} 是 NOT NULL，
     *       上传端已拒绝空白 sessionId（见 {@code ChatAttachmentService#requireOwnedSession}），
     *       但历史脏数据/修复前遗留的空白会话附件仍可能存在，这里纵深防御一道，防止靠
     *       {@code ?sessionId=} 空值命中这批"所有人共享"的孤儿；
     *   <li>{@link TenantOwnershipGuard#mutableInCurrentContext}——用写语义而非读语义的
     *       {@code belongsToCurrentContext}：后者对 NULL 租户无条件放行（配置类实体那是有意
     *       为之的共享回退语义，附件没有这层含义）；
     *   <li>{@code sessionId} 必须与附件所属会话一致——开放 API 的身份粒度是「整个 app」，
     *       只查 tenant/app 归属堵不住同一 app 内部的越权：appKey 本身是公开信息（Widget 明写
     *       在页面源码里），{@code ChatAttachment.id} 又是全局自增整数，攻击者持公开 appKey
     *       逐 id 枚举即可读到该 app 下所有访客上传的文件（含身份证、发票等）。要求调用方带上
     *       自己会话的 sessionId 作为第二重能力凭证，把身份粒度从「app」收紧到「session」，
     *       与 {@code OpenSessionController} 用 sessionId 作访问凭证是同一模式。
     * </ol>
     * 任一条不满足都按「不存在」同语义处理，防枚举探测。
     */
    @GetMapping("/files/{id}")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable Long id, @RequestParam String sessionId) {
        ChatAttachment a = chatAttachmentService.getForRead(id);
        if (sessionId.isBlank()
                || !TenantOwnershipGuard.mutableInCurrentContext(a.getTenantId(), a.getAppId())
                || !sessionId.equals(a.getSessionId())) {
            throw new ResourceNotFoundException("附件不存在: " + id);
        }
        return AttachmentResponses.stream(a, fileService);
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
                .attachmentIds(req.getAttachmentIds())
                .build();
    }
}

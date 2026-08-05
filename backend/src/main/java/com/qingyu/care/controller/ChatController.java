package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.controller.support.AttachmentResponses;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.AgentReplyRequest;
import com.qingyu.care.dto.AttachmentDTO;
import com.qingyu.care.dto.ChatMessageDTO;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.PageDTO;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.SessionTag;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.service.ChatFeedbackService;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.ChatStreamSseMapper;
import com.qingyu.care.service.FileService;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天控制器
 */
@RestController
@RequestMapping("/v1/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "AI智能问答接口")
public class ChatController {

    private final ChatService chatService;
    private final ChatFeedbackService chatFeedbackService;
    private final ChatStreamSseMapper sseMapper;
    private final ChatAttachmentService chatAttachmentService;
    private final ChatAttachmentRepository chatAttachmentRepository;
    private final FileService fileService;

    @PostMapping
    @Operation(summary = "发送消息", description = "发送消息并获取AI回复")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        request.setOpenChannel(false);
        ChatResponse response = chatService.chat(request);
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式聊天", description = "SSE 事件流：start/delta/card/done/error")
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ChatRequest request) {
        request.setOpenChannel(false);
        // 管理后台不计量（token 计量是开放 API 专属）
        return chatService.streamChat(request).map(sseMapper::toSse);
    }
    
    @GetMapping("/session/{sessionId}")
    @Operation(summary = "获取会话信息", description = "获取指定会话的详细信息")
    public ApiResponse<ChatSession> getSession(@PathVariable String sessionId) {
        ChatSession session = chatService.getSession(sessionId);
        return ApiResponse.success(session);
    }
    
    @PostMapping("/session/{sessionId}/end")
    @Operation(summary = "结束会话", description = "结束指定的会话")
    public ApiResponse<Void> endSession(@PathVariable String sessionId) {
        chatService.endSession(sessionId);
        return ApiResponse.success("会话已结束", null);
    }
    
    @PostMapping("/session/{sessionId}/handover")
    @Operation(summary = "请求人工接管", description = "请求人工客服接管会话")
    public ApiResponse<Void> requestHandover(@PathVariable String sessionId) {
        chatService.requestHandover(sessionId);
        return ApiResponse.success("已请求人工接管", null);
    }
    
    // ===== 新增端点 =====
    
    @GetMapping("/sessions")
    @Operation(summary = "获取所有会话列表", description = "分页获取所有会话，可按状态过滤")
    public ApiResponse<PageDTO<ChatSession>> getAllSessions(
            @RequestParam(required = false) SessionStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ChatSession> sessions = chatService.getAllSessions(status, pageable);
        return ApiResponse.success(PageDTO.from(sessions));
    }
    
    @GetMapping("/sessions/user/{userId}")
    @Operation(summary = "获取用户历史会话", description = "分页获取指定用户的历史会话列表")
    public ApiResponse<PageDTO<ChatSession>> getUserSessions(
            @PathVariable String userId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ChatSession> sessions = chatService.getUserSessions(userId, pageable);
        return ApiResponse.success(PageDTO.from(sessions));
    }
    
    @PostMapping("/session/{sessionId}/tag")
    @Operation(summary = "添加会话标签", description = "为会话添加标签（支持多标签）")
    public ApiResponse<ChatSession> addSessionTag(
            @PathVariable String sessionId,
            @RequestParam SessionTag tag) {
        ChatSession session = chatService.addSessionTag(sessionId, tag);
        return ApiResponse.success("标签已添加", session);
    }

    @DeleteMapping("/session/{sessionId}/tag")
    @Operation(summary = "移除会话标签", description = "从会话移除标签")
    public ApiResponse<ChatSession> removeSessionTag(
            @PathVariable String sessionId,
            @RequestParam SessionTag tag) {
        ChatSession session = chatService.removeSessionTag(sessionId, tag);
        return ApiResponse.success("标签已移除", session);
    }
    
    @PutMapping("/session/{sessionId}/summary")
    @Operation(summary = "更新会话摘要", description = "更新会话的摘要内容")
    public ApiResponse<ChatSession> updateSessionSummary(
            @PathVariable String sessionId,
            @RequestBody String summary) {
        ChatSession session = chatService.updateSessionSummary(sessionId, summary);
        return ApiResponse.success("会话摘要已更新", session);
    }
    
    // ===== 人工客服端点 =====
    
    @PostMapping("/session/{sessionId}/accept")
    @Operation(summary = "接管会话", description = "客服接管等待中的会话")
    public ApiResponse<ChatSession> acceptSession(@PathVariable String sessionId) {
        SecurityUtil.requireAgent();
        Long agentId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        ChatSession session = chatService.acceptSession(sessionId, agentId);
        return ApiResponse.success("会话已接管", session);
    }

    @PostMapping("/agent/reply")
    @Operation(summary = "客服回复", description = "客服发送消息（绕过AI）")
    public ApiResponse<Void> agentReply(@Valid @RequestBody AgentReplyRequest request) {
        SecurityUtil.requireAgent();
        Long agentId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        chatService.agentReply(request.getSessionId(), request.getMessage(), agentId,
                request.getAttachmentIds());
        return ApiResponse.success("消息已发送", null);
    }

    @GetMapping("/sessions/waiting")
    @Operation(summary = "获取待接管会话", description = "获取所有等待人工接管的会话")
    public ApiResponse<PageDTO<ChatSession>> getWaitingAgentSessions(
            @PageableDefault(size = 20) Pageable pageable) {
        SecurityUtil.requireAgent();
        Page<ChatSession> sessions = chatService.getWaitingAgentSessions(pageable);
        return ApiResponse.success(PageDTO.from(sessions));
    }

    @GetMapping("/session/{sessionId}/messages")
    @Operation(summary = "获取会话消息历史", description = "获取指定会话的所有消息")
    public ApiResponse<List<ChatMessageDTO>> getSessionMessages(
            @PathVariable String sessionId) {
        SecurityUtil.requireAgent();
        List<ChatMessage> messages = chatService.getSessionMessages(sessionId);
        List<Long> messageIds = messages.stream().map(ChatMessage::getId).toList();
        // 一次查完全部消息的附件，按 messageId 分组塞回去——不在循环里逐条查，避免 N+1
        Map<Long, List<AttachmentDTO>> attachmentsByMessageId = messageIds.isEmpty()
                ? Map.of()
                : chatAttachmentRepository.findByMessageIdInOrderById(messageIds).stream()
                        .collect(Collectors.groupingBy(ChatAttachment::getMessageId,
                                Collectors.mapping(AttachmentDTO::of, Collectors.toList())));
        List<ChatMessageDTO> dtos = messages.stream()
                .map(m -> ChatMessageDTO.of(m, attachmentsByMessageId.getOrDefault(m.getId(), List.of())))
                .toList();
        return ApiResponse.success(dtos);
    }

    @PostMapping("/session/{sessionId}/return-to-ai")
    @Operation(summary = "交还 AI", description = "坐席把会话交还 AI 续答")
    public ApiResponse<Void> returnToAI(@PathVariable String sessionId) {
        SecurityUtil.requireAgent();
        chatService.returnToAI(sessionId);
        return ApiResponse.success("已交还 AI", null);
    }

    @PostMapping("/session/{sessionId}/message/{messageId}/feedback")
    @Operation(summary = "评价消息", description = "对 AI 回复打赞/踩（feedback ∈ {1,-1}，重复提交覆盖）")
    public ApiResponse<Void> feedback(
            @PathVariable String sessionId,
            @PathVariable Long messageId,
            @RequestParam int feedback) {
        chatFeedbackService.submitFeedback(sessionId, messageId, feedback);
        return ApiResponse.success("反馈已记录", null);
    }

    // ===== 附件（多媒体消息）=====

    /**
     * 管理端/坐席上传附件。上传与发消息分两步，这里只落库拿 id。
     *
     * <p>{@code file} 排在 {@code sessionId} 前——{@code AuditLogAspect#extractTargetId}
     * 约定取方法首参当 targetId（仅当其为 Long/String），若 sessionId 排首位会被误记成
     * "ChatAttachment 的 targetId"，实际是会话标识不是附件 id；MultipartFile 不落这条
     * 约定的判断分支，天然落回「create 类操作 targetId 为 null」这一既有惯例
     * （同 {@code AbTestController#create}）。sessionId 仍完整保留在 detail 里（记录全部入参）。
     */
    @PostMapping("/files")
    @AuditLog(action = "CHAT_ATTACHMENT_UPLOAD", targetType = "ChatAttachment")
    @Operation(summary = "上传附件", description = "上传图片/文件，返回附件 id；发消息时再关联")
    public ApiResponse<AttachmentDTO> uploadAttachment(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sessionId") String sessionId) {
        ChatAttachment a = chatAttachmentService.upload(sessionId, file, MessageRole.AGENT);
        return ApiResponse.success(AttachmentDTO.of(a));
    }

    /**
     * 读取附件。桶是私有的，客户端拿不到也不该拿到裸 S3 地址，一律经此代理。
     *
     * <p>图片 inline 供内嵌预览，其余一律 attachment 强制下载——不给浏览器渲染的机会。
     *
     * <p>{@code getForRead} 本身不做归属校验（两条访问端点鉴权路径不同，交给调用方），
     * 这里补上——findById 不受 Hibernate {@code @Filter} 保护，漏了这一步就是对全库附件的跨租户 IDOR。
     * 用写语义的 {@link TenantOwnershipGuard#mutableInCurrentContext} 而非读语义的
     * {@code belongsToCurrentContext}：后者对 NULL 租户（如平台管理员上传、未走 tenant 上下文
     * 落库的行）无条件放行——那对配置类实体是有意为之（NULL=平台默认=有意共享回退），但对附件
     * 不成立（NULL 只是没有归属，从无共享意图），会让平台管理员的文件对任意租户可读。
     */
    @GetMapping("/files/{id}")
    @Operation(summary = "下载附件", description = "私有桶代理下载，图片 inline 预览，其余强制下载")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(@PathVariable Long id) {
        ChatAttachment a = chatAttachmentService.getForRead(id);
        if (!TenantOwnershipGuard.mutableInCurrentContext(a.getTenantId(), a.getAppId())) {
            throw new ResourceNotFoundException("附件不存在: " + id);
        }
        return AttachmentResponses.stream(a, fileService);
    }
}


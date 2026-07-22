package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.AgentReplyRequest;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.PageDTO;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.SessionTag;
import com.qingyu.care.service.ChatFeedbackService;
import com.qingyu.care.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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

    @PostMapping
    @Operation(summary = "发送消息", description = "发送消息并获取AI回复")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        request.setOpenChannel(false);
        ChatResponse response = chatService.chat(request);
        return ApiResponse.success(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式聊天", description = "发送消息并以流式方式获取AI回复")
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request) {
        request.setOpenChannel(false);
        return chatService.streamChat(request);
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
    public ApiResponse<ChatSession> acceptSession(
            @PathVariable String sessionId,
            @RequestParam Long agentId) {
        ChatSession session = chatService.acceptSession(sessionId, agentId);
        return ApiResponse.success("会话已接管", session);
    }
    
    @PostMapping("/agent/reply")
    @Operation(summary = "客服回复", description = "客服发送消息（绕过AI）")
    public ApiResponse<Void> agentReply(@Valid @RequestBody AgentReplyRequest request) {
        chatService.agentReply(request.getSessionId(), request.getMessage(), request.getAgentId());
        return ApiResponse.success("消息已发送", null);
    }
    
    @GetMapping("/sessions/waiting")
    @Operation(summary = "获取待接管会话", description = "获取所有等待人工接管的会话")
    public ApiResponse<PageDTO<ChatSession>> getWaitingAgentSessions(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ChatSession> sessions = chatService.getWaitingAgentSessions(pageable);
        return ApiResponse.success(PageDTO.from(sessions));
    }
    
    @GetMapping("/session/{sessionId}/messages")
    @Operation(summary = "获取会话消息历史", description = "获取指定会话的所有消息")
    public ApiResponse<java.util.List<ChatMessage>> getSessionMessages(
            @PathVariable String sessionId) {
        return ApiResponse.success(chatService.getSessionMessages(sessionId));
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
}


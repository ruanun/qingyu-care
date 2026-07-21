package com.qingyu.care.service;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.SessionTag;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天服务接口
 */
public interface ChatService {
    
    /**
     * 发送消息并获取AI回复
     */
    ChatResponse chat(ChatRequest request);
    
    /**
     * 流式聊天（内部调用，不计量）
     */
    Flux<String> streamChat(ChatRequest request);

    /**
     * 流式聊天，流正常/异常结束时以本次 total_tokens 回调（供开放 API 计量；内部调用用无回调重载）。
     */
    reactor.core.publisher.Flux<String> streamChat(com.qingyu.care.dto.ChatRequest request,
                                                   java.util.function.IntConsumer onTokensUsed);

    /**
     * 流式聊天（计量 + 落库回调）。onAiMessageSaved 在流正常完成、消息落库后以 AI 消息 ID 回调；
     * 落库失败或空回复以 -1 回调（回调必达，SSE meta 事件不悬挂）。error/cancel 不落库不回调。
     */
    reactor.core.publisher.Flux<String> streamChat(com.qingyu.care.dto.ChatRequest request,
                                                   java.util.function.IntConsumer onTokensUsed,
                                                   java.util.function.LongConsumer onAiMessageSaved);

    /**
     * 获取会话信息
     */
    ChatSession getSession(String sessionId);

    /**
     * 判断会话是否存在（按当前上下文可见）。开放 API 用于对失效 sessionId 显式报错。
     */
    boolean sessionExists(String sessionId);
    
    /**
     * 结束会话
     */
    void endSession(String sessionId);
    
    /**
     * 请求人工接管
     */
    void requestHandover(String sessionId);

    /**
     * 知识检索：按当前租户上下文 + 激活的 default 场景策略，检索相关知识（混合检索开关由策略
     * hybridEnabled 决定，null 视为开启）。fail-soft：任何异常均返回空列表，不阻断对话。
     * chat()/streamChat() 内部复用同一实现（传入已取的 strategy，避免重复查询）；
     * 本方法额外暴露给外部（如集成测试）按 query 直接验证检索链路。
     */
    List<Document> searchKnowledge(String query);

    // ===== 新增方法 =====
    
    /**
     * 获取用户的历史会话列表（分页）
     */
    Page<ChatSession> getUserSessions(String userId, Pageable pageable);
    
    /**
     * 获取所有会话列表（分页，带状态过滤）
     */
    Page<ChatSession> getAllSessions(SessionStatus status, Pageable pageable);
    
    /**
     * 添加会话标签
     */
    ChatSession addSessionTag(String sessionId, SessionTag tag);

    /**
     * 移除会话标签
     */
    ChatSession removeSessionTag(String sessionId, SessionTag tag);
    
    /**
     * 更新会话摘要
     */
    ChatSession updateSessionSummary(String sessionId, String summary);
    
    // ===== 人工客服相关 =====
    
    /**
     * 客服接管会话
     */
    ChatSession acceptSession(String sessionId, Long agentId);
    
    /**
     * 客服发送消息（绕过 AI）
     */
    void agentReply(String sessionId, String message, Long agentId);
    
    /**
     * 获取待接管会话列表
     */
    Page<ChatSession> getWaitingAgentSessions(Pageable pageable);
    
    /**
     * 获取会话消息历史
     */
    List<ChatMessage> getSessionMessages(String sessionId);
}


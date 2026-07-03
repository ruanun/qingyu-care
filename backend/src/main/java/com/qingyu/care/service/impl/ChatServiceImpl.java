package com.qingyu.care.service.impl;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import com.qingyu.care.event.WebhookDomainEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {
    
    private final VectorStore vectorStore;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final PromptTemplateService promptTemplateService;
    private final HandoverRuleService handoverRuleService;
    private final ModelRouterService modelRouterService;
    private final ChatStrategyService chatStrategyService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public ChatResponse chat(ChatRequest request) {
        // 获取或创建会话
        ChatSession session = getOrCreateSession(request);
        
        // AI 静默模式：当会话由人工处理时，不调用 AI
        if (session.getStatus() == SessionStatus.AGENT_PROCESSING || 
            session.getStatus() == SessionStatus.WAITING_AGENT) {
            // 仅保存用户消息，不调用 AI
            ChatMessage userMsg = ChatMessage.builder()
                    .session(session)
                    .role(MessageRole.USER)
                    .content(request.getMessage())
                    .build();
            messageRepository.save(userMsg);
            
            return ChatResponse.builder()
                    .sessionId(session.getSessionId())
                    .content("[会话已转接人工客服，请等待回复]")
                    .confidenceScore(0.0)
                    .suggestHandover(false)
                    .knowledgeHits(List.of())
                    .build();
        }
        
        // 检查关键词触发转人工
        if (handoverRuleService.checkKeywordTrigger(request.getMessage())) {
            session.setStatus(SessionStatus.WAITING_AGENT);
            sessionRepository.save(session);
            log.info("关键词触发转人工: sessionId={}", session.getSessionId());

            eventPublisher.publishEvent(new WebhookDomainEvent(
                    "session.handover_requested", session.getAppId(), session.getTenantId(),
                    session.getSessionId(), "keyword", LocalDateTime.now()));

            // 保存用户消息
            ChatMessage userMsg = ChatMessage.builder()
                    .session(session)
                    .role(MessageRole.USER)
                    .content(request.getMessage())
                    .build();
            messageRepository.save(userMsg);
            
            return ChatResponse.builder()
                    .sessionId(session.getSessionId())
                    .content("正在为您转接人工客服，请稍候...")
                    .confidenceScore(0.0)
                    .suggestHandover(true)
                    .knowledgeHits(List.of())
                    .build();
        }
        
        // 获取策略配置（默认为 default，未来可扩充根据请求场景选择）
        ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");
        
        // 检索相关知识
        List<Document> relevantDocs = searchKnowledge(request.getMessage(), strategy.getTopK(), strategy.getSimilarityThreshold());
        
        // 计算置信度
        double maxScore = relevantDocs.isEmpty() ? 0.0 : 
            relevantDocs.stream()
                .mapToDouble(doc -> (Double) doc.getMetadata().getOrDefault("score", 0.0))
                .max()
                .orElse(0.0);
        
        // 如果知识库未命中且不允许自由发挥
        if (relevantDocs.isEmpty() && Boolean.FALSE.equals(strategy.getAllowNoKnowledge())) {
            return ChatResponse.builder()
                    .sessionId(session.getSessionId())
                    .content(strategy.getFallbackMessage())
                    .confidenceScore(0.0)
                    .suggestHandover(false)
                    .knowledgeHits(List.of())
                    .build();
        }
        
        // 构建上下文
        String context = buildContext(relevantDocs);
        
        // 构建消息历史
        List<Message> messages = buildMessages(session, request.getMessage(), context);
        
        // 调用AI（使用动态模型路由）
        final List<Message> finalMessages = messages;
        String response = modelRouterService.callWithFallback("default", client -> 
            client.prompt()
                .messages(finalMessages)
                .call()
                .content()
        );
        
        // 保存消息
        saveMessages(session, request.getMessage(), response, maxScore);
        
        // 构建响应
        return ChatResponse.builder()
                .sessionId(session.getSessionId())
                .content(response)
                .confidenceScore(maxScore)
                .suggestHandover(handoverRuleService.checkLowConfidenceTrigger(maxScore))
                .knowledgeHits(relevantDocs.stream()
                        .map(doc -> ChatResponse.KnowledgeHit.builder()
                                .content(truncateContent(doc.getContent(), 100))
                                .score((Double) doc.getMetadata().getOrDefault("score", 0.0))
                                .source((String) doc.getMetadata().getOrDefault("source", "unknown"))
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        // 流式响应实现
        ChatSession session = getOrCreateSession(request);
        ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");
        List<Document> relevantDocs = searchKnowledge(request.getMessage(), strategy.getTopK(), strategy.getSimilarityThreshold());
        String context = buildContext(relevantDocs);
        List<Message> messages = buildMessages(session, request.getMessage(), context);
        
        ChatClient chatClient = modelRouterService.getDefaultChatClient();
        return chatClient.prompt()
                .messages(messages)
                .stream()
                .content();
    }

    @Override
    public ChatSession getSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("会话", sessionId));
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return sessionId != null && sessionRepository.findBySessionId(sessionId).isPresent();
    }

    @Override
    @Transactional
    public void endSession(String sessionId) {
        ChatSession session = getSession(sessionId);
        session.setStatus(SessionStatus.ENDED);
        session.setEndedAt(LocalDateTime.now());
        sessionRepository.save(session);
        log.info("Session {} ended", sessionId);

        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.ended", session.getAppId(), session.getTenantId(),
                session.getSessionId(), null, LocalDateTime.now()));

        // 异步生成摘要
        generateSummaryAsync(session);
    }

    /**
     * 异步生成会话摘要
     */
    private void generateSummaryAsync(ChatSession session) {
        try {
            List<ChatMessage> messages = messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(session.getId());
            if (messages.isEmpty()) {
                return;
            }
            
            // 构建消息文本
            StringBuilder sb = new StringBuilder();
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                String role = msg.getRole() == MessageRole.USER ? "用户" : "客服";
                sb.append(role).append(": ").append(msg.getContent()).append("\n");
            }
            
            // 调用 AI 生成摘要（使用动态模型）
            String prompt = "请用一句简短的话总结以下客服对话的主要内容和用户需求（不超过50字）：\n\n" + sb.toString();
            
            String summary = modelRouterService.callWithFallback("default", client -> 
                client.prompt()
                    .user(prompt)
                    .call()
                    .content()
            );
            
            // 保存摘要
            session.setSummary(summary);
            sessionRepository.save(session);
            log.info("Generated summary for session {}: {}", session.getSessionId(), summary);
        } catch (Exception e) {
            log.error("Failed to generate summary for session {}: {}", session.getSessionId(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public void requestHandover(String sessionId) {
        ChatSession session = getSession(sessionId);
        session.setStatus(SessionStatus.WAITING_AGENT);
        sessionRepository.save(session);
        log.info("Session {} is now waiting for agent handover", sessionId);

        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.handover_requested", session.getAppId(), session.getTenantId(),
                session.getSessionId(), "user_request", LocalDateTime.now()));
    }
    
    private ChatSession getOrCreateSession(ChatRequest request) {
        if (request.getSessionId() != null) {
            return sessionRepository.findBySessionId(request.getSessionId())
                    .orElseGet(() -> createNewSession(request));
        }
        return createNewSession(request);
    }
    
    private ChatSession createNewSession(ChatRequest request) {
        ChatSession session = ChatSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .status(SessionStatus.AI_PROCESSING)
                .build();
        session = sessionRepository.save(session);
        log.info("Session {} created for user {}", session.getSessionId(), session.getUserId());
        return session;
    }
    
    private List<Document> searchKnowledge(String query, int topK, double threshold) {
        try {
            Long tenantId = com.qingyu.care.config.TenantContext.getTenantId();
            
            SearchRequest searchRequest;
            if (tenantId != null) {
                // 有租户上下文时，添加租户过滤条件
                searchRequest = SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(threshold)
                        .withFilterExpression("tenantId == '" + tenantId + "'");
                log.debug("Searching knowledge with tenantId filter: {}", tenantId);
            } else {
                // 无租户上下文时（如平台管理员），搜索所有
                searchRequest = SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(threshold);
            }
            return vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.warn("Knowledge search failed: {}", e.getMessage());
            return List.of();
        }
    }
    
    private String buildContext(List<Document> docs) {
        if (docs.isEmpty()) {
            return "暂无相关知识库内容。";
        }
        return docs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));
    }
    
    private List<Message> buildMessages(ChatSession session, String userMessage, String context) {
        List<Message> messages = new ArrayList<>();
        
        // 系统消息 - 从数据库获取激活的 Prompt 模板
        String systemTemplate = promptTemplateService.getActiveTemplateContent();
        String systemPrompt = systemTemplate.replace("{context}", context);
        messages.add(new SystemMessage(systemPrompt));
        
        // 历史消息
        List<ChatMessage> history = messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(session.getId());
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (msg.getRole() == MessageRole.USER) {
                messages.add(new UserMessage(msg.getContent()));
            } else if (msg.getRole() == MessageRole.ASSISTANT) {
                messages.add(new AssistantMessage(msg.getContent()));
            }
        }
        
        // 当前用户消息
        messages.add(new UserMessage(userMessage));
        
        return messages;
    }
    
    private void saveMessages(ChatSession session, String userMessage, String aiResponse, double confidence) {
        // 保存用户消息
        ChatMessage userMsg = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(userMessage)
                .build();
        messageRepository.save(userMsg);
        
        // 保存AI回复
        ChatMessage aiMsg = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(aiResponse)
                .confidenceScore(confidence)
                .build();
        messageRepository.save(aiMsg);
    }
    
    /**
     * 截断内容，避免返回过长的文本
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength) + "...";
    }
    
    // ===== 新增方法实现 =====
    
    @Override
    public org.springframework.data.domain.Page<ChatSession> getUserSessions(String userId, org.springframework.data.domain.Pageable pageable) {
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }
    
    @Override
    public org.springframework.data.domain.Page<ChatSession> getAllSessions(com.qingyu.care.enums.SessionStatus status, org.springframework.data.domain.Pageable pageable) {
        if (status == null) {
            return sessionRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return sessionRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
    }
    
    @Override
    @Transactional
    public ChatSession addSessionTag(String sessionId, com.qingyu.care.enums.SessionTag tag) {
        ChatSession session = getSession(sessionId);
        session.getTags().add(tag);
        return sessionRepository.save(session);
    }

    @Override
    @Transactional
    public ChatSession removeSessionTag(String sessionId, com.qingyu.care.enums.SessionTag tag) {
        ChatSession session = getSession(sessionId);
        session.getTags().remove(tag);
        return sessionRepository.save(session);
    }
    
    @Override
    @Transactional
    public ChatSession updateSessionSummary(String sessionId, String summary) {
        ChatSession session = getSession(sessionId);
        session.setSummary(summary);
        return sessionRepository.save(session);
    }
    
    // ===== 人工客服相关实现 =====
    
    @Override
    @Transactional
    public ChatSession acceptSession(String sessionId, Long agentId) {
        ChatSession session = getSession(sessionId);
        // 支持从 WAITING_AGENT 或 AI_PROCESSING 状态接管
        if (session.getStatus() != SessionStatus.WAITING_AGENT && 
            session.getStatus() != SessionStatus.AI_PROCESSING) {
            throw new InvalidOperationException("该会话状态不允许接管：" + session.getStatus());
        }
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setAgentId(agentId);
        session.setHandoverTime(LocalDateTime.now());
        log.info("Session {} accepted by agent {}", sessionId, agentId);
        return sessionRepository.save(session);
    }
    
    @Override
    @Transactional
    public void agentReply(String sessionId, String message, Long agentId) {
        ChatSession session = getSession(sessionId);
        if (session.getStatus() != SessionStatus.AGENT_PROCESSING) {
            throw new InvalidOperationException("该会话不在人工处理状态");
        }
        // 保存人工客服消息
        ChatMessage agentMsg = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(message)
                .build();
        messageRepository.save(agentMsg);
        log.info("Agent {} replied to session {}", agentId, sessionId);
    }
    
    @Override
    public org.springframework.data.domain.Page<ChatSession> getWaitingAgentSessions(org.springframework.data.domain.Pageable pageable) {
        return sessionRepository.findByStatusOrderByCreatedAtDesc(SessionStatus.WAITING_AGENT, pageable);
    }
    
    @Override
    public java.util.List<ChatMessage> getSessionMessages(String sessionId) {
        ChatSession session = getSession(sessionId);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
    }
}


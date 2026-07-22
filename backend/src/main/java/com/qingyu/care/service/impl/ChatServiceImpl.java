package com.qingyu.care.service.impl;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.RetrievalScope;
import com.qingyu.care.service.RetrievalScopeResolver;
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
import org.springframework.transaction.support.TransactionTemplate;
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
    private final HybridRetrievalService hybridRetrievalService;
    private final RetrievalScopeResolver retrievalScopeResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final AbTestService abTestService;

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

        // 检索相关知识（是否走混合检索由 strategy.hybridEnabled 决定）
        List<Document> relevantDocs = searchKnowledge(request.getMessage(), strategy);
        
        // 计算置信度（M4 PgVectorStore 写入的是 "distance" 键，须换算，见 resolveConfidenceScore）
        double maxScore = relevantDocs.isEmpty() ? 0.0 :
            relevantDocs.stream()
                .mapToDouble(ChatServiceImpl::resolveConfidenceScore)
                .max()
                .orElse(0.0);
        
        // 如果知识库未命中且不允许自由发挥（兜底回复也落库：fallback 标记 + messageId 回传）
        if (relevantDocs.isEmpty() && Boolean.FALSE.equals(strategy.getAllowNoKnowledge())) {
            ChatMessage fallbackMsg = saveMessages(session, request.getMessage(),
                    strategy.getFallbackMessage(), 0.0, null, null, true);
            return ChatResponse.builder()
                    .sessionId(session.getSessionId())
                    .messageId(fallbackMsg.getId())
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
        
        // 调用AI（动态模型路由）；取完整响应以拿到 token 用量
        final List<Message> finalMessages = messages;
        org.springframework.ai.chat.model.ChatResponse aiResp =
                modelRouterService.callWithFallback("default", client ->
                        client.prompt().messages(finalMessages).call().chatResponse());
        // M4: 取文本用 getResult().getOutput().getContent()（1.0 GA 才是 getText()）
        String response = aiResp.getResult().getOutput().getContent();
        // M4: getTotalTokens() 返回 Long；安全空检查后转 Integer 填入 DTO
        Integer tokenUsage = (aiResp.getMetadata() != null && aiResp.getMetadata().getUsage() != null
                && aiResp.getMetadata().getUsage().getTotalTokens() != null)
                ? aiResp.getMetadata().getUsage().getTotalTokens().intValue()
                : null;

        // 保存消息（埋点：命中文档 + token 用量）
        String hitDocumentIds = extractHitDocumentIds(relevantDocs);
        ChatMessage aiMsg = saveMessages(session, request.getMessage(), response, maxScore,
                hitDocumentIds, tokenUsage, false);

        // 构建响应（tokenUsage 由开放控制器用于计量；内部对话同样填入供前端展示）
        return ChatResponse.builder()
                .sessionId(session.getSessionId())
                .messageId(aiMsg.getId())
                .content(response)
                .confidenceScore(maxScore)
                .tokenUsage(tokenUsage)
                .suggestHandover(handoverRuleService.checkLowConfidenceTrigger(maxScore))
                .knowledgeHits(relevantDocs.stream()
                        .map(doc -> ChatResponse.KnowledgeHit.builder()
                                .content(truncateContent(doc.getContent(), 100))
                                .score(resolveConfidenceScore(doc))
                                .source((String) doc.getMetadata().getOrDefault("source", "unknown"))
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        // 内部调用：不计量，回调为空操作
        return streamChat(request, t -> {});
    }

    @Override
    public Flux<String> streamChat(ChatRequest request, java.util.function.IntConsumer onTokensUsed) {
        return streamChat(request, onTokensUsed, id -> { });
    }

    @Override
    public Flux<String> streamChat(ChatRequest request,
                                   java.util.function.IntConsumer onTokensUsed,
                                   java.util.function.LongConsumer onAiMessageSaved) {
        // 请求线程内完成所有依赖 TenantContext 的操作（会话创建/检索）；
        // 完成回调运行在 Reactor 线程，只做无上下文依赖的落库
        ChatSession session = getOrCreateSession(request);
        ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");
        List<Document> relevantDocs = searchKnowledge(request.getMessage(), strategy);
        String context = buildContext(relevantDocs);
        List<Message> messages = buildMessages(session, request.getMessage(), context);

        double maxScore = relevantDocs.isEmpty() ? 0.0 :
                relevantDocs.stream().mapToDouble(ChatServiceImpl::resolveConfidenceScore).max().orElse(0.0);
        String hitDocumentIds = extractHitDocumentIds(relevantDocs);
        String userMessage = request.getMessage();

        ChatClient chatClient = modelRouterService.getDefaultChatClient();
        // stream().chatResponse() 返回 Flux<org.springframework.ai.chat.model.ChatResponse>
        Flux<org.springframework.ai.chat.model.ChatResponse> source =
                chatClient.prompt().messages(messages).stream().chatResponse();
        return withStreamPersistence(meterContentStream(source, onTokensUsed),
                full -> persistStreamedMessages(session, userMessage, full, maxScore,
                        hitDocumentIds, onAiMessageSaved));
    }

    /**
     * 纯方法：从 Spring AI 响应流中 tap 末尾 usage、映射为内容、结束时回调 total_tokens。
     * 无实例状态依赖（static），可在不启动 Spring 容器的单元测试中直接调用。
     *
     * M4 说明：streamUsage(true) 已在 OpenAiChatOptions 中支持（见 ModelRouterService）；
     * 末尾 chunk 携带真实 total_tokens，tokens.get() 即为真实用量。
     * 若某 chunk 无 usage 信息（非末尾 chunk），tokens 保持上次设值（最终为末尾值）。
     */
    static Flux<String> meterContentStream(
            Flux<org.springframework.ai.chat.model.ChatResponse> source,
            java.util.function.IntConsumer onTokensUsed) {
        java.util.concurrent.atomic.AtomicInteger tokens = new java.util.concurrent.atomic.AtomicInteger(0);
        return source
                .doOnNext(cr -> {
                    if (cr.getMetadata() != null && cr.getMetadata().getUsage() != null
                            && cr.getMetadata().getUsage().getTotalTokens() != null) {
                        tokens.set(cr.getMetadata().getUsage().getTotalTokens().intValue());
                    }
                })
                .mapNotNull(cr -> (cr.getResult() != null && cr.getResult().getOutput() != null)
                        ? cr.getResult().getOutput().getContent() : null)
                .doFinally(sig -> onTokensUsed.accept(tokens.get()));
    }

    /**
     * 纯方法：透传内容流并累积完整文本，仅 onComplete 时回调（error/cancel 不回调——
     * 客户端中断的半截回复不落库）。static 免容器单测，同 meterContentStream 惯例。
     */
    static Flux<String> withStreamPersistence(Flux<String> content,
                                              java.util.function.Consumer<String> onCompleteFull) {
        StringBuilder acc = new StringBuilder();
        return content
                .doOnNext(acc::append)
                .doOnComplete(() -> onCompleteFull.accept(acc.toString()));
    }

    /**
     * 流式补落库（Reactor 线程执行：ChatMessage 不继承 BaseTenantEntity，persist 不依赖租户过滤器）。
     * fail-soft：落库失败仅告警，不影响已送达的回复；回调必达（失败/空回复回 -1）。
     * 已知取舍：流式 AI 消息不落 tokenUsage（消息级 token 仅非流式；用量计量仍走 onTokensUsed → SysApiUsage）。
     * 事务原子性：与 chat() 对齐（同批用户消息+AI消息同提交/同回滚）——Reactor 线程无代理事务生效，
     * 须显式用 TransactionTemplate 包裹 saveMessages；异常捕获仍留在事务外层，保证回调必达语义不变。
     * package-private 便于同包单测（同 meterContentStream 惯例）。
     */
    void persistStreamedMessages(ChatSession session, String userMessage, String aiResponse,
                                 double confidence, String hitDocumentIds,
                                 java.util.function.LongConsumer onAiMessageSaved) {
        long savedId = -1L;
        try {
            if (!aiResponse.isEmpty()) {
                ChatMessage aiMsg = transactionTemplate.execute(status ->
                        saveMessages(session, userMessage, aiResponse, confidence, hitDocumentIds, null, false));
                savedId = aiMsg.getId();
            }
        } catch (Exception e) {
            log.warn("流式消息落库失败 sessionId={}: {}", session.getSessionId(), e.getMessage());
        }
        onAiMessageSaved.accept(savedId);
    }

    /**
     * 纯方法：从检索命中的 metadata 解析置信度（static，免容器单测，与 meterContentStream 同惯例）。
     *
     * M4 说明：PgVectorStore 的 DocumentRowMapper 把余弦距离写入键 "distance"（Float 类型），
     * 从不写 "score"——历史代码读 "score" 导致置信度恒 0、默认低置信规则（threshold 0.3）恒触发转人工。
     * 换算：score = 1 - distance（COSINE_DISTANCE 语义，与 similarityThreshold 自洽），钳位 [0,1]。
     * "score" 键存在时优先（兼容自定义写入）；两键皆无（如关键词路命中）返回 0——关键词命中的
     * 置信度语义未知，为已知局限。一律按 Number 读取，防 Float/Double 强转 CCE。
     */
    public static double resolveConfidenceScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score instanceof Number n) {
            return n.doubleValue();
        }
        Object distance = doc.getMetadata().get("distance");
        if (distance instanceof Number n) {
            return Math.max(0.0, Math.min(1.0, 1.0 - n.doubleValue()));
        }
        return 0.0;
    }

    /**
     * 纯方法：从检索命中 metadata 提取去重升序的 documentId，序列化为 JSON 数组（如 "[3,12]"）。
     * 空命中返回 null（覆盖率口径：hit_document_ids 非空即命中）。static 免容器单测，同 meterContentStream 惯例。
     */
    static String extractHitDocumentIds(List<Document> docs) {
        List<Long> ids = docs.stream()
                .map(d -> d.getMetadata().get("documentId"))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .filter(s -> !s.isEmpty() && s.chars().allMatch(Character::isDigit))
                .map(Long::parseLong)
                .distinct()
                .sorted()
                .toList();
        if (ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
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
    
    /** package-private 便于同包单测（同 persistStreamedMessages 惯例） */
    ChatSession createNewSession(ChatRequest request) {
        ChatSession session = ChatSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .status(SessionStatus.AI_PROCESSING)
                .build();
        // 仅开放渠道参与 A/B 分流（管理后台测试对话不污染实验样本）
        if (request.isOpenChannel()) {
            abTestService.assignForNewSession().ifPresent(a -> {
                session.setExperimentId(a.experimentId());
                session.setVariantName(a.variantName());
                session.setVariantStrategyId(a.variantStrategyId());
            });
        }
        ChatSession saved = sessionRepository.save(session);
        log.info("Session {} created for user {}", saved.getSessionId(), saved.getUserId());
        return saved;
    }
    
    /**
     * 知识检索入口（供外部/测试直接调用）：按当前租户上下文取该租户激活的 default 场景策略，
     * 再委派给按 strategy 分流的实现。
     */
    @Override
    public List<Document> searchKnowledge(String query) {
        ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");
        return searchKnowledge(query, strategy);
    }

    /**
     * 先解析检索范围（当前应用下 enabled 知识库集合）：空范围（无应用上下文 / 无启用库 / 解析异常）
     * 直接短路返回空（省掉 embedding 调用），走既有 allowNoKnowledge/fallbackMessage 语义。
     * 非空范围下按 strategy 分流：hybridEnabled（null 视为开启）走混合检索，否则纯向量；
     * 两条路径都带 tenant+app+kbIds 过滤。整体 fail-soft：任何异常均返回空列表，不阻断对话。
     */
    private List<Document> searchKnowledge(String query, ChatStrategy strategy) {
        try {
            RetrievalScope scope = retrievalScopeResolver.resolve();
            if (scope.isEmpty()) {
                log.debug("检索范围为空（无应用上下文或无启用知识库），跳过检索");
                return List.of();
            }

            // null 视为开启：特性默认开，且免去既有部署（旧数据行 hybridEnabled 为 null）的 backfill 迁移
            boolean hybrid = !Boolean.FALSE.equals(strategy.getHybridEnabled());
            if (hybrid) {
                return hybridRetrievalService.search(query, scope, strategy);
            }

            int topK = strategy.getTopK() != null ? strategy.getTopK() : 5;
            double threshold = strategy.getSimilarityThreshold() != null ? strategy.getSimilarityThreshold() : 0.7;
            SearchRequest searchRequest = SearchRequest.query(query)
                    .withTopK(topK)
                    .withSimilarityThreshold(threshold)
                    .withFilterExpression(scope.toFilterExpression());
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
    
    /**
     * 保存一轮问答（用户消息 + AI 回复），返回 AI 消息（供响应回传 messageId）。
     * fallback 只在兜底时置 TRUE（其余 null 避免 FALSE 噪声；rollup 按 = TRUE 统计）。
     */
    private ChatMessage saveMessages(ChatSession session, String userMessage, String aiResponse,
                                     double confidence, String hitDocumentIds,
                                     Integer tokenUsage, boolean fallback) {
        // 保存用户消息
        ChatMessage userMsg = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(userMessage)
                .build();
        messageRepository.save(userMsg);

        // 保存AI回复（埋点：命中文档 + token + 兜底标记）
        ChatMessage aiMsg = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(aiResponse)
                .confidenceScore(confidence)
                .hitDocumentIds(hitDocumentIds)
                .tokenUsage(tokenUsage)
                .fallback(fallback ? Boolean.TRUE : null)
                .build();
        return messageRepository.save(aiMsg);
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


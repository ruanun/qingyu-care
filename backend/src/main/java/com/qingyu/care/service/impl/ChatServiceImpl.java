package com.qingyu.care.service.impl;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.dto.UiPart;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.RetrievalScope;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.StreamingSpecSplitter;
import com.qingyu.care.service.UiSpecService;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
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
    private final ConfigService configService;
    private final ChatToolService chatToolService;
    private final RealtimeNotifier realtimeNotifier;
    private final EmbeddingModelProvider embeddingModelProvider;
    private final UiSpecService uiSpecService;
    private final ObjectMapper objectMapper;
    // 附件关联（MM-6）：本类 → ChatAttachmentService 单方向依赖，反向会构造器循环依赖
    // （ChatAttachmentService 刻意不注入 ChatService，见 ChatAttachmentService 类注释）
    private final ChatAttachmentService chatAttachmentService;

    // 结构化卡片：是否向系统 Prompt 注入卡片能力片段（流式与非流式均注入；
    // 流式由 StreamingSpecSplitter 在围栏闭合时增量下发 CARD 事件）
    @Value("${qingyu.care.cards.enabled:true}")
    private boolean cardsEnabled;

    /**
     * 结构化卡片能力 Prompt 片段：追加在系统提示末尾，告知模型可用 ```spec 围栏内联输出结构化 UI
     * 节点（与普通文字交错）。仅在 cardsEnabled 开启且 buildMessages(withCards=true) 时注入
     * （chat() 与 streamChat() 均传 withCards=true）。
     */
    private static final String CARD_PROMPT = """


            ---
            【结构化卡片能力】
            除了普通文字，你还可以在回复中内联输出结构化卡片：用 ```spec 围栏包裹一段完整的 JSON UI 树，可与文字交错出现（围栏外的文字仍按普通文本展示）。
            节点格式：{"type":"组件名","props":{...},"children":[子节点...]}
            可用组件：card（卡片容器，props.title）、text（文本，props.value，可选 props.emphasis 高亮）、kv（标签:值，props.label/props.value）、
            badge（徽标，props.text）、divider（分割线）、image（图片，props.src）、button（按钮，props.label 必填；点击行为二选一——props.action 用 Action URI 执行动作：url://链接（新标签打开）、
            msg://文本（作为用户消息发出）、copy://文本（复制到剪贴板）；或 props.value 纯文本（点击后作为用户消息发出，等价于 msg://）。
            可选 props.style：primary（主要）/danger（危险），缺省为普通样式）、
            actions（按钮组容器）、list（列表）、row（横排容器）、column（竖排容器）。
            示例：
            ```spec
            {"type":"card","props":{"title":"订单 #10086"},"children":[
              {"type":"kv","props":{"label":"金额","value":"¥199"}},
              {"type":"actions","children":[
                {"type":"button","props":{"label":"查看物流","value":"查询订单10086物流"}},
                {"type":"button","props":{"label":"订单详情","action":"url://https://shop.example.com/orders/10086"}},
                {"type":"button","props":{"label":"复制单号","action":"copy://SF13142026","style":"primary"}}
              ]}
            ]}
            ```
            表单类（收集用户输入）：form（表单容器）、input（文本框，props.name/props.label/props.placeholder，可选 props.required=true 标记必填，用户未填/未勾则无法提交）、
            select（下拉，props.name/props.label/props.options 数组，可选 props.required=true）、checkbox（勾选，props.name/props.label，可选 props.required=true 表示必须勾选）、
            datetime（日期，props.name/props.label，可选 props.required=true）、submit（提交按钮，props.label）。
            表单内各字段的 name 必须唯一（作为字段标识与提交时的键）。
            表单示例：
            ```spec
            {"type":"form","children":[
              {"type":"input","props":{"name":"姓名","label":"您的姓名","required":true}},
              {"type":"select","props":{"name":"日期","label":"预约日期","options":["今天","明天"]}},
              {"type":"submit","props":{"label":"提交预约"}}
            ]}
            ```
            用户填写并点击提交后，系统会把各字段值作为一条消息发给你，你据此继续处理。
            只在确实需要结构化展示（如订单、列表、可点击操作、表单收集）时才使用卡片；不需要卡片时，直接正常回复纯文本即可，不要滥用。
            """;

    // 自注入解决 @Transactional 同类自调用不经代理不生效问题（仿 KnowledgeServiceImpl.self）
    private ChatServiceImpl self;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    public void setSelf(ChatServiceImpl self) {
        this.self = self;
    }

    @Override
    @Transactional
    public ChatResponse chat(ChatRequest request) {
        // 获取或创建会话
        ChatSession session = getOrCreateSession(request);
        
        // AI 静默模式：当会话由人工处理时，不调用 AI（仅存用户消息 + 已接管则实时推给坐席）
        if (isAgentControlled(session)) {
            persistUserMessageDuringHandover(session, request.getMessage(), request.getAttachmentIds());

            return ChatResponse.builder()
                    .sessionId(session.getSessionId())
                    .content("[会话已转接人工客服，请等待回复]")
                    .confidenceScore(0.0)
                    .suggestHandover(false)
                    .knowledgeHits(List.of())
                    .build();
        }

        // 检查关键词触发转人工
        if (triggerKeywordHandoverIfHit(session, request.getMessage(), request.getAttachmentIds())) {
            return ChatResponse.builder()
                    .sessionId(session.getSessionId())
                    .content("正在为您转接人工客服，请稍候...")
                    .confidenceScore(0.0)
                    .suggestHandover(true)
                    .knowledgeHits(List.of())
                    .build();
        }

        // 获取策略配置（会话带 A/B 变体时用变体策略）
        ChatStrategy strategy = resolveStrategy(session);

        // 检索相关知识（是否走混合检索由 strategy.hybridEnabled 决定）；
        // 经 self 走代理 → searchKnowledge 的 REQUIRES_NEW 只读事务生效，检索 SQL 失败不毒化本写事务
        List<Document> relevantDocs;
        try {
            relevantDocs = self.searchKnowledge(request.getMessage(), strategy);
        } catch (Exception e) {
            // REQUIRES_NEW 的开启/提交事务阶段异常（如连接池耗尽）落在 searchKnowledge 方法体 try 之外，
            // 此处兜底补全 fail-soft 契约：检索不可用时降级为空知识、不阻断对话
            log.warn("检索独立事务开启/提交失败，降级为空知识: {}", e.getMessage());
            relevantDocs = List.of();
        }

        // 计算置信度（M4 PgVectorStore 写入的是 "distance" 键，须换算，见 resolveConfidenceScore）
        double maxScore = relevantDocs.isEmpty() ? 0.0 :
            relevantDocs.stream()
                .mapToDouble(ChatServiceImpl::resolveConfidenceScore)
                .max()
                .orElse(0.0);

        // 连续未命中转人工：本轮是否未命中知识库 → 更新会话连续未命中计数（命中归 0），显式落库
        // 存量行（加列前创建）读回 no_hit_streak 可能为 null，null 归 0 兜底防拆箱 NPE
        boolean noHit = relevantDocs.isEmpty();
        int currentStreak = session.getNoHitStreak() == null ? 0 : session.getNoHitStreak();
        session.setNoHitStreak(noHit ? currentStreak + 1 : 0);
        sessionRepository.save(session);
        // 达阈值建议转人工（getNoHitStreakThreshold 禁用时返 Integer.MAX_VALUE，天然不触发）
        boolean noHitStreakHandover = noHit
                && session.getNoHitStreak() >= handoverRuleService.getNoHitStreakThreshold();

        // 如果知识库未命中且不允许自由发挥（兜底回复也落库：fallback 标记 + messageId 回传）
        if (relevantDocs.isEmpty() && Boolean.FALSE.equals(strategy.getAllowNoKnowledge())) {
            ChatMessage fallbackMsg = saveMessages(session, request.getMessage(),
                    strategy.getFallbackMessage(), 0.0, null, null, true, null,
                    request.getAttachmentIds());
            return ChatResponse.builder()
                    .sessionId(session.getSessionId())
                    .messageId(fallbackMsg.getId())
                    .content(strategy.getFallbackMessage())
                    .confidenceScore(0.0)
                    .suggestHandover(noHitStreakHandover)
                    .knowledgeHits(List.of())
                    .build();
        }

        // 构建上下文
        String context = buildContext(relevantDocs);

        // 解析当前应用的 skill/工具（请求线程内，依赖 TenantContext）
        ChatToolService.ToolBundle toolBundle = chatToolService.resolve();
        // withCards=true：注入卡片能力 Prompt 片段（chat() 与 streamChat() 均传 true）
        List<Message> messages = buildMessages(session, request.getMessage(), context, strategy,
                toolBundle.skillMarkdowns(), true);

        // 调用AI（动态模型路由，场景取租户配置 tenant.default.model.scenario）；取完整响应以拿到 token 用量
        final List<Message> finalMessages = messages;
        final List<ToolCallback> tools = toolBundle.toolCallbacks();
        org.springframework.ai.chat.model.ChatResponse aiResp =
                modelRouterService.callWithFallback(resolveModelScenario(), client -> {
                    // 不要在这里 .options(...)，会整体替换掉模型默认 options（含 streamUsage(true)），
                    // 见 ModelRouterService#createOpenAiCompatibleClient
                    var spec = client.prompt().messages(finalMessages);
                    if (!tools.isEmpty()) {
                        spec = spec.toolCallbacks(tools);   // 无工具时不挂载 → 与现状逐字节一致
                    }
                    return spec.call().chatResponse();
                });
        if (!tools.isEmpty()) {
            log.debug("本轮启用 {} 个工具；带工具调用时 usage 仅反映末轮，token 计量可能少计（已知局限）",
                    tools.size());
        }
        // Spring AI 1.x GA：取文本用 getResult().getOutput().getText()
        String response = aiResp.getResult().getOutput().getText();
        // getTotalTokens() 返回 Integer；安全空检查后填入 DTO
        Integer tokenUsage = (aiResp.getMetadata() != null && aiResp.getMetadata().getUsage() != null
                && aiResp.getMetadata().getUsage().getTotalTokens() != null)
                ? aiResp.getMetadata().getUsage().getTotalTokens().intValue()
                : null;

        // 结构化卡片：解析原始回复里内联的 ```spec 围栏为有序 uiParts，content 落为剥离围栏的纯文本
        // （fail-soft：UiSpecService.parse 自身不抛异常，无围栏时 uiParts 只含一个 text 片段，逐字节兼容旧行为）
        UiSpecService.ParseResult parsed = uiSpecService.parse(response);
        String plainText = parsed.plainText();
        String uiSpecJson = writeUiSpec(parsed.uiParts());

        // 保存消息（埋点：命中文档 + token 用量 + 卡片 JSON）
        String hitDocumentIds = extractHitDocumentIds(relevantDocs);
        ChatMessage aiMsg = saveMessages(session, request.getMessage(), plainText, maxScore,
                hitDocumentIds, tokenUsage, false, uiSpecJson, request.getAttachmentIds());

        // 构建响应（tokenUsage 由开放控制器用于计量；内部对话同样填入供前端展示）
        return ChatResponse.builder()
                .sessionId(session.getSessionId())
                .messageId(aiMsg.getId())
                .content(plainText)
                .uiParts(parsed.uiParts())
                .confidenceScore(maxScore)
                .tokenUsage(tokenUsage)
                .suggestHandover(handoverRuleService.checkLowConfidenceTrigger(maxScore) || noHitStreakHandover)
                .knowledgeHits(relevantDocs.stream()
                        .map(doc -> ChatResponse.KnowledgeHit.builder()
                                .content(truncateContent(doc.getText(), 100))
                                .score(resolveConfidenceScore(doc))
                                .source((String) doc.getMetadata().getOrDefault("source", "unknown"))
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    public Flux<ChatStreamEvent> streamChat(ChatRequest request) {
        // 内部调用：不计量，两个回调均为空操作
        return streamChat(request, t -> {}, t -> {});
    }

    @Override
    public Flux<ChatStreamEvent> streamChat(ChatRequest request,
                                            java.util.function.IntConsumer onTokensUsed,
                                            java.util.function.IntConsumer onFallbackTokensUsed) {
        // 请求线程内完成所有依赖 TenantContext 的操作（会话创建/检索/模型解析）；
        // 落库回调运行在 Reactor 线程，只做无上下文依赖的写入
        ChatSession session = getOrCreateSession(request);

        // 与 chat() 严格同序：先判人工接管态静默，再判关键词触发，避免已接管会话被关键词打回 WAITING_AGENT
        if (isAgentControlled(session)) {
            persistUserMessageDuringHandover(session, request.getMessage(), request.getAttachmentIds());
            onTokensUsed.accept(0);
            // 会话已在人工流程中，再提示"建议转人工"是噪声
            return earlyReturn(session, "[会话已转接人工客服，请等待回复]", false);
        }
        if (triggerKeywordHandoverIfHit(session, request.getMessage(), request.getAttachmentIds())) {
            onTokensUsed.accept(0);
            // 与 chat() 的关键词分支同语义：suggestHandover=true，否则管理端转人工提示条不出现
            return earlyReturn(session, "正在为您转接人工客服，请稍候...", true);
        }

        ChatStrategy strategy = resolveStrategy(session);
        // 同 chat()：经 self 走代理，检索在独立只读事务上执行
        List<Document> relevantDocs;
        try {
            relevantDocs = self.searchKnowledge(request.getMessage(), strategy);
        } catch (Exception e) {
            // 同 chat()：REQUIRES_NEW 的开启/提交事务阶段异常落在方法体 try 之外，此处兜底 fail-soft
            log.warn("检索独立事务开启/提交失败，降级为空知识: {}", e.getMessage());
            relevantDocs = List.of();
        }
        double maxScore = relevantDocs.isEmpty() ? 0.0 :
                relevantDocs.stream().mapToDouble(ChatServiceImpl::resolveConfidenceScore).max().orElse(0.0);

        // 连续未命中转人工：与 chat() 同步更新会话计数（命中归 0），显式落库。
        // 存量行（加列前创建）读回 no_hit_streak 可能为 null，null 归 0 兜底防拆箱 NPE
        boolean noHit = relevantDocs.isEmpty();
        int currentStreak = session.getNoHitStreak() == null ? 0 : session.getNoHitStreak();
        session.setNoHitStreak(noHit ? currentStreak + 1 : 0);
        sessionRepository.save(session);
        boolean noHitStreakHandover = noHit
                && session.getNoHitStreak() >= handoverRuleService.getNoHitStreakThreshold();

        // 未命中且不允许自由发挥：与 chat() 一致走兜底回复（兜底也落库：fallback 标记 + messageId 回传）
        if (relevantDocs.isEmpty() && Boolean.FALSE.equals(strategy.getAllowNoKnowledge())) {
            onTokensUsed.accept(0);
            ChatMessage fallbackMsg = saveMessages(session, request.getMessage(),
                    strategy.getFallbackMessage(), 0.0, null, null, true, null,
                    request.getAttachmentIds());
            return Flux.just(
                    ChatStreamEvent.start(session.getSessionId(), noHitStreakHandover, List.of()),
                    ChatStreamEvent.delta(strategy.getFallbackMessage()),
                    ChatStreamEvent.done(fallbackMsg.getId()));
        }

        String context = buildContext(relevantDocs);
        // 解析当前应用的 skill/工具（请求线程内，依赖 TenantContext）——与 chat() 同源
        ChatToolService.ToolBundle toolBundle = chatToolService.resolve();
        // withCards=true：流式同样注入卡片能力 Prompt 片段，由 StreamingSpecSplitter 增量下发
        List<Message> messages = buildMessages(session, request.getMessage(), context, strategy,
                toolBundle.skillMarkdowns(), true);

        String hitDocumentIds = extractHitDocumentIds(relevantDocs);
        String userMessage = request.getMessage();
        boolean suggestHandover =
                handoverRuleService.checkLowConfidenceTrigger(maxScore) || noHitStreakHandover;
        List<ChatResponse.KnowledgeHit> knowledgeHits = relevantDocs.stream()
                .map(doc -> ChatResponse.KnowledgeHit.builder()
                        .content(truncateContent(doc.getText(), 100))
                        .score(resolveConfidenceScore(doc))
                        .source((String) doc.getMetadata().getOrDefault("source", "unknown"))
                        .build())
                .collect(Collectors.toList());

        final List<ToolCallback> tools = toolBundle.toolCallbacks();
        Flux<org.springframework.ai.chat.model.ChatResponse> source =
                modelRouterService.streamWithFallback(resolveModelScenario(), client -> {
                    // 不要在这里 .options(...)，会整体替换掉模型默认 options（含 streamUsage(true)，
                    // 丢失后 token 计量会静默记 0），见 ModelRouterService#createOpenAiCompatibleClient
                    var spec = client.prompt().messages(messages);
                    if (!tools.isEmpty()) {
                        spec = spec.toolCallbacks(tools);   // 无工具时不挂载 → 与 chat() 同款处理
                    }
                    return spec.stream().chatResponse();
                });

        ChatStreamEvent startEvent =
                ChatStreamEvent.start(session.getSessionId(), suggestHandover, knowledgeHits);

        // 请求线程内捕获（Reactor 线程无 ThreadLocal 上下文，回退分支要手动搬运）
        final Long tenantIdForFallback = TenantContext.getTenantId();
        final Long appIdForFallback = TenantContext.getAppId();
        final DocumentVisibility accessLevelForFallback = TenantContext.getAccessLevel();

        return Flux.defer(() -> {
            // splitter/累加器/emitted 均有状态，必须每次订阅各持一份，否则重复订阅会串状态；
            // onErrorResume 一并挪进来（而非挂在 Flux.defer 外层），好让它能读到本次订阅专属的 emitted
            StreamingSpecSplitter splitter = new StreamingSpecSplitter(objectMapper);
            StringBuilder acc = new StringBuilder();
            java.util.concurrent.atomic.AtomicBoolean emitted =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Flux<ChatStreamEvent> body = meterContentStream(source, onTokensUsed)
                    .doOnNext(acc::append)
                    .concatMap(chunk -> Flux.fromIterable(splitter.accept(chunk)))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(splitter.flush())))
                    // 已经吐给客户端至少一个事件：回退会把完整答案整段重发，与已发内容重叠拼接成
                    // 「半截+完整」，只有目标缺陷那种「首个 chunk 合并即炸、一个字节没吐出去」的场景才能安全回退
                    .doOnNext(evt -> emitted.set(true));
            // 落库排在正文之后（DONE 的 messageId 必然有效）；客户端中断时该 Mono 根本不会被订阅，
            // 半截回复自然不落库（与改造前 doOnComplete 语义一致）。
            // tenantIdForFallback/appIdForFallback/accessLevelForFallback 复用 fallbackToNonStreaming
            // 同一份请求线程捕获——本 Mono 同样在无 TenantContext 的 Reactor 线程执行，
            // 附件归属校验（attachToMessage → mutableInCurrentContext）依赖它
            Mono<ChatStreamEvent> done = Mono.fromCallable(() -> persistStreamedMessages(
                    session, userMessage, acc.toString(), maxScore, hitDocumentIds,
                    request.getAttachmentIds(), tenantIdForFallback, appIdForFallback,
                    accessLevelForFallback));
            return Flux.concat(Flux.just(startEvent), body, done)
                    .onErrorResume(e -> {
                        // 固定文案，绝不回传原始异常（widget 运行在客户网页里）；详情只进日志
                        log.warn("流式对话失败 sessionId={}: {}", session.getSessionId(), e.getMessage(), e);
                        if (tools.isEmpty() || emitted.get()) {
                            return Flux.just(ChatStreamEvent.error("回复生成失败，请稍后重试"));
                        }
                        // 已知上游缺陷：Spring AI 2.0 合并含 tool_call 的流式 chunk 时抛 NoSuchElementException
                        // （spring-projects/spring-ai#4790，修复 PR #4794 未合并、2.0.0 已是最新稳定版）。
                        // 挂了工具且尚未吐出任何正文的这一轮降级为非流式重跑，保住工具能力；
                        // 无工具、或已经吐过内容，均不走此路——前者免得把真实故障静默重跑掩盖掉，
                        // 后者是为了不把内容拼两遍。
                        return fallbackToNonStreaming(request, session, tenantIdForFallback,
                                appIdForFallback, accessLevelForFallback, onFallbackTokensUsed);
                    });
        });
    }

    /**
     * 流式失败后的非流式兜底：在独立线程临时恢复租户上下文，用非流式 chat() 重跑一轮，
     * 把完整结果补发为 DELTA/CARD/DONE 事件（START 已在流首发出，此处不再补）。
     * <p>ThreadLocal 必须 finally 清理：boundedElastic 线程复用，漏清会把租户上下文泄漏给后续请求。
     * <p>{@code subscribeOn(boundedElastic())}：调用方（Spring AI 发流的线程，生产上可能是事件循环线程）
     * 不能被 {@code self.chat(...)} 这个阻塞的非流式 LLM 往返占用一整轮；隔离到独立线程还顺带避免了
     * ThreadLocal 的 set/clear 在同步订阅下误伤调用方自己线程上的上下文。
     * <p><b>token 计量</b>：回退触发前 {@link #meterContentStream} 的 {@code doFinally} 已经以 0 token
     * 为这一轮计过账（流式失败于 usage 到达之前）；本方法回退成功后通过 {@code onFallbackTokensUsed}
     * 补记 {@code chat()} 返回的真实 token 用量——只补 token 差值，不重复计调用/消息次数（不能直接复用
     * 主计量回调，{@code ApiUsageService.record} 的 SQL 无条件把 call_count + 1）。回退本身失败则不补记，
     * 维持已记的 0。
     * <p>已知取舍（token 计量的另一半）：客户端「停止生成」/异步超时（{@code doFinally(CANCEL)}）与本方法
     * 无关，同样只记 0 token——彼时真实 token 已被上游模型消耗但无法追加计量，这是流式取消场景的固有代价。
     * <p>已知取舍（no_hit_streak）：回退会重跑 {@code self.chat(request)}，其内部重新执行一次检索并
     * 二次更新会话的 {@code no_hit_streak} 计数；未命中场景下该计数会因这次"重复的未命中"而虚高一次，
     * 转人工阈值可能因此提前触发。
     */
    Flux<ChatStreamEvent> fallbackToNonStreaming(ChatRequest request, ChatSession session,
                                                 Long tenantId, Long appId, DocumentVisibility accessLevel,
                                                 java.util.function.IntConsumer onFallbackTokensUsed) {
        return Mono.fromCallable(() -> {
            List<ChatStreamEvent> events = new ArrayList<>();
            try {
                TenantContext.setTenantId(tenantId);
                TenantContext.setAppId(appId);
                TenantContext.setAccessLevel(accessLevel);
                // 钉住会话：START 已把该 sessionId 发给客户端，回退不能另起新会话
                request.setSessionId(session.getSessionId());
                ChatResponse resp = self.chat(request);          // 走代理，保事务
                if (resp.getTokenUsage() != null && resp.getTokenUsage() > 0) {
                    onFallbackTokensUsed.accept(resp.getTokenUsage());
                }
                List<UiPart> parts = resp.getUiParts();
                if (parts != null && !parts.isEmpty()) {
                    for (UiPart p : parts) {
                        if ("ui".equals(p.getKind()) && p.getNode() != null) {
                            events.add(ChatStreamEvent.card(p.getNode()));
                        } else if (p.getText() != null && !p.getText().isEmpty()) {
                            events.add(ChatStreamEvent.delta(p.getText()));
                        }
                    }
                } else if (resp.getContent() != null && !resp.getContent().isEmpty()) {
                    events.add(ChatStreamEvent.delta(resp.getContent()));
                }
                events.add(ChatStreamEvent.done(resp.getMessageId()));
            } finally {
                TenantContext.clear();   // 必须无条件清理
            }
            return events;
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMapMany(Flux::fromIterable)
          .onErrorResume(e2 -> {
              // 兜底的兜底：回退本身再失败就老实报错，不再递归
              log.warn("非流式回退同样失败 sessionId={}: {}", session.getSessionId(), e2.getMessage(), e2);
              return Flux.just(ChatStreamEvent.error("回复生成失败，请稍后重试"));
          });
    }

    /**
     * 早退分支（人工接管静默 / 关键词转人工）：同样发完整 START + DELTA + DONE 序列，
     * 否则客户端拿不到 sessionId。这两条路不落 AI 消息，故 DONE 的 messageId 为 null。
     * <p>suggestHandover 由调用方按分支语义传入，与 chat() 的对应分支保持同语义：人工接管态传 false
     * （会话已在人工流程中，再提示「建议转人工」是噪声）；关键词命中传 true（首次触发，管理端需要
     * 出现转人工提示条），否则相对非流式 chat() 会是行为回退。
     */
    private Flux<ChatStreamEvent> earlyReturn(ChatSession session, String text, boolean suggestHandover) {
        return Flux.just(
                ChatStreamEvent.start(session.getSessionId(), suggestHandover, List.of()),
                ChatStreamEvent.delta(text),
                ChatStreamEvent.done(null));
    }

    /**
     * 纯方法：把各 skill 的 Markdown 正文追加到系统提示末尾（分隔符包裹）。空/ null 原样返回。
     * static 免容器单测，同 meterContentStream 惯例。
     */
    static String appendSkillMarkdowns(String systemPrompt, java.util.List<String> markdowns) {
        if (markdowns == null || markdowns.isEmpty()) {
            return systemPrompt;
        }
        StringBuilder sb = new StringBuilder(systemPrompt);
        for (String md : markdowns) {
            sb.append("\n\n--- 技能指引 ---\n").append(md);
        }
        return sb.toString();
    }

    /**
     * 纯方法：卡片能力开启（withCards 且 cardsEnabled）时把卡片指令片段追加到系统提示末尾，否则原样返回。
     * static 免容器单测，同 appendSkillMarkdowns 惯例——让「开关生效」这条产出机制可被直接单测。
     */
    static String appendCardPrompt(String systemPrompt, boolean withCards, boolean cardsEnabled) {
        if (!withCards || !cardsEnabled) {
            return systemPrompt;
        }
        return systemPrompt + CARD_PROMPT;
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
                        ? cr.getResult().getOutput().getText() : null)
                .doFinally(sig -> onTokensUsed.accept(tokens.get()));
    }

    /**
     * 流式补落库（Reactor 线程执行：ChatMessage 不继承 BaseTenantEntity，persist 不依赖租户过滤器），
     * 并产出流末尾的 DONE 事件。无附件时的旧签名，保留给既有直调单测（同包单测惯例）；
     * 生产路径（streamChat）一律走下方带附件/租户参数的重载。
     */
    ChatStreamEvent persistStreamedMessages(ChatSession session, String userMessage, String rawResponse,
                                            double confidence, String hitDocumentIds) {
        return persistStreamedMessages(session, userMessage, rawResponse, confidence, hitDocumentIds,
                null, null, null, null);
    }

    /**
     * 流式补落库（Reactor 线程执行：ChatMessage 不继承 BaseTenantEntity，persist 不依赖租户过滤器），
     * 并产出流末尾的 DONE 事件。
     * <p>落库走 {@link UiSpecService#parse}（与非流式 chat() 同一实现）：content 落剥围栏的纯文本、
     * ui_spec 落卡片 JSON——渲染侧的 StreamingSpecSplitter 只管何时发事件，不承担落库正确性。
     * <p>fail-soft：落库失败仅告警，返回 messageId 为 null 的 DONE，不影响已送达的回复。
     * <p>已知取舍：流式 AI 消息不落 tokenUsage（消息级 token 仅非流式；用量计量仍走 onTokensUsed → SysApiUsage）。
     * <p>事务原子性：Reactor 线程无代理事务生效，须显式用 TransactionTemplate 包裹 saveMessages。
     * <p>附件关联（MM-6）：本方法运行在无 {@link TenantContext} 的 Reactor 线程（同 {@link #fallbackToNonStreaming}
     * 的既有教训），而 {@code saveMessages} 内部的 {@code attachToMessage} 依赖它做归属校验
     * （{@code mutableInCurrentContext} 在 ctxTenantId 为 null 时无条件放行——若不恢复上下文，
     * 会让归属校验形同虚设）。仅当确有附件时才 set/finally-clear（tenantId/appId/accessLevel
     * 均由 streamChat() 在请求线程内提前捕获、经方法参数搬运过来），避免无附件的常见路径
     * 无谓地清空/恢复 ThreadLocal。
     * package-private 便于同包单测（同 meterContentStream 惯例）。
     */
    ChatStreamEvent persistStreamedMessages(ChatSession session, String userMessage, String rawResponse,
                                            double confidence, String hitDocumentIds,
                                            List<Long> attachmentIds,
                                            Long tenantId, Long appId, DocumentVisibility accessLevel) {
        Long savedId = null;
        try {
            if (!rawResponse.isEmpty()) {
                UiSpecService.ParseResult parsed = uiSpecService.parse(rawResponse);
                String uiSpecJson = writeUiSpec(parsed.uiParts());
                boolean needsContext = attachmentIds != null && !attachmentIds.isEmpty();
                if (needsContext) {
                    TenantContext.setTenantId(tenantId);
                    TenantContext.setAppId(appId);
                    TenantContext.setAccessLevel(accessLevel);
                }
                try {
                    ChatMessage aiMsg = transactionTemplate.execute(status ->
                            saveMessages(session, userMessage, parsed.plainText(), confidence,
                                    hitDocumentIds, null, false, uiSpecJson, attachmentIds));
                    savedId = aiMsg.getId();
                } finally {
                    if (needsContext) {
                        TenantContext.clear();   // 必须清理，防止执行线程被复用时把上下文漏给下一轮请求
                    }
                }
            }
        } catch (Exception e) {
            log.warn("流式消息落库失败 sessionId={}: {}", session.getSessionId(), e.getMessage());
        }
        return ChatStreamEvent.done(savedId);
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
        ChatSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("会话", sessionId));
        // findBySessionId 不受 @Filter 保护，加载后显式校验归属，越权按「不存在」同语义处理防枚举探测；
        // 无上下文（平台管理员登录后 tenantId 恒为 null）按 belongsToCurrentContext 语义放行
        if (!TenantOwnershipGuard.belongsToCurrentContext(session.getTenantId(), session.getAppId())) {
            throw new ResourceNotFoundException("会话", sessionId);
        }
        return session;
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
            
            // 不要在这里 .options(...)，会整体替换掉模型默认 options，
            // 见 ModelRouterService#createOpenAiCompatibleClient
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
    
    /**
     * 取本次对话生效的策略：会话带有效变体（实验仍 RUNNING 且策略可用）→ 变体策略；
     * 其余一切情形回退默认策略。package-private 便于同包单测。
     */
    ChatStrategy resolveStrategy(ChatSession session) {
        return abTestService.resolveVariantStrategy(session.getExperimentId(), session.getVariantStrategyId())
                .orElseGet(() -> chatStrategyService.getActiveStrategy("default"));
    }

    /**
     * 解析当前租户的默认模型场景（租户配置 tenant.default.model.scenario，缺省 default）。
     * 只影响模型路由（callWithFallback / 流式 ChatClient 选择）；问答策略的场景解析与
     * 会话摘要等辅助任务不受此配置影响，仍走 default。依赖 TenantContext，须在请求线程调用。
     */
    String resolveModelScenario() {
        return configService.get(OwnerScope.TENANT, TenantContext.getTenantId(),
                "tenant.default.model.scenario", "default");
    }

    /**
     * 解析系统 Prompt 模板：策略绑定 promptTemplateId 优先（闭合该字段长期未接线的缺口，
     * 取不到 fail-soft 回退），否则全局激活模板。package-private 便于同包单测。
     */
    String resolveSystemTemplate(ChatStrategy strategy) {
        if (strategy != null && strategy.getPromptTemplateId() != null) {
            try {
                return promptTemplateService.getById(strategy.getPromptTemplateId()).getContent();
            } catch (Exception e) {
                log.warn("策略绑定的 Prompt 模板不可用（id={}），回退全局激活模板: {}",
                        strategy.getPromptTemplateId(), e.getMessage(), e);
            }
        }
        return promptTemplateService.getActiveTemplateContent();
    }

    /**
     * 对话入口的租户上下文守卫：无租户上下文（平台管理员）一律拒绝发起/续接对话。
     *
     * <p>为什么必须拦：{@link com.qingyu.care.config.TenantEntityListener} 只从
     * {@link TenantContext} 回填、不做 appId→租户反查，而平台管理员的 tenantId 恒为 null，
     * 于是会话落库时 {@code tenant_id} 为 NULL、{@code app_id} 有值 —— 平台管理员自己的
     * 会话列表按 {@link #getAllSessions} 的越权收口返空页看不到，被测租户的管理员也看不到
     * （NULL ≠ 其 tenantId），**成为谁都读不到的孤儿数据**。与「列会话返空页」保持同一种
     * 态度：宁可拒绝，也不静默写出不可达的行。
     *
     * <p>注意读写两侧此前是不一致的：{@link com.qingyu.care.service.ConfigScopeResolver}
     * 会为平台管理员按 X-App-Id 反查租户（故检索能命中被测租户的知识库），而写路径不会。
     * 本守卫按「禁止」方向对齐；若将来改为允许，正确做法是让写路径也做同样的反查，
     * 并给这类调试会话打标记，避免污染该租户的用量与效果统计。
     *
     * <p>开放 API / Widget 不受影响：{@code OpenApiAuthInterceptor} 鉴权通过后已写入 tenantId。
     */
    private void requireTenantContext() {
        if (TenantContext.getTenantId() == null) {
            throw new BusinessException("平台管理员无租户上下文，无法发起对话；请使用租户账号登录后再试");
        }
    }

    private ChatSession getOrCreateSession(ChatRequest request) {
        requireTenantContext();
        if (request.getSessionId() != null) {
            // findBySessionId 不受 @Filter 保护，加载后须显式校验归属；越权（如平台管理员选了应用 X
            // 却传入应用 Y 的 sessionId）视同「未找到」直接建新会话，热路径不抛异常，保正常续接 UX
            return sessionRepository.findBySessionId(request.getSessionId())
                    .filter(s -> TenantOwnershipGuard.belongsToCurrentContext(s.getTenantId(), s.getAppId()))
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
     * <p>事务隔离：经 chat()/streamChat() 的 self 代理调用时以 REQUIRES_NEW 只读新事务执行——
     * 检索 SQL（关键词全文/向量）失败只毒化该独立连接，chat 写事务连接不受影响，真正 fail-soft。
     * 公开入口 searchKnowledge(String) 内部对本方法保持直调（自调用不经代理、注解失效），
     * 以保住检索类 IT 的同事务播种可见性——切勿改成 self.searchKnowledge。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Document> searchKnowledge(String query, ChatStrategy strategy) {
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
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(scope.toFilterExpression(embeddingModelProvider.getModel()))
                    .build();
            return vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.warn("Knowledge search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** 会话是否处于人工掌控（等待接管 / 已接管）。 */
    private boolean isAgentControlled(ChatSession session) {
        return session.getStatus() == SessionStatus.AGENT_PROCESSING
                || session.getStatus() == SessionStatus.WAITING_AGENT;
    }

    /** 命中转人工关键词则置 WAITING_AGENT、存消息、关联附件、发事件、广播新待接，返回是否命中。 */
    private boolean triggerKeywordHandoverIfHit(ChatSession session, String message, List<Long> attachmentIds) {
        if (!handoverRuleService.checkKeywordTrigger(message)) {
            return false;
        }
        session.setStatus(SessionStatus.WAITING_AGENT);
        sessionRepository.save(session);
        log.info("关键词触发转人工: sessionId={}", session.getSessionId());
        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.handover_requested", session.getAppId(), session.getTenantId(),
                session.getSessionId(), "keyword", LocalDateTime.now()));
        ChatMessage userMsg = ChatMessage.builder().session(session)
                .role(MessageRole.USER).content(message).build();
        messageRepository.save(userMsg);
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            chatAttachmentService.attachToMessage(attachmentIds, session.getSessionId(), userMsg.getId());
        }
        realtimeNotifier.newWaitingToTenantAgents(session.getTenantId(), session);
        return true;
    }

    /** 人工掌控态：存用户消息、关联附件；若已被坐席接管则实时推给该坐席。 */
    private void persistUserMessageDuringHandover(ChatSession session, String message, List<Long> attachmentIds) {
        ChatMessage userMsg = ChatMessage.builder().session(session)
                .role(MessageRole.USER).content(message).build();
        messageRepository.save(userMsg);
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            chatAttachmentService.attachToMessage(attachmentIds, session.getSessionId(), userMsg.getId());
        }
        if (session.getStatus() == SessionStatus.AGENT_PROCESSING && session.getAgentId() != null) {
            realtimeNotifier.toAgentMessage(session.getTenantId(), session.getAgentId(), userMsg);
        }
    }

    private String buildContext(List<Document> docs) {
        if (docs.isEmpty()) {
            return "暂无相关知识库内容。";
        }
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }
    
    private List<Message> buildMessages(ChatSession session, String userMessage, String context,
                                        ChatStrategy strategy, java.util.List<String> skillMarkdowns,
                                        boolean withCards) {
        List<Message> messages = new ArrayList<>();

        // 系统消息 - 策略绑定模板优先，否则全局激活模板
        String systemTemplate = resolveSystemTemplate(strategy);
        String systemPrompt = systemTemplate.replace("{context}", context);
        systemPrompt = appendSkillMarkdowns(systemPrompt, skillMarkdowns);
        // 结构化卡片能力 Prompt 片段：withCards=true 且 config 开关开启时注入；
        // chat() 与 streamChat() 均传 withCards=true，流式与非流式同语义
        systemPrompt = appendCardPrompt(systemPrompt, withCards, cardsEnabled);
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
     * uiSpec 为结构化卡片 uiParts 的 JSON——chat() 主路与流式主路（经 persistStreamedMessages
     * 回落此方法）均可能非空；两条未命中兜底路径（chat()/streamChat() 各自的 fallback 分支）恒传 null。
     * <p>附件关联（MM-6）：落点是用户消息（附件是用户/坐席发的，不是 AI 生成的），故在
     * userMsg 落库拿到自增 id 后立即挂、早于 AI 消息落库。{@code attachToMessage} 用
     * {@code session.getSessionId()}（服务端持有、已过归属校验）而非任何客户端回传值；
     * 它抛出的 {@code BusinessException} 不在此吞掉——任其向上传播使外层事务回滚，
     * 不能 catch 后继续跑（会在提交时吃 {@code UnexpectedRollbackException}）。
     */
    private ChatMessage saveMessages(ChatSession session, String userMessage, String aiResponse,
                                     double confidence, String hitDocumentIds,
                                     Integer tokenUsage, boolean fallback, String uiSpec,
                                     List<Long> attachmentIds) {
        // 保存用户消息
        ChatMessage userMsg = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(userMessage)
                .build();
        messageRepository.save(userMsg);
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            chatAttachmentService.attachToMessage(attachmentIds, session.getSessionId(), userMsg.getId());
        }

        // 保存AI回复（埋点：命中文档 + token + 兜底标记 + 卡片 JSON）
        ChatMessage aiMsg = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(aiResponse)
                .confidenceScore(confidence)
                .hitDocumentIds(hitDocumentIds)
                .tokenUsage(tokenUsage)
                .fallback(fallback ? Boolean.TRUE : null)
                .uiSpec(uiSpec)
                .build();
        return messageRepository.save(aiMsg);
    }

    /**
     * uiParts 只含单个 text 片段（无卡片，即模型回复未使用 ```spec 围栏）时返回 null，
     * 不落 ui_spec 噪声；含至少一个 ui 片段则序列化为 JSON。fail-soft：序列化异常不抛，仅告警返回 null
     * （不能让卡片序列化失败拖垮整轮对话落库）。
     */
    private String writeUiSpec(List<UiPart> uiParts) {
        boolean hasUi = uiParts.stream().anyMatch(p -> "ui".equals(p.getKind()));
        if (!hasUi) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(uiParts);
        } catch (Exception e) {
            log.warn("uiParts 序列化失败: {}", e.getMessage());
            return null;
        }
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
        // 应用级 list 平台管理员越权收口：无租户上下文（平台管理员）返空页，不裸查（防 userId 猜测跨租户）
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        return sessionRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId, pageable);
    }

    @Override
    public org.springframework.data.domain.Page<ChatSession> getAllSessions(com.qingyu.care.enums.SessionStatus status, org.springframework.data.domain.Pageable pageable) {
        // 应用级 list 平台管理员越权收口：无租户上下文（平台管理员）返空页，不裸查
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        if (status == null) {
            return sessionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        }
        return sessionRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status, pageable);
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
        ChatSession saved = sessionRepository.save(session);

        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.accepted", session.getAppId(), session.getTenantId(),
                session.getSessionId(), null, LocalDateTime.now()));
        realtimeNotifier.toUserSessionEvent(session.getSessionId(), "accepted", "客服已接入，正在为您服务");

        return saved;
    }

    @Override
    @Transactional
    public void agentReply(String sessionId, String message, Long agentId) {
        agentReply(sessionId, message, agentId, null);
    }

    @Override
    @Transactional
    public void agentReply(String sessionId, String message, Long agentId, List<Long> attachmentIds) {
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
        agentMsg = messageRepository.save(agentMsg);
        // 附件关联：落点是这条坐席消息本身（同用户消息路径同语义），用 session.getSessionId()
        // 而非任何客户端回传值；attachToMessage 抛出的异常任其传播，不在此吞掉
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            chatAttachmentService.attachToMessage(attachmentIds, session.getSessionId(), agentMsg.getId());
        }
        log.info("Agent {} replied to session {}", agentId, sessionId);

        eventPublisher.publishEvent(new WebhookDomainEvent(
                "agent.replied", session.getAppId(), session.getTenantId(),
                session.getSessionId(), null, LocalDateTime.now()));
        realtimeNotifier.toUserMessage(session.getSessionId(), agentMsg);
    }

    @Override
    public org.springframework.data.domain.Page<ChatSession> getWaitingAgentSessions(org.springframework.data.domain.Pageable pageable) {
        // 应用级 list 平台管理员越权收口：无租户上下文（平台管理员）返空页，不裸查
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        // appId 非空时按 tenantId+appId 双重收窄（防跨应用查到待接会话）；appId 为空退回仅 tenantId
        // （兼容管理端无 appId 场景，避免炸空）
        Long appId = TenantContext.getAppId();
        if (appId != null) {
            return sessionRepository.findByTenantIdAndAppIdAndStatusOrderByCreatedAtDesc(
                    tenantId, appId, SessionStatus.WAITING_AGENT, pageable);
        }
        return sessionRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(
                tenantId, SessionStatus.WAITING_AGENT, pageable);
    }
    
    @Override
    public java.util.List<ChatMessage> getSessionMessages(String sessionId) {
        ChatSession session = getSession(sessionId);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
    }

    @Override
    @Transactional
    public void returnToAI(String sessionId) {
        ChatSession session = getSession(sessionId);
        if (session.getStatus() != SessionStatus.AGENT_PROCESSING) {
            throw new InvalidOperationException("该会话不在人工处理状态，无法交还 AI：" + session.getStatus());
        }
        session.setStatus(SessionStatus.AI_PROCESSING);
        session.setAgentId(null);
        sessionRepository.save(session);

        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.returned_to_ai", session.getAppId(), session.getTenantId(),
                session.getSessionId(), null, LocalDateTime.now()));
        realtimeNotifier.toUserSessionEvent(sessionId, "returned_to_ai", "已切回智能助手为您服务");
        log.info("Session {} returned to AI by agent", sessionId);
    }
}


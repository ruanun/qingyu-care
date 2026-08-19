package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScope;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.UiSpecService;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：
 * <ul>
 *   <li>persistStreamedMessages 的落库+DONE 事件三分支（正常/空回复/异常），以及围栏原文落库时
 *       经 UiSpecService 解析、content 落纯文本 + ui_spec 落卡片 JSON；</li>
 *   <li>streamChat 完整管道语义（设计稿 §6）：START→DELTA*→DONE 的事件顺序、客户端取消订阅时
 *       落库 Mono 根本不被订阅、模型流失败时降级为固定文案 ERROR 且不落库。</li>
 * </ul>
 * 说明：DONE 事件由 persistStreamedMessages 直接返回（不再走 Sinks 回调），故可脱离 Reactor 直接断言。
 * UiSpecService/ObjectMapper 用真实实例（@Spy 包真对象）——卡片解析正是本用例要验证的行为，
 * mock 会让断言失去意义（同 ChatCardWiringTest 惯例）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class StreamChatEventPipelineTest {

    @Mock VectorStore vectorStore;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock PromptTemplateService promptTemplateService;
    @Mock HandoverRuleService handoverRuleService;
    @Mock ModelRouterService modelRouterService;
    @Mock ChatStrategyService chatStrategyService;
    @Mock HybridRetrievalService hybridRetrievalService;
    @Mock RetrievalScopeResolver retrievalScopeResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock AbTestService abTestService;
    @Mock ConfigService configService;
    @Mock ChatToolService chatToolService;
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock ChatAttachmentService chatAttachmentService;
    @Spy UiSpecService uiSpecService = new UiSpecService(new ObjectMapper());
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ChatServiceImpl service;

    @BeforeEach
    void wireSelf() {
        // streamChat 经 self.searchKnowledge 路由；单测无 Spring，self 需手动指向本实例防 NPE
        service.setSelf(service);
        // streamChat 现要求非空租户上下文（见 ChatServiceImpl#requireTenantContext）
        TenantContext.setTenantId(9L);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 事务模板直通：把 TransactionCallback 当普通函数调用，保持纯 Mockito 单测特性。 */
    private void stubTransactionPassThrough() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    /** 落库时给消息按序分配自增 id（第 1 条用户消息、第 2 条 AI 消息）。 */
    private void stubSaveWithIdSequence() {
        AtomicLong seq = new AtomicLong();
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(seq.incrementAndGet());
            return m;
        });
    }

    /**
     * streamChat 管道测试的公共桩：找到既有会话（AI_PROCESSING，跳过接管/关键词分支）、
     * 空检索范围（跳过实际向量检索）、无 A/B 变体、空历史消息，让管道能一路走到模型调用。
     */
    private ChatSession stubPipelineSession(String sessionId) {
        ChatSession session = ChatSession.builder().sessionId(sessionId)
                .status(SessionStatus.AI_PROCESSING).build();
        session.setTenantId(9L);
        when(sessionRepository.findBySessionId(sessionId)).thenReturn(Optional.of(session));
        when(abTestService.resolveVariantStrategy(any(), any())).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(ChatStrategy.builder().build());
        when(retrievalScopeResolver.resolve()).thenReturn(RetrievalScope.empty(9L));
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("系统提示：{context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        // streamChat 现与 chat() 同源解析工具/Skill（Task 5），管道测试关注事件顺序/落库/降级，
        // 不关心工具装配本身，桩个空 bundle 让 buildMessages/streamWithFallback 正常往下走
        when(chatToolService.resolve()).thenReturn(ChatToolService.ToolBundle.empty());
        return session;
    }

    @Test
    void 正常回复落库后DONE携带AI消息真实id() {
        stubTransactionPassThrough();
        stubSaveWithIdSequence();

        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s1").build(), "问题", "你好", 0.8, "[1,2]");

        assertThat(done.type()).isEqualTo(ChatStreamEvent.Type.DONE);
        // 第 2 次 save 是 AI 消息，id=2；DONE 必须带真实落库 id 而非占位值
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isEqualTo(2L);
    }

    @Test
    void 空回复不落库且DONE的消息id为null() {
        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s2").build(), "问题", "", 0.0, null);

        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isNull();
        verify(messageRepository, never()).save(any());
    }

    @Test
    void 落库异常时fail_soft仍返回DONE不抛() {
        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("DB挂了"));

        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s3").build(), "问题", "你好", 0.5, null);

        assertThat(done.type()).isEqualTo(ChatStreamEvent.Type.DONE);
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isNull();
    }

    @Test
    void 含围栏的原文落库时content剥围栏且ui_spec非空() {
        stubTransactionPassThrough();
        stubSaveWithIdSequence();

        service.persistStreamedMessages(ChatSession.builder().sessionId("s4").build(), "问题",
                "前言\n```spec\n{\"type\":\"card\"}\n```\n后记", 0.9, null);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        ChatMessage aiMsg = captor.getAllValues().stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .findFirst().orElseThrow();
        assertThat(aiMsg.getContent()).doesNotContain("```").contains("前言").contains("后记");
        assertThat(aiMsg.getUiSpec()).contains("\"kind\"").contains("ui");
    }

    @Test
    void 完整一轮_事件顺序为START_DELTA可多个_DONE排在最后() {
        stubPipelineSession("s5");
        stubTransactionPassThrough();
        stubSaveWithIdSequence();
        when(modelRouterService.streamWithFallback(any(), any())).thenReturn(
                Flux.just(SpringAiResponses.of("你好", 5), SpringAiResponses.of("，请问有什么可以帮您", 12)));

        StepVerifier.create(service.streamChat(
                        ChatRequest.builder().sessionId("s5").message("在吗").build(), tokens -> { }, tokens -> { }))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.START))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DELTA))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DELTA))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DONE))
                .verifyComplete();
    }

    /**
     * 契约补齐（Task 7）：主问答路径（检索命中/允许无知识）此刻拿不到真实落库 userMessageId
     * ——该路径的用户消息与 AI 回复整轮一起延迟到流结束才落库（见 persistStreamedMessages
     * 的 rawResponse.isEmpty() 判断），是「停止生成不落库」契约的一部分（见上方两条用例：
     * 客户端取消订阅/模型流失败均不落库）。提前单独落库用户消息来换取这里的真实 id，
     * 会把这两条已钉住的行为一并打破，故本路径的 START 明确记录为已知限制，不是遗漏。
     */
    @Test
    void 主问答路径的START事件userMessageId为null_已知限制() {
        stubPipelineSession("s10");
        stubTransactionPassThrough();
        stubSaveWithIdSequence();
        when(modelRouterService.streamWithFallback(any(), any())).thenReturn(
                Flux.just(SpringAiResponses.of("你好", 5)));

        StepVerifier.create(service.streamChat(
                        ChatRequest.builder().sessionId("s10").message("在吗").build(), tokens -> { }, tokens -> { }))
                .assertNext(e -> assertThat(((ChatStreamEvent.Start) e.payload()).userMessageId()).isNull())
                .thenConsumeWhile(e -> true)
                .verifyComplete();
    }

    @Test
    void 客户端取消订阅_落库Mono不被订阅_不落库() {
        stubPipelineSession("s6");
        // 必须打通落库路径的真实桩（同其余用例），否则 verify(never()).save() 是恒真断言：
        // transactionTemplate 若不桩，Mockito 对未桩方法直接返回 null——persistStreamedMessages
        // 即便真被误订阅执行，也会在 transactionTemplate.execute(...) 这一步就短路返回 null，
        // 根本走不到 messageRepository.save，never() 测不出「误订阅」与「正确取消」的区别。
        // 标 lenient：这正是本用例要证明的——cancel 后这两个桩预期从未被触发，Mockito 严格模式
        // 默认会把「配置了却未被调用」的桩报成 UnnecessaryStubbingException；这个异常本身就是
        // 「persistStreamedMessages 确实没执行」的独立佐证（保留 lenient 只是不让它使测试意外挂掉）。
        AtomicLong idSeq = new AtomicLong();
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        lenient().when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(idSeq.incrementAndGet());
            return m;
        });
        when(modelRouterService.streamWithFallback(any(), any())).thenReturn(
                Flux.just(SpringAiResponses.of("你好", 5)));

        StepVerifier.create(service.streamChat(
                        ChatRequest.builder().sessionId("s6").message("在吗").build(), tokens -> { }, tokens -> { }))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.START))
                .thenCancel()
                .verify(Duration.ofSeconds(2));

        verify(messageRepository, never()).save(any());
    }

    @Test
    void 模型流失败_降级为固定文案ERROR事件_不落库_不泄露原始异常() {
        stubPipelineSession("s7");
        when(modelRouterService.streamWithFallback(any(), any()))
                .thenReturn(Flux.error(new RuntimeException("上游 502，内部主机 10.0.0.7 不可达")));

        List<ChatStreamEvent> events = service.streamChat(
                        ChatRequest.builder().sessionId("s7").message("在吗").build(), tokens -> { }, tokens -> { })
                .collectList().block();

        // START 在模型调用之前已同步构造好、与失败与否无关，故仍会先送达；
        // 断言的重点是「失败后不再有 DELTA/CARD/DONE，only ERROR 收尾、文案固定、不落库」
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(ChatStreamEvent.Type.START);
        assertThat(events.get(1).type()).isEqualTo(ChatStreamEvent.Type.ERROR);
        String message = ((ChatStreamEvent.Error) events.get(1).payload()).message();
        assertThat(message).isEqualTo("回复生成失败，请稍后重试");
        assertThat(message).doesNotContain("502").doesNotContain("10.0.0.7");
        verify(messageRepository, never()).save(any());
    }

    /**
     * MM-6：流式主路径落库最终经 persistStreamedMessages 完成（Reactor 线程，无 TenantContext）。
     * 落点仍是用户消息，且必须在调用 attachToMessage 那一刻把请求线程捕获的租户上下文正确恢复——
     * 否则 TenantOwnershipGuard.mutableInCurrentContext 在 ctxTenantId 为 null 时无条件放行，
     * 归属校验形同虚设（本仓库 fallbackToNonStreaming 已有的同类教训）。
     * <p>I-1（终审发现）：source 必须显式 {@code .publishOn(Schedulers.boundedElastic())}——
     * 不加的话，{@code transactionTemplate} 是直通桩、{@code source} 是 {@code Flux.just(...)}，
     * 整条链会同步跑在**本测试自己的线程**上，而 {@code @BeforeEach} 早已在这个线程上
     * {@code setTenantId(9L)}；此时即便把生产代码里 {@code persistStreamedMessages} 的三行
     * {@code TenantContext.set*} 整段删掉，探针读到的仍是测试线程遗留的 9L——测试只守住了
     * "调用了 attachToMessage"，守不住"租户上下文真的是搬运过去的而不是巧合地读到了别的线程
     * 遗留值"。加上 publishOn 后落库那段真的跑在另一个线程，该线程没有测试的 @BeforeEach
     * 遗留值，删掉三行 set 后探针会读到 null，断言随之转红——这才是本用例要守护的核心。
     * <p>不再断言"方法返回后 TenantContext 清空"：restore 逻辑现在跑在 boundedElastic 线程上
     * （M-2 改为恢复原值而非无条件 clear），测试线程的 TenantContext 从未被那段代码触碰，
     * 断言它是否清空测的是错误的线程、恒为 9L（@BeforeEach 遗留），不具判别力。
     */
    @Test
    void 流式完整落库时用用户消息id关联附件_且恢复了请求线程的租户上下文() {
        stubPipelineSession("s8");
        stubTransactionPassThrough();
        stubSaveWithIdSequence();
        when(modelRouterService.streamWithFallback(any(), any())).thenReturn(
                Flux.just(SpringAiResponses.of("好的，收到", 5))
                        .publishOn(reactor.core.scheduler.Schedulers.boundedElastic()));

        List<Long> tenantIdSeenByGuard = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            tenantIdSeenByGuard.add(TenantContext.getTenantId());
            return null;
        }).when(chatAttachmentService).attachToMessage(any(), any(), any());

        StepVerifier.create(service.streamChat(
                        ChatRequest.builder().sessionId("s8").message("这是报错截图")
                                .attachmentIds(List.of(7L)).build(),
                        tokens -> { }, tokens -> { }))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.START))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DELTA))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DONE))
                .verifyComplete();

        // 第 1 条 save 是用户消息（id=1），落点必须是它，不是随后的 AI 消息（id=2）
        verify(chatAttachmentService).attachToMessage(eq(List.of(7L)), eq("s8"), eq(1L));
        // 在 boundedElastic 线程上执行 attachToMessage 时，租户上下文已被正确搬运为请求线程原有的 9L
        // （该线程本身没有这个值——不搬运就会读到 null，见上方 javadoc）
        assertThat(tenantIdSeenByGuard).containsExactly(9L);
    }

    @Test
    void 流式完整落库时没带附件_不调用关联_也不触碰租户上下文() {
        stubPipelineSession("s9");
        stubTransactionPassThrough();
        stubSaveWithIdSequence();
        when(modelRouterService.streamWithFallback(any(), any())).thenReturn(
                Flux.just(SpringAiResponses.of("你好", 5)));

        StepVerifier.create(service.streamChat(
                        ChatRequest.builder().sessionId("s9").message("你好").build(),
                        tokens -> { }, tokens -> { }))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.START))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DELTA))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DONE))
                .verifyComplete();

        verify(chatAttachmentService, never()).attachToMessage(any(), any(), any());
    }

    /**
     * C-1 预校验：附件非法（存在性/租户归属/会话归属）时 streamChat 必须在流开始前
     * **同步抛出**，不能进入 Flux 管道——否则用户要等一整轮回复流完才在 DONE 处静默失去这条对话
     * （见 persistStreamedMessages 的 fail-soft 兜底）。同步抛出意味着模型压根不会被调用。
     */
    @Test
    void 附件预校验失败时streamChat同步抛出_不进入模型调用() {
        ChatSession session = ChatSession.builder().sessionId("s12")
                .status(SessionStatus.AI_PROCESSING).build();
        session.setTenantId(9L);
        when(sessionRepository.findBySessionId("s12")).thenReturn(Optional.of(session));
        org.mockito.Mockito.doThrow(new com.qingyu.care.exception.BusinessException("附件不属于当前会话"))
                .when(chatAttachmentService).validateAttachable(eq(List.of(99L)), eq("s12"));

        assertThatThrownBy(() -> service.streamChat(
                ChatRequest.builder().sessionId("s12").message("图在这")
                        .attachmentIds(List.of(99L)).build(),
                tokens -> { }, tokens -> { }))
                .isInstanceOf(com.qingyu.care.exception.BusinessException.class)
                .hasMessageContaining("附件不属于当前会话");

        verify(modelRouterService, never()).streamWithFallback(any(), any());
    }

    /**
     * C-1 fail-soft：即便过了预校验，真正 attachToMessage 那一刻仍可能失败（如同一批附件被
     * 并发重复提交的残余竞态）。这种失败不能连累整轮已经流式吐给用户的回复——DONE 事件必须
     * 仍带真实 messageId（消息正常落库），而不是本仓库既有 fail-soft catch 兜底出的 null。
     * <p>⚠️ 本用例的判别力边界（复核终审明确指出的坑）：{@code chatAttachmentService} 是
     * mock、{@code transactionTemplate} 是直通桩，纯 Mockito 环境下没有真实
     * {@code JpaTransactionManager}，感知不到"参与型事务被标记 rollback-only"这件事——
     * 无论 {@code saveMessages} 是直调 {@code chatAttachmentService.attachToMessage} 还是
     * 经 {@code self.attachInNewTransaction} 走 REQUIRES_NEW，本用例都会一样通过，测不出
     * 两者的关键差异。本用例只守护"异常被捕获、不向外传播、DONE 携带真实 id"这一层
     * wiring；REQUIRES_NEW 真的隔离了参与型事务这件事，由真实事务的
     * {@code StreamAttachmentFailSoftIT}（it 包，Testcontainers 真实 PG）兜底验证——
     * 那条 IT 才是本坑唯一有效的判别力来源。
     */
    @Test
    void 流式主路径附件关联失败时对话仍正常落库_不因附件失败丢失整轮回复() {
        stubPipelineSession("s13");
        stubTransactionPassThrough();
        stubSaveWithIdSequence();
        when(modelRouterService.streamWithFallback(any(), any())).thenReturn(
                Flux.just(SpringAiResponses.of("已收到", 5))
                        .publishOn(reactor.core.scheduler.Schedulers.boundedElastic()));
        org.mockito.Mockito.doThrow(new com.qingyu.care.exception.BusinessException("附件已被使用"))
                .when(chatAttachmentService).attachToMessage(any(), any(), any());

        java.util.concurrent.atomic.AtomicReference<ChatStreamEvent> doneEvent =
                new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(service.streamChat(
                        ChatRequest.builder().sessionId("s13").message("图在这")
                                .attachmentIds(List.of(7L)).build(),
                        tokens -> { }, tokens -> { }))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.START))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DELTA))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DONE);
                    doneEvent.set(e);
                })
                .verifyComplete();

        // 附件关联失败被降级为 fail-soft：DONE 事件仍带真实 messageId，不是"整轮丢失"的 null
        assertThat(((ChatStreamEvent.Done) doneEvent.get().payload()).messageId()).isNotNull();
        // userMsg + aiMsg 两条都正常落库了
        verify(messageRepository, times(2)).save(any());
    }
}

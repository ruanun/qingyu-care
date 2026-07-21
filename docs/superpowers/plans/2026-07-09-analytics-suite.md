# 分析三件套（质量评估 + 热点统计 + 知识覆盖率）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **约束传递（必须原样传给子代理）**：本仓库允许 `git commit`（当前在特性分支 `feature/phase4-analytics-suite`），**禁止 `git push`**；全部中文（注释/commit）；author 用 `ruan`；产出以 `mvn clean install` 兜底验证。

**Goal:** 落地 Phase 4.4 分析三件套：对话质量评估（规则指标 + 消息级赞/踩）、热点问题统计（zhparser 词频 + 高频问句）、知识覆盖率（问题侧 + 文档侧），以及配套的埋点补齐（流式落库、命中文档、兜底标记）。

**Architecture:** 方案 B——日粒度 rollup 预聚合：`chat_message`/`chat_session` 埋点 → `AnalyticsRollupScheduler` 每日先删后插三张 stat 表（幂等）→ `AnalyticsController` 查询端点读 stat 表 + 少量实时明细 → 前端 `UsagePage` Tab 化。stat 实体不继承 `BaseTenantEntity`（定时线程无 TenantContext，沿用 `SysApiUsage` 模式）。

**Tech Stack:** Spring Boot 3 + JPA(ddl-auto update) + JdbcTemplate native SQL + zhparser(`chinese_zh`) + Testcontainers IT；前端 React 19 + recharts；Widget 原生 TS。

**Spec:** `docs/superpowers/specs/2026-07-09-analytics-suite-design.md`

---

## 文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `backend/.../entity/ChatMessage.java` | 改 | 加 `hitDocumentIds`/`feedback`/`fallback` 三列 |
| `backend/.../dto/ChatResponse.java`、`dto/open/OpenChatResponse.java` | 改 | 加 `messageId` |
| `backend/.../service/impl/ChatServiceImpl.java` | 改 | `extractHitDocumentIds`/`withStreamPersistence` 纯方法、`saveMessages` 扩展、兜底落库、流式落库 |
| `backend/.../service/ChatService.java` | 改 | 三参 `streamChat` 重载 |
| `backend/.../controller/open/OpenChatController.java` | 改 | SSE meta 事件 + `/feedback` 端点 |
| `backend/.../service/ChatFeedbackService.java` | 新 | 反馈提交（校验归属） |
| `backend/.../dto/open/OpenFeedbackRequest.java` | 新 | 开放反馈请求体 |
| `backend/.../controller/ChatController.java` | 改 | 后台反馈端点 |
| `backend/.../entity/StatQualityDaily.java`、`StatHotKeywordDaily.java`、`StatDocHitDaily.java` | 新 | rollup 三表 |
| `backend/.../repository/AnalyticsRollupDao.java` | 新 | 聚合 SQL（先删后插） |
| `backend/.../service/AnalyticsRollupService.java` | 新 | rollup 编排（TransactionTemplate） |
| `backend/.../scheduler/AnalyticsRollupScheduler.java` | 新 | 每日定时（近 2 天） |
| `backend/.../repository/StatQualityDailyRepository.java` 等 3 个 + projection 6 个 | 新 | 查询侧 |
| `backend/.../repository/ChatMessageRepository.java`、`DocumentRepository.java` | 改 | 高频问句 / 未命中明细 / 零命中文档 native 查询 |
| `backend/.../dto/QualityAnalyticsDTO.java`、`HotTopicsDTO.java`、`CoverageAnalyticsDTO.java`、`MissedQuestionDTO.java` | 新 | 看板 DTO |
| `backend/.../service/AnalyticsService.java`、`controller/AnalyticsController.java` | 改 | 查询方法 + 5 个端点 |
| `frontend/src/api/analytics.ts`、`api/chat.ts` | 改 | 新端点封装 |
| `frontend/src/pages/UsagePage.tsx` | 改 | Tab 化为数据分析页 |
| `frontend/src/pages/analytics/QualityTab.tsx`、`HotTopicsTab.tsx`、`CoverageTab.tsx` | 新 | 三个看板 Tab |
| `frontend/src/components/SessionMessages.tsx` | 改 | 后台人工赞/踩标注 |
| `widget/src/api.ts`、`ui.ts`、`main.ts`、`styles.ts` | 改 | 赞/踩按钮（须重新 build 提交 `widget.js`） |
| IT：`AnalyticsRollupIT`、`ChatFeedbackIT`、`StreamPersistenceIT` | 新 | 回归网 |

执行顺序：Task 1→2→3 同文件链必须顺序做；Task 4→5 后端聚合链；Task 6 依赖 1-5；Task 7/8 前端与 Widget 可在 6 后并行；Task 9 收尾。

---

### Task 1: ChatMessage 埋点字段 + 命中提取纯方法 + messageId 回传

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/ChatMessage.java`
- Modify: `backend/src/main/java/com/qingyu/care/dto/ChatResponse.java`
- Modify: `backend/src/main/java/com/qingyu/care/dto/open/OpenChatResponse.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/service/impl/ChatAnalyticsInstrumentationTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：extractHitDocumentIds 纯方法（static package-private，同 meterContentStream 惯例）。
 *
 * @author ruan
 */
class ChatAnalyticsInstrumentationTest {

    @Test
    void 提取命中文档ID_去重升序为JSON数组() {
        Document d1 = new Document("c1", Map.of("documentId", "12"));
        Document d2 = new Document("c2", Map.of("documentId", "3"));
        Document d3 = new Document("c3", Map.of("documentId", "12"));
        assertThat(ChatServiceImpl.extractHitDocumentIds(List.of(d1, d2, d3))).isEqualTo("[3,12]");
    }

    @Test
    void 空命中或缺键返回null() {
        assertThat(ChatServiceImpl.extractHitDocumentIds(List.of())).isNull();
        Document noKey = new Document("c", Map.of("source", "x"));
        assertThat(ChatServiceImpl.extractHitDocumentIds(List.of(noKey))).isNull();
    }

    @Test
    void 非数字documentId被忽略() {
        Document bad = new Document("c", Map.of("documentId", "abc"));
        Document good = new Document("c", Map.of("documentId", "7"));
        assertThat(ChatServiceImpl.extractHitDocumentIds(List.of(bad, good))).isEqualTo("[7]");
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `cd backend && mvn test -Dtest=ChatAnalyticsInstrumentationTest`
Expected: 编译错误 `找不到符号 extractHitDocumentIds`

- [ ] **Step 3: 实体与 DTO 加字段**

`ChatMessage.java` 在 `tokenUsage` 字段后追加：

```java
    /**
     * 命中的知识文档 IDs（JSON 数组，如 [3,12]；null = 未命中/未检索。覆盖率口径：非空即命中）
     */
    @Column(name = "hit_document_ids", length = 1000)
    private String hitDocumentIds;

    /**
     * 用户反馈：1=赞 / -1=踩 / null=未评价（重复提交覆盖）
     */
    @Column(name = "feedback")
    private Integer feedback;

    /**
     * 兜底回复标记（知识未命中且策略不允许自由发挥时返回 fallbackMessage 的 AI 消息为 TRUE，其余为 null）
     */
    @Column(name = "fallback")
    private Boolean fallback;
```

`ChatResponse.java` 在 `sessionId` 字段后追加：

```java
    /**
     * 落库后的 AI 消息 ID（供反馈接口定位；静默/转人工提示等未落 AI 消息的路径为 null）
     */
    private Long messageId;
```

`OpenChatResponse.java` 加字段并改 `from`：

```java
    /**
     * AI 消息 ID（提交赞/踩反馈时使用）
     */
    private Long messageId;
```

```java
    public static OpenChatResponse from(ChatResponse r) {
        return OpenChatResponse.builder()
                .sessionId(r.getSessionId())
                .messageId(r.getMessageId())
                .reply(r.getContent())
                .handoverSuggested(r.isSuggestHandover())
                .build();
    }
```

- [ ] **Step 4: ChatServiceImpl 实现纯方法 + saveMessages 扩展 + chat() 埋点**

在 `resolveConfidenceScore` 方法后新增：

```java
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
```

替换整个 `saveMessages` 方法（原签名 4 参 → 7 参，返回 AI 消息）：

```java
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
```

`chat()` 兜底早退分支（原来不落 AI 消息，是 spec 指出的缺口）整体替换为：

```java
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
```

`chat()` 正常路径的 `saveMessages` 调用与响应构建改为：

```java
        // 保存消息（埋点：命中文档 + token 用量）
        String hitDocumentIds = extractHitDocumentIds(relevantDocs);
        ChatMessage aiMsg = saveMessages(session, request.getMessage(), response, maxScore,
                hitDocumentIds, tokenUsage, false);
```

并在其响应 builder 中 `.sessionId(...)` 后追加一行 `.messageId(aiMsg.getId())`。

- [ ] **Step 5: 跑测试确认通过 + 全量单测无回归**

Run: `cd backend && mvn test -Dtest=ChatAnalyticsInstrumentationTest`
Expected: 3 个测试 PASS
Run: `cd backend && mvn test`
Expected: BUILD SUCCESS（`MeterContentStreamTest` 等既有单测不受影响）

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/ChatMessage.java backend/src/main/java/com/qingyu/care/dto/ChatResponse.java backend/src/main/java/com/qingyu/care/dto/open/OpenChatResponse.java backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java backend/src/test/java/com/qingyu/care/service/impl/ChatAnalyticsInstrumentationTest.java
git commit -m "feat: 对话埋点（命中文档ID/反馈/兜底标记落库 + messageId 回传）"
```

---

### Task 2: 流式补落库 + SSE meta 事件

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatService.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`
- Test: `backend/src/test/java/com/qingyu/care/service/impl/ChatStreamPersistenceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service.impl;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单元测试：withStreamPersistence 纯方法（内容透传 + 完整文本仅在正常完成时回调）。
 *
 * @author ruan
 */
class ChatStreamPersistenceTest {

    @Test
    void 流正常完成_以完整聚合文本回调() {
        AtomicReference<String> full = new AtomicReference<>();
        List<String> out = ChatServiceImpl.withStreamPersistence(Flux.just("你", "好"), full::set)
                .collectList().block();
        assertThat(out).containsExactly("你", "好");
        assertThat(full.get()).isEqualTo("你好");
    }

    @Test
    void 流错误_不回调() {
        AtomicReference<String> full = new AtomicReference<>();
        Flux<String> f = ChatServiceImpl.withStreamPersistence(
                Flux.concat(Flux.just("半"), Flux.error(new RuntimeException("模型中断"))), full::set);
        assertThatThrownBy(f::blockLast).hasMessageContaining("模型中断");
        assertThat(full.get()).isNull();
    }

    @Test
    void 空流完成_以空串回调() {
        AtomicReference<String> full = new AtomicReference<>();
        ChatServiceImpl.withStreamPersistence(Flux.empty(), full::set).collectList().block();
        assertThat(full.get()).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `cd backend && mvn test -Dtest=ChatStreamPersistenceTest`
Expected: 编译错误 `找不到符号 withStreamPersistence`

- [ ] **Step 3: 接口加三参重载**

`ChatService.java` 在两参 `streamChat` 声明后追加：

```java
    /**
     * 流式聊天（计量 + 落库回调）。onAiMessageSaved 在流正常完成、消息落库后以 AI 消息 ID 回调；
     * 落库失败或空回复以 -1 回调（回调必达，SSE meta 事件不悬挂）。error/cancel 不落库不回调。
     */
    reactor.core.publisher.Flux<String> streamChat(com.qingyu.care.dto.ChatRequest request,
                                                   java.util.function.IntConsumer onTokensUsed,
                                                   java.util.function.LongConsumer onAiMessageSaved);
```

- [ ] **Step 4: ChatServiceImpl 实现**

原两参实现改为委派，并新增三参实现与落库私有方法。原 `streamChat(request, onTokensUsed)` 方法体整体替换为：

```java
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
```

`meterContentStream` 后新增两个方法：

```java
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
     */
    private void persistStreamedMessages(ChatSession session, String userMessage, String aiResponse,
                                         double confidence, String hitDocumentIds,
                                         java.util.function.LongConsumer onAiMessageSaved) {
        long savedId = -1L;
        try {
            if (aiResponse != null && !aiResponse.isEmpty()) {
                ChatMessage aiMsg = saveMessages(session, userMessage, aiResponse,
                        confidence, hitDocumentIds, null, false);
                savedId = aiMsg.getId();
            }
        } catch (Exception e) {
            log.warn("流式消息落库失败 sessionId={}: {}", session.getSessionId(), e.getMessage());
        }
        onAiMessageSaved.accept(savedId);
    }
```

- [ ] **Step 5: OpenChatController /stream 改造（meta 事件）**

`stream` 方法整体替换（新增 import：`org.springframework.http.codec.ServerSentEvent`、`reactor.core.publisher.Mono`、`reactor.core.publisher.Sinks`）：

```java
    /**
     * 流式对话（SSE）。正文为默认（message）事件、payload 不变；流末尾追加 event:meta
     * 携带落库后的 messageId（旧客户端不识别 meta 事件自动忽略，向后兼容）。
     * 流结束时经计量回调异步记录用量（计量保持开放 API 专属）。
     * POST /open/v1/chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody OpenChatRequest req) {
        // 必须在请求线程（Reactor 订阅前）捕获上下文，回调运行在非 HTTP 线程中无上下文
        final Long tid = TenantContext.getTenantId();
        final Long aid = TenantContext.getAppId();
        // Sinks.one 缓存落库回调的 messageId；正文完成后 concat 出 meta 事件。
        // 回调必达（service 失败时回 -1），filter 挡掉无效 ID → meta Mono 完成为空，SSE 不悬挂
        Sinks.One<Long> messageIdSink = Sinks.one();
        Flux<String> content = chatService.streamChat(toInternal(req, true),
                tokens -> apiUsageService.record(tid, aid, 1L, tokens),
                messageIdSink::tryEmitValue);
        Flux<ServerSentEvent<String>> body = content.map(c -> ServerSentEvent.builder(c).build());
        Mono<ServerSentEvent<String>> meta = messageIdSink.asMono()
                .filter(id -> id > 0)
                .map(id -> ServerSentEvent.<String>builder("{\"messageId\":" + id + "}")
                        .event("meta").build());
        return Flux.concat(body, meta);
    }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=ChatStreamPersistenceTest,MeterContentStreamTest`
Expected: 全部 PASS
Run: `cd backend && mvn test`
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatService.java backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java backend/src/test/java/com/qingyu/care/service/impl/ChatStreamPersistenceTest.java
git commit -m "feat: 流式对话补消息落库 + SSE 末尾 meta 事件回传 messageId"
```

---

### Task 3: 消息反馈服务与端点（开放 + 后台）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/ChatFeedbackService.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/open/OpenFeedbackRequest.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ChatFeedbackServiceTest.java`

- [ ] **Step 0: 核对 TenantContext 静态方法名**

Run: `grep -n "public static" backend/src/main/java/com/qingyu/care/config/TenantContext.java`
Expected: 存在 `getAppId()`/`setAppId(Long)`/`clear()`（命名不同则同步调整下面代码）

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：反馈提交的参数与归属校验分支。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatFeedbackServiceTest {

    @Mock
    private ChatMessageRepository messageRepository;
    @InjectMocks
    private ChatFeedbackService service;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    private ChatMessage aiMessage(String sessionId, Long appId) {
        ChatSession session = ChatSession.builder().sessionId(sessionId).build();
        session.setAppId(appId);
        return ChatMessage.builder().id(10L).session(session)
                .role(MessageRole.ASSISTANT).content("回复").build();
    }

    @Test
    void 合法反馈_落库覆盖() {
        ChatMessage msg = aiMessage("s-1", 100L);
        when(messageRepository.findById(10L)).thenReturn(Optional.of(msg));

        service.submitFeedback("s-1", 10L, 1);

        assertThat(msg.getFeedback()).isEqualTo(1);
        verify(messageRepository).save(msg);
    }

    @Test
    void 非法值_拒绝() {
        assertThatThrownBy(() -> service.submitFeedback("s-1", 10L, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 会话不匹配_按404处理() {
        when(messageRepository.findById(10L)).thenReturn(Optional.of(aiMessage("s-1", 100L)));
        assertThatThrownBy(() -> service.submitFeedback("s-其他", 10L, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 跨应用上下文_按404处理() {
        when(messageRepository.findById(10L)).thenReturn(Optional.of(aiMessage("s-1", 100L)));
        TenantContext.setAppId(999L);
        assertThatThrownBy(() -> service.submitFeedback("s-1", 10L, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 用户消息不可评价() {
        ChatSession session = ChatSession.builder().sessionId("s-1").build();
        ChatMessage userMsg = ChatMessage.builder().id(11L).session(session)
                .role(MessageRole.USER).content("提问").build();
        when(messageRepository.findById(11L)).thenReturn(Optional.of(userMsg));
        assertThatThrownBy(() -> service.submitFeedback("s-1", 11L, -1))
                .isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `cd backend && mvn test -Dtest=ChatFeedbackServiceTest`
Expected: 编译错误 `找不到符号 ChatFeedbackService`

- [ ] **Step 3: 实现服务**

`ChatFeedbackService.java`（`service/` 下单类，不加接口——项目约定只有复杂服务才拆接口）：

```java
package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息级反馈（赞/踩）。单一职责小服务，不并入已复杂的 ChatService。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class ChatFeedbackService {

    private final ChatMessageRepository messageRepository;

    /**
     * 提交反馈（1=赞 / -1=踩，重复提交覆盖）。
     * 归属校验：消息须为 AI 回复且属于给定会话；有应用上下文时会话须属于当前应用——
     * ChatMessage 非租户过滤实体，findById 不受过滤器保护，显式校验闭合跨会话/跨租户投毒。
     * 越权一律按 404 处理（不泄露消息是否存在）。
     */
    @Transactional
    public void submitFeedback(String sessionId, Long messageId, int feedback) {
        if (feedback != 1 && feedback != -1) {
            throw new BusinessException("feedback 仅允许 1（赞）或 -1（踩）");
        }
        ChatMessage msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("消息", String.valueOf(messageId)));
        ChatSession session = msg.getSession();
        if (session == null || !session.getSessionId().equals(sessionId)) {
            throw new ResourceNotFoundException("消息", String.valueOf(messageId));
        }
        Long ctxAppId = TenantContext.getAppId();
        if (ctxAppId != null && session.getAppId() != null && !ctxAppId.equals(session.getAppId())) {
            throw new ResourceNotFoundException("消息", String.valueOf(messageId));
        }
        if (msg.getRole() != MessageRole.ASSISTANT) {
            throw new BusinessException("仅可评价 AI 回复消息");
        }
        msg.setFeedback(feedback);
        messageRepository.save(msg);
    }
}
```

注意 `ResourceNotFoundException` 构造为 `(String 资源名, String 标识)`（与 `ChatServiceImpl.getSession` 用法一致；若签名不同按现有类调整）。

- [ ] **Step 4: 开放端点**

`OpenFeedbackRequest.java`：

```java
package com.qingyu.care.dto.open;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 开放 API 消息反馈请求。
 *
 * @author ruan
 */
@Data
public class OpenFeedbackRequest {

    /** 会话 ID */
    @NotBlank
    private String sessionId;

    /** AI 消息 ID（completions 响应 / SSE meta 事件返回） */
    @NotNull
    private Long messageId;

    /** 1=赞 / -1=踩 */
    @NotNull
    private Integer feedback;
}
```

`OpenChatController` 注入 `private final ChatFeedbackService chatFeedbackService;` 并新增端点：

```java
    /**
     * 消息反馈（赞/踩，重复提交覆盖）。POST /open/v1/chat/feedback
     */
    @PostMapping("/feedback")
    public java.util.Map<String, Object> feedback(@Valid @RequestBody OpenFeedbackRequest req) {
        chatFeedbackService.submitFeedback(req.getSessionId(), req.getMessageId(), req.getFeedback());
        return java.util.Map.of("success", true);
    }
```

- [ ] **Step 5: 后台端点**

`ChatController` 注入 `private final ChatFeedbackService chatFeedbackService;` 并新增：

```java
    @PostMapping("/session/{sessionId}/message/{messageId}/feedback")
    @Operation(summary = "评价消息", description = "对 AI 回复打赞/踩（feedback ∈ {1,-1}，重复提交覆盖）")
    public ApiResponse<Void> feedback(
            @PathVariable String sessionId,
            @PathVariable Long messageId,
            @RequestParam int feedback) {
        chatFeedbackService.submitFeedback(sessionId, messageId, feedback);
        return ApiResponse.success("反馈已记录", null);
    }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=ChatFeedbackServiceTest`
Expected: 5 个测试 PASS

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatFeedbackService.java backend/src/main/java/com/qingyu/care/dto/open/OpenFeedbackRequest.java backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java backend/src/main/java/com/qingyu/care/controller/ChatController.java backend/src/test/java/com/qingyu/care/service/ChatFeedbackServiceTest.java
git commit -m "feat: 消息级赞/踩反馈（开放 API + 管理后台端点，归属校验防投毒）"
```

---

### Task 4: rollup 三表 + 聚合 DAO + 编排服务 + 定时任务 + 回填端点

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/entity/StatQualityDaily.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/StatHotKeywordDaily.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/StatDocHitDaily.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/AnalyticsRollupDao.java`
- Create: `backend/src/main/java/com/qingyu/care/service/AnalyticsRollupService.java`
- Create: `backend/src/main/java/com/qingyu/care/scheduler/AnalyticsRollupScheduler.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/AnalyticsController.java`
- Test: `backend/src/test/java/com/qingyu/care/service/AnalyticsRollupServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AnalyticsRollupDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 单元测试：rollup 编排（先删后插顺序、区间校验、单日失败不阻断）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsRollupServiceTest {

    @Mock
    private AnalyticsRollupDao rollupDao;
    @Mock
    private TransactionTemplate transactionTemplate;
    @InjectMocks
    private AnalyticsRollupService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void passThroughTx() {
        // TransactionTemplate 打桩为直通执行（单测不验证事务语义）
        lenient().doAnswer(inv -> {
            inv.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void 单日聚合_先删后插() {
        LocalDate day = LocalDate.of(2026, 7, 8);
        service.rollupDay(day);
        var order = inOrder(rollupDao);
        order.verify(rollupDao).deleteDay(day);
        order.verify(rollupDao).insertQuality(day);
        order.verify(rollupDao).insertHotKeywords(eq(day), anyInt());
        order.verify(rollupDao).insertDocHits(day);
    }

    @Test
    void 回填_单日失败继续后续日期() {
        doThrow(new RuntimeException("boom")).when(rollupDao).insertQuality(LocalDate.of(2026, 7, 2));
        int ok = service.backfill(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3));
        assertThat(ok).isEqualTo(2);   // 7/1、7/3 成功，7/2 失败跳过
        verify(rollupDao, times(3)).deleteDay(any());
    }

    @Test
    void 回填_区间非法或过长拒绝() {
        assertThatThrownBy(() -> service.backfill(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.backfill(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `cd backend && mvn test -Dtest=AnalyticsRollupServiceTest`
Expected: 编译错误（类不存在）

- [ ] **Step 3: 三张 stat 实体**

`StatQualityDaily.java`：

```java
package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 对话质量日统计（rollup 预聚合）。不继承 BaseTenantEntity：
 * 定时任务线程无 TenantContext，显式持有租户/应用字段（同 SysApiUsage 模式），查询侧手写 where。
 *
 * @author ruan
 */
@Entity
@Table(name = "stat_quality_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_quality_tenant_app_date",
                columnNames = {"tenant_id", "app_id", "stat_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatQualityDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    /** AI 回复数（role=ASSISTANT 且置信度非空，排除人工客服消息） */
    @Column(name = "ai_msg_count", nullable = false)
    @Builder.Default
    private long aiMsgCount = 0;

    /** 当日平均置信度（无 AI 回复时 null） */
    @Column(name = "avg_confidence")
    private Double avgConfidence;

    /** 低置信回复数（confidence < 0.5 固定口径，保证历史可比） */
    @Column(name = "low_confidence_count", nullable = false)
    @Builder.Default
    private long lowConfidenceCount = 0;

    /** 兜底回复数（fallback = TRUE） */
    @Column(name = "fallback_count", nullable = false)
    @Builder.Default
    private long fallbackCount = 0;

    /** 用户提问数（role=USER） */
    @Column(name = "question_count", nullable = false)
    @Builder.Default
    private long questionCount = 0;

    /** 命中知识的 AI 回复数（hit_document_ids 非空） */
    @Column(name = "hit_question_count", nullable = false)
    @Builder.Default
    private long hitQuestionCount = 0;

    @Column(name = "feedback_up", nullable = false)
    @Builder.Default
    private long feedbackUp = 0;

    @Column(name = "feedback_down", nullable = false)
    @Builder.Default
    private long feedbackDown = 0;

    /** 当日新建会话数 */
    @Column(name = "session_count", nullable = false)
    @Builder.Default
    private long sessionCount = 0;

    /** 转人工会话数（handover_time 非空） */
    @Column(name = "handover_count", nullable = false)
    @Builder.Default
    private long handoverCount = 0;

    /** AI 解决数（ENDED 且无人工 agent_id） */
    @Column(name = "resolved_count", nullable = false)
    @Builder.Default
    private long resolvedCount = 0;

    @Column(name = "ended_count", nullable = false)
    @Builder.Default
    private long endedCount = 0;
}
```

`StatHotKeywordDaily.java`：

```java
package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 热点关键词日统计（rollup 预聚合，每租户+应用+日仅存 TopN）。
 * 不继承 BaseTenantEntity（同 SysApiUsage 模式）。
 *
 * @author ruan
 */
@Entity
@Table(name = "stat_hot_keyword_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_hot_keyword_tenant_app_date_kw",
                columnNames = {"tenant_id", "app_id", "stat_date", "keyword"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatHotKeywordDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    /** zhparser 分词后的词元（单字词与超长词已在聚合 SQL 过滤） */
    @Column(name = "keyword", nullable = false, length = 100)
    private String keyword;

    /** 当日词频 */
    @Column(name = "freq", nullable = false)
    private long freq;
}
```

`StatDocHitDaily.java`：

```java
package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 文档命中日统计（rollup 预聚合，来自 AI 消息 hit_document_ids 展开）。
 * 不继承 BaseTenantEntity（同 SysApiUsage 模式）。
 *
 * @author ruan
 */
@Entity
@Table(name = "stat_doc_hit_daily",
        uniqueConstraints = @UniqueConstraint(name = "uk_doc_hit_tenant_app_date_doc",
                columnNames = {"tenant_id", "app_id", "stat_date", "document_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatDocHitDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 当日命中次数 */
    @Column(name = "hit_count", nullable = false)
    private long hitCount;
}
```

- [ ] **Step 4: 聚合 DAO（native SQL）**

`AnalyticsRollupDao.java`：

```java
package com.qingyu.care.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 分析 rollup 聚合 DAO：native SQL 直写三张 stat 表（配合服务层先删后插保证幂等）。
 * 全部绕开 Hibernate 过滤器（stat 表非过滤实体；chat_* 表 native 查询不受租户过滤器影响）。
 * tenant_id/app_id 为 NULL 的历史脏数据行不参与统计（stat 表以两键为唯一键，NULL 行无归属意义）。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsRollupDao {

    private final JdbcTemplate jdbcTemplate;

    /** 幂等清理：删除指定日期的三表已有行 */
    public void deleteDay(LocalDate day) {
        jdbcTemplate.update("DELETE FROM stat_quality_daily WHERE stat_date = ?", day);
        jdbcTemplate.update("DELETE FROM stat_hot_keyword_daily WHERE stat_date = ?", day);
        jdbcTemplate.update("DELETE FROM stat_doc_hit_daily WHERE stat_date = ?", day);
    }

    /**
     * 质量日聚合：消息侧与会话侧分别按（租户,应用）分组后 FULL JOIN——
     * 某组当日可能只有消息或只有会话，两侧都不能丢。
     */
    public int insertQuality(LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        return jdbcTemplate.update("""
                INSERT INTO stat_quality_daily (tenant_id, app_id, stat_date,
                    ai_msg_count, avg_confidence, low_confidence_count, fallback_count,
                    question_count, hit_question_count, feedback_up, feedback_down,
                    session_count, handover_count, resolved_count, ended_count)
                SELECT COALESCE(m.tenant_id, s.tenant_id), COALESCE(m.app_id, s.app_id), ?,
                       COALESCE(m.ai_msg_count, 0), m.avg_confidence, COALESCE(m.low_confidence_count, 0),
                       COALESCE(m.fallback_count, 0), COALESCE(m.question_count, 0),
                       COALESCE(m.hit_question_count, 0), COALESCE(m.feedback_up, 0), COALESCE(m.feedback_down, 0),
                       COALESCE(s.session_count, 0), COALESCE(s.handover_count, 0),
                       COALESCE(s.resolved_count, 0), COALESCE(s.ended_count, 0)
                FROM (
                    SELECT cs.tenant_id, cs.app_id,
                           COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.confidence_score IS NOT NULL) AS ai_msg_count,
                           AVG(cm.confidence_score) FILTER (WHERE cm.role = 'ASSISTANT') AS avg_confidence,
                           COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.confidence_score < 0.5) AS low_confidence_count,
                           COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.fallback = TRUE) AS fallback_count,
                           COUNT(*) FILTER (WHERE cm.role = 'USER') AS question_count,
                           COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.hit_document_ids IS NOT NULL) AS hit_question_count,
                           COUNT(*) FILTER (WHERE cm.feedback = 1) AS feedback_up,
                           COUNT(*) FILTER (WHERE cm.feedback = -1) AS feedback_down
                    FROM chat_message cm
                    JOIN chat_session cs ON cm.session_id = cs.id
                    WHERE cm.created_at >= ? AND cm.created_at < ?
                      AND cs.tenant_id IS NOT NULL AND cs.app_id IS NOT NULL
                    GROUP BY cs.tenant_id, cs.app_id
                ) m
                FULL JOIN (
                    SELECT tenant_id, app_id,
                           COUNT(*) AS session_count,
                           COUNT(*) FILTER (WHERE handover_time IS NOT NULL) AS handover_count,
                           COUNT(*) FILTER (WHERE status = 'ENDED' AND agent_id IS NULL) AS resolved_count,
                           COUNT(*) FILTER (WHERE status = 'ENDED') AS ended_count
                    FROM chat_session
                    WHERE created_at >= ? AND created_at < ?
                      AND tenant_id IS NOT NULL AND app_id IS NOT NULL
                    GROUP BY tenant_id, app_id
                ) s ON m.tenant_id = s.tenant_id AND m.app_id = s.app_id
                """, day, start, end, start, end);
    }

    /**
     * 热词日聚合：zhparser（chinese_zh，与混合检索同源配置）对当日用户提问分词，
     * unnest(tsvector) 展开词元 → 组内窗口取 TopN。丢弃单字词与超长词（列宽 100）。
     */
    public int insertHotKeywords(LocalDate day, int topN) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        return jdbcTemplate.update("""
                INSERT INTO stat_hot_keyword_daily (tenant_id, app_id, stat_date, keyword, freq)
                SELECT tenant_id, app_id, ?, lexeme, freq FROM (
                    SELECT cs.tenant_id, cs.app_id, t.lexeme, COUNT(*) AS freq,
                           ROW_NUMBER() OVER (PARTITION BY cs.tenant_id, cs.app_id
                                              ORDER BY COUNT(*) DESC, t.lexeme) AS rn
                    FROM chat_message cm
                    JOIN chat_session cs ON cm.session_id = cs.id
                    CROSS JOIN LATERAL unnest(to_tsvector('chinese_zh', cm.content)) AS t
                    WHERE cm.role = 'USER' AND cm.created_at >= ? AND cm.created_at < ?
                      AND cs.tenant_id IS NOT NULL AND cs.app_id IS NOT NULL
                      AND length(t.lexeme) BETWEEN 2 AND 100
                    GROUP BY cs.tenant_id, cs.app_id, t.lexeme
                ) x WHERE rn <= ?
                """, day, start, end, topN);
    }

    /** 文档命中日聚合：hit_document_ids JSON 数组展开计数 */
    public int insertDocHits(LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        return jdbcTemplate.update("""
                INSERT INTO stat_doc_hit_daily (tenant_id, app_id, stat_date, document_id, hit_count)
                SELECT cs.tenant_id, cs.app_id, ?, d.doc_id::bigint, COUNT(*)
                FROM chat_message cm
                JOIN chat_session cs ON cm.session_id = cs.id
                CROSS JOIN LATERAL json_array_elements_text(cm.hit_document_ids::json) AS d(doc_id)
                WHERE cm.role = 'ASSISTANT' AND cm.hit_document_ids IS NOT NULL
                  AND cm.created_at >= ? AND cm.created_at < ?
                  AND cs.tenant_id IS NOT NULL AND cs.app_id IS NOT NULL
                GROUP BY cs.tenant_id, cs.app_id, d.doc_id
                """, day, start, end);
    }
}
```

- [ ] **Step 5: 编排服务 + 定时任务**

`AnalyticsRollupService.java`：

```java
package com.qingyu.care.service;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AnalyticsRollupDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 分析 rollup 编排服务：按日「先删后插」（幂等），单日一个事务。
 * 用 TransactionTemplate 而非 @Transactional：backfill 内部逐日调用 rollupDay，
 * 自调用不经代理、注解事务不生效（Spring AOP 自调用陷阱）。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsRollupService {

    private final AnalyticsRollupDao rollupDao;
    private final TransactionTemplate transactionTemplate;

    /** 每租户+应用每日热词保留条数 */
    @Value("${qingyu.care.analytics.hot-keyword-top-n:50}")
    private int hotKeywordTopN;

    /** 聚合单日（幂等：先删后插，同一事务） */
    public void rollupDay(LocalDate day) {
        transactionTemplate.executeWithoutResult(tx -> {
            rollupDao.deleteDay(day);
            int q = rollupDao.insertQuality(day);
            int k = rollupDao.insertHotKeywords(day, hotKeywordTopN);
            int d = rollupDao.insertDocHits(day);
            log.info("分析 rollup 完成 date={} quality={} keywords={} docHits={}", day, q, k, d);
        });
    }

    /**
     * 区间回填 [from, to]：逐日独立事务执行，单日失败记录后继续，返回成功天数。
     */
    public int backfill(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException("回填区间非法：from 须早于等于 to");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw new BusinessException("回填区间过长（最多 366 天）");
        }
        int ok = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            try {
                rollupDay(d);
                ok++;
            } catch (Exception e) {
                log.error("分析 rollup 失败 date={}: {}", d, e.getMessage(), e);
            }
        }
        return ok;
    }
}
```

`AnalyticsRollupScheduler.java`：

```java
package com.qingyu.care.scheduler;

import com.qingyu.care.service.AnalyticsRollupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 分析 rollup 定时任务：每日凌晨聚合近 2 天（昨日 + 前日）。
 * 覆盖前日是为吸收跑批后才提交的迟到反馈——幂等先删后插，重跑无副作用。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsRollupScheduler {

    private final AnalyticsRollupService rollupService;

    @Scheduled(cron = "${qingyu.care.analytics.rollup-cron:0 30 2 * * *}")
    public void rollupDaily() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        int ok = rollupService.backfill(yesterday.minusDays(1), yesterday);
        log.info("分析 rollup 定时任务完成，成功 {}/2 天", ok);
    }
}
```

- [ ] **Step 6: 回填端点**

`AnalyticsController` 注入 `AnalyticsRollupService rollupService`、`AuthService authService`（新增 import：`com.qingyu.care.annotation.AuditLog`、`com.qingyu.care.exception.BusinessException`、`com.qingyu.care.service.AnalyticsRollupService`、`com.qingyu.care.service.AuthService`、`org.springframework.format.annotation.DateTimeFormat`、`org.springframework.web.bind.annotation.PostMapping`、`java.time.LocalDate`），新增端点：

```java
    @PostMapping("/rollup/backfill")
    @AuditLog(action = "ANALYTICS_BACKFILL", targetType = "ANALYTICS")
    @Operation(summary = "手动回填分析统计", description = "平台管理员专用；逐日幂等重算 [from, to]，返回成功天数")
    public ApiResponse<Integer> backfill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!authService.isPlatformAdmin()) {
            throw new BusinessException(403, "仅平台管理员可执行回填");
        }
        return ApiResponse.success(rollupService.backfill(from, to));
    }
```

（`@AuditLog` 属性名以 `annotation/AuditLog.java` 实际定义为准，现有写操作注解有先例可对照。）

- [ ] **Step 7: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=AnalyticsRollupServiceTest`
Expected: 3 个测试 PASS
Run: `cd backend && mvn test`
Expected: BUILD SUCCESS

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/StatQualityDaily.java backend/src/main/java/com/qingyu/care/entity/StatHotKeywordDaily.java backend/src/main/java/com/qingyu/care/entity/StatDocHitDaily.java backend/src/main/java/com/qingyu/care/repository/AnalyticsRollupDao.java backend/src/main/java/com/qingyu/care/service/AnalyticsRollupService.java backend/src/main/java/com/qingyu/care/scheduler/AnalyticsRollupScheduler.java backend/src/main/java/com/qingyu/care/controller/AnalyticsController.java backend/src/test/java/com/qingyu/care/service/AnalyticsRollupServiceTest.java
git commit -m "feat: 分析 rollup 三表日粒度预聚合（zhparser 热词/文档命中/质量指标）+ 定时与回填"
```

---

### Task 5: 查询侧（projection + repository + DTO + AnalyticsService + 端点）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/repository/projection/QualityDailyRow.java`、`KeywordFreqRow.java`、`DocHitRankRow.java`、`HotQuestionRow.java`、`MissedQuestionRow.java`、`DocIdNameRow.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/StatQualityDailyRepository.java`、`StatHotKeywordDailyRepository.java`、`StatDocHitDailyRepository.java`
- Modify: `backend/src/main/java/com/qingyu/care/repository/ChatMessageRepository.java`、`DocumentRepository.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/QualityAnalyticsDTO.java`、`HotTopicsDTO.java`、`CoverageAnalyticsDTO.java`、`MissedQuestionDTO.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/AnalyticsService.java`、`controller/AnalyticsController.java`
- Test: `backend/src/test/java/com/qingyu/care/service/AnalyticsQualityServiceTest.java`

- [ ] **Step 0: 核对 DocumentStatus 枚举值**

Run: `grep -n "COMPLETED\|SUCCESS\|READY" backend/src/main/java/com/qingyu/care/enums/DocumentStatus.java`
Expected: 确认「处理完成」的枚举字面量（下面零命中 SQL 里的 `'COMPLETED'` 若不符则替换为实际值）

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.QualityAnalyticsDTO;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.StatDocHitDailyRepository;
import com.qingyu.care.repository.StatHotKeywordDailyRepository;
import com.qingyu.care.repository.StatQualityDailyRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.QualityDailyRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：质量看板的角色视角、趋势补零与率值口径。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsQualityServiceTest {

    @Mock SysApiUsageRepository usageRepository;
    @Mock ChatSessionRepository sessionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock AuthService authService;
    @Mock StatQualityDailyRepository qualityRepository;
    @Mock StatHotKeywordDailyRepository hotKeywordRepository;
    @Mock StatDocHitDailyRepository docHitRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock DocumentRepository documentRepository;
    @InjectMocks AnalyticsService service;

    /** projection 行桩（接口 getter 无法用 record 实现，用 Mockito 逐一打桩） */
    private QualityDailyRow row(LocalDate date, long aiMsg, Double confSum, long lowConf, long fallback,
                                long question, long hit, long up, long down,
                                long sessions, long handover, long resolved, long ended) {
        QualityDailyRow r = mock(QualityDailyRow.class);
        lenient().when(r.getDate()).thenReturn(date);
        lenient().when(r.getAiMsgCount()).thenReturn(aiMsg);
        lenient().when(r.getConfSum()).thenReturn(confSum);
        lenient().when(r.getLowConfidenceCount()).thenReturn(lowConf);
        lenient().when(r.getFallbackCount()).thenReturn(fallback);
        lenient().when(r.getQuestionCount()).thenReturn(question);
        lenient().when(r.getHitQuestionCount()).thenReturn(hit);
        lenient().when(r.getFeedbackUp()).thenReturn(up);
        lenient().when(r.getFeedbackDown()).thenReturn(down);
        lenient().when(r.getSessionCount()).thenReturn(sessions);
        lenient().when(r.getHandoverCount()).thenReturn(handover);
        lenient().when(r.getResolvedCount()).thenReturn(resolved);
        lenient().when(r.getEndedCount()).thenReturn(ended);
        return r;
    }

    @Test
    void 租户视角_按租户过滤_趋势补零_汇总口径正确() {
        when(authService.isPlatformAdmin()).thenReturn(false);
        when(authService.getCurrentTenantId()).thenReturn(7L);
        LocalDate cutoff = LocalDate.now().minusDays(1);
        when(qualityRepository.aggregateDaily(cutoff.minusDays(6), 7L, null))
                .thenReturn(List.of(row(cutoff, 10, 8.0, 2, 1, 12, 6, 3, 1, 5, 1, 2, 3)));

        QualityAnalyticsDTO dto = service.getQuality(7, null);

        assertThat(dto.scope()).isEqualTo("TENANT");
        assertThat(dto.statCutoff()).isEqualTo(cutoff);
        assertThat(dto.trend()).hasSize(7);                              // 补零后每天一个点
        assertThat(dto.trend().get(6).aiMsgCount()).isEqualTo(10);
        assertThat(dto.trend().get(0).aiMsgCount()).isZero();
        assertThat(dto.summary().avgConfidence()).isEqualTo(0.8);        // 8.0 / 10
        assertThat(dto.summary().lowConfidenceRate()).isEqualTo(20.0);   // 2/10
        assertThat(dto.summary().fallbackRate()).isEqualTo(10.0);        // 1/10
        assertThat(dto.summary().aiResolutionRate()).isEqualTo(66.7);    // 2/3 ended
        assertThat(dto.summary().handoverRate()).isEqualTo(20.0);        // 1/5 sessions
    }

    @Test
    void 平台视角_跨租户聚合_忽略appId下钻() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(qualityRepository.aggregateDaily(any(), isNull(), isNull())).thenReturn(List.of());

        QualityAnalyticsDTO dto = service.getQuality(30, 123L);

        assertThat(dto.scope()).isEqualTo("PLATFORM");
        verify(qualityRepository).aggregateDaily(any(), isNull(), isNull());
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `cd backend && mvn test -Dtest=AnalyticsQualityServiceTest`
Expected: 编译错误（projection/DTO/方法不存在）

- [ ] **Step 3: projection 接口（`repository/projection/` 包，每个一文件）**

```java
package com.qingyu.care.repository.projection;

import java.time.LocalDate;

/**
 * 质量日统计聚合行（跨应用求和；confSum = Σ(avg_confidence × ai_msg_count)，加权分子，除法在服务层做）。
 *
 * @author ruan
 */
public interface QualityDailyRow {
    LocalDate getDate();
    long getAiMsgCount();
    Double getConfSum();
    long getLowConfidenceCount();
    long getFallbackCount();
    long getQuestionCount();
    long getHitQuestionCount();
    long getFeedbackUp();
    long getFeedbackDown();
    long getSessionCount();
    long getHandoverCount();
    long getResolvedCount();
    long getEndedCount();
}
```

```java
package com.qingyu.care.repository.projection;

/**
 * 热词聚合行。
 *
 * @author ruan
 */
public interface KeywordFreqRow {
    String getKeyword();
    long getFreq();
}
```

```java
package com.qingyu.care.repository.projection;

/**
 * 文档命中排行行。
 *
 * @author ruan
 */
public interface DocHitRankRow {
    Long getDocumentId();
    long getHits();
}
```

```java
package com.qingyu.care.repository.projection;

/**
 * 高频问句行（实时明细，同内容精确归组）。
 *
 * @author ruan
 */
public interface HotQuestionRow {
    String getContent();
    long getCnt();
}
```

```java
package com.qingyu.care.repository.projection;

import java.time.LocalDateTime;

/**
 * 未命中提问明细行。
 *
 * @author ruan
 */
public interface MissedQuestionRow {
    String getContent();
    LocalDateTime getCreatedAt();
    String getSessionId();
}
```

```java
package com.qingyu.care.repository.projection;

/**
 * 文档 id+name 行（零命中清单与排行名称回填共用）。
 *
 * @author ruan
 */
public interface DocIdNameRow {
    Long getId();
    String getName();
}
```

- [ ] **Step 4: stat 表 repository（JPQL 手写 where——实体非过滤实体，无 Hibernate 过滤器干扰）**

`StatQualityDailyRepository.java`：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.StatQualityDaily;
import com.qingyu.care.repository.projection.QualityDailyRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 质量日统计查询（tenantId/appId 为 null 时该维度不过滤，同 SysApiUsageRepository 惯例）。
 *
 * @author ruan
 */
@Repository
public interface StatQualityDailyRepository extends JpaRepository<StatQualityDaily, Long> {

    /** 按日聚合（跨应用求和；平均置信度以加权分子 confSum 返回，除法在服务层做） */
    @Query("""
            SELECT q.statDate AS date,
                   SUM(q.aiMsgCount) AS aiMsgCount,
                   SUM(COALESCE(q.avgConfidence, 0) * q.aiMsgCount) AS confSum,
                   SUM(q.lowConfidenceCount) AS lowConfidenceCount,
                   SUM(q.fallbackCount) AS fallbackCount,
                   SUM(q.questionCount) AS questionCount,
                   SUM(q.hitQuestionCount) AS hitQuestionCount,
                   SUM(q.feedbackUp) AS feedbackUp,
                   SUM(q.feedbackDown) AS feedbackDown,
                   SUM(q.sessionCount) AS sessionCount,
                   SUM(q.handoverCount) AS handoverCount,
                   SUM(q.resolvedCount) AS resolvedCount,
                   SUM(q.endedCount) AS endedCount
            FROM StatQualityDaily q
            WHERE q.statDate >= :from
              AND (:tenantId IS NULL OR q.tenantId = :tenantId)
              AND (:appId IS NULL OR q.appId = :appId)
            GROUP BY q.statDate
            ORDER BY q.statDate
            """)
    List<QualityDailyRow> aggregateDaily(@Param("from") LocalDate from,
                                         @Param("tenantId") Long tenantId,
                                         @Param("appId") Long appId);
}
```

`StatHotKeywordDailyRepository.java`：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.StatHotKeywordDaily;
import com.qingyu.care.repository.projection.KeywordFreqRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 热词统计查询。
 *
 * @author ruan
 */
@Repository
public interface StatHotKeywordDailyRepository extends JpaRepository<StatHotKeywordDaily, Long> {

    /** 窗口内按词求和取 TopN（Pageable 控制 N） */
    @Query("""
            SELECT k.keyword AS keyword, SUM(k.freq) AS freq
            FROM StatHotKeywordDaily k
            WHERE k.statDate >= :from
              AND (:tenantId IS NULL OR k.tenantId = :tenantId)
              AND (:appId IS NULL OR k.appId = :appId)
            GROUP BY k.keyword
            ORDER BY SUM(k.freq) DESC
            """)
    List<KeywordFreqRow> topKeywords(@Param("from") LocalDate from,
                                     @Param("tenantId") Long tenantId,
                                     @Param("appId") Long appId,
                                     Pageable pageable);
}
```

`StatDocHitDailyRepository.java`：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.StatDocHitDaily;
import com.qingyu.care.repository.projection.DocHitRankRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 文档命中统计查询。
 *
 * @author ruan
 */
@Repository
public interface StatDocHitDailyRepository extends JpaRepository<StatDocHitDaily, Long> {

    /** 窗口内文档命中排行 TopN */
    @Query("""
            SELECT d.documentId AS documentId, SUM(d.hitCount) AS hits
            FROM StatDocHitDaily d
            WHERE d.statDate >= :from
              AND (:tenantId IS NULL OR d.tenantId = :tenantId)
              AND (:appId IS NULL OR d.appId = :appId)
            GROUP BY d.documentId
            ORDER BY SUM(d.hitCount) DESC
            """)
    List<DocHitRankRow> rankDocs(@Param("from") LocalDate from,
                                 @Param("tenantId") Long tenantId,
                                 @Param("appId") Long appId,
                                 Pageable pageable);
}
```

- [ ] **Step 5: ChatMessageRepository / DocumentRepository 加实时明细查询**

`ChatMessageRepository` 追加（import `Page`/`Pageable`/`Query`/`Param`/`LocalDateTime` 及两个 projection）：

```java
    // ===== 分析三件套实时明细（native 绕过租户过滤器，where 手写；同 ChatSessionRepository 聚合惯例）=====

    /** 高频问句 TopN（同内容精确归组；含当日实时数据） */
    @Query(value = """
            SELECT cm.content AS content, COUNT(*) AS cnt
            FROM chat_message cm
            JOIN chat_session cs ON cm.session_id = cs.id
            WHERE cm.role = 'USER' AND cm.created_at >= :from
              AND (:tenantId IS NULL OR cs.tenant_id = :tenantId)
              AND (:appId IS NULL OR cs.app_id = :appId)
            GROUP BY cm.content
            ORDER BY COUNT(*) DESC, cm.content
            LIMIT :limit
            """, nativeQuery = true)
    List<HotQuestionRow> topQuestions(@Param("from") LocalDateTime from,
                                      @Param("tenantId") Long tenantId,
                                      @Param("appId") Long appId,
                                      @Param("limit") int limit);

    /**
     * 未命中提问明细：AI 回复无命中（hit_document_ids IS NULL 且置信度非空=真 AI 回复）
     * 对应的最近一条用户提问（LEFT JOIN LATERAL 取同会话中 id 更小的最后一条 USER 消息）。
     */
    @Query(value = """
            SELECT COALESCE(u.content, '(无对应提问)') AS content,
                   a.created_at AS createdAt, cs.session_id AS sessionId
            FROM chat_message a
            JOIN chat_session cs ON a.session_id = cs.id
            LEFT JOIN LATERAL (
                SELECT m.content FROM chat_message m
                WHERE m.session_id = a.session_id AND m.role = 'USER' AND m.id < a.id
                ORDER BY m.id DESC LIMIT 1
            ) u ON TRUE
            WHERE a.role = 'ASSISTANT' AND a.confidence_score IS NOT NULL
              AND a.hit_document_ids IS NULL AND a.created_at >= :from
              AND (:tenantId IS NULL OR cs.tenant_id = :tenantId)
              AND (:appId IS NULL OR cs.app_id = :appId)
            ORDER BY a.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM chat_message a
            JOIN chat_session cs ON a.session_id = cs.id
            WHERE a.role = 'ASSISTANT' AND a.confidence_score IS NOT NULL
              AND a.hit_document_ids IS NULL AND a.created_at >= :from
              AND (:tenantId IS NULL OR cs.tenant_id = :tenantId)
              AND (:appId IS NULL OR cs.app_id = :appId)
            """, nativeQuery = true)
    Page<MissedQuestionRow> findMissedQuestions(@Param("from") LocalDateTime from,
                                                @Param("tenantId") Long tenantId,
                                                @Param("appId") Long appId,
                                                Pageable pageable);
```

`DocumentRepository` 追加（import `Query`/`Param`/`LocalDate`/`List` 及 `DocIdNameRow`）：

```java
    // ===== 覆盖率分析（native 绕过租户过滤器，where 手写）=====

    /** 窗口内零命中的启用文档（最多 50 条；status 字面量以 DocumentStatus 实际枚举为准） */
    @Query(value = """
            SELECT d.id AS id, d.name AS name FROM document d
            WHERE (:tenantId IS NULL OR d.tenant_id = :tenantId)
              AND (:appId IS NULL OR d.app_id = :appId)
              AND (d.enabled IS NULL OR d.enabled = TRUE)
              AND d.status = 'COMPLETED'
              AND NOT EXISTS (
                  SELECT 1 FROM stat_doc_hit_daily h
                  WHERE h.document_id = d.id AND h.stat_date >= :from)
            ORDER BY d.id
            LIMIT 50
            """, nativeQuery = true)
    List<DocIdNameRow> findZeroHitDocs(@Param("from") LocalDate from,
                                       @Param("tenantId") Long tenantId,
                                       @Param("appId") Long appId);

    /** 按 id 批量取文档名（native 免过滤器：排行含跨应用文档时名称仍可回填） */
    @Query(value = "SELECT d.id AS id, d.name AS name FROM document d WHERE d.id IN (:ids)", nativeQuery = true)
    List<DocIdNameRow> findNamesByIds(@Param("ids") List<Long> ids);
```

- [ ] **Step 6: DTO（`dto/` 包，record 风格同 UsageAnalyticsDTO）**

`QualityAnalyticsDTO.java`：

```java
package com.qingyu.care.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 对话质量看板返回体。statCutoff 为统计截止日（rollup T+1，不含当日）。
 *
 * @author ruan
 */
public record QualityAnalyticsDTO(
        Summary summary,
        List<TrendPoint> trend,
        String scope,
        LocalDate statCutoff) {

    /** 区间汇总。各率值为百分数（0~100，一位小数）；avgConfidence 为 0~1（两位小数）。 */
    public record Summary(long aiMsgCount, double avgConfidence, double lowConfidenceRate,
                          double fallbackRate, long feedbackUp, long feedbackDown,
                          double aiResolutionRate, double handoverRate) {
    }

    /** 按天趋势点（已补零）。 */
    public record TrendPoint(LocalDate date, long aiMsgCount, double avgConfidence,
                             long feedbackUp, long feedbackDown) {
    }
}
```

`HotTopicsDTO.java`：

```java
package com.qingyu.care.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 热点问题统计返回体。keywords 来自 rollup（截止 statCutoff）；questions 为实时明细（含当日）。
 *
 * @author ruan
 */
public record HotTopicsDTO(
        List<KeywordItem> keywords,
        List<QuestionItem> questions,
        String scope,
        LocalDate statCutoff) {

    /** 关键词项 */
    public record KeywordItem(String keyword, long freq) {
    }

    /** 高频问句项（同内容精确归组） */
    public record QuestionItem(String content, long count) {
    }
}
```

`CoverageAnalyticsDTO.java`：

```java
package com.qingyu.care.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 知识覆盖率返回体。answeredCount = AI 回答数（每条对应一个提问）；hitRate 为百分数。
 *
 * @author ruan
 */
public record CoverageAnalyticsDTO(
        Summary summary,
        List<TrendPoint> trend,
        List<DocHitItem> topDocs,
        List<DocItem> zeroHitDocs,
        String scope,
        LocalDate statCutoff) {

    public record Summary(long answeredCount, long hitCount, double hitRate) {
    }

    public record TrendPoint(LocalDate date, long answeredCount, long hitCount, double hitRate) {
    }

    /** 文档命中排行项 */
    public record DocHitItem(Long documentId, String name, long hits) {
    }

    /** 零命中文档项 */
    public record DocItem(Long documentId, String name) {
    }
}
```

`MissedQuestionDTO.java`：

```java
package com.qingyu.care.dto;

import java.time.LocalDateTime;

/**
 * 未命中提问明细项（知识缺口清单）。
 *
 * @author ruan
 */
public record MissedQuestionDTO(String content, LocalDateTime createdAt, String sessionId) {
}
```

- [ ] **Step 7: AnalyticsService 扩展**

注入新依赖（构造注入字段追加）：

```java
    private final StatQualityDailyRepository qualityRepository;
    private final StatHotKeywordDailyRepository hotKeywordRepository;
    private final StatDocHitDailyRepository docHitRepository;
    private final ChatMessageRepository messageRepository;
    private final DocumentRepository documentRepository;
```

类尾追加方法（import 相应 DTO/projection/`PageDTO`/`Page`）：

```java
    // ===== Phase 4.4 分析三件套 =====

    /** 视角解析（与 getUsage 同规则）：平台跨租户；租户限本租户，appId 下钻仅租户视角有效 */
    private Scope resolveScope(Long appIdFilter) {
        boolean platform = authService.isPlatformAdmin();
        Long tenantId = platform ? null : authService.getCurrentTenantId();
        Long appId = platform ? null : appIdFilter;
        return new Scope(platform, tenantId, appId);
    }

    private record Scope(boolean platform, Long tenantId, Long appId) {
    }

    /** 对话质量看板（rollup T+1：窗口为 [cutoff-span+1, cutoff]，cutoff=昨日） */
    public QualityAnalyticsDTO getQuality(int days, Long appIdFilter) {
        int span = normalizeDays(days);
        LocalDate cutoff = LocalDate.now().minusDays(1);
        LocalDate from = cutoff.minusDays(span - 1L);
        Scope sc = resolveScope(appIdFilter);

        Map<LocalDate, QualityDailyRow> byDate = qualityRepository
                .aggregateDaily(from, sc.tenantId(), sc.appId()).stream()
                .collect(Collectors.toMap(QualityDailyRow::getDate, Function.identity(), (a, b) -> a));

        List<QualityAnalyticsDTO.TrendPoint> trend = new ArrayList<>(span);
        long aiMsg = 0, lowConf = 0, fallback = 0, up = 0, down = 0;
        long sessions = 0, handover = 0, resolved = 0, ended = 0;
        double confSum = 0;
        for (int i = 0; i < span; i++) {
            LocalDate d = from.plusDays(i);
            QualityDailyRow r = byDate.get(d);
            long dayAi = r != null ? r.getAiMsgCount() : 0;
            double dayConfSum = (r != null && r.getConfSum() != null) ? r.getConfSum() : 0;
            trend.add(new QualityAnalyticsDTO.TrendPoint(d, dayAi,
                    dayAi > 0 ? round2(dayConfSum / dayAi) : 0.0,
                    r != null ? r.getFeedbackUp() : 0,
                    r != null ? r.getFeedbackDown() : 0));
            if (r != null) {
                aiMsg += dayAi;
                confSum += dayConfSum;
                lowConf += r.getLowConfidenceCount();
                fallback += r.getFallbackCount();
                up += r.getFeedbackUp();
                down += r.getFeedbackDown();
                sessions += r.getSessionCount();
                handover += r.getHandoverCount();
                resolved += r.getResolvedCount();
                ended += r.getEndedCount();
            }
        }
        QualityAnalyticsDTO.Summary summary = new QualityAnalyticsDTO.Summary(
                aiMsg,
                aiMsg > 0 ? round2(confSum / aiMsg) : 0.0,
                aiMsg > 0 ? round1((double) lowConf / aiMsg * 100) : 0.0,
                aiMsg > 0 ? round1((double) fallback / aiMsg * 100) : 0.0,
                up, down,
                ended > 0 ? round1((double) resolved / ended * 100) : 0.0,
                sessions > 0 ? round1((double) handover / sessions * 100) : 0.0);
        return new QualityAnalyticsDTO(summary, trend, sc.platform() ? "PLATFORM" : "TENANT", cutoff);
    }

    /** 热点问题：关键词 TopN（rollup）+ 高频问句 TopN（实时） */
    public HotTopicsDTO getHotTopics(int days, Long appIdFilter) {
        int span = normalizeDays(days);
        LocalDate cutoff = LocalDate.now().minusDays(1);
        LocalDate from = cutoff.minusDays(span - 1L);
        Scope sc = resolveScope(appIdFilter);

        List<HotTopicsDTO.KeywordItem> keywords = hotKeywordRepository
                .topKeywords(from, sc.tenantId(), sc.appId(), PageRequest.of(0, 20)).stream()
                .map(r -> new HotTopicsDTO.KeywordItem(r.getKeyword(), r.getFreq()))
                .toList();
        List<HotTopicsDTO.QuestionItem> questions = messageRepository
                .topQuestions(from.atStartOfDay(), sc.tenantId(), sc.appId(), 10).stream()
                .map(r -> new HotTopicsDTO.QuestionItem(r.getContent(), r.getCnt()))
                .toList();
        return new HotTopicsDTO(keywords, questions, sc.platform() ? "PLATFORM" : "TENANT", cutoff);
    }

    /** 知识覆盖率：命中率趋势（读质量 rollup）+ 文档命中排行 + 零命中文档 */
    public CoverageAnalyticsDTO getCoverage(int days, Long appIdFilter) {
        int span = normalizeDays(days);
        LocalDate cutoff = LocalDate.now().minusDays(1);
        LocalDate from = cutoff.minusDays(span - 1L);
        Scope sc = resolveScope(appIdFilter);

        Map<LocalDate, QualityDailyRow> byDate = qualityRepository
                .aggregateDaily(from, sc.tenantId(), sc.appId()).stream()
                .collect(Collectors.toMap(QualityDailyRow::getDate, Function.identity(), (a, b) -> a));
        List<CoverageAnalyticsDTO.TrendPoint> trend = new ArrayList<>(span);
        long total = 0, hit = 0;
        for (int i = 0; i < span; i++) {
            LocalDate d = from.plusDays(i);
            QualityDailyRow r = byDate.get(d);
            long dayTotal = r != null ? r.getAiMsgCount() : 0;
            long dayHit = r != null ? r.getHitQuestionCount() : 0;
            trend.add(new CoverageAnalyticsDTO.TrendPoint(d, dayTotal, dayHit,
                    dayTotal > 0 ? round1((double) dayHit / dayTotal * 100) : 0.0));
            total += dayTotal;
            hit += dayHit;
        }

        List<DocHitRankRow> rank = docHitRepository.rankDocs(from, sc.tenantId(), sc.appId(),
                PageRequest.of(0, TOP_N));
        List<Long> rankIds = rank.stream().map(DocHitRankRow::getDocumentId)
                .filter(Objects::nonNull).toList();
        Map<Long, String> names = rankIds.isEmpty() ? Map.of()
                : documentRepository.findNamesByIds(rankIds).stream()
                        .collect(Collectors.toMap(DocIdNameRow::getId, DocIdNameRow::getName));
        List<CoverageAnalyticsDTO.DocHitItem> topDocs = rank.stream()
                .map(r -> new CoverageAnalyticsDTO.DocHitItem(r.getDocumentId(),
                        names.getOrDefault(r.getDocumentId(), "未知文档"), r.getHits()))
                .toList();

        List<CoverageAnalyticsDTO.DocItem> zeroHit = documentRepository
                .findZeroHitDocs(from, sc.tenantId(), sc.appId()).stream()
                .map(r -> new CoverageAnalyticsDTO.DocItem(r.getId(), r.getName()))
                .toList();

        CoverageAnalyticsDTO.Summary summary = new CoverageAnalyticsDTO.Summary(total, hit,
                total > 0 ? round1((double) hit / total * 100) : 0.0);
        return new CoverageAnalyticsDTO(summary, trend, topDocs, zeroHit,
                sc.platform() ? "PLATFORM" : "TENANT", cutoff);
    }

    /** 未命中提问明细（实时含当日；size 钳位 [1,100]） */
    public PageDTO<MissedQuestionDTO> getMisses(int days, Long appIdFilter, int page, int size) {
        int span = normalizeDays(days);
        LocalDateTime from = LocalDate.now().minusDays(span - 1L).atStartOfDay();
        Scope sc = resolveScope(appIdFilter);
        Page<MissedQuestionRow> rows = messageRepository.findMissedQuestions(
                from, sc.tenantId(), sc.appId(),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
        return PageDTO.from(rows.map(r ->
                new MissedQuestionDTO(r.getContent(), r.getCreatedAt(), r.getSessionId())));
    }

    /** 保留两位小数（置信度显示用） */
    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
```

- [ ] **Step 8: AnalyticsController 加 4 个查询端点**

```java
    @GetMapping("/quality")
    @Operation(summary = "对话质量看板", description = "规则指标 + 反馈汇总/趋势；days∈{7,30,90}，rollup T+1")
    public ApiResponse<QualityAnalyticsDTO> quality(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long appId) {
        return ApiResponse.success(analyticsService.getQuality(days, appId));
    }

    @GetMapping("/hot-topics")
    @Operation(summary = "热点问题统计", description = "关键词 TopN（rollup）+ 高频问句 TopN（实时）")
    public ApiResponse<HotTopicsDTO> hotTopics(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long appId) {
        return ApiResponse.success(analyticsService.getHotTopics(days, appId));
    }

    @GetMapping("/coverage")
    @Operation(summary = "知识覆盖率", description = "命中率趋势 + 文档命中排行 + 零命中文档")
    public ApiResponse<CoverageAnalyticsDTO> coverage(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long appId) {
        return ApiResponse.success(analyticsService.getCoverage(days, appId));
    }

    @GetMapping("/coverage/misses")
    @Operation(summary = "未命中提问明细", description = "知识缺口清单（分页，实时含当日）")
    public ApiResponse<PageDTO<MissedQuestionDTO>> misses(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long appId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(analyticsService.getMisses(days, appId, page, size));
    }
```

- [ ] **Step 9: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=AnalyticsQualityServiceTest`
Expected: 2 个测试 PASS
Run: `cd backend && mvn test`
Expected: BUILD SUCCESS

- [ ] **Step 10: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/repository/projection/ backend/src/main/java/com/qingyu/care/repository/StatQualityDailyRepository.java backend/src/main/java/com/qingyu/care/repository/StatHotKeywordDailyRepository.java backend/src/main/java/com/qingyu/care/repository/StatDocHitDailyRepository.java backend/src/main/java/com/qingyu/care/repository/ChatMessageRepository.java backend/src/main/java/com/qingyu/care/repository/DocumentRepository.java backend/src/main/java/com/qingyu/care/dto/QualityAnalyticsDTO.java backend/src/main/java/com/qingyu/care/dto/HotTopicsDTO.java backend/src/main/java/com/qingyu/care/dto/CoverageAnalyticsDTO.java backend/src/main/java/com/qingyu/care/dto/MissedQuestionDTO.java backend/src/main/java/com/qingyu/care/service/AnalyticsService.java backend/src/main/java/com/qingyu/care/controller/AnalyticsController.java backend/src/test/java/com/qingyu/care/service/AnalyticsQualityServiceTest.java
git commit -m "feat: 分析三件套查询端点（质量/热点/覆盖率/未命中明细，角色自适应视角）"
```

---

### Task 6: 集成测试回归网（需 Docker Desktop）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/AnalyticsRollupIT.java`
- Create: `backend/src/test/java/com/qingyu/care/it/ChatFeedbackIT.java`
- Create: `backend/src/test/java/com/qingyu/care/it/StreamPersistenceIT.java`

- [ ] **Step 0: 核对 GlobalExceptionHandler 对 ResourceNotFoundException/BusinessException 的 HTTP 状态映射**

Run: `grep -n "ResourceNotFoundException\|BusinessException\|HttpStatus" backend/src/main/java/com/qingyu/care/exception/GlobalExceptionHandler.java`
Expected: 确认 404/400 是走 HTTP 状态码还是 200+code 封装（下面断言按实际调整）

- [ ] **Step 1: AnalyticsRollupIT**

要点：种子须 `saveAndFlush`（rollup 走 JdbcTemplate 原生 SQL，Hibernate 不会为其自动 flush）；`@Transactional` 回滚种子（TransactionTemplate 默认 PROPAGATION_REQUIRED 会加入测试事务）。

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.StatQualityDaily;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.StatDocHitDailyRepository;
import com.qingyu.care.repository.StatHotKeywordDailyRepository;
import com.qingyu.care.repository.StatQualityDailyRepository;
import com.qingyu.care.service.AnalyticsRollupService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * rollup 聚合正确性 + 多租户隔离 + 幂等重跑。
 * 种子 tenantId+appId 双设（NULL 行不参与统计）；saveAndFlush 保证 native SQL 可见。
 *
 * @author ruan
 */
@Transactional
class AnalyticsRollupIT extends AbstractPostgresIT {

    private static final Long T1 = 91001L, T2 = 91002L, A1 = 91011L, A2 = 91021L;

    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired StatQualityDailyRepository qualityRepository;
    @Autowired StatHotKeywordDailyRepository hotKeywordRepository;
    @Autowired StatDocHitDailyRepository docHitRepository;
    @Autowired AnalyticsRollupService rollupService;

    private ChatSession seedSession(Long tenantId, Long appId, SessionStatus status,
                                    Long agentId, boolean handover) {
        ChatSession s = ChatSession.builder()
                .sessionId("roll-" + tenantId + "-" + System.nanoTime())
                .userId("u").status(status).agentId(agentId)
                .handoverTime(handover ? LocalDateTime.now() : null).build();
        s.setTenantId(tenantId);
        s.setAppId(appId);
        return sessionRepository.saveAndFlush(s);
    }

    private void seedExchange(ChatSession s, String question, double confidence,
                              String hitDocIds, Boolean fallback, Integer feedback) {
        messageRepository.saveAndFlush(ChatMessage.builder()
                .session(s).role(MessageRole.USER).content(question).build());
        messageRepository.saveAndFlush(ChatMessage.builder()
                .session(s).role(MessageRole.ASSISTANT).content("答复")
                .confidenceScore(confidence).hitDocumentIds(hitDocIds)
                .fallback(fallback).feedback(feedback).build());
    }

    @Test
    void 质量文档热词聚合_口径与隔离与幂等() {
        ChatSession t1 = seedSession(T1, A1, SessionStatus.ENDED, null, false);
        seedExchange(t1, "怎么申请退货", 0.9, "[5]", null, 1);
        seedExchange(t1, "发票如何开具", 0.2, null, null, -1);
        seedExchange(t1, "退货运费谁承担", 0.0, null, true, null);
        ChatSession t2 = seedSession(T2, A2, SessionStatus.AI_PROCESSING, null, true);
        seedExchange(t2, "如何退货", 0.8, "[5]", null, null);

        rollupService.rollupDay(LocalDate.now());

        // ===== 质量：T1 口径 =====
        List<StatQualityDaily> t1Rows = qualityRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList();
        assertThat(t1Rows).hasSize(1);
        StatQualityDaily q = t1Rows.get(0);
        assertThat(q.getAiMsgCount()).isEqualTo(3);
        assertThat(q.getQuestionCount()).isEqualTo(3);
        assertThat(q.getHitQuestionCount()).isEqualTo(1);
        assertThat(q.getLowConfidenceCount()).isEqualTo(2);      // 0.2、0.0 < 0.5
        assertThat(q.getFallbackCount()).isEqualTo(1);
        assertThat(q.getFeedbackUp()).isEqualTo(1);
        assertThat(q.getFeedbackDown()).isEqualTo(1);
        assertThat(q.getAvgConfidence()).isCloseTo(0.3667, within(0.01));
        assertThat(q.getSessionCount()).isEqualTo(1);
        assertThat(q.getEndedCount()).isEqualTo(1);
        assertThat(q.getResolvedCount()).isEqualTo(1);
        assertThat(q.getHandoverCount()).isZero();

        // ===== 隔离：T2 独立成行 =====
        assertThat(qualityRepository.findAll().stream()
                .filter(r -> T2.equals(r.getTenantId())).toList()).hasSize(1);

        // ===== 文档命中 =====
        var t1Docs = docHitRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList();
        assertThat(t1Docs).hasSize(1);
        assertThat(t1Docs.get(0).getDocumentId()).isEqualTo(5L);
        assertThat(t1Docs.get(0).getHitCount()).isEqualTo(1);

        // ===== 热词（zhparser 分词，双字以上）=====
        assertThat(hotKeywordRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList()).isNotEmpty();

        // ===== 幂等：重跑不翻倍 =====
        rollupService.rollupDay(LocalDate.now());
        assertThat(qualityRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList()).hasSize(1);
        assertThat(docHitRepository.findAll().stream()
                .filter(r -> T1.equals(r.getTenantId())).toList()).hasSize(1);
    }
}
```

- [ ] **Step 2: 跑 AnalyticsRollupIT**

Run: `cd backend && mvn verify -Dit.test=AnalyticsRollupIT`
Expected: PASS（首跑需构建 zhparser 镜像，耗时长属正常）

- [ ] **Step 3: ChatFeedbackIT**

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开放反馈端点：双路径鉴权（API Key / Origin）+ 跨会话拒绝 + 非法值拒绝。
 * 断言的 HTTP 状态按 GlobalExceptionHandler 实际映射调整（Step 0 已核对）。
 *
 * @author ruan
 */
class ChatFeedbackIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired ChatMessageRepository messageRepository;

    private Application app;
    private ChatSession session;
    private ChatMessage aiMsg;

    @BeforeEach
    void seed() {
        app = seedApp("fb" + System.nanoTime(), "http://fb.example.com");
        session = ChatSession.builder()
                .sessionId("fb-session-" + System.nanoTime())
                .userId("u").status(SessionStatus.AI_PROCESSING).build();
        session.setTenantId(app.getTenant().getId());
        session.setAppId(app.getId());
        session = sessionRepository.saveAndFlush(session);
        aiMsg = messageRepository.saveAndFlush(ChatMessage.builder()
                .session(session).role(MessageRole.ASSISTANT)
                .content("答复").confidenceScore(0.8).build());
    }

    @AfterEach
    void cleanup() {
        messageRepository.deleteById(aiMsg.getId());
        sessionRepository.deleteById(session.getId());
    }

    private String body(String sessionId, Long messageId, int feedback) {
        return "{\"sessionId\":\"" + sessionId + "\",\"messageId\":" + messageId
                + ",\"feedback\":" + feedback + "}";
    }

    @Test
    void ApiKey路径_提交反馈成功() throws Exception {
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(session.getSessionId(), aiMsg.getId(), 1)))
                .andExpect(status().isOk());
        assertThat(messageRepository.findById(aiMsg.getId()).orElseThrow().getFeedback())
                .isEqualTo(1);
    }

    @Test
    void Origin路径_提交反馈成功() throws Exception {
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("Origin", "http://fb.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(session.getSessionId(), aiMsg.getId(), -1)))
                .andExpect(status().isOk());
        assertThat(messageRepository.findById(aiMsg.getId()).orElseThrow().getFeedback())
                .isEqualTo(-1);
    }

    @Test
    void 跨会话messageId_拒绝() throws Exception {
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("其他会话id", aiMsg.getId(), 1)))
                .andExpect(status().isNotFound());
        assertThat(messageRepository.findById(aiMsg.getId()).orElseThrow().getFeedback()).isNull();
    }

    @Test
    void 非法feedback值_拒绝() throws Exception {
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(session.getSessionId(), aiMsg.getId(), 5)))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 4: StreamPersistenceIT**

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式对话补落库 + SSE 末尾 meta 事件。ChatClient 用深桩 mock 流式链
 * （不 mock VectorStore——会破坏共享表，见多知识库检索 IT 的教训）。
 *
 * @author ruan
 */
class StreamPersistenceIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatMessageRepository messageRepository;

    @MockitoBean
    private ModelRouterService modelRouterService;

    @Test
    void 流式对话_消息落库并追加meta事件() throws Exception {
        Application app = seedApp("stream" + System.nanoTime(), "http://st.example.com");
        ChatClient client = Mockito.mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(client.prompt().messages(ArgumentMatchers.<Message>anyList())
                        .stream().chatResponse())
                .thenReturn(Flux.just(SpringAiResponses.of("你好", 5),
                        SpringAiResponses.of("！", 7)));
        Mockito.when(modelRouterService.getDefaultChatClient()).thenReturn(client);

        MvcResult mvcResult = mockMvc.perform(post("/open/v1/chat/stream")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"流式落库验证\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("data:你好");
        assertThat(body).contains("event:meta");
        assertThat(body).contains("messageId");

        // 落库：用户提问 + 聚合后的完整 AI 回复（"你好！"）
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var saved = messageRepository.findAll().stream()
                    .filter(m -> "流式落库验证".equals(m.getContent())
                            || "你好！".equals(m.getContent()))
                    .toList();
            assertThat(saved).hasSize(2);
        });
    }
}
```

- [ ] **Step 5: 跑全部新 IT + 受影响的既有 IT**

Run: `cd backend && mvn verify -Dit.test='AnalyticsRollupIT,ChatFeedbackIT,StreamPersistenceIT,ApiUsageMeteringIT,OpenApiAuthIT,TokenMeteringIT'`
Expected: 全部 PASS（`/stream` 改 `ServerSentEvent` 后 SSE data 行格式不变，既有 IT 应兼容；若断言流式响应体的 IT 失败，按 `data:` 行语义修断言）

- [ ] **Step 6: 全量 verify**

Run: `cd backend && mvn verify`
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/AnalyticsRollupIT.java backend/src/test/java/com/qingyu/care/it/ChatFeedbackIT.java backend/src/test/java/com/qingyu/care/it/StreamPersistenceIT.java
git commit -m "test: rollup 聚合/反馈鉴权/流式落库集成回归网"
```

---

### Task 7: 前端——数据分析页 Tab 化 + 三个看板 Tab + 会话标注

**Files:**
- Modify: `frontend/src/api/analytics.ts`、`frontend/src/api/chat.ts`
- Modify: `frontend/src/pages/UsagePage.tsx`
- Create: `frontend/src/pages/analytics/QualityTab.tsx`、`HotTopicsTab.tsx`、`CoverageTab.tsx`
- Modify: `frontend/src/components/SessionMessages.tsx`

- [ ] **Step 1: analytics.ts 追加类型与方法**

在文件末尾 `analyticsService` 前追加类型，`analyticsService` 内追加方法：

```ts
// ===== Phase 4.4 分析三件套 =====

export interface QualitySummary {
    aiMsgCount: number;
    avgConfidence: number;
    lowConfidenceRate: number;
    fallbackRate: number;
    feedbackUp: number;
    feedbackDown: number;
    aiResolutionRate: number;
    handoverRate: number;
}

export interface QualityTrendPoint {
    date: string;
    aiMsgCount: number;
    avgConfidence: number;
    feedbackUp: number;
    feedbackDown: number;
}

export interface QualityAnalytics {
    summary: QualitySummary;
    trend: QualityTrendPoint[];
    scope: 'PLATFORM' | 'TENANT';
    statCutoff: string;
}

export interface KeywordItem { keyword: string; freq: number }
export interface QuestionItem { content: string; count: number }
export interface HotTopics {
    keywords: KeywordItem[];
    questions: QuestionItem[];
    scope: 'PLATFORM' | 'TENANT';
    statCutoff: string;
}

export interface CoverageSummary { answeredCount: number; hitCount: number; hitRate: number }
export interface CoverageTrendPoint { date: string; answeredCount: number; hitCount: number; hitRate: number }
export interface DocHitItem { documentId: number; name: string; hits: number }
export interface DocItem { documentId: number; name: string }
export interface CoverageAnalytics {
    summary: CoverageSummary;
    trend: CoverageTrendPoint[];
    topDocs: DocHitItem[];
    zeroHitDocs: DocItem[];
    scope: 'PLATFORM' | 'TENANT';
    statCutoff: string;
}

export interface MissedQuestion { content: string; createdAt: string; sessionId: string }
export interface MissPage {
    content: MissedQuestion[];
    totalPages: number;
    totalElements: number;
}
```

```ts
    // 对话质量看板
    getQuality: (days: number, appId?: number): Promise<ApiResponse<QualityAnalytics>> =>
        api.get('/analytics/quality', { params: { days, ...(appId ? { appId } : {}) } }),

    // 热点问题统计
    getHotTopics: (days: number, appId?: number): Promise<ApiResponse<HotTopics>> =>
        api.get('/analytics/hot-topics', { params: { days, ...(appId ? { appId } : {}) } }),

    // 知识覆盖率
    getCoverage: (days: number, appId?: number): Promise<ApiResponse<CoverageAnalytics>> =>
        api.get('/analytics/coverage', { params: { days, ...(appId ? { appId } : {}) } }),

    // 未命中提问明细（分页）
    getMisses: (days: number, page: number, appId?: number): Promise<ApiResponse<MissPage>> =>
        api.get('/analytics/coverage/misses', {
            params: { days, page, size: 20, ...(appId ? { appId } : {}) },
        }),
```

- [ ] **Step 2: chat.ts 追加反馈方法**

`chatService` 对象内追加：

```ts
    // 消息反馈（1=赞 / -1=踩，重复提交覆盖）
    submitFeedback: (sessionId: string, messageId: number, feedback: 1 | -1): Promise<ApiResponse<void>> =>
        api.post(`/chat/session/${sessionId}/message/${messageId}/feedback`, null, { params: { feedback } }),
```

- [ ] **Step 3: 三个 Tab 组件**

`frontend/src/pages/analytics/QualityTab.tsx`：

```tsx
import React, { useState, useEffect, useCallback } from 'react';
import {
    LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import { Loader2, Bot, Gauge, ThumbsUp, ThumbsDown, ShieldAlert, LifeBuoy } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { analyticsService } from '@/api/analytics';
import type { QualityAnalytics } from '@/api/analytics';
import { useToast } from '@/hooks/use-toast';

interface Props { days: number; appId?: number }

/** 对话质量看板 Tab（数据来自 rollup，T+1，statCutoff 为统计截止日） */
const QualityTab: React.FC<Props> = ({ days, appId }) => {
    const [data, setData] = useState<QualityAnalytics | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    const fetchData = useCallback(async () => {
        setIsLoading(true);
        try {
            const res = await analyticsService.getQuality(days, appId);
            if (res.code === 200) setData(res.data);
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '加载失败', description: msg });
        } finally {
            setIsLoading(false);
        }
    }, [days, appId, toast]);

    useEffect(() => { fetchData(); }, [fetchData]);

    if (isLoading && !data) {
        return <div className="flex h-64 items-center justify-center"><Loader2 className="w-8 h-8 animate-spin text-primary" /></div>;
    }
    const s = data?.summary;

    return (
        <div className="space-y-6">
            <p className="text-xs text-muted-foreground">统计截止 {data?.statCutoff}（预聚合 T+1，不含当日）</p>
            <div className="grid grid-cols-2 md:grid-cols-6 gap-4">
                <Stat icon={<Bot className="w-4 h-4 text-primary" />} label="AI 回复数" value={s?.aiMsgCount ?? 0} />
                <Stat icon={<Gauge className="w-4 h-4 text-blue-500" />} label="平均置信度" value={s?.avgConfidence ?? 0} />
                <Stat icon={<ShieldAlert className="w-4 h-4 text-orange-500" />} label="低置信率" value={`${s?.lowConfidenceRate ?? 0}%`} />
                <Stat icon={<LifeBuoy className="w-4 h-4 text-red-500" />} label="兜底回复率" value={`${s?.fallbackRate ?? 0}%`} />
                <Stat icon={<ThumbsUp className="w-4 h-4 text-green-500" />} label="赞" value={s?.feedbackUp ?? 0} />
                <Stat icon={<ThumbsDown className="w-4 h-4 text-rose-500" />} label="踩" value={s?.feedbackDown ?? 0} />
            </div>
            <Card>
                <CardHeader><CardTitle className="text-lg">回复量与反馈趋势</CardTitle></CardHeader>
                <CardContent>
                    <ResponsiveContainer width="100%" height={300}>
                        <LineChart data={data?.trend ?? []}>
                            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                            <YAxis tick={{ fontSize: 12 }} />
                            <Tooltip />
                            <Legend />
                            <Line type="monotone" dataKey="aiMsgCount" name="AI 回复数" stroke="#6366f1" dot={false} />
                            <Line type="monotone" dataKey="feedbackUp" name="赞" stroke="#10b981" dot={false} />
                            <Line type="monotone" dataKey="feedbackDown" name="踩" stroke="#f43f5e" dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </CardContent>
            </Card>
            <Card>
                <CardHeader><CardTitle className="text-lg">平均置信度趋势</CardTitle></CardHeader>
                <CardContent>
                    <ResponsiveContainer width="100%" height={220}>
                        <LineChart data={data?.trend ?? []}>
                            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                            <YAxis domain={[0, 1]} tick={{ fontSize: 12 }} />
                            <Tooltip />
                            <Line type="monotone" dataKey="avgConfidence" name="平均置信度" stroke="#f59e0b" dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </CardContent>
            </Card>
        </div>
    );
};

const Stat: React.FC<{ icon: React.ReactNode; label: string; value: React.ReactNode }> = ({ icon, label, value }) => (
    <Card>
        <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium flex items-center gap-2">{icon} {label}</CardTitle>
        </CardHeader>
        <CardContent><div className="text-2xl font-bold">{value}</div></CardContent>
    </Card>
);

export default QualityTab;
```

`frontend/src/pages/analytics/HotTopicsTab.tsx`：

```tsx
import React, { useState, useEffect, useCallback } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
} from 'recharts';
import { Loader2 } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { analyticsService } from '@/api/analytics';
import type { HotTopics } from '@/api/analytics';
import { useToast } from '@/hooks/use-toast';

interface Props { days: number; appId?: number }

/** 热点问题 Tab：关键词 TopN（rollup）+ 高频问句 TopN（实时） */
const HotTopicsTab: React.FC<Props> = ({ days, appId }) => {
    const [data, setData] = useState<HotTopics | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    const fetchData = useCallback(async () => {
        setIsLoading(true);
        try {
            const res = await analyticsService.getHotTopics(days, appId);
            if (res.code === 200) setData(res.data);
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '加载失败', description: msg });
        } finally {
            setIsLoading(false);
        }
    }, [days, appId, toast]);

    useEffect(() => { fetchData(); }, [fetchData]);

    if (isLoading && !data) {
        return <div className="flex h-64 items-center justify-center"><Loader2 className="w-8 h-8 animate-spin text-primary" /></div>;
    }

    return (
        <div className="space-y-6">
            <p className="text-xs text-muted-foreground">关键词统计截止 {data?.statCutoff}（T+1）；高频问句为实时数据</p>
            <Card>
                <CardHeader><CardTitle className="text-lg">热点关键词 Top 20</CardTitle></CardHeader>
                <CardContent>
                    {(data?.keywords?.length ?? 0) === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">暂无数据</div>
                    ) : (
                        <ResponsiveContainer width="100%" height={Math.max(240, (data?.keywords.length ?? 0) * 28)}>
                            <BarChart data={data?.keywords ?? []} layout="vertical" margin={{ left: 24 }}>
                                <XAxis type="number" tick={{ fontSize: 12 }} />
                                <YAxis type="category" dataKey="keyword" width={100} tick={{ fontSize: 12 }} />
                                <Tooltip />
                                <Bar dataKey="freq" name="词频" fill="#6366f1" radius={[0, 4, 4, 0]} />
                            </BarChart>
                        </ResponsiveContainer>
                    )}
                </CardContent>
            </Card>
            <Card>
                <CardHeader><CardTitle className="text-lg">高频问句 Top 10</CardTitle></CardHeader>
                <CardContent>
                    {(data?.questions?.length ?? 0) === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">暂无数据</div>
                    ) : (
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="text-muted-foreground border-b">
                                    <th className="text-left py-2 font-medium">问句</th>
                                    <th className="text-right py-2 font-medium">次数</th>
                                </tr>
                            </thead>
                            <tbody>
                                {data?.questions.map((q) => (
                                    <tr key={q.content} className="border-b last:border-0">
                                        <td className="py-2">{q.content}</td>
                                        <td className="py-2 text-right font-mono">{q.count}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </CardContent>
            </Card>
        </div>
    );
};

export default HotTopicsTab;
```

`frontend/src/pages/analytics/CoverageTab.tsx`：

```tsx
import React, { useState, useEffect, useCallback } from 'react';
import {
    LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { Loader2, Target, FileSearch, FileX } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { analyticsService } from '@/api/analytics';
import type { CoverageAnalytics, MissPage } from '@/api/analytics';
import { useToast } from '@/hooks/use-toast';

interface Props { days: number; appId?: number }

/** 知识覆盖率 Tab：命中率趋势 + 文档命中排行 + 零命中文档 + 未命中提问明细 */
const CoverageTab: React.FC<Props> = ({ days, appId }) => {
    const [data, setData] = useState<CoverageAnalytics | null>(null);
    const [misses, setMisses] = useState<MissPage | null>(null);
    const [missPage, setMissPage] = useState(0);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    const fetchData = useCallback(async () => {
        setIsLoading(true);
        try {
            const res = await analyticsService.getCoverage(days, appId);
            if (res.code === 200) setData(res.data);
        } catch (error) {
            const msg = error instanceof Error ? error.message : String(error);
            toast({ variant: 'destructive', title: '加载失败', description: msg });
        } finally {
            setIsLoading(false);
        }
    }, [days, appId, toast]);

    const fetchMisses = useCallback(async () => {
        try {
            const res = await analyticsService.getMisses(days, missPage, appId);
            if (res.code === 200) setMisses(res.data);
        } catch { /* 明细失败不阻断整页 */ }
    }, [days, missPage, appId]);

    useEffect(() => { fetchData(); }, [fetchData]);
    useEffect(() => { fetchMisses(); }, [fetchMisses]);
    // days/appId 变化时回到第 0 页
    useEffect(() => { setMissPage(0); }, [days, appId]);

    if (isLoading && !data) {
        return <div className="flex h-64 items-center justify-center"><Loader2 className="w-8 h-8 animate-spin text-primary" /></div>;
    }
    const s = data?.summary;

    return (
        <div className="space-y-6">
            <p className="text-xs text-muted-foreground">命中统计截止 {data?.statCutoff}（T+1）；未命中明细为实时数据</p>
            <div className="grid grid-cols-3 gap-4">
                <Stat icon={<FileSearch className="w-4 h-4 text-primary" />} label="AI 回答数" value={s?.answeredCount ?? 0} />
                <Stat icon={<Target className="w-4 h-4 text-green-500" />} label="命中知识数" value={s?.hitCount ?? 0} />
                <Stat icon={<Target className="w-4 h-4 text-blue-500" />} label="命中率" value={`${s?.hitRate ?? 0}%`} />
            </div>
            <Card>
                <CardHeader><CardTitle className="text-lg">命中率趋势</CardTitle></CardHeader>
                <CardContent>
                    <ResponsiveContainer width="100%" height={260}>
                        <LineChart data={data?.trend ?? []}>
                            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                            <YAxis domain={[0, 100]} tick={{ fontSize: 12 }} unit="%" />
                            <Tooltip />
                            <Line type="monotone" dataKey="hitRate" name="命中率" stroke="#10b981" dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </CardContent>
            </Card>
            <div className="grid md:grid-cols-2 gap-6">
                <Card>
                    <CardHeader><CardTitle className="text-lg">文档命中排行 Top 10</CardTitle></CardHeader>
                    <CardContent>
                        {(data?.topDocs?.length ?? 0) === 0 ? (
                            <div className="text-center py-8 text-muted-foreground opacity-50">暂无数据</div>
                        ) : (
                            <table className="w-full text-sm">
                                <thead>
                                    <tr className="text-muted-foreground border-b">
                                        <th className="text-left py-2 font-medium">文档</th>
                                        <th className="text-right py-2 font-medium">命中次数</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {data?.topDocs.map((d) => (
                                        <tr key={d.documentId} className="border-b last:border-0">
                                            <td className="py-2">{d.name}</td>
                                            <td className="py-2 text-right font-mono">{d.hits}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        )}
                    </CardContent>
                </Card>
                <Card>
                    <CardHeader>
                        <CardTitle className="text-lg flex items-center gap-2">
                            <FileX className="w-4 h-4 text-orange-500" /> 零命中文档（死知识）
                        </CardTitle>
                    </CardHeader>
                    <CardContent>
                        {(data?.zeroHitDocs?.length ?? 0) === 0 ? (
                            <div className="text-center py-8 text-muted-foreground opacity-50">窗口内全部文档均有命中</div>
                        ) : (
                            <ul className="text-sm space-y-1">
                                {data?.zeroHitDocs.map((d) => (
                                    <li key={d.documentId} className="border-b last:border-0 py-1.5">{d.name}</li>
                                ))}
                            </ul>
                        )}
                    </CardContent>
                </Card>
            </div>
            <Card>
                <CardHeader><CardTitle className="text-lg">未命中提问（知识缺口）</CardTitle></CardHeader>
                <CardContent className="space-y-3">
                    {(misses?.content?.length ?? 0) === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">暂无未命中提问</div>
                    ) : (
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="text-muted-foreground border-b">
                                    <th className="text-left py-2 font-medium">提问</th>
                                    <th className="text-right py-2 font-medium w-40">时间</th>
                                </tr>
                            </thead>
                            <tbody>
                                {misses?.content.map((m, i) => (
                                    <tr key={`${m.sessionId}-${i}`} className="border-b last:border-0">
                                        <td className="py-2">{m.content}</td>
                                        <td className="py-2 text-right text-xs text-muted-foreground font-mono">
                                            {new Date(m.createdAt).toLocaleString()}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                    {(misses?.totalPages ?? 0) > 1 && (
                        <div className="flex justify-end gap-2">
                            <Button variant="outline" size="sm" disabled={missPage === 0}
                                onClick={() => setMissPage((p) => p - 1)}>上一页</Button>
                            <Button variant="outline" size="sm"
                                disabled={missPage >= (misses?.totalPages ?? 1) - 1}
                                onClick={() => setMissPage((p) => p + 1)}>下一页</Button>
                        </div>
                    )}
                </CardContent>
            </Card>
        </div>
    );
};

const Stat: React.FC<{ icon: React.ReactNode; label: string; value: React.ReactNode }> = ({ icon, label, value }) => (
    <Card>
        <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium flex items-center gap-2">{icon} {label}</CardTitle>
        </CardHeader>
        <CardContent><div className="text-2xl font-bold">{value}</div></CardContent>
    </Card>
);

export default CoverageTab;
```

- [ ] **Step 4: UsagePage Tab 化**

改动点（保持原有用量内容不动，只包一层 section 切换）：

1. 顶部 import 追加：

```tsx
import QualityTab from '@/pages/analytics/QualityTab';
import HotTopicsTab from '@/pages/analytics/HotTopicsTab';
import CoverageTab from '@/pages/analytics/CoverageTab';
```

2. `DAY_OPTIONS` 后追加：

```tsx
const SECTION_TABS = [
    { value: 'usage', label: '用量' },
    { value: 'quality', label: '质量' },
    { value: 'hot', label: '热点' },
    { value: 'coverage', label: '覆盖率' },
];
```

3. 组件内 state 追加 `const [section, setSection] = useState('usage');`

4. 标题「用量统计」改为「数据分析」，描述保留。

5. 头部控制区（`<div className="flex items-center gap-3">` 内、天数 Tabs 之前）插入 section 切换：

```tsx
                    <Tabs value={section} onValueChange={setSection}>
                        <TabsList>
                            {SECTION_TABS.map((t) => (
                                <TabsTrigger key={t.value} value={t.value}>{t.label}</TabsTrigger>
                            ))}
                        </TabsList>
                    </Tabs>
```

6. 将「汇总卡片 / 本月额度 / 趋势折线图 / 排行榜」四块 JSX 包进 `{section === 'usage' && (<> ... </>)}`；其后追加：

```tsx
            {section === 'quality' && (
                <QualityTab days={Number(days)} appId={appId === ALL_APPS ? undefined : Number(appId)} />
            )}
            {section === 'hot' && (
                <HotTopicsTab days={Number(days)} appId={appId === ALL_APPS ? undefined : Number(appId)} />
            )}
            {section === 'coverage' && (
                <CoverageTab days={Number(days)} appId={appId === ALL_APPS ? undefined : Number(appId)} />
            )}
```

（应用下钻 Select 依赖 `data?.scope === 'TENANT'`，usage 数据仍在页面加载时拉取，不受 section 影响——保持现状即可。）

- [ ] **Step 5: SessionMessages 人工标注**

改动点：

1. import 追加 `ThumbsUp, ThumbsDown`（lucide-react）与 `useToast`；
2. `Message` 接口追加 `feedback?: number | null;`
3. 组件内追加处理函数：

```tsx
    const { toast } = useToast();

    const handleFeedback = async (msg: Message, value: 1 | -1) => {
        try {
            await chatService.submitFeedback(sessionId, msg.id, value);
            setMessages((prev) => prev.map((m) => (m.id === msg.id ? { ...m, feedback: value } : m)));
        } catch (err: any) {
            toast({ variant: 'destructive', title: '评价失败', description: err.message });
        }
    };
```

4. 消息气泡内时间戳 `<p>` 之后（仍在气泡 div 内）追加：

```tsx
                            {msg.role !== 'USER' && (
                                <div className="flex gap-1 justify-end mt-1">
                                    <button
                                        onClick={() => handleFeedback(msg, 1)}
                                        className={cn('p-0.5 rounded hover:opacity-100',
                                            msg.feedback === 1 ? 'opacity-100' : 'opacity-40')}
                                        aria-label="赞"
                                    >
                                        <ThumbsUp className="w-3 h-3" />
                                    </button>
                                    <button
                                        onClick={() => handleFeedback(msg, -1)}
                                        className={cn('p-0.5 rounded hover:opacity-100',
                                            msg.feedback === -1 ? 'opacity-100' : 'opacity-40')}
                                        aria-label="踩"
                                    >
                                        <ThumbsDown className="w-3 h-3" />
                                    </button>
                                </div>
                            )}
```

- [ ] **Step 6: 构建与 lint 验证**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建成功、lint 无新错误

- [ ] **Step 7: 提交**

```bash
git add frontend/src/api/analytics.ts frontend/src/api/chat.ts frontend/src/pages/UsagePage.tsx frontend/src/pages/analytics/ frontend/src/components/SessionMessages.tsx
git commit -m "feat: 数据分析页 Tab 化（质量/热点/覆盖率看板）+ 会话详情人工赞踩标注"
```

---

### Task 8: Widget 赞/踩（须重新 build 提交 widget.js）

**Files:**
- Modify: `widget/src/api.ts`、`widget/src/ui.ts`、`widget/src/main.ts`、`widget/src/styles.ts`
- Test: `widget/src/api.test.ts`（追加用例，mock 风格对齐既有用例）
- 产物: `backend/src/main/resources/static/widget.js`（构建产物，必须提交）

- [ ] **Step 1: api.ts 加 messageId 与 feedback**

`ChatReply` 追加字段：

```ts
export interface ChatReply {
  sessionId: string;
  reply: string;
  handoverSuggested: boolean;
  /** AI 消息 ID（提交赞/踩时使用；后端旧版本可能缺省） */
  messageId?: number;
}
```

`createApi` 返回对象内追加方法：

```ts
    /** 消息反馈（1=赞 / -1=踩）；失败静默（不干扰对话） */
    async feedback(sessionId: string, messageId: number, value: 1 | -1): Promise<void> {
      await fetch(`${cfg.apiBase}/open/v1/chat/feedback`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ sessionId, messageId, feedback: value }),
      });
    },
```

- [ ] **Step 2: api.test.ts 追加用例（写完先跑，确认红→绿）**

参照文件内既有 fetch mock 风格（若用 `vi.stubGlobal` 以外的方式则对齐调整）：

```ts
  it('feedback 提交到开放反馈端点', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    const api = createApi(cfg);   // cfg 复用文件内既有测试配置
    await api.feedback('s-1', 42, 1);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/open/v1/chat/feedback'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ sessionId: 's-1', messageId: 42, feedback: 1 }),
      }),
    );
  });
```

Run: `cd widget && pnpm test`
Expected: 新用例先失败（feedback 未实现时）→ Step 1 实现后全部 PASS

- [ ] **Step 3: ui.ts 加反馈按钮**

`WidgetUi` 接口与 `addBotMessage` 改为：

```ts
export interface BotFeedback {
  onFeedback: (value: 1 | -1) => void;
}

export interface WidgetUi {
  addUserMessage: (text: string) => void;
  addBotMessage: (text: string, feedback?: BotFeedback) => void;
  setTyping: (on: boolean) => void;
  setInputEnabled: (on: boolean) => void;
}
```

`mountUi` 内 `addBotMessage` 实现替换为：

```ts
  function addBotMessage(text: string, feedback?: BotFeedback) {
    const row = appendMsg(text, 'bot');
    if (feedback) {
      const bar = document.createElement('div');
      bar.className = 'qy-feedback';
      const up = document.createElement('button');
      up.type = 'button';
      up.textContent = '👍';
      up.setAttribute('aria-label', '有帮助');
      const down = document.createElement('button');
      down.type = 'button';
      down.textContent = '👎';
      down.setAttribute('aria-label', '没帮助');
      const pick = (value: 1 | -1, chosen: HTMLButtonElement) => {
        feedback.onFeedback(value);
        up.disabled = true;
        down.disabled = true;
        chosen.classList.add('active');
      };
      up.addEventListener('click', () => pick(1, up));
      down.addEventListener('click', () => pick(-1, down));
      bar.appendChild(up);
      bar.appendChild(down);
      row.appendChild(bar);
      msgs.scrollTop = msgs.scrollHeight;
    }
  }
```

- [ ] **Step 4: styles.ts 追加样式**

在 `buildStyles` 返回的样式串中追加：

```css
.qy-msg .qy-feedback { display: flex; gap: 4px; margin-top: 4px; }
.qy-msg.bot .qy-feedback { justify-content: flex-start; }
.qy-feedback button { background: none; border: 1px solid #e5e7eb; border-radius: 10px;
  cursor: pointer; font-size: 12px; line-height: 1; padding: 2px 8px; opacity: .7; }
.qy-feedback button:hover { opacity: 1; }
.qy-feedback button:disabled { cursor: default; opacity: .4; }
.qy-feedback button.active { opacity: 1; border-color: currentColor; }
```

- [ ] **Step 5: main.ts 接线**

`handleSend` 内 `ui.addBotMessage(reply.reply);` 替换为：

```ts
      const mid = reply.messageId;
      ui.addBotMessage(
        reply.reply,
        mid
          ? { onFeedback: (v) => void api.feedback(reply.sessionId, mid, v) }
          : undefined,
      );
```

- [ ] **Step 6: 测试 + 构建 + 提交产物**

Run: `cd widget && pnpm test && pnpm build`
Expected: vitest 全部 PASS；`backend/src/main/resources/static/widget.js` 更新（LF 行尾由 .gitattributes 保证）

```bash
git add widget/src/ backend/src/main/resources/static/widget.js
git commit -m "feat: Widget 赞/踩反馈按钮（重新构建 widget.js）"
```

---

### Task 9: 收尾——文档更新 + 全量验证

**Files:**
- Modify: `SaaS平台规划.md`
- Modify: `docs/superpowers/specs/2026-07-09-analytics-suite-design.md`

- [ ] **Step 1: 规划勾选**

`SaaS平台规划.md` 158-161 行，前三项打勾（A/B 测试保留）：

```markdown
### 4.4 分析与优化
- [x] 对话质量评估
- [x] 热点问题统计
- [x] 知识库覆盖率分析
- [ ] A/B 测试
```

187 行 Phase 4 状态同步：将「剩余：多渠道接入、分析与优化」改为「剩余：4.2 多渠道接入、4.4 A/B 测试」。

- [ ] **Step 2: spec 状态置为已实现**

spec 头部 `status: 设计完成，待实现` 改为 `status: 已实现`；如实施中发现与 spec 的偏差（字面量、状态映射等），在 spec 末尾补一节「实施偏差记录」。

- [ ] **Step 3: 全量验证（兜底）**

Run: `cd backend && mvn clean install`
Expected: BUILD SUCCESS（单测全绿）
Run: `cd backend && mvn verify`
Expected: BUILD SUCCESS（IT 全绿，需 Docker Desktop）
Run: `cd frontend && pnpm build && pnpm lint`
Expected: 通过
Run: `cd widget && pnpm test`
Expected: 通过

- [ ] **Step 4: 提交**

```bash
git add SaaS平台规划.md docs/superpowers/specs/2026-07-09-analytics-suite-design.md
git commit -m "docs: Phase 4.4 分析三件套完成（规划勾选 + spec 置为已实现）"
```

---

## 风险与已知取舍备忘（执行时对照）

1. **`unnest(tsvector)`/`FULL JOIN`/`LEFT JOIN LATERAL`** 均为 PostgreSQL 原生语法（pg17 支持），H2 等不兼容——本项目 IT 即真实 PG，无此问题。
2. **IT 种子必须 `saveAndFlush`**：rollup/明细走 native SQL，Hibernate 不会自动 flush。
3. **stat 表/ChatMessage 均非租户过滤实体**：查询 where 全手写，新增查询时勿依赖过滤器。
4. **`/stream` 返回类型改 `ServerSentEvent`**：data 行格式不变，理论向后兼容；跑受影响 IT 确认。
5. **流式 AI 消息 tokenUsage 为 null**（已知取舍，计量不受影响）；error/cancel 的半截回复不落库。
6. **反馈迟到窗口**：定时任务重跑近 2 天吸收；更晚的反馈需手动回填端点补算。
7. **字面量核对步骤**（TenantContext 方法名、DocumentStatus 枚举、GlobalExceptionHandler 映射、@AuditLog 属性名）执行时先做，不符则同步调整代码。

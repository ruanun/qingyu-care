# 开放 API Token 记账 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 LLM 响应的 token 用量接回：非流式填 `ChatResponse.tokenUsage` 并计量真实 token；流式端点补上计量（含末尾 usage），且计量保持开放 API 专属。

**Architecture:** `ModelRouterService.callWithFallback` 泛型化以透出完整响应；`chat()` 从 Spring AI `ChatResponse` 取 content+usage 填 `tokenUsage`（不记账）；`streamChat` 增带 `IntConsumer` 完成回调的重载，`.stream().chatResponse()` tap 末尾 usage、`doFinally` 触发回调；开放控制器在请求线程捕获 tenantId/appId 并在回调里 `record`；内部 `ChatController` 用无回调重载（不计量）。

**Tech Stack:** Spring AI 1.0.0-M4（OpenAI 兼容）、Reactor、JUnit5、Testcontainers（延续现有回归网）。

**参考 spec:** `docs/superpowers/specs/2026-07-04-token-accounting-design.md`

---

## 关键前置事实（实现者必读）

- **命名冲突**：项目自有 DTO `com.qingyu.care.dto.ChatResponse`（`ChatServiceImpl` 已 import 并用 `ChatResponse.builder()`）。Spring AI 的响应类型是 `org.springframework.ai.chat.model.ChatResponse`——**两者同名**。凡引用 Spring AI 的，一律用**全限定名**，不要 import（否则与 dto 冲突）。
- **`callWithFallback` 现状**（`ModelRouterService`）：`String callWithFallback(String scenario, Function<ChatClient,String> promptRunner)`，内部遍历模型 try/catch 降级。2 个调用点：`ChatServiceImpl.chat`（line ~141，返回 `.content()`）、摘要生成（line ~232，返回 String）。
- **现有回归 IT 会受影响**：`PromptTemplateFallbackIT` 与 `ChatStrategyFallbackIT` 用 `@MockitoBean ModelRouterService` 打桩 `callWithFallback(eq("default"), any())` **返回 String `"MOCK_AI_REPLY"`**。Task 2 让 `chat()` 改从 `callWithFallback` 拿 Spring AI `ChatResponse` 后，`PromptTemplateFallbackIT`（绿路径走模型）的桩会 CCE，**必须更新**为返回 Spring AI `ChatResponse`；`ChatStrategyFallbackIT` 绿路径在策略兜底分支提前返回、不走模型，但为一致**也更新**。
- **Spring AI M4 API 不确定项**（实现时用 IDE/jar 核实，按真实签名适配）：`.call().chatResponse()` 取响应；从响应取文本（M4 很可能是 `resp.getResult().getOutput().getContent()`，1.0 GA 才是 `getText()`）；`resp.getMetadata().getUsage().getTotalTokens()`（返回 `Integer`/`Long` 待定）；构造测试用响应（`new AssistantMessage(text)` / `new Generation(assistantMessage)` / `new DefaultUsage(pt,ct,tt)` / `ChatResponseMetadata.builder().usage(u).build()` / `new org.springframework.ai.chat.model.ChatResponse(List<Generation>, metadata)`）；流式 usage 开启（`OpenAiChatOptions.builder().streamUsage(true)` 或全局属性）+ `.stream().chatResponse()`。

---

## Task 1: `callWithFallback` 泛型化

**Files:** Modify `backend/src/main/java/com/qingyu/care/service/ModelRouterService.java`

- [ ] **Step 1: 改签名与返回类型为泛型**

把方法头与内部 `String result` 改为泛型 `<T>`：

```java
public <T> T callWithFallback(String scenario, java.util.function.Function<ChatClient, T> promptRunner) {
    List<AiModelConfig> models = getModelsForScenario(scenario);

    if (models.isEmpty()) {
        throw new RuntimeException("没有可用的 AI 模型配置");
    }

    Exception lastException = null;

    for (AiModelConfig model : models) {
        try {
            log.debug("Calling model: {} ({})", model.getName(), model.getModelId());
            ChatClient client = createChatClient(model);
            T result = promptRunner.apply(client);
            log.debug("Model {} returned successfully", model.getName());
            return result;
        } catch (Exception e) {
            log.warn("Model {} failed: {}, trying next...", model.getName(), e.getMessage());
            lastException = e;
        }
    }

    log.error("All models failed for scenario: {}", scenario);
    throw new RuntimeException("所有 AI 模型调用失败", lastException);
}
```

（若类顶部已 import `java.util.function.Function`，用简单名即可；否则用全限定名如上。）

- [ ] **Step 2: 编译 + 现有单测不破坏**

现有 2 个调用点的 lambda 都返回 String（T 推断为 String），无需改。
Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test`
Expected: 60 单测全绿、BUILD SUCCESS（纯重构，无行为变化）。

- [ ] **Step 3: 提交**

```bash
git -C "D:/Code/m/qingyu-care" add backend/src/main/java/com/qingyu/care/service/ModelRouterService.java
git -C "D:/Code/m/qingyu-care" commit -m "refactor: ModelRouterService.callWithFallback 泛型化以透出完整响应"
```

---

## Task 2: 非流式 —— 接回 usage 填 `tokenUsage`

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（chat 的模型调用段 ~141-152）
- Modify: `backend/src/test/java/com/qingyu/care/it/PromptTemplateFallbackIT.java`（桩改返回 Spring AI ChatResponse）
- Modify: `backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java`（同上，一致性）
- Create: `backend/src/test/java/com/qingyu/care/support/SpringAiResponses.java`（测试辅助：构造 Spring AI ChatResponse）
- Create: `backend/src/test/java/com/qingyu/care/it/TokenMeteringIT.java`

- [ ] **Step 1: 核实 Spring AI M4 API**

Run（确认取文本与 usage 的真实方法名、构造签名）：
```bash
cd /d/Code/m/qingyu-care/backend
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt >/dev/null 2>&1
# 用 IDE 或 javap 查看 M4 类的方法签名：
javap -cp "$(cat /tmp/cp.txt)" org.springframework.ai.chat.model.ChatResponse 2>&1 | head -30
javap -cp "$(cat /tmp/cp.txt)" org.springframework.ai.chat.messages.AssistantMessage 2>&1 | grep -iE "getText|getContent|AssistantMessage\("
javap -cp "$(cat /tmp/cp.txt)" org.springframework.ai.chat.metadata.Usage 2>&1 | grep -iE "getTotalTokens"
javap -cp "$(cat /tmp/cp.txt)" org.springframework.ai.chat.metadata.DefaultUsage 2>&1 | grep -iE "DefaultUsage\("
```
记录真实签名（取文本方法名 = `getContent()` 或 `getText()`；`getTotalTokens()` 返回类型；`DefaultUsage` 构造参数顺序）。下面代码按最可能签名写，**按 javap 结果适配**。

- [ ] **Step 2: 写测试辅助 `SpringAiResponses`（构造 Spring AI ChatResponse）**

`backend/src/test/java/com/qingyu/care/support/SpringAiResponses.java`：

```java
package com.qingyu.care.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

/** 测试辅助：构造带 usage 的 Spring AI ChatResponse，供 @MockitoBean 打桩 callWithFallback。 */
public final class SpringAiResponses {
    private SpringAiResponses() {}

    /** 按 M4 实际构造签名适配（见 Task 2 Step 1 javap 结果）。 */
    public static ChatResponse of(String text, int totalTokens) {
        Generation gen = new Generation(new AssistantMessage(text));
        ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(0, totalTokens, totalTokens))
                .build();
        return new ChatResponse(List.of(gen), meta);
    }
}
```

- [ ] **Step 3: 写 `TokenMeteringIT`（RED：现在 token_count=0）**

`backend/src/test/java/com/qingyu/care/it/TokenMeteringIT.java`：

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 非流式开放 API 对话后，sys_api_usage.token_count 应为真实 total_tokens（回归 token_count 恒 0）。 */
class TokenMeteringIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SysApiUsageRepository usageRepository;

    @MockitoBean
    private ModelRouterService modelRouterService;

    private Application app;

    @AfterEach
    void cleanup() {
        if (app != null) {
            usageRepository.findAll().stream()
                    .filter(u -> app.getId().equals(u.getAppId()))
                    .forEach(usageRepository::delete);
        }
    }

    @Test
    void 非流式对话计量真实token() throws Exception {
        app = seedApp("tokmeter", "http://it.example.com");
        // 打桩：任意模型调用返回带 usage(total=42) 的 Spring AI 响应
        Mockito.when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("MOCK_AI_REPLY", 42));

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey()).header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        await().atMost(5, SECONDS).untilAsserted(() -> {
            var rows = usageRepository.findAll().stream()
                    .filter(u -> app.getId().equals(u.getAppId())).toList();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTokenCount()).isEqualTo(42);
        });
    }
}
```

- [ ] **Step 4: 运行确认 RED**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=TokenMeteringIT -Dtest=none -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **FAIL** —— 一是当前 `callWithFallback` 桩返回类型与 chat 期望不符会 CCE，二是即便跑通 token_count 也是 0（尚未接 usage）。（这是 RED，Step 5-6 修好。）

- [ ] **Step 5: 改 `ChatServiceImpl.chat` 接回 usage（GREEN 生产改动）**

把 chat 里的模型调用段（原 `String response = modelRouterService.callWithFallback("default", client -> client.prompt().messages(finalMessages).call().content());`）替换为：

```java
        // 调用AI（动态模型路由）；取完整响应以拿到 token 用量
        final List<Message> finalMessages = messages;
        org.springframework.ai.chat.model.ChatResponse aiResp =
                modelRouterService.callWithFallback("default", client ->
                        client.prompt().messages(finalMessages).call().chatResponse());
        // ↓ 按 Task2 Step1 javap 结果适配 getContent()/getText()
        String response = aiResp.getResult().getOutput().getContent();
        Integer tokenUsage = (aiResp.getMetadata() != null && aiResp.getMetadata().getUsage() != null
                && aiResp.getMetadata().getUsage().getTotalTokens() != null)
                ? aiResp.getMetadata().getUsage().getTotalTokens().intValue()
                : null;
```

然后在该方法末尾的 `return ChatResponse.builder()...` 链里加一行 `.tokenUsage(tokenUsage)`（dto.ChatResponse 已有该字段）。**不在 chat() 内记账**（计量留在开放控制器）。

- [ ] **Step 6: 更新受影响的现有 IT 桩（返回 Spring AI ChatResponse）**

`PromptTemplateFallbackIT`：把
```java
Mockito.when(modelRouterService.callWithFallback(eq("default"), any())).thenReturn("MOCK_AI_REPLY");
```
改为
```java
Mockito.when(modelRouterService.callWithFallback(eq("default"), any()))
        .thenReturn(com.qingyu.care.support.SpringAiResponses.of("MOCK_AI_REPLY", 7));
```
`ChatStrategyFallbackIT` 同样把 `.thenReturn("MOCK_AI_REPLY")` 改为 `.thenReturn(...SpringAiResponses.of("MOCK_AI_REPLY", 7))`（其绿路径虽不走模型，但保持类型一致、避免 revert-proof 时 CCE）。

- [ ] **Step 7: 运行 TokenMeteringIT + 两个受影响 IT → GREEN**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test='TokenMeteringIT,PromptTemplateFallbackIT,ChatStrategyFallbackIT' -Dtest=none -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿（TokenMeteringIT: token_count==42；两个既有 IT 仍绿）。

- [ ] **Step 8: 提交**

```bash
git -C "D:/Code/m/qingyu-care" add backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java \
  backend/src/test/java/com/qingyu/care/support/SpringAiResponses.java \
  backend/src/test/java/com/qingyu/care/it/TokenMeteringIT.java \
  backend/src/test/java/com/qingyu/care/it/PromptTemplateFallbackIT.java \
  backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java
git -C "D:/Code/m/qingyu-care" commit -m "feat: 非流式对话接回 token 用量并计量真实 token_count"
```

---

## Task 3: 流式 —— 计量 + 末尾 usage

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatService.java`（接口加重载）
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（streamChat）
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`（stream 用带回调重载 + 捕获上下文）
- Create: `backend/src/test/java/com/qingyu/care/it/StreamMeteringIT.java`

- [ ] **Step 1: 核实 M4 流式 usage 开启方式**

Run:
```bash
cd /d/Code/m/qingyu-care/backend
javap -cp "$(cat /tmp/cp.txt)" org.springframework.ai.openai.OpenAiChatOptions 2>&1 | grep -iE "streamUsage|StreamOptions|includeUsage"
```
确认 M4 是否支持 `streamUsage(true)`（`OpenAiChatOptions.builder().streamUsage(true)`）。**若不支持**：本 Task 流式**只记调用次数、token 记 0**（在代码注释标注 M4 限制），StreamMeteringIT 相应只断言"计量行存在/call_count≥1"。

- [ ] **Step 2: 接口加重载 `ChatService.streamChat(request, IntConsumer)`**

在 `service/ChatService.java` 加：
```java
/** 流式对话，流正常/异常结束时以本次 total_tokens 回调（供开放 API 计量；内部调用用无回调重载）。 */
reactor.core.publisher.Flux<String> streamChat(com.qingyu.care.dto.ChatRequest request,
                                               java.util.function.IntConsumer onTokensUsed);
```
（若接口已 import Flux/ChatRequest，用简单名。）

- [ ] **Step 3: 实现重载并让无参版委托（GREEN 生产改动）**

在 `ChatServiceImpl` 把现有 `streamChat(request)` 改为委托，并加带回调实现。用 `.stream().chatResponse()` tap 末尾 usage、`doFinally` 触发回调：

```java
@Override
public Flux<String> streamChat(ChatRequest request) {
    return streamChat(request, t -> {}); // 内部调用：不计量
}

@Override
public Flux<String> streamChat(ChatRequest request, java.util.function.IntConsumer onTokensUsed) {
    ChatSession session = getOrCreateSession(request);
    ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");
    List<Document> relevantDocs = searchKnowledge(request.getMessage(), strategy.getTopK(), strategy.getSimilarityThreshold());
    String context = buildContext(relevantDocs);
    List<Message> messages = buildMessages(session, request.getMessage(), context);

    ChatClient chatClient = modelRouterService.getDefaultChatClient();
    java.util.concurrent.atomic.AtomicInteger tokens = new java.util.concurrent.atomic.AtomicInteger(0);
    return chatClient.prompt()
            .messages(messages)
            .stream()
            .chatResponse()   // Flux<org.springframework.ai.chat.model.ChatResponse>
            .doOnNext(cr -> {
                if (cr.getMetadata() != null && cr.getMetadata().getUsage() != null
                        && cr.getMetadata().getUsage().getTotalTokens() != null) {
                    tokens.set(cr.getMetadata().getUsage().getTotalTokens().intValue());
                }
            })
            .mapNotNull(cr -> (cr.getResult() != null && cr.getResult().getOutput() != null)
                    ? cr.getResult().getOutput().getContent() : null)  // 适配 getContent()/getText()
            .doFinally(sig -> onTokensUsed.accept(tokens.get()));
}
```
> 若 Step 1 确认 M4 不支持 streamUsage：仍用 `.chatResponse()`（拿不到 usage 时 tokens 保持 0），`doFinally` 照常回调（token=0，只记调用次数）。若 `.stream().chatResponse()` 在 M4 也不可用，退回 `.stream().content()` + `doFinally` 回调 token=0。

- [ ] **Step 4: 开放控制器 stream 用带回调重载 + 捕获上下文**

`OpenChatController.stream` 改为：
```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(@Valid @RequestBody OpenChatRequest req) {
    final Long tid = TenantContext.getTenantId();
    final Long aid = TenantContext.getAppId();
    return chatService.streamChat(toInternal(req, true),
            tokens -> apiUsageService.record(tid, aid, 1L, tokens));
}
```
（移除原 TODO 注释。内部 `ChatController.stream` 不动——继续调无参 `streamChat`，不计量。）

- [ ] **Step 5: 写 `StreamMeteringIT`（GREEN 验证）**

`backend/src/test/java/com/qingyu/care/it/StreamMeteringIT.java`：经 `POST /open/v1/chat/stream` 驱动，`@MockitoBean ModelRouterService` 打桩 `getDefaultChatClient()` 返回一个能产出固定流的 ChatClient（M4 下 mock ChatClient 较重——**优先**改为不 mock、断言"流结束后该应用有计量行"，token 视 M4 能否透出而定）。最小实现：

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 流式开放 API 对话完成后，该应用应产生 sys_api_usage 计量行（回归"流式端点不计量"）。 */
class StreamMeteringIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SysApiUsageRepository usageRepository;

    private Application app;

    @AfterEach
    void cleanup() {
        if (app != null) {
            usageRepository.findAll().stream()
                    .filter(u -> app.getId().equals(u.getAppId()))
                    .forEach(usageRepository::delete);
        }
    }

    @Test
    void 流式对话完成后产生计量行() throws Exception {
        app = seedApp("streammeter", "http://it.example.com");

        var mvcResult = mockMvc.perform(post("/open/v1/chat/stream")
                        .header("X-App-Id", app.getAppKey()).header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)); // 触发 SSE 完成

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var rows = usageRepository.findAll().stream()
                    .filter(u -> app.getId().equals(u.getAppId())).toList();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getCallCount()).isGreaterThanOrEqualTo(1);
        });
    }
}
```
> **实现注意**：本 IT 走真实模型（流式）会调外部 LLM——不可取。需 `@MockitoBean ModelRouterService` 让 `getDefaultChatClient()` 返回一个产出固定 `Flux<ChatResponse>`（末尾带 usage）的 stub `ChatClient`。M4 下 stub `ChatClient` 的链式调用较繁琐；若难以稳定 mock，**退化方案**：在 `ChatServiceImpl` 增一个 seam（如把「拿到内容流 + usage」抽成可被 `@MockitoBean` 覆盖的窄接口/方法），或将 StreamMeteringIT 降级为「验证 `streamChat(request, cb)` 的回调在流结束时被调用」的**切片/单测**（直接注入 mock ChatClient 到 ModelRouterService.getDefaultChatClient()）。**关键验收：流结束→回调触发→记账**必须被某种自动化测试覆盖；若 IT 层实在难稳定，用单测覆盖回调触发 + 保留一条不断言 token 的 smoke IT。实现者按 M4 实况选最稳的方式，并在 PR 说明选择理由。

- [ ] **Step 6: 运行 → GREEN**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=StreamMeteringIT -Dtest=none -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`（或对应单测）
Expected: 绿（流结束后有计量行 / 回调被触发并记账）。

- [ ] **Step 7: 提交**

```bash
git -C "D:/Code/m/qingyu-care" add backend/src/main/java/com/qingyu/care/service/ChatService.java \
  backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java \
  backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java \
  backend/src/test/java/com/qingyu/care/it/StreamMeteringIT.java
git -C "D:/Code/m/qingyu-care" commit -m "feat: 流式开放 API 对话计量（末尾 usage + doFinally 回调，保持开放专属）"
```

---

## Task 4: 全量验证 + 收尾

**Files:** 无新增

- [ ] **Step 1: 全量 `mvn verify`**

Run: `cd /d/Code/m/qingyu-care/backend && mvn verify`
Expected: surefire（60 单测）+ failsafe（全部 IT，含新增 TokenMeteringIT/StreamMeteringIT）全绿，BUILD SUCCESS。若失败逐条排查，不弱化断言。

- [ ] **Step 2: `mvn test` 仍免 Docker**

Run: `cd /d/Code/m/qingyu-care/backend && mvn test`
Expected: 60 单测绿、无容器。

- [ ] **Step 3: 真实环境抽验（可选，需运行中的后端+可用 key）**

若本机有运行中的 dev 后端：`POST /api/open/v1/chat/completions` 一次真实对话后，`SELECT token_count FROM sys_api_usage` 应 > 0。（无环境则跳过，以 IT 为准。）

- [ ] **Step 4: 更新 spec 状态**

把 `docs/superpowers/specs/2026-07-04-token-accounting-design.md` frontmatter `status: 待评审` → `status: 已实现`；若流式 token 因 M4 限制降级，在 §6 风险节补一句实际结论。
```bash
git -C "D:/Code/m/qingyu-care" add docs/superpowers/specs/2026-07-04-token-accounting-design.md
git -C "D:/Code/m/qingyu-care" commit -m "docs: Token 记账落地，spec 置为已实现"
```

---

## Self-Review 覆盖对照（spec → task）

- spec §3.1 泛型化 → Task 1。
- spec §3.2 非流式填 tokenUsage → Task 2 Step 5。
- spec §3.3 流式 usage + 计量 → Task 3 Step 3-4。
- spec §3.4 计量开放专属（不下沉共享）→ Task 2（chat 不记账）+ Task 3（回调在开放控制器）+ 内部 ChatController 不动。
- spec §5 测试 → TokenMeteringIT（Task 2）+ StreamMeteringIT/单测（Task 3）。
- spec §6 M4 不确定项 → Task 2 Step 1、Task 3 Step 1 的核实步骤 + 退化方案。

## 完成标准

- 非流式开放对话后 `sys_api_usage.token_count` = 真实 total_tokens（IT: ==42）。
- 流式开放对话完成后产生计量行（回调触发记账）；token 视 M4 支持而定（尽力而为）。
- 内部 `ChatController` 对话不计量（未新增 sys_api_usage 行）。
- `mvn test` 免 Docker 全绿；`mvn verify` 含新 IT 全绿。

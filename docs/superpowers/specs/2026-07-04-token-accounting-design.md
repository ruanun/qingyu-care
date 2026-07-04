---
title: 开放 API Token 记账 设计
author: ruan
date: 2026-07-04
status: 待评审
---

# 开放 API Token 记账 设计

## 1. 背景与范围

「技术债清扫」的第一个子项（子项 A）。当前开放 API 的用量计量存在两处**静默错误**：

1. **`token_count` 恒为 0**：`OpenChatController.completions` 计量时传的是 `ChatResponse.tokenUsage`，但该字段从未被填充——根因是 `ModelRouterService.callWithFallback(String scenario, Function<ChatClient,String>)` 只返回**回复文本 String**，把 LLM 响应里的 usage 元数据丢弃在模型调用边界。
2. **流式端点完全不计量**：`OpenChatController.stream` 直接返回 `chatService.streamChat(...)` 的 `Flux<String>`，没有任何 `apiUsageService.record` 调用（代码注释亦标注「流式端点暂不计量」+ `TODO`）。

**可行性已核实（2026-07-04 直连 dashscope compatible-mode）**：
- 非流式响应体含 `usage: {prompt_tokens, completion_tokens, total_tokens, ...}`。
- 流式加 `stream_options.include_usage=true` 后，末尾 chunk（`choices` 为空）携带 `usage`。
- 结论：两条路径的 token 用量都拿得到，只是被我们的代码丢了/没接。

**范围**：把两条路径的 token 用量正确接回并落到 `sys_api_usage`。**非目标**：不拆分 prompt/completion/reasoning 分列、不改表结构、不做实时用量看板（Phase 3 收尾）、不动计费。

**决策已定**（brainstorming 2026-07-04）：
- **模型调用边界**：把 `callWithFallback` **泛型化**透出完整响应，而非新增并行方法。
- **计量归位**：把用量记账从 `OpenChatController` **集中到 service 层**（`chat()` 与 `streamChat()`），两条路径一致、控制器变薄。
- **口径**：记 `total_tokens`（含 deepseek 的 reasoning tokens），**不改 `sys_api_usage` 表结构**（仍单列 `token_count`）。

## 2. 目标与非目标

**目标**
- 非流式 `/open/v1/chat/completions`：`ChatResponse.tokenUsage` 被真实 total_tokens 填充，`sys_api_usage.token_count` 累加真实用量。
- 流式 `/open/v1/chat/stream`：完成后对该应用记 1 次调用 + 本次 total_tokens（尽力而为）。
- 计量逻辑集中在 service 层，控制器不再直接记账。
- 新增单测 + 集成 IT（延续现有回归网）守护，防再次静默归零。

**非目标（YAGNI）**
- 不新增 prompt/completion/reasoning 分列，不改 `sys_api_usage` 表结构。
- 不做用量统计看板 / 计费 / 套餐（属 Phase 3 收尾与 Phase 4）。
- 不改 `callWithFallback` 的多模型降级语义。

## 3. 设计

### 3.1 模型调用边界透出 usage —— `ModelRouterService`

现状：`String callWithFallback(String scenario, Function<ChatClient, String> promptRunner)`，内部遍历候选模型、逐个 try、失败降级，返回 `promptRunner` 的结果（String）。

改为**泛型**：`<T> T callWithFallback(String scenario, Function<ChatClient, T> promptRunner)`。降级逻辑不变，只是返回类型由调用方的 `promptRunner` 决定。

- `chat()` 的 promptRunner 改为返回 Spring AI `org.springframework.ai.chat.model.ChatResponse`（`client.prompt().messages(...).call().chatResponse()`）；
- 摘要生成（`ChatServiceImpl` line ~232）的 promptRunner 仍返回 String，**不受影响**。

> 泛型化优于新增 `callWithFallbackDetailed`：避免重复降级逻辑、不留两套并行 API。

### 3.2 非流式：填 `ChatResponse.tokenUsage` —— `ChatServiceImpl.chat`

从 3.1 拿到的 Spring AI `ChatResponse`：
- `content` = `resp.getResult().getOutput().getText()`（替换原 `.content()`）；
- `totalTokens` = `resp.getMetadata().getUsage() != null ? resp.getMetadata().getUsage().getTotalTokens() : null`（Spring AI M4 的 `Usage.getTotalTokens()` 类型以实际 API 为准，落库前转 int）。

用 `totalTokens` 填我们自己的 `dto.ChatResponse.tokenUsage`（原本恒 null）。随后 §3.4 在 service 内记账。

### 3.3 流式：捕获 usage 并计量 —— `ChatServiceImpl.streamChat`

现状：`getDefaultChatClient().prompt().messages(...).stream().content()` → `Flux<String>`，usage 丢失、无计量。

改造：
1. **启用 include_usage**：给流式请求带上 OpenAI 的 `stream_options.include_usage=true`。实现时确认 Spring AI 1.0.0-M4 的开启方式（`OpenAiChatOptions.builder().streamUsage(true)` 之类，或全局 `spring.ai.openai.chat.options.stream-usage=true`）；若 M4 不支持该选项，退化为「只记调用次数、token 记 0」并在代码注释标注。
2. **改用 `.stream().chatResponse()`**（`Flux<org.springframework.ai.chat.model.ChatResponse>`）：对客户端仍 `map` 成 `Flux<String>` 内容（保持 SSE 输出契约不变）；管道内 `doOnNext` 记住**末尾非空 usage**（用一个 `AtomicInteger`/`AtomicReference` 累积最后一次 usage）。
3. **完成时计量**：`doFinally`（或 `doOnComplete`）中调用 `apiUsageService.record(tenantId, appId, 1, capturedTokens)`。
4. **上下文捕获（关键）**：`doFinally` 可能在 Reactor 的其它线程执行，`TenantContext`（ThreadLocal）此时取不到——与 Webhook `@Async` 同理。故在**请求线程调用 `streamChat` 的入口处**从 `TenantContext` 读出 `tenantId`/`appId`，闭包捕获后带进管道，不在 `doFinally` 里读 ThreadLocal。

### 3.4 计量位置 —— 保持「开放 API 专属」，不下沉到共享 service

> **修正（2026-07-04，实现前核实）**：`ChatServiceImpl.chat()` / `streamChat()` 被**内部 `ChatController` 与开放 `OpenChatController` 双方共享**。用量计量是**开放 API 专属**概念（内部管理端的测试对话不应计入应用配额），故计量**不能**下沉到共享的 `chat()`/`streamChat()`——否则会误计内部对话。

因此：
- **非流式**：`chat()` 只负责**填 `ChatResponse.tokenUsage`**（纯响应自带信息，内部/开放都填，无害）。计量仍留在 `OpenChatController.completions` 的现有 `apiUsageService.record(...)`——位置不变，只是现在 `tokens` 非零了。内部 `ChatController` 不记账（现状即如此）。
- **流式**：usage 只在流完成时（Reactor 管道内）才拿得到，故需在 `streamChat` 内捕获，但**计量动作由调用方决定**——给 `streamChat` 加一个**带完成回调的重载**：
  - `Flux<String> streamChat(ChatRequest request)` —— 内部用，不计量（委托给下方重载、传一个 no-op 回调，复用同一实现，避免重复管道逻辑）；
  - `Flux<String> streamChat(ChatRequest request, java.util.function.IntConsumer onTokensUsed)` —— `doFinally` 中 `onTokensUsed.accept(capturedTotalTokens)`。
  - `OpenChatController.stream` 调用带回调的重载，回调里 `apiUsageService.record(tid, aid, 1, tokens)`；`tid/aid` 在**控制器线程**从 `TenantContext` 读出后闭包捕获（§3.3(4)）。内部 `ChatController.stream` 调用无回调重载 → 不计量。

> 结果：计量始终是开放 API 专属；非流式改动最小（仅 `chat()` 填 tokenUsage）；流式通过回调把「产生 usage 的服务层」与「记账的开放层」解耦。`record` 仍 `@Async` + `@Transactional incrementUsage`（既有修复）保证落库、不阻塞主链路。

### 3.5 口径与边界

- **token 记 `total_tokens`**（deepseek 的 `completion_tokens` 含 `reasoning_tokens`，`total_tokens` 是计费/统计单位）。不拆列、不改表。
- **流式尽力而为**：`doFinally` 总记 1 次调用（一次真实模型调用已发生）；`capturedTokens` 为末尾 chunk 的 total_tokens，若流异常中断/未拿到 usage 则记 0。
- **非流式失败**：模型调用抛错时走既有异常链（不记账），与现状一致。

## 4. 影响面

| 文件 | 改动 |
|---|---|
| `service/ModelRouterService.java` | `callWithFallback` 泛型化（返回 `<T>`） |
| `service/ChatService.java`（接口） | 新增 `streamChat(ChatRequest, IntConsumer onTokensUsed)` 重载声明 |
| `service/impl/ChatServiceImpl.java` | `chat()` 取 usage 填 `tokenUsage`（**不记账**）；`streamChat` 加带回调重载：`.stream().chatResponse()` + tap 末尾 usage + `doFinally` 触发回调；无参重载委托传 no-op 回调 |
| `controller/open/OpenChatController.java` | completions 记账**保留**（tokens 现在非零）；stream 改为调带回调重载，回调里 `record`（先在控制器线程捕获 tenantId/appId） |
| `controller/ChatController.java` | 内部 stream 继续用无参 `streamChat`（不计量），**无需改动** |
| `dto/ChatResponse.java` | 无需改（`tokenUsage` 字段已存在，只是现在被填） |

## 5. 测试

- **单测（Mockito）**：mock `ModelRouterService.callWithFallback` 返回一个带 `usage.totalTokens` 的 Spring AI `ChatResponse`，断言 `ChatServiceImpl.chat` 产出的 `dto.ChatResponse.tokenUsage` == 该值；mock `apiUsageService`，`verify record(...)` 以正确 tokens 调用。
- **集成 IT（延续回归网，`@MockitoBean ModelRouterService`）**：
  - `TokenMeteringIT`（非流式）：经 `POST /open/v1/chat/completions` 驱动，mock 模型返回带 usage 的响应 → Awaitility 等到 `sys_api_usage` 该应用行 `token_count == 预期 total_tokens`。
  - 流式计量 IT：经 `POST /open/v1/chat/stream` 驱动 → 流结束后 Awaitility 等到该应用 `sys_api_usage` 出现一行（call_count≥1），token 视 mock 能否透出末尾 usage 而定；若 M4 流式 usage 透出不可控，至少断言「调用被计量」。
- 命名 `*IT`、继承 `AbstractPostgresIT`；`mvn verify` 跑通。

## 6. 风险与考量

- **Spring AI M4 流式 usage 支持不确定**：M4（里程碑版）对 `stream_options.include_usage` 的封装可能不完整。实现时先用一个探针确认 `.stream().chatResponse()` 末尾是否带 usage；若不支持，按 §3.3(1) 退化（流式只记调用次数、token=0），并在 spec/代码注释如实标注，不阻塞主目标（非流式 token + 流式调用计量）。
- **Reactor 上下文传播**：务必闭包捕获 tenantId/appId，勿在 `doFinally` 读 ThreadLocal（已在 §3.3(4) 固化）。
- **泛型化的兼容性**：`callWithFallback` 现有 2 个调用点（chat、summary）；泛型化后 summary 仍传返回 String 的 lambda，类型推断正常，无破坏。

## 7. 验收标准

- 非流式**开放** API 对话后 `sys_api_usage.token_count` 为真实 total_tokens（> 0）。
- 流式**开放** API 对话完成后，该应用在 `sys_api_usage` 有计量行（至少 call_count 增加；token 尽力而为）。
- 计量始终**开放 API 专属**：内部 `ChatController` 的对话（流式/非流式）**不**产生 `sys_api_usage` 记录。
- 单测 + IT 全绿；`mvn test` 仍免 Docker、`mvn verify` 含新 IT 全绿。

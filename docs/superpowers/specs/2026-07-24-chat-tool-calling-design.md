# 对话内工具调用 + Skill 机制（MVP）设计

- 日期：2026-07-24
- 分支：`feat/chat-tool-calling`
- 作者：ruan
- 来源：对参考项目 tgo 的借鉴分析——「第一档高价值能力缺口 #1」。第一档还有 #2 QA 知识库 / #3 真人坐席 / #4 结构化卡片，按序单分支推进（本稿只覆盖 #1）。

## 1. 背景与目标

轻语当前的对话链路（`ChatServiceImpl`）是**纯单轮 RAG**：检索知识 → 拼上下文 → 调模型 → 落库，模型无法在回答中主动调用外部能力。客服场景的高频刚需——「查订单 / 查物流 / 查库存」——所需数据都在**租户自己的业务系统**里，知识库检索无法覆盖。

本设计引入**对话内工具调用（function calling）**，让模型在回答前可自主决定调用租户配置的工具（HTTP 接口），并以 **Skill 机制**把「一段处理某类场景的 Markdown 指令 + 绑定的若干工具」打包成可复用的能力单元。

目标：在**不改动检索/落库主体结构、零回归**的前提下，为非流式 `chat()` 增加工具调用与 skill 能力。

## 2. MVP 范围契约

### 2.1 IN（本期做）
- **HTTP 工具**：租户在后台配置「工具 = 一个 HTTP 端点 + 参数 schema」，模型只填参数、不选 URL；平台代发请求。
- **Skill 机制**：Markdown 能力包，正文注入系统提示、绑定若干工具；**全量注入、无路由**；工具**只经 skill 激活**。
- 仅**非流式 `chat()`**。
- 仅**只读查询工具**（无写/动作、无人工确认流）。
- **SSRF 守卫为硬门槛**。

### 2.2 OUT（明确延后，见 §10；已记入 memory `tool-calling-skill-mvp-scope`）
流式工具调用、skill 命中触发/路由、写/动作工具 + 确认流、MCP 接入、插件运行时/打包、平台共享工具/skill、skill 与 ChatStrategy 场景绑定、Token 多轮聚合计量。

## 3. 数据模型

两个实体，均继承 `BaseTenantEntity`（`@PrePersist` 自动回填 `tenant_id`/`app_id`，查询经 `TenantDataAspect` 自动过滤 → **工具与 skill 天然按应用隔离**）。沿用 `AiModelConfig` 的字段风格。

### 3.1 `AiTool`（表 `ai_tool`）——可执行原语

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 主键 |
| `name` | String(100) | 暴露给 LLM 的工具名，合法标识符（如 `query_order`） |
| `description` | String(500) | **给 LLM 判断何时调用**的用途描述（命中率关键） |
| `inputSchema` | text | JSON Schema 字符串，描述参数；LLM 据此填参 |
| `httpMethod` | String(10) | `GET` / `POST` |
| `urlTemplate` | String(1000) | 端点模板，含占位符（如 `https://api.example.com/orders/{orderId}`） |
| `authHeader` | String(1000) | 静态鉴权头（如 `Authorization: Bearer xxx`），可空 |
| `timeoutMs` | Integer | 出站超时，默认 5000 |
| `maxResponseChars` | Integer | 回灌 LLM 的响应体截断长度，默认 4000 |
| `enabled` | Boolean | 开关，默认 true |
| `createdAt`/`updatedAt` | LocalDateTime | 时间戳 |

不设 `type` 字段（本期仅 HTTP，将来加类型是加列迁移）。

### 3.2 `AiSkill`（表 `ai_skill`）——能力包

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 主键 |
| `name` | String(100) | 显示名 |
| `description` | String(500) | 用途描述（本期文档用途，为未来命中触发预留） |
| `content` | text | **Markdown 正文 = 注入系统提示的 SOP/指令** |
| `toolIds` | 元素集合 | 绑定的 `AiTool` id 列表（`@ElementCollection`，避开 join 表与租户过滤器纠缠） |
| `enabled` | Boolean | 开关，默认 true |
| `createdAt`/`updatedAt` | LocalDateTime | 时间戳 |

## 4. 运行时架构

### 4.1 `HttpToolCallback implements org.springframework.ai.tool.ToolCallback`
- `getToolDefinition()`：返回 `AiTool` 的 name / description / inputSchema。
- `call(String argsJson)`：解析参数 → 填充 `urlTemplate` 占位符 → 经 **SSRF 守卫**校验目标 → 用 `RestClient` 带 `authHeader` 发请求（`timeoutMs`）→ 按 `maxResponseChars` 截断响应体 → 返回字符串。
- 每个启用的 `AiTool` 实例化一个。

### 4.2 `ChatToolResolver`
在**请求线程**内调用（依赖 `TenantContext`，与 `searchKnowledge`、模型场景解析同位）：
1. 取当前应用 enabled 的 `AiSkill` → 收集各 `content` 待注入；
2. 取这些 skill 绑定 `toolIds` 的并集 → 加载 enabled 的 `AiTool` → 组 `List<ToolCallback>`（每个 `HttpToolCallback`）；
3. 返回 `ToolBundle { List<String> skillMarkdowns, List<ToolCallback> toolCallbacks }`。

**工具暴露口径**：工具**只经 skill 激活**——未被任何 enabled skill 绑定的 `AiTool` 处于休眠，不暴露给模型。单一激活路径。

### 4.3 注入点（仅 `chat()`）
- **系统提示**：`buildMessages` **新增 `List<String> skillMarkdowns` 参数**，在现有模板 `.replace("{context}", …)` 之后**追加各 skill 的 markdown**（用分隔符包裹，标明来自 skill）。`chat()` 传入 `bundle.skillMarkdowns()`；`streamChat()` **传空列表**——这样共用的 `buildMessages` 只在 `chat()` 路径被注入，流式行为不变、不产生歧义。
- **模型调用**：`chat()` 内那行改为——
  ```java
  ChatToolResolver.ToolBundle bundle = chatToolResolver.resolve();
  // systemPrompt 追加 bundle.skillMarkdowns()
  modelRouterService.callWithFallback(scenario, client ->
      client.prompt().messages(finalMessages)
            .toolCallbacks(bundle.toolCallbacks())
            .call().chatResponse());
  ```
- **零回归**：无 skill/工具时 `skillMarkdowns` 与 `toolCallbacks` 皆空，系统提示与调用与现状**逐字节一致**。
- **流式不变**：`streamChat()` 本期**不注入 skill、不带工具**——避免「注入了用工具的指令却没工具」的不一致。skill/工具的激活**只发生在 `chat()`**。

Spring AI 默认开启内部工具执行循环（`ToolCallingManager`），模型返回 tool_call 时框架自动执行工具并回灌，直至产出最终回答。

## 5. 安全（硬门槛，不可砍）

1. **SSRF 守卫**：平台代租户出站，恶意租户可能把 `urlTemplate` 指向 `http://169.254.169.254`（云元数据）或内网。**发请求前解析并校验目标 IP，拒绝 loopback / link-local / 私有网段**（可配白名单）。实现为纯函数，单测覆盖。
2. **超时 + 响应体大小上限**：`timeoutMs` + `maxResponseChars`，防慢请求 / 超大响应灌爆 LLM 上下文。
3. **审计**：工具调用走 `@AuditLog`，记录工具名 + 脱敏参数（复用现有 `AuditLogAspect` 正则脱敏）。
4. **密钥存储**：`authHeader` 明文存储，与 `AiModelConfig.apiKey` 同一技术债；本期标注、不扩大治理，加密留后续统一处理。

## 6. Token 计量取舍

带工具调用有多轮模型往返，`chatResponse().getMetadata().getUsage()` 只反映**最后一轮**，会漏计中间轮。MVP 决定：**接受少计 + 打日志标注已知局限**，多轮聚合计量列后续（§10）。理由：开放 API 非流式计费本走这条 usage，少计偏保守（少收租户钱），可接受。

## 7. 管理面

- **后端**：`AiToolController` + `AiSkillController`，CRUD；按 id 的 get/delete 接 `TenantOwnershipGuard` 归属校验（复刻既有防越权范式）。
- **前端**：`ToolConfigPage.tsx` + `SkillConfigPage.tsx`（仿 `ModelConfigPage`，包 `<RequireApp>`）；skill 页含 markdown 编辑 + 勾选绑定工具；侧边导航按角色可见。

## 8. 测试策略

- **单元测试（优先，纯 Mockito 免容器）**：
  - `HttpToolCallback.call()`：填参 / 发请求 / 截断（mock `RestClient`）。
  - SSRF 守卫：拒 loopback/link-local/内网、放行公网（纯函数）。
  - `ChatToolResolver`：从 skill+tool 配置组装 skillMarkdowns 与 callbacks；工具仅经 skill 激活。
  - 系统提示拼接：含 skill 正文；无 skill 时与现状一致。
- **集成测试（Failsafe，`*IT`）**：
  - 用**桩 `ChatModel`**（固定返回一次 tool_call，再返回最终答案）验证 `chat()` 全链路真的执行工具并回灌，且 skill markdown 落进系统提示。
  - 说明：真模型是否调工具不可控，端到端不能靠真 LLM 测。

## 9. 分期（都在 `feat/chat-tool-calling` 一条分支）

- **Phase A · 后端核心**：`AiTool`/`AiSkill` 实体 + repository；`HttpToolCallback`；SSRF 守卫；`ChatToolResolver`；注入 `chat()`（系统提示 + toolCallbacks）；单元测试。
- **Phase B · 管理面**：`AiToolController` + `AiSkillController`；`ToolConfigPage` + `SkillConfigPage`；导航接入。
- **Phase C · 收尾**：`@AuditLog` 审计；计量少计日志；桩模型 IT。

## 10. 明确延后（后续计划，按优先级）

1. **流式工具调用**（`streamChat()` 带工具 + 多轮 token 聚合计量）——主产品面（widget / 开放 SSE），紧随 MVP。
2. **Skill 命中触发/路由**（description 向量或 LLM 选 skill）——skill 数量多、全量注入撑爆上下文时上。
3. **写/动作类工具 + 敏感工具人工确认流**（挂起-恢复）。
4. **MCP 接入**（Spring AI 1.1.8 MCP 客户端把远端工具转 `ToolCallback`）。
5. **插件机制**（打包 skill+工具+配置为可安装/停用单元；再上层才是 tgo 式独立插件运行时/市场）。
6. **平台共享工具/skill**（`OwnerScope.PLATFORM`，仿 `AiModelConfig` 共享模型回退语义）。
7. **Skill 与 ChatStrategy 场景绑定**（按场景暴露不同 skill/工具）。
8. **Token 多轮聚合计量**（替换本期末轮少计）。

## 11. 待核实

- Spring AI **1.1.8** 里 `ChatClient` 的 `.toolCallbacks(...)` 与自定义 `ToolCallback` / `ToolDefinition` 的确切 API（进入 writing-plans / 实现前用 context7 对 1.1.8 核实，勿照搬旧版）。
- Spring AI 内部工具执行循环（`ToolCallingManager`）默认开启的确认，及其对 `ChatResponse` usage 聚合的行为。
- MCP（若日后启用）连接缓存的生命周期。

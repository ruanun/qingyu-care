# MCP 接入设计（远端 MCP server 工具转 ToolCallback）

> 承接工具调用 MVP（`2026-07-24-chat-tool-calling-design.md`）§10 延后清单第 4 项。
> 那份清单写于 Spring AI **1.1.8** 时期，现为 **2.0**，接入方式已按 2.0 重新核实，见 §2.1。
> 同时闭合该文 §11「待核实」最后一条「MCP 连接缓存的生命周期」——本设计的答案是**不缓存**，见 §2.4。
>
> 作者：ruan　日期：2026-08-21

## 1. 目标与非目标

**目标**：租户在管理后台登记一个远端 MCP server（自建的，或第三方公开的），一键同步它的工具清单到本地，逐个勾选进 skill，对话时由 LLM 调用。相比现状「逐个手工配 HTTP 工具（URL/method/inputSchema 全靠人填）」，一次接入即可拿到一整套工具，且 schema 由远端提供、不会人工抄错。

**非目标**（本期明确不做）：

- **平台共享 MCP** —— 表里保留 `scope` 字段，将来要加不改结构。不做的收益是不必引入 `SharedCredentialMasker`（它有「不 `em.detach` 就把凭据真删掉」的坑）与 native 共享查询的双重钉死，那几处历史上出过跨租户越权（见 CLAUDE.md「平台共享工具」一节）。
- **可写 / 动作类工具与人工确认流** —— 见 §2.3。这是延后清单第 3 项，独立立项。
- **定时同步** —— 见 §2.5。
- **stdio 传输** —— 见 §2.2。
- **`outputSchema`** —— MCP 较新 spec 才有的可选项，tgo 有而我们没有。当前 `HttpToolCallback` 也只用 `inputSchema`，加它不产生行为差异。
- **工具商店**（分类/搜索/评分/安装量）—— tgo 有完整实现，那是延后清单第 5 项「插件运行时 + 工具商店」的范畴。
- **工具调用的审计回放** —— 现有 `HttpToolCallback` 只 `log.info` 工具名与参数键（不记值），MCP 沿用同样口径，不新建审计体系。

## 2. 关键决策

### 2.1 接入方式：不用官方 starter，自己建 client

Spring AI 2.0 提供了 `McpToolCallbackAutoConfiguration`、`SyncMcpToolCallbackProvider`、
`McpToolUtils.getToolCallbacksFromSyncClients(...)`，以及 `spring.ai.mcp.client.streamable-http.connections.*` 这套配置。

**两条都用不了**：

1. **starter 的连接配置是静态 yaml**，进程启动时定死。本项目是多租户 SaaS，MCP server 由租户在后台配、按 app 隔离、运行时解析——形态根本对不上。
2. **`SyncMcpToolCallbackProvider` 的模型是「先连上 → 拉清单 → 转 callback」**，而本设计是「从库里的清单造 callback、调用时才连」（§2.4）。它需要一个已连接的 `McpSyncClient` 才能列工具，与我们的生命周期相反。

所以自己实现 `McpToolCallback implements ToolCallback`，与现有 `HttpToolCallback` 逐点对称。底层连接用官方 MCP Java SDK 的 `io.modelcontextprotocol.client.McpSyncClient`（Spring AI 2.0 传递引入），传输用 `WebClientStreamableHttpTransport`。

⚠️ **实现时必须按 2.0 的 javadoc 核实 builder 的确切签名**，不要照抄本文——本仓库已有多次「照着过时描述动手」翻车的记录（如 `AI_BASE_URL` 的 `/v1` 契约在 1.x→2.0 之间整个翻转）。

### 2.2 只支持远端 HTTP 类传输，不做 stdio

支持 `STREAMABLE_HTTP`（主）与 `SSE`（兼容旧 server）。

**stdio 不做**：它要求在平台服务器上 `fork` 子进程执行租户填写的命令行，等于给租户一个远程命令执行入口，比现有 `HttpToolCallback` 的 SSRF 面严重一个量级。多租户 SaaS 下没有安全的做法。

### 2.3 本期只挂只读工具，且判据是租户标注而非远端自报

MCP 工具本质上什么都能做（改订单、发邮件、删数据），而现有工具体系是只读硬门槛。

协议里表达「是否只读」的只有 `readOnlyHint` 这个 **annotation，服务端自报、可选不填**——第三方 server 不可信，自建 server 也常常不填。**因此不能用它做安全判据**：同步时记录它仅作为界面上的默认建议值，真正的判据是 `McpTool.readOnly` 这个**租户在界面上自己标注**的字段，默认 `false`（未知一律当可写，默认不挂）。

只读门槛落在 `resolve()` 的查询条件上（§5.3），不是在界面上拦——界面拦得住的东西，接口层拦不住。

⚠️ **摩擦要在 UI 层解决，不能在安全层让步**：一个 20 工具的 server 逐个点只读会很烦，但答案不是「`readOnlyHint=true` 就自动标只读」（那等于信任远端自报），而是界面上提供**批量标注**——「按远端建议全选」一次点完，租户仍是那个做决定的人。见 §7.2。

### 2.4 连接不缓存：解析走数据库，调用才连远端

工具调用 MVP 的 §11 把「MCP 连接缓存的生命周期」列为待核实，一直没解。既然工具清单已经落库（§2.6），`ChatToolService.resolve()` 就**根本不需要连远端**——直接从 `mcp_tool` 行造 `ToolCallback`，保持它现在这样的纯数据库操作。连接只在 LLM **真的调用**某个工具的那一刻建立，用完即弃。

**代价**：每次工具调用多一次 `initialize` 握手往返。
**换掉的**：一整类连接池生命周期 bug——按 `(tenantId, appId, serverId)` 缓存的失效策略、租户改配置后的陈旧连接、并发复用、以及最危险的跨租户串号。在多租户下这个交换很划算。

这也与现有 `HttpToolCallback` 的无状态模型一致（每次调用新建一个 HttpClient、用后即关，见 `RestClientHttpToolExecutor`）。

### 2.5 只手动同步

界面上一个「同步工具」按钮，接入时拉一次。**不做定时同步**：工具清单的变化频率远低于网页内容，不值得为它引入一套调度器（还要处理调度线程没有 `TenantContext`、必须走 native SQL 绕过 `@Filter` 那个坑，见 CLAUDE.md「网站知识库」一节）。

漂移的后果是 fail-loud 的：远端报 unknown tool 或参数错误，LLM 拿到错误文本，不是静默失效。

### 2.6 数据模型：独立表，不复用 `ai_tool`

**参考对照**：tgo（`references/tgo`）用的是相反方案——单张统一 tool 表 + `source_type` 枚举（`MCP_SERVER` / `CUSTOM`），MCP 工具带 `mcp_server_id`、自定义工具带 `webhook_url`，互斥字段全部 `Optional`。

**本项目不跟这个方案**，三条理由都是我们特有的：

1. `AiTool.urlTemplate` 与 `httpMethod` 是 `nullable = false`。复用就必须把它们放开成可空——现在数据库保证「HTTP 工具一定有 URL」，改完就不保证了，必填校验退化到应用层。**这类约束放松是不可逆的**，而 tgo 的 `webhook_url` 本来就是 `Optional`，它没付这个代价。
2. 租户在工具编辑页能改一个 MCP 工具的 `httpMethod` / `timeoutMs`，语义错乱。
3. 给 `AiTool` 加 MCP 语义字段会直接触发 `UpdateRequestFieldCoverageTest`（实体字段必须进 DTO 或进白名单写理由），逼着 `AiToolUpdateRequest` 长出 MCP 字段。四道哨兵守的正是这种漂移，绕过它们比顺着它们更贵。

**但 tgo 有一条直接抄**：`ToolStatus.DEPRECATED`，见 §3.2 与 §4.2。

## 3. 数据模型

### 3.1 `McpServer`（表 `mcp_server`）

继承 `BaseTenantEntity`（`@PrePersist` 自动回填 `tenant_id`/`app_id`，查询经 `TenantDataAspect` 自动过滤 → 天然按应用隔离）。字段风格沿用 `AiTool`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | varchar(100) NOT NULL | 显示名，**可中文**（如「订单系统」）。仅界面展示，不参与任何标识 |
| `code` | varchar(50) NOT NULL | 标识符，`[a-z][a-z0-9_]*`，租户内唯一。**参与 LLM 可见的工具名**，见 §5.2。新建时可由 `name` 自动生成草稿供用户改，但落库前必须校验格式 |
| `description` | varchar(500) | 用途描述，仅界面展示 |
| `transport` | varchar(20) NOT NULL | `STREAMABLE_HTTP` \| `SSE`，默认前者 |
| `url` | varchar(1000) NOT NULL | 远端端点 |
| `authHeader` | varchar(1000) | 静态鉴权头，格式同 `AiTool.authHeader`（`"Header-Name: value"`）。明文存储，与 `AiTool.authHeader` / `AiModelConfig.apiKey` 同属既有技术债，本期不单独治理 |
| `timeoutMs` | int NOT NULL | 默认 **10000**（比 HTTP 工具的 5000 宽，因为多一次 `initialize` 握手） |
| `maxResponseChars` | int NOT NULL | 默认 4000，同 `AiTool` |
| `enabled` | boolean NOT NULL | 默认 true |
| `scope` | varchar(20) NOT NULL | `@ColumnDefault("'TENANT'")`，本期恒为 `TENANT`，为将来的平台共享预留 |
| `lastSyncedAt` | timestamp | 最后一次成功同步 |
| `lastSyncError` | varchar(1000) | 最后一次同步的失败原因，成功时置 null |

⚠️ `scope` 的 `@ColumnDefault` **是必需的**，不是美化。`mcp_server` 是新表本次不受影响，但沿用 `AiTool.scope` 的写法保持一致——那条注释记录的是一个真实事故：非空表加不带 default 的 NOT NULL 列，单条 DDL 被 PostgreSQL 拒绝，而 `SchemaUpdate` 对单条 DDL 失败只打 WARN 不阻断启动，结果列压根没建出来、应用照常起、直到运行时查询才炸。

### 3.2 `McpTool`（表 `mcp_tool`）

同样继承 `BaseTenantEntity`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `mcpServerId` | bigint NOT NULL | **存 `Long`，不用 `@ManyToOne`**，见下方警告 |
| `name` | varchar(200) NOT NULL | 远端工具名，同步时写入，不可编辑 |
| `description` | varchar(1000) | 来自远端，每次同步覆盖 |
| `inputSchema` | text | 来自远端，每次同步覆盖 |
| `status` | varchar(20) NOT NULL | `ACTIVE` \| `DEPRECATED`，默认 `ACTIVE` |
| `readOnly` | boolean NOT NULL | **租户标注**，默认 `false`。同步**绝不覆盖**它，见 §6.1 |
| `readOnlyHint` | boolean | 远端自报的 annotation，**仅作界面默认建议值**，不参与任何安全判定 |

唯一约束 `(mcp_server_id, name)` —— upsert 的依据。

⚠️ **`mcpServerId` 存 `Long` 而不是 `@ManyToOne` 关联**：`CrawlSite.knowledgeBase`（LAZY）踩过的坑是，detached 代理上除 `getId()` 外任何取值都会在非请求线程抛 `LazyInitializationException`，且把代理塞进 `Set`/调 `equals` 也会触发。本期同步跑在请求线程里，暂时不会炸，但省下的那一次查询不值得给将来留一颗雷。

**不给 `McpTool` 加 `enabled`**：`AiTool` 有 `enabled` 是因为它手工创建、需要临时停用；MCP 工具是同步来的，停用方式就是从 skill 解绑。少一个状态维度就少一处「`enabled` 与 `status` 打架」的不一致。

### 3.3 `AiSkill` 增加 `mcpToolIds`

```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "ai_skill_mcp_tool", joinColumns = @JoinColumn(name = "skill_id"))
@Column(name = "mcp_tool_id")
@Builder.Default
private Set<Long> mcpToolIds = new LinkedHashSet<>();
```

与现有 `toolIds` 并列、同样的元素集合范式（避开 join 表与租户过滤器纠缠）。

⚠️ 加了这个字段，`AiSkillUpdateRequest` 必须跟着加 `mcpToolIds` 并在更新路径上写 `setMcpToolIds`，否则 `UpdateRequestFieldCoverageTest` 与 `UpdateRequestPropagationTest` 会转红——这正是它们该做的事。

## 4. 组件划分

### 4.1 `service/mcp/` 包

| 组件 | 职责 |
|---|---|
| `McpConnectionFactory` | 按 `McpServer` 建一个 `McpSyncClient`（传输选择、鉴权头、超时、**SSRF 守卫**）。唯一碰 MCP 协议的地方 |
| `McpToolExecutor`（接口） | `listTools(McpServer)` / `callTool(McpServer, String toolName, String argsJson)` |
| `SpringAiMcpToolExecutor` | 上面的实现，用 `McpConnectionFactory`。接口/实现分离是为了让 `McpToolCallback` 的单测能 mock 掉协议层（范式同 `HttpToolExecutor` / `RestClientHttpToolExecutor`） |
| `McpToolCallback` | `implements ToolCallback`，与 `HttpToolCallback` 对称 |
| `McpServerService` | `McpServer` / `McpTool` 的 CRUD + 同步编排 |

把协议细节关在 `McpConnectionFactory` 后面，`ChatToolService` 只看得到 `ToolCallback`。这一点与 tgo 的思路一致——它把整个 MCP 协议关在一个独立微服务边界后，`tgo-ai` 全程只调 REST、不碰协议。

### 4.2 同步流程 `McpServerService.sync(serverId)`

请求线程内同步执行（一次往返、秒级，不进 `AsyncConfig.LONG_TASK_EXECUTOR`）：

1. 取 server（经 `TenantOwnershipGuard` 写级守卫，越权按「不存在」处理）
2. `executor.listTools(server)`：连接 → `initialize` → `tools/list` → 关闭
3. 按 `(serverId, name)` upsert：
   - 远端有、库里有 → 更新 `description` / `inputSchema` / `readOnlyHint`，`status = ACTIVE`
   - 远端有、库里无 → 新建，`readOnly = false`
   - **库里有、远端无 → `status = DEPRECATED`，不删行**
4. 成功则 `lastSyncedAt = now()`、`lastSyncError = null`；失败则只写 `lastSyncError`，**不动任何工具行**

**第 3 步第三分支为什么不删行**（抄自 tgo 的 `ToolStatus.DEPRECATED`）：工具可能已经被 skill 的 `mcpToolIds` 绑定，删行会让绑定悬空（`resolve()` 查不到、界面上也看不到，运营完全无从得知）。标记则能在界面上显示「远端已下线」，解不解绑交给运营。

**第 4 步失败分支为什么不动工具行**：一次网络抖动不该让整套工具集体变 `DEPRECATED` 进而从对话里静默消失。语义同 `WebPageSyncService.syncDocument` 的「抓取失败保留旧内容与旧向量」。

## 5. 运行时执行

### 5.1 `McpToolCallback`

- `getToolDefinition()`：用落库的 `name`（经 §5.2 加前缀）/ `description` / `inputSchema`，沿用 `HttpToolCallback` 的空 schema 兜底 `{"type":"object","properties":{}}`
- `getToolMetadata()`：`ToolMetadata.builder().build()`，同 `HttpToolCallback`
- `call(String toolInput)`：委托 `executor.callTool(...)`，异常 **fail-soft** 返回 `"工具调用失败: " + e.getMessage()` 而非抛出（同 `HttpToolCallback`——抛出会中断整轮对话），响应按 `server.maxResponseChars` 截断
- 审计口径同 `HttpToolCallback`：`log.info` 记工具名与**参数键**，不记值

### 5.2 LLM 可见的工具名加命名空间

**暴露名 = `{server.code}__{tool.name}`**（双下划线分隔）。

理由：同一个 skill 可能绑两个 server 的同名工具（`search`、`query`、`get_user` 撞名概率很高），不加命名空间会让 LLM 收到两个同名工具，行为未定义。

⚠️ **用 `code` 而不是 `name`**：工具名必须是合法标识符，但不该因此逼租户把服务器叫 `order_system` 而不是「订单系统」——那是把数据模型的便利转嫁给用户。`name` 保持可中文的显示名，`code` 承担标识职责（范式同 `Tenant.code`）。

调用时用 `tool.name`（原名）发给远端，前缀只存在于暴露给 LLM 的那一层。

### 5.3 `ChatToolService.resolve()` 的合并

在现有逻辑上追加：收集各 skill 的 `mcpToolIds` 并集 → 一条与 `resolveTools` 对称的 **filter-bound** 查询 → 造 `McpToolCallback` 追加进同一个 `callbacks` 列表。

```java
// 只读门槛落在这条查询上，不是在界面上
List<McpTool> tools = mcpToolRepository
        .findByIdInAndStatusAndReadOnlyTrue(mcpToolIds, McpToolStatus.ACTIVE);
```

⚠️ **必须是 filter-bound 的普通 JPA 查询**（不是 native）——`McpTool` 继承 `BaseTenantEntity`，`@Filter` 会自动挡掉别的租户的 id。本期没有平台共享，所以**不需要**也**不应该**写 native 查询绕过过滤器（那正是共享工具那条路径反复出安全问题的地方）。

⚠️ 追加后做一次**全局工具名去重**：与已有 HTTP 工具重名时跳过后来者并打 WARN。同名工具进同一次 `toolCallbacks(...)` 的行为是未定义的。

## 6. 最容易写错的地方

### 6.1 同步覆盖 `readOnly` = 静默放开可写工具

`readOnly` 是租户的安全标注，而 `description` / `inputSchema` 每次同步都要覆盖。一旦在 upsert 里顺手把整行 setter 刷一遍（或者用 `save(newEntity)` 覆盖），租户标过的只读位就被远端的下一次同步冲掉。

后果是**安全降级且完全静默**：一个可写工具会重新出现在 `resolve()` 的结果里被 LLM 调用。必须有专门的单测钉住「同步后 `readOnly` 保持租户标注值」。

### 6.2 用错传输实现，SSRF 守卫静默失去 rebinding 防护

§9 已实测确认守卫能套上，但**只在 webflux 那条线上成立**。若图省事换成 httpclient 版传输（JDK `HttpClient`），它不支持自定义 DNS 解析——代码照常编译、连接照常建立、功能照常可用，**只是钉 IP 那层没了**，退回到「校验时是公网、连接时翻内网」可绕过的状态。

同理，漏掉 `followRedirect(false)` 会让 302 把请求带去未经校验的主机。

这两条都是**静默失效**：没有报错、没有日志、功能表现完全正常。守护方式是在 `McpConnectionFactory` 上写明这两处的理由（同 `RestClientHttpToolExecutor` 的类注释），并在实施时保留一条断言「构造出的连接器带自定义 resolver」的单测。

### 6.3 只读门槛写在界面而非解析处

界面上把可写工具置灰很容易，但接口层照样能提交任意 `mcpToolIds`。门槛必须落在 §5.3 那条查询的 `readOnly = true` 上。

### 6.4 `DEPRECATED` 工具仍被 `resolve()` 取到

§5.3 的查询同时要卡 `status = ACTIVE`。漏掉的话，远端已下线的工具仍会被挂给 LLM，调用时才报 unknown tool——白烧一轮 token 且让模型拿着错误响应继续编答案。

### 6.5 同步失败时误伤存量工具

见 §4.2 第 4 步。失败分支只写 `lastSyncError`，一行工具都不能动。

### 6.6 删 server 留下悬空的 skill 绑定

删除 `McpServer` 会连带删掉它的 `mcp_tool` 行，而这些 id 可能还留在某些 `AiSkill.mcpToolIds` 里。

**后果是静默降级**：§5.3 那条 filter-bound 查询查不到就自然跳过，不报错也不打日志——skill 在界面上看起来仍绑着 N 个工具，实际挂给 LLM 的少了几个，运营完全无从得知。这与「删站点勾选删文档必须逐篇走 `deleteDocument`」是同一类问题（那里留下的是仍参与检索的孤儿向量，这里留下的是永远解析不出来的孤儿 id），只是方向相反。

**处理**：删除 server 时在同一事务内清理所有 skill 的 `mcpToolIds` 中指向它的 id；删除前先统计「该 server 的工具正被 M 个 skill 使用」并在前端二次确认弹窗里显示。

⚠️ 遍历 skill 做清理时注意 `AiSkill` 是 filter-bound 的，删除操作发生在请求线程、`TenantContext` 齐备，用普通 JPA 查询即可——**不要**为此写 native SQL，那会连别的租户的 skill 一起扫到。

## 7. 端点与前端

### 7.1 端点（`McpServerController`，`/v1/mcp`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/servers` | 当前应用的 server 列表 |
| POST | `/servers` | 新建（请求体直接收实体，同 `AiToolController` 的 create 范式） |
| PUT | `/servers/{id}` | 更新，**必须用 `McpServerUpdateRequest`**，字段不写初始化器 |
| DELETE | `/servers/{id}` | 删除（连带删除其 `mcp_tool` 行 **并清理 skill 绑定**，见 §6.6） |
| POST | `/servers/{id}/sync` | 同步工具清单 |
| GET | `/servers/{id}/tools` | 该 server 的工具清单 |
| PUT | `/tools/{id}/read-only` | 租户标注只读位 |
| PUT | `/tools/read-only/batch` | 批量标注（§2.3 的摩擦缓解，请求体为 `{toolIds: [], readOnly: bool}`）。⚠️ 每个 id 都要过租户守卫，不能因为是批量就只校验第一个 |

⚠️ `PUT /servers/{id}` 会被 `PutEndpointBodyRegistrationTest` 要求登记；`McpServerUpdateRequest` 会被另外三道哨兵各查一遍。凭据类字段（`authHeader`）的判空必须是 `!= null && !isBlank()`——前端不回显凭据、编辑弹窗把该字段初始化为空串却照样提交，只判 `!= null` 挡不住。

### 7.2 前端

新页面「MCP 服务」（应用级配置页，外包 `<RequireApp>`）：服务器列表 + 新建/编辑弹窗（显示名 / `code` / URL / 传输 / 凭据 / 超时）+「同步工具」按钮（显示 `lastSyncedAt` 与 `lastSyncError`）+ 展开查看工具清单（`DEPRECATED` 徽标、只读标注开关）。

**工具清单必须支持批量标注只读**（§2.3）：至少一个「按远端建议全选」——把所有 `readOnlyHint = true` 的工具一次标为只读。没有它，接一个 20 工具的 server 要点 20 次，用户会转而要求「自动信任远端」，安全判据就是这样一步步被磨掉的。

Skill 编辑页的工具勾选区分两组：「HTTP 工具」与「MCP 工具」（后者按 server 分组显示）。

⚠️ 所有写操作显式判 `if (res.code !== 200) throw new Error(res.message)`。业务异常是「HTTP 200 + `body.code`」约定，axios 不会 reject，不判就会把后端错误静默吞掉、用户点按钮毫无反应。

⚠️ 侧边导航按 `user.role` 过滤，新页面要加进 `MainLayout` 的菜单配置。

## 8. 测试策略

**单测（Surefire，纯 Mockito）**：

- `McpServerService.sync` 三分支：新增 / 更新 / 远端消失标 `DEPRECATED`
- **同步不覆盖 `readOnly`**（§6.1，安全关键）
- 同步失败时不动任何工具行（§6.5）
- 删除 server 时清理 skill 的 `mcpToolIds`，不留悬空 id（§6.6）
- `McpToolCallback`：definition 构造、空 schema 兜底、fail-soft 不抛异常、响应截断
- 工具名加前缀与调用时用原名
- `ChatToolService.resolve()`：合并两类工具、只取 `ACTIVE` + `readOnly`、重名跳过

**集成测试（Failsafe，`it/`，继承 `AbstractPostgresIT`）**：

- 跨租户拿不到别人的 `mcp_tool`（走 MockMvc 真实请求，`@Filter` 只在真实请求流经 `TenantInterceptor` 时生效）
- `AiSkill.mcpToolIds` 的持久化与回读

**前端**：按「改到哪补到哪」的常态约定，给 MCP 服务页补写操作回归测试（模板同 `ToolConfigPage.test.tsx`：桩 api service + `useToast`，断言业务错误弹 destructive toast、成功才刷新列表）。

**手工验证（自动化覆盖不到，须写进 CLAUDE.md）**：

1. **真连一个 MCP server** 走完「登记 → 同步 → 标只读 → 绑进 skill → 对话触发」，确认出站请求带着配置的凭据。理由同平台共享工具那条：凭据若在某个环节被清掉，症状是第三方返 401、模型拿着错误响应继续编答案，**全程无异常无日志**。
2. **`DEPRECATED` 路径**：远端下线一个工具后再同步，确认是标记而非删行，且界面上有提示。
3. **SSRF**：把 server URL 指向 `169.254.169.254` 或内网地址，确认被拒；再用一个 TTL=0、解析结果会翻转的域名验证 rebinding 也被挡（§9 已确认防护强度与 `RestClientHttpToolExecutor` 对等，这条验的是实现有没有真的照做）。

## 9. SSRF 守卫：技术验证结论（2026-08-21 已实测）

现有的 SSRF 硬门槛不是简单的地址校验，而是 `SsrfGuard.resolveAndValidate(host)` + **自定义 Apache `DnsResolver` 把校验期解析出的 IP 钉进连接期** + `disableRedirectHandling()`，专门防 DNS rebinding / TOCTOU（见 `RestClientHttpToolExecutor` 的类注释）。MCP server 的 URL 由租户填写，与工具 URL 同等危险，**不能降格处理**。

**结论：可以套上同等强度的守卫，不需要降级方案。** 实测四条：

1. **传输层的 HTTP 客户端可以由我们完全接管**。`javap` 确认
   `WebClientStreamableHttpTransport.builder(WebClient$Builder)` 与
   `.webClientBuilder(WebClient$Builder)` 都接受外部传入的 builder——底层连接器是我们的。
2. **Reactor Netty 支持钉住解析结果**。`reactor-netty-core:1.3.6` 的
   `reactor.netty.transport.ClientTransport` 上有 `public T resolver(io.netty.resolver.AddressResolverGroup<?>)`，
   与 Apache HttpClient 的 `DnsResolver` 是同一类能力。`HttpClient.followRedirect(boolean)` 提供禁重定向。
3. **引入 webflux 不会把本应用切成 reactive**。这是最需要实测的一条（classpath 推导型行为，纸面推理最容易翻车）。加依赖后真启动，日志为
   `Starting Servlet engine: [Apache Tomcat/11.0.22]`，真实请求 `/api/widget.js` 返回 200。
   因为 `spring-boot-starter-web` 带着 `DispatcherServlet`，`WebApplicationType` 仍判定 SERVLET。
4. **不引 starter 就没有自动配置介入**。启动日志中无任何 MCP 自动配置输出。

**由此确定的实施要点**：

- 依赖用 **`org.springframework.ai:spring-ai-mcp` + `org.springframework.ai:mcp-spring-webflux`**（版本由现有 `spring-ai-bom` 托管，勿写 version）。
- ⚠️ **不要用 `spring-ai-starter-mcp-client*`**：starter 会带 `McpClientAutoConfiguration`，它按静态 yaml 建连接，与 §2.4「运行时按租户配置建、用完即弃」冲突。
- ⚠️ **不能用 httpclient 版传输**（`spring-ai-autoconfigure-mcp-client-httpclient` 那条线）：它基于 JDK `HttpClient`，**不支持自定义 DNS 解析**，钉不住 IP。这条排除是硬性的，不是偏好。
- `McpConnectionFactory` 的形状：`SsrfGuard.resolveAndValidate(host)` 拿到已校验地址 → 构造只对该 host 返回这批地址、其余 fail-closed 的 `AddressResolverGroup` → `HttpClient.create().resolver(...).followRedirect(false)` → `ReactorClientHttpConnector` → `WebClient.Builder` → `WebClientStreamableHttpTransport`。逐段对应 `RestClientHttpToolExecutor` 的既有做法。

> spike 期间临时加进 `pom.xml` 的两个依赖已回滚，实施时重新加。

## 10. 范围外（后续可能）

- 平台共享 MCP（`scope` 已预留）
- 可写工具 + 人工确认流（挂起-恢复），延后清单第 3 项
- 定时同步（`lastSyncedAt` 已预留，加一个 `@Scheduled` 即可，注意调度线程无 `TenantContext`）
- `outputSchema`
- MCP resources / prompts（本期只接 tools）
- 工具商店（延后清单第 5 项）
- 工具调用的审计回放

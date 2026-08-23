# OpenAPI Schema 自动生成工具表单设计

> `SaaS平台规划.md` Phase 5.2 中期最后一项。同批附带修掉一个既有缺陷：
> `HttpToolCallback.fillTemplate` 对 LLM 未填的占位符原样保留，可选 query 参数在今天的
> 模型里是坏的（见 §5）。
>
> 作者：ruan　日期：2026-08-23

## 1. 目标与非目标

**目标**：租户管理员给一份 OpenAPI 3.x schema（粘贴文本或填 URL），系统解析出其中可用的
端点，勾选后批量生成 `AiTool` 行。相比现状「一个端点一条、`urlTemplate` / `httpMethod` /
`inputSchema` 全靠手填，其中 `inputSchema` 还得手写 JSON Schema」，接入一套 REST API 的
成本从「按端点数线性投入」降到「一次导入」。

**非目标**（本期明确不做，写在这里免得被当成遗漏）：

- **平台共享工具侧的导入**（`/v1/platform/tools`）—— 平台共享工具按设计就该是「少量精选、
  只读/幂等」的（CLAUDE.md「平台共享工具」一节），批量导入几十个不符合其使用形态；而那条
  路径背着 `scope` 强制、`SharedCredentialMasker` 脱敏（漏 `em.detach` 会真删凭据）、
  `tenant_id IS NULL` 三重约束，历史上反复出过跨租户越权，扩面必须独立立项并配自己的 IT。
- **Swagger 2.0** —— 见 §2.3。
- **上传 schema 文件** —— 粘贴与 URL 已覆盖，多一条 multipart 校验链（大小/扩展名/真实
  MIME）而能力重合。
- **远程 `$ref`** —— 见 §2.1，这是安全决策而非工作量决策。
- **导入后与 schema 的持续同步** —— ⚠️ 最容易被想当然期待成「像 MCP 那样能再同步一次」的
  一条。**导入是一次性的**：schema 之后变了不会自动反映到已生成的工具，要重新导入并手工
  处理与既有工具的关系（名字冲突会被检出，见 §3.2）。做双向同步需要在 `AiTool` 上记住
  「来自哪份 schema 的哪个 operation」并处理三分支（新增/更新/远端消失），那是 MCP
  `sync()` 那一整套的复杂度，与本期「降低首次接入成本」的目标不成比例。

## 2. 关键决策

### 2.1 解析用已在类路径的 swagger-core，不引入 swagger-parser

`springdoc-openapi-starter-webmvc-ui` 已经把 `io.swagger.core.v3:swagger-core-jakarta` 与
`swagger-models-jakarta`（2.2.47）传递进 **compile** 类路径，其中 `Json31` / `Yaml31` 能把
文本反序列化成官方的 `OpenAPI` 模型对象，JSON 与 YAML 都支持。

**不用 `io.swagger.parser.v3:swagger-parser-v3`** 的主要理由不是依赖重量，而是安全：它的
`ParseOptions.setResolve(true)` 会**自己去 fetch 远程 `$ref`**——那是一条完全绕过
`SsrfGuard` 的出站路径，藏在依赖内部。可以显式关掉，但「关掉了没有」是静默的，需要专门写
测试钉住；而本项目对 SSRF 的一贯处理是硬门槛而非配置项。用 swagger-core 则**远程 `$ref`
在结构上就不存在**（它只做反序列化，不解析任何 `$ref`）。

⚠️ **必须在 `pom.xml` 里显式声明这两个坐标**（不写 version，由 springdoc/BOM 托管）。现在
它们是传递依赖，哪天 springdoc 换实现或改依赖，编译会突然断，而原因与本特性看不出关系。

⚠️ swagger-core 用的是 **Jackson 2**（`com.fasterxml.jackson`），本项目主栈是 **Jackson 3**
（`tools.jackson`）。两者在类路径上共存（实测 2.21.4 与 3.1.4 并存，包名不同互不冲突），
但**别混用**：解析 OpenAPI 文本走 swagger-core 自带的 mapper，我们自己产出的
`inputSchema` JSON 文本走项目的 Jackson 3 `ObjectMapper`。

### 2.2 只解本地 `$ref`，带深度上限与循环检测

只解析 `#/components/schemas/X` 形式的本地引用。跨文件与远程 `$ref` 直接不支持（§2.1）。

必须带**深度上限 + 循环检测**：自引用结构（评论树、组织架构、分类树）在真实 schema 里很
常见，`Category.children: array<Category>` 这类不设防就是无限递归栈溢出。超限时降级成
`{"type":"object"}` 并记 warning，而不是抛异常——一个字段解不开不该让整份 schema 导不进来。

### 2.3 只支持 OpenAPI 3.x

3.0 与 3.1 都收。两者在 schema 表达上有差异（3.1 用 JSON Schema 2020-12），但本设计只取
`paths` / `parameters` / `requestBody` 的浅层结构，差异影响很小。

Swagger 2.0 不支持：它的结构差异较大（`basePath` / `consumes` / `parameters.in=body`），
要么引 swagger-parser 做转换（与 §2.1 冲突），要么自己写一套转换。检测到 `swagger: "2.x"`
时给明确错误消息，而不是丢一个含糊的解析失败。

### 2.4 只导入 GET 与 POST

`RestClientHttpToolExecutor.execute` 的分派是
`"POST".equalsIgnoreCase(method) ? HttpMethod.POST : HttpMethod.GET`——**非 POST 一律降级
成 GET**。硬把 PUT/PATCH/DELETE 导进来，结果是一个 DELETE 端点被当成 GET 发出去，语义错得
无声无息。

故这三类 operation **跳过并记 warning**。这不是偷懒：让 `AiTool` 支持更多方法是对运行时
执行器的独立改动，且「让 LLM 能调 DELETE」本身是需要单独评估的安全决策（同 MCP 那边「只挂
只读工具」的取舍）。

### 2.5 import 端点收「前端编辑后的候选列表」，不收「schema + 勾选的 operationId」

后者要在后端重新解析一次，两次结果可能不一致（schema URL 的内容变了、解析器行为随版本变），
用户在预览里看到的与实际落库的会悄悄分叉。收列表则所见即所得。

**这不引入新攻击面**：既有 `POST /v1/ai-tools` 本来就直接收整个 `AiTool` 实体，
`urlTemplate` / `authHeader` 一直是租户管理员可控字段。本端点的写入同样经
`TenantEntityListener` 回填租户，且 `BaseTenantEntity.tenantId` 带
`@JsonProperty(READ_ONLY)`（请求体给了也绑不上）。

### 2.6 复用 `WebPageFetcher.downloadRaw`，不新写一套出站防线

URL 拉取那条路复用网站知识库那套硬门槛（`SsrfGuard` 钉 IP + 禁重定向 + 超时 + 5MB 上限 +
Content-Type 白名单）。`downloadRaw` 是 public 的，其注释已明写「public 是为了让
`RobotsChecker` 复用这一整套出站防线」。

只需把 Content-Type 白名单参数化：`downloadRaw(url, allowedTypes)`，原方法委托并传默认集合
（`text/html` + `text/plain`），网页抓取与 robots 两条既有调用点行为不变。

**不把防线抽成独立类**：那要改动网页抓取链路，而那条链路挂着 13 条手工验证项；收益只是包
依赖方向更好看。代价是 `service/openapi` 依赖 `service/web`，方向单一、无环，可接受。

**不复制一份**：安全代码复制出第二份必然漂移——卡片 Action URI 白名单分裂三处就是本仓库的
前车之鉴（CLAUDE.md 专门为此立了一节 + 一个哨兵测试）。

## 3. 映射规则（核心）

对 `paths` 下每个 path 的每个 operation，产出一个候选工具。

⚠️ **warnings 分两级，别混成一个列表**：**候选级** warning 挂在具体候选上（描述被截断、
header 参数被忽略、`$ref` 被降级、名字冲突），随它显示在勾选列表里；**全局级** warning 描述
「压根没能成为候选」的那些（PUT/DELETE 被跳过、requestBody 非 object 被跳过、超出 200 条
上限被截断）。后者没有对应的候选行可挂——只做候选级 warning 的话，这些 operation 会**无声
消失**，用户只会觉得"我这个端点怎么没导进来"，而这正是最需要解释的一类。

### 3.1 字段映射

| `AiTool` 字段 | 来源 | 细则 |
|---|---|---|
| `name` | `operationId` | 清洗成 `[A-Za-z0-9_]`，首字符非字母则前置 `t_`；缺失时用 `{method}_{path}` 生成。⚠️ **截断到 64**，见 §6.1 |
| `description` | `summary`，空则 `description`，再空则 `{METHOD} {path}` | 截断到 500（DB 列宽），截断时记 warning |
| `httpMethod` | operation 的方法 | 只 GET / POST，其余跳过（§2.4） |
| `urlTemplate` | `baseUrl` + path + query 串 | 见 §3.3 |
| `inputSchema` | path + query 参数 + requestBody | 见 §3.4 |
| `authHeader` / `timeoutMs` / `maxResponseChars` / `enabled` | 导入面板统一填 | 应用到本批全部工具 |

`scope` 由控制器强制 `TENANT`（同既有 create 端点，`scope` 是客户端可控字段，不清掉租户可
自封 `PLATFORM`——CLAUDE.md「平台共享工具」第 1 条记录的真实事故）。

### 3.2 名字冲突

- **批内冲突**：加 `_2` / `_3` 后缀。
- **与库里已有工具冲突**：parse 阶段查一次本租户现有工具名，标 `nameConflict: true`，前端
  高亮并要求改名后才能提交；import 阶段**再检一次**（parse 与 import 是两次请求，中间可能
  有并发创建，且接口层不能只靠界面拦）。

⚠️ 冲突检测只看**本租户**（`AiToolRepository` 的查询是 filter-bound 的）。跨租户同名是完全
正常的，不该拦。

### 3.3 `urlTemplate` 的拼接

`baseUrl` + path + query 串。

- **path 直接原样拼**：OpenAPI 的路径占位符语法 `{orderId}` 与本项目 `fillTemplate` 的
  `{key}` **完全一致**，无需转换。
- **query 串**：把 query 参数按 schema 里的**原始顺序**拼成 `?a={a}&b={b}`。不按
  required/optional 重排——顺序稳定才可预期，且 §5 的新 `fillTemplate` 能正确处理任意位置的
  可选参数。
- ⚠️ **`baseUrl` 已含 query 时用 `&` 连接**（如 `https://x.com/api?v=1`）。
- **`baseUrl` 的来源**：导入面板的输入框，默认预填 `servers[0].url`。⚠️ 很多 schema 的
  `servers` 是相对路径（`/v1`）甚至缺失，此时留空 + warning 要求填写——**不能**猜测成
  schema URL 的 origin：那在 schema 托管地址与 API 实际地址不同的常见情形下会静默生成一批
  指向错误主机的工具。

### 3.4 `inputSchema` 的三个来源

产出 `{"type":"object","properties":{...},"required":[...]}`，properties 来自：

1. **path 参数** —— 全部 `required`（URL 里填不出来就没法调用）。
2. **query 参数** —— 按各自的 `required` 标记。
3. **POST 的 `requestBody`（`application/json`）** —— 把 body schema 的 properties
   **平铺合并到顶层**。

第 3 条是被运行时逼出来的：`HttpToolCallback` 在 POST 时把**整个 toolInput 原样当 body**
发出去，所以 body 字段必须出现在参数对象的顶层，不能嵌在 `body: {...}` 里。

由此得到一个硬边界：**`requestBody` 的 schema 不是 object 时，该 operation 跳过 + warning**。
`POST /items` 收一个数组、或收一个裸字符串的端点，在当前模型里根本表达不了——LLM 交出来的
永远是一个 object。

每个参数的 `description` 一并带过来（直接影响 LLM 填参质量）。

**冲突**：body 字段与 query/path 参数同名时，保留参数侧的定义（URL 必须能填出来）并记
warning。

⚠️ **已知限制，写进文档不修**：POST 时 path/query 参数会**同时**出现在 URL 与 body JSON 里
（因为整个 toolInput 作 body）。多数服务端忽略多余字段。要修得改运行时热路径的 body 构造，
不在本期范围。

### 3.5 被忽略的部分

- **header / cookie 参数** —— `AiTool` 只有一个静态 `authHeader`，无法表达按调用填的 header。
  记 warning（这条尤其重要，见 §6.2）。
- **非 `application/json` 的 requestBody**（form-urlencoded / multipart）—— 执行器只发
  JSON body，跳过 + warning。
- **`security` / `securitySchemes`** —— 不自动映射成 `authHeader`（它只是声明"需要什么认证"，
  凭据本身不在 schema 里）。由用户在导入面板统一填。

### 3.6 上限

parse 最多返回 **200** 个候选，import 一次最多 **50** 条。一份 schema（如各家云厂商的）可能
有上千 operation，不设限会让预览界面与批量事务双双失控。超出时截断并明确告知，而不是静默丢弃。

## 4. 组件划分

新包 `service/openapi/`，四个职责单一的组件：

| 组件 | 职责 | IO |
|---|---|---|
| `OpenApiSchemaParser` | 文本 → `OpenAPI` 模型；JSON/YAML 判别；版本校验（3.x） | 无 |
| `OpenApiToolMapper` | `OpenAPI` + 导入配置 → 候选列表 + warnings | **纯函数，无 IO 无 DB** |
| `OpenApiSchemaFetcher` | URL → schema 文本 | 委托 `WebPageFetcher.downloadRaw` |
| `OpenApiImportService` | 编排：取文本 → 解析 → 映射 → 冲突标记 / 批量落库 | DB |

映射层做成纯函数是刻意的：它是本特性规则最密、最需要大量边界单测的部分，不该被 HTTP 或 DB
拖累。判据是「给定一份 `OpenAPI` 对象与一份配置，输出完全确定」。

## 5. 运行时改动：`UrlTemplateFiller`

把 `HttpToolCallback.fillTemplate`（private，三行 `replace`）抽成独立的纯函数类
`service/tool/UrlTemplateFiller`。现状它对 LLM 未填的占位符原样保留，导致
`?q=%E9%9E%8B&limit={limit}` 这样的 URL 被真的发出去——**可选 query 参数在今天的模型里是
坏的**，手工配置也一样坏，只是没人系统性地用过。

**新规则**，按 URL 的两段分别处理：

- **path 段**：替换 args 里有的占位符（URL 编码，同现状）。仍有未替换的 → **抛异常**，由
  `HttpToolCallback.call()` 既有的 catch 变成 `"工具调用失败: 缺少必填参数 orderId"` 返回给
  LLM。
- **query 段**：按 `&` 拆开逐段判断：
  - 段内占位符都有值 → 替换；
  - **段内任一占位符缺值 → 整段删除**（`?range={from}-{to}` 只填一半，`range=5-` 几乎肯定
    比不传更糟）；
  - 无占位符的静态段（`apiKey=xxx`）→ 原样保留。
- 重组时若 query 段全被删光，连 `?` 一起去掉。

**两处既有行为变化**（都判为变好，但必须记档）：

1. 未填的 query 占位符：字面量发出去 → 整段删除。旧行为是坏的，不存在依赖它的可能。
2. 未填的 **path** 占位符：字面量发出去（服务端必然 404）→ 不发请求、直接把「缺少必填参数
   X」返回给 LLM。让配置错误更早、更明确地暴露：过去 LLM 收到 404 无从判断原因，现在可以
   直接补参数重试。

⚠️ **一条现状就成立、但必须钉住的正确性**：参数值里若含 `{` `}` 字面量不会引发二次替换，
因为 `URLEncoder.encode` 把它们编码成了 `%7B` / `%7D`。这不是新加的保护，但要写成测试，
免得将来有人"优化"掉编码时把它一起弄丢。

## 6. 最容易写错的地方

### 6.1 工具名截断到 100 而不是 64

`AiTool.name` 列宽是 100，按它截断能存进库、能在界面上正常显示，但 OpenAI 兼容端点对函数名
的限制是 **64**。超长的工具挂给 LLM 时被模型侧拒绝，症状是「工具配好了、界面一切正常、就是
从来不被调用」——没有异常、没有日志，与「LLM 觉得不需要调用」完全无法区分。

### 6.2 warnings 当成日志而不是界面元素

每个候选的 warnings 必须显示在勾选列表里、就近可见。不这样的话用户会以为导进来就能用，而
一个「因为忽略了 header 认证参数所以永远返回 401」的工具，在运行时是 **fail-soft** 的：
`HttpToolCallback.call()` catch 住异常返回一句错误文本，模型拿着它继续编答案，全程无异常无
日志。本仓库已经在平台共享工具与 MCP 两处踩过同款症状。

### 6.3 parse 端点写库

parse 必须**只读**：它要查一次现有工具名做冲突标记，但绝不能落任何行。用户可能反复解析同
一份 schema 调整配置，每次都落库会留下大量垃圾。IT 里用「断言表行数不变」钉住。

### 6.4 `$ref` 循环导致栈溢出

见 §2.2。真实 schema 里的自引用结构很常见，且它不是畸形输入——是合法且正当的建模。

### 6.5 `baseUrl` 缺失时猜测成 schema URL 的 origin

见 §3.3。schema 托管地址（如 GitHub raw、文档站）与 API 实际地址不同是常态，猜测会静默生成
一批指向错误主机的工具，而错误要到对话真的触发工具时才暴露。

### 6.6 前端不判 `res.code`

本项目业务异常是「HTTP 200 + `body.code`」约定，axios 不会 reject。导入对话框的两个写操作
都必须显式 `if (res.code !== 200) throw`，否则解析失败、名字冲突、超出条数上限这些**全部
静默**，用户点按钮毫无反应且没有任何报错。

### 6.7 禁重定向导致的拉取失败给不出可读原因

`downloadRaw` 硬关重定向（重定向目标不再经 SsrfGuard 校验），而真实世界里会 302 的 schema
URL 很常见。错误消息必须明确说「目标地址发生跳转（HTTP 302），请填写跳转后的最终地址」，
而不是丢一个"拉取失败"让人猜。这是安全取舍的**必然**代价，只能靠消息质量补偿。

## 7. 端点与前端

### 7.1 端点（`AiToolController`，`/v1/ai-tools`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/openapi/parse` | 收 `{schemaText}` 或 `{schemaUrl}`（二选一）+ 可选 `baseUrl`，返回候选列表 + 全局 warnings。**不落库** |
| POST | `/openapi/import` | 收候选列表 + 统一配置，批量创建。**整批一个事务** |

import 整批一个事务、任一失败全回滚：冲突已在 parse 阶段暴露过一轮，此处失败是小概率，而
部分成功会让用户完全不知道哪些进去了。

⚠️ **只有 import 打 `@AuditLog`（`TOOL_IMPORT`）**。parse 是只读的（§6.3），且用户会为调整
配置反复解析同一份 schema，给它打审计只会把审计日志刷满无信息量的行。

### 7.2 前端

`ToolConfigPage` 加「从 OpenAPI 导入」按钮，一个 Dialog 内走三步：

1. **输入** —— 粘贴文本 / URL 二选一 + 「解析」；
2. **预览** —— 候选列表（勾选框 + 可编辑 name/description + warnings 就近显示）+ 统一配置
   （baseUrl / authHeader / timeoutMs / maxResponseChars）；
3. **提交** —— 调 import，成功后 toast + 刷新列表 + 关闭。

## 8. 测试策略

**单测（Surefire，纯函数 / Mockito）**：

- `UrlTemplateFiller`（~12 条）：可选 query 缺值删整段 / 多占位符段任一缺值即删 / 静态段保留 /
  全删后不留 `?` / `baseUrl` 已含 query / path 缺值抛异常 / URL 编码 / 值含 `{}` 不二次替换
- `OpenApiToolMapper`（~15 条）：GET+POST 收而 PUT/PATCH/DELETE 跳过 / `operationId` 缺失时
  的名字生成 / 名字清洗与**截断 64** / 批内重名加后缀 / path+query+body 三来源合并 /
  requestBody 非 object 跳过 / 本地 `$ref` 解析 / `$ref` 循环降级不栈溢出 / header 参数记
  warning / `servers` 相对或缺失 / 描述截断 / 上限截断
- `OpenApiSchemaParser`：JSON 与 YAML 都能解 / Swagger 2.0 给明确错误 / 非法输入不抛原始
  解析器异常

**集成测试（Failsafe，`it/`，继承 `AbstractPostgresIT`）**：

- import 批量落库，`tenant_id` / `app_id` 正确（走 MockMvc 真实请求——`@Filter` 只在真实请求
  流经 `TenantInterceptor` 时生效）
- **parse 不落库**（§6.3，断言表行数不变）
- 名字冲突检测只看本租户（跨租户同名应当允许创建）

**变异验证**（本仓库规矩：绿本身证明不了守护有效）：至少对 `UrlTemplateFiller` 做一次——把
「删整段」改回「留字面量」，相关测试必须转红。

**前端**：按「改到哪补到哪」的常态约定给导入对话框补 vitest（模板同 `ToolConfigPage.test.tsx`：
桩 api service + `useToast`，断言业务错误弹 destructive toast、成功才刷新列表）。⚠️ Radix
Dialog 要桩但**不能桩成 `() => null`**，否则回调驱动的提交路径静默零覆盖（`CrawlSiteDialog`
的前车之鉴）。

**手工验证（自动化覆盖不到，须写进 CLAUDE.md 新一节）**：

1. **真实公开 schema 走通全链**：导入 → 勾选 → 绑进 skill → 对话触发 → 确认出站 URL 与参数
   正确。单测只能证明映射规则对，证明不了「生成出来的工具真的能被 LLM 调用并拿到结果」。
2. **可选参数不填时端到端正确**：提一个不涉及可选参数的问题，确认出站 URL 里没有残留占位符
   也没有半截 query 段。§5 的单测钉的是纯函数，这条钉的是它真的被接在链路上。
3. **YAML schema 能导入**：JSON 与 YAML 走的是解析器的两条不同分支。
4. **会 302 的 schema URL 给出可读错误**（§6.7），而不是含糊的失败。

## 9. 范围外（后续可能）

- 平台共享工具侧的导入（§1）
- Swagger 2.0（§2.3）
- 上传 schema 文件（§1）
- 导入后与 schema 的持续同步（§1）
- 让 `AiTool` 支持 PUT/PATCH/DELETE（§2.4，需独立的安全评估）
- 按调用填充的 header 参数（§3.5，需扩 `AiTool` 模型）
- POST 时把 path/query 参数从 body 里剔除（§3.4，需改运行时 body 构造）
- form-urlencoded / multipart 请求体（§3.5）

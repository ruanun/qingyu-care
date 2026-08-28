# 工具商店（能力包）设计

> 2026-08-25 · 作者 ruan
> 对应规划：Phase 5.3「插件运行时 + 工具商店」（原工具调用延后清单第 5 项）
> 前置：MCP 接入（2026-08-21）、平台共享工具（2026-08-12）、OpenAPI 工具导入（2026-08-23）均已完成

## 1. 目标与非目标

租户接一套外部能力，现在得自己填 HTTP 工具、自己登记 MCP server、自己导 OpenAPI，**每个应用重来一遍**，
还得自己摸索提示词怎么写 LLM 才真的会去调。本特性把这件事变成「挑一个包 → 填自己的凭据 → 装好能用」。

**目标**

- 平台管理员把自己已经调通的一组工具 + skill **导出成包**上架；租户一键安装到自己的应用。
- 安装产物是**租户自己的副本**：落在租户名下，填自己的凭据，之后可改可停用可删。
- 装完即可用——包里带 skill（提示词），不是只丢一堆工具让租户自己摸索。

**非目标（每条都有理由，不是漏了）**

- **不做插件运行时**（下载 / 构建 / 起进程 / socket，即 tgo-plugin-runtime 那套）。「让第三方扩展能力」
  这件事已由 **MCP 接入**给出答案——第三方代码跑在**对方**那边，本项目只做协议客户端，安全边界是
  `SsrfGuard` 而非本地沙箱。再自建本地运行时等于把已经推出去的风险重新拉回来，还要新起一套沙箱、
  进程生命周期、资源限额与多租户隔离。
- **不做版本升级**。安装是一次性的，装完与包断开（同 OpenAPI 导入的取舍）。
- **不做租户投稿上架**。本项目的租户是客户不是开发者；开放投稿要配审核、版本、下架联动，是另一个量级。
- **不记安装记录**。见 §2.3。
- **不做包分类 / 图标 / 评分**。运营量级还不需要。

## 2. 关键决策

### 2.1 安装是复制副本，不是引用共享

`scope=PLATFORM` 的共享工具已经覆盖了「引用」语义：用平台的凭据、租户不能改。商店的价值恰在反面
——装下来是租户自己的行，填自己的凭据、能改能停用。**两者不重叠，也不能互相替代**：共享工具适合
平台统一采购的能力（平台付费、租户共用），商店适合租户各自开户的能力（各填各的 key）。

### 2.2 上架方式是「从现成配置导出」，不是包编辑器

平台管理员在自己的应用里把工具与 skill **真的调通、真的对话验证过**，再一键导出成包。

替代方案是做一个包编辑器（在界面上从零填 manifest），已否决：那是不小的表单工程，而且填出来的东西
**没被任何一次真实对话验证过**。导出路径复用的是他本来就要走的配置流程，副产品是「租户侧导出」能力
天然就有了（本期只开放平台侧入口，租户侧留待需要时开）。

### 2.3 不建安装记录表

「一次性、装完断开」就该没有这张表。有了它就会长出「已安装」状态，而那个状态是假的——租户随手删掉
装下来的工具，表里仍写着已安装。冲突检测按 name 现查即可（同 OpenAPI 导入按名字标冲突的做法）。

### 2.4 `ToolPackage` 不继承 `BaseTenantEntity`

它没有租户维度：平台建、所有租户可读。继承了反而要为「租户读平台行」再写一遍绕过 `@Filter` 的
native 查询（配置回退那套的成本）。沿用 `SysAuditLog` / `WebhookDelivery` / `SysApiUsage` 那批
显式不继承、自己带字段的先例。**写入侧的隔离靠端点分离**：平台端点 `/v1/platform/packages/**` 要
`PLATFORM_ADMIN`，租户端点只读 + 安装。

### 2.5 Java 包名用 `pkg` 而非 `package`

`package` 是 Java 关键字，`com.qingyu.care.service.package` 直接编译不过。定为
`service/pkg/`、`dto/pkg/`。写代码时第一次撞上会浪费一轮，先在这里钉死。

## 3. 数据模型与 manifest

### 3.1 `ToolPackage`

| 字段 | 说明 |
|---|---|
| `code` | 唯一标识，租户安装端点用它寻址（`/v1/packages/{code}/install`） |
| `name` / `description` | 商店文案 |
| `status` | `DRAFT` / `PUBLISHED` / `ARCHIVED`，租户列表只返回 `PUBLISHED` |
| `manifest` | **`text`**，存 manifest 的 JSON 序列化结果 |
| `createdAt` / `updatedAt` | |

⚠️ **`manifest` 必须是 `text`，不能设列宽**：长度由包里有多少工具、skill 的 markdown 有多长决定，
没有可预估的上限。`McpTool.description` 就是设成 `varchar(1000)` 之后在真机接入 context7 时整个
server 接不进来（CLAUDE.md「MCP 接入」第 10 条）。同一个坑不换位置再踩一次。

⚠️ **刻意没有 `version` 字段**。一次性安装下没有任何逻辑会消费它，而一个纯展示的版本号会诱使人
以为存在版本管理（"我装的是哪一版" 恰恰是我们查不出来的，因为不记安装记录）。

### 3.2 manifest 结构

```jsonc
{
  "manifestVersion": 1,
  "tools": [{
    "key": "weather_query",          // 包内符号，仅用于引用，不落库
    "name": "...", "description": "...", "inputSchema": "...",
    "httpMethod": "GET", "urlTemplate": "...",
    "timeoutMs": 5000, "maxResponseChars": 4000,
    "authHeaderVar": "WEATHER_API_KEY"   // 引用 variables 里的变量名，可空
  }],
  "mcpServers": [{
    "key": "ctx7",
    "name": "...", "code": "...", "description": "...",
    "transport": "STREAMABLE_HTTP", "url": "...",
    "timeoutMs": 10000, "maxResponseChars": 4000,
    "authHeaderVar": "CTX7_TOKEN"
  }],
  "skills": [{
    "key": "weather_skill",
    "name": "...", "description": "...", "content": "...(markdown)",
    "toolKeys": ["weather_query"],
    "mcpRefs": [{ "serverKey": "ctx7", "toolNames": ["resolve-library-id"] }]
  }],
  "variables": [{
    "name": "WEATHER_API_KEY", "label": "和风天气 API Key",
    "required": true, "secret": true, "help": "在 xxx 控制台获取"
  }]
}
```

### 3.3 引用一律用包内 `key`，绝不用 id

`AiSkill` 绑的是 `toolIds`（`Set<Long>`），而安装时工具是新建行、id 全新。安装期间维护一张
`key → 新 id` 映射表，建完工具再回填 skill。

⚠️ 漏掉这层映射的后果是**完全静默**：`ChatToolService.resolve()` 的 filter-bound 查询查不到那个
id 就自然跳过，不报错、不打日志，界面上 skill 仍显示绑着 N 个工具，实际挂给 LLM 的少了几个——与
CLAUDE.md「MCP 接入」第 7 条（删 server 必须清理 skill 绑定）**同一个失效模式**。

⚠️ **`mcpRefs` 引用的是工具名而非工具 id**，因为安装那一刻 `mcp_tool` 表里一行都还没有（见 §4.3）。

### 3.4 manifest 里没有 `scope`，也没有 `tenantId` / `appId`

安装时一律由服务端强制写 `OwnerScope.TENANT` + 当前 `TenantContext`。

⚠️ `scope` 是客户端可控字段这条**已经真实栽过一次**：租户 POST `/v1/ai-tools` 时在请求体里自封
`"scope":"PLATFORM"`，落库后带着自己的 `authHeader` 出现在所有租户的共享列表里（CLAUDE.md
「平台共享工具」第 1 条）。manifest 是另一条能把值送进实体的路径，不能让它把这个洞复活。

### 3.5 变量只能出现在四个白名单字段

`AiTool.authHeader`、`AiTool.urlTemplate`、`McpServer.authHeader`、`McpServer.url`。

**不做全文模板替换**：那意味着租户填的值能落进 manifest 的任意位置（包括 skill 的 markdown、
`inputSchema`），既难审计又给注入留了口子。字段级引用（`authHeaderVar`）+ URL 类字段的替换后
结构校验（**必须仍是 http/https 绝对地址**）是本期的表达力边界。

## 4. 两条路径

### 4.1 导出（上架）

`POST /v1/platform/packages/export`，body 是平台管理员勾选的 `toolIds` / `mcpServerIds` / `skillIds`，
产出一个 `DRAFT` 包，补完文案与变量 `label` 后 `PUBLISH`。

1. **凭据升格成变量**：`authHeader` 非空 → 生成一个 `secret: true` 的变量，字段改为引用它。
2. **两类 warning，不阻断**：
   - skill 绑了不在本次导出集合内的工具 → 装出来会绑不全；
   - `urlTemplate` / `url` 里疑似嵌了密钥（扫 `key=` / `token=` / `secret=` / `apikey=`）。
     这是启发式，**最终仍要平台管理员逐字看一遍 manifest 预览再发布**——同噪声剔除，没法自动化。
3. **发布前 `status=DRAFT` 对租户不可见**，预览就是给这一步用的。

⚠️ **导出全程只读实体，绝不 setter 置空**。本项目未配 `spring.jpa.open-in-view`，即 Spring Boot
默认的 `true`，OSIV 让查出来的实体在整个请求期保持**受管**，直接 `setAuthHeader(null)` 会被脏检查
刷回库——本意是防泄漏，结果是**把平台管理员自己的凭据真的删掉**（`SharedCredentialMasker` 那次
就是靠 `em.detach` 才闭合的）。这里连 `detach` 都不需要：读 getter 拼 manifest DTO，从头不碰实体
状态。⚠️ 直调 service 的单测**证明不了这条**（实体本来就是分离的，断言会静默退化成永真），必须用
真实 MockMvc 请求钉住，同 `SharedToolIT`。

### 4.2 安装

`POST /v1/packages/{code}/install`，body 是变量取值；目标应用取 `X-App-Id`（页面外包 `<RequireApp>`）。

1. 解析 manifest → 校验必填变量齐全 → 应用变量（含 URL 结构校验）。
2. **冲突检测，整体拒绝不自动改名**：按 name 在当前租户+应用范围内查已有的 `AiTool` / `AiSkill`
   / `McpServer`（外加 `McpServer.code`，它是 LLM 工具名前缀，同租户内不能撞），有冲突就列出来让
   租户先处理。⚠️ 静默改名会让人以为装好了却在列表里找不到自己认识的名字。
3. **段一（事务内）**：建 `AiTool` / `McpServer` / `AiSkill`（`mcpToolIds` 先留空），提交。
4. **段二（事务外）**：对每个 `McpServer` best-effort `sync()`；成功则按 `mcpRefs.toolNames` 映射
   成新建的 `McpTool` id，再开一个短事务回填 skill。
5. 返回安装摘要：建了哪些行、哪些需要后续操作。

安装、发布、下架都是写操作，一律打 `@AuditLog`（安装尤其要——它一次性在租户名下建出一批带凭据的
工具，事后要能查是谁在什么时候装的）。

### 4.3 为什么必须分两段

`McpServerService.sync()` 是**网络出站 + 自己写库**，且**刻意不带 `@Transactional`**——它的半截
状态靠重新同步收敛（CLAUDE.md「MCP 接入」第 9 条）。把它包进安装事务有两重问题：长事务占着连接
做网络往返；以及跟它自己的非事务语义直接打架。这与 `AfterCommit.run(...)` 那条既有教训同源
（事务内投递 `@Async` → 异步侧读不到未提交的行）。

**段二失败不回滚段一**，这是有意的部分成功：`McpServer` 已经装好了，界面明确提示「同步失败，请
手动同步后到 skill 里勾选工具」。整个回滚对租户更糟——工具和 skill 全白装，而它们跟 MCP 同步失败
没有任何关系。

## 5. 组件划分

| 组件 | 职责 | 形态 |
|---|---|---|
| `entity/ToolPackage` + `enums/PackageStatus` | 包本体（见 §3.1） | 实体，**不继承** `BaseTenantEntity` |
| `dto/pkg/PackageManifest` 及子 record | manifest 的类型化表示 | record |
| `service/pkg/PackageManifestBuilder` | 实体 → manifest + warnings（导出） | **纯函数** |
| `service/pkg/PackageManifestParser` | JSON → `PackageManifest` + 结构校验 | **纯函数** |
| `service/pkg/PackageVariableApplier` | manifest + 变量取值 → 已填充的 manifest | **纯函数** |
| `service/pkg/ToolPackageService` | 平台侧 CRUD、导出编排、发布/下架 | `@Service` |
| `service/pkg/PackageInstallService` | 安装编排（冲突检测 + 两段事务） | `@Service` |
| `controller/PlatformPackageController` | `/v1/platform/packages/**` | `PLATFORM_ADMIN` |
| `controller/ToolPackageController` | `/v1/packages`（只读 + 安装） | `TENANT_ADMIN` |

三个纯函数类是规则最密、单测最值钱的部分，与 `OpenApiToolMapper` 同范式（那次的经验：把规则关进
纯函数，编排层只做取数与落库）。

## 6. 最容易写错的地方

### 6.1 忘了把 `key → 新 id` 回填进 skill

见 §3.3。**完全静默**，只表现为 LLM 从来不调那个工具。

### 6.2 导出时 setter 置空凭据

见 §4.1。后果不是泄漏而是**把平台管理员的凭据删了**，且单测证明不了。

### 6.3 安装时信了 manifest 里的 `scope` / 租户字段

见 §3.4。manifest DTO 里就不该有这些字段——**没有比判空更可靠**。

### 6.4 把 `sync()` 放进安装事务

见 §4.3。

### 6.5 `manifest` 列设了宽度

见 §3.1。存量库另需迁移 SQL——`ddl-auto: update` 只加列、不改已有列的类型。

### 6.6 前端不判 `res.code`

全局约定：业务异常是「HTTP 200 + `body.code`」，axios 不 reject。安装失败（变量缺失、名字冲突）
全都是这条路回来的——不判就是**点了安装毫无反应**。写法见 `CrawlSiteList.tsx` 的四个写操作。

### 6.7 变量做成全文替换

见 §3.5。

### 6.8 导出的字段抄写会随实体演进漂移

这是方案本身唯一的结构性弱点，用哨兵补（见 §7）。

## 7. 守护

**扫描哨兵 `PackageManifestFieldCoverageTest`**：`AiTool` / `AiSkill` / `McpServer` 的可变字段必须
要么出现在对应的 manifest record 里，要么在白名单中显式声明类别与理由。范式**直接复刻
`UpdateRequestFieldCoverageTest`**，连同它的 `ExclusionCategory` 枚举（2026-08-25 刚做完技术债
第 19 项的产物）——那套「差集必须被刻意声明」的结构正好适用：实体加了字段、导出没跟上，就会静默
少装一个字段。

**单测（Surefire，纯 Mockito）**：三个纯函数类各自的规则——凭据升格、集合外引用告警、密钥启发式、
符号引用映射、变量白名单字段、URL 结构校验、必填变量校验；`PackageInstallService` 的冲突拒绝与
`scope` 强制。

**IT（Failsafe）**：① 装一个含工具 + skill 的包，断言落库的 `tenant_id` / `app_id` 正确、
`scope=TENANT`、skill 的 `toolIds` 指向新建行；② manifest 里塞 `scope: PLATFORM` 必须被忽略
（照 `CreateTenantInjectionIT` 的写法）；③ 导出走**真实 MockMvc 请求**，断言平台管理员那条工具的
`authHeader` 在库里仍在（§4.1 的 OSIV 那条，直调 service 会永真）。

## 8. 手工验证（自动化都够不到）

1. **凭据真的是租户填的那个**：装一个含 HTTP 工具 + skill 的包 → 发一句触发工具的对话 → 确认出站
   请求头里带的是安装时填的值。凭据若在某个环节被清掉，症状是第三方返 401、模型拿着错误响应继续
   编答案，**全程无异常无日志**（同平台共享工具那条）。
2. **MCP 包的两条路径**：正常 url 装一次，确认 sync 成功后 skill 的 `mcpToolIds` 真绑上了；再用坏
   url 装一次，确认是**部分成功**（工具与 skill 都在、提示明确），不是整体失败。
3. **上下文不泄漏**：连续给**两个不同租户**各装同一个包，确认第二个租户的行 `tenant_id` / `app_id`
   仍正确。这是第 1 条的另一半，**只有真机连做两次才暴露**，单次安装永远是绿的（同站点爬取第 7 条）。

⚠️ 「导出不吃掉平台凭据」那条**不在这张表里**——它由 §7 的 IT ③ 用真实 MockMvc 请求覆盖（OSIV 过滤器
在 `@SpringBootTest(MOCK)` 下确实生效，`SharedToolIT` 已经这么钉过一次）。列进手工清单只会让这张
「自动化够不到」的表贬值。

## 9. 已知限制

- **MCP 类型的包必须同步一次才有工具**：安装那一刻 `mcp_tool` 表里没有行，是两段式的必然结果。
  同步成功时安装流程会自动完成绑定，失败时留给租户手动做。⚠️ 同步成功后工具仍是「可写」
  状态——`McpServerService.sync()` 对新建的工具一律 `readOnly=false`，而
  `PackageInstallService` **绝不代劳**标只读（只读是租户自己的安全判据，代劳就是安全降级且
  完全静默）。所以即使 skill 的 `mcpToolIds` 已经绑上，工具此刻仍挂不给 AI，必须由租户自己
  到 MCP 服务页手动标为只读——安装结果里的 warning 会明确提示这一步。
- **包没有版本与升级**：一次性安装的取舍。平台改了包不影响已装的。
- **不支持租户投稿上架**。
- **变量只能出现在四个白名单字段**（`AiTool.authHeader` / `AiTool.urlTemplate` /
  `McpServer.authHeader` / `McpServer.url`，与 §3.5 一致）：需要租户填私有部署地址之类的场景已覆盖（`urlTemplate` / `url`
  在白名单里），但填进 `inputSchema` 或 skill 正文的场景不支持。
- **重复安装会产生重复的工具与 skill**：冲突检测按 name 拒绝，租户改名后仍可再装一份。同 OpenAPI
  导入的既有行为。

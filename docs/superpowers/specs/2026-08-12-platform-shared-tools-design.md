# 平台共享工具 + 共享行凭据脱敏

> 日期：2026-08-12
> 作者：ruan
> 来源：`SaaS平台规划.md` Phase 5.4 第 6 项

## 1. 背景与目标

工具（`AiTool`）目前是纯租户级资源：每个租户要自己配 URL、鉴权头、参数 schema。而查天气、查快递、查汇率这类工具对所有租户是同一份——重复配置是纯粹的运营负担，且每个租户都得自备第三方 API key。

**目标**：平台管理员维护一个共享 HTTP 工具库，租户建 skill 时可直接勾选这些工具使用，无需知道也无法读取平台的凭据。

范式在仓库里现成：`AiModelConfig` 的 `OwnerScope.PLATFORM` + 「自有 ∪ 平台共享」合并（`ModelRouterService.getAllModels`），本设计照抄这套。

### 同批修复的既有漏洞

实施过程中实测确认：**租户管理员可通过 `GET /api/v1/ai-models` 读到平台共享模型的明文 `apiKey`**（用演示租户的 `demo_admin` 实测拿到 `sk-c47…6fb9`，那是平台自己的 DashScope key）。

`TenantOwnershipGuard` 的类注释其实已经写明「含密钥的资源读路径也应使用本守卫」，`getById` 也确实用了——**漏的是 list 端点，它压根没经过守卫**。前端刻意不回显（`ModelConfigPage` 里 `{ ...model, apiKey: '' }`），但数据已经发到了线上，devtools 或 curl 直接可见。

这与本特性要引入的是**同一个模式**（共享行含明文凭据 + 列表接口原样返回实体），照抄范式就会把 `authHeader` 用同样的方式漏出去。因此两者一并处理：抽一个脱敏助手，同时用于 `AiTool.authHeader` 与 `AiModelConfig.apiKey`。

### 非目标

- **不共享 `AiSkill`**。skill 的 markdown 会注入进每一轮系统提示，平台加一条 skill 等于静默改掉所有租户的 Prompt 与回答行为，风险与收益不匹配。skill 保持租户自建。
- **不做共享工具的使用统计**（哪些租户绑了）。需要时查 `ai_skill_tool` 表即可，YAGNI。
- **不改 SSRF 门槛与工具执行链路**。`HttpToolExecutor` 的既有防护对共享工具同样生效，无需改动。
- **不做凭据加密存储**。明文存储是既有技术债（`AiTool.authHeader` 与 `AiModelConfig.apiKey` 的注释都已标注），本次只堵「泄漏给不该看的人」，不改存储形态。

---

## 2. 现状勘查（已核实）

| 事实 | 影响 |
|------|------|
| `ChatToolService.resolve()` 用 `skillRepository.findByEnabledTrue()` 与 `toolRepository.findByIdInAndEnabledTrue(ids)`，都是 **filter-bound 查询** | 平台共享行 `tenant_id IS NULL`，Hibernate `@Filter` **根本看不见**——同 CLAUDE.md「多级配置回退」那个坑，共享查询必须走 native SQL |
| 工具**只经 skill 激活**（`resolve()` 注释写明，无 skill 时返回 `empty()`） | 「共享工具」与「共享 skill」语义完全不同；只共享工具不会产生任何静默的 Prompt 变化 |
| `AiToolController.list()` 返回 `List<AiTool>` 实体，含明文 `authHeader` | 现在无害（只返租户自己的凭据），并入共享项后即成泄漏 |
| `AiToolController.update()` 已有「空值不覆盖」约定（`if (tool.getAuthHeader() != null && !isEmpty())`） | 脱敏后回传保存不会清空原凭据，无需额外处理 |
| `TenantOwnershipGuard.mutableInCurrentContext` 对 `tenantId == null` 的行只放行平台上下文 | 现成的「能否看凭据」判据，脱敏直接复用，不新造概念 |
| `AiModelConfigRepository.findAllSharedModels()` 是 native SQL + `WHERE scope = 'PLATFORM'` | 要照抄的写法就在这儿 |
| `AiTool` / `AiSkill` 均无 `scope` 字段 | 本次给 `AiTool` 加 |

---

## 3. 设计

### 3.1 数据模型

`AiTool` 新增一个字段，与 `AiModelConfig` 完全一致：

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
@Builder.Default
private OwnerScope scope = OwnerScope.TENANT;
```

存量行由 `ddl-auto: update` 加列。⚠️ 新列 `nullable = false` 且存量行无值，需配一条迁移 SQL 把历史行回填为 `'TENANT'`（同 `migration/` 下既有脚本的一次性手工执行约定，幂等：只更新 `scope IS NULL` 的行）。

> 开发库当前 `ai_tool` 只有 1 行，但迁移脚本仍要写——其他环境（staging/生产）的行数未知，且 `ddl-auto: update` 加非空列时 PostgreSQL 对已有行的行为取决于是否给 default，不能靠运气。

平台共享行的三个字段固定为：`scope = PLATFORM`、`tenant_id = NULL`、`app_id = NULL`，由服务层 `saveSharedTool` 强制设定，不信任请求体。

### 3.2 仓库层

`AiToolRepository` 新增两条 **native SQL**（绕开 `@Filter`，否则查不到 `tenant_id IS NULL` 的行）：

```java
/** 全部平台共享工具（平台管理面 + 租户列表合并用） */
@Query(value = "SELECT * FROM ai_tool WHERE scope = 'PLATFORM' ORDER BY name", nativeQuery = true)
List<AiTool> findAllSharedTools();

/** 按 id 集合取启用的平台共享工具（运行时解析用） */
@Query(value = "SELECT * FROM ai_tool WHERE scope = 'PLATFORM' AND enabled = true AND id IN (:ids)",
       nativeQuery = true)
List<AiTool> findSharedByIdInAndEnabledTrue(@Param("ids") Collection<Long> ids);
```

> ⚠️ **两条都必须钉 `scope = 'PLATFORM'`**。现在的 `findByIdInAndEnabledTrue` 是 filter-bound 的，别的租户的 id 天然查不出来；改用 native 后若只写 `WHERE id IN (...)`，就变成**任意 id 都能绑 = 跨租户越权读取他人工具（含其鉴权头）**。这是本设计最容易写错、后果最严重的一处。

### 3.3 运行时解析（`ChatToolService.resolve()`）

工具解析从一条查询变成两条求并集：

```java
List<AiTool> tools = new ArrayList<>(toolRepository.findByIdInAndEnabledTrue(toolIds)); // 自有，filter-bound
Set<Long> found = tools.stream().map(AiTool::getId).collect(Collectors.toSet());
Set<Long> rest = toolIds.stream().filter(id -> !found.contains(id)).collect(Collectors.toSet());
if (!rest.isEmpty()) {
    tools.addAll(toolRepository.findSharedByIdInAndEnabledTrue(rest));   // 平台共享，native
}
```

先查自有再补共享，等价于「租户自有优先」——同一个 id 不可能同时是两者，所以这里的顺序只影响查询次数不影响结果；`rest` 为空时省掉第二条查询（绝大多数租户没绑共享工具，不该为此多打一次库）。

skill 侧不变：仍是 `findByEnabledTrue()`，因为 skill 不共享。

> ⚠️ **这条路径拿到的必须是未脱敏的原始实体**——`HttpToolCallback` 要用 `authHeader` 真的发出站请求。脱敏若做在仓库层或实体上，共享工具会在运行时**没有凭据、静默失败**（第三方返 401，模型拿到一段错误响应继续编答案，全程无异常）。**脱敏只发生在管理面出口。**

### 3.4 凭据脱敏

新增 `config/SharedCredentialMasker`（与 `TenantOwnershipGuard` 同包同风格的静态工具类）：

```java
/**
 * 共享行凭据脱敏：非平台上下文读到平台共享行时，把凭据字段置空。
 * 判据直接复用 TenantOwnershipGuard.mutableInCurrentContext——「能写它」与「能看它的凭据」
 * 是同一个权限边界，不新造概念。
 */
public static <T> List<T> maskCredentials(List<T> list,
                                          Function<T, Long> tenantIdGetter,
                                          Function<T, Long> appIdGetter,
                                          Consumer<T> credentialCleaner) { ... }
```

调用点两处，**都是面向租户的 list**：
- `ChatToolService.listTools()` → 清 `authHeader`
- `ModelRouterService.getAllModels()` → 清 `apiKey`

⚠️ **`getSharedModels()` / `listSharedTools()` 不接脱敏**。它们只被 `PlatformController` 调用，每个端点开头都有 `SecurityUtil.requirePlatformAdmin()`，而平台管理员维护共享项时**必须能看到并回填凭据**。虽然脱敏判据对平台上下文本就是 no-op（`mutableInCurrentContext` 在 `ctxTenantId == null` 时返回 true），但不接更清楚地表达了「这条路径的受众是平台管理员」。

**写入侧不受影响**：`AiToolController.update` 与模型侧同款逻辑都已是「空值不覆盖原值」，脱敏后的对象回传保存不会抹掉真实凭据。

⚠️ 脱敏必须作用在**从库里读出来的对象**上，而这些对象在同一个持久化上下文里可能是**受管实体**——直接 setter 置空会被 Hibernate 脏检查当成真实修改刷回库，**把凭据真的删掉**。因此脱敏路径必须在只读事务外，或对分离实体操作。本设计的调用点（`listTools` / `getAllModels`）都不带 `@Transactional`，查询完即分离，安全；**但实现时要在测试里钉住这一条**（见 §5）。

### 3.5 管理面

**租户侧**（`ChatToolService`）：

```java
public List<AiTool> listTools() {
    Long appId = TenantContext.getAppId();
    List<AiTool> own = appId == null ? List.of() : toolRepository.findByAppId(appId);
    List<AiTool> shared = toolRepository.findAllSharedTools();
    return mask(mergeById(own, shared));    // 合并语义同 ModelRouterService.mergeOwnAndSharedById
}
```

`getTool` / `saveTool` / `deleteTool` **完全不动**——现有的 `mutableInCurrentContext` 守卫已经保证租户不能读凭据、不能改、不能删平台工具。

**平台侧**（新增，仿 `ModelRouterService.getSharedModels` / `saveSharedModel`）：

```java
public List<AiTool> listSharedTools()                  // 平台管理员用，不脱敏
public AiTool saveSharedTool(AiTool t)                 // 强制 scope=PLATFORM, tenantId=null, appId=null
public void deleteSharedTool(Long id)                  // 校验确是 PLATFORM 行再删
```

端点挂在既有的 `PlatformController` 下，路径 **`/v1/platform/tools`**（与共享模型的 `/v1/platform/models` 对齐，该控制器的既有约定是不带 `shared-` 前缀，靠 `/platform` 这一层表达含义）。

⚠️ 鉴权是**每个方法开头显式调 `SecurityUtil.requirePlatformAdmin()`**，不是类级注解——新增的三个端点必须逐个加上，漏一个就是平台凭据对所有登录用户敞开。`updateSharedTool` 还须照抄 `updateSharedModel` 的防越权判断：只允许操作 `scope == PLATFORM` 的行，避免拿租户私有工具的 id 越权提权。

### 3.6 前端

| 页面 | 改动 |
|------|------|
| 新增 `SharedToolPage.tsx` | 平台管理员维护共享工具，结构仿 `SharedModelPage.tsx`（同款 reload / 对话框 / `res.code !== 200` 判错 + destructive toast） |
| 侧边栏 | 「平台管理」组下加「共享工具」，与「共享模型池」并排 |
| `SkillConfigPage.tsx` | 工具选择器给 `scope === 'PLATFORM'` 的项加一个「平台共享」badge，让租户知道这不是自己的工具 |
| `ToolConfigPage.tsx` | 列表里同样标注平台共享项，且**不显示编辑/删除按钮**（后端已挡，前端不该给出一个必然失败的入口） |

### 3.7 共享工具停用语义

平台把某个共享工具 `enabled = false` → **对所有租户立即失效**（`findSharedByIdInAndEnabledTrue` 带 `enabled = true` 条件，自然生效）。不为已绑定的租户保留。

理由：共享工具的凭据与配额都在平台侧，平台停用通常是因为凭据失效或成本失控，此时继续让租户调用只会得到一串失败响应。

---

## 4. 影响面与风险

| 风险 | 处理 |
|------|------|
| native 查询漏掉 `scope='PLATFORM'` → 跨租户越权 | §3.2 明确标注；单测 + IT 双重守护（§5） |
| 脱敏做到运行时路径 → 共享工具静默失效 | §3.3 明确分离；手工验证兜底（§5） |
| 脱敏 setter 触发脏检查把凭据刷回 null | §3.4 说明；测试钉住「脱敏后库里凭据仍在」 |
| 存量 `ai_tool` 行 `scope` 为 NULL 撞 `nullable=false` | 配迁移 SQL 回填 `'TENANT'` |
| 租户误以为能编辑平台工具 | 前端隐藏入口，后端守卫不动 |

## 5. 测试

**单元测试（Surefire，纯 Mockito）**

1. `resolve()` 能解析到平台共享工具（自有查询返空、共享查询返一条 → bundle 含该工具）
2. `resolve()` 解析不到其他租户的工具（两条查询都返空 → bundle 为空）——**安全不变式 1 的守护**
3. `rest` 为空时不发起第二条查询（`verify(never())`）
4. `listTools()` 对非平台上下文清空共享项的 `authHeader`，且**不影响自有项**
5. `listTools()` 对平台上下文原样返回
6. 模型侧 `getAllModels()` 同 4/5 两条

**集成测试（Failsafe，Testcontainers）**

7. `SharedToolIT`：租户 A 建 skill 绑定「平台共享工具 id」→ 能解析；绑定「租户 B 的工具 id」→ 解析不到。走真实 MockMvc 请求——`@Filter` 只在真实请求流经 `TenantInterceptor` 时才生效，**这条只有 IT 能测**。
8. 同 IT 内验证：脱敏返回后再查库，`auth_header` / `api_key` 仍在（守 §3.4 的脏检查风险）。

**必须手工验证（自动化覆盖不到）**

9. 建一个平台共享工具（配真实可访问的 HTTP 端点 + 鉴权头），租户建 skill 绑定它并**真的触发一次工具调用**，确认出站请求带上了正确的鉴权头。单测里 `HttpToolExecutor` 是 mock 的，不校验凭据是否为空——脱敏若误伤运行时路径，只有真机能发现。

## 6. 交付物

**新增**
- `backend/.../config/SharedCredentialMasker.java`
- `backend/.../resources/migration/2026-08-12-backfill-ai-tool-scope.sql`
- `frontend/src/pages/SharedToolPage.tsx`
- 对应单测与 `SharedToolIT`

**修改**
- `AiTool`（加 `scope`）、`AiToolRepository`（两条 native 查询）
- `ChatToolService`（`resolve` 求并集、`listTools` 合并+脱敏、平台侧三个方法）
- `ModelRouterService`（list 路径接入脱敏）
- `PlatformController`（共享工具 CRUD 端点）
- `frontend`：`App.tsx` 路由、`MainLayout.tsx` 菜单、`SkillConfigPage.tsx`、`ToolConfigPage.tsx`、`api/platform.ts`
- `SaaS平台规划.md`（5.4 第 6 项标记完成）、`CLAUDE.md`（架构要点补共享工具的两条不变式）

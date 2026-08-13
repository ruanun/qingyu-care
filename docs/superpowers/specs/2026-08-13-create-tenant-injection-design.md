# create 端点跨租户写入收口：归属字段在绑定层阻断

> 日期：2026-08-13
> 作者：ruan
> 来源：`docs/技术债清单.md` 第 14 项

## 1. 背景

`BaseTenantEntity` 是裸 `@Data`，`tenantId` / `appId` 有公开 setter、无任何 Jackson 屏蔽；
`TenantEntityListener.prePersist` **只在字段为 null 时**才从 `TenantContext` 回填。

租户侧四个 create 端点把请求体直接绑到实体，落库前不清空这两个字段：

| 端点 | 控制器 | save 路径 |
|------|--------|-----------|
| `POST /v1/ai-tools` | `AiToolController.create` | `ChatToolService.saveTool` 裸 `save` |
| `POST /v1/ai-models` | `AiModelController.createModel` | `ModelRouterService.saveModel` 裸 `save` |
| `POST /v1/ai-skills` | `AiSkillController.create` | `ChatToolService.saveSkill` 裸 `save` |
| `POST /v1/strategies` | `ChatStrategyController.create` | `ChatStrategyService.saveStrategy`（守卫只在 `id != null` 时生效，create 路径 `id == null` 跳过） |

⇒ 租户管理员 `POST {"name":"x","tenantId":<他人租户id>}`，该字段非 null，监听器不覆盖，
行直接建进目标租户，随后出现在受害租户的列表里（`listTools` 等是 filter-bound，按 `tenant_id` 命中）。
若建的是工具，`urlTemplate` 与 `authHeader` 均由攻击者控制。

### 严重性定级

**中**，不是「立即失守」：

- 需要知道目标租户 id（自增整数，可枚举）；
- 建进去的行要真正造成危害，还需受害租户**主动把它绑进自己的 skill**；
- ⚠️ **最坏变体已被挡住**：传 `tenantId: null`（或不带该字段）会被监听器正常回填成当前租户，
  做不到「自封为平台共享（`tenant_id IS NULL`）」——那条更严重的路径已在 2026-08-12
  那批修掉（见 CLAUDE.md「平台共享工具」第 1 条）。

即便如此，「租户能往别人租户写数据」本身就越过了本项目最核心的不变量，故清理。

### 定性

**既有缺口，非任何一批引入。** 2026-08-13 那批做 update 端点 DTO 化时，
在核实「`scope` 是客户端可控字段」的同族问题时顺带发现：`tenantId` 同样是客户端可控的，
而当时的修复只覆盖了 `scope`。

## 2. 方案：绑定层结构性阻断

`BaseTenantEntity` 的两个字段各加一个注解：

```java
@JsonProperty(access = JsonProperty.Access.READ_ONLY)
@Column(name = "tenant_id")
private Long tenantId;

@JsonProperty(access = JsonProperty.Access.READ_ONLY)
@Column(name = "app_id")
private Long appId;
```

Jackson 反序列化时不认这两个字段，`@PrePersist` 照常从 `TenantContext` 回填。攻击者带别人的
`tenantId` 发过来，行落进**他自己的**租户——无害。序列化方向不受影响，响应契约零改动。

### 2.1 为什么改一处就够

所有受租户隔离的实体都继承 `BaseTenantEntity`。现有五个绑实体的 create 端点
（上表四个 + `AbTestController.create`）与**将来任何新增端点**自动免疫。

这正是它优于「四处显式 `setTenantId(null)`」的地方：后一种修法的成因，就是「新端点时人得记得加」。
本仓库已经为同一模式付过两次学费（`scope` 自封、以及这一条）。

### 2.2 不动的三处

`AbTestService.create`、`ChatToolService.saveSharedTool`、`ModelRouterService.saveSharedModel`
里既有的 `setTenantId(null)` / `setAppId(null)` **保留**。它们对 JSON 路径现在冗余，但仍守着
程序化误用（不经 Jackson 的代码路径），且 `saveShared*` 的清空本身表达了「平台共享 = 归属为 NULL」
这个业务语义，不是纯防御。

### 2.3 硬约束：不能改成「监听器总是用上下文覆盖」

`QaGenerationService` 在 `@Async` 线程里用 setter 从源文档传播租户：

```java
// @Async 无 TenantContext：显式从源文档行回填租户/应用，避免 @PrePersist 写 null
qa.setTenantId(doc.getTenantId());
qa.setAppId(doc.getAppId());
```

改成「监听器无条件用 `TenantContext` 覆盖」会把这里写成 **null**——异步线程没有上下文。
后果不是「对所有租户可见」而是反过来「对任何租户都检索不到」：`tenantId` 为 null 时向量
metadata 写成 `""`，与 `tenantId == '<真实id>'` 永远匹配不上。成本照花、0 命中、全程无异常无日志
（同 CLAUDE.md 里 `DemoDataSeeder` 与站点爬取两处记过的事故）。

**注解只影响 JSON 绑定、不影响 setter**，故这条链路不受触碰。这是选中本方案的决定性理由。

### 2.4 与「平台管理员指定租户」不冲突

本仓库确有「平台管理员建资源时指定归属租户」的合法模式，但它们走的是**另外两套机制**：

- `ApplicationController.create`：用 `tenant` **关系映射**（`application.getTenant().getId()`），
  且 `Application` 不继承 `BaseTenantEntity`；
- `UserController.createUser`：收 `Map<String,Object>`，且 `User` 不继承 `BaseTenantEntity`。

两者都不经 `BaseTenantEntity.tenantId` 这个标量，故本次改动不影响它们。

## 3. 必须先实证的前提

**动手前第一件事**：确认 `@JsonProperty(access = READ_ONLY)` 加在 `@MappedSuperclass` 的字段上，
配合 Lombok `@Data`（生成 setter）与实体上的 `@NoArgsConstructor(onConstructor_ = @JsonCreator)`，
**真的能阻断绑定**。

不能假设。本仓库在「`ddl-auto` 会自动加 NOT NULL 列」这个未实证的前提上栽过一次
（实际是整表失败且只打 WARN）。

**若实证不通过，停下来报告，改走方案二（四处显式清空）**，不要绕路硬凑。

## 4. 测试

### 单元测试（Surefire）

用 **Spring 配好的 `ObjectMapper`** 反序列化带归属字段的 JSON，断言两个字段为 null：

```
{"name":"x","urlTemplate":"https://e.com/{q}","tenantId":99,"appId":88}
  → new AiTool().getTenantId() == null && getAppId() == null
```

### 集成测试（Failsafe）

租户 A 真实 MockMvc `POST /v1/ai-tools`，请求体带租户 B 的 `tenantId` → 断言落库行的
`tenant_id` 是 **A**，不是 B。

⚠️ **两层都要**：单测若自建 `ObjectMapper`，可能与 Spring 实际配置不一致，断言会静默失去意义；
IT 才走真实的 Jackson 配置与完整请求链路。这与本仓库 `SharedToolIT` 用真实 MockMvc 守 OSIV
脏检查是同一个理由（直调 service 时实体本来就是分离的，断言会退化成永真）。

### 变异验证（必做）

删掉 `BaseTenantEntity` 上的两个注解 → 单测与 IT **都必须转红**。贴真实终端输出。

⚠️ 变异前先 `grep` 确认注解真的被删掉了再跑测试——「变异没落地」与「守护失效」在输出上完全
无法区分，本仓库已在这上面栽过三次。

## 5. 取舍：静默丢弃而非显式拒绝

租户传了别人的 id 不会报错，行落进自己租户。

- **好处**：零新增运行时失败模式，不可能误伤任何既有调用方；
- **代价**：恶意尝试不留痕迹。

判断：`@AuditLog` 已记录 create 操作，且落库结果本身正确，不值得为留痕引入 403 分支与它带来的
误伤面。

## 6. 非目标

- 不动 `TenantEntityListener` 的回填逻辑（见 §2.3）。
- 不给 `Application` / `User` / `Tenant` 加同类防护——它们不继承 `BaseTenantEntity`，
  归属由关系映射或显式校验决定，是另一套机制。
- 不改既有的三处显式清空（见 §2.2）。
- 不引入「显式拒绝 + 告警」（见 §5）。

## 7. 交付物

**修改**：`entity/BaseTenantEntity.java`（两个注解）；`docs/技术债清单.md` 第 14 项标记已清理并折叠原问题。

**新增**：归属字段绑定阻断的单测；跨租户 create 的 IT。

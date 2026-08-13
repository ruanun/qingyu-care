# 局部更新语义修复：update 端点全面 DTO 化

> 日期：2026-08-13
> 作者：ruan
> 来源：`docs/技术债清单.md` 第 11 项

## 1. 背景

八个 update 端点直接用 **JPA 实体**接收请求体，靠 `if (field != null) existing.setX(...)` 实现「空值不覆盖」。这个写法建立在一个错误前提上：**请求体没给的字段会是 `null`**。

实际不是。Lombok 的 `@Builder.Default` **会把默认值也应用进无参构造**（已用字节码确认）：

```
public com.qingyu.care.entity.AiTool();
  12: invokestatic  $default$timeoutMs        → putfield timeoutMs
  19: invokestatic  $default$maxResponseChars → putfield maxResponseChars
```

而 Jackson 反序列化走的正是那个无参构造（实体上标了 `@NoArgsConstructor(onConstructor_ = @JsonCreator)`）。所以请求体里没给的字段拿到的是**类默认值而非 null**，`!= null` 恒真，把库里的值覆盖掉。

运行时实测：

```
new AiSkill().toolIds        = []
new Tenant().plan            = FREE
new Application().themeColor = #6366f1
new ChatStrategy().similarityThreshold = 0.45 / topK = 5
```

### 实际后果（前端五处局部 PUT 全部可达）

| 操作 | 后果 |
|------|------|
| 技能页点**启用开关** | `toolIds` 被覆盖成 `[]` —— **该技能绑定的工具全部清空**，重新启用后是空壳 |
| 租户页改**状态**（冻结/启用） | `plan` 打回 `FREE` —— **套餐降级，直接影响计费额度** |
| 工具页点启用开关 | `httpMethod`→`GET`、`timeoutMs`→5000、`maxResponseChars`→4000，配好的 POST 工具变 GET |
| 应用页点启用开关 | `themeColor` 打回 `#6366f1` |
| 用户页点启用开关 | （`UserController` 用 `containsKey`，不受本条影响，但另有问题见 §2） |

全程无异常无日志。

## 2. 同批修复：编辑用户会把密码重置成空串

勘查中发现的**另一个活缺陷**，位置在本次要改的同一个方法里。

`UserListPage` 的编辑弹窗把 `password` 初始化为 `''`，注释写着「编辑时不显示/修改密码除非输入」——但提交时 `const { tenantId, ...rest } = formData` 把它一起发了出去。后端 `UserController.updateUser` 判的是 `request.containsKey("password")`，空串同样成立，于是执行 `BCrypt.hashpw("")`。

**后果**：管理员只是改了个昵称，该用户的密码哈希变成空串的哈希。旧密码登不上；空密码又被 `LoginRequest` 的 `@NotBlank` 拒掉（`AuthController.login` 有 `@Validated`）——**用户被彻底锁死，且毫无提示**。

⚠️ **单纯 DTO 化修不掉它**：前端发的是空串不是 null，`!= null` 照样成立。密码字段必须用 `!= null && !isEmpty()`（本仓库 `AiToolController` 对 `authHeader` 已是这个写法）。

## 3. 方案

### 3.1 八个 `*UpdateRequest` DTO

放 `dto/` 下与既有 `*Request` 平级。每个只含对应 update 端点**实际会覆盖**的字段。

**铁律：所有字段都是包装类型，一律不写字段初始化器、不加 `@Builder.Default`。** 这是整条修复的要害——`null` 必须真正表示「没传」。

> ⚠️ 既有的 `KnowledgeBaseRequest` 里写着 `private Boolean enabled = true;`，**正是反例**。它现在没出事只因为前端建/改知识库都发整份对象。新增的 DTO 一律不许有初始化器。

DTO 只用 `@Data`，**不加 `@Builder`**——加了 builder 就会诱使下一个人写 `@Builder.Default`，把坑重新种回来。

### 3.2 映射代码形状不变

```java
if (req.getX() != null) existing.setX(req.getX());
```

一行不改，只换参数类型。因为 DTO 没有默认值，这个判断此时才是对的。改动面看着大，实则是纯机械的类型替换。

### 3.3 DTO 里不放 `scope`

`AiTool` / `AiModelConfig` 的 update DTO **不含 `scope` 字段**。这让「租户不能自封平台共享」从「我们记得不拷贝」变成**结构上不可能**，把 2026-08-12 那批修的提权漏洞又加固一层。

### 3.4 密码与凭据用 `!= null && !isEmpty()`

三个字段：`UserUpdateRequest.password`、`AiToolUpdateRequest.authHeader`、`AiModelConfigUpdateRequest.apiKey`。语义是「前端不回显、留空表示不修改」，空串必须与 null 同等对待。

`authHeader` / `apiKey` 现有代码已经是这个写法，保持；`password` 是本次新修的（§2）。

### 3.5 `UserController` 从 `Map` 改成 DTO

它现在用 `Map<String,Object>` + `containsKey`，是仓库里唯一的另一种写法。统一成 DTO，仓库里只留一种范式。

⚠️ 迁移时两处不能丢：
- `enabled` 与 `role` 的权限判断（只有平台/租户管理员可改）
- 「非平台管理员不能把用户提升为 `PLATFORM_ADMIN`」这条校验

⚠️ **语义变化**：`containsKey` 支持「显式传 `null` 清空字段」，`!= null` 不支持。已核实前端不依赖此能力——`UserListPage` 的表单用空串 `''` 而非 `null`（`nickname: user.nickname || ''`），清空文本框发的是 `""`，两种写法行为一致。

### 3.6 范围

| 控制器 | 字段数 | 说明 |
|------|------|------|
| `AiToolController` | 9 | 不含 scope |
| `AiModelController` | 9 | 不含 scope |
| `AiSkillController` | 5 | `toolIds` 是最严重的一条 |
| `ApplicationController` | 10 | |
| `ChatStrategyController` | 9 | |
| `TenantController` | 7 | `plan` 是最严重的一条 |
| `PlatformController` | 18（两个 update） | 复用工具与模型的 DTO |
| `UserController` | 7 | 从 Map 改 DTO，含权限逻辑与密码修复 |

前端与 API 契约**完全不变**——接受的 JSON 形状一样，纯后端改动。

## 4. 非目标

- 不改 create 端点。它们发整份对象，不受影响；改了反而扩大风险面。
- 不清理既有 `*Request` DTO 里的字段初始化器（如 `KnowledgeBaseRequest.enabled`）。那是另一条线，本次只在注释里点名它是反例。
- 不动实体上的 `@Builder.Default`。那些默认值对 `builder()` 是必要的（播种代码依赖），问题从来不在实体，在「拿实体当请求 DTO」。

## 5. 测试

### 单元测试（Surefire，纯 Mockito）

每个受影响端点一条**局部更新回归**：只传一个字段，断言其他字段纹丝不动。

判别力要求：断言必须检查**具体的原值**（如 `assertThat(existing.getTimeoutMs()).isEqualTo(30000)`），不能只断言「非 null」——后者在字段被打回默认值时同样成立，是永真测试。

另加一条：**编辑用户时传空串密码，密码哈希不变**（守 §2）。

### 变异验证（必做，三条）

1. 给 `AiSkillUpdateRequest.toolIds` 加回字段初始化器 `= new HashSet<>()` → 对应测试必须转红
2. 给 `TenantUpdateRequest.plan` 加回 `= TenantPlan.FREE` → 对应测试必须转红
3. 把 `UserUpdateRequest.password` 的判断从 `!= null && !isEmpty()` 改回 `!= null` → 密码那条必须转红

每条都要贴真实终端输出。这三条守的都是「不做也照样跑得通、只在特定输入下毁数据」的约束，必须证明抓得住。

### 手工验证

Docker 起来后，真机走一遍最严重的两条：
1. 建一个绑了工具的技能 → 点停用 → 查 `ai_skill_tool` 表，绑定还在
2. 把某租户改成 PRO → 冻结该租户 → 查 `sys_tenant.plan`，仍是 PRO

## 6. 交付物

**新增**：`dto/` 下 8 个 `*UpdateRequest`；对应单测（含三条变异验证）

**修改**：8 个控制器的 update 方法签名与参数类型；`docs/技术债清单.md` 第 11 项标记已清理并折叠原问题；`UserListPage.tsx`（可选：编辑时不再提交空密码字段，作为前端侧的第二道）

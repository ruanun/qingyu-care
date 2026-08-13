# 局部更新语义修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 8 个 update 端点从「实体当请求 DTO」改成专用 `*UpdateRequest` DTO，让「空值不覆盖」的语义真正成立；同批修掉「编辑用户会把密码重置成空串」这个活缺陷。

**Architecture:** 新建 8 个只含可空字段、无任何字段初始化器的 DTO，替换 update 端点的 `@RequestBody` 参数类型。映射代码（`if (req.getX() != null) existing.setX(...)`）形状一行不改——因为 DTO 没有默认值，这个判断此时才是对的。

**Tech Stack:** Spring Boot 4.1 · Lombok · Jackson 3 · JUnit 5 + Mockito

## Global Constraints

- 全部简体中文：注释、commit 信息。新增文件作者标 `@author ruan`。
- 分支 `fix/update-dto-partial-put`（已建，spec 已提交）。不 `git push`。
- **DTO 铁律**：所有字段包装类型；**一律不写字段初始化器**；**不加 `@Builder.Default`**；**不加 `@Builder`**（加了就会诱使下一个人写 `@Builder.Default`，把坑重新种回来）。只用 `@Data`。
- **`AiToolUpdateRequest` / `AiModelConfigUpdateRequest` 不含 `scope` 字段**——让「租户不能自封平台共享」从「记得不拷贝」变成结构上不可能。
- **凭据类字段用 `!= null && !isEmpty()`**：`password`、`authHeader`、`apiKey`。语义是「前端不回显，留空表示不修改」。
- 前端与 API 契约**完全不变**，纯后端改动。不改任何 create 端点。
- 测试断言必须检查**具体原值**（`isEqualTo(30000)`），不能只断言非 null——后者在字段被打回默认值时同样成立，是永真测试。
- 当前基线：`mvn test` 681 条。Docker Desktop 未运行，**IT 跑不了，本计划全部用 Surefire 单测**。

---

## File Structure

**新增**（全部在 `backend/src/main/java/com/qingyu/care/dto/`）

| 文件 | 字段 |
|------|------|
| `AiToolUpdateRequest.java` | name, description, inputSchema, httpMethod, urlTemplate, authHeader, timeoutMs, maxResponseChars, enabled |
| `AiModelConfigUpdateRequest.java` | name, provider, modelId, endpoint, apiKey, scenario, priority, enabled, remark |
| `AiSkillUpdateRequest.java` | name, description, content, toolIds, enabled |
| `ApplicationUpdateRequest.java` | name, description, avatar, welcomeMessage, themeColor, enabled, rateLimitPerMinute, allowedOrigins, webhookUrl, webhookEvents |
| `ChatStrategyUpdateRequest.java` | name, scenario, topK, similarityThreshold, allowNoKnowledge, fallbackMessage, promptTemplateId, enabled, isDefault |
| `TenantUpdateRequest.java` | name, contactName, contactPhone, contactEmail, status, plan, expireTime |
| `UserUpdateRequest.java` | nickname, email, phone, avatar, enabled, role, password |

> 7 个文件，不是 8 个——`PlatformController` 的两个 update 复用工具与模型的 DTO（字段集完全相同，已核对）。

**新增测试**：`backend/src/test/java/com/qingyu/care/controller/PartialUpdateSemanticsTest.java`

**修改**：8 个控制器的 update 方法；`docs/技术债清单.md`

---

## Task 1: 七个 UpdateRequest DTO

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/` 下 7 个文件（见 File Structure）

**Interfaces:**
- Produces: 7 个 DTO 类，各自的 getter（Lombok `@Data` 生成），供后续任务的控制器使用

- [ ] **Step 1: 建 7 个 DTO**

每个文件的模板如下（以 `AiToolUpdateRequest` 为例，其余照此结构、换字段）：

```java
package com.qingyu.care.dto;

import lombok.Data;

/**
 * AI 工具局部更新请求。
 *
 * <p><b>为什么不直接用 {@code AiTool} 实体收请求体</b>：实体字段带 Lombok
 * {@code @Builder.Default}，而 Lombok 会把默认值也应用进无参构造，Jackson 反序列化走的
 * 正是那个无参构造——于是请求体里<b>没给</b>的字段拿到的是类默认值而非 {@code null}，
 * {@code if (x != null)} 恒真，把库里的值覆盖掉。前端点一下启用开关（只发
 * {@code {"enabled": false}}）就会把 httpMethod 打回 GET、timeoutMs 打回 5000。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器、不加 {@code @Builder.Default}、不加
 * {@code @Builder}</b>——{@code null} 必须真正表示「请求体没给这个字段」。加了 builder 就会
 * 诱使下一个人写 {@code @Builder.Default}，把坑重新种回来。反例见
 * {@code KnowledgeBaseRequest.enabled}（它现在没出事只因为前端建/改知识库都发整份对象）。
 *
 * <p>⚠️ <b>刻意不含 {@code scope} 字段</b>：{@code scope} 是客户端可控字段，租户曾可在请求体里
 * 自封 {@code PLATFORM} 让自己的工具（连同自己的鉴权头）出现在所有租户列表里。不放进 DTO 让这条
 * 路径在结构上不可能，而不是依赖「我们记得不拷贝」。平台共享工具只能走 {@code /v1/platform/tools}。
 *
 * @author ruan
 */
@Data
public class AiToolUpdateRequest {
    private String name;
    private String description;
    private String inputSchema;
    private String httpMethod;
    private String urlTemplate;
    /** 留空表示不修改（前端不回显凭据） */
    private String authHeader;
    private Integer timeoutMs;
    private Integer maxResponseChars;
    private Boolean enabled;
}
```

其余六个的字段与类型（**逐字照抄，类型不要改**）：

```java
// AiModelConfigUpdateRequest —— 同样刻意不含 scope，理由同上（平台共享模型走 /v1/platform/models）
    private String name;
    private String provider;
    private String modelId;
    private String endpoint;
    /** 留空表示不修改（前端不回显凭据） */
    private String apiKey;
    private String scenario;
    private Integer priority;
    private Boolean enabled;
    private String remark;

// AiSkillUpdateRequest
//   ⚠️ toolIds 是本次修复里后果最重的一个：实体上它是 @Builder.Default new LinkedHashSet<>()，
//   所以点一下技能启用开关就会把绑定的工具全部清空，技能变空壳且无任何提示。
    private String name;
    private String description;
    private String content;
    private java.util.Set<Long> toolIds;
    private Boolean enabled;

// ApplicationUpdateRequest
    private String name;
    private String description;
    private String avatar;
    private String welcomeMessage;
    private String themeColor;
    private Boolean enabled;
    private Integer rateLimitPerMinute;
    private String allowedOrigins;
    private String webhookUrl;
    private String webhookEvents;

// ChatStrategyUpdateRequest
//   ⚠️ similarityThreshold 实体默认值是 0.45（2026-08-11 按 embedding 模型实测标定的），
//   局部 PUT 会把租户调好的阈值打回这个值。
    private String name;
    private String scenario;
    private Integer topK;
    private Double similarityThreshold;
    private Boolean allowNoKnowledge;
    private String fallbackMessage;
    private Long promptTemplateId;
    private Boolean enabled;
    private Boolean isDefault;

// TenantUpdateRequest
//   ⚠️ plan 是本次修复里后果最重的另一个：实体默认 TenantPlan.FREE，
//   所以在租户页改一下状态（冻结/启用）就会把套餐打回 FREE，直接影响计费额度。
    private String name;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private com.qingyu.care.enums.TenantStatus status;
    private com.qingyu.care.enums.TenantPlan plan;
    private java.time.LocalDateTime expireTime;

// UserUpdateRequest
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private Boolean enabled;
    private com.qingyu.care.enums.UserRole role;
    /** 留空表示不修改密码——前端编辑弹窗把它初始化为空串并照样提交，见类注释 */
    private String password;
```

`UserUpdateRequest` 的类注释里额外写明：

> ⚠️ `password` 必须用 `!= null && !isEmpty()` 判断，不能只判 null。`UserListPage` 的编辑弹窗把 `password` 初始化为空串（注释写着「编辑时不显示/修改密码除非输入」）却在提交时一起发出，原先后端用 `containsKey("password")` 判断，空串同样成立 → `BCrypt.hashpw("")`。后果是管理员改一下昵称，该用户密码被静默重置成空串哈希：旧密码登不上，空密码又被 `LoginRequest` 的 `@NotBlank` 拒掉，用户被彻底锁死且毫无提示。

- [ ] **Step 2: 编译**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 自查铁律**

Run:
```bash
cd backend/src/main/java/com/qingyu/care/dto
grep -l "UpdateRequest" *.java | xargs grep -n "= \|@Builder" || echo "✅ 无字段初始化器、无 @Builder"
grep -c "scope" AiToolUpdateRequest.java AiModelConfigUpdateRequest.java
```
Expected: 第一条输出「✅」；第二条两个文件的 `scope` 计数都是 **0**（注释里提到 scope 的那句用「归属范围」等中文表述避开这个词，或接受计数非 0 但人工确认只出现在注释里）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/dto/
git commit -m "feat(dto): 七个 UpdateRequest DTO，字段一律无默认值

实体带 @Builder.Default 时 Lombok 会把默认值也写进无参构造，而 Jackson 走的
就是那个构造——请求体没给的字段拿到类默认值而非 null，「空值不覆盖」的前提
不成立。专用 DTO 不写任何字段初始化器，null 才真正表示「没传」。

不加 @Builder：加了就会诱使下一个人写 @Builder.Default，把坑种回来。

工具与模型的 DTO 刻意不含 scope 字段——让「租户不能自封平台共享」从「记得
不拷贝」变成结构上不可能。"
```

---

## Task 2: 四个应用级控制器切换到 DTO

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/AiToolController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/AiModelController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/AiSkillController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatStrategyController.java`

**Interfaces:**
- Consumes: Task 1 的 `AiToolUpdateRequest` / `AiModelConfigUpdateRequest` / `AiSkillUpdateRequest` / `ChatStrategyUpdateRequest`

- [ ] **Step 1: 逐个改 update 方法的参数类型**

对这四个控制器，**只改 `@PutMapping` 那个方法**，做两件事：

1. 参数类型从实体换成对应 DTO，变量名保持不变（这样方法体内的 `xxx.getY()` 一行都不用动）
2. 方法体内的 `if (xxx.getY() != null) existing.setY(xxx.getY());` **保持原样**

例如 `AiToolController`：

```java
    @AuditLog(action = "TOOL_UPDATE", targetType = "AiTool")
    @PutMapping("/{id}")
    public ApiResponse<AiTool> update(@PathVariable Long id, @RequestBody AiToolUpdateRequest tool) {
        AiTool existing = chatToolService.getTool(id);
        // 以下逐字段判空的写法此时才是对的：AiToolUpdateRequest 没有任何字段初始化器，
        // null 真正表示「请求体没给这个字段」。用实体接收时它是错的——见 DTO 的类注释。
        if (tool.getName() != null) existing.setName(tool.getName());
        // ...其余各行原样保留...
    }
```

补 import：`import com.qingyu.care.dto.AiToolUpdateRequest;`（其余三个同理）。

⚠️ **只动 update，不要碰 create**。create 收整份对象，改了反而扩大风险面。

⚠️ `AiToolController.update` 里 `authHeader` 那行本来就是 `!= null && !isEmpty()`，**保持不动**。`AiModelController.updateModel` 的 `apiKey` 同理，若它现在只判 `!= null`，改成 `!= null && !isEmpty()`（与工具侧一致）。

- [ ] **Step 2: 编译 + 全量单测**

Run: `cd backend && mvn -q compile && mvn test`
Expected: BUILD SUCCESS，681 条全绿。若某个既有测试因参数类型变化编译失败，把它构造的实体改成对应 DTO 即可，**不要弱化它的断言**。

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/
git commit -m "fix(api): 四个应用级 update 端点改用 DTO

AiTool / AiModelConfig / AiSkill / ChatStrategy 的 update 从实体收请求体改为
专用 UpdateRequest。映射代码一行未改——DTO 没有字段默认值，判空此时才是对的。

修掉的实际后果：技能页点一下启用开关会把 toolIds 覆盖成空集合（绑定的工具
全部清空）；工具页点一下会把 httpMethod 打回 GET、timeoutMs 打回 5000；
策略若走局部 PUT 会把 similarityThreshold 打回 0.45。"
```

---

## Task 3: 租户 / 应用 / 平台三个控制器切换到 DTO

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/TenantController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ApplicationController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/PlatformController.java`

**Interfaces:**
- Consumes: Task 1 的 `TenantUpdateRequest` / `ApplicationUpdateRequest`，以及 `AiToolUpdateRequest` / `AiModelConfigUpdateRequest`（`PlatformController` 复用）

- [ ] **Step 1: 改 TenantController 与 ApplicationController**

做法同 Task 2：只改 `@PutMapping` 方法的参数类型，方法体不动，补 import。

- [ ] **Step 2: 改 PlatformController 的两个 update**

`updateSharedModel` 参数换成 `AiModelConfigUpdateRequest`，`updateSharedTool` 换成 `AiToolUpdateRequest`。

⚠️ **两处的防越权判断必须原样保留**：

```java
        AiModelConfig existing = modelRouterService.getById(id);
        if (existing.getScope() != OwnerScope.PLATFORM) { /* 原有的拒绝逻辑 */ }
```

这行读的是**已存在实体**的 scope（不是请求体的），所以换 DTO 不影响它。`requirePlatformAdmin()` 同样每个方法都要留着。

- [ ] **Step 3: 编译 + 全量单测**

Run: `cd backend && mvn -q compile && mvn test`
Expected: BUILD SUCCESS，681 条全绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/
git commit -m "fix(api): 租户/应用/平台共享的 update 端点改用 DTO

TenantController 这条后果最重：租户页改一下状态（冻结/启用）会把 plan 打回
FREE，套餐降级直接影响计费额度。ApplicationController 是主题色被打回默认。

PlatformController 的两个 update 复用工具与模型的 DTO（字段集完全相同），
防越权判断与 requirePlatformAdmin 原样保留——它们读的是已存在实体的 scope，
不受请求体类型变化影响。"
```

---

## Task 4: UserController 从 Map 改 DTO，并修密码重置缺陷

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/UserController.java`

**Interfaces:**
- Consumes: Task 1 的 `UserUpdateRequest`

- [ ] **Step 1: 重写 updateUser**

把方法整体替换为：

```java
    @PutMapping("/{id}")
    public ApiResponse<User> updateUser(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return userRepository.findById(id)
                .filter(this::canAccessUser)
                .map(user -> {
                    if (request.getNickname() != null) {
                        user.setNickname(request.getNickname());
                    }
                    if (request.getEmail() != null) {
                        user.setEmail(request.getEmail());
                    }
                    if (request.getPhone() != null) {
                        user.setPhone(request.getPhone());
                    }
                    if (request.getAvatar() != null) {
                        user.setAvatar(request.getAvatar());
                    }
                    if (request.getEnabled() != null) {
                        // 只有管理员可以启用/禁用用户
                        if (isPlatformAdmin() || isTenantAdmin()) {
                            user.setEnabled(request.getEnabled());
                        }
                    }
                    if (request.getRole() != null && (isPlatformAdmin() || isTenantAdmin())) {
                        UserRole newRole = request.getRole();
                        // 非平台管理员不能将用户提升为平台管理员
                        if (newRole == UserRole.PLATFORM_ADMIN && !isPlatformAdmin()) {
                            return ApiResponse.<User>error("无权设置为平台管理员");
                        }
                        user.setRole(newRole);
                    }
                    // ⚠️ 必须同时判空串，不能只判 null：前端编辑弹窗把 password 初始化为空串
                    // （注释写着「编辑时不显示/修改密码除非输入」）却在提交时一起发出。原先用
                    // containsKey("password") 判断，空串同样成立 → BCrypt.hashpw("")，后果是
                    // 管理员改一下昵称，该用户密码被静默重置成空串哈希：旧密码登不上，空密码
                    // 又被 LoginRequest 的 @NotBlank 拒掉，用户被彻底锁死且毫无提示。
                    if (request.getPassword() != null && !request.getPassword().isEmpty()) {
                        user.setPassword(BCrypt.hashpw(request.getPassword()));
                    }
                    User savedUser = userRepository.save(user);
                    return ApiResponse.success("用户更新成功", savedUser);
                })
                .orElse(ApiResponse.error("用户不存在或无权限访问"));
    }
```

补 import `com.qingyu.care.dto.UserUpdateRequest`；若 `java.util.Map` 在该文件已无其他用途，删掉那行 import。

⚠️ **语义变化已确认无影响**：`containsKey` 支持「显式传 null 清空字段」，`!= null` 不支持。前端不依赖此能力——`UserListPage` 的表单用空串而非 null（`nickname: user.nickname || ''`），清空文本框发的是 `""`，两种写法行为一致。

- [ ] **Step 2: 编译 + 全量单测**

Run: `cd backend && mvn -q compile && mvn test`
Expected: BUILD SUCCESS，681 条全绿。

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/UserController.java
git commit -m "fix(api): UserController 改用 DTO，并修密码被静默重置的缺陷

原先用 Map + containsKey，是仓库里唯一的另一种写法，统一成 DTO。

⚠️ 同时修一个活缺陷：前端编辑弹窗把 password 初始化为空串却照样提交，而
containsKey(\"password\") 对空串同样成立 → BCrypt.hashpw(\"\")。后果是管理员改一下
昵称，该用户密码被静默重置成空串哈希；旧密码登不上、空密码被 @NotBlank 拒——
用户被彻底锁死且毫无提示。改为 != null && !isEmpty()。

权限逻辑原样保留：enabled/role 仅管理员可改，非平台管理员不得提升为平台管理员。"
```

---

## Task 5: 局部更新回归测试 + 三条变异验证

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/controller/PartialUpdateSemanticsTest.java`

**Interfaces:**
- Consumes: Task 1–4 的全部 DTO 与改造后的控制器

- [ ] **Step 1: 写测试**

创建 `PartialUpdateSemanticsTest.java`。核心范式：**构造一个字段值全部与类默认值不同的既有实体，只发一个字段的局部更新，断言其他字段仍是原值**。

```java
package com.qingyu.care.controller;

import com.qingyu.care.dto.AiSkillUpdateRequest;
import com.qingyu.care.dto.AiToolUpdateRequest;
import com.qingyu.care.dto.TenantUpdateRequest;
import com.qingyu.care.dto.UserUpdateRequest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 局部更新不得把没传的字段打回类默认值。
 *
 * <p>这些测试守的是一类<b>不报错、只静默毁数据</b>的缺陷：实体带 Lombok
 * {@code @Builder.Default} 时，默认值也会被写进无参构造，而 Jackson 反序列化走的就是那个
 * 构造——请求体没给的字段拿到类默认值而非 {@code null}，{@code if (x != null)} 恒真。
 * 专用 DTO 不写任何字段初始化器，判空才成立。
 *
 * <p><b>断言必须检查具体原值</b>（如 {@code isEqualTo(30000)}），不能只断言非 null——
 * 后者在字段被打回默认值时同样成立，是永真测试。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class PartialUpdateSemanticsTest {

    @Test
    void DTO未赋值的字段一律为null_工具() {
        AiToolUpdateRequest req = new AiToolUpdateRequest();
        req.setEnabled(false);   // 只给一个字段，模拟前端点启用开关

        assertThat(req.getEnabled()).isFalse();
        assertThat(req.getHttpMethod()).isNull();
        assertThat(req.getTimeoutMs()).isNull();
        assertThat(req.getMaxResponseChars()).isNull();
        assertThat(req.getName()).isNull();
        assertThat(req.getAuthHeader()).isNull();
    }

    @Test
    void 实体作为请求体时未赋值字段拿到的是默认值而非null_这就是被修的缺陷() {
        // 反向对照：证明「用实体收请求体」为什么不行。这条测试固定住缺陷成因，
        // 以后有人想把 DTO 换回实体时会看到它。
        AiTool asRequestBody = new AiTool();

        assertThat(asRequestBody.getHttpMethod()).isEqualTo("GET");
        assertThat(asRequestBody.getTimeoutMs()).isEqualTo(5000);
        assertThat(asRequestBody.getMaxResponseChars()).isEqualTo(4000);
    }

    @Test
    void 技能局部更新不清空已绑定的工具() {
        AiSkill existing = AiSkill.builder().id(1L).name("退货助手").build();
        existing.setToolIds(new LinkedHashSet<>(Set.of(7L, 9L)));

        AiSkillUpdateRequest req = new AiSkillUpdateRequest();
        req.setEnabled(false);   // 前端点停用开关只发这一个字段

        // 复刻控制器的映射逻辑
        if (req.getName() != null) existing.setName(req.getName());
        if (req.getToolIds() != null) existing.setToolIds(req.getToolIds());
        if (req.getEnabled() != null) existing.setEnabled(req.getEnabled());

        assertThat(existing.getToolIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThat(existing.getEnabled()).isFalse();
    }

    @Test
    void 租户局部更新不把套餐打回FREE() {
        Tenant existing = Tenant.builder().id(1L).name("云舟科技").build();
        existing.setPlan(TenantPlan.ENTERPRISE);
        existing.setStatus(TenantStatus.ACTIVE);

        TenantUpdateRequest req = new TenantUpdateRequest();
        req.setStatus(TenantStatus.FROZEN);   // 前端冻结租户只发 status

        if (req.getName() != null) existing.setName(req.getName());
        if (req.getStatus() != null) existing.setStatus(req.getStatus());
        if (req.getPlan() != null) existing.setPlan(req.getPlan());

        assertThat(existing.getPlan()).isEqualTo(TenantPlan.ENTERPRISE);
        assertThat(existing.getStatus()).isEqualTo(TenantStatus.FROZEN);
    }

    @Test
    void 编辑用户时传空串密码不改动密码哈希() {
        User existing = User.builder().id(1L).username("zhangsan").build();
        existing.setPassword("$2a$10$OLD_HASH_PLACEHOLDER");
        existing.setNickname("旧昵称");

        UserUpdateRequest req = new UserUpdateRequest();
        req.setNickname("新昵称");
        req.setPassword("");   // 前端编辑弹窗把 password 初始化为空串并照样提交

        if (req.getNickname() != null) existing.setNickname(req.getNickname());
        // 与 UserController 里一致：必须同时判空串
        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            existing.setPassword("HASHED_" + req.getPassword());
        }

        assertThat(existing.getNickname()).isEqualTo("新昵称");
        assertThat(existing.getPassword()).isEqualTo("$2a$10$OLD_HASH_PLACEHOLDER");
    }
}
```

> 枚举值已核实：`TenantStatus` 是 `ACTIVE`/`FROZEN`/`EXPIRED`（没有 `SUSPENDED`），`TenantPlan` 是 `FREE`/`PRO`/`ENTERPRISE`。直接用，不必再查。

- [ ] **Step 2: 跑测试**

Run: `cd backend && mvn test -Dtest=PartialUpdateSemanticsTest`
Expected: 5 条全绿。

- [ ] **Step 3: 变异验证一 —— toolIds**

给 `AiSkillUpdateRequest.toolIds` 临时加回初始化器：

```java
    private java.util.Set<Long> toolIds = new java.util.LinkedHashSet<>();
```

Run: `cd backend && mvn test -Dtest=PartialUpdateSemanticsTest`
Expected: `技能局部更新不清空已绑定的工具` **转红**（`toolIds` 变成空集合）。

确认转红后**删掉那个初始化器**，重跑确认恢复绿。

- [ ] **Step 4: 变异验证二 —— plan**

给 `TenantUpdateRequest.plan` 临时加回初始化器：

```java
    private com.qingyu.care.enums.TenantPlan plan = com.qingyu.care.enums.TenantPlan.FREE;
```

Run: `cd backend && mvn test -Dtest=PartialUpdateSemanticsTest`
Expected: `租户局部更新不把套餐打回FREE` **转红**（套餐变 FREE）。

确认后删掉，重跑恢复绿。

- [ ] **Step 5: 变异验证三 —— 密码空串**

把测试里那行判断临时改回只判 null：

```java
        if (req.getPassword() != null) {
```

Run: `cd backend && mvn test -Dtest=PartialUpdateSemanticsTest`
Expected: `编辑用户时传空串密码不改动密码哈希` **转红**（哈希变成 `HASHED_`）。

确认后改回 `!= null && !isEmpty()`，重跑恢复绿。

> 三条变异的**真实终端输出**都要贴进报告。这些测试守的都是「不做也照样跑得通、只在特定输入下毁数据」的约束，必须证明抓得住。

- [ ] **Step 6: 全量回归**

Run: `cd backend && mvn test`
Expected: 686 条全绿（681 基线 + 5 条新增）。

- [ ] **Step 7: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/controller/PartialUpdateSemanticsTest.java
git commit -m "test(api): 局部更新不得把没传的字段打回类默认值

五条测试 + 三次变异验证：
· 给 AiSkillUpdateRequest.toolIds 加回初始化器 → 技能那条转红
· 给 TenantUpdateRequest.plan 加回初始化器 → 租户那条转红
· 把密码判断改回只判 null → 密码那条转红

其中一条是反向对照（断言 new AiTool() 的字段确实是默认值而非 null），把缺陷
成因固定住——以后有人想把 DTO 换回实体收请求体时会先看到它。

断言一律检查具体原值而非「非 null」：后者在字段被打回默认值时同样成立，
是永真测试。"
```

---

## Task 6: 技术债回填与收尾

**Files:**
- Modify: `docs/技术债清单.md`
- Modify: `frontend/src/pages/UserListPage.tsx`（可选第二道防线）

- [ ] **Step 1: 前端不再提交空密码（第二道防线）**

`UserListPage.tsx` 的提交处，编辑分支把空密码剔掉：

```ts
            const { tenantId, ...rest } = formData;
            if (editingUser) {
                // 编辑时不提交空密码——后端虽已用 !isEmpty() 兜住，但前端本就无意修改密码，
                // 不该把这个字段发出去（注释见 handleOpenDialog 里的 password: ''）
                const { password, ...withoutPassword } = rest;
                const payload = password ? rest : withoutPassword;
                const response = await userService.update(editingUser.id, payload);
```

⚠️ 后端那道**不能因此去掉**——两道是互为兜底，且后端要防的是任何调用方而不只是这个页面。

- [ ] **Step 2: 前端回归**

Run: `cd frontend && pnpm test && pnpm lint && pnpm build`
Expected: 全绿，lint 零 error。

- [ ] **Step 3: 技术债清单标记已清理**

把第 11 项改成已清理形态（与清单里第 1/3/5 项同款：删除线标题 + ✅ 已清理 + `<details>` 折叠原问题）：

```markdown
11. ~~**`@Builder.Default` 字段在局部 PUT 时被静默重置成类默认值**~~ ✅ **已清理（2026-08-13）**
   八个 update 端点全部改用专用 `*UpdateRequest` DTO（`dto/` 下 7 个类，`PlatformController`
   的两个 update 复用工具与模型的 DTO）。DTO 一律不写字段初始化器、不加 `@Builder.Default`、
   不加 `@Builder`，`null` 才真正表示「请求体没给」。映射代码形状未变——判空此时才是对的。
   `UserController` 一并从 `Map + containsKey` 统一成 DTO。
   同批修掉一个活缺陷：编辑用户时前端提交空串密码 → 后端 `BCrypt.hashpw("")` → 用户被
   彻底锁死（旧密码登不上，空密码被 `@NotBlank` 拒）。改为 `!= null && !isEmpty()`，
   前端也不再提交空密码作为第二道。
   `PartialUpdateSemanticsTest` 五条 + 三次变异验证守住。详见
   `docs/superpowers/specs/2026-08-13-update-dto-partial-put-design.md`。
   <details><summary>原问题</summary>
   （把原来那整段描述搬进来，保留当时的判断依据）
   </details>
```

- [ ] **Step 4: 全量回归**

```bash
cd backend && mvn test
cd ../frontend && pnpm test && pnpm lint
cd ../widget && pnpm test
```
Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add docs/技术债清单.md frontend/src/pages/UserListPage.tsx
git commit -m "docs: 技术债第 11 项标记已清理 + 前端不再提交空密码

清单条目按仓库既有形态折叠保留原问题描述。前端编辑用户时剔掉空密码字段，
与后端的 !isEmpty() 互为兜底——后端那道不能去掉，它要防的是任何调用方。"
```

---

## 收尾

- [ ] **Step 1: 通读全分支 diff**

Run: `git diff master...HEAD --stat`
确认无误提交（`.env`、`target/`、`node_modules/`）。

- [ ] **Step 2: 点名脚本**

Run: `./scripts/manual-smoke-hints.sh master...HEAD`

- [ ] **Step 3: Docker 起来后补手工验证**

spec §5 那两条只有真机能验：
1. 建一个绑了工具的技能 → 点停用 → 查 `ai_skill_tool` 表，绑定还在
2. 把某租户改成 PRO → 冻结该租户 → 查 `sys_tenant.plan`，仍是 PRO

- [ ] **Step 4: 交给用户决定合并方式**

不自行合并。

---

## Self-Review

**1. Spec coverage**

| Spec 要求 | 对应 Task |
|-----------|-----------|
| §3.1 七个 DTO + 铁律 | Task 1 |
| §3.2 映射代码形状不变 | Task 2/3/4 |
| §3.3 DTO 不放 scope | Task 1（Step 3 有自查） |
| §3.4 凭据用 `!isEmpty()` | Task 2（authHeader/apiKey）、Task 4（password） |
| §3.5 UserController 改 DTO + 保住权限逻辑 | Task 4 |
| §3.6 八个控制器范围 | Task 2（4 个）+ Task 3（3 个）+ Task 4（1 个） |
| §5 单测 + 三条变异 | Task 5 |
| §5 手工验证 | 收尾 Step 3 |
| §6 技术债回填 | Task 6 |

无遗漏。

**2. Placeholder scan**：无 TBD/TODO、无「以实际为准」的推诿。枚举值已在写计划时核实并写死（`TenantStatus.FROZEN`——初稿写的 `SUSPENDED` 不存在，已订正）。

**3. Type consistency**：7 个 DTO 的字段名与类型均从实体逐字抄出（已核对 `AiTool`/`AiModelConfig`/`AiSkill`/`Application`/`ChatStrategy`/`Tenant`/`User` 的 `private` 声明）；控制器只换参数类型、变量名不变，故方法体内的 `xxx.getY()` 调用全部继续成立；`PlatformController` 复用工具与模型的 DTO 已核对字段集完全相同。

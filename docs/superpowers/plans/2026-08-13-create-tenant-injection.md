# create 端点跨租户写入收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让请求体里的 `tenantId` / `appId` 在 Jackson 绑定层就被丢弃，租户再也无法把行建进别人租户。

**Architecture:** 在 `BaseTenantEntity` 的两个归属字段上加 `@JsonProperty(access = READ_ONLY)`。反序列化时 Jackson 不认这两个字段，`TenantEntityListener.prePersist` 照常从 `TenantContext` 回填；序列化方向不变，响应契约零改动。一处改动覆盖全部五个绑实体的 create 端点与将来所有新增端点。

**Tech Stack:** Spring Boot 4.1 / Jackson 3 / Hibernate 6 / JUnit 5 + AssertJ / Testcontainers（pgvector）

## Global Constraints

- JDK 17。后端命令一律在 `backend/` 下跑。
- **Jackson 3 的包名是分裂的**：`ObjectMapper` 在 `tools.jackson.databind`，**注解仍在 `com.fasterxml.jackson.annotation`**。写 import 时别混。
- Jackson 3 的 `readValue` 抛的是**非受检**异常，测试方法**不需要** `throws`（`om.readValue` 直接写即可）。
- 单元测试（Surefire，`mvn test`）：纯 JUnit / Mockito，**不启 Spring 容器、不需要 Docker**。
- 集成测试（Failsafe，`mvn verify`）：类名以 `IT` 结尾，放 `it/` 包，继承 `support/AbstractPostgresIT`。**需要 Docker Desktop 运行**。
- ⚠️ 跑 `mvn verify` 前必须确认本机 `:8080` 没有在跑 dev 后端，且 `docker compose up -d` 已起（**IT 的 PG 走 Testcontainers，但 Redis 连的是本机 6379**；不起 Redis 会 124 个 IT 全挂在 `realtimeListenerContainer`，报错与代码毫无关系）。
- 全部使用简体中文：注释、测试方法名、commit 信息。
- **禁止改动 `TenantEntityListener`**（见 spec §2.3）。
- **禁止删除**既有的三处 `setTenantId(null)`（`AbTestService.create`、`ChatToolService.saveSharedTool`、`ModelRouterService.saveSharedModel`）。

---

### Task 1: 绑定层阻断 + 单测

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/BaseTenantEntity.java`
- Create: `backend/src/test/java/com/qingyu/care/entity/TenantOwnershipNotBindableTest.java`

**Interfaces:**
- Consumes: 无（本任务是起点）
- Produces: `BaseTenantEntity.tenantId` / `appId` 对 Jackson 变为只写出、不读入。Task 2 的 IT 依赖这个行为。

⚠️ **本任务的第一步同时就是 spec §3 要求的前提实证**：测试先写、先跑、**必须先红**。红了才证明「不加注解确实能注入」；加上注解转绿才证明「注解真的阻断了绑定」。这一红一绿就是完整的变异验证，不要跳过先跑那一步直接加注解——那样绿了也说明不了任何事。

- [ ] **Step 1: 写测试（此时必然失败）**

创建 `backend/src/test/java/com/qingyu/care/entity/TenantOwnershipNotBindableTest.java`：

```java
package com.qingyu.care.entity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户归属字段（tenantId / appId）不得从请求体绑定。
 *
 * <p>守的是一个真实的越权面：五个 create 端点把请求体直接绑到实体，而
 * {@code TenantEntityListener.prePersist} <b>只在字段为 null 时</b>才从 {@code TenantContext}
 * 回填。租户管理员 POST {@code {"tenantId": <他人租户id>}} 时该字段非 null，监听器不覆盖，
 * 行直接建进目标租户。
 *
 * <p>⚠️ 断言的是<b>行为</b>而不是「注解存在」。注解写法可能变（换成 mixin、换成
 * {@code @JsonIgnore}），但「请求体给了也绑不上」这条语义不能变。
 *
 * <p>⚠️ 这里同时用两个实体，是为了证明阻断来自<b>基类</b>而非某个子类的偶然配置——
 * 只测一个的话，把注解从基类挪到那一个子类上，测试照样绿。
 *
 * @author ruan
 */
class TenantOwnershipNotBindableTest {

    /** 与 Spring MVC 消息转换器同源（同 dto/RequestBodyDeserializationTest 的用法） */
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void 工具的租户归属字段不参与反序列化() {
        AiTool tool = om.readValue("""
                {"name":"query_order","description":"查订单",
                 "urlTemplate":"https://e.example.com/o?id={id}",
                 "tenantId":99,"appId":88}
                """, AiTool.class);

        // 普通字段照常绑定——证明这条测试确实解析成功了，不是整体失败导致的空值
        assertThat(tool.getName()).isEqualTo("query_order");
        assertThat(tool.getTenantId()).as("请求体里的 tenantId 必须被丢弃").isNull();
        assertThat(tool.getAppId()).as("请求体里的 appId 必须被丢弃").isNull();
    }

    @Test
    void 技能的租户归属字段不参与反序列化() {
        AiSkill skill = om.readValue("""
                {"name":"退货助手","content":"# 指引","tenantId":99,"appId":88}
                """, AiSkill.class);

        assertThat(skill.getName()).isEqualTo("退货助手");
        assertThat(skill.getTenantId()).isNull();
        assertThat(skill.getAppId()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试，确认它现在是红的**

Run: `mvn test -Dtest=TenantOwnershipNotBindableTest`

Expected: **FAIL**，两条都失败，报 `expected: null but was: 99`。

⚠️ 如果它意外地绿了，**停下来报告**——说明前提与 spec §1 的分析不符（可能实体上另有屏蔽），不要往下加注解。

- [ ] **Step 3: 加注解**

修改 `backend/src/main/java/com/qingyu/care/entity/BaseTenantEntity.java`。

加 import：

```java
import com.fasterxml.jackson.annotation.JsonProperty;
```

两个字段各加一行注解，并把注释补成解释「为什么」：

```java
    /**
     * 租户ID
     *
     * <p>⚠️ {@code READ_ONLY} 表示「只写出、不读入」：响应里照常返回，但<b>请求体给了也绑不上</b>。
     * 少了它，租户管理员 POST {@code {"tenantId": <他人租户id>}} 就能把行建进别人租户——
     * {@code TenantEntityListener.prePersist} 只在为 null 时才回填，非 null 时不覆盖。
     * 归属只能由服务端从 {@code TenantContext} 决定，绝不能由客户端指定。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "tenant_id")
    private Long tenantId;

    /**
     * 应用ID
     *
     * <p>⚠️ 同 {@link #tenantId}：只写出、不读入。应用归属由 {@code X-App-Id} 请求头决定。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "app_id")
    private Long appId;
```

⚠️ **只改这两个字段的注解与注释**，类上的 `@FilterDef` / `@Filter` / `@EntityListeners` 一律不动。

- [ ] **Step 4: 跑测试，确认转绿**

Run: `mvn test -Dtest=TenantOwnershipNotBindableTest`

Expected: **PASS**，2 条全绿。

- [ ] **Step 5: 跑全量单测，确认没打断别处**

Run: `mvn test`

Expected: BUILD SUCCESS，条数应为 **694 + 2 = 696**。

⚠️ 如果有既有测试转红，**停下来报告**，别改那条测试去迁就——很可能意味着某处确实依赖从请求体读归属字段，那是 spec §2.4 没覆盖到的情况。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/BaseTenantEntity.java \
        backend/src/test/java/com/qingyu/care/entity/TenantOwnershipNotBindableTest.java
git commit -m "fix(security): 租户归属字段不再从请求体绑定

BaseTenantEntity 是裸 @Data，tenantId/appId 有公开 setter 且无 Jackson 屏蔽，
而 prePersist 只在为 null 时回填——租户 POST {\"tenantId\":<他人>} 就能把
工具/模型/技能/策略建进别人租户。

加 @JsonProperty(READ_ONLY)：只写出、不读入。改一处覆盖全部五个绑实体的
create 端点与将来所有新增端点，比逐个端点补 setTenantId(null) 更难退化。

测试断言行为而非注解存在，且同时用两个实体，证明阻断来自基类而非子类偶然配置。"
```

---

### Task 2: 跨租户 create 的集成测试

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/CreateTenantInjectionIT.java`

**Interfaces:**
- Consumes: Task 1 的 `BaseTenantEntity` 绑定阻断行为
- Produces: 无（终点守护）

⚠️ **为什么单测之外还要 IT**：Task 1 的单测用的是**手工 new 的 `ObjectMapper`**，可能与 Spring 实际配置的那个不一致，断言会静默失去意义。IT 走真实 Jackson 配置 + 完整请求链路（`TenantInterceptor` 写上下文 → 控制器绑定 → `prePersist` 回填 → 落库）。这与 `SharedToolIT` 必须走真实 MockMvc 是同一个理由。

- [ ] **Step 1: 写 IT**

创建 `backend/src/test/java/com/qingyu/care/it/CreateTenantInjectionIT.java`：

```java
package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户不能通过请求体把资源建进别人租户。
 *
 * <p>⚠️ 必须走真实 MockMvc 而不是直调 service 或手工 {@code ObjectMapper}：
 * <ul>
 *   <li>手工 mapper 可能与 Spring 实际配置的那个不一致，断言会<b>静默失去意义</b>；
 *   <li>{@code TenantContext} 只在请求流经 {@code TenantInterceptor} 时才有值，
 *       而这条测试的核心正是「回填用的是上下文、不是请求体」。
 * </ul>
 *
 * <p>注：越权尝试<b>不报错</b>，行会正常建进发起方自己的租户（见 spec §5，
 * 静默丢弃而非显式拒绝）。所以断言看的是<b>落库归属</b>，不是响应状态码。
 *
 * @author ruan
 */
class CreateTenantInjectionIT extends AbstractPostgresIT {

    @Autowired
    private AiToolRepository toolRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    @Test
    void 租户在请求体里指定他人租户_行仍落进自己租户() throws Exception {
        Application appA = seedApp("INJECT_A", "http://localhost");
        Application appB = seedApp("INJECT_B", "http://localhost");

        userRepository.saveAndFlush(User.builder()
                .tenant(appA.getTenant())
                .username("inject_a_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());

        String tokenA = login("inject_a_admin", "pwd123456");
        Long victimTenantId = appB.getTenant().getId();

        MvcResult r = mockMvc.perform(post("/v1/ai-tools")
                        .header("satoken", tokenA)
                        .header("X-App-Id", appA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"injected_tool\",\"description\":\"越权尝试\","
                                + "\"urlTemplate\":\"http://evil.example.com/{q}\","
                                + "\"tenantId\":" + victimTenantId + ","
                                + "\"appId\":" + appB.getId() + "}"))
                .andExpect(status().isOk()).andReturn();

        long createdId = om.readTree(r.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        AiTool created = toolRepository.findById(createdId).orElseThrow();

        assertThat(created.getTenantId())
                .as("必须落进发起方租户 A，而不是请求体里指定的租户 B")
                .isEqualTo(appA.getTenant().getId());
        assertThat(created.getAppId())
                .as("appId 同样由 X-App-Id 头决定，不由请求体决定")
                .isEqualTo(appA.getId());
    }
}
```

⚠️ `toolRepository.findById(...)` 在测试线程里是安全的：Hibernate 6 的 `findById` **不走 `@Filter`**（本仓库正因如此才有 `TenantOwnershipGuard`），`SharedToolIT` 也是这么验落库的。

- [ ] **Step 2: 起基础设施（若未起）**

Run: `cd docker && docker compose up -d && docker compose ps`

Expected: `postgres` / `redis` / `rustfs` 三个都是 healthy。

⚠️ 漏了这步的症状是 **124 个 IT 全挂**在 `Failed to start bean 'realtimeListenerContainer'` / `Unable to connect to Redis`，看起来跟本次改动毫无关系。

- [ ] **Step 3: 跑这一条 IT**

Run: `mvn verify -Dit.test=CreateTenantInjectionIT`

（Failsafe 用 `it.test` 而不是 `test`。）

Expected: PASS，1 条绿。

- [ ] **Step 4: 变异验证——把注解删掉，确认 IT 转红**

先临时删掉 `BaseTenantEntity` 上那两行 `@JsonProperty(access = JsonProperty.Access.READ_ONLY)`。

⚠️ **删完先 `grep -n "JsonProperty" backend/src/main/java/com/qingyu/care/entity/BaseTenantEntity.java` 确认真的没了再跑测试**。「变异没落地」与「守护失效」在输出上完全无法区分，本仓库已在这上面栽过三次。

Run: `mvn verify -Dit.test=CreateTenantInjectionIT`

Expected: **FAIL**，报 `expected: <A的id> but was: <B的id>`。

然后 `git checkout -- backend/src/main/java/com/qingyu/care/entity/BaseTenantEntity.java` 还原，重跑确认转绿。

**报告里必须贴这一红一绿的真实终端输出。**

- [ ] **Step 5: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/CreateTenantInjectionIT.java
git commit -m "test(security): 跨租户 create 的真实请求链路守护

单测用的是手工 new 的 ObjectMapper，可能与 Spring 实际配置不一致、
断言会静默失去意义；IT 走真实 Jackson 配置 + 完整请求链路
（TenantInterceptor 写上下文 → 绑定 → prePersist 回填 → 落库）。

断言看落库归属而非响应码：越权尝试不报错，行正常建进发起方自己租户。"
```

---

### Task 3: 技术债清单回填与收官

**Files:**
- Modify: `docs/技术债清单.md`

**Interfaces:**
- Consumes: Task 1、Task 2 的全部产出
- Produces: 无（收尾）

- [ ] **Step 1: 把第 14 项标记为已清理**

按清单里第 1 / 3 / 5 / 11 项的**既有形态**改写第 14 项：标题加删除线 + ✅ + 清理日期，正文写清怎么修的，**原问题描述用 `<details>` 折叠保留**（不要删掉，也不要精简改写）。

新正文要点（据实写，不要抄下面的字数）：
- 修法是 `BaseTenantEntity` 的 `tenantId` / `appId` 加 `@JsonProperty(READ_ONLY)`，**一处覆盖全部五个绑实体的 create 端点与将来所有新增端点**；
- 为什么不做成「监听器总是用 `TenantContext` 覆盖」：`QaGenerationService` 在 `@Async` 里用 setter 从源文档传播租户，那样会把它写成 null，触发「向量 metadata 落空串、对任何租户都检索不到」那个已记过两次的事故；注解只影响 JSON 绑定、不影响 setter；
- 语义是**静默丢弃**：越权尝试不报错，行落进发起方自己的租户；
- 守护两层：单测（两个实体，证明阻断来自基类）+ IT（真实请求链路），都做过变异验证；
- 既有的三处显式 `setTenantId(null)` 保留未动。

同时更新文件开头那行 **「盘点记录」**，追加「2026-08-13 清理第 14 项」。

- [ ] **Step 2: 全量验证**

依次跑，全部必须绿：

```bash
cd backend && mvn verify
```

Expected: 单测 **696**、IT **131**，BUILD SUCCESS。

```bash
cd frontend && pnpm test && pnpm lint
```

Expected: 23 条绿，lint **零 error**。

（本次不改前端与 widget，但仍跑一遍确认没被波及。）

- [ ] **Step 3: 跑手工验证点名脚本**

Run: `./scripts/manual-smoke-hints.sh master...HEAD`

把它列出的章节标题**原样抄进报告**（脚本刻意只存标题不复述内容）。不要自行判断哪些可以跳过——由协调者定夺。

- [ ] **Step 4: 提交**

```bash
git add docs/技术债清单.md
git commit -m "docs: 技术债第 14 项已清理——归属字段绑定层阻断

修法与残留说明按第 1/3/5/11 项的既有形态回填，原问题描述折叠保留。"
```

---

## Self-Review

**Spec 覆盖核对：**

| spec 章节 | 落在哪 |
|---|---|
| §2 方案（两个注解） | Task 1 Step 3 |
| §2.1 为什么改一处就够 | Task 1 Step 6 commit body + Task 3 Step 1 |
| §2.2 不动的三处 | Global Constraints 明令禁止删除 |
| §2.3 硬约束（不碰监听器） | Global Constraints 明令禁止 + Task 3 Step 1 写进清单 |
| §2.4 与平台管理员指定租户不冲突 | Task 1 Step 5 全量单测兜底（若有冲突会转红并要求停下报告） |
| §3 必须先实证的前提 | Task 1 Step 1-2（测试先红），意外绿则停下报告 |
| §4 单测 | Task 1 Step 1 |
| §4 集成测试 | Task 2 Step 1 |
| §4 变异验证 | Task 1 Step 2（天然红→绿）+ Task 2 Step 4（显式删注解） |
| §5 静默丢弃 | Task 2 IT 类注释 + Task 3 Step 1 |
| §7 交付物 | Task 1 / 2 / 3 全覆盖 |

**类型一致性：** 全程只用 `BaseTenantEntity.getTenantId()` / `getAppId()`（`Long`）、`seedApp(String, String)` → `Application`、`login(String, String)` → `String`，与 `SharedToolIT` 及 `AbstractPostgresIT` 现有签名一致。

**占位符扫描：** 无 TBD / TODO / 「类似 Task N」/ 无代码的代码步骤。

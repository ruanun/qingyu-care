# 平台共享工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 平台管理员维护一个共享 HTTP 工具库，租户建 skill 时可勾选使用，且无法读取平台凭据；同批修掉「租户管理员能读到平台共享模型明文 apiKey」这个既有漏洞。

**Architecture:** 给 `AiTool` 加 `OwnerScope scope`（照抄 `AiModelConfig`），共享行 `tenant_id IS NULL` 靠 native SQL 查询绕开 Hibernate `@Filter`；运行时解析 = 自有（filter-bound）∪ 平台共享（native）；管理面出口经 `SharedCredentialMasker` 脱敏，脱敏前必须 `em.detach`（OSIV 默认开启，实体一直受管）。

**Tech Stack:** Spring Boot 4.1 · Spring Data JPA · PostgreSQL · React 19 + Vite 7

## Global Constraints

- 全部简体中文：代码注释、commit 信息、UI 文案。新增文件的作者标 `@author ruan`。
- 分支 `feature/platform-shared-tools`（已建，spec 已提交）。不 `git push`。
- **两条 native 共享查询必须钉 `scope = 'PLATFORM'`**。改成 native 后若只写 `WHERE id IN (...)`，就变成任意 id 可绑 = 跨租户读他人工具及其鉴权头。这是本计划后果最严重的一处。
- **脱敏只作用于面向租户的 list（`listTools` / `getAllModels`）**，绝不碰运行时解析路径——`HttpToolCallback` 要用真凭据发出站请求，脱敏误伤会让共享工具静默失效。
- **脱敏前必须 `em.detach(entity)`**。本项目未配置 `spring.jpa.open-in-view`，即 Spring Boot 默认 `true`，OSIV 让实体在整个请求期间保持受管，直接 setter 置空会被脏检查刷回库、真把凭据删掉。
- 平台端点鉴权是**每个方法开头显式调 `SecurityUtil.requirePlatformAdmin()`**，不是类级注解，新增端点逐个加。
- 新增测试优先写成 Surefire 单测（纯 Mockito，免 Docker）；只有「必须走真实 HTTP 请求」的才写 IT。
- ⚠️ 跑 `mvn verify` 前先停掉本机在跑的 dev 后端（`:8080`），否则 `WebhookDeliveryIT` 必红（共用本机 Redis 消费组）。

---

## File Structure

**新增**

| 文件 | 职责 |
|------|------|
| `backend/src/main/java/com/qingyu/care/config/SharedCredentialMasker.java` | 共享行凭据脱敏，唯一职责；持有 `EntityManager` 做 detach |
| `backend/src/main/resources/migration/2026-08-12-backfill-ai-tool-scope.sql` | 存量 `ai_tool` 行回填 `scope='TENANT'` |
| `backend/src/test/java/com/qingyu/care/config/SharedCredentialMaskerTest.java` | 脱敏助手单测 |
| `backend/src/test/java/com/qingyu/care/service/ChatToolServiceSharedTest.java` | 共享工具解析与列表单测 |
| `backend/src/test/java/com/qingyu/care/it/SharedToolIT.java` | 跨租户越权 + OSIV 脏检查，只有真实请求能测 |
| `frontend/src/pages/SharedToolPage.tsx` | 平台管理员维护共享工具 |
| `frontend/src/pages/SharedToolPage.test.tsx` | 写操作回归（按「改到哪补到哪」的常态约定） |

**修改**

| 文件 | 改动 |
|------|------|
| `entity/AiTool.java` | 加 `scope` 字段 |
| `repository/AiToolRepository.java` | 两条 native 共享查询 |
| `service/ChatToolService.java` | `resolve()` 求并集、`listTools()` 合并+脱敏、平台侧三方法 |
| `service/ModelRouterService.java` | `getAllModels()` 接入脱敏 |
| `controller/PlatformController.java` | 共享工具 CRUD 三端点 |
| `frontend/src/App.tsx` / `layouts/MainLayout.tsx` | 路由与菜单 |
| `frontend/src/api/platform.ts` | 共享工具 service 方法 |
| `frontend/src/pages/SkillConfigPage.tsx` / `ToolConfigPage.tsx` | 平台共享项标注与只读 |
| `SaaS平台规划.md` / `CLAUDE.md` | 状态回填与架构要点 |

---

## Task 1: `AiTool.scope` 字段与迁移脚本

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/AiTool.java`
- Create: `backend/src/main/resources/migration/2026-08-12-backfill-ai-tool-scope.sql`

**Interfaces:**
- Consumes: `com.qingyu.care.enums.OwnerScope`（既有枚举，值 `PLATFORM` / `TENANT`）
- Produces: `AiTool.getScope()` / `setScope(OwnerScope)`；builder 支持 `.scope(OwnerScope.PLATFORM)`

- [ ] **Step 1: 加字段**

在 `AiTool.java` 的 `enabled` 字段之后、`createdAt` 之前插入（与 `AiModelConfig` 的写法逐字一致）：

```java
    /**
     * 归属范围：TENANT=租户自建；PLATFORM=平台共享（tenant_id/app_id 均为 null，所有租户可绑定使用）。
     * 平台共享行的凭据对租户不可见——见 SharedCredentialMasker。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OwnerScope scope = OwnerScope.TENANT;
```

补 import：

```java
import com.qingyu.care.enums.OwnerScope;
```

- [ ] **Step 2: 写迁移脚本**

创建 `backend/src/main/resources/migration/2026-08-12-backfill-ai-tool-scope.sql`：

```sql
-- ============================================================
-- 存量数据修复：ai_tool 新增 scope 列后回填历史行
--
-- 背景：本次给 AiTool 加了 scope 字段（TENANT / PLATFORM），列声明为 NOT NULL。
-- ddl-auto: update 会把列加上，但已存在的行没有值——PostgreSQL 对「加非空列且无
-- default」的既有行处理取决于具体路径，不能靠运气。所有历史工具都是租户自建的，
-- 一律回填 TENANT。
--
-- 幂等：只更新 scope IS NULL 的行，重复执行第二次匹配 0 行。
--
-- 一次性手动执行（项目未接入 Flyway/Liquibase）：
--   docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
--     < backend/src/main/resources/migration/2026-08-12-backfill-ai-tool-scope.sql
-- ============================================================

UPDATE ai_tool SET scope = 'TENANT' WHERE scope IS NULL;
```

- [ ] **Step 3: 编译**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 起一次后端让 ddl-auto 加列，再跑迁移**

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 等启动完成后 Ctrl+C
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
  < src/main/resources/migration/2026-08-12-backfill-ai-tool-scope.sql
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care -c \
  "SELECT id, name, scope, tenant_id FROM ai_tool;"
```
Expected: `scope` 列存在且所有行为 `TENANT`，无 NULL。

- [ ] **Step 5: 单测回归**

Run: `cd backend && mvn test`
Expected: 全绿（667 条基线）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/AiTool.java \
        backend/src/main/resources/migration/2026-08-12-backfill-ai-tool-scope.sql
git commit -m "feat(tool): AiTool 加 scope 字段，为平台共享做准备

照抄 AiModelConfig 的 OwnerScope 范式。新列 NOT NULL 且存量行无值，配幂等
迁移脚本回填 TENANT——历史工具都是租户自建的。"
```

---

## Task 2: 仓库层两条 native 共享查询

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/AiToolRepository.java`

**Interfaces:**
- Consumes: Task 1 的 `AiTool.scope`
- Produces: `AiToolRepository.findAllSharedTools() → List<AiTool>`、`AiToolRepository.findSharedByIdInAndEnabledTrue(Collection<Long> ids) → List<AiTool>`

- [ ] **Step 1: 加两条查询**

把 `AiToolRepository.java` 整体替换为：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.AiTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AiToolRepository extends JpaRepository<AiTool, Long> {

    /** 按 id 集合取启用工具（skill 激活时用）。受 @Filter 保护，只看得到当前租户/应用自有的行。 */
    List<AiTool> findByIdInAndEnabledTrue(Collection<Long> ids);

    /** 显式按 appId 过滤（管理面 list 用，防平台管理员裸 findAll 跨应用读取） */
    List<AiTool> findByAppId(Long appId);

    /**
     * 全部平台共享工具（平台管理面 + 租户列表合并用）。
     *
     * <p>必须用 native SQL：平台共享行 tenant_id IS NULL，Hibernate @Filter 根本看不见它们
     * （同「多级配置回退」那个坑，见 CLAUDE.md）。
     */
    @Query(value = "SELECT * FROM ai_tool WHERE scope = 'PLATFORM' ORDER BY name", nativeQuery = true)
    List<AiTool> findAllSharedTools();

    /**
     * 按 id 集合取启用的平台共享工具（运行时解析用）。
     *
     * <p>⚠️ <b>WHERE 里的 {@code scope = 'PLATFORM'} 不能去掉。</b>上面的
     * {@code findByIdInAndEnabledTrue} 是 filter-bound 的，别的租户的 id 天然查不出来；
     * 这条是 native 查询、不受 @Filter 保护，少了 scope 条件就变成「任意 id 都能查出来」——
     * 租户只要在自己的 skill 里填上别人的工具 id，就能调用他人工具（连带用掉他人的鉴权头）。
     */
    @Query(value = "SELECT * FROM ai_tool WHERE scope = 'PLATFORM' AND enabled = true AND id IN (:ids)",
           nativeQuery = true)
    List<AiTool> findSharedByIdInAndEnabledTrue(@Param("ids") Collection<Long> ids);
}
```

- [ ] **Step 2: 编译**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/repository/AiToolRepository.java
git commit -m "feat(tool): 加两条 native 共享工具查询

平台共享行 tenant_id IS NULL，Hibernate @Filter 看不见，必须 native。
两条都钉死 scope='PLATFORM'——native 不受 @Filter 保护，少了这个条件就是
任意 id 可查 = 跨租户读他人工具及其鉴权头。"
```

---

## Task 3: 凭据脱敏助手

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/SharedCredentialMasker.java`
- Test: `backend/src/test/java/com/qingyu/care/config/SharedCredentialMaskerTest.java`

**Interfaces:**
- Consumes: `TenantOwnershipGuard.mutableInCurrentContext(Long, Long) → boolean`（既有静态方法）
- Produces: `SharedCredentialMasker.mask(List<T>, Function<T,Long> tenantIdGetter, Function<T,Long> appIdGetter, Consumer<T> credentialCleaner) → List<T>`（返回入参那个 list 本身，元素已就地脱敏）

- [ ] **Step 1: 写失败的测试**

创建 `backend/src/test/java/com/qingyu/care/config/SharedCredentialMaskerTest.java`：

```java
package com.qingyu.care.config;

import com.qingyu.care.entity.AiTool;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 共享行凭据脱敏助手单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class SharedCredentialMaskerTest {

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private SharedCredentialMasker masker;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AiTool tool(Long tenantId, Long appId, String authHeader) {
        AiTool t = AiTool.builder().name("t").description("d").urlTemplate("http://x").build();
        t.setTenantId(tenantId);
        t.setAppId(appId);
        t.setAuthHeader(authHeader);
        return t;
    }

    @Test
    void 租户上下文读平台共享行时凭据被清空() {
        TenantContext.setTenantId(7L);
        AiTool shared = tool(null, null, "Bearer platform-secret");

        masker.mask(List.of(shared), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        assertThat(shared.getAuthHeader()).isNull();
    }

    @Test
    void 租户上下文读自有行时凭据保留() {
        TenantContext.setTenantId(7L);
        AiTool own = tool(7L, null, "Bearer my-secret");

        masker.mask(List.of(own), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        assertThat(own.getAuthHeader()).isEqualTo("Bearer my-secret");
    }

    @Test
    void 平台上下文读共享行时凭据保留() {
        // 平台管理员登录后 TenantContext.tenantId 恒为 null
        AiTool shared = tool(null, null, "Bearer platform-secret");

        masker.mask(List.of(shared), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        assertThat(shared.getAuthHeader()).isEqualTo("Bearer platform-secret");
    }

    @Test
    void 脱敏前必须先detach否则脏检查会把null刷回库() {
        TenantContext.setTenantId(7L);
        AiTool shared = tool(null, null, "Bearer platform-secret");

        masker.mask(List.of(shared), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        verify(entityManager).detach(shared);
    }

    @Test
    void 不脱敏的行不应被detach() {
        TenantContext.setTenantId(7L);
        AiTool own = tool(7L, null, "Bearer my-secret");

        masker.mask(List.of(own), AiTool::getTenantId, AiTool::getAppId, t -> t.setAuthHeader(null));

        verify(entityManager, never()).detach(own);
    }
}
```

> ⚠️ 上面 `ReflectionTestUtils` 的 import 若 IDE 报未使用，删掉即可——`@InjectMocks` 会把 `@Mock EntityManager` 注进字段，不需要反射赋值。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=SharedCredentialMaskerTest`
Expected: 编译失败，`SharedCredentialMasker` 不存在。

- [ ] **Step 3: 写实现**

创建 `backend/src/main/java/com/qingyu/care/config/SharedCredentialMasker.java`：

```java
package com.qingyu.care.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 共享行凭据脱敏：非平台上下文读到平台共享行（{@code tenant_id IS NULL}）时，把凭据字段置空。
 *
 * <p><b>判据复用 {@link TenantOwnershipGuard#mutableInCurrentContext}</b>——「能修改它」与
 * 「能看它的凭据」是同一个权限边界，不新造概念。该守卫对平台上下文（{@code tenantId} 为空）
 * 放行，对租户上下文读平台共享行返回 false，正是需要的语义。
 *
 * <p><b>为什么是 {@code @Component} 而不是像 {@link TenantOwnershipGuard} 那样的静态工具类</b>：
 * 脱敏前必须 {@code detach}，需要注入 {@link EntityManager}。这是被下面那条约束逼出来的，
 * 不是随意偏离既有风格。
 *
 * <p>⚠️ <b>必须先 detach 再置空。</b>本项目未显式配置 {@code spring.jpa.open-in-view}，
 * 即 Spring Boot 默认的 {@code true}：OSIV 让 EntityManager 在整个请求期间保持打开，
 * list 查询返回的实体一直是<b>受管</b>状态。直接对受管实体 setter 置空会被 Hibernate 脏检查
 * 当成真实修改，后续同一请求内任何事务提交都可能把 {@code null} 刷回库——本意是防止凭据
 * 泄漏，结果是把凭据<b>真的删掉</b>。detach 后该对象脱离持久化上下文，置空只影响即将序列化
 * 给前端的这份数据。
 *
 * <p>⚠️ <b>只能用在面向租户的管理面 list 出口</b>（{@code ChatToolService.listTools} /
 * {@code ModelRouterService.getAllModels}）。<b>绝不能用在运行时解析路径</b>——
 * {@code HttpToolCallback} 要拿真凭据发出站请求，脱敏误伤会让共享工具静默失效：第三方返 401，
 * 模型拿到一段错误响应继续编答案，全程无异常无日志。
 *
 * @author ruan
 */
@Component
public class SharedCredentialMasker {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 就地脱敏列表中当前上下文无权查看凭据的元素。
     *
     * @param list             待处理列表（就地修改并原样返回）
     * @param tenantIdGetter   取元素的 tenant_id
     * @param appIdGetter      取元素的 app_id
     * @param credentialCleaner 清空该元素凭据字段的动作（如 {@code t -> t.setAuthHeader(null)}）
     * @return 入参 list 本身
     */
    public <T> List<T> mask(List<T> list,
                            Function<T, Long> tenantIdGetter,
                            Function<T, Long> appIdGetter,
                            Consumer<T> credentialCleaner) {
        for (T item : list) {
            if (TenantOwnershipGuard.mutableInCurrentContext(
                    tenantIdGetter.apply(item), appIdGetter.apply(item))) {
                continue;   // 有权查看，原样保留
            }
            entityManager.detach(item);      // 先脱离持久化上下文，见类注释
            credentialCleaner.accept(item);
        }
        return list;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=SharedCredentialMaskerTest`
Expected: 5 条全绿。

- [ ] **Step 5: 变异验证 detach 那条测试真的有判别力**

临时把实现里的 `entityManager.detach(item);` 注释掉，重跑：

Run: `cd backend && mvn test -Dtest=SharedCredentialMaskerTest`
Expected: `脱敏前必须先detach否则脏检查会把null刷回库` **转红**（`Wanted but not invoked: entityManager.detach()`）。

确认转红后**把那行加回来**，再跑一次确认恢复全绿。

> 这一步不能省：这条测试守的是一个「不做也照样跑得通、只在特定时序下毁数据」的约束，必须证明它抓得住。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/config/SharedCredentialMasker.java \
        backend/src/test/java/com/qingyu/care/config/SharedCredentialMaskerTest.java
git commit -m "feat(security): 共享行凭据脱敏助手

判据复用 TenantOwnershipGuard.mutableInCurrentContext——「能改它」与「能看它的
凭据」是同一个权限边界。

⚠️ 脱敏前必须 em.detach：本项目 OSIV 默认开启，list 查出来的实体在整个请求期间
保持受管，直接 setter 置空会被脏检查刷回库、真把凭据删掉。已用变异验证钉住这条
（注释掉 detach 那行，对应测试转红）。"
```

---

## Task 4: 运行时解析支持平台共享工具

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatToolService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ChatToolServiceSharedTest.java`

**Interfaces:**
- Consumes: Task 2 的 `findSharedByIdInAndEnabledTrue(Collection<Long>)`
- Produces: `ChatToolService.resolve() → ToolBundle`（签名不变，行为扩展）

- [ ] **Step 1: 写失败的测试**

创建 `backend/src/test/java/com/qingyu/care/service/ChatToolServiceSharedTest.java`：

```java
package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.service.tool.HttpToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台共享工具的运行时解析。
 *
 * <p>核心守护：租户的 skill 里填上别的租户的工具 id 时，解析不出任何东西——
 * 两条查询（filter-bound 的自有 + native 的平台共享）都覆盖不到它。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatToolServiceSharedTest {

    @Mock
    private AiToolRepository toolRepository;
    @Mock
    private AiSkillRepository skillRepository;
    @Mock
    private HttpToolExecutor httpToolExecutor;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private SharedCredentialMaskerHolder unusedHolder;   // 占位，见下方说明

    @InjectMocks
    private ChatToolService chatToolService;

    /** 占位类型：若 ChatToolService 尚未持有该依赖，删掉上面的 @Mock 字段即可。 */
    interface SharedCredentialMaskerHolder {
    }

    private AiSkill skillBinding(Long... toolIds) {
        return AiSkill.builder()
                .name("s").content("# SOP")
                .toolIds(new java.util.LinkedHashSet<>(List.of(toolIds)))
                .enabled(true).build();
    }

    private AiTool tool(Long id, String name) {
        AiTool t = AiTool.builder().name(name).description("d")
                .urlTemplate("http://example.com/{q}").build();
        t.setId(id);
        return t;
    }

    @Test
    void 能解析到平台共享工具() {
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skillBinding(9L)));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of());
        when(toolRepository.findSharedByIdInAndEnabledTrue(anyCollection()))
                .thenReturn(List.of(tool(9L, "shared_weather")));

        ChatToolService.ToolBundle bundle = chatToolService.resolve();

        assertThat(bundle.toolCallbacks()).hasSize(1);
    }

    @Test
    void 解析不到其他租户的工具() {
        // 租户在自己的 skill 里填了别人的工具 id 99：
        // 自有查询（filter-bound）看不到它，共享查询（钉了 scope=PLATFORM）也看不到它
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skillBinding(99L)));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of());
        when(toolRepository.findSharedByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of());

        ChatToolService.ToolBundle bundle = chatToolService.resolve();

        assertThat(bundle.toolCallbacks()).isEmpty();
    }

    @Test
    void 自有工具全部命中时不再发起共享查询() {
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skillBinding(1L)));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection()))
                .thenReturn(List.of(tool(1L, "own_tool")));

        ChatToolService.ToolBundle bundle = chatToolService.resolve();

        assertThat(bundle.toolCallbacks()).hasSize(1);
        verify(toolRepository, never()).findSharedByIdInAndEnabledTrue(anyCollection());
    }

    @Test
    void 自有与共享混绑时两者都在() {
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skillBinding(1L, 9L)));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection()))
                .thenReturn(List.of(tool(1L, "own_tool")));
        when(toolRepository.findSharedByIdInAndEnabledTrue(Set.of(9L)))
                .thenReturn(List.of(tool(9L, "shared_weather")));

        ChatToolService.ToolBundle bundle = chatToolService.resolve();

        assertThat(bundle.toolCallbacks()).hasSize(2);
    }
}
```

> ⚠️ 上面的 `SharedCredentialMaskerHolder` 占位接口与对应 `@Mock` 字段是给 Task 5 用的：Task 5 会让 `ChatToolService` 持有 `SharedCredentialMasker`。**本任务实现时先删掉这两行**（`@Mock private SharedCredentialMaskerHolder unusedHolder;` 与 `interface SharedCredentialMaskerHolder {}`），Task 5 再把真实依赖加回来。`any` 与 `verify` 的 import 若有未使用的按编译提示清理。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=ChatToolServiceSharedTest`
Expected: 编译失败或 `能解析到平台共享工具` 红——`findSharedByIdInAndEnabledTrue` 未被调用。

- [ ] **Step 3: 改 resolve()**

在 `ChatToolService.java` 中，把 `resolve()` 里这一段：

```java
        List<ToolCallback> callbacks = new ArrayList<>();
        if (!toolIds.isEmpty()) {
            for (AiTool t : toolRepository.findByIdInAndEnabledTrue(toolIds)) {
                callbacks.add(new HttpToolCallback(t, httpToolExecutor, objectMapper));
            }
        }
        return new ToolBundle(markdowns, callbacks);
```

替换为：

```java
        List<ToolCallback> callbacks = new ArrayList<>();
        if (!toolIds.isEmpty()) {
            for (AiTool t : resolveTools(toolIds)) {
                callbacks.add(new HttpToolCallback(t, httpToolExecutor, objectMapper));
            }
        }
        return new ToolBundle(markdowns, callbacks);
    }

    /**
     * 按 id 集合解析工具：当前应用自有 ∪ 平台共享。
     *
     * <p>先查自有（filter-bound，天然只看得到本租户本应用的行），剩下没命中的 id 再去查平台
     * 共享（native，钉死 {@code scope='PLATFORM'}）。同一个 id 不可能既是自有又是共享，
     * 所以顺序只影响查询次数不影响结果；自有全部命中时省掉第二条查询——绝大多数租户没绑
     * 共享工具，不该为此多打一次库。
     *
     * <p><b>安全</b>：别的租户的工具 id 在两条查询里都覆盖不到——自有查询有 @Filter 挡着，
     * 共享查询有 {@code scope='PLATFORM'} 挡着。少任何一道都会变成跨租户越权调用。
     *
     * <p><b>这里拿到的必须是未脱敏的原始实体</b>：{@link HttpToolCallback} 要用 authHeader
     * 真的发出站请求。绝不能在这条路径上套 {@link SharedCredentialMasker}。
     */
    private List<AiTool> resolveTools(Set<Long> toolIds) {
        List<AiTool> tools = new ArrayList<>(toolRepository.findByIdInAndEnabledTrue(toolIds));
        Set<Long> found = new LinkedHashSet<>();
        for (AiTool t : tools) {
            found.add(t.getId());
        }
        Set<Long> rest = new LinkedHashSet<>(toolIds);
        rest.removeAll(found);
        if (!rest.isEmpty()) {
            tools.addAll(toolRepository.findSharedByIdInAndEnabledTrue(rest));
        }
        return tools;
```

（注意上面替换块自带了 `resolve()` 的收尾 `}` 与新方法的开头，最终 `resolveTools` 的收尾 `}` 沿用原本 `resolve()` 的那个。编译不过时按 IDE 提示补花括号。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=ChatToolServiceSharedTest`
Expected: 4 条全绿。

- [ ] **Step 5: 全量单测回归**

Run: `cd backend && mvn test`
Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatToolService.java \
        backend/src/test/java/com/qingyu/care/service/ChatToolServiceSharedTest.java
git commit -m "feat(tool): 运行时解析支持平台共享工具

自有（filter-bound）∪ 平台共享（native），自有全部命中时省掉第二条查询。

安全不变式已有测试守护：别的租户的工具 id 在两条查询里都覆盖不到——自有查询有
@Filter 挡着，共享查询有 scope='PLATFORM' 挡着。

这条路径刻意不脱敏：HttpToolCallback 要用真 authHeader 发出站请求。"
```

---

## Task 5: 管理面列表合并与脱敏

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatToolService.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/ModelRouterService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ChatToolServiceSharedTest.java`（追加）

**Interfaces:**
- Consumes: Task 3 的 `SharedCredentialMasker.mask(...)`、Task 2 的 `findAllSharedTools()`
- Produces: `ChatToolService.listTools()`（行为扩展）、`ChatToolService.listSharedTools() → List<AiTool>`、`ChatToolService.saveSharedTool(AiTool) → AiTool`、`ChatToolService.deleteSharedTool(Long)`

- [ ] **Step 1: 追加失败的测试**

在 `ChatToolServiceSharedTest.java` 里追加（并把 Task 1 里那个占位 `SharedCredentialMaskerHolder` 换成真实依赖）：

```java
    @Mock
    private SharedCredentialMasker credentialMasker;
```

以及两条测试：

```java
    @Test
    void 管理面列表把自有与平台共享合并() {
        TenantContext.setAppId(3L);
        try {
            when(toolRepository.findByAppId(3L)).thenReturn(List.of(tool(1L, "own")));
            when(toolRepository.findAllSharedTools()).thenReturn(List.of(tool(9L, "shared")));
            when(credentialMasker.mask(any(), any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            List<AiTool> list = chatToolService.listTools();

            assertThat(list).extracting(AiTool::getId).containsExactly(1L, 9L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 管理面列表经过脱敏助手() {
        TenantContext.setAppId(3L);
        try {
            when(toolRepository.findByAppId(3L)).thenReturn(List.of());
            when(toolRepository.findAllSharedTools()).thenReturn(List.of(tool(9L, "shared")));
            when(credentialMasker.mask(any(), any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            chatToolService.listTools();

            verify(credentialMasker).mask(any(), any(), any(), any());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void 保存共享工具强制平台归属() {
        AiTool t = tool(null, "shared_new");
        t.setTenantId(7L);          // 请求体里带了租户归属，必须被覆盖
        t.setAppId(3L);
        when(toolRepository.save(any(AiTool.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTool saved = chatToolService.saveSharedTool(t);

        assertThat(saved.getScope()).isEqualTo(OwnerScope.PLATFORM);
        assertThat(saved.getTenantId()).isNull();
        assertThat(saved.getAppId()).isNull();
    }
```

补 import：

```java
import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.enums.OwnerScope;
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=ChatToolServiceSharedTest`
Expected: 新增三条红（`listTools` 未合并共享、`saveSharedTool` 不存在）。

- [ ] **Step 3: 改 ChatToolService**

加字段：

```java
    private final SharedCredentialMasker credentialMasker;
```

把 `listTools()` 替换为：

```java
    /**
     * 按当前应用收窄的工具列表 ∪ 平台共享工具。
     *
     * <p>TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，裸 findAll 会让平台管理员
     * 跨应用读全平台工具；显式按 appId 收窄，无 appId（未选应用）时自有部分为空，但平台共享
     * 工具仍须可见——租户要能在 skill 里勾选它们。
     *
     * <p>返回前经 {@link SharedCredentialMasker} 脱敏：平台共享工具的 authHeader 是平台的
     * 凭据，租户可以<b>使用</b>但不能<b>看到</b>。
     */
    public List<AiTool> listTools() {
        Long appId = TenantContext.getAppId();
        List<AiTool> own = appId == null ? List.of() : toolRepository.findByAppId(appId);
        List<AiTool> shared = toolRepository.findAllSharedTools();
        List<AiTool> merged = mergeById(own, shared);
        return credentialMasker.mask(merged, AiTool::getTenantId, AiTool::getAppId,
                t -> t.setAuthHeader(null));
    }

    /** 自有优先、按 id 去重合并（范式同 ModelRouterService.mergeOwnAndSharedById）。 */
    private List<AiTool> mergeById(List<AiTool> own, List<AiTool> shared) {
        java.util.LinkedHashMap<Long, AiTool> byId = new java.util.LinkedHashMap<>();
        for (AiTool t : own) byId.putIfAbsent(t.getId(), t);
        for (AiTool t : shared) byId.putIfAbsent(t.getId(), t);
        return new ArrayList<>(byId.values());
    }
```

在 `deleteTool` 之后追加平台侧三个方法：

```java
    // ===== 平台共享工具（仅平台管理员，端点侧已 requirePlatformAdmin） =====

    /** 列出全部平台共享工具。不脱敏——本方法只被平台管理员调用，维护时必须能看到并回填凭据。 */
    public List<AiTool> listSharedTools() {
        return toolRepository.findAllSharedTools();
    }

    /** 保存平台共享工具（强制平台归属，避免被 TenantEntityListener 回填租户，也不信任请求体）。 */
    @Transactional
    public AiTool saveSharedTool(AiTool t) {
        t.setScope(OwnerScope.PLATFORM);
        t.setTenantId(null);
        t.setAppId(null);
        return toolRepository.save(t);
    }

    /** 删除平台共享工具。只允许删 PLATFORM 行，避免拿租户私有工具的 id 越权删除。 */
    @Transactional
    public void deleteSharedTool(Long id) {
        AiTool t = toolRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("共享工具", id.toString()));
        if (t.getScope() != OwnerScope.PLATFORM) {
            throw new ResourceNotFoundException("共享工具", id.toString());
        }
        toolRepository.delete(t);
    }
```

补 import：

```java
import com.qingyu.care.config.SharedCredentialMasker;
import com.qingyu.care.enums.OwnerScope;
```

- [ ] **Step 4: 改 ModelRouterService 的 getAllModels**

加字段：

```java
    private final SharedCredentialMasker credentialMasker;
```

把 `getAllModels()` 的 `return` 一行改为：

```java
        List<AiModelConfig> merged = mergeOwnAndSharedById(own, shared);
        // 平台共享模型的 apiKey 是平台凭据，租户可用不可见。
        // 这条此前缺失，导致租户管理员调 GET /v1/ai-models 能读到平台的明文 key。
        return credentialMasker.mask(merged, AiModelConfig::getTenantId, AiModelConfig::getAppId,
                m -> m.setApiKey(null));
```

`getSharedModels()` **不动**——它只被 `PlatformController` 调用，平台管理员需要看到凭据。

补 import：

```java
import com.qingyu.care.config.SharedCredentialMasker;
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=ChatToolServiceSharedTest`
Expected: 7 条全绿。

- [ ] **Step 6: 全量单测回归**

Run: `cd backend && mvn test`
Expected: 全绿。若 `ModelRouterService` 的既有单测因新增构造参数编译失败，给它们的 `@Mock` 列表补上 `SharedCredentialMasker` 并对 `mask(...)` 打桩 `thenAnswer(inv -> inv.getArgument(0))`。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatToolService.java \
        backend/src/main/java/com/qingyu/care/service/ModelRouterService.java \
        backend/src/test/java/com/qingyu/care/service/ChatToolServiceSharedTest.java
git commit -m "feat(tool): 管理面列表合并平台共享项并脱敏凭据

listTools 改为「自有 ∪ 平台共享」，返回前清掉共享项的 authHeader——租户可以
使用平台工具但不能看到平台的凭据。

同批修既有漏洞：getAllModels 此前没脱敏，租户管理员调 GET /v1/ai-models 能读到
平台共享模型的明文 apiKey（实测拿到平台的 DashScope key）。getSharedModels 不接
脱敏——它只被平台管理员调用，维护时必须能看到并回填凭据。"
```

---

## Task 6: 平台共享工具 CRUD 端点

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/PlatformController.java`

**Interfaces:**
- Consumes: Task 5 的 `listSharedTools()` / `saveSharedTool(AiTool)` / `deleteSharedTool(Long)`
- Produces: `GET|POST /v1/platform/tools`、`PUT|DELETE /v1/platform/tools/{id}`

- [ ] **Step 1: 加端点**

在 `PlatformController` 加字段：

```java
    private final ChatToolService chatToolService;
```

在共享模型端点之后追加（逐字照抄共享模型那套的鉴权与防越权写法）：

```java
    // ===== 平台共享工具 =====

    @GetMapping("/tools")
    public ApiResponse<List<AiTool>> listSharedTools() {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(chatToolService.listSharedTools());
    }

    @AuditLog(action = "SHARED_TOOL_CREATE", targetType = "AiTool")
    @PostMapping("/tools")
    public ApiResponse<AiTool> createSharedTool(@RequestBody AiTool tool) {
        SecurityUtil.requirePlatformAdmin();
        tool.setId(null);
        return ApiResponse.success("共享工具已创建", chatToolService.saveSharedTool(tool));
    }

    @AuditLog(action = "SHARED_TOOL_UPDATE", targetType = "AiTool")
    @PutMapping("/tools/{id}")
    public ApiResponse<AiTool> updateSharedTool(@PathVariable Long id, @RequestBody AiTool tool) {
        SecurityUtil.requirePlatformAdmin();
        AiTool existing = chatToolService.getTool(id);
        // 防越权：只允许操作平台共享工具，避免用租户私有工具的 id 越权提升/篡改
        if (existing.getScope() != OwnerScope.PLATFORM) {
            throw new BusinessException(404, "共享工具不存在");
        }
        if (tool.getName() != null) existing.setName(tool.getName());
        if (tool.getDescription() != null) existing.setDescription(tool.getDescription());
        if (tool.getInputSchema() != null) existing.setInputSchema(tool.getInputSchema());
        if (tool.getHttpMethod() != null) existing.setHttpMethod(tool.getHttpMethod());
        if (tool.getUrlTemplate() != null) existing.setUrlTemplate(tool.getUrlTemplate());
        // 空值不覆盖：前端不回显凭据，提交时该字段为空表示「不修改」
        if (tool.getAuthHeader() != null && !tool.getAuthHeader().isEmpty()) {
            existing.setAuthHeader(tool.getAuthHeader());
        }
        if (tool.getTimeoutMs() != null) existing.setTimeoutMs(tool.getTimeoutMs());
        if (tool.getMaxResponseChars() != null) existing.setMaxResponseChars(tool.getMaxResponseChars());
        if (tool.getEnabled() != null) existing.setEnabled(tool.getEnabled());
        return ApiResponse.success("共享工具已更新", chatToolService.saveSharedTool(existing));
    }

    @AuditLog(action = "SHARED_TOOL_DELETE", targetType = "AiTool")
    @DeleteMapping("/tools/{id}")
    public ApiResponse<Void> deleteSharedTool(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        chatToolService.deleteSharedTool(id);
        return ApiResponse.success("共享工具已删除", null);
    }
```

补 import（按 IDE 提示，至少需要）：

```java
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.ChatToolService;
```

> ⚠️ `getTool(id)` 走的是租户侧守卫 `mutableInCurrentContext`，平台管理员的 `TenantContext.tenantId` 为 null 时该守卫放行，所以平台管理员能按 id 取到共享工具。这是既有语义，与 `updateSharedModel` 调 `getById` 完全一致。

- [ ] **Step 2: 编译 + 单测回归**

Run: `cd backend && mvn -q compile && mvn test`
Expected: BUILD SUCCESS，单测全绿。

- [ ] **Step 3: 真机验证端点鉴权**

起后端（`dev,demo` profile），然后：

```bash
# 平台管理员：应当 200
TOKEN_ADMIN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
curl -s http://localhost:8080/api/v1/platform/tools -H "satoken: $TOKEN_ADMIN" | head -c 200; echo

# 租户管理员：应当业务态 403
TOKEN_TENANT=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_admin","password":"demo123456"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
curl -s http://localhost:8080/api/v1/platform/tools -H "satoken: $TOKEN_TENANT" | head -c 200; echo
```
Expected: 第一条 `"code":200`；第二条 `"code":403`。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/PlatformController.java
git commit -m "feat(tool): 平台共享工具 CRUD 端点

路径 /v1/platform/tools，与共享模型的 /v1/platform/models 对齐。

鉴权是每个方法开头显式 requirePlatformAdmin()（该控制器的既有写法，不是类级
注解），update 照抄共享模型的防越权判断：只允许操作 scope=PLATFORM 的行。

真机验证：平台管理员 200，租户管理员 403。"
```

---

## Task 7: 跨租户越权与 OSIV 脏检查的集成测试

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/SharedToolIT.java`

**Interfaces:**
- Consumes: `AbstractPostgresIT.seedApp(String suffix, String allowedOrigins) → Application`；Task 6 的端点

- [ ] **Step 1: 写测试**

创建 `backend/src/test/java/com/qingyu/care/it/SharedToolIT.java`：

```java
package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台共享工具的两条只有真实 HTTP 请求才测得到的约束。
 *
 * <p>⚠️ 必须走 MockMvc 而不是直调 service：
 * <ul>
 *   <li>Hibernate {@code @Filter} 只在请求流经 {@code TenantInterceptor} 时才启用；
 *   <li>OSIV 只在真实请求里才让实体保持受管——直调 service 时实体本来就是分离的，
 *       脏检查那条断言会<b>静默退化成永真</b>。
 * </ul>
 *
 * @author ruan
 */
class SharedToolIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AiToolRepository toolRepository;
    @Autowired
    private UserRepository userRepository;

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    /** 建一个平台共享工具（tenant_id/app_id 均 null）。 */
    private AiTool seedSharedTool(String authHeader) {
        AiTool t = AiTool.builder()
                .name("shared_weather").description("查天气")
                .urlTemplate("http://example.com/weather?city={city}")
                .scope(OwnerScope.PLATFORM)
                .enabled(true).build();
        t.setAuthHeader(authHeader);
        t.setTenantId(null);
        t.setAppId(null);
        return toolRepository.saveAndFlush(t);
    }

    @Test
    void 租户列表能看到平台共享工具但读不到其凭据且库里凭据不被抹掉() throws Exception {
        AiTool shared = seedSharedTool("Bearer platform-secret-abc");
        Application app = seedApp("SHARED_TOOL", "http://localhost");
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username("shared_tool_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());

        String token = login("shared_tool_admin", "pwd123456");

        MvcResult r = mockMvc.perform(get("/v1/ai-tools")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId()))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString()).path("data");
        var sharedNode = java.util.stream.StreamSupport.stream(body.spliterator(), false)
                .filter(n -> n.path("id").asLong() == shared.getId())
                .findFirst().orElseThrow(() -> new AssertionError("共享工具未出现在租户列表里"));

        // 能看到工具本身
        assertThat(sharedNode.path("name").asString()).isEqualTo("shared_weather");
        // 但读不到凭据
        assertThat(sharedNode.path("authHeader").isNull()
                || sharedNode.path("authHeader").asString().isEmpty()).isTrue();

        // ⚠️ 核心：脱敏不能把库里的凭据也抹掉（OSIV 下实体受管，漏 detach 就会被刷回 null）
        AiTool reloaded = toolRepository.findById(shared.getId()).orElseThrow();
        assertThat(reloaded.getAuthHeader()).isEqualTo("Bearer platform-secret-abc");
    }

    @Test
    void 租户读不到其他租户的工具() throws Exception {
        Application appA = seedApp("TOOL_OWNER_A", "http://localhost");
        Application appB = seedApp("TOOL_PEEK_B", "http://localhost");

        AiTool aTool = AiTool.builder()
                .name("a_private_tool").description("A 租户私有")
                .urlTemplate("http://a.example.com/{q}")
                .scope(OwnerScope.TENANT).enabled(true).build();
        aTool.setAuthHeader("Bearer a-secret");
        aTool.setTenantId(appA.getTenant().getId());
        aTool.setAppId(appA.getId());
        AiTool savedA = toolRepository.saveAndFlush(aTool);

        userRepository.saveAndFlush(User.builder()
                .tenant(appB.getTenant())
                .username("tool_peek_b_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());

        String tokenB = login("tool_peek_b_admin", "pwd123456");

        MvcResult r = mockMvc.perform(get("/v1/ai-tools")
                        .header("satoken", tokenB)
                        .header("X-App-Id", appB.getId()))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString()).path("data");
        boolean leaked = java.util.stream.StreamSupport.stream(body.spliterator(), false)
                .anyMatch(n -> n.path("id").asLong() == savedA.getId());
        assertThat(leaked).as("B 租户不该看到 A 租户的私有工具").isFalse();
    }
}
```

- [ ] **Step 2: 停掉 dev 后端，跑 IT**

```bash
# ⚠️ 必须先停：IT 用 Testcontainers 起自己的 PG，但 Redis 连本机 6379，
# 与 dev 后端共用消费组会让 WebhookDeliveryIT 必红
PID=$(netstat -ano | grep ":8080" | grep LISTENING | head -1 | awk '{print $NF}')
[ -n "$PID" ] && taskkill //PID $PID //F

cd backend && mvn verify -Dit.test=SharedToolIT
```
Expected: 2 条全绿。

- [ ] **Step 3: 变异验证 detach 那条断言真的有判别力**

临时把 `SharedCredentialMasker` 里的 `entityManager.detach(item);` 注释掉，重跑：

Run: `cd backend && mvn verify -Dit.test=SharedToolIT`
Expected: `租户列表能看到平台共享工具但读不到其凭据且库里凭据不被抹掉` **转红**——重新查库时 `authHeader` 已变成 null。

**若没有转红**，说明这条测试没有判别力（多半是脱敏后没有触发 flush），此时不要继续：把断言改成在同一请求内再触发一次写操作（如再调一次 `POST /v1/ai-tools` 建个工具）以强制 flush，直到变异能转红为止。测试的价值全在能不能抓住这个缺陷。

确认转红后把 `detach` 那行加回来，重跑确认恢复绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/SharedToolIT.java
git commit -m "test(tool): 跨租户越权与 OSIV 脏检查的集成测试

两条都只有真实 HTTP 请求才测得到：@Filter 只在请求流经 TenantInterceptor 时启用；
OSIV 只在真实请求里才让实体保持受管——直调 service 时实体本来就是分离的，脏检查
那条断言会静默退化成永真。

已用变异验证：注释掉 SharedCredentialMasker 的 em.detach，第一条转红。"
```

---

## Task 8: 前端平台共享工具页与租户侧标注

**Files:**
- Create: `frontend/src/pages/SharedToolPage.tsx`
- Create: `frontend/src/pages/SharedToolPage.test.tsx`
- Modify: `frontend/src/api/platform.ts`、`frontend/src/App.tsx`、`frontend/src/layouts/MainLayout.tsx`、`frontend/src/pages/SkillConfigPage.tsx`、`frontend/src/pages/ToolConfigPage.tsx`

**Interfaces:**
- Consumes: Task 6 的 `/v1/platform/tools` 四个端点
- Produces: `platformService.listSharedTools()` / `createSharedTool(data)` / `updateSharedTool(id, data)` / `deleteSharedTool(id)`，均返回 `Promise<ApiResponse<...>>`

- [ ] **Step 1: 加 api service 方法**

先读 `frontend/src/api/platform.ts` 现有的共享模型方法，**照它的写法**在同一个 `platformService` 对象里追加：

```ts
  // 共享工具（路径不带 /v1——axios 实例的 baseURL 已经是 '/api/v1'，
  // 见 api/client.ts；写成 '/v1/platform/tools' 会拼成 /api/v1/v1/...）
  listSharedTools: (): Promise<ApiResponse<AiTool[]>> =>
    api.get('/platform/tools'),
  createSharedTool: (data: Partial<AiTool>): Promise<ApiResponse<AiTool>> =>
    api.post('/platform/tools', data),
  updateSharedTool: (id: number, data: Partial<AiTool>): Promise<ApiResponse<AiTool>> =>
    api.put(`/platform/tools/${id}`, data),
  deleteSharedTool: (id: number): Promise<ApiResponse<void>> =>
    api.delete(`/platform/tools/${id}`),
```

> `getErrorMessage` 从 `@/lib/apiResult` import（既有助手，`SharedModelPage` 已在用）。

`AiTool` 类型若尚未在该文件可见，从既有的工具类型定义处 import（见 `ToolConfigPage.tsx` 用的那个）。若 `AiTool` 类型里没有 `scope`，补上：

```ts
scope?: 'PLATFORM' | 'TENANT';
```

- [ ] **Step 2: 写 SharedToolPage**

创建 `frontend/src/pages/SharedToolPage.tsx`，**结构逐段照抄 `SharedModelPage.tsx`**（同款 `reload` / 对话框 / 保存 / 删除），字段换成工具的那几个：名称、描述、HTTP 方法、URL 模板、鉴权头、参数 Schema、超时、响应截断、启用。

必须遵守的两条本仓库约定：

```ts
// ① 业务异常是「HTTP 200 + body.code」，axios 不 reject，写操作必须显式判
const res = await platformService.createSharedTool(form);
if (res.code !== 200) throw new Error(res.message);

// ② catch 里弹 destructive toast
catch (e: unknown) {
  toast({ title: '保存失败', description: getErrorMessage(e), variant: 'destructive' });
}
```

鉴权头输入框加一行说明：

```tsx
<p className="text-xs text-muted-foreground">
  留空表示不修改。该凭据由平台持有，租户可以使用这个工具但看不到凭据内容。
</p>
```

- [ ] **Step 3: 挂路由与菜单**

`App.tsx` 在 `platform/models` 那条之后加：

```tsx
<Route path="platform/tools" element={<SharedToolPage />} />
```

并 import。`MainLayout.tsx` 在「平台管理」组里「共享模型池」之后加一项「共享工具」，`to="/platform/tools"`，图标沿用工具页那个（`Wrench`）。

- [ ] **Step 4: 租户侧标注平台共享项**

`ToolConfigPage.tsx` 列表项里，`scope === 'PLATFORM'` 时：

```tsx
{t.scope === 'PLATFORM' && (
  <Badge variant="secondary" className="text-[10px]">平台共享</Badge>
)}
```

并且**该项不渲染编辑/删除按钮**——后端已挡（`mutableInCurrentContext` 对租户拒绝平台行），前端不该给出一个必然失败的入口：

```tsx
{t.scope !== 'PLATFORM' && (
  <>{/* 原有的编辑与删除按钮 */}</>
)}
```

`SkillConfigPage.tsx` 的工具选择器同样加「平台共享」badge（勾选框保留——租户就是要能勾它）：

```tsx
{t.scope === 'PLATFORM' && (
  <Badge variant="secondary" className="text-[10px] ml-1">平台共享</Badge>
)}
```

- [ ] **Step 5: 写 SharedToolPage 的写操作回归测试**

创建 `frontend/src/pages/SharedToolPage.test.tsx`，**模板照抄 `ToolConfigPage.test.tsx`**（桩 api service + `useToast`，点一下触发写操作，断言业务错误弹 destructive toast、成功才刷新列表）。至少两条：

```tsx
it('创建失败时弹 destructive toast', async () => { /* 桩 createSharedTool 返回 {code:400,message:'重名'} */ });
it('创建成功后刷新列表', async () => { /* 桩返回 {code:200}，断言 listSharedTools 被再次调用 */ });
```

⚠️ `vi.mock` 的工厂被提到所有 import 之前执行，桩函数必须用 `vi.hoisted` 一起提上去。

- [ ] **Step 6: 跑前端测试与 lint**

```bash
cd frontend && pnpm test && pnpm lint
```
Expected: 全绿，lint 零 error。

- [ ] **Step 7: 提交**

```bash
git add frontend/src
git commit -m "feat(frontend): 平台共享工具页 + 租户侧标注

新增 SharedToolPage（结构照抄 SharedModelPage），挂在「平台管理」组下。

租户侧：ToolConfigPage 与 SkillConfigPage 给平台共享项加 badge；ToolConfigPage
不渲染平台项的编辑/删除按钮——后端已挡，前端不该给出一个必然失败的入口。

写操作按本仓库约定显式判 res.code !== 200（业务异常是 HTTP 200 + body.code，
axios 不 reject，不判就会静默吞掉），并补了两条回归测试。"
```

---

## Task 9: 真机验证与文档回填

**Files:**
- Modify: `SaaS平台规划.md`、`CLAUDE.md`

- [ ] **Step 1: 真机验证共享工具端到端可用（本计划唯一自动化测不到的一条）**

起后端 `dev,demo` profile 与前端。

1. 用 `admin` / `admin123`（平台管理员）登录，进「平台管理 → 共享工具」，建一个工具：
   - 名称 `shared_echo`，描述 `回显测试`
   - URL 模板 `https://postman-echo.com/get?q={q}`
   - 鉴权头 `X-Demo: platform-secret`
   - 参数 Schema `{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}`
2. 用 `demo_admin` / `demo123456` 登录，选中「云舟商城客服」应用：
   - 进「工具」页，确认 `shared_echo` 出现且带「平台共享」badge、**没有编辑/删除按钮**
   - 打开浏览器 devtools 的 Network，看 `/v1/ai-tools` 的响应体，确认该项的 `authHeader` **为空**
3. 进「技能」页建一个 skill，正文写「需要回显时调用 shared_echo」，勾选 `shared_echo`
4. 进「智能问答」页问一句会触发该工具的话
5. **确认工具真的被调用且带上了鉴权头** —— 看后端日志里的出站请求，或用一个能回显请求头的端点（postman-echo 的响应里会带回 headers）

Expected：第 5 步能看到 `X-Demo: platform-secret` 出现在出站请求里。

> ⚠️ 这条是整个计划里唯一自动化测不到的：单测里 `HttpToolExecutor` 是 mock 的，不校验凭据是否为空；IT 也不发真实出站请求。**脱敏若误伤了运行时路径，只有这一步能发现**——症状是工具调用返回 401/403，模型拿着错误响应继续编答案，日志里没有任何异常。

- [ ] **Step 2: 验证共享工具停用即时生效**

把 `shared_echo` 在平台页停用，再问一次同样的问题。
Expected: 模型不再调用该工具（`findSharedByIdInAndEnabledTrue` 带 `enabled = true` 条件）。

验完改回启用。

- [ ] **Step 3: 回填规划文档**

`SaaS平台规划.md` 的 5.4 第 6 项改为：

```markdown
- 第 6 项 ~~**平台共享工具/skill**~~ ✅ **已完成（2026-08-12，只做工具）**
  平台管理员维护共享 HTTP 工具库（`/v1/platform/tools`），租户建 skill 时可勾选使用，
  可用不可见凭据。**刻意不共享 skill**：它的 markdown 注入每一轮系统提示，平台加一条等于
  静默改掉所有租户的 Prompt 与回答行为，风险与收益不匹配。
  同批修掉一个既有漏洞：租户管理员此前能通过 `GET /v1/ai-models` 读到平台共享模型的明文
  `apiKey`。详见 `docs/superpowers/specs/2026-08-12-platform-shared-tools-design.md`。
```

`## 当前状态` 的待办摘要里，把「第 6 项…最便宜」那句删掉（已完成）。

- [ ] **Step 4: 回填 CLAUDE.md**

在「工具调用」相关的架构要点处（若无则在「AOP 横切」之后）新增一小节：

```markdown
### 平台共享工具（`AiTool.scope`）

平台管理员可建 `scope=PLATFORM` 的共享工具（`tenant_id`/`app_id` 均 null），租户在自己的
skill 里勾选使用。**只共享工具、不共享 skill**——skill 的 markdown 注入每一轮系统提示，
共享它等于静默改掉所有租户的回答行为。

⚠️ 三条容易写错的地方：

1. **两条 native 共享查询必须钉 `scope = 'PLATFORM'`**（`AiToolRepository`）。自有工具走的
   `findByIdInAndEnabledTrue` 是 filter-bound 的，别的租户的 id 天然查不出来；共享查询是
   native、不受 `@Filter` 保护，少了这个条件就变成任意 id 可绑 = **跨租户调用他人工具并用掉
   他人的鉴权头**。
2. **脱敏只能用在管理面 list 出口**（`listTools` / `getAllModels`），**绝不能碰运行时解析**
   （`resolveTools`）——`HttpToolCallback` 要用真 `authHeader` 发出站请求。误伤的症状是共享
   工具静默失效：第三方返 401，模型拿着错误响应继续编答案，全程无异常无日志。
3. **`SharedCredentialMasker` 脱敏前必须 `em.detach`**。本项目未配置
   `spring.jpa.open-in-view`，即 Spring Boot 默认的 `true`，OSIV 让 list 查出来的实体在整个
   请求期间保持**受管**，直接 setter 置空会被脏检查刷回库——本意是防泄漏，结果是**把凭据真的
   删掉**。`SharedToolIT` 用真实 MockMvc 请求钉住这条（直调 service 时实体本来就是分离的，
   断言会静默退化成永真）。
```

- [ ] **Step 5: 补点名脚本映射**

`scripts/manual-smoke-hints.sh` 的 `RULES` 里，给工具相关路径加一条指向新章节：

```bash
  'AiTool|ChatToolService|SharedCredentialMasker|AiToolRepository::### 平台共享工具（`AiTool.scope`）'
```

Run: `./scripts/manual-smoke-hints.sh master...HEAD`
Expected: 点名「平台共享工具」这一节，exit 0。若 exit 1 说明章节标题与脚本里写的不一致，对齐即可。

- [ ] **Step 6: 全量回归**

```bash
cd backend && mvn test
cd ../frontend && pnpm test && pnpm lint
cd ../widget && pnpm test
```
Expected: 全绿。

- [ ] **Step 7: 提交**

```bash
git add SaaS平台规划.md CLAUDE.md scripts/manual-smoke-hints.sh
git commit -m "docs: 平台共享工具收官——回填规划与架构要点

Phase 5.4 第 6 项标记完成（只做工具，不共享 skill，理由写在条目里）。

CLAUDE.md 新增一节，写明三条容易写错的地方：native 查询必须钉 scope、脱敏不能
碰运行时路径、脱敏前必须 detach（OSIV 默认开启）。点名脚本加了对应映射。

真机验证过共享工具端到端可用且出站请求带上了平台凭据——这条自动化测不到，
单测里 HttpToolExecutor 是 mock 的，不校验凭据是否为空。"
```

---

## 收尾：分支合并

- [ ] **Step 1: 通读全分支 diff**

Run: `git diff master...HEAD --stat`
逐个文件确认无误提交（`.env`、`target/`、`node_modules/`）。

- [ ] **Step 2: 按点名脚本走手工验证**

Run: `./scripts/manual-smoke-hints.sh master...HEAD`

- [ ] **Step 3: 交给用户决定合并方式**

不自行合并，先把分支改动摘要交给用户。

---

## Self-Review

**1. Spec coverage**

| Spec 要求 | 对应 Task |
|-----------|-----------|
| §3.1 `AiTool.scope` + 迁移 | Task 1 |
| §3.2 两条 native 查询（钉 `scope='PLATFORM'`） | Task 2 |
| §3.3 `resolve()` 求并集、不脱敏 | Task 4 |
| §3.4 脱敏助手 + `em.detach` | Task 3 |
| §3.5 管理面合并/脱敏 + 平台侧三方法 + 端点 | Task 5、Task 6 |
| §3.6 前端四处改动 | Task 8 |
| §3.7 停用即时生效 | Task 9 Step 2 验证（实现由 `enabled = true` 条件天然满足） |
| §5 测试 1–6（单测） | Task 3（4–6 对应脱敏三条）、Task 4（1–3） |
| §5 测试 7–8（IT） | Task 7 |
| §5 测试 9（手工） | Task 9 Step 1 |
| 同批修 `getAllModels` 漏洞 | Task 5 Step 4 |

无遗漏。

**2. Placeholder scan**：无 TBD/TODO。三处标注「照抄既有文件」（`SharedToolPage` 抄 `SharedModelPage`、前端测试抄 `ToolConfigPage.test.tsx`、api 方法抄既有共享模型方法）是**刻意的**——那些文件的完整内容在仓库里，抄比在计划里重述可靠，且计划已给出必须遵守的两条约定（`res.code !== 200` 判错、`vi.hoisted`）的具体代码。

**3. Type consistency**：`SharedCredentialMasker.mask(List<T>, Function<T,Long>, Function<T,Long>, Consumer<T>) → List<T>` 在 Task 3 定义，Task 5 两处调用签名一致；`findAllSharedTools()` / `findSharedByIdInAndEnabledTrue(Collection<Long>)` 在 Task 2 定义，Task 4、Task 5 调用一致；`listSharedTools()` / `saveSharedTool(AiTool)` / `deleteSharedTool(Long)` 在 Task 5 定义，Task 6 调用一致；`OwnerScope.PLATFORM` 全程用既有枚举，未新造值。

**已知的跨任务耦合**：Task 4 的测试文件里有一个给 Task 5 用的占位 `@Mock`，Task 4 实现时要删、Task 5 再加回真实依赖——已在 Task 4 Step 1 的注释里写明。

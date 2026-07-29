# 多级配置回退·平台管理员单结果查询收口 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 消除平台管理员（PLATFORM_ADMIN 绕过 Hibernate @Filter）用应用聊天时 `getActiveStrategy`/`getActiveTemplate`/`resolveActiveRule` 三处单结果配置查询抛 `NonUniqueResultException`（HTTP 500）的缺陷，并让配置解析到「该应用所属租户」的配置。

**Architecture:** 新增 `ConfigScopeResolver` 解析有效 (tenantId, appId)（平台管理员由 X-App-Id 反查 Application 归属租户）；三处「@Filter 依赖的 `Optional` 派生查询」改为「显式按 (tenantId, appId) 收窄的 JPQL `List` 查询 + findFirst」；回退链其余步骤（平台默认 native / default / 兜底）不变。

**Tech Stack:** Spring Boot 3 + Spring Data JPA（Hibernate 6）+ PostgreSQL；单测纯 Mockito；IT 继承 `AbstractPostgresIT`（Testcontainers pgvector）。

## Global Constraints

- 简体中文：对话、代码注释、commit 信息；文件作者署名 `ruan`。
- 仅在 `fix/config-resolution-platform-scope` 分支 commit；**不 push / 不 merge / 不 rebase / 不 reset / 不 stash / 不切分支**（收尾合并另行授权）。
- **正常租户 / 开放 API 零回归**：它们 `tenantId != null`，新 scoped 查询须与当前 @Filter 逐条件等价。
- 回退链形状与语义不变：平台默认走既有 native 查询（`findPlatformDefaultBy...`）、default 场景、静态兜底 / `orElseThrow`。
- 平台管理员语义：解析为**该应用所属租户**的配置（已拍板 Option B）。
- 新 scoped 查询用 **JPQL**（非 native），返回 `List` + `ORDER BY id`，复用本仓 `:appId IS NULL OR app_id = :appId` 惯用法（见 `PromptTemplateRepository.deactivateAllForTenant`）。
- `ConfigScopeResolver.resolve()` 中 `tenantId != null` 时**不得**查 Application（正常租户零额外查询）。
- 每个 Task 末尾跑 `cd backend && mvn -q test`（单测）兜底；含 IT 的 Task 用 `mvn -q verify`（需 Docker Desktop）。
- 不改 `@Filter`/`TenantDataAspect`、不改管理面 `getAll*`、不给 HandoverRule 加 create 接口。

---

### Task 1: ConfigScopeResolver + Application 归属反查

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/ConfigScopeResolver.java`
- Modify: `backend/src/main/java/com/qingyu/care/repository/ApplicationRepository.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ConfigScopeResolverTest.java`

**Interfaces:**
- Produces:
  - `ConfigScopeResolver`（`@Component`）：`Scope resolve()`；嵌套 `public record Scope(Long tenantId, Long appId) {}`。
  - `ApplicationRepository.findTenantIdById(Long appId) -> Optional<Long>`（native 取 FK）。
- Consumes: `TenantContext.getTenantId()/getAppId()`（static，`config/`）；`ApplicationRepository`。

- [ ] **Step 1: 写失败单测 `ConfigScopeResolverTest`**

```java
package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.repository.ApplicationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigScopeResolverTest {

    @Mock ApplicationRepository applicationRepository;
    @InjectMocks ConfigScopeResolver resolver;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 有租户上下文_原样返回且不查Application() {
        TenantContext.setTenantId(7L);
        TenantContext.setAppId(3L);

        ConfigScopeResolver.Scope scope = resolver.resolve();

        assertThat(scope.tenantId()).isEqualTo(7L);
        assertThat(scope.appId()).isEqualTo(3L);
        verifyNoInteractions(applicationRepository);
    }

    @Test
    void 平台管理员无租户但有应用_反查应用归属租户() {
        TenantContext.setAppId(3L);   // tenantId 为空
        when(applicationRepository.findTenantIdById(3L)).thenReturn(Optional.of(4L));

        ConfigScopeResolver.Scope scope = resolver.resolve();

        assertThat(scope.tenantId()).isEqualTo(4L);
        assertThat(scope.appId()).isEqualTo(3L);
    }

    @Test
    void 无租户无应用_返回全空() {
        ConfigScopeResolver.Scope scope = resolver.resolve();

        assertThat(scope.tenantId()).isNull();
        assertThat(scope.appId()).isNull();
        verifyNoInteractions(applicationRepository);
    }

    @Test
    void 有应用但应用查不到归属租户_租户仍为空() {
        TenantContext.setAppId(99L);
        when(applicationRepository.findTenantIdById(99L)).thenReturn(Optional.empty());

        ConfigScopeResolver.Scope scope = resolver.resolve();

        assertThat(scope.tenantId()).isNull();
        assertThat(scope.appId()).isEqualTo(99L);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn -q test -Dtest=ConfigScopeResolverTest`
Expected: 编译失败（`ConfigScopeResolver`/`findTenantIdById` 不存在）。

- [ ] **Step 3: 加 `ApplicationRepository.findTenantIdById`**

在 `ApplicationRepository` 加（补 import `org.springframework.data.jpa.repository.Query`、`org.springframework.data.repository.query.Param`）：
```java
    /**
     * 反查应用的归属租户 ID（native 直取 FK，避开 lazy Tenant 关联）。
     * 供平台管理员(无租户上下文)按当前 X-App-Id 解析配置作用域。
     */
    @Query(value = "SELECT tenant_id FROM sys_application WHERE id = :appId", nativeQuery = true)
    Optional<Long> findTenantIdById(@Param("appId") Long appId);
```

- [ ] **Step 4: 写 `ConfigScopeResolver`**

```java
package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.repository.ApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 配置查询作用域解析器：给出「当前生效配置」查询应使用的有效 (tenantId, appId)。
 *
 * <p>有租户上下文（普通租户 / 开放 API 鉴权后）直接沿用；平台管理员登录后
 * {@link TenantContext#getTenantId()} 恒为空，则由当前 X-App-Id 反查应用归属租户，
 * 使多级配置回退解析到「该应用真实使用的那一份配置」（与该应用租户用户所见一致），
 * 而非依赖 Hibernate @Filter（平台管理员绕过过滤器会裸扫全库致单结果查询 NonUnique）。
 */
@Component
@RequiredArgsConstructor
public class ConfigScopeResolver {

    private final ApplicationRepository applicationRepository;

    public Scope resolve() {
        Long appId = TenantContext.getAppId();
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null && appId != null) {
            tenantId = applicationRepository.findTenantIdById(appId).orElse(null);
        }
        return new Scope(tenantId, appId);
    }

    /** 有效配置作用域。tenantId 为空表示无有效租户（应跳过租户自有查询、直落平台默认）。 */
    public record Scope(Long tenantId, Long appId) {}
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn -q test -Dtest=ConfigScopeResolverTest`
Expected: 4/4 通过。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ConfigScopeResolver.java \
        backend/src/main/java/com/qingyu/care/repository/ApplicationRepository.java \
        backend/src/test/java/com/qingyu/care/service/ConfigScopeResolverTest.java
git commit -m "feat: ConfigScopeResolver 解析有效配置作用域(平台管理员由X-App-Id反查归属租户)"
```

---

### Task 2: ChatStrategyService 收口 + FallbackIT 平台管理员断言

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/ChatStrategyRepository.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatStrategyService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ChatStrategyServiceTest.java`（若已存在则补用例；否则新建）
- Test: `backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java`（扩展既有）

**Interfaces:**
- Consumes: `ConfigScopeResolver`（Task 1）；`ChatStrategyRepository.findPlatformDefaultByScenario`（既有）。
- Produces: `ChatStrategyRepository.findActiveDefaultScoped(String scenario, Long tenantId, Long appId) -> List<ChatStrategy>`。

- [ ] **Step 1: 写失败单测（解析按 Scope 收窄 + 回退）**

先 `cat backend/src/test/java/com/qingyu/care/service/ChatStrategyServiceTest.java` 看是否存在及其构造方式。新增/补充以下用例（`@ExtendWith(MockitoExtension.class)`，`@Mock ChatStrategyRepository repository; @Mock AbExperimentRepository abExperimentRepository; @Mock ConfigScopeResolver configScopeResolver; @InjectMocks ChatStrategyService service;`）：

```java
    @org.junit.jupiter.api.Test
    void getActiveStrategy_有有效作用域_按tenantId_appId取自有默认策略() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        ChatStrategy own = ChatStrategy.builder().name("租户4自有").scenario("default").build();
        when(repository.findActiveDefaultScoped("default", 4L, 3L))
                .thenReturn(java.util.List.of(own));

        ChatStrategy result = service.getActiveStrategy("default");

        assertThat(result.getName()).isEqualTo("租户4自有");
        verify(repository, never()).findPlatformDefaultByScenario(anyString());
    }

    @org.junit.jupiter.api.Test
    void getActiveStrategy_作用域无租户_跳过自有直落平台默认() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(null, 3L));
        ChatStrategy platform = ChatStrategy.builder().name("平台默认").scenario("default").build();
        when(repository.findPlatformDefaultByScenario("default"))
                .thenReturn(java.util.Optional.of(platform));

        ChatStrategy result = service.getActiveStrategy("default");

        assertThat(result.getName()).isEqualTo("平台默认");
        verify(repository, never()).findActiveDefaultScoped(anyString(), any(), any());
    }

    @org.junit.jupiter.api.Test
    void getActiveStrategy_自有多行_findFirst不抛NonUnique() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        when(repository.findActiveDefaultScoped("default", 4L, 3L))
                .thenReturn(java.util.List.of(
                        ChatStrategy.builder().name("第一").scenario("default").build(),
                        ChatStrategy.builder().name("第二").scenario("default").build()));

        ChatStrategy result = service.getActiveStrategy("default");

        assertThat(result.getName()).isEqualTo("第一");   // ORDER BY id → findFirst
    }
```
> 若已存在覆盖 `getActiveStrategy` 老行为的用例（如断言调 `findByScenarioAndEnabledTrueAndIsDefaultTrue`），改为上面新范式；`init()`/`saveStrategy`/`getById` 等既有用例保持。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ChatStrategyServiceTest`
Expected: 编译失败（`findActiveDefaultScoped` 不存在 / 构造缺 `ConfigScopeResolver`）。

- [ ] **Step 3: 加 repository scoped 查询**

在 `ChatStrategyRepository` 加：
```java
    /**
     * 显式按 (tenantId, appId) 收窄取场景默认启用策略——不依赖 Hibernate @Filter，
     * 使平台管理员(过滤器绕过)也能确定性取到目标租户的策略。返回 List + ORDER BY id 杜绝 NonUnique。
     */
    @Query("SELECT s FROM ChatStrategy s WHERE s.scenario = :scenario AND s.enabled = true AND s.isDefault = true "
         + "AND s.tenantId = :tenantId AND (:appId IS NULL OR s.appId = :appId) ORDER BY s.id")
    List<ChatStrategy> findActiveDefaultScoped(@Param("scenario") String scenario,
                                               @Param("tenantId") Long tenantId,
                                               @Param("appId") Long appId);
```

- [ ] **Step 4: 改造 `ChatStrategyService`**

注入 resolver：把字段区改为
```java
    private final ChatStrategyRepository repository;
    private final AbExperimentRepository abExperimentRepository;
    private final ConfigScopeResolver configScopeResolver;
```
把 `getActiveStrategy` 改为：
```java
    public ChatStrategy getActiveStrategy(String scenario) {
        ConfigScopeResolver.Scope scope = configScopeResolver.resolve();
        return findScopedActiveDefault(scenario, scope)
                .or(() -> repository.findPlatformDefaultByScenario(scenario))
                .or(() -> findScopedActiveDefault("default", scope))
                .orElseGet(() -> ChatStrategy.builder()
                        .name("运行时硬编码兜底")
                        .scenario("default")
                        .topK(5)
                        .similarityThreshold(0.7)
                        .allowNoKnowledge(true)
                        .build());
    }

    /** 按有效作用域取租户自有场景默认策略；无有效租户(平台管理员且应用无归属)则跳过、交由平台默认兜底。 */
    private Optional<ChatStrategy> findScopedActiveDefault(String scenario, ConfigScopeResolver.Scope scope) {
        if (scope.tenantId() == null) {
            return Optional.empty();
        }
        return repository.findActiveDefaultScoped(scenario, scope.tenantId(), scope.appId())
                .stream().findFirst();
    }
```
（更新 `getActiveStrategy` javadoc 首句为「显式按有效作用域收窄，避免平台管理员绕过 @Filter 时 NonUnique」。）

- [ ] **Step 5: 运行单测确认通过**

Run: `cd backend && mvn -q test -Dtest=ChatStrategyServiceTest`
Expected: 全绿。

- [ ] **Step 6: 扩展 `ChatStrategyFallbackIT` 平台管理员断言**

先 `cat backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java` 摸清它的 seed 方式（`seedApp`、jdbcTemplate 或实体播种、如何设 `TenantContext`、是否类级 `@Transactional`）。新增一条用例（**沿用该类既有 seed 风格**，不引入新机制）：

- 播种：`seedApp` 得应用 A（归属租户 T）；播种「租户 T + 另一租户 T2 各一条 `scenario='default', enabled=true, isDefault=true` 行」+ 平台默认（`tenant_id IS NULL`）；确保三行 `enabled/isDefault` 同键（正是 NonUnique 触发条件）。
- 设 `TenantContext.setTenantId(null); TenantContext.setAppId(A.id)`（IT 未登录 → `TenantDataAspect` 不启用过滤器，精确复现平台管理员「无过滤器」态）；类若无类级 `@Transactional` 则该用例需保证 filter 语义与既有用例一致（照既有用例做法）。
- 断言：`chatStrategyService.getActiveStrategy("default")` 返回**租户 T 的那一行**（按可区分字段，如 name/id）、**不抛异常**、既不是 T2 也不是平台默认。

> 关键：断言必须验证「解析到租户 T」这一正确行为，不能只验「不抛异常」（避免把错误解析当通过——参照 handover 修复教训）。

- [ ] **Step 7: 运行 IT 确认通过**

Run: `cd backend && mvn -q verify -Dit.test=ChatStrategyFallbackIT`（需 Docker Desktop）
Expected: 通过。

- [ ] **Step 8: 全量单测兜底 + 提交**

Run: `cd backend && mvn -q test`
Expected: 全绿（既有 ChatServiceImpl 等单测若因 `ChatStrategyService` 构造变化编译失败，属正常——但本 Task 只改 `ChatStrategyService` 内部字段，`ChatStrategyService` 的其它使用方通过 Spring 注入不受影响；若有直接 `new ChatStrategyService(...)` 的测试则同步补 mock。）

```bash
git add backend/src/main/java/com/qingyu/care/repository/ChatStrategyRepository.java \
        backend/src/main/java/com/qingyu/care/service/ChatStrategyService.java \
        backend/src/test/java/com/qingyu/care/service/ChatStrategyServiceTest.java \
        backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java
git commit -m "fix: ChatStrategy 解析显式按有效作用域收窄，修平台管理员 NonUnique(含 FallbackIT 平台管理员断言)"
```

---

### Task 3: PromptTemplateService 收口 + FallbackIT 平台管理员断言

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/PromptTemplateRepository.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/PromptTemplateService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/PromptTemplateServiceTest.java`（补/新建）
- Test: `backend/src/test/java/com/qingyu/care/it/PromptTemplateFallbackIT.java`（扩展既有）

**Interfaces:**
- Consumes: `ConfigScopeResolver`；`PromptTemplateRepository.findPlatformDefaultActive`（既有）。
- Produces: `PromptTemplateRepository.findActiveScoped(Long tenantId, Long appId) -> List<PromptTemplate>`。

- [ ] **Step 1: 写失败单测**

`@Mock PromptTemplateRepository repository; @Mock ConfigScopeResolver configScopeResolver; @InjectMocks PromptTemplateService service;`（注意：`PromptTemplateService` 还有 `@Value defaultTemplate` 字段，@InjectMocks 不注入 @Value，测试解析路径不依赖它，可不设）：

```java
    @org.junit.jupiter.api.Test
    void getActiveTemplate_有有效作用域_取自有激活模板() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        PromptTemplate own = PromptTemplate.builder().name("租户4").content("C").isActive(true).build();
        when(repository.findActiveScoped(4L, 3L)).thenReturn(java.util.List.of(own));

        assertThat(service.getActiveTemplate().getName()).isEqualTo("租户4");
        verify(repository, never()).findPlatformDefaultActive();
    }

    @org.junit.jupiter.api.Test
    void getActiveTemplate_作用域无租户_跳过自有落平台默认() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(null, 3L));
        when(repository.findActiveScoped(any(), any())).thenReturn(java.util.List.of()); // 不应被调用,但给安全桩
        PromptTemplate platform = PromptTemplate.builder().name("平台").content("P").isActive(true).build();
        when(repository.findPlatformDefaultActive()).thenReturn(java.util.Optional.of(platform));

        assertThat(service.getActiveTemplate().getName()).isEqualTo("平台");
        verify(repository, never()).findActiveScoped(any(), any());
    }

    @org.junit.jupiter.api.Test
    void getActiveTemplate_均无_抛ResourceNotFound() {
        when(configScopeResolver.resolve())
                .thenReturn(new ConfigScopeResolver.Scope(null, 3L));
        when(repository.findPlatformDefaultActive()).thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getActiveTemplate())
                .isInstanceOf(com.qingyu.care.exception.ResourceNotFoundException.class);
    }
```
> 第二个用例里 `findActiveScoped` 的 `never()` 与安全桩二选一：因 `tenantId==null` 会短路，不会调 `findActiveScoped`——直接删掉那行安全桩、只留 `never()` 断言更干净。实现时择一。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=PromptTemplateServiceTest`
Expected: 编译失败。

- [ ] **Step 3: 加 repository scoped 查询**

在 `PromptTemplateRepository` 加：
```java
    /**
     * 显式按 (tenantId, appId) 收窄取激活模板——不依赖 Hibernate @Filter，
     * 使平台管理员(过滤器绕过)也能确定性取到目标租户的激活模板。List + ORDER BY id 杜绝 NonUnique。
     */
    @Query("SELECT t FROM PromptTemplate t WHERE t.isActive = true "
         + "AND t.tenantId = :tenantId AND (:appId IS NULL OR t.appId = :appId) ORDER BY t.id")
    List<PromptTemplate> findActiveScoped(@Param("tenantId") Long tenantId, @Param("appId") Long appId);
```

- [ ] **Step 4: 改造 `PromptTemplateService`**

字段加 `private final ConfigScopeResolver configScopeResolver;`。`getActiveTemplate` 改为：
```java
    public PromptTemplate getActiveTemplate() {
        ConfigScopeResolver.Scope scope = configScopeResolver.resolve();
        return findScopedActive(scope)
                .or(repository::findPlatformDefaultActive)
                .orElseThrow(() -> new ResourceNotFoundException("没有激活的 Prompt 模板"));
    }

    /** 按有效作用域取租户自有激活模板；无有效租户则跳过、交由平台默认兜底。 */
    private java.util.Optional<PromptTemplate> findScopedActive(ConfigScopeResolver.Scope scope) {
        if (scope.tenantId() == null) {
            return java.util.Optional.empty();
        }
        return repository.findActiveScoped(scope.tenantId(), scope.appId()).stream().findFirst();
    }
```

- [ ] **Step 5: 运行单测确认通过**

Run: `cd backend && mvn -q test -Dtest=PromptTemplateServiceTest`
Expected: 全绿。

- [ ] **Step 6: 扩展 `PromptTemplateFallbackIT`**

`cat` 该 IT 摸清 seed 风格。新增用例：`seedApp`→应用 A(租户 T)；播种「租户 T + 租户 T2 各一条 `is_active=true` 模板」+ 平台默认(`tenant_id IS NULL`)；设 `TenantContext(tenantId=null, appId=A.id)`；断言 `getActiveTemplate()` 返回**租户 T 的模板**、不抛 NonUnique。

- [ ] **Step 7: 运行 IT**

Run: `cd backend && mvn -q verify -Dit.test=PromptTemplateFallbackIT`
Expected: 通过。

- [ ] **Step 8: 全量单测 + 提交**

Run: `cd backend && mvn -q test`
```bash
git add backend/src/main/java/com/qingyu/care/repository/PromptTemplateRepository.java \
        backend/src/main/java/com/qingyu/care/service/PromptTemplateService.java \
        backend/src/test/java/com/qingyu/care/service/PromptTemplateServiceTest.java \
        backend/src/test/java/com/qingyu/care/it/PromptTemplateFallbackIT.java
git commit -m "fix: PromptTemplate 解析显式按有效作用域收窄，修平台管理员 NonUnique(含 FallbackIT 平台管理员断言)"
```

---

### Task 4: HandoverRuleService 收口 + FallbackIT 平台管理员断言

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/HandoverRuleRepository.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/HandoverRuleService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/HandoverTriggerFallbackTest.java`（既有；补用例）或对应单测
- Test: `backend/src/test/java/com/qingyu/care/it/HandoverRuleFallbackIT.java`（扩展既有）

**Interfaces:**
- Consumes: `ConfigScopeResolver`；`HandoverRuleRepository.findPlatformDefaultByRuleType`（既有）。
- Produces: `HandoverRuleRepository.findByRuleTypeScoped(HandoverRuleType ruleType, Long tenantId, Long appId) -> List<HandoverRule>`。

- [ ] **Step 1: 写失败单测**

`cat backend/src/test/java/com/qingyu/care/service/HandoverTriggerFallbackTest.java` 看既有构造（它测 `checkKeywordTrigger` 等）。构造需补 `@Mock ConfigScopeResolver configScopeResolver;`。新增/改用例——`resolveActiveRule` 现经 Scope：

```java
    @org.junit.jupiter.api.Test
    void checkKeywordTrigger_有作用域_按tenantId取自有规则命中() throws Exception {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(4L, 3L));
        HandoverRule own = HandoverRule.builder().ruleType(HandoverRuleType.KEYWORD)
                .enabled(true).config("{\"keywords\":[\"转人工\"]}").build();
        when(repository.findByRuleTypeScoped(HandoverRuleType.KEYWORD, 4L, 3L))
                .thenReturn(java.util.List.of(own));

        assertThat(service.checkKeywordTrigger("我要转人工")).isTrue();
        verify(repository, never()).findPlatformDefaultByRuleType(anyString());
    }

    @org.junit.jupiter.api.Test
    void checkKeywordTrigger_作用域无租户_落平台默认() throws Exception {
        when(configScopeResolver.resolve()).thenReturn(new ConfigScopeResolver.Scope(null, 3L));
        HandoverRule platform = HandoverRule.builder().ruleType(HandoverRuleType.KEYWORD)
                .enabled(true).config("{\"keywords\":[\"人工\"]}").build();
        when(repository.findPlatformDefaultByRuleType("KEYWORD"))
                .thenReturn(java.util.Optional.of(platform));

        assertThat(service.checkKeywordTrigger("请转人工")).isTrue();
        verify(repository, never()).findByRuleTypeScoped(any(), any(), any());
    }
```
> 若既有用例 stub 了 `repository.findByRuleType(...)`，改为 `findByRuleTypeScoped(...)` 并补 `configScopeResolver.resolve()` 桩（既有测多为无上下文 → Scope(null,null) → 命中平台默认路径，与原「回退平台默认」断言一致）。保持既有断言语义。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=HandoverTriggerFallbackTest`
Expected: 编译失败。

- [ ] **Step 3: 加 repository scoped 查询**

在 `HandoverRuleRepository` 加：
```java
    /**
     * 显式按 (tenantId, appId) 收窄取指定类型规则——不依赖 Hibernate @Filter，
     * 使平台管理员(过滤器绕过)也能确定性取到目标租户的规则。List + ORDER BY id 杜绝 NonUnique。
     */
    @Query("SELECT r FROM HandoverRule r WHERE r.ruleType = :ruleType "
         + "AND r.tenantId = :tenantId AND (:appId IS NULL OR r.appId = :appId) ORDER BY r.id")
    List<HandoverRule> findByRuleTypeScoped(@Param("ruleType") HandoverRuleType ruleType,
                                            @Param("tenantId") Long tenantId,
                                            @Param("appId") Long appId);
```

- [ ] **Step 4: 改造 `HandoverRuleService`**

字段加 `private final ConfigScopeResolver configScopeResolver;`。`resolveActiveRule` 改为：
```java
    private java.util.Optional<HandoverRule> resolveActiveRule(HandoverRuleType type) {
        ConfigScopeResolver.Scope scope = configScopeResolver.resolve();
        java.util.Optional<HandoverRule> own = scope.tenantId() == null
                ? java.util.Optional.empty()
                : repository.findByRuleTypeScoped(type, scope.tenantId(), scope.appId()).stream().findFirst();
        return own.isPresent() ? own : repository.findPlatformDefaultByRuleType(type.name());
    }
```

- [ ] **Step 5: 运行单测确认通过**

Run: `cd backend && mvn -q test -Dtest=HandoverTriggerFallbackTest`
Expected: 全绿。

- [ ] **Step 6: 扩展 `HandoverRuleFallbackIT`**

`cat` 该 IT。新增用例：`seedApp`→应用 A(租户 T)；播种「租户 T + 租户 T2 各一条 `rule_type='KEYWORD'` 规则」+ 平台默认(`tenant_id IS NULL`)；设 `TenantContext(tenantId=null, appId=A.id)`；断言 `checkKeywordTrigger(含关键词)` 走**租户 T 的规则**、不抛 NonUnique。（该 IT 有类级 `@Transactional` 令 filter 生效的既有约定，照做。）

- [ ] **Step 7: 运行 IT**

Run: `cd backend && mvn -q verify -Dit.test=HandoverRuleFallbackIT`
Expected: 通过。

- [ ] **Step 8: 全量单测 + 提交**

Run: `cd backend && mvn -q test`
```bash
git add backend/src/main/java/com/qingyu/care/repository/HandoverRuleRepository.java \
        backend/src/main/java/com/qingyu/care/service/HandoverRuleService.java \
        backend/src/test/java/com/qingyu/care/service/HandoverTriggerFallbackTest.java \
        backend/src/test/java/com/qingyu/care/it/HandoverRuleFallbackIT.java
git commit -m "fix: HandoverRule 解析显式按有效作用域收窄，修平台管理员 NonUnique(含 FallbackIT 平台管理员断言)"
```

---

### Task 5: 收尾（全量 verify + 真机复验）

**Files:** 无（仅验证）。

- [ ] **Step 1: 后端全量 verify**

Run: `cd backend && mvn -q verify`（需 Docker Desktop）
Expected: BUILD SUCCESS，单测 + IT 全绿。如实记录 Tests run 数字。

- [ ] **Step 2: 真机复验（若 dev 栈在跑）**

- 平台管理员 `admin`/`admin123` 登录 → 选应用「冒烟应用」(app 3) → 发一句普通对话 → **不再 500**，正常回复。
- 若便捷可测：确认解析用的是 app 3 归属租户(租户 4)的配置（如租户 4 有自有策略则命中之）。
- 无 dev 栈则跳过并注明（契约层已 IT 覆盖）。

- [ ] **Step 3: 登记留债/说明**

如实记录：`getNoHitStreakThreshold` 生产无调用方（既有死代码，未新增，不在本次范围）；HandoverRule 租户自有行路径当前无 create 接口（结构性收口已完成，产品面不扩）。

## Self-Review

**1. Spec coverage：** §3.1 ConfigScopeResolver → T1；§3.2 Application 反查 → T1；§3.3 三 repository scoped 查询 → T2/T3/T4 各 Step3；§3.4 三服务改造 → T2/T3/T4 各 Step4；§4 单测 → 各 Step1、IT → 各 Step6；§2 零回归 → Global Constraints + 各 Step8 全量 test。无遗漏。
**2. Placeholder scan：** 无 TBD；resolver/三 repository 查询/三服务改造/单测均给完整代码；IT 用例给了 seed 要素与断言要求（须照既有 IT 风格落地，非模糊——明确「验解析到租户 T 而非只验不抛」）。
**3. Type consistency：** `ConfigScopeResolver.resolve() -> Scope(Long tenantId, Long appId)`；`findActiveDefaultScoped(String,Long,Long)->List`、`findActiveScoped(Long,Long)->List`、`findByRuleTypeScoped(HandoverRuleType,Long,Long)->List`、`findTenantIdById(Long)->Optional<Long>` 前后一致。
**⚠️ 跨切**：三个服务构造新增 `ConfigScopeResolver` 依赖 → 若有测试直接 `new XxxService(...)` 须补参；Spring 注入的使用方（如 `ChatServiceImpl`）不受影响。各 Task Step8 全量 `mvn test` 兜底捕获。

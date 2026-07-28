# 转人工触发规则平台默认回退修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `HandoverRuleService` 的三个运行时触发读取（关键词/低置信/连续未命中）能看到 `tenant_id IS NULL` 的平台默认规则，闭合「转人工等触发对所有租户请求静默失效」缺陷。

**Architecture:** 复刻同 service `getAllRules` 已有的 native 平台默认回退范式——仓库加按类型的 native 查询，服务抽 `resolveActiveRule(type)`（租户自有 `findByRuleType` ∪ 回退平台默认 native），三个触发方法改用它；`.filter(enabled)`/config 解析/`orElse` 安全兜底全部保留。

**Tech Stack:** Spring Data JPA（native `@Query`）+ Hibernate `@Filter` 多租户 / JUnit5 + Mockito 单测 / Testcontainers pgvector IT。

## Global Constraints

- 全部简体中文：代码注释、commit、文档。作者署名 `ruan`。
- 本项目允许 `git commit`，仅在特性分支 `fix/handover-trigger-fallback` 上；未获授权**不 push**。
- 单元测试纯 Mockito（不启 Spring、不需 Docker）；集成测试类名以 `IT` 结尾、继承 `com.qingyu.care.support.AbstractPostgresIT`（需 Docker Desktop）。
- **只补「看得见平台默认」，不改任何 fail-soft 兜底语义**（停用/缺失/坏 config → 安全默认：keyword=false、lowConf=false、noHit=`MAX_VALUE`）。
- 不加租户建自有规则的 create 入口；不动 `getAllRules`/`updateRule`/`getById`（已正确）；不改 `checkKeywordTrigger` 的 `contains` 匹配语义。
- 既有事实：`handover_rules.rule_type` 列存**字符串**（枚举 name，如 `KEYWORD`，DB 已验）；`HandoverRule extends BaseTenantEntity`（受 `tenant_id` 过滤器）；`HandoverRuleService` 构造为 `@RequiredArgsConstructor(HandoverRuleRepository repository, ObjectMapper objectMapper)`；仓库已有 `findByRuleType`(Optional,filter-bound)/`findAllPlatformDefaults`(native)。

---

## 文件结构
- 改 `repository/HandoverRuleRepository.java`：加 `findPlatformDefaultByRuleType(String)` native。
- 改 `service/HandoverRuleService.java`：加私有 `resolveActiveRule(HandoverRuleType)`，三个触发方法改用它。
- 新增 `service/HandoverTriggerFallbackTest.java`：单测（纯 Mockito + 真 ObjectMapper）。
- 改 `it/HandoverRuleFallbackIT.java`：断言从「不抛/false」改为「租户上下文经平台默认正确触发」，并重写把 bug 当预期的 javadoc。

---

### Task 1: 平台默认回退（仓库 native + 服务 resolver + 三处替换 + 单测）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/HandoverRuleRepository.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/HandoverRuleService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/HandoverTriggerFallbackTest.java`

**Interfaces:**
- Produces：
  - `HandoverRuleRepository.findPlatformDefaultByRuleType(String ruleType) -> Optional<HandoverRule>`（native，`WHERE rule_type=? AND tenant_id IS NULL LIMIT 1`）
  - `HandoverRuleService` 私有 `resolveActiveRule(HandoverRuleType type) -> Optional<HandoverRule>`

- [ ] **Step 1：写单测 `HandoverTriggerFallbackTest`（先失败）**

```java
package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.HandoverRule;
import com.qingyu.care.enums.HandoverRuleType;
import com.qingyu.care.repository.HandoverRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转人工触发规则平台默认回退单测：租户上下文下 findByRuleType 被 @Filter 挡空时，
 * 三个触发方法须经 native 平台默认回退正确触发（原 bug：静默不触发）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class HandoverTriggerFallbackTest {

    @Mock HandoverRuleRepository repository;
    HandoverRuleService service;

    @BeforeEach
    void setUp() {
        // 用真 ObjectMapper 解析 config（服务内部 readTree），只 mock 仓库
        service = new HandoverRuleService(repository, new ObjectMapper());
    }

    private HandoverRule rule(HandoverRuleType type, boolean enabled, String config) {
        return HandoverRule.builder().ruleType(type).name("t").enabled(enabled).config(config).build();
    }

    @Test
    void 自有空_回退平台默认_关键词触发() {
        when(repository.findByRuleType(HandoverRuleType.KEYWORD)).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultByRuleType("KEYWORD"))
                .thenReturn(Optional.of(rule(HandoverRuleType.KEYWORD, true, "{\"keywords\":[\"转人工\"]}")));

        assertThat(service.checkKeywordTrigger("我要转人工")).isTrue();
    }

    @Test
    void 自有存在_用自有_不查平台默认() {
        when(repository.findByRuleType(HandoverRuleType.KEYWORD))
                .thenReturn(Optional.of(rule(HandoverRuleType.KEYWORD, true, "{\"keywords\":[\"人工客服\"]}")));

        assertThat(service.checkKeywordTrigger("请转人工客服")).isTrue();
        verify(repository, never()).findPlatformDefaultByRuleType(any());
    }

    @Test
    void 平台默认停用_安全默认不触发() {
        when(repository.findByRuleType(HandoverRuleType.KEYWORD)).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultByRuleType("KEYWORD"))
                .thenReturn(Optional.of(rule(HandoverRuleType.KEYWORD, false, "{\"keywords\":[\"转人工\"]}")));

        assertThat(service.checkKeywordTrigger("我要转人工")).isFalse();
    }

    @Test
    void 自有空_回退平台默认_低置信触发() {
        when(repository.findByRuleType(HandoverRuleType.LOW_CONFIDENCE)).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultByRuleType("LOW_CONFIDENCE"))
                .thenReturn(Optional.of(rule(HandoverRuleType.LOW_CONFIDENCE, true, "{\"threshold\":0.3}")));

        assertThat(service.checkLowConfidenceTrigger(0.1)).isTrue();
    }

    @Test
    void 自有空_回退平台默认_连续未命中阈值() {
        when(repository.findByRuleType(HandoverRuleType.NO_HIT_STREAK)).thenReturn(Optional.empty());
        when(repository.findPlatformDefaultByRuleType("NO_HIT_STREAK"))
                .thenReturn(Optional.of(rule(HandoverRuleType.NO_HIT_STREAK, true, "{\"count\":3}")));

        assertThat(service.getNoHitStreakThreshold()).isEqualTo(3);
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=HandoverTriggerFallbackTest`
Expected: 编译失败（`findPlatformDefaultByRuleType` 不存在）或断言失败（当前三方法只查 `findByRuleType`，mock 的平台默认从不被消费 → keyword=false 等）。

- [ ] **Step 3：仓库加 native 平台默认按类型查询**

在 `HandoverRuleRepository.java` 的 `findAllPlatformDefaults()` 之后加（补 `import org.springframework.data.repository.query.Param;`）：

```java
    /**
     * 按类型取平台默认规则（tenant_id IS NULL，绕过 Hibernate @Filter）；仿 findAllPlatformDefaults。
     * rule_type 列存枚举 name 字符串，故传 HandoverRuleType.name()。
     */
    @Query(value = "SELECT * FROM handover_rules WHERE rule_type = :ruleType AND tenant_id IS NULL LIMIT 1",
            nativeQuery = true)
    Optional<HandoverRule> findPlatformDefaultByRuleType(@Param("ruleType") String ruleType);
```

- [ ] **Step 4：服务加 resolver + 三处替换**

在 `HandoverRuleService.java` 加私有方法（放在三个触发方法附近）：

```java
    /**
     * 解析生效规则：租户自有优先（filter-bound findByRuleType 只命中当前租户行），
     * 无自有则回退平台默认（native 绕过 @Filter 取 tenant_id IS NULL）。
     * 闭合「平台默认被多租户过滤器挡掉致触发对租户静默失效」缺陷（同 getAllRules 范式）。
     */
    private java.util.Optional<HandoverRule> resolveActiveRule(HandoverRuleType type) {
        java.util.Optional<HandoverRule> own = repository.findByRuleType(type);
        return own.isPresent() ? own : repository.findPlatformDefaultByRuleType(type.name());
    }
```

把三个触发方法开头的 `repository.findByRuleType(...)` 替换为 `resolveActiveRule(...)`（其后 `.filter(HandoverRule::getEnabled)`、config 解析、`.orElse(...)` 全部不变）：
- `checkKeywordTrigger`（约 137 行）：`repository.findByRuleType(HandoverRuleType.KEYWORD)` → `resolveActiveRule(HandoverRuleType.KEYWORD)`
- `checkLowConfidenceTrigger`（约 163 行）：`repository.findByRuleType(HandoverRuleType.LOW_CONFIDENCE)` → `resolveActiveRule(HandoverRuleType.LOW_CONFIDENCE)`
- `getNoHitStreakThreshold`（约 185 行）：`repository.findByRuleType(HandoverRuleType.NO_HIT_STREAK)` → `resolveActiveRule(HandoverRuleType.NO_HIT_STREAK)`

- [ ] **Step 5：运行确认通过 + 全量单测**

Run: `cd backend && mvn -q test -Dtest=HandoverTriggerFallbackTest`
Expected: PASS（5/5）。
再跑 `cd backend && mvn -q test` 确认全量单测无回归。

- [ ] **Step 6：提交**

```bash
cd backend && git add src/main/java/com/qingyu/care/repository/HandoverRuleRepository.java \
  src/main/java/com/qingyu/care/service/HandoverRuleService.java \
  src/test/java/com/qingyu/care/service/HandoverTriggerFallbackTest.java
git commit -m "fix: 转人工触发规则回退平台默认（native 绕过多租户过滤器），闭合触发对租户静默失效"
```

---

### Task 2: 修 `HandoverRuleFallbackIT`（断言正确触发，推翻把 bug 当预期的旧结论）

**Files:**
- Modify: `backend/src/test/java/com/qingyu/care/it/HandoverRuleFallbackIT.java`

**Interfaces:**
- Consumes：Task 1 的 `resolveActiveRule` 平台默认回退（经 `HandoverRuleService` 触发方法）、`JdbcTemplate`（播种平台默认）。

- [ ] **Step 1：整体重写 `HandoverRuleFallbackIT`**

把该 IT 整体替换为下面内容——确定性 native 播种 `tenant_id IS NULL` 平台默认规则（不依赖 `@PostConstruct` 的 `count()==0` 时序），租户上下文下断言三类触发**正确触发**（原断言「不抛/false」把 bug 当预期，故一并推翻并重写 javadoc）：

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转人工触发规则平台默认回退（真库）：验证租户上下文下，三个运行时触发读取
 * （关键词/低置信/连续未命中）能经 native 回退看到 tenant_id IS NULL 的平台默认规则、
 * 正确触发——而非被多租户 @Filter 挡掉后静默降级为「不触发」。
 *
 * 背景：此前审计曾把「租户上下文查不到平台默认 → 不触发」当作合理优雅降级、判定无需修复；
 * 但真机冒烟证明这使「转人工」关键词对所有租户从不生效（打转人工不转接、落 AI）。故本 IT
 * 断言修正后的正确行为，作为该缺陷的回归网（原 IT 只测「不抛异常」，测了安全降级而非正确触发）。
 *
 * @author ruan
 */
class HandoverRuleFallbackIT extends AbstractPostgresIT {

    private static final String MARK = "IT回退平台默认";

    @Autowired
    private HandoverRuleService handoverRuleService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** native 播种一条 tenant_id IS NULL 平台默认规则（绕过 JPA 的 TenantEntityListener 回填）。 */
    private void seedPlatformDefault(String ruleType, String config) {
        jdbcTemplate.update(
                "INSERT INTO handover_rules(rule_type, name, description, enabled, config, priority, " +
                        "tenant_id, app_id, created_at, updated_at) " +
                        "VALUES (?, ?, 'it', true, ?::text, 100, NULL, NULL, now(), now())",
                ruleType, MARK + ruleType, config);
    }

    @BeforeEach
    void seed() {
        seedPlatformDefault("KEYWORD", "{\"keywords\": [\"转人工\"]}");
        seedPlatformDefault("LOW_CONFIDENCE", "{\"threshold\": 0.3}");
        seedPlatformDefault("NO_HIT_STREAK", "{\"count\": 3}");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbcTemplate.update("DELETE FROM handover_rules WHERE name LIKE ?", MARK + "%");
    }

    @Test
    void 租户上下文_关键词经平台默认正确触发() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        assertThat(handoverRuleService.checkKeywordTrigger("我要转人工")).isTrue();
    }

    @Test
    void 租户上下文_低置信经平台默认正确触发() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        assertThat(handoverRuleService.checkLowConfidenceTrigger(0.1)).isTrue();
    }

    @Test
    void 租户上下文_连续未命中阈值经平台默认为3而非禁用() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        assertThat(handoverRuleService.getNoHitStreakThreshold()).isEqualTo(3);
    }
}
```

> 说明：本 IT 用 `TenantContext` 直接设租户上下文（不经真实 HTTP），触发方法内 `findByRuleType` 走 `repository..*` 会被 `TenantDataAspect` 启用 `tenantFilter`（tenant_id=90301）→ 命中不到播种的 NULL 平台默认 → 经 `resolveActiveRule` 的 native 回退命中 → 正确触发。`AfterEach` 按 `MARK` 前缀名删除，只清本 IT 播种、不动 `@PostConstruct` 平台默认，避免污染共享容器其它 IT。

- [ ] **Step 2：运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=HandoverRuleFallbackIT -DfailIfNoTests=false`
Expected: PASS（3/3，需 Docker）。

- [ ] **Step 3：提交**

```bash
cd backend && git add src/test/java/com/qingyu/care/it/HandoverRuleFallbackIT.java
git commit -m "test: HandoverRuleFallbackIT 断言平台默认经回退正确触发（推翻把 bug 当预期的旧结论）"
```

---

### Task 3: 全量回归

- [ ] **Step 1：全量 verify**

Run: `cd backend && mvn -q verify`
Expected: BUILD SUCCESS，全部单测 + IT 绿。

- [ ] **Step 2：留债登记**
真机 re-smoke（打「转人工」应转接而非落 AI）待 DashScope 额度恢复复验。

## Self-Review

**1. Spec coverage：** spec §2.1 仓库 native → Task 1 Step 3；§2.2 resolver + 三处替换 → Task 1 Step 4；§4 单测 → Task 1 Step 1；§4 IT 修（断言正确触发 + 确定性播种）→ Task 2；§5 留债 → Task 3。无遗漏。
**2. Placeholder scan：** 无 TBD；每改码步给完整代码与确切命令。
**3. Type consistency：** `findPlatformDefaultByRuleType(String)→Optional<HandoverRule>`、`resolveActiveRule(HandoverRuleType)→Optional<HandoverRule>`、传参 `type.name()`（String）、`HandoverRule.builder().ruleType/name/enabled/config`、`HandoverRuleService(repository, objectMapper)` 构造前后一致；事件/枚举名 KEYWORD/LOW_CONFIDENCE/NO_HIT_STREAK 一致。

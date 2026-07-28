# 转人工触发规则平台默认回退（fail-soft 修复）设计

- 日期：2026-07-28
- 分支：`fix/handover-trigger-fallback`
- 作者：ruan
- 来源：真人坐席真机冒烟发现——打「转人工」不转接、落到 LLM（额度 403）返 500。

## 1. 背景与根因（已由 smoke + DB + 代码坐实）

`HandoverRuleService` 的三个**运行时触发读取**方法都用 filter-bound JPA 查规则：
- `checkKeywordTrigger(message)` → `repository.findByRuleType(KEYWORD)`（`HandoverRuleService.java:137`）
- `checkLowConfidenceTrigger(confidence)` → `repository.findByRuleType(LOW_CONFIDENCE)`（:163）
- `getNoHitStreakThreshold()` → `repository.findByRuleType(NO_HIT_STREAK)`（:185）

`findByRuleType` 是普通 JPA 查询，被 `TenantDataAspect` 的多租户 Hibernate `@Filter`（`tenant_id = :当前租户`）约束。而 `handover_rules` 表里的规则是 `@PostConstruct init()` 播种的**平台默认**（`tenant_id IS NULL`，`:39-66`），且**目前没有任何租户建自有规则的入口**（本 service 只有 `updateRule`，无 create）。

后果：租户上下文（如开放 API 请求，`tenantId` 非空）下，`findByRuleType` 因过滤器 `tenant_id = 5` 匹配不到 `tenant_id IS NULL` 的平台默认行 → 返回空 → `.orElse(false)`/`.orElse(MAX_VALUE)` → **三类触发对所有租户请求静默失效**。用户打「转人工」不转接、继续走 AI（冒烟时因 DashScope 额度耗尽 → 500）。

这正是 CLAUDE.md 明写的「多级配置回退」陷阱：**平台默认由 `@PostConstruct` 播种为 `tenant_id IS NULL`，会撞多租户过滤器，回退平台默认必须用原生 SQL 绕过 Hibernate 过滤器**。同 service 的 `getAllRules`（`:78-83`）已用 `repository.findAllPlatformDefaults()`（native）正确处理，但这三个运行时方法漏了。

**测试为何没拦住**：`HandoverRuleFallbackIT` 现有用例断言的是三个方法在租户上下文下**「不抛异常 / `orElse` 兜底」**（注释「规则不存在时安全返回 false」）——即测了「不崩」而非「正确触发」，把 bug 当成了预期行为。

## 2. 方案（复刻 `getAllRules` 的 native 回退范式，最小改动）

### 2.1 仓库
`HandoverRuleRepository` 加按类型的平台默认 native 查询（仿既有 `findAllPlatformDefaults`）：
```java
@Query(value = "SELECT * FROM handover_rules WHERE rule_type = :ruleType AND tenant_id IS NULL LIMIT 1",
       nativeQuery = true)
Optional<HandoverRule> findPlatformDefaultByRuleType(@Param("ruleType") String ruleType);
```

### 2.2 服务
抽私有解析器，三个触发方法改用它替换 `repository.findByRuleType(type)`：
```java
/** 解析生效规则：租户自有优先（filter-bound findByRuleType 只命中当前租户行），
 *  无自有则回退平台默认（native 绕过 @Filter 取 tenant_id IS NULL）。 */
private Optional<HandoverRule> resolveActiveRule(HandoverRuleType type) {
    Optional<HandoverRule> own = repository.findByRuleType(type);
    return own.isPresent() ? own : repository.findPlatformDefaultByRuleType(type.name());
}
```
- `checkKeywordTrigger`/`checkLowConfidenceTrigger`/`getNoHitStreakThreshold` 把开头的 `repository.findByRuleType(X)` 换成 `resolveActiveRule(X)`；其后的 `.filter(HandoverRule::getEnabled)` + config 解析 + `.orElse(...)` 安全兜底**全部保留不变**（fail-soft 语义、停用/缺失/坏 config 的安全默认均不变）。

### 2.3 语义
租户自有规则（未来若开放 create）→ 平台默认；缺失/停用/config 解析失败 → 安全默认（keyword=false、lowConf=false、noHit=`MAX_VALUE` 即不触发）。修复只补「看得见平台默认」，不改任何兜底语义。

## 3. 范围
- IN：`HandoverRuleRepository` +1 native 方法；`HandoverRuleService` +1 私有 resolver + 3 处替换；单测；修 `HandoverRuleFallbackIT`。
- OUT（YAGNI）：不加租户建自有规则的 create 入口；不动 `getAllRules`/`updateRule`/`getById`（已正确）；不改 `checkKeywordTrigger` 的 `contains` 匹配语义；不碰 DashScope 额度（外部账户）。
- 平台管理员（`tenantId` null，`@Filter` 整体不启用）路径：`findByRuleType` 无过滤，当前每类型仅一条平台默认 → Optional 正常返回；未来若多租户各建同类型规则会 NonUniqueResult，属既有 latent、本次不处理（当前无 create 入口，不可达）。

## 4. 测试
- **单测（纯 Mockito，`HandoverTriggerFallbackTest`）**：
  - 自有空 + 平台默认存在（mock `findByRuleType`→empty、`findPlatformDefaultByRuleType`→平台默认规则）→ `checkKeywordTrigger("转人工")=true`、`checkLowConfidenceTrigger(0.1)=true`（threshold 0.3）、`getNoHitStreakThreshold()=3`。
  - 自有存在 → 用自有（`findByRuleType` 返回自有规则时不查平台默认，`verify(repository, never()).findPlatformDefaultByRuleType(...)`）。
  - 平台默认停用（enabled=false）→ 仍安全默认（keyword=false / noHit=MAX）。
- **IT（修 `HandoverRuleFallbackIT`，真库）**：把「不抛/false」断言改为「租户上下文下经平台默认**正确触发**」——确定性播种一条 `tenant_id IS NULL` 平台默认 KEYWORD/LOW_CONFIDENCE/NO_HIT_STREAK 规则（native insert，不依赖 `@PostConstruct` 的 `count()==0` 时序），设 `TenantContext` 租户上下文，断言 `checkKeywordTrigger("转人工")=true`、`checkLowConfidenceTrigger(0.1)=true`、`getNoHitStreakThreshold()==3`。这个 IT 改动本身即本该拦住 bug 的回归网（原 IT 测了安全降级、没测正确行为）。

## 5. 已知局限
真机 re-smoke（打「转人工」应转接而非落 AI）待 DashScope 额度恢复后复验完整链路；本次以单测 + IT 覆盖修复正确性。

# 多级配置回退·平台管理员单结果查询收口设计

- 日期：2026-07-29
- 分支：`fix/config-resolution-platform-scope`
- 作者：ruan
- 来源：结构化卡片 SP1 真机冒烟时发现——平台管理员用应用聊天，后端 500 `NonUniqueResultException`（`ChatStrategyService.getActiveStrategy`）。

## 1. 背景与根因

多租户隔离靠 Hibernate `@Filter`（`tenant_id = :tenantId` + `app_id = :appId`），由 `TenantDataAspect` 在 repository 调用前按 `TenantContext` 启用。**但对 PLATFORM_ADMIN（及未登录）整体不启用过滤器**（`TenantDataAspect:49`）。平台管理员登录后 `TenantContext.getTenantId()` 恒为 `null`。

「多级配置回退」四类实体（`ChatStrategy` / `PromptTemplate` / `AiModelConfig` / `HandoverRule`）解析「当前生效配置」时，靠 `@Filter` 把 `Optional<X> findBy...` 单结果查询收窄到唯一行。平台管理员无过滤器 → 裸扫全库 → **≥2 个租户各有同键行时 `getSingleResult()` 抛 `NonUniqueResultException`**（Spring 包 `IncorrectResultSizeDataAccessException` → HTTP 500）。

### 爆炸半径（已勘查确认）
三处高危单结果查询，**全在 `chat()`/`streamChat()` 热路径**：

| 服务方法 | 依赖 @Filter 的查询 | chat() 触点 | 触发门槛 |
|---|---|---|---|
| `ChatStrategyService.getActiveStrategy(scenario)` | `findByScenarioAndEnabledTrueAndIsDefaultTrue`（含 step1 场景 + step3 "default"） | `resolveStrategy`（非 A/B 流量必经） | ≥2 租户同 scenario 各有默认策略 |
| `PromptTemplateService.getActiveTemplate()` | `findByIsActiveTrue`（**无 scenario 键，最脆**） | `resolveSystemTemplate`（默认种子策略未绑模板即必经） | ≥2 租户各激活过一次模板 |
| `HandoverRuleService.resolveActiveRule(type)` | `findByRuleType`（KEYWORD/LOW_CONFIDENCE 两触点） | `checkKeywordTrigger`（chat 第一步）/`checkLowConfidenceTrigger` | 当前无 create 接口，租户自有行路径未打开；但同代码模式，有 `HandoverRuleFallbackIT` 实测崩溃先例、且种子重复即复现，结构性高危一并收口 |

**`ModelRouterService` 免疫**（`AiModelConfigRepository` 全 `List` 语义，多行只是多候选、不抛异常）——是本次修复参照的正确范式。

## 2. 修复目标与决策

- **消除 500**：三处解析改为确定性单行、不再抛 NonUnique。
- **平台管理员语义（已拍板）**：平台管理员用某应用（`X-App-Id`）聊天时，配置**解析为该应用所属租户的配置**（以该应用身份测试，与该应用真实用户所见一致）。
- 顺带堵住潜在越权：当前若恰有 1 个租户有配置，平台管理员不崩但会**串到那家租户的配置**——显式收窄后消除。
- **正常租户 / 开放 API 零回归**：它们 `tenantId != null`，@Filter 已正确收窄，行为逐条件不变。

## 3. 组件设计

### 3.1 `ConfigScopeResolver`（新，`service/`，`@Component`）
解析配置查询的**有效 (tenantId, appId) 范围**——单一来源，供三个配置服务共用：
```java
@Component @RequiredArgsConstructor
public class ConfigScopeResolver {
    private final ApplicationRepository applicationRepository;
    /**
     * 有租户上下文(普通租户/开放API)直接用之；
     * 平台管理员(tenantId=null)由当前应用 X-App-Id 反查其归属租户，
     * 使配置解析到该应用真实使用的那一份（与该应用租户用户一致）。
     */
    public Scope resolve() {
        Long appId = TenantContext.getAppId();
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null && appId != null) {
            tenantId = applicationRepository.findTenantIdById(appId).orElse(null);
        }
        return new Scope(tenantId, appId);
    }
    public record Scope(Long tenantId, Long appId) {}
}
```

### 3.2 `ApplicationRepository` 新增反查（native 取 FK，避开 lazy Tenant）
```java
@Query(value = "SELECT tenant_id FROM sys_application WHERE id = :appId", nativeQuery = true)
Optional<Long> findTenantIdById(@Param("appId") Long appId);
```
（`Application` 用 `@ManyToOne Tenant tenant`，无 tenantId 列；native 直取 FK，且平台管理员/未登录态 @Filter 本就不启用。）

### 3.3 三处解析：@Filter 依赖的 `Optional` 派生查询 → 显式按 Scope 收窄的 JPQL（`List` + findFirst）
每个 repository 新增一个显式作用域查询，返回 `List`（杜绝 NonUnique）、`ORDER BY id` 确定性、复用本仓已验证的 `:appId IS NULL OR app_id = :appId` 惯用法（见 `PromptTemplateRepository.deactivateAllForTenant:60`）：

- **ChatStrategyRepository**
  ```java
  @Query("SELECT s FROM ChatStrategy s WHERE s.scenario = :scenario AND s.enabled = true AND s.isDefault = true "
       + "AND s.tenantId = :tenantId AND (:appId IS NULL OR s.appId = :appId) ORDER BY s.id")
  List<ChatStrategy> findActiveDefaultScoped(@Param("scenario") String scenario,
                                             @Param("tenantId") Long tenantId, @Param("appId") Long appId);
  ```
- **PromptTemplateRepository**
  ```java
  @Query("SELECT t FROM PromptTemplate t WHERE t.isActive = true "
       + "AND t.tenantId = :tenantId AND (:appId IS NULL OR t.appId = :appId) ORDER BY t.id")
  List<PromptTemplate> findActiveScoped(@Param("tenantId") Long tenantId, @Param("appId") Long appId);
  ```
- **HandoverRuleRepository**
  ```java
  @Query("SELECT r FROM HandoverRule r WHERE r.ruleType = :ruleType "
       + "AND r.tenantId = :tenantId AND (:appId IS NULL OR r.appId = :appId) ORDER BY r.id")
  List<HandoverRule> findByRuleTypeScoped(@Param("ruleType") HandoverRuleType ruleType,
                                          @Param("tenantId") Long tenantId, @Param("appId") Long appId);
  ```

> **为何 JPQL 而非 native**：正常租户 `tenantId!=null` → @Filter 仍会叠加（与显式条件同值，冗余无害）；平台管理员无 @Filter，靠显式 `tenant_id = :tenantId` 收窄。JPQL 免去 native 空参数 CAST 隐患，且 `:appId IS NULL OR` 惯用法本仓已证可用。返回 `List`+`findFirst` 从根上杜绝脏数据下的 NonUnique（原 @Filter 遇脏重复也会崩，此举更稳）。

### 3.4 服务改造（回退链形状不变，仅 step「租户自有」显式收窄）
三个服务注入 `ConfigScopeResolver`，把原「@Filter 依赖的自有查询」换成「按 Scope 的 scoped 查询 + findFirst；`tenantId==null` 时跳过（无有效租户 → 直落平台默认）」。示例（ChatStrategy）：
```java
public ChatStrategy getActiveStrategy(String scenario) {
    ConfigScopeResolver.Scope scope = configScopeResolver.resolve();
    return findScopedActiveDefault(scenario, scope)
            .or(() -> repository.findPlatformDefaultByScenario(scenario))   // 平台默认 native，不变
            .or(() -> findScopedActiveDefault("default", scope))
            .orElseGet(() -> /* 运行时静态兜底，不变 */);
}
private Optional<ChatStrategy> findScopedActiveDefault(String scenario, ConfigScopeResolver.Scope scope) {
    if (scope.tenantId() == null) return Optional.empty();  // 无有效租户 → 跳过自有，走平台默认
    return repository.findActiveDefaultScoped(scenario, scope.tenantId(), scope.appId())
            .stream().findFirst();
}
```
`PromptTemplateService.getActiveTemplate`、`HandoverRuleService.resolveActiveRule` 同法（平台默认 native 与 orElseThrow/兜底语义均不变）。

- 平台管理员用 app 3（归属租户 4）：scope=(4,3) → step1 命中租户 4/app3 配置 → 与该应用真实用户一致 ✅。
- 平台管理员无应用（不该发生，前端强制选应用）：scope=(null,·) → 跳过自有 → 平台默认。
- 正常租户：scope=(自身 tenantId, appId) → 与当前 @Filter 逐条件等价 → 零回归。

## 4. 测试

### 单测（纯 Mockito）
- `ConfigScopeResolver`：①`tenantId` 已存在 → 原样返回、**不**查 Application；②`tenantId=null`+`appId` 存在 → 反查 `findTenantIdById` 得租户；③二者皆空 → `(null,null)`。
- 三个服务解析：mock `ConfigScopeResolver` 返 Scope + mock repo，验证——scoped 查询按解析出的 (tenantId, appId) 调用；scoped 空 → 回退平台默认 native → default → 兜底/异常；`tenantId==null` 时**不**调 scoped 查询直落平台默认。既有 `@InjectMocks` 单测补 `@Mock ConfigScopeResolver`（返回既有 TenantContext 对应 Scope，保持原断言）。

### IT（继承 `AbstractPostgresIT`，扩展既有 `*FallbackIT`）
守护本易错链路的 `ChatStrategyFallbackIT`/`PromptTemplateFallbackIT`/`HandoverRuleFallbackIT` 各加一条**平台管理员场景**：
- `seedApp` 播种 app（归属租户 T）；jdbcTemplate/实体播种 **租户 T + 另一租户 T2 各一条同键激活行 + 平台默认（tenant_id NULL）**；
- 设 `TenantContext(tenantId=null, appId=该 app)`（IT 未登录 → 过滤器不启用，精确复现平台管理员「无过滤器」态）；类级 `@Transactional`；
- 断言解析结果 == **租户 T 的行**（Option B 语义）、**不抛 NonUnique**、不误取 T2 或平台默认。
- `ModelRouterDefaultIT` 无需改（ModelRouter 免疫），但保留其绿。

## 5. 明确不做（YAGNI）
- 不改 `@Filter`/`TenantDataAspect` 全局机制（只在三处解析显式收窄，最小面）。
- 不给 `HandoverRule` 补 create 接口（其自有行路径未打开；本次仅统一收口解析，不扩产品面）。
- 不改平台默认 native、default 场景、静态兜底/异常等回退语义。
- 不动管理面 `getAll*`（已是「自有 ∪ 平台默认 native 去重」正确范式）与 `getById`/写路径守卫。
- 平台管理员「跨应用聚合视图」等更宏大语义不做。

## 6. 分期（一分支 `fix/config-resolution-platform-scope`）
- **Task 1**：`ConfigScopeResolver` + `ApplicationRepository.findTenantIdById` + 单测。
- **Task 2**：`ChatStrategyService` 收口（repo scoped 查询 + 服务改造 + 单测 + 既有单测补 mock + `ChatStrategyFallbackIT` 平台管理员断言）。
- **Task 3**：`PromptTemplateService` 收口（同上 + `PromptTemplateFallbackIT`）。
- **Task 4**：`HandoverRuleService` 收口（同上 + `HandoverRuleFallbackIT`）。
- **Task 5**：收尾——全量 `mvn verify` + 真机复验（平台管理员 admin 登录用 app 3 聊天不再 500、拿到租户 4 配置）。

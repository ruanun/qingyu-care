# 计费与套餐额度 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给开放 API 加租户级月度用量额度（调用+token 双维度），超额拦截、接近阈值告警，套餐额度平台可配。

**Architecture:** 新增 `QuotaService`（注入 `ConfigService`/`TenantRepository`/`SysApiUsageRepository`）解析套餐额度（平台配置 + 内置默认）、按租户当月累计 `sys_api_usage`、返回检查结果。`OpenApiAuthInterceptor` 在限流后调 `QuotaService.check`：超额返回 403 `QUOTA_EXCEEDED`、近阈值加 `X-Quota-Usage` 头 + 审计告警。`BillingController` 暴露 `GET /v1/billing/quota` 供租户查看，前端在用量看板加额度卡片。额度检查是开放 API 专属（内部管理端不受限），月度自然重置（免定时任务），组件异常 fail-open。

**Tech Stack:** Spring Boot 3 / Spring Data JPA / Sa-Token / PostgreSQL；React 19 + TS；测试 Mockito（单测）+ Testcontainers（IT）。

---

## Task 1: 额度 DTO/投影 + 当月累计查询

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/QuotaCheckResult.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/QuotaViewDTO.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/projection/QuotaUsageRow.java`
- Modify: `backend/src/main/java/com/qingyu/care/repository/SysApiUsageRepository.java`
- Test: `backend/src/test/java/com/qingyu/care/it/BillingRepositoryIT.java`

- [ ] **Step 1: 创建 DTO 与投影**

`QuotaCheckResult.java`：
```java
package com.qingyu.care.dto;

/** 额度检查结果。limit=0 表示该维度不限。 */
public record QuotaCheckResult(
        boolean exceeded,
        boolean nearThreshold,
        long callsUsed, long callsLimit,
        long tokensUsed, long tokensLimit) {
}
```

`QuotaViewDTO.java`：
```java
package com.qingyu.care.dto;

import java.time.LocalDate;

/** 租户额度视图。plan=null 表示无租户上下文（平台管理员）。 */
public record QuotaViewDTO(
        String plan,
        long callsUsed, long callsLimit,
        long tokensUsed, long tokensLimit,
        int warnThreshold, LocalDate resetDate) {

    public static QuotaViewDTO empty() {
        return new QuotaViewDTO(null, 0, 0, 0, 0, 0, null);
    }
}
```

`QuotaUsageRow.java`：
```java
package com.qingyu.care.repository.projection;

/** 租户当月用量累计。 */
public interface QuotaUsageRow {
    long getCalls();
    long getTokens();
}
```

- [ ] **Step 2: 写失败的仓库 IT**

`BillingRepositoryIT.java`：
```java
package com.qingyu.care.it;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.projection.QuotaUsageRow;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 租户当月用量累计：只统计当月、按租户隔离。@Transactional 回滚 seed。 */
@Transactional
class BillingRepositoryIT extends AbstractPostgresIT {

    private static final Long T1 = 80001L, T2 = 80002L, A1 = 80011L;

    @Autowired SysApiUsageRepository usageRepository;

    private void seed(Long tenant, Long app, LocalDate date, long calls, long tokens) {
        usageRepository.save(SysApiUsage.builder()
                .tenantId(tenant).appId(app).usageDate(date)
                .callCount(calls).messageCount(calls).tokenCount(tokens).build());
    }

    @Test
    void 当月累计_只算本月本租户() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        seed(T1, A1, LocalDate.now(), 5, 50);
        seed(T1, 80012L, LocalDate.now(), 3, 30);       // 同租户另一应用，计入
        seed(T1, A1, monthStart.minusDays(1), 9, 90);   // 上月，不计入
        seed(T2, 80021L, LocalDate.now(), 7, 70);       // 他租户，不计入

        QuotaUsageRow row = usageRepository.sumSince(T1, monthStart);
        assertThat(row.getCalls()).isEqualTo(8);   // 5+3
        assertThat(row.getTokens()).isEqualTo(80); // 50+30
    }

    @Test
    void 无用量时返回0而非null() {
        QuotaUsageRow row = usageRepository.sumSince(T2, LocalDate.now().withDayOfMonth(1));
        assertThat(row.getCalls()).isEqualTo(0);
        assertThat(row.getTokens()).isEqualTo(0);
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=BillingRepositoryIT`
Expected: 编译/测试失败（`sumSince` 不存在）。

- [ ] **Step 4: 给 `SysApiUsageRepository` 加当月累计查询**（在接口末尾、`rankByApp` 之后追加；`QuotaUsageRow` 需 import）

在 import 区加：
```java
import com.qingyu.care.repository.projection.QuotaUsageRow;
```
在接口末尾加：
```java
    /**
     * 租户当月用量累计（calls/tokens）。SysApiUsage 非 filter 实体，手写 where。
     * COALESCE 保证无记录时返回 0 而非 null。
     */
    @Query("""
            SELECT COALESCE(SUM(u.callCount), 0) AS calls,
                   COALESCE(SUM(u.tokenCount), 0) AS tokens
            FROM SysApiUsage u
            WHERE u.tenantId = :tenantId AND u.usageDate >= :monthStart
            """)
    QuotaUsageRow sumSince(@Param("tenantId") Long tenantId, @Param("monthStart") LocalDate monthStart);
```

- [ ] **Step 5: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=BillingRepositoryIT`
Expected: BUILD SUCCESS，2 测试全绿。

- [ ] **Step 6: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/dto/QuotaCheckResult.java backend/src/main/java/com/qingyu/care/dto/QuotaViewDTO.java backend/src/main/java/com/qingyu/care/repository/projection/QuotaUsageRow.java backend/src/main/java/com/qingyu/care/repository/SysApiUsageRepository.java backend/src/test/java/com/qingyu/care/it/BillingRepositoryIT.java
git commit -m "feat: 额度 DTO/投影 + 租户当月用量累计查询"
```

---

## Task 2: QuotaService（解析额度 + 检查 + 视图）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/QuotaService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/QuotaServiceTest.java`

- [ ] **Step 1: 写失败的单测**

`QuotaServiceTest.java`：
```java
package com.qingyu.care.service;

import com.qingyu.care.dto.QuotaCheckResult;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.QuotaUsageRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock ConfigService configService;
    @Mock TenantRepository tenantRepository;
    @Mock SysApiUsageRepository usageRepository;
    @InjectMocks QuotaService service;

    private QuotaUsageRow usage(long calls, long tokens) {
        return new QuotaUsageRow() {
            public long getCalls() { return calls; }
            public long getTokens() { return tokens; }
        };
    }

    private void stubPlan(Long tenantId, TenantPlan plan) {
        when(tenantRepository.findById(tenantId))
                .thenReturn(Optional.of(Tenant.builder().plan(plan).build()));
    }

    private void stubQuota(TenantPlan plan, int calls, int tokens) {
        when(configService.getInt(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota." + plan.name() + ".calls"), anyInt())).thenReturn(calls);
        when(configService.getInt(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota." + plan.name() + ".tokens"), anyInt())).thenReturn(tokens);
    }

    private void stubWarn(int pct) {
        when(configService.getInt(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota.warn-threshold"), anyInt())).thenReturn(pct);
    }

    @Test
    void 未超额_未近阈值() {
        stubPlan(1L, TenantPlan.FREE);
        stubQuota(TenantPlan.FREE, 100, 10000);
        stubWarn(80);
        when(usageRepository.sumSince(eq(1L), any(LocalDate.class))).thenReturn(usage(10, 1000));

        QuotaCheckResult r = service.check(1L);
        assertThat(r.exceeded()).isFalse();
        assertThat(r.nearThreshold()).isFalse();
        assertThat(r.callsUsed()).isEqualTo(10);
        assertThat(r.callsLimit()).isEqualTo(100);
    }

    @Test
    void 调用超额即exceeded() {
        stubPlan(1L, TenantPlan.FREE);
        stubQuota(TenantPlan.FREE, 100, 10000);
        stubWarn(80);
        when(usageRepository.sumSince(eq(1L), any(LocalDate.class))).thenReturn(usage(100, 500));

        assertThat(service.check(1L).exceeded()).isTrue();
    }

    @Test
    void token超额即exceeded() {
        stubPlan(1L, TenantPlan.PRO);
        stubQuota(TenantPlan.PRO, 100000, 1000);
        stubWarn(80);
        when(usageRepository.sumSince(eq(1L), any(LocalDate.class))).thenReturn(usage(5, 1000));

        assertThat(service.check(1L).exceeded()).isTrue();
    }

    @Test
    void 近阈值_放行但标记() {
        stubPlan(1L, TenantPlan.FREE);
        stubQuota(TenantPlan.FREE, 100, 10000);
        stubWarn(80);
        when(usageRepository.sumSince(eq(1L), any(LocalDate.class))).thenReturn(usage(85, 100));

        QuotaCheckResult r = service.check(1L);
        assertThat(r.exceeded()).isFalse();
        assertThat(r.nearThreshold()).isTrue();
    }

    @Test
    void 额度0表示不限_直接放行() {
        stubPlan(1L, TenantPlan.ENTERPRISE);
        stubQuota(TenantPlan.ENTERPRISE, 0, 0);
        // 两维度都不限 → 快路径，不查用量
        QuotaCheckResult r = service.check(1L);
        assertThat(r.exceeded()).isFalse();
        assertThat(r.nearThreshold()).isFalse();
    }

    @Test
    void 检查异常_failOpen放行() {
        when(tenantRepository.findById(1L)).thenThrow(new RuntimeException("db down"));
        QuotaCheckResult r = service.check(1L);
        assertThat(r.exceeded()).isFalse();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test -Dtest=QuotaServiceTest`
Expected: 编译失败（`QuotaService` 不存在）。

- [ ] **Step 3: 实现 `QuotaService`**

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.QuotaCheckResult;
import com.qingyu.care.dto.QuotaViewDTO;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.QuotaUsageRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 套餐额度：解析（平台配置 + 内置默认）+ 租户当月用量检查。
 * 额度=0 表示不限。检查异常 fail-open（放行），不因组件故障拦截业务。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

    /** 内置默认额度 [calls, tokens]；平台未配置时回落。 */
    private static final Map<TenantPlan, long[]> DEFAULTS = Map.of(
            TenantPlan.FREE, new long[]{1000, 100000},
            TenantPlan.PRO, new long[]{50000, 5000000},
            TenantPlan.ENTERPRISE, new long[]{0, 0});
    private static final int DEFAULT_WARN = 80;

    private final ConfigService configService;
    private final TenantRepository tenantRepository;
    private final SysApiUsageRepository usageRepository;

    public QuotaCheckResult check(Long tenantId) {
        try {
            long[] limits = resolveQuota(tenantId);
            long callsLimit = limits[0], tokensLimit = limits[1];
            if (callsLimit <= 0 && tokensLimit <= 0) {
                return new QuotaCheckResult(false, false, 0, callsLimit, 0, tokensLimit);
            }
            QuotaUsageRow used = usageRepository.sumSince(tenantId, monthStart());
            long callsUsed = used.getCalls(), tokensUsed = used.getTokens();
            int warn = configService.getInt(OwnerScope.PLATFORM, null, "billing.quota.warn-threshold", DEFAULT_WARN);
            boolean exceeded = (callsLimit > 0 && callsUsed >= callsLimit)
                    || (tokensLimit > 0 && tokensUsed >= tokensLimit);
            boolean near = !exceeded && (
                    (callsLimit > 0 && callsUsed >= callsLimit * warn / 100.0)
                    || (tokensLimit > 0 && tokensUsed >= tokensLimit * warn / 100.0));
            return new QuotaCheckResult(exceeded, near, callsUsed, callsLimit, tokensUsed, tokensLimit);
        } catch (Exception e) {
            log.warn("额度检查异常，fail-open 放行 tenantId={}", tenantId, e);
            return new QuotaCheckResult(false, false, 0, 0, 0, 0);
        }
    }

    public QuotaViewDTO view(Long tenantId) {
        if (tenantId == null) {
            return QuotaViewDTO.empty();
        }
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return QuotaViewDTO.empty();
        }
        long[] limits = resolveQuota(tenantId);
        QuotaUsageRow used = usageRepository.sumSince(tenantId, monthStart());
        int warn = configService.getInt(OwnerScope.PLATFORM, null, "billing.quota.warn-threshold", DEFAULT_WARN);
        return new QuotaViewDTO(tenant.getPlan().name(),
                used.getCalls(), limits[0], used.getTokens(), limits[1],
                warn, monthStart().plusMonths(1));
    }

    /** 解析 [callsLimit, tokensLimit]；平台配置优先，缺失回落内置默认。 */
    private long[] resolveQuota(Long tenantId) {
        TenantPlan plan = tenantRepository.findById(tenantId)
                .map(Tenant::getPlan).orElse(TenantPlan.FREE);
        long[] def = DEFAULTS.getOrDefault(plan, DEFAULTS.get(TenantPlan.FREE));
        long calls = configService.getInt(OwnerScope.PLATFORM, null,
                "billing.quota." + plan.name() + ".calls", (int) def[0]);
        long tokens = configService.getInt(OwnerScope.PLATFORM, null,
                "billing.quota." + plan.name() + ".tokens", (int) def[1]);
        return new long[]{calls, tokens};
    }

    private LocalDate monthStart() {
        return LocalDate.now().withDayOfMonth(1);
    }
}
```

> `check` 中额度=0 的快路径不查用量库；`view` 总是查（要展示已用量）。`resolveQuota` 在 `check`/`view` 各调一次 `tenantRepository.findById`——可接受（单次主键查询）。

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test -Dtest=QuotaServiceTest`
Expected: BUILD SUCCESS，6 测试全绿。

- [ ] **Step 5: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/service/QuotaService.java backend/src/test/java/com/qingyu/care/service/QuotaServiceTest.java
git commit -m "feat: QuotaService 套餐额度解析与当月用量检查"
```

---

## Task 3: 拦截器额度检查 + 超额响应

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/dto/open/OpenErrorResponse.java`
- Modify: `backend/src/main/java/com/qingyu/care/config/OpenApiAuthInterceptor.java`
- Test: `backend/src/test/java/com/qingyu/care/it/BillingQuotaIT.java`

- [ ] **Step 1: 写失败的端到端 IT**

`BillingQuotaIT.java`：
```java
package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开放 API 额度拦截：FREE 超额 403 QUOTA_EXCEEDED；ENTERPRISE(0) 不限放行。
 * 额度检查是开放 API 专属。手动清理 seed + 平台配置。
 *
 * @author ruan
 */
class BillingQuotaIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired SysApiUsageRepository usageRepository;
    @Autowired ConfigService configService;

    @MockitoBean ModelRouterService modelRouterService;

    private Application freeApp, entApp;

    @AfterEach
    void cleanup() {
        for (Application a : new Application[]{freeApp, entApp}) {
            if (a == null) continue;
            usageRepository.findAll().stream()
                    .filter(u -> a.getId().equals(u.getAppId()))
                    .forEach(usageRepository::delete);
        }
        // 清理本测试写入的平台额度配置（避免污染其它 IT）
        configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "", null);
    }

    private void seedUsage(Application app, long calls) {
        usageRepository.save(SysApiUsage.builder()
                .tenantId(app.getTenant().getId()).appId(app.getId())
                .usageDate(LocalDate.now()).callCount(calls).messageCount(calls).tokenCount(0).build());
    }

    @Test
    void FREE超额返回403_未超额放行() throws Exception {
        freeApp = seedApp("quota_free", "http://it.example.com"); // 租户默认 FREE
        configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "3", "IT");
        Mockito.when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("OK", 1));

        // 当月已用 2 < 3 → 放行 200
        seedUsage(freeApp, 2);
        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", freeApp.getAppKey()).header("X-Api-Key", freeApp.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        // 追加到 3（达额度）→ 403 QUOTA_EXCEEDED
        seedUsage(freeApp, 1);
        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", freeApp.getAppKey()).header("X-Api-Key", freeApp.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.reason").value("QUOTA_EXCEEDED"));
    }

    @Test
    void ENTERPRISE额度0不限_高用量仍放行() throws Exception {
        Tenant ent = tenantRepository.save(Tenant.builder()
                .name("企业IT").code("QUOTA_ENT")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.ENTERPRISE).build());
        entApp = new Application();
        entApp.setName("ent"); entApp.setTenant(ent);
        entApp.setAppKey("quota_ent_key"); entApp.setAppSecret("quota_ent_sec");
        entApp.setEnabled(true); entApp.setAllowedOrigins("http://it.example.com");
        entApp = applicationRepository.save(entApp);

        Mockito.when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("OK", 1));
        seedUsage(entApp, 999999); // 用量很高，但 ENTERPRISE 额度=0（不限）

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", entApp.getAppKey()).header("X-Api-Key", entApp.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        applicationRepository.delete(entApp);
        tenantRepository.delete(ent);
        entApp = null;
    }
}
```

> 说明：`seedApp` 的租户默认 `FREE`（见 `AbstractPostgresIT`）。`@AfterEach` 把 `billing.quota.FREE.calls` 置空恢复默认，避免污染其它跑 FREE 对话的 IT。`ENTERPRISE` 分支用完即删应用/租户。

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=BillingQuotaIT`
Expected: 失败（超额未拦截 / `$.error.reason` 不存在）。

- [ ] **Step 3: 给 `OpenErrorResponse` 加可空 `reason`**

```java
package com.qingyu.care.dto.open;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 开放 API 统一错误体：{ "error": { "code": 401, "message": "...", "reason": "..."? } }
 * reason 为可空业务标识（如 QUOTA_EXCEEDED），供客户端区分同状态码下的不同原因。
 */
@Data
@RequiredArgsConstructor
public class OpenErrorResponse {
    private final Error error;

    @Data
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Error {
        private int code;
        private String message;
        private String reason;
    }

    public static OpenErrorResponse of(int code, String message) {
        return new OpenErrorResponse(new Error(code, message, null));
    }

    public static OpenErrorResponse of(int code, String message, String reason) {
        return new OpenErrorResponse(new Error(code, message, reason));
    }
}
```

- [ ] **Step 4: 给 `OpenApiAuthInterceptor` 插额度检查**

在字段区加两个依赖（`@RequiredArgsConstructor` 自动注入 final 字段）：
```java
    private final com.qingyu.care.service.QuotaService quotaService;
    private final com.qingyu.care.service.AuditLogService auditLogService;
```
在限流检查通过之后（`return true;` 之前）插入：
```java
            // 额度检查（开放 API 专属）：超额 403，近阈值放行 + 软提示 + 审计
            com.qingyu.care.dto.QuotaCheckResult quota = quotaService.check(app.getTenant().getId());
            if (quota.exceeded()) {
                TenantContext.clear();
                writeError(response, 403, "本月额度已用尽，请升级套餐", "QUOTA_EXCEEDED");
                return false;
            }
            if (quota.nearThreshold()) {
                response.setHeader("X-Quota-Usage",
                        "calls=" + quota.callsUsed() + "/" + quota.callsLimit()
                        + ";tokens=" + quota.tokensUsed() + "/" + quota.tokensLimit());
                auditLogService.saveAsync(com.qingyu.care.entity.SysAuditLog.builder()
                        .tenantId(app.getTenant().getId())
                        .action("QUOTA_WARN").targetType("Tenant")
                        .targetId(String.valueOf(app.getTenant().getId()))
                        .detail("接近额度: calls=" + quota.callsUsed() + "/" + quota.callsLimit()
                                + ", tokens=" + quota.tokensUsed() + "/" + quota.tokensLimit())
                        .build());
            }
```
并在 `writeError` 加带 `reason` 的重载（原方法委托）：
```java
    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        writeError(response, status, message, null);
    }

    private void writeError(HttpServletResponse response, int status, String message, String reason) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(OpenErrorResponse.of(status, message, reason)));
    }
```

- [ ] **Step 5: 运行确认通过 + 全量单测无回归**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=BillingQuotaIT`
Expected: BUILD SUCCESS，2 测试全绿。

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q test`
Expected: BUILD SUCCESS（免 Docker）。

- [ ] **Step 6: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/dto/open/OpenErrorResponse.java backend/src/main/java/com/qingyu/care/config/OpenApiAuthInterceptor.java backend/src/test/java/com/qingyu/care/it/BillingQuotaIT.java
git commit -m "feat: 开放 API 额度检查拦截（超额 403 QUOTA_EXCEEDED + 近阈值告警）"
```

---

## Task 4: 额度查询接口

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/controller/BillingController.java`
- Test: `backend/src/test/java/com/qingyu/care/it/BillingQuotaViewIT.java`

- [ ] **Step 1: 写失败的 IT**

`BillingQuotaViewIT.java`：
```java
package com.qingyu.care.it;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.support.AbstractPostgresIT;
import cn.dev33.satoken.secure.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 租户查自身额度：plan/used/limit 正确。 */
class BillingQuotaViewIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired SysApiUsageRepository usageRepository;
    @Autowired ConfigService configService;
    private final ObjectMapper om = new ObjectMapper();

    private String login(String u, String p) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    @Test
    void 租户查额度返回plan与已用() throws Exception {
        Tenant t = tenantRepository.save(Tenant.builder().name("额度视图IT").code("QUOTA_VIEW")
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        User admin = userRepository.save(User.builder()
                .username("quota_view_admin").password(BCrypt.hashpw("pw123456"))
                .nickname("v").role(UserRole.TENANT_ADMIN).tenant(t).enabled(true).build());
        configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "1000", "IT");
        usageRepository.save(SysApiUsage.builder().tenantId(t.getId()).appId(90090L)
                .usageDate(LocalDate.now()).callCount(12).messageCount(12).tokenCount(340).build());

        try {
            String token = login("quota_view_admin", "pw123456");
            mockMvc.perform(get("/v1/billing/quota").header("satoken", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.plan").value("FREE"))
                    .andExpect(jsonPath("$.data.callsUsed").value(12))
                    .andExpect(jsonPath("$.data.callsLimit").value(1000));
        } finally {
            usageRepository.findAll().stream().filter(u -> t.getId().equals(u.getTenantId()))
                    .forEach(usageRepository::delete);
            userRepository.delete(admin);
            tenantRepository.delete(t);
            configService.set(OwnerScope.PLATFORM, null, "billing.quota.FREE.calls", "", null);
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=BillingQuotaViewIT`
Expected: 失败（`/v1/billing/quota` 未映射）。

- [ ] **Step 3: 实现 `BillingController`**

```java
package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.QuotaViewDTO;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费/额度查询。面向租户用户；平台管理员无租户上下文返回空视图。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/billing")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "计费与额度")
public class BillingController {

    private final QuotaService quotaService;
    private final AuthService authService;

    @GetMapping("/quota")
    @Operation(summary = "查看本租户本月额度使用")
    public ApiResponse<QuotaViewDTO> quota() {
        return ApiResponse.success(quotaService.view(authService.getCurrentTenantId()));
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify -Dit.test=BillingQuotaViewIT`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
cd /d/Code/m/qingyu-care
git add backend/src/main/java/com/qingyu/care/controller/BillingController.java backend/src/test/java/com/qingyu/care/it/BillingQuotaViewIT.java
git commit -m "feat: 额度查询接口 GET /v1/billing/quota"
```

---

## Task 5: 前端额度卡片

**Files:**
- Create: `frontend/src/api/billing.ts`
- Modify: `frontend/src/pages/UsagePage.tsx`

- [ ] **Step 1: 创建 `api/billing.ts`**

```typescript
import api from './client';
import type { ApiResponse } from '../types';

export interface QuotaView {
    plan: string | null;
    callsUsed: number;
    callsLimit: number;
    tokensUsed: number;
    tokensLimit: number;
    warnThreshold: number;
    resetDate: string | null;
}

export const billingService = {
    // 查看本租户本月额度（平台管理员返回 plan=null 空视图）
    getQuota: (): Promise<ApiResponse<QuotaView>> => api.get('/billing/quota'),
};
```

- [ ] **Step 2: 在 `UsagePage.tsx` 加"本月额度"卡片（租户视角）**

在 import 区加：
```tsx
import { billingService } from '@/api/billing';
import type { QuotaView } from '@/api/billing';
```
在组件内 `const [apps, setApps] = useState<Application[]>([]);` 之后加 state：
```tsx
    const [quota, setQuota] = useState<QuotaView | null>(null);
```
在拉取应用列表的 `useEffect` 之后加一个 effect（租户视角拉额度）：
```tsx
    useEffect(() => {
        if (data?.scope === 'TENANT') {
            billingService.getQuota().then((res) => {
                if (res.code === 200 && res.data.plan) setQuota(res.data);
            }).catch(() => { /* 额度拉取失败不阻断看板 */ });
        }
    }, [data?.scope]);
```
在汇总卡片那个 `<div className="grid grid-cols-2 md:grid-cols-5 gap-4">...</div>` 之后、趋势 `<Card>` 之前，插入额度卡片区：
```tsx
            {/* 本月额度（租户视角） */}
            {isTenant && quota && (
                <Card>
                    <CardHeader className="pb-2">
                        <CardTitle className="text-base flex items-center gap-2">
                            本月额度
                            <span className="text-xs font-normal text-muted-foreground">套餐 {quota.plan}</span>
                        </CardTitle>
                    </CardHeader>
                    <CardContent className="grid md:grid-cols-2 gap-6">
                        <QuotaBar label="调用次数" used={quota.callsUsed} limit={quota.callsLimit} />
                        <QuotaBar label="Token" used={quota.tokensUsed} limit={quota.tokensLimit} />
                    </CardContent>
                </Card>
            )}
```
在文件底部 `StatCard` 组件定义之后、`export default UsagePage;` 之前，加 `QuotaBar` 子组件：
```tsx
const QuotaBar: React.FC<{ label: string; used: number; limit: number }> = ({ label, used, limit }) => {
    const unlimited = limit <= 0;
    const pct = unlimited ? 0 : Math.min(100, Math.round((used / limit) * 100));
    const danger = pct >= 100;
    const warn = pct >= 80 && pct < 100;
    return (
        <div className="space-y-1">
            <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">{label}</span>
                <span className="font-mono">{used}{unlimited ? '' : ` / ${limit}`}{unlimited && ' （不限）'}</span>
            </div>
            {!unlimited && (
                <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
                    <div
                        className={danger ? 'h-full bg-destructive' : warn ? 'h-full bg-amber-500' : 'h-full bg-primary'}
                        style={{ width: `${pct}%` }}
                    />
                </div>
            )}
        </div>
    );
};
```

- [ ] **Step 3: 类型检查与构建**

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm exec tsc -b`
Expected: 无类型错误。

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 成功。

- [ ] **Step 4: 提交**

```bash
cd /d/Code/m/qingyu-care
git add frontend/src/api/billing.ts frontend/src/pages/UsagePage.tsx
git commit -m "feat: 用量看板加本月额度卡片"
```

---

## Task 6: 收尾与全量回归

- [ ] **Step 1: 全量回归**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q verify`
Expected: 单测 + 全部 IT（含 `BillingRepositoryIT`/`BillingQuotaIT`/`BillingQuotaViewIT`）全绿。

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 成功。

- [ ] **Step 2: 更新 spec 状态**

把 `docs/superpowers/specs/2026-07-05-billing-quota-design.md` frontmatter 的 `status: 草案` 改为 `status: 已实现`，末尾追加实现结论（额度检查在拦截器、开放 API 专属、月度自然重置、fail-open）。

- [ ] **Step 3: 提交**

```bash
cd /d/Code/m/qingyu-care
git add docs/superpowers/specs/2026-07-05-billing-quota-design.md
git commit -m "docs: 计费与套餐额度 spec 置为已实现"
```

- [ ] **Step 4: 验收核对**（对照 spec §10）
  - FREE 租户当月调用达额度 → 403 `QUOTA_EXCEEDED`；ENTERPRISE(0) 不限放行。
  - 近阈值放行 + `X-Quota-Usage` 头 + 审计。
  - 跨月自动重置（当月累计从 0 起）。
  - `GET /v1/billing/quota` 返回正确 used/limit/resetDate；看板展示额度卡片。
  - 额度仅作用于开放 API，内部管理端不受限。
  - `mvn verify` 全绿、`pnpm build` 通过。

---

## 附：任务依赖

- 严格顺序：Task 1 → 2 → 3；Task 2 → 4（controller 依赖 QuotaService）；Task 4 → 5（前端依赖接口）。
- Task 6 收尾需前 5 个完成。
- 全程 TDD、小提交；后端单测免 Docker，IT 需 Docker Desktop（已运行）。

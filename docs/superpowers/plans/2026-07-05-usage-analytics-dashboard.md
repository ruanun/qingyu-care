# 用量统计看板 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为轻语管理后台新增按角色自适应的用量统计看板（开放 API 用量 + 轻量会话效果），收尾 Phase 3「数据统计」。

**Architecture:** 后端新增 `AnalyticsController` → `AnalyticsService`（注入现有 `AuthService` 判角色），实时 SQL 聚合 `sys_api_usage`（JPQL，非 filter 实体）与 `chat_session`（**native query 绕过 Hibernate `appFilter`**），service 层做补零、口径计算、排行名称回填，返回单个 overview DTO。前端新增 `UsagePage` + `api/analytics.ts`，引入 recharts 3.x **直接使用其原生组件**（`LineChart`/`BarChart`/`ResponsiveContainer` + tailwind 主题色，**不照搬 shadcn `chart.tsx` 全套封装——YAGNI，对 spec §5 的简化**）。

**Tech Stack:** Spring Boot 3 / Spring Data JPA / Sa-Token / PostgreSQL；React 19 / TypeScript / recharts 3.x / Zustand / shadcn 风格组件；测试 Mockito（单测）+ Testcontainers pgvector（IT）。

**与 spec 的对齐说明（writing-plans 自检发现，实现更贴合现状）：**
- **AI 解决率口径**用 `agent_id IS NULL`（沿用现有 `ChatSessionRepository.countAiResolvedSessions`），非 spec 初稿的 `handover_time`；转人工率用 `handover_time IS NOT NULL`。
- **`chat_session` 聚合用 native query** 绕过 Hibernate filter（filter 只作用于 JPQL/Criteria，不作用于 native SQL），这是「跨应用聚合不被 `appFilter` 挡」的真正实现手段。
- **角色判定复用 `AuthService`** 现成的 `isPlatformAdmin()` / `getCurrentTenantId()`，不新建工具类、不碰 `TenantDataAspect`。
- 前端图表**直接用 recharts**，不新建 `components/ui/chart.tsx`。

---

## Task 1: 后端 DTO 与查询投影接口

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/UsageAnalyticsDTO.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/projection/UsageDailyRow.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/projection/UsageRankRow.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/projection/SessionDailyRow.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/projection/SessionSummaryRow.java`

纯数据结构，无独立单测（由 Task 3/4 消费并验证）。验证方式：编译通过。

- [ ] **Step 1: 创建返回 DTO**（Java record，嵌套子结构）

```java
package com.qingyu.care.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 用量统计看板返回体。scope 供前端切换排行标题/文案（"PLATFORM" 按租户排行 / "TENANT" 按应用排行）。
 *
 * @author ruan
 */
public record UsageAnalyticsDTO(
        Summary summary,
        List<TrendPoint> trend,
        List<RankingItem> ranking,
        String scope) {

    /** 区间汇总。aiResolutionRate / handoverRate 为百分数（0~100，保留一位小数）。 */
    public record Summary(long totalCalls, long totalTokens, long totalMessages,
                          long totalSessions, double aiResolutionRate, double handoverRate) {
    }

    /** 按天趋势点（已补零：区间内每天一个）。 */
    public record TrendPoint(LocalDate date, long calls, long tokens, long sessions) {
    }

    /** 排行项：平台视角 id/name 为租户，租户视角为应用。 */
    public record RankingItem(Long id, String name, long calls, long tokens) {
    }
}
```

- [ ] **Step 2: 创建 4 个查询投影接口**

`UsageDailyRow.java`：
```java
package com.qingyu.care.repository.projection;

import java.time.LocalDate;

/** sys_api_usage 按天聚合行。 */
public interface UsageDailyRow {
    LocalDate getDate();
    long getCalls();
    long getTokens();
    long getMessages();
}
```

`UsageRankRow.java`：
```java
package com.qingyu.care.repository.projection;

/** sys_api_usage 排行行；refId 为租户 id 或应用 id。 */
public interface UsageRankRow {
    Long getRefId();
    long getCalls();
    long getTokens();
}
```

`SessionDailyRow.java`：
```java
package com.qingyu.care.repository.projection;

import java.time.LocalDate;

/** chat_session 按天会话数。 */
public interface SessionDailyRow {
    LocalDate getDate();
    long getSessions();
}
```

`SessionSummaryRow.java`：
```java
package com.qingyu.care.repository.projection;

/** chat_session 区间汇总。 */
public interface SessionSummaryRow {
    long getTotal();
    long getEnded();
    long getAiResolved();
    long getHandover();
}
```

- [ ] **Step 3: 编译**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/dto/UsageAnalyticsDTO.java backend/src/main/java/com/qingyu/care/repository/projection/
git commit -m "feat: 用量看板返回 DTO 与查询投影接口"
```

---

## Task 2: 仓库聚合查询（sys_api_usage JPQL + chat_session native）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/repository/SysApiUsageRepository.java`
- Modify: `backend/src/main/java/com/qingyu/care/repository/ChatSessionRepository.java`
- Test: `backend/src/test/java/com/qingyu/care/it/AnalyticsRepositoryIT.java`

- [ ] **Step 1: 写失败的仓库层 IT**（验证聚合 SQL、过滤、排序；测试线程无租户上下文，native 手写 where 直接生效）

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.projection.SessionSummaryRow;
import com.qingyu.care.repository.projection.UsageDailyRow;
import com.qingyu.care.repository.projection.UsageRankRow;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 聚合查询 SQL 正确性（无登录上下文，native 手写过滤直接生效）。@Transactional 回滚 seed。 */
@Transactional
class AnalyticsRepositoryIT extends AbstractPostgresIT {

    private static final Long T1 = 70001L, T2 = 70002L, A1 = 70011L, A2 = 70012L, A3 = 70021L;

    @Autowired SysApiUsageRepository usageRepository;
    @Autowired ChatSessionRepository sessionRepository;

    private void seedUsage(Long tenant, Long app, LocalDate date, long calls, long tokens) {
        SysApiUsage u = SysApiUsage.builder()
                .tenantId(tenant).appId(app).usageDate(date)
                .callCount(calls).messageCount(calls).tokenCount(tokens).build();
        usageRepository.save(u);
    }

    private void seedSession(Long tenant, Long app, SessionStatus status, Long agentId, boolean handover) {
        ChatSession s = ChatSession.builder()
                .sessionId("it-" + tenant + "-" + app + "-" + System.nanoTime())
                .userId("u").status(status).agentId(agentId)
                .handoverTime(handover ? LocalDateTime.now() : null).build();
        s.setTenantId(tenant);
        s.setAppId(app);
        sessionRepository.save(s);
    }

    @Test
    void sys用量按天聚合_可按租户与应用过滤() {
        LocalDate today = LocalDate.now();
        seedUsage(T1, A1, today, 3, 30);
        seedUsage(T1, A1, today, 2, 20);   // 同租户同应用同天 → 聚合到一天
        seedUsage(T2, A3, today, 9, 90);

        // 租户 T1 全部应用
        List<UsageDailyRow> t1 = usageRepository.aggregateDaily(today.minusDays(29), T1, null);
        assertThat(t1).hasSize(1);
        assertThat(t1.get(0).getCalls()).isEqualTo(5);
        assertThat(t1.get(0).getTokens()).isEqualTo(50);

        // 全平台（tenantId=null 不过滤）
        List<UsageDailyRow> all = usageRepository.aggregateDaily(today.minusDays(29), null, null);
        assertThat(all.get(0).getCalls()).isEqualTo(14);
    }

    @Test
    void 排行_平台按租户_租户按应用_降序取前N() {
        LocalDate today = LocalDate.now();
        seedUsage(T1, A1, today, 5, 50);
        seedUsage(T1, A2, today, 1, 10);
        seedUsage(T2, A3, today, 8, 80);

        List<UsageRankRow> byTenant = usageRepository.rankByTenant(today.minusDays(29), PageRequest.of(0, 10));
        assertThat(byTenant.get(0).getRefId()).isEqualTo(T2);   // T2=8 > T1=6
        assertThat(byTenant.get(0).getCalls()).isEqualTo(8);

        List<UsageRankRow> byApp = usageRepository.rankByApp(today.minusDays(29), T1, PageRequest.of(0, 10));
        assertThat(byApp.get(0).getRefId()).isEqualTo(A1);      // A1=5 > A2=1
    }

    @Test
    void 会话汇总_native绕过过滤_口径正确() {
        seedSession(T1, A1, SessionStatus.ENDED, null, false);   // AI 解决
        seedSession(T1, A2, SessionStatus.ENDED, 999L, true);    // 人工介入 + 转人工
        seedSession(T1, A1, SessionStatus.AI_PROCESSING, null, false);

        LocalDateTime from = LocalDate.now().minusDays(29).atStartOfDay();
        SessionSummaryRow s = sessionRepository.aggregateSessionSummary(from, T1, null);
        assertThat(s.getTotal()).isEqualTo(3);
        assertThat(s.getEnded()).isEqualTo(2);
        assertThat(s.getAiResolved()).isEqualTo(1);
        assertThat(s.getHandover()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行 IT 确认失败**

Run: `cd backend && mvn -q verify -Dit.test=AnalyticsRepositoryIT`
Expected: 编译失败/测试失败 —— `aggregateDaily` 等方法尚不存在。

- [ ] **Step 3: 给 `SysApiUsageRepository` 加聚合与排行查询**（在接口末尾、`incrementUsage` 之后追加）

```java
    // ===== 用量看板聚合（该实体不继承 BaseTenantEntity，不受 Hibernate 租户过滤影响，where 手写）=====

    /** 按天聚合调用/token/消息；tenantId/appId 为 null 时该维度不过滤。 */
    @org.springframework.data.jpa.repository.Query("""
            SELECT u.usageDate AS date, SUM(u.callCount) AS calls,
                   SUM(u.tokenCount) AS tokens, SUM(u.messageCount) AS messages
            FROM SysApiUsage u
            WHERE u.usageDate >= :from
              AND (:tenantId IS NULL OR u.tenantId = :tenantId)
              AND (:appId IS NULL OR u.appId = :appId)
            GROUP BY u.usageDate
            ORDER BY u.usageDate
            """)
    java.util.List<com.qingyu.care.repository.projection.UsageDailyRow> aggregateDaily(
            @Param("from") LocalDate from,
            @Param("tenantId") Long tenantId,
            @Param("appId") Long appId);

    /** 平台视角：按租户排行（调用数降序）。用 Pageable 取 Top N。 */
    @org.springframework.data.jpa.repository.Query("""
            SELECT u.tenantId AS refId, SUM(u.callCount) AS calls, SUM(u.tokenCount) AS tokens
            FROM SysApiUsage u
            WHERE u.usageDate >= :from
            GROUP BY u.tenantId
            ORDER BY SUM(u.callCount) DESC
            """)
    java.util.List<com.qingyu.care.repository.projection.UsageRankRow> rankByTenant(
            @Param("from") LocalDate from, org.springframework.data.domain.Pageable pageable);

    /** 租户视角：本租户内按应用排行。 */
    @org.springframework.data.jpa.repository.Query("""
            SELECT u.appId AS refId, SUM(u.callCount) AS calls, SUM(u.tokenCount) AS tokens
            FROM SysApiUsage u
            WHERE u.usageDate >= :from AND u.tenantId = :tenantId
            GROUP BY u.appId
            ORDER BY SUM(u.callCount) DESC
            """)
    java.util.List<com.qingyu.care.repository.projection.UsageRankRow> rankByApp(
            @Param("from") LocalDate from, @Param("tenantId") Long tenantId,
            org.springframework.data.domain.Pageable pageable);
```

> 注：`SysApiUsageRepository` 已 import `Query`/`Param`。为避免与 native `@Query` 混淆，上面显式写全限定名亦可正常编译；若嫌冗长可改用已有 import 的短名。

- [ ] **Step 4: 给 `ChatSessionRepository` 加 native 聚合查询**（在接口末尾追加）

```java
    // ===== 用量看板会话聚合 =====
    // chat_session 继承 BaseTenantEntity，受 Hibernate tenantFilter/appFilter 约束。
    // Hibernate 过滤器只作用于 JPQL/Criteria，不作用于 native SQL——故这里用 nativeQuery
    // 绕过过滤器、手写 tenant_id 条件，确保「租户级跨应用汇总」不被请求头 X-App-Id 挡到单应用。

    /** 按天会话数（native 绕过过滤器）。 */
    @Query(value = """
            SELECT CAST(created_at AS DATE) AS date, COUNT(*) AS sessions
            FROM chat_session
            WHERE created_at >= :from
              AND (:tenantId IS NULL OR tenant_id = :tenantId)
              AND (:appId IS NULL OR app_id = :appId)
            GROUP BY CAST(created_at AS DATE)
            ORDER BY CAST(created_at AS DATE)
            """, nativeQuery = true)
    List<com.qingyu.care.repository.projection.SessionDailyRow> aggregateDailySessions(
            @Param("from") LocalDateTime from,
            @Param("tenantId") Long tenantId,
            @Param("appId") Long appId);

    /** 区间汇总：总数 / 已结束 / AI 解决（ENDED 且无人工 agent_id）/ 转人工（handover_time 非空）。 */
    @Query(value = """
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN status = 'ENDED' THEN 1 ELSE 0 END), 0) AS ended,
                   COALESCE(SUM(CASE WHEN status = 'ENDED' AND agent_id IS NULL THEN 1 ELSE 0 END), 0) AS aiResolved,
                   COALESCE(SUM(CASE WHEN handover_time IS NOT NULL THEN 1 ELSE 0 END), 0) AS handover
            FROM chat_session
            WHERE created_at >= :from
              AND (:tenantId IS NULL OR tenant_id = :tenantId)
              AND (:appId IS NULL OR app_id = :appId)
            """, nativeQuery = true)
    com.qingyu.care.repository.projection.SessionSummaryRow aggregateSessionSummary(
            @Param("from") LocalDateTime from,
            @Param("tenantId") Long tenantId,
            @Param("appId") Long appId);
```

> `ChatSessionRepository` 已 import `Query`/`Param`/`List`/`LocalDateTime`，可直接使用。

- [ ] **Step 5: 运行 IT 确认通过**

Run: `cd backend && mvn -q verify -Dit.test=AnalyticsRepositoryIT`
Expected: BUILD SUCCESS，3 个测试全绿。

> 若 native 投影 `getDate()` 报 `java.sql.Date`→`LocalDate` 转换错，把 `SessionDailyRow.getDate()` 返回类型改为 `java.sql.Date`，并在 Task 3 service 层用 `.toLocalDate()` 转换。先按 `LocalDate` 尝试（Hibernate 6 通常支持）。
> 若 `aggregateDaily`（JPQL）报无法推断可空参数类型（`:tenantId is null` 传 null 时），给参数加 CAST：`(:tenantId IS NULL OR u.tenantId = CAST(:tenantId AS long))`，或将该查询也改为 `nativeQuery = true`（与会话查询同风格）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/repository/ backend/src/test/java/com/qingyu/care/it/AnalyticsRepositoryIT.java
git commit -m "feat: 用量看板仓库聚合查询（sys 用量 JPQL + 会话 native 绕过过滤）"
```

---

## Task 3: AnalyticsService（补零、口径、角色自适应、排行回填）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/AnalyticsService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/AnalyticsServiceTest.java`

- [ ] **Step 1: 写失败的单测**（mock 所有仓库 + AuthService；覆盖角色分支/补零/口径/回填）

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.UsageAnalyticsDTO;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock SysApiUsageRepository usageRepository;
    @Mock ChatSessionRepository sessionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock AuthService authService;
    @InjectMocks AnalyticsService service;

    private UsageDailyRow usageRow(LocalDate d, long calls, long tokens, long messages) {
        return new UsageDailyRow() {
            public LocalDate getDate() { return d; }
            public long getCalls() { return calls; }
            public long getTokens() { return tokens; }
            public long getMessages() { return messages; }
        };
    }

    private SessionSummaryRow summaryRow(long total, long ended, long ai, long handover) {
        return new SessionSummaryRow() {
            public long getTotal() { return total; }
            public long getEnded() { return ended; }
            public long getAiResolved() { return ai; }
            public long getHandover() { return handover; }
        };
    }

    @Test
    void 平台管理员_跨租户聚合_排行按租户_且appId被忽略() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(usageRepository.aggregateDaily(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateDailySessions(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(0, 0, 0, 0));
        when(usageRepository.rankByTenant(any(), any())).thenReturn(List.of());

        UsageAnalyticsDTO dto = service.getUsage(30, 999L); // 传 appId 也应被忽略

        assertThat(dto.scope()).isEqualTo("PLATFORM");
        verify(usageRepository).aggregateDaily(any(), isNull(), isNull());
        verify(usageRepository).rankByTenant(any(), any());
        verify(usageRepository, never()).rankByApp(any(), anyLong(), any());
    }

    @Test
    void 租户视角_限本租户_排行按应用_支持appId下钻() {
        when(authService.isPlatformAdmin()).thenReturn(false);
        when(authService.getCurrentTenantId()).thenReturn(7L);
        when(usageRepository.aggregateDaily(any(), eq(7L), eq(3L))).thenReturn(List.of());
        when(sessionRepository.aggregateDailySessions(any(), eq(7L), eq(3L))).thenReturn(List.of());
        when(sessionRepository.aggregateSessionSummary(any(), eq(7L), eq(3L)))
                .thenReturn(summaryRow(0, 0, 0, 0));
        when(usageRepository.rankByApp(any(), eq(7L), any())).thenReturn(List.of());

        UsageAnalyticsDTO dto = service.getUsage(7, 3L);

        assertThat(dto.scope()).isEqualTo("TENANT");
        verify(usageRepository).rankByApp(any(), eq(7L), any());
        verify(usageRepository, never()).rankByTenant(any(), any());
    }

    @Test
    void 趋势补零_区间内每天一个点_缺失天为0() {
        LocalDate today = LocalDate.now();
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(usageRepository.aggregateDaily(any(), isNull(), isNull()))
                .thenReturn(List.of(usageRow(today, 10, 100, 8)));
        when(sessionRepository.aggregateDailySessions(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(0, 0, 0, 0));
        when(usageRepository.rankByTenant(any(), any())).thenReturn(List.of());

        UsageAnalyticsDTO dto = service.getUsage(30, null);

        assertThat(dto.trend()).hasSize(30);
        assertThat(dto.trend().get(29).date()).isEqualTo(today);
        assertThat(dto.trend().get(29).calls()).isEqualTo(10);
        assertThat(dto.trend().get(0).calls()).isEqualTo(0); // 最早一天无数据补零
        assertThat(dto.summary().totalCalls()).isEqualTo(10);
        assertThat(dto.summary().totalMessages()).isEqualTo(8);
    }

    @Test
    void 口径_解决率与转人工率_分母为0不除零() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(usageRepository.aggregateDaily(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateDailySessions(any(), isNull(), isNull())).thenReturn(List.of());
        when(usageRepository.rankByTenant(any(), any())).thenReturn(List.of());

        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(10, 4, 3, 2));
        UsageAnalyticsDTO dto = service.getUsage(30, null);
        assertThat(dto.summary().aiResolutionRate()).isEqualTo(75.0);  // 3/4
        assertThat(dto.summary().handoverRate()).isEqualTo(20.0);      // 2/10
        assertThat(dto.summary().totalSessions()).isEqualTo(10);

        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(0, 0, 0, 0));
        UsageAnalyticsDTO empty = service.getUsage(30, null);
        assertThat(empty.summary().aiResolutionRate()).isEqualTo(0.0);
        assertThat(empty.summary().handoverRate()).isEqualTo(0.0);
    }

    @Test
    void days非法值回落30() {
        when(authService.isPlatformAdmin()).thenReturn(true);
        when(usageRepository.aggregateDaily(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateDailySessions(any(), isNull(), isNull())).thenReturn(List.of());
        when(sessionRepository.aggregateSessionSummary(any(), isNull(), isNull()))
                .thenReturn(summaryRow(0, 0, 0, 0));
        when(usageRepository.rankByTenant(any(), any())).thenReturn(List.of());

        assertThat(service.getUsage(5, null).trend()).hasSize(30);
        assertThat(service.getUsage(90, null).trend()).hasSize(90);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=AnalyticsServiceTest`
Expected: 编译失败 —— `AnalyticsService` 不存在。

- [ ] **Step 3: 实现 `AnalyticsService`**

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.UsageAnalyticsDTO;
import com.qingyu.care.dto.UsageAnalyticsDTO.RankingItem;
import com.qingyu.care.dto.UsageAnalyticsDTO.Summary;
import com.qingyu.care.dto.UsageAnalyticsDTO.TrendPoint;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.SessionDailyRow;
import com.qingyu.care.repository.projection.SessionSummaryRow;
import com.qingyu.care.repository.projection.UsageDailyRow;
import com.qingyu.care.repository.projection.UsageRankRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用量统计看板聚合服务：按当前登录用户角色自适应聚合范围。
 * 角色判定复用 {@link AuthService}（与 TenantDataAspect 同源），不另造一套。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final int TOP_N = 10;

    private final SysApiUsageRepository usageRepository;
    private final ChatSessionRepository sessionRepository;
    private final TenantRepository tenantRepository;
    private final ApplicationRepository applicationRepository;
    private final AuthService authService;

    public UsageAnalyticsDTO getUsage(int days, Long appIdFilter) {
        int span = normalizeDays(days);
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(span - 1L);
        LocalDateTime fromDateTime = fromDate.atStartOfDay();

        boolean platform = authService.isPlatformAdmin();
        Long tenantId = platform ? null : authService.getCurrentTenantId();
        Long appId = platform ? null : appIdFilter; // 平台视角忽略应用下钻

        Map<LocalDate, UsageDailyRow> usageByDate = usageRepository
                .aggregateDaily(fromDate, tenantId, appId).stream()
                .collect(Collectors.toMap(UsageDailyRow::getDate, Function.identity(), (a, b) -> a));
        Map<LocalDate, Long> sessionsByDate = sessionRepository
                .aggregateDailySessions(fromDateTime, tenantId, appId).stream()
                .collect(Collectors.toMap(SessionDailyRow::getDate, SessionDailyRow::getSessions, (a, b) -> a));

        List<TrendPoint> trend = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate d = fromDate.plusDays(i);
            UsageDailyRow u = usageByDate.get(d);
            trend.add(new TrendPoint(d,
                    u != null ? u.getCalls() : 0L,
                    u != null ? u.getTokens() : 0L,
                    sessionsByDate.getOrDefault(d, 0L)));
        }

        long totalCalls = trend.stream().mapToLong(TrendPoint::calls).sum();
        long totalTokens = trend.stream().mapToLong(TrendPoint::tokens).sum();
        long totalMessages = usageByDate.values().stream().mapToLong(UsageDailyRow::getMessages).sum();

        SessionSummaryRow ss = sessionRepository.aggregateSessionSummary(fromDateTime, tenantId, appId);
        double aiRate = ss.getEnded() > 0 ? round1((double) ss.getAiResolved() / ss.getEnded() * 100) : 0.0;
        double handoverRate = ss.getTotal() > 0 ? round1((double) ss.getHandover() / ss.getTotal() * 100) : 0.0;
        Summary summary = new Summary(totalCalls, totalTokens, totalMessages,
                ss.getTotal(), aiRate, handoverRate);

        List<RankingItem> ranking = platform
                ? buildTenantRanking(fromDate)
                : buildAppRanking(fromDate, tenantId);

        return new UsageAnalyticsDTO(summary, trend, ranking, platform ? "PLATFORM" : "TENANT");
    }

    private List<RankingItem> buildTenantRanking(LocalDate from) {
        List<UsageRankRow> rows = usageRepository.rankByTenant(from, PageRequest.of(0, TOP_N));
        Map<Long, String> names = tenantRepository.findAllById(refIds(rows)).stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName));
        return rows.stream()
                .map(r -> new RankingItem(r.getRefId(), names.getOrDefault(r.getRefId(), "未知租户"),
                        r.getCalls(), r.getTokens()))
                .toList();
    }

    private List<RankingItem> buildAppRanking(LocalDate from, Long tenantId) {
        List<UsageRankRow> rows = usageRepository.rankByApp(from, tenantId, PageRequest.of(0, TOP_N));
        Map<Long, String> names = applicationRepository.findAllById(refIds(rows)).stream()
                .collect(Collectors.toMap(Application::getId, Application::getName));
        return rows.stream()
                .map(r -> new RankingItem(r.getRefId(), names.getOrDefault(r.getRefId(), "未知应用"),
                        r.getCalls(), r.getTokens()))
                .toList();
    }

    private List<Long> refIds(List<UsageRankRow> rows) {
        return rows.stream().map(UsageRankRow::getRefId).filter(Objects::nonNull).toList();
    }

    private int normalizeDays(int days) {
        return (days == 7 || days == 90) ? days : 30;
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
```

> `tenantRepository.findAllById` / `applicationRepository.findAllById` 对 `Tenant`/`Application`（均未继承 `BaseTenantEntity`）不受租户过滤，名称回填安全。若某排行 id 已不存在（如租户被删），回填「未知租户/应用」占位。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=AnalyticsServiceTest`
Expected: BUILD SUCCESS，5 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/AnalyticsService.java backend/src/test/java/com/qingyu/care/service/AnalyticsServiceTest.java
git commit -m "feat: AnalyticsService 用量聚合（补零/口径/角色自适应/排行回填）"
```

---

## Task 4: AnalyticsController + 端到端 IT（角色隔离 + appFilter 坑）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/controller/AnalyticsController.java`
- Test: `backend/src/test/java/com/qingyu/care/it/AnalyticsUsageIT.java`

- [ ] **Step 1: 写失败的端到端 IT**

```java
package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用量看板端到端：平台管理员跨租户 / 租户只见本租户 / 带 X-App-Id 时会话聚合仍跨应用（appFilter 未挡）。
 * 不加 @Transactional：MockMvc 请求经真实拦截器链，需 seed 已提交可见；结尾手动清理。
 *
 * @author ruan
 */
class AnalyticsUsageIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired SysApiUsageRepository usageRepository;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private void seedUsage(Long tenant, Long app, long calls, long tokens) {
        usageRepository.save(SysApiUsage.builder()
                .tenantId(tenant).appId(app).usageDate(LocalDate.now())
                .callCount(calls).messageCount(calls).tokenCount(tokens).build());
    }

    private void seedSession(Long tenant, Long app) {
        ChatSession s = ChatSession.builder()
                .sessionId("it-usage-" + tenant + "-" + app + "-" + System.nanoTime())
                .userId("u").status(SessionStatus.ENDED).build();
        s.setTenantId(tenant);
        s.setAppId(app);
        sessionRepository.save(s);
    }

    @Test
    void 平台管理员看到跨租户_租户只看本租户_且带AppId会话仍跨应用() throws Exception {
        // 两租户 + 两应用（同租户 T_A 下 APP_A1/APP_A2），各造用量与会话
        Tenant tenantA = tenantRepository.save(Tenant.builder()
                .name("用量IT租户A").code("USAGE_IT_A")
                .status(com.qingyu.care.enums.TenantStatus.ACTIVE)
                .plan(com.qingyu.care.enums.TenantPlan.FREE).build());
        Long TA = tenantA.getId();

        Application appA1 = new Application();
        appA1.setName("A1"); appA1.setTenant(tenantA);
        appA1.setAppKey("usage_it_a1"); appA1.setAppSecret("sec_a1");
        appA1.setEnabled(true);
        appA1 = applicationRepository.save(appA1);
        Long A1 = appA1.getId();

        Application appA2 = new Application();
        appA2.setName("A2"); appA2.setTenant(tenantA);
        appA2.setAppKey("usage_it_a2"); appA2.setAppSecret("sec_a2");
        appA2.setEnabled(true);
        appA2 = applicationRepository.save(appA2);
        Long A2 = appA2.getId();

        Tenant tenantB = tenantRepository.save(Tenant.builder()
                .name("用量IT租户B").code("USAGE_IT_B")
                .status(com.qingyu.care.enums.TenantStatus.ACTIVE)
                .plan(com.qingyu.care.enums.TenantPlan.FREE).build());
        Long TB = tenantB.getId();

        // 租户 A 管理员账号
        User adminA = userRepository.save(User.builder()
                .username("usage_it_admin_a").password(BCrypt.hashpw("pw123456"))
                .nickname("A管理员").role(UserRole.TENANT_ADMIN).tenant(tenantA).enabled(true).build());

        seedUsage(TA, A1, 5, 50);
        seedUsage(TA, A2, 3, 30);
        seedUsage(TB, 999001L, 8, 80);
        seedSession(TA, A1);
        seedSession(TA, A2);
        seedSession(TB, 999001L);

        try {
            // —— 平台管理员：全平台 ——
            String platToken = login("admin", "admin123");
            mockMvc.perform(get("/v1/analytics/usage?days=30").header("satoken", platToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope").value("PLATFORM"))
                    // 全平台调用数 = 5+3+8 = 16
                    .andExpect(jsonPath("$.data.summary.totalCalls").value(16));

            // —— 租户 A 管理员：只见本租户（TA=8，不含 TB 的 8）——
            String aToken = login("usage_it_admin_a", "pw123456");
            mockMvc.perform(get("/v1/analytics/usage?days=30").header("satoken", aToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope").value("TENANT"))
                    .andExpect(jsonPath("$.data.summary.totalCalls").value(8));

            // —— 关键：租户 A 管理员带 X-App-Id=A1 时，会话聚合仍跨应用（A1+A2=2），不被 appFilter 挡 ——
            MvcResult withApp = mockMvc.perform(get("/v1/analytics/usage?days=30")
                            .header("satoken", aToken)
                            .header("X-App-Id", String.valueOf(A1)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = om.readTree(withApp.getResponse().getContentAsString()).path("data");
            assertThat(data.path("summary").path("totalSessions").asLong()).isEqualTo(2L);
        } finally {
            usageRepository.findAll().stream()
                    .filter(u -> TA.equals(u.getTenantId()) || TB.equals(u.getTenantId()))
                    .forEach(usageRepository::delete);
            sessionRepository.findAll().stream()
                    .filter(s -> TA.equals(s.getTenantId()) || TB.equals(s.getTenantId()))
                    .forEach(sessionRepository::delete);
            userRepository.delete(adminA);
            applicationRepository.deleteAll(List.of(appA1, appA2));
            tenantRepository.deleteAll(List.of(tenantA, tenantB));
        }
    }
}
```

> `tenantRepository`/`applicationRepository` 由基类 `AbstractPostgresIT` 提供（`protected`）。`import java.util.List;` 已隐含于上；若缺失编译错，补 `import java.util.List;`。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q verify -Dit.test=AnalyticsUsageIT`
Expected: 失败 —— `/v1/analytics/usage` 尚未映射（404 / 断言失败）。

- [ ] **Step 3: 实现 `AnalyticsController`**

```java
package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.UsageAnalyticsDTO;
import com.qingyu.care.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用量统计看板 API。需登录（/v1/** 由 Sa-Token 拦截器要求登录）；
 * 聚合范围按角色在 service 内强制，不信任前端传参。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "用量统计看板")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/usage")
    @Operation(summary = "用量统计概览", description = "按角色自适应返回汇总/趋势/排行；days∈{7,30,90}，appId 仅租户视角有效")
    public ApiResponse<UsageAnalyticsDTO> usage(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long appId) {
        return ApiResponse.success(analyticsService.getUsage(days, appId));
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=AnalyticsUsageIT`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 跑全部后端测试确认无回归**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS（单测全绿，免 Docker）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/AnalyticsController.java backend/src/test/java/com/qingyu/care/it/AnalyticsUsageIT.java
git commit -m "feat: 用量看板开放接口 /v1/analytics/usage + 端到端隔离 IT"
```

---

## Task 5: 前端依赖与 API service

**Files:**
- Modify: `frontend/package.json`（新增 recharts）
- Create: `frontend/src/api/analytics.ts`

- [ ] **Step 1: 安装 recharts**

Run: `cd frontend && pnpm add recharts@^3.2.1`
Expected: `package.json` dependencies 出现 `"recharts": "^3.2.1"`，`pnpm-lock.yaml` 更新。

- [ ] **Step 2: 创建 `api/analytics.ts`**

```typescript
import api from './client';
import type { ApiResponse } from '../types';

export interface UsageSummary {
    totalCalls: number;
    totalTokens: number;
    totalMessages: number;
    totalSessions: number;
    aiResolutionRate: number;
    handoverRate: number;
}

export interface UsageTrendPoint {
    date: string;
    calls: number;
    tokens: number;
    sessions: number;
}

export interface UsageRankingItem {
    id: number;
    name: string;
    calls: number;
    tokens: number;
}

export interface UsageAnalytics {
    summary: UsageSummary;
    trend: UsageTrendPoint[];
    ranking: UsageRankingItem[];
    scope: 'PLATFORM' | 'TENANT';
}

export const analyticsService = {
    // 获取用量统计（days ∈ 7/30/90；appId 仅租户视角有效）
    getUsage: (days: number, appId?: number): Promise<ApiResponse<UsageAnalytics>> =>
        api.get('/analytics/usage', { params: { days, ...(appId ? { appId } : {}) } }),
};
```

- [ ] **Step 3: 类型检查**

Run: `cd frontend && pnpm exec tsc -b`
Expected: 无类型错误。

- [ ] **Step 4: 提交**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml frontend/src/api/analytics.ts
git commit -m "feat: 前端引入 recharts 与用量统计 API service"
```

---

## Task 6: UsagePage 看板页

**Files:**
- Create: `frontend/src/pages/UsagePage.tsx`

页面结构：时间快捷 Tabs（7/30/90）→ 租户视角显示应用下钻 Select →汇总卡片行 → 趋势折线图 → 排行榜（柱状 + 表格）。加载/错误复用 `DashboardPage` 模式，口径差异脚注。

- [ ] **Step 1: 创建 `UsagePage.tsx`**

```tsx
import React, { useState, useEffect } from 'react';
import {
    LineChart, Line, BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import { Loader2, RefreshCw, Phone, MessageCircle, Coins, Bot, UserPen } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
    Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { analyticsService } from '@/api/analytics';
import type { UsageAnalytics } from '@/api/analytics';
import { applicationService } from '@/api/application';
import type { Application } from '@/types';
import { useToast } from '@/hooks/use-toast';

const DAY_OPTIONS = [
    { value: '7', label: '近 7 天' },
    { value: '30', label: '近 30 天' },
    { value: '90', label: '近 90 天' },
];
const ALL_APPS = '__all__';

const UsagePage: React.FC = () => {
    const [days, setDays] = useState('30');
    const [appId, setAppId] = useState(ALL_APPS);
    const [data, setData] = useState<UsageAnalytics | null>(null);
    const [apps, setApps] = useState<Application[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const { toast } = useToast();

    const fetchData = async () => {
        setIsLoading(true);
        try {
            const res = await analyticsService.getUsage(
                Number(days),
                appId === ALL_APPS ? undefined : Number(appId),
            );
            if (res.code === 200) setData(res.data);
        } catch (error: any) {
            toast({ variant: 'destructive', title: '加载失败', description: error.message });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => { fetchData(); /* eslint-disable-next-line */ }, [days, appId]);

    // 租户视角才需要应用下钻列表；平台视角接口忽略 appId，无需拉取
    useEffect(() => {
        if (data?.scope === 'TENANT' && apps.length === 0) {
            applicationService.list().then((res) => {
                if (res.code === 200) setApps(res.data);
            }).catch(() => { /* 下钻列表拉取失败不阻断看板 */ });
        }
    }, [data?.scope, apps.length]);

    const isTenant = data?.scope === 'TENANT';
    const rankingTitle = isTenant ? '应用用量 Top 10' : '租户用量 Top 10';

    if (isLoading && !data) {
        return (
            <div className="flex h-full items-center justify-center">
                <Loader2 className="w-8 h-8 animate-spin text-primary" />
            </div>
        );
    }

    const s = data?.summary;

    return (
        <div className="p-8 max-w-6xl mx-auto space-y-6 h-full overflow-y-auto">
            <div className="flex justify-between items-end flex-wrap gap-4">
                <div>
                    <h1 className="text-3xl font-bold tracking-tight">用量统计</h1>
                    <p className="text-muted-foreground mt-2">
                        开放 API 调用与会话效果概览。
                        <span className="text-xs">（用量 = 开放 API 接入；会话 = 全部渠道，含后台测试对话，二者口径不同）</span>
                    </p>
                </div>
                <div className="flex items-center gap-3">
                    {isTenant && (
                        <Select value={appId} onValueChange={setAppId}>
                            <SelectTrigger className="w-40"><SelectValue placeholder="全部应用" /></SelectTrigger>
                            <SelectContent>
                                <SelectItem value={ALL_APPS}>全部应用</SelectItem>
                                {apps.map((a) => (
                                    <SelectItem key={a.id} value={String(a.id)}>{a.name}</SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    )}
                    <Tabs value={days} onValueChange={setDays}>
                        <TabsList>
                            {DAY_OPTIONS.map((o) => (
                                <TabsTrigger key={o.value} value={o.value}>{o.label}</TabsTrigger>
                            ))}
                        </TabsList>
                    </Tabs>
                    <Button variant="outline" size="sm" onClick={fetchData} className="gap-2">
                        <RefreshCw className="w-4 h-4" /> 刷新
                    </Button>
                </div>
            </div>

            {/* 汇总卡片 */}
            <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
                <StatCard icon={<Phone className="w-4 h-4 text-primary" />} label="调用数" value={s?.totalCalls ?? 0} />
                <StatCard icon={<Coins className="w-4 h-4 text-amber-500" />} label="Token" value={s?.totalTokens ?? 0} />
                <StatCard icon={<MessageCircle className="w-4 h-4 text-green-500" />} label="会话数" value={s?.totalSessions ?? 0} />
                <StatCard icon={<Bot className="w-4 h-4 text-blue-500" />} label="AI 解决率" value={`${s?.aiResolutionRate ?? 0}%`} />
                <StatCard icon={<UserPen className="w-4 h-4 text-purple-500" />} label="转人工率" value={`${s?.handoverRate ?? 0}%`} />
            </div>

            {/* 趋势 */}
            <Card>
                <CardHeader><CardTitle className="text-lg">用量趋势</CardTitle></CardHeader>
                <CardContent>
                    <ResponsiveContainer width="100%" height={320}>
                        <LineChart data={data?.trend ?? []}>
                            <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                            <YAxis tick={{ fontSize: 12 }} />
                            <Tooltip />
                            <Legend />
                            <Line type="monotone" dataKey="calls" name="调用数" stroke="#6366f1" dot={false} />
                            <Line type="monotone" dataKey="tokens" name="Token" stroke="#f59e0b" dot={false} />
                            <Line type="monotone" dataKey="sessions" name="会话数" stroke="#10b981" dot={false} />
                        </LineChart>
                    </ResponsiveContainer>
                </CardContent>
            </Card>

            {/* 排行 */}
            <Card>
                <CardHeader><CardTitle className="text-lg">{rankingTitle}</CardTitle></CardHeader>
                <CardContent>
                    {(data?.ranking?.length ?? 0) === 0 ? (
                        <div className="text-center py-8 text-muted-foreground opacity-50">暂无数据</div>
                    ) : (
                        <div className="grid md:grid-cols-2 gap-6 items-center">
                            <ResponsiveContainer width="100%" height={280}>
                                <BarChart data={data?.ranking ?? []} layout="vertical" margin={{ left: 24 }}>
                                    <XAxis type="number" tick={{ fontSize: 12 }} />
                                    <YAxis type="category" dataKey="name" width={100} tick={{ fontSize: 12 }} />
                                    <Tooltip />
                                    <Bar dataKey="calls" name="调用数" fill="#6366f1" radius={[0, 4, 4, 0]} />
                                </BarChart>
                            </ResponsiveContainer>
                            <table className="w-full text-sm">
                                <thead>
                                    <tr className="text-muted-foreground border-b">
                                        <th className="text-left py-2 font-medium">{isTenant ? '应用' : '租户'}</th>
                                        <th className="text-right py-2 font-medium">调用数</th>
                                        <th className="text-right py-2 font-medium">Token</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {data?.ranking.map((r) => (
                                        <tr key={r.id} className="border-b last:border-0">
                                            <td className="py-2">{r.name}</td>
                                            <td className="py-2 text-right font-mono">{r.calls}</td>
                                            <td className="py-2 text-right font-mono">{r.tokens}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </CardContent>
            </Card>
        </div>
    );
};

const StatCard: React.FC<{ icon: React.ReactNode; label: string; value: React.ReactNode }> = ({ icon, label, value }) => (
    <Card>
        <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium flex items-center gap-2">{icon} {label}</CardTitle>
        </CardHeader>
        <CardContent><div className="text-2xl font-bold">{value}</div></CardContent>
    </Card>
);

export default UsagePage;
```

> `Application` 类型须含 `id`/`name`（现有 `types/index.ts` 已定义；`application.ts` 已在用）。若 `Select`/`Tabs` 的具体 props 与上例不符，按 `components/ui/select.tsx`、`tabs.tsx` 实际导出微调（shadcn 标准 API）。

- [ ] **Step 2: 类型检查**

Run: `cd frontend && pnpm exec tsc -b`
Expected: 无类型错误。

- [ ] **Step 3: 提交**

```bash
git add frontend/src/pages/UsagePage.tsx
git commit -m "feat: 用量统计看板页面（recharts 趋势 + 排行）"
```

---

## Task 7: 路由与导航接入 + 构建验证

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`

- [ ] **Step 1: `App.tsx` 加路由**（不包 `RequireApp`）

在 import 区加：
```tsx
import UsagePage from './pages/UsagePage';
```
在「管理」路由块（`dashboard` 路由附近）加：
```tsx
          <Route path="usage" element={<UsagePage />} />
```

- [ ] **Step 2: `MainLayout.tsx` 加导航项**

在 lucide-react 的 import 中追加 `BarChart3`：
```tsx
    Activity, Boxes, SlidersHorizontal, ScrollText, Building2, Plug, BarChart3
```
在「管理」section 的 items 数组中，`数据统计` 项之后加：
```tsx
            { label: '用量统计', path: '/usage', icon: BarChart3, roles: ['PLATFORM_ADMIN', 'TENANT_ADMIN'] },
```

- [ ] **Step 3: 构建验证（含 recharts 3.x × React 19 兼容）**

Run: `cd frontend && pnpm build`
Expected: `tsc -b && vite build` 成功，无类型/编译错误。

> 若 recharts 3.x 报 React 19 peer/运行时不兼容：先尝试 `pnpm add recharts@latest`；仍不行则记录到 spec 风险节，回 brainstorming 议图表替代（零依赖 SVG）。

- [ ] **Step 4: Lint**

Run: `cd frontend && pnpm lint`
Expected: 无新增 error（既有告警不阻断）。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/App.tsx frontend/src/layouts/MainLayout.tsx
git commit -m "feat: 用量统计页接入路由与侧边导航（平台/租户管理员可见）"
```

---

## Task 8: 收尾（spec 状态与验收）

**Files:**
- Modify: `docs/superpowers/specs/2026-07-05-usage-analytics-dashboard-design.md`

- [ ] **Step 1: 全量回归**

Run: `cd backend && mvn -q verify`
Expected: 单测 + 全部 IT（含 `AnalyticsRepositoryIT`、`AnalyticsUsageIT`）全绿。

Run: `cd frontend && pnpm build`
Expected: 成功。

- [ ] **Step 2: 更新 spec 状态**

把 spec frontmatter 的 `status: 草案` 改为 `status: 已实现`，并在末尾追加一行实现结论（合入分支、关键实现要点：native 绕 filter、复用 AuthService、recharts 直用）。

- [ ] **Step 3: 提交并结束特性分支**

```bash
git add docs/superpowers/specs/2026-07-05-usage-analytics-dashboard-design.md
git commit -m "docs: 用量看板 spec 置为已实现"
```

- [ ] **Step 4: 验收核对**（对照 spec §9）
  - 平台管理员 `/usage` → `scope=PLATFORM`、按租户 Top 10、跨租户汇总。
  - 租户管理员 `/usage` → `scope=TENANT`、按应用 Top 10、只见本租户、可下钻、带 X-App-Id 会话仍跨应用。
  - 趋势区间每天有点（补零）；7/30/90 切换刷新。
  - 界面标注口径差异。
  - `mvn verify` 全绿、`pnpm build` 通过。

---

## 附：任务依赖与并行性

- 后端链：Task 1 → 2 → 3 → 4（严格顺序，后者依赖前者类型）。
- 前端链：Task 5 → 6 → 7（顺序）。
- **后端链（1-4）与前端链（5-7）相互独立，可并行推进**；Task 8 收尾需两条链都完成。
- 全程遵循 TDD（先写失败测试）、每步小提交；后端单测免 Docker，IT 需 Docker Desktop。

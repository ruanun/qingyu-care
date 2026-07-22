# Phase 4.4 A/B 测试实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为应用提供问答策略 A/B 实验能力——开放流量按权重会话级分桶到 2~N 个策略变体，实时对比质量指标。

**Architecture:** 新增 `AbExperiment` 实体（应用级、状态机 DRAFT→RUNNING→ENDED）+ 会话打标（`ChatSession` 三个可空字段）+ `ChatServiceImpl` 取策略点替换为 `resolveStrategy(session)` + native SQL 实时报表（rollup 零改动）。顺带接线 `ChatStrategy.promptTemplateId`（既有字段未使用的缺口）。

**Tech Stack:** Spring Boot 3 / JPA(Hibernate Filter 多租户) / JdbcTemplate native 聚合 / Mockito 单测 / Testcontainers IT / React 19 + shadcn 前端。

**Spec:** `docs/superpowers/specs/2026-07-22-phase4-ab-testing-design.md`（本计划一切以 spec 为准）

## Global Constraints

- 全程简体中文（代码注释、commit 信息）；文件 author 写 `ruan`。
- 工作分支 `feature/phase4-ab-testing`（已存在）；每任务结束 commit；**不 push**。
- 后端命令在 `backend/` 下执行；前端命令在 `frontend/` 下执行（pnpm）。
- 单元测试：纯 Mockito（`@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`），不启 Spring 容器、不需要 Docker；类名 `*Test`。
- 集成测试：类名 `*IT`、放 `com.qingyu.care.it` 包、继承 `com.qingyu.care.support.AbstractPostgresIT`；需 Docker Desktop；**mock AI 用 `@MockitoBean ModelRouterService`，绝不 mock VectorStore**（会破坏共享表，见 StreamPersistenceIT 注释）。
- 管理端业务校验失败抛 `BusinessException`（HTTP 200 + code 400）；开放端由 `OpenApiExceptionHandler` 映射真实状态码。
- 实验链路全程 fail-soft：任何异常不阻断对话，回退默认策略。
- 新前端页面必须包 `<RequireApp>`；HTTP 走 `api/client.ts` 单例。
- `ChatServiceImpl`/`ChatStrategyService` 构造器新增依赖后，若既有单测报错，在对应测试类补 `@Mock` 字段即可。

---

### Task 1: 实体脚手架（枚举 + 实验实体 + 仓库 + 会话打标字段）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/enums/ExperimentStatus.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/AbVariant.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/AbExperiment.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/AbExperimentRepository.java`
- Modify: `backend/src/main/java/com/qingyu/care/entity/ChatSession.java`（`endedAt` 字段之后追加三字段）

**Interfaces:**
- Produces: `ExperimentStatus{DRAFT,RUNNING,ENDED}`；`AbExperiment`（getters：`getId/getName/getDescription/getStatus/getVariants/getStartedAt/getEndedAt`）；`AbVariant{getName():String, getStrategyId():Long, getWeight():Integer}`；`AbExperimentRepository`（方法见下）；`ChatSession.get/setExperimentId(Long)、get/setVariantName(String)、get/setVariantStrategyId(Long)`。

- [ ] **Step 1: 创建枚举**

```java
package com.qingyu.care.enums;

/**
 * A/B 实验状态：DRAFT →(start)→ RUNNING →(end)→ ENDED，不可逆、不可跳步。
 *
 * @author ruan
 */
public enum ExperimentStatus {
    DRAFT, RUNNING, ENDED
}
```

- [ ] **Step 2: 创建变体嵌入类**

```java
package com.qingyu.care.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A/B 实验变体（嵌入表 ab_experiment_variant）。
 *
 * @author ruan
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbVariant {

    /** 变体名（实验内唯一，报表分组键） */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 绑定的问答策略 ID */
    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    /** 流量权重（0~100 整数，实验内合计 100；0 = 不接流量/软下线） */
    @Column(name = "weight", nullable = false)
    private Integer weight;
}
```

- [ ] **Step 3: 创建实验实体**

```java
package com.qingyu.care.entity;

import com.qingyu.care.enums.ExperimentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A/B 实验实体（应用级归属；同应用同时最多一个 RUNNING，服务层校验；RUNNING 后变体锁定）。
 *
 * @author ruan
 */
@Entity
@Table(name = "ab_experiment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbExperiment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ExperimentStatus status = ExperimentStatus.DRAFT;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ab_experiment_variant", joinColumns = @JoinColumn(name = "experiment_id"))
    @Builder.Default
    private List<AbVariant> variants = new ArrayList<>();

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: 创建仓库**

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.enums.ExperimentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * A/B 实验数据访问（实体继承 BaseTenantEntity，租户/应用过滤自动生效）。
 *
 * @author ruan
 */
public interface AbExperimentRepository extends JpaRepository<AbExperiment, Long> {

    /** 当前租户/应用范围内是否存在指定状态实验（范围由 Hibernate 过滤器限定） */
    boolean existsByStatus(ExperimentStatus status);

    /** 取当前范围内一个指定状态实验（同应用最多一个 RUNNING，findFirst 足够） */
    Optional<AbExperiment> findFirstByStatus(ExperimentStatus status);

    List<AbExperiment> findAllByOrderByCreatedAtDesc();

    /** 指定策略是否被指定状态的实验引用（策略删除保护用；派生查询跨 @ElementCollection 属性路径） */
    boolean existsByStatusAndVariants_StrategyId(ExperimentStatus status, Long strategyId);
}
```

- [ ] **Step 5: ChatSession 追加三字段**（`endedAt` 字段声明之后）

```java
    /** 参与的 A/B 实验 ID（null = 未参与实验） */
    @Column(name = "experiment_id")
    private Long experimentId;

    /** A/B 变体名（报表分组键） */
    @Column(name = "variant_name", length = 50)
    private String variantName;

    /** 变体绑定的策略 ID（对话取策略免 join 实验定义） */
    @Column(name = "variant_strategy_id")
    private Long variantStrategyId;
```

- [ ] **Step 6: 编译验证**

Run: `cd backend && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/enums/ExperimentStatus.java backend/src/main/java/com/qingyu/care/entity/AbVariant.java backend/src/main/java/com/qingyu/care/entity/AbExperiment.java backend/src/main/java/com/qingyu/care/repository/AbExperimentRepository.java backend/src/main/java/com/qingyu/care/entity/ChatSession.java
git commit -m "feat: A/B 实验实体脚手架（实验/变体/仓库 + 会话打标字段）"
```

---

### Task 2: AbTestService 生命周期（create/update/start/end/delete）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/AbTestService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/AbTestServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的实体与仓库。
- Produces: `AbTestService` 方法——`List<AbExperiment> list()`、`AbExperiment getById(Long)`（缺失抛 `ResourceNotFoundException`）、`AbExperiment create(AbExperiment)`、`AbExperiment update(Long, AbExperiment)`、`AbExperiment start(Long)`、`AbExperiment end(Long)`、`void delete(Long)`。构造依赖：`AbExperimentRepository repository`、`ChatStrategyRepository strategyRepository`（`@RequiredArgsConstructor`）。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A/B 实验生命周期与校验单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AbTestServiceTest {

    @Mock AbExperimentRepository repository;
    @Mock ChatStrategyRepository strategyRepository;
    @InjectMocks AbTestService service;

    private AbExperiment draft(AbVariant... variants) {
        return AbExperiment.builder().id(1L).name("实验")
                .status(ExperimentStatus.DRAFT)
                .variants(new ArrayList<>(List.of(variants))).build();
    }

    private AbVariant v(String name, Long strategyId, int weight) {
        return AbVariant.builder().name(name).strategyId(strategyId).weight(weight).build();
    }

    private ChatStrategy enabledStrategy(Long id) {
        return ChatStrategy.builder().id(id).name("s" + id).scenario("default").enabled(true).build();
    }

    @Test
    void create_强制置为草稿并清运行时字段() {
        AbExperiment input = AbExperiment.builder().id(99L).name("新实验")
                .status(ExperimentStatus.RUNNING)
                .variants(new ArrayList<>(List.of(v("A", 1L, 50), v("B", 2L, 50)))).build();
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbExperiment saved = service.create(input);

        assertThat(saved.getId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(ExperimentStatus.DRAFT);
        assertThat(saved.getStartedAt()).isNull();
    }

    @Test
    void start_成功_置运行状态与开始时间() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(enabledStrategy(1L)));
        when(strategyRepository.findById(2L)).thenReturn(Optional.of(enabledStrategy(2L)));
        when(repository.existsByStatus(ExperimentStatus.RUNNING)).thenReturn(false);
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbExperiment started = service.start(1L);

        assertThat(started.getStatus()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(started.getStartedAt()).isNotNull();
    }

    @Test
    void start_权重0变体合法() {
        AbExperiment exp = draft(v("A", 1L, 100), v("B", 2L, 0));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(enabledStrategy(1L)));
        when(strategyRepository.findById(2L)).thenReturn(Optional.of(enabledStrategy(2L)));
        when(repository.existsByStatus(ExperimentStatus.RUNNING)).thenReturn(false);
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.start(1L).getStatus()).isEqualTo(ExperimentStatus.RUNNING);
    }

    @Test
    void start_非草稿_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setStatus(ExperimentStatus.ENDED);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("草稿");
    }

    @Test
    void start_变体不足两个_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(draft(v("A", 1L, 100))));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2 个变体");
    }

    @Test
    void start_权重和非100_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(draft(v("A", 1L, 60), v("B", 2L, 50))));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("100");
    }

    @Test
    void start_变体名重复_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(draft(v("A", 1L, 50), v("A", 2L, 50))));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重复");
    }

    @Test
    void start_策略未启用_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        ChatStrategy disabled = enabledStrategy(1L);
        disabled.setEnabled(false);
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未启用");
    }

    @Test
    void start_已有运行中实验_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(enabledStrategy(1L)));
        when(strategyRepository.findById(2L)).thenReturn(Optional.of(enabledStrategy(2L)));
        when(repository.existsByStatus(ExperimentStatus.RUNNING)).thenReturn(true);
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("运行中");
    }

    @Test
    void end_运行中_成功置结束() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setStatus(ExperimentStatus.RUNNING);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbExperiment ended = service.end(1L);
        assertThat(ended.getStatus()).isEqualTo(ExperimentStatus.ENDED);
        assertThat(ended.getEndedAt()).isNotNull();
    }

    @Test
    void end_非运行中_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(draft(v("A", 1L, 50), v("B", 2L, 50))));
        assertThatThrownBy(() -> service.end(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void update_非草稿_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setStatus(ExperimentStatus.RUNNING);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        assertThatThrownBy(() -> service.update(1L, AbExperiment.builder().name("改名").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("草稿");
    }

    @Test
    void delete_运行中_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setStatus(ExperimentStatus.RUNNING);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BusinessException.class);
        verify(repository, never()).delete(any(AbExperiment.class));
    }

    @Test
    void delete_草稿_成功() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        service.delete(1L);
        verify(repository).delete(exp);
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `cd backend && mvn test -Dtest=AbTestServiceTest -q`
Expected: 编译失败（`AbTestService` 不存在）

- [ ] **Step 3: 实现 AbTestService**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A/B 实验服务：生命周期管理（本类）+ 分桶/变体解析/报表（后续任务追加）。
 * 实验实体继承 BaseTenantEntity，list/getById 等查询天然限定当前租户/应用范围。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbTestService {

    private final AbExperimentRepository repository;
    private final ChatStrategyRepository strategyRepository;

    public List<AbExperiment> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public AbExperiment getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("A/B 实验", id.toString()));
    }

    /** 创建：强制 DRAFT，清空运行时字段；变体只做基础校验（和=100 延迟到 start 强校验） */
    @Transactional
    public AbExperiment create(AbExperiment input) {
        input.setId(null);
        input.setStatus(ExperimentStatus.DRAFT);
        input.setStartedAt(null);
        input.setEndedAt(null);
        validateVariantBasics(input.getVariants());
        return repository.save(input);
    }

    /** 编辑：仅 DRAFT 可改（RUNNING 后变体锁定） */
    @Transactional
    public AbExperiment update(Long id, AbExperiment patch) {
        AbExperiment existing = getById(id);
        if (existing.getStatus() != ExperimentStatus.DRAFT) {
            throw new BusinessException("仅草稿状态的实验可编辑");
        }
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getVariants() != null && !patch.getVariants().isEmpty()) {
            validateVariantBasics(patch.getVariants());
            existing.getVariants().clear();
            existing.getVariants().addAll(patch.getVariants());
        }
        return repository.save(existing);
    }

    /** 启动：全量校验后 DRAFT → RUNNING */
    @Transactional
    public AbExperiment start(Long id) {
        AbExperiment exp = getById(id);
        if (exp.getStatus() != ExperimentStatus.DRAFT) {
            throw new BusinessException("仅草稿状态的实验可启动");
        }
        List<AbVariant> variants = exp.getVariants();
        if (variants == null || variants.size() < 2) {
            throw new BusinessException("实验至少需要 2 个变体");
        }
        validateVariantBasics(variants);
        int sum = variants.stream().mapToInt(AbVariant::getWeight).sum();
        if (sum != 100) {
            throw new BusinessException("变体权重合计必须为 100，当前为 " + sum);
        }
        for (AbVariant v : variants) {
            ChatStrategy s = strategyRepository.findById(v.getStrategyId())
                    .orElseThrow(() -> new BusinessException(
                            "变体 " + v.getName() + " 绑定的策略不存在（id=" + v.getStrategyId() + "）"));
            if (!Boolean.TRUE.equals(s.getEnabled())) {
                throw new BusinessException("变体 " + v.getName() + " 绑定的策略未启用");
            }
        }
        if (repository.existsByStatus(ExperimentStatus.RUNNING)) {
            throw new BusinessException("当前应用已有运行中的实验，请先结束后再启动新实验");
        }
        exp.setStatus(ExperimentStatus.RUNNING);
        exp.setStartedAt(LocalDateTime.now());
        return repository.save(exp);
    }

    /** 结束：RUNNING → ENDED，存量会话下一条消息即回归默认策略 */
    @Transactional
    public AbExperiment end(Long id) {
        AbExperiment exp = getById(id);
        if (exp.getStatus() != ExperimentStatus.RUNNING) {
            throw new BusinessException("仅运行中的实验可结束");
        }
        exp.setStatus(ExperimentStatus.ENDED);
        exp.setEndedAt(LocalDateTime.now());
        return repository.save(exp);
    }

    /** 删除：仅 DRAFT / ENDED（存量会话标记悬空无害，resolve 时自动回默认） */
    @Transactional
    public void delete(Long id) {
        AbExperiment exp = getById(id);
        if (exp.getStatus() == ExperimentStatus.RUNNING) {
            throw new BusinessException("运行中的实验不能删除，请先结束");
        }
        repository.delete(exp);
    }

    /** 基础合法性：变体名非空不重复、权重 0~100、必须绑定策略（权重和只在 start 强校验） */
    private void validateVariantBasics(List<AbVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        long distinct = variants.stream().map(AbVariant::getName)
                .filter(n -> n != null && !n.isBlank()).distinct().count();
        if (distinct != variants.size()) {
            throw new BusinessException("变体名不能为空且不能重复");
        }
        for (AbVariant v : variants) {
            if (v.getWeight() == null || v.getWeight() < 0 || v.getWeight() > 100) {
                throw new BusinessException("变体权重必须为 0~100 的整数");
            }
            if (v.getStrategyId() == null) {
                throw new BusinessException("变体必须绑定一条问答策略");
            }
        }
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `cd backend && mvn test -Dtest=AbTestServiceTest -q`
Expected: Tests run: 14, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/AbTestService.java backend/src/test/java/com/qingyu/care/service/AbTestServiceTest.java
git commit -m "feat: A/B 实验生命周期服务（状态机 + 启动全量校验）"
```

---

### Task 3: 分桶与变体策略解析（assign / pickVariant / resolveVariantStrategy）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/AbTestService.java`（追加方法）
- Test: `backend/src/test/java/com/qingyu/care/service/AbTestAssignTest.java`

**Interfaces:**
- Produces（追加到 `AbTestService`）：
  - `public record VariantAssignment(Long experimentId, String variantName, Long variantStrategyId)`（嵌套在 AbTestService 内）
  - `Optional<VariantAssignment> assignForNewSession()`
  - `static AbVariant pickVariant(List<AbVariant> variants, int roll)`（纯函数，roll ∈ [0,100)）
  - `Optional<ChatStrategy> resolveVariantStrategy(Long experimentId, Long variantStrategyId)`

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 分桶纯函数 + 会话分配 + 变体策略解析单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AbTestAssignTest {

    @Mock AbExperimentRepository repository;
    @Mock ChatStrategyRepository strategyRepository;
    @InjectMocks AbTestService service;

    private AbVariant v(String name, Long strategyId, int weight) {
        return AbVariant.builder().name(name).strategyId(strategyId).weight(weight).build();
    }

    @Test
    void pickVariant_按累计权重区间取变体() {
        List<AbVariant> variants = List.of(v("A", 1L, 50), v("B", 2L, 50));
        assertThat(AbTestService.pickVariant(variants, 0).getName()).isEqualTo("A");
        assertThat(AbTestService.pickVariant(variants, 49).getName()).isEqualTo("A");
        assertThat(AbTestService.pickVariant(variants, 50).getName()).isEqualTo("B");
        assertThat(AbTestService.pickVariant(variants, 99).getName()).isEqualTo("B");
    }

    @Test
    void pickVariant_权重0变体永不选中() {
        List<AbVariant> variants = List.of(v("A", 1L, 0), v("B", 2L, 100));
        for (int roll = 0; roll < 100; roll++) {
            assertThat(AbTestService.pickVariant(variants, roll).getName()).isEqualTo("B");
        }
    }

    @Test
    void assign_无运行实验_返回empty() {
        when(repository.findFirstByStatus(ExperimentStatus.RUNNING)).thenReturn(Optional.empty());
        assertThat(service.assignForNewSession()).isEmpty();
    }

    @Test
    void assign_有运行实验_返回三元组() {
        AbExperiment exp = AbExperiment.builder().id(7L).name("e")
                .status(ExperimentStatus.RUNNING)
                .variants(new ArrayList<>(List.of(v("B", 42L, 100)))).build();
        when(repository.findFirstByStatus(ExperimentStatus.RUNNING)).thenReturn(Optional.of(exp));

        var assignment = service.assignForNewSession();

        assertThat(assignment).isPresent();
        assertThat(assignment.get().experimentId()).isEqualTo(7L);
        assertThat(assignment.get().variantName()).isEqualTo("B");
        assertThat(assignment.get().variantStrategyId()).isEqualTo(42L);
    }

    @Test
    void assign_仓库异常_failSoft返回empty() {
        when(repository.findFirstByStatus(ExperimentStatus.RUNNING))
                .thenThrow(new RuntimeException("db down"));
        assertThat(service.assignForNewSession()).isEmpty();
    }

    @Test
    void resolve_参数为空_返回empty() {
        assertThat(service.resolveVariantStrategy(null, 42L)).isEmpty();
        assertThat(service.resolveVariantStrategy(7L, null)).isEmpty();
    }

    @Test
    void resolve_实验已结束_返回empty() {
        AbExperiment ended = AbExperiment.builder().id(7L).name("e")
                .status(ExperimentStatus.ENDED).build();
        when(repository.findById(7L)).thenReturn(Optional.of(ended));
        assertThat(service.resolveVariantStrategy(7L, 42L)).isEmpty();
    }

    @Test
    void resolve_策略被禁用_返回empty() {
        AbExperiment running = AbExperiment.builder().id(7L).name("e")
                .status(ExperimentStatus.RUNNING).build();
        when(repository.findById(7L)).thenReturn(Optional.of(running));
        ChatStrategy disabled = ChatStrategy.builder().id(42L).name("s").scenario("default")
                .enabled(false).build();
        when(strategyRepository.findById(42L)).thenReturn(Optional.of(disabled));
        assertThat(service.resolveVariantStrategy(7L, 42L)).isEmpty();
    }

    @Test
    void resolve_正常_返回变体策略() {
        AbExperiment running = AbExperiment.builder().id(7L).name("e")
                .status(ExperimentStatus.RUNNING).build();
        when(repository.findById(7L)).thenReturn(Optional.of(running));
        ChatStrategy enabled = ChatStrategy.builder().id(42L).name("s").scenario("default")
                .enabled(true).build();
        when(strategyRepository.findById(42L)).thenReturn(Optional.of(enabled));
        assertThat(service.resolveVariantStrategy(7L, 42L)).contains(enabled);
    }

    @Test
    void resolve_仓库异常_failSoft返回empty() {
        when(repository.findById(7L)).thenThrow(new RuntimeException("db down"));
        assertThat(service.resolveVariantStrategy(7L, 42L)).isEmpty();
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `cd backend && mvn test -Dtest=AbTestAssignTest -q`
Expected: 编译失败（方法不存在）

- [ ] **Step 3: 在 AbTestService 追加实现**（类末尾，`validateVariantBasics` 之前）

```java
    /** 分桶结果三元组（落 ChatSession 三字段） */
    public record VariantAssignment(Long experimentId, String variantName, Long variantStrategyId) {}

    /**
     * 为新会话分桶（仅开放路径调用）。fail-soft：无运行实验或任何异常返回 empty，
     * 会话不参与实验、不阻断对话。
     */
    public java.util.Optional<VariantAssignment> assignForNewSession() {
        try {
            return repository.findFirstByStatus(ExperimentStatus.RUNNING)
                    .map(exp -> {
                        AbVariant v = pickVariant(exp.getVariants(),
                                java.util.concurrent.ThreadLocalRandom.current().nextInt(100));
                        return new VariantAssignment(exp.getId(), v.getName(), v.getStrategyId());
                    });
        } catch (Exception e) {
            log.warn("A/B 分桶失败，会话不参与实验: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 纯函数：按累计权重区间取变体（roll ∈ [0,100)）。权重 0 的变体区间为空，永不选中。
     * static 免容器单测（同 ChatServiceImpl.meterContentStream 惯例）。
     */
    static AbVariant pickVariant(List<AbVariant> variants, int roll) {
        int cumulative = 0;
        for (AbVariant v : variants) {
            cumulative += v.getWeight();
            if (roll < cumulative) {
                return v;
            }
        }
        // 理论不可达（启动校验保证权重和=100），防御性兜底取末位
        return variants.get(variants.size() - 1);
    }

    /**
     * 对话时解析变体策略。empty = 走默认策略（未参与实验 / 实验已结束或被删 /
     * 策略被禁用或删除 / 异常，全部 fail-soft）。
     */
    public java.util.Optional<ChatStrategy> resolveVariantStrategy(Long experimentId, Long variantStrategyId) {
        if (experimentId == null || variantStrategyId == null) {
            return java.util.Optional.empty();
        }
        try {
            return repository.findById(experimentId)
                    .filter(e -> e.getStatus() == ExperimentStatus.RUNNING)
                    .flatMap(e -> strategyRepository.findById(variantStrategyId))
                    .filter(s -> Boolean.TRUE.equals(s.getEnabled()));
        } catch (Exception e) {
            log.warn("解析变体策略失败，回退默认策略: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }
```

- [ ] **Step 4: 运行验证通过**

Run: `cd backend && mvn test -Dtest=AbTestAssignTest -q`
Expected: Tests run: 10, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/AbTestService.java backend/src/test/java/com/qingyu/care/service/AbTestAssignTest.java
git commit -m "feat: A/B 权重分桶与变体策略解析（全程 fail-soft）"
```

---

### Task 4: 开放渠道标记与新会话分桶接线

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/dto/ChatRequest.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`（`toInternal` 方法）
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java`（`chat`/`streamChat` 两方法）
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（新增 `AbTestService` 依赖 + `createNewSession` 改造）
- Test: `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceAbWiringTest.java`

**Interfaces:**
- Consumes: `AbTestService.assignForNewSession()`（Task 3）。
- Produces: `ChatRequest.isOpenChannel()/setOpenChannel(boolean)`（`@JsonIgnore`，请求体不可注入）；`ChatServiceImpl.createNewSession` 改为 **package-private**（同 `persistStreamedMessages` 便于同包单测的惯例）。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service.impl;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放渠道新会话分桶接线单测（createNewSession package-private 直测）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceAbWiringTest {

    @Mock VectorStore vectorStore;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock PromptTemplateService promptTemplateService;
    @Mock HandoverRuleService handoverRuleService;
    @Mock ModelRouterService modelRouterService;
    @Mock ChatStrategyService chatStrategyService;
    @Mock HybridRetrievalService hybridRetrievalService;
    @Mock RetrievalScopeResolver retrievalScopeResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock AbTestService abTestService;
    @InjectMocks ChatServiceImpl chatService;

    @Test
    void 开放渠道_新会话落实验标记() {
        when(abTestService.assignForNewSession()).thenReturn(Optional.of(
                new AbTestService.VariantAssignment(7L, "B", 42L)));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatSession session = chatService.createNewSession(
                ChatRequest.builder().message("hi").openChannel(true).build());

        assertThat(session.getExperimentId()).isEqualTo(7L);
        assertThat(session.getVariantName()).isEqualTo("B");
        assertThat(session.getVariantStrategyId()).isEqualTo(42L);
    }

    @Test
    void 非开放渠道_不调用分桶() {
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatSession session = chatService.createNewSession(
                ChatRequest.builder().message("hi").build());

        verify(abTestService, never()).assignForNewSession();
        assertThat(session.getExperimentId()).isNull();
    }

    @Test
    void 开放渠道_无运行实验_不落标() {
        when(abTestService.assignForNewSession()).thenReturn(Optional.empty());
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatSession session = chatService.createNewSession(
                ChatRequest.builder().message("hi").openChannel(true).build());

        assertThat(session.getExperimentId()).isNull();
        assertThat(session.getVariantName()).isNull();
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `cd backend && mvn test -Dtest=ChatServiceAbWiringTest -q`
Expected: 编译失败（`openChannel` / `createNewSession` 不可见 / 构造器缺依赖）

- [ ] **Step 3: 实现四处改动**

`ChatRequest.java`——`stream` 字段之后追加：

```java
    /**
     * 是否来自开放渠道（/open/v1，参与 A/B 分流）。内部字段：
     * @JsonIgnore 禁止从请求体注入，防手工构造 JSON 混入分流污染实验样本。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Builder.Default
    private boolean openChannel = false;
```

`OpenChatController.toInternal`——builder 链加一行 `.openChannel(true)`：

```java
    private ChatRequest toInternal(OpenChatRequest req, boolean stream) {
        return ChatRequest.builder()
                .sessionId(req.getSessionId())
                .userId(req.getUserId())
                .message(req.getMessage())
                .stream(stream)
                .openChannel(true)
                .build();
    }
```

`ChatController.chat` 与 `streamChat`——方法体首行各加（双保险，@JsonIgnore 已挡请求体注入）：

```java
        request.setOpenChannel(false);
```

`ChatServiceImpl`——字段区追加依赖（`private final TransactionTemplate transactionTemplate;` 之后）：

```java
    private final AbTestService abTestService;
```

`import com.qingyu.care.service.AbTestService;` 加入 import 区。`createNewSession` 替换为（private → package-private）：

```java
    /** package-private 便于同包单测（同 persistStreamedMessages 惯例） */
    ChatSession createNewSession(ChatRequest request) {
        ChatSession session = ChatSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .status(SessionStatus.AI_PROCESSING)
                .build();
        // 仅开放渠道参与 A/B 分流（管理后台测试对话不污染实验样本）
        if (request.isOpenChannel()) {
            abTestService.assignForNewSession().ifPresent(a -> {
                session.setExperimentId(a.experimentId());
                session.setVariantName(a.variantName());
                session.setVariantStrategyId(a.variantStrategyId());
            });
        }
        ChatSession saved = sessionRepository.save(session);
        log.info("Session {} created for user {}", saved.getSessionId(), saved.getUserId());
        return saved;
    }
```

- [ ] **Step 4: 运行验证通过 + 全量单测回归**

Run: `cd backend && mvn test -Dtest=ChatServiceAbWiringTest -q` → Tests run: 3, Failures: 0
Run: `cd backend && mvn test -q` → BUILD SUCCESS（若既有 ChatService 相关测试因新构造依赖报错，在其测试类补 `@Mock AbTestService`）

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/dto/ChatRequest.java backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java backend/src/main/java/com/qingyu/care/controller/ChatController.java backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java backend/src/test/java/com/qingyu/care/service/impl/ChatServiceAbWiringTest.java
git commit -m "feat: 开放渠道标记 + 新会话 A/B 分桶落标（@JsonIgnore 防样本污染）"
```

---

### Task 5: 对话取策略与 Prompt 模板接线

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceStrategyResolveTest.java`

**Interfaces:**
- Consumes: `AbTestService.resolveVariantStrategy(Long, Long)`（Task 3）；`PromptTemplateService.getById(Long)`（既有，返回 `PromptTemplate`，缺失抛 `ResourceNotFoundException`）。
- Produces: `ChatServiceImpl` package-private 方法 `ChatStrategy resolveStrategy(ChatSession session)`、`String resolveSystemTemplate(ChatStrategy strategy)`；`buildMessages` 签名变为 `(ChatSession, String, String, ChatStrategy)`。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service.impl;

import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话取策略（变体优先/回退默认）与 Prompt 模板接线单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceStrategyResolveTest {

    @Mock VectorStore vectorStore;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock PromptTemplateService promptTemplateService;
    @Mock HandoverRuleService handoverRuleService;
    @Mock ModelRouterService modelRouterService;
    @Mock ChatStrategyService chatStrategyService;
    @Mock HybridRetrievalService hybridRetrievalService;
    @Mock RetrievalScopeResolver retrievalScopeResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock AbTestService abTestService;
    @InjectMocks ChatServiceImpl chatService;

    @Test
    void 会话带有效变体_使用变体策略() {
        ChatSession session = ChatSession.builder().build();
        session.setExperimentId(7L);
        session.setVariantStrategyId(42L);
        ChatStrategy variant = ChatStrategy.builder().name("变体B").scenario("default").build();
        when(abTestService.resolveVariantStrategy(7L, 42L)).thenReturn(Optional.of(variant));

        assertThat(chatService.resolveStrategy(session)).isSameAs(variant);
        verify(chatStrategyService, never()).getActiveStrategy(any());
    }

    @Test
    void 无变体_回退默认策略() {
        ChatSession session = ChatSession.builder().build();
        ChatStrategy def = ChatStrategy.builder().name("默认").scenario("default").build();
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(def);

        assertThat(chatService.resolveStrategy(session)).isSameAs(def);
    }

    @Test
    void 策略绑定模板_优先使用其内容() {
        PromptTemplate tpl = PromptTemplate.builder().content("绑定模板 {context}").build();
        when(promptTemplateService.getById(9L)).thenReturn(tpl);

        String result = chatService.resolveSystemTemplate(
                ChatStrategy.builder().promptTemplateId(9L).build());

        assertThat(result).isEqualTo("绑定模板 {context}");
        verify(promptTemplateService, never()).getActiveTemplateContent();
    }

    @Test
    void 绑定模板失效_failSoft回退激活模板() {
        when(promptTemplateService.getById(9L))
                .thenThrow(new ResourceNotFoundException("Prompt 模板", "9"));
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("激活模板");

        assertThat(chatService.resolveSystemTemplate(
                ChatStrategy.builder().promptTemplateId(9L).build())).isEqualTo("激活模板");
    }

    @Test
    void 未绑定模板_使用激活模板() {
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("激活模板");
        assertThat(chatService.resolveSystemTemplate(ChatStrategy.builder().build()))
                .isEqualTo("激活模板");
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `cd backend && mvn test -Dtest=ChatServiceStrategyResolveTest -q`
Expected: 编译失败（方法不存在）

- [ ] **Step 3: 实现 ChatServiceImpl 改动**

新增两个 package-private 方法（放在 `getOrCreateSession` 之前）：

```java
    /**
     * 取本次对话生效的策略：会话带有效变体（实验仍 RUNNING 且策略可用）→ 变体策略；
     * 其余一切情形回退默认策略。package-private 便于同包单测。
     */
    ChatStrategy resolveStrategy(ChatSession session) {
        return abTestService.resolveVariantStrategy(session.getExperimentId(), session.getVariantStrategyId())
                .orElseGet(() -> chatStrategyService.getActiveStrategy("default"));
    }

    /**
     * 解析系统 Prompt 模板：策略绑定 promptTemplateId 优先（闭合该字段长期未接线的缺口，
     * 取不到 fail-soft 回退），否则全局激活模板。package-private 便于同包单测。
     */
    String resolveSystemTemplate(ChatStrategy strategy) {
        if (strategy != null && strategy.getPromptTemplateId() != null) {
            try {
                return promptTemplateService.getById(strategy.getPromptTemplateId()).getContent();
            } catch (Exception e) {
                log.warn("策略绑定的 Prompt 模板不可用（id={}），回退全局激活模板: {}",
                        strategy.getPromptTemplateId(), e.getMessage());
            }
        }
        return promptTemplateService.getActiveTemplateContent();
    }
```

三处调用点替换：

1. `chat()` 中 `ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");` →
   `ChatStrategy strategy = resolveStrategy(session);`（注释同步改为「获取策略配置（会话带 A/B 变体时用变体策略）」）
2. `streamChat(...)` 中同样替换 `ChatStrategy strategy = chatStrategyService.getActiveStrategy("default");` → `ChatStrategy strategy = resolveStrategy(session);`
3. `buildMessages` 签名与首段改为：

```java
    private List<Message> buildMessages(ChatSession session, String userMessage, String context,
                                        ChatStrategy strategy) {
        List<Message> messages = new ArrayList<>();

        // 系统消息 - 策略绑定模板优先，否则全局激活模板
        String systemTemplate = resolveSystemTemplate(strategy);
        String systemPrompt = systemTemplate.replace("{context}", context);
        messages.add(new SystemMessage(systemPrompt));
```

（方法其余部分不变。）`chat()` 与 `streamChat()` 中的调用改为 `buildMessages(session, request.getMessage(), context, strategy)`。

- [ ] **Step 4: 运行验证通过 + 全量单测回归**

Run: `cd backend && mvn test -Dtest=ChatServiceStrategyResolveTest -q` → Tests run: 5, Failures: 0
Run: `cd backend && mvn test -q` → BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java backend/src/test/java/com/qingyu/care/service/impl/ChatServiceStrategyResolveTest.java
git commit -m "feat: 对话按会话变体取策略 + 接线策略绑定 Prompt 模板（闭合既有缺口）"
```

---

### Task 6: 策略删除保护

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatStrategyService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ChatStrategyDeleteGuardTest.java`

**Interfaces:**
- Consumes: `AbExperimentRepository.existsByStatusAndVariants_StrategyId(ExperimentStatus, Long)`（Task 1）。
- Produces: `ChatStrategyService` 构造器新增 `AbExperimentRepository abExperimentRepository` 依赖。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略删除保护：被运行中 A/B 实验引用的策略禁删。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatStrategyDeleteGuardTest {

    @Mock ChatStrategyRepository repository;
    @Mock AbExperimentRepository abExperimentRepository;
    @InjectMocks ChatStrategyService service;

    @Test
    void 被运行实验引用_拒绝删除() {
        when(repository.findById(5L)).thenReturn(Optional.of(
                ChatStrategy.builder().id(5L).name("s").scenario("faq").isDefault(false).build()));
        when(abExperimentRepository.existsByStatusAndVariants_StrategyId(ExperimentStatus.RUNNING, 5L))
                .thenReturn(true);

        assertThatThrownBy(() -> service.deleteStrategy(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("运行中的 A/B 实验");
        verify(repository, never()).deleteById(any());
    }

    @Test
    void 未被引用_正常删除() {
        when(repository.findById(5L)).thenReturn(Optional.of(
                ChatStrategy.builder().id(5L).name("s").scenario("faq").isDefault(false).build()));
        when(abExperimentRepository.existsByStatusAndVariants_StrategyId(ExperimentStatus.RUNNING, 5L))
                .thenReturn(false);

        service.deleteStrategy(5L);
        verify(repository).deleteById(5L);
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `cd backend && mvn test -Dtest=ChatStrategyDeleteGuardTest -q`
Expected: 编译失败或断言失败（保护逻辑不存在）

- [ ] **Step 3: 实现**

`ChatStrategyService` 字段区追加依赖与 import（`ExperimentStatus`、`BusinessException`、`AbExperimentRepository`）：

```java
    private final AbExperimentRepository abExperimentRepository;
```

`deleteStrategy` 在既有默认策略校验之后、`deleteById` 之前插入：

```java
        if (abExperimentRepository.existsByStatusAndVariants_StrategyId(
                com.qingyu.care.enums.ExperimentStatus.RUNNING, id)) {
            throw new com.qingyu.care.exception.BusinessException(
                    "该策略正被运行中的 A/B 实验引用，无法删除");
        }
```

- [ ] **Step 4: 运行验证通过 + 全量单测回归**

Run: `cd backend && mvn test -Dtest=ChatStrategyDeleteGuardTest -q` → Tests run: 2, Failures: 0
Run: `cd backend && mvn test -q` → BUILD SUCCESS（既有 ChatStrategyService 相关测试若因构造器新依赖报错，补 `@Mock AbExperimentRepository`）

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatStrategyService.java backend/src/test/java/com/qingyu/care/service/ChatStrategyDeleteGuardTest.java
git commit -m "feat: 策略删除保护（被运行中 A/B 实验引用拒删）"
```

---

### Task 7: 报表聚合（DAO + DTO + getReport）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/repository/AbTestReportDao.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/AbTestReportDTO.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/AbTestService.java`（新增 `AbTestReportDao` 依赖 + `getReport`/`rate`）
- Test: `backend/src/test/java/com/qingyu/care/service/AbTestReportTest.java`

**Interfaces:**
- Produces: `AbTestService.getReport(Long id): AbTestReportDTO`；`static Double rate(long, long)`（分母 0 → null，4 位小数）；DAO records `SessionRow`/`MessageRow`（字段见下方代码）。
- 注意: `AbTestService` 构造器新增第三个依赖 `AbTestReportDao reportDao`——`AbTestServiceTest`/`AbTestAssignTest` 各补一个 `@Mock AbTestReportDao reportDao;` 字段即可。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.AbTestReportDTO;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.AbTestReportDao;
import com.qingyu.care.repository.ChatStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * A/B 报表合并与派生率单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AbTestReportTest {

    @Mock AbExperimentRepository repository;
    @Mock ChatStrategyRepository strategyRepository;
    @Mock AbTestReportDao reportDao;
    @InjectMocks AbTestService service;

    @Test
    void rate_正常计算保留4位() {
        assertThat(AbTestService.rate(1, 3)).isEqualTo(0.3333);
        assertThat(AbTestService.rate(1, 1)).isEqualTo(1.0);
    }

    @Test
    void rate_分母为0_返回null() {
        assertThat(AbTestService.rate(0, 0)).isNull();
    }

    @Test
    void getReport_合并两侧数据_缺数据变体全0() {
        AbExperiment exp = AbExperiment.builder().id(7L).name("实验")
                .status(ExperimentStatus.RUNNING)
                .variants(new ArrayList<>(List.of(
                        AbVariant.builder().name("A").strategyId(1L).weight(50).build(),
                        AbVariant.builder().name("B").strategyId(2L).weight(50).build())))
                .build();
        when(repository.findById(7L)).thenReturn(Optional.of(exp));
        // 仅 A 有数据：2 会话 1 转人工；4 提问 4 AI 回复 2 兜底 1 赞 1 踩
        when(reportDao.aggregateSessions(7L)).thenReturn(List.of(
                new AbTestReportDao.SessionRow("A", 2, 1, 1, 1)));
        when(reportDao.aggregateMessages(7L)).thenReturn(List.of(
                new AbTestReportDao.MessageRow("A", 4, 4, 0.72, 1, 2, 3, 1, 1)));

        AbTestReportDTO report = service.getReport(7L);

        assertThat(report.variants()).hasSize(2);
        AbTestReportDTO.VariantReport a = report.variants().get(0);
        assertThat(a.variantName()).isEqualTo("A");
        assertThat(a.sessionCount()).isEqualTo(2);
        assertThat(a.handoverRate()).isEqualTo(0.5);   // 1/2
        assertThat(a.fallbackRate()).isEqualTo(0.5);   // 2/4
        assertThat(a.likeRate()).isEqualTo(0.5);       // 1/(1+1)
        assertThat(a.hitRate()).isEqualTo(0.75);       // 3/4
        assertThat(a.resolvedRate()).isEqualTo(1.0);   // 1/1
        assertThat(a.avgConfidence()).isEqualTo(0.72);

        AbTestReportDTO.VariantReport b = report.variants().get(1);
        assertThat(b.variantName()).isEqualTo("B");
        assertThat(b.sessionCount()).isZero();
        assertThat(b.likeRate()).isNull();
        assertThat(b.fallbackRate()).isNull();
        assertThat(b.avgConfidence()).isNull();
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `cd backend && mvn test -Dtest=AbTestReportTest -q`
Expected: 编译失败（DAO/DTO/方法不存在）

- [ ] **Step 3: 实现 DAO**

```java
package com.qingyu.care.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * A/B 实验报表聚合 DAO（native 绕过滤器；入参 experimentId 须先经 JPA getById 校验归属）。
 * 指标口径与 AnalyticsRollupDao.insertQuality 保持一致（低置信 <0.5、AI 回复含置信度非空等）。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class AbTestReportDao {

    private final JdbcTemplate jdbcTemplate;

    /** 会话级变体聚合行 */
    public record SessionRow(String variantName, long sessionCount, long handoverCount,
                             long resolvedCount, long endedCount) {}

    /** 消息级变体聚合行 */
    public record MessageRow(String variantName, long questionCount, long aiMsgCount,
                             Double avgConfidence, long lowConfidenceCount, long fallbackCount,
                             long hitCount, long feedbackUp, long feedbackDown) {}

    public List<SessionRow> aggregateSessions(Long experimentId) {
        return jdbcTemplate.query("""
                SELECT variant_name,
                       COUNT(*) AS session_count,
                       COUNT(*) FILTER (WHERE handover_time IS NOT NULL) AS handover_count,
                       COUNT(*) FILTER (WHERE status = 'ENDED' AND agent_id IS NULL) AS resolved_count,
                       COUNT(*) FILTER (WHERE status = 'ENDED') AS ended_count
                FROM chat_session
                WHERE experiment_id = ? AND variant_name IS NOT NULL
                GROUP BY variant_name
                """,
                (rs, i) -> new SessionRow(rs.getString("variant_name"), rs.getLong("session_count"),
                        rs.getLong("handover_count"), rs.getLong("resolved_count"),
                        rs.getLong("ended_count")),
                experimentId);
    }

    public List<MessageRow> aggregateMessages(Long experimentId) {
        return jdbcTemplate.query("""
                SELECT cs.variant_name,
                       COUNT(*) FILTER (WHERE cm.role = 'USER') AS question_count,
                       COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.confidence_score IS NOT NULL) AS ai_msg_count,
                       AVG(cm.confidence_score) FILTER (WHERE cm.role = 'ASSISTANT') AS avg_confidence,
                       COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.confidence_score < 0.5) AS low_confidence_count,
                       COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.fallback = TRUE) AS fallback_count,
                       COUNT(*) FILTER (WHERE cm.role = 'ASSISTANT' AND cm.hit_document_ids IS NOT NULL) AS hit_count,
                       COUNT(*) FILTER (WHERE cm.feedback = 1) AS feedback_up,
                       COUNT(*) FILTER (WHERE cm.feedback = -1) AS feedback_down
                FROM chat_message cm
                JOIN chat_session cs ON cm.session_id = cs.id
                WHERE cs.experiment_id = ? AND cs.variant_name IS NOT NULL
                GROUP BY cs.variant_name
                """,
                (rs, i) -> new MessageRow(rs.getString("variant_name"), rs.getLong("question_count"),
                        rs.getLong("ai_msg_count"), (Double) rs.getObject("avg_confidence"),
                        rs.getLong("low_confidence_count"), rs.getLong("fallback_count"),
                        rs.getLong("hit_count"), rs.getLong("feedback_up"), rs.getLong("feedback_down")),
                experimentId);
    }
}
```

- [ ] **Step 4: 实现 DTO**

```java
package com.qingyu.care.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A/B 实验对比报表：每变体一行，样本量 + 派生率。
 * 率的分母为 0 时为 null；平均置信度无 AI 回复时为 null。
 *
 * @author ruan
 */
public record AbTestReportDTO(
        Long experimentId, String name, String status,
        LocalDateTime startedAt, LocalDateTime endedAt,
        List<VariantReport> variants) {

    public record VariantReport(
            String variantName, Integer weight,
            long sessionCount, long questionCount, long aiMsgCount,
            Double avgConfidence,
            long feedbackUp, long feedbackDown,
            Double likeRate,          // 赞 /(赞+踩)
            Double fallbackRate,      // 兜底 / AI 回复
            Double lowConfidenceRate, // 低置信(<0.5) / AI 回复
            Double hitRate,           // 命中知识 / AI 回复
            Double handoverRate,      // 转人工 / 会话
            Double resolvedRate       // AI 独立解决 / 已结束会话
    ) {}
}
```

- [ ] **Step 5: AbTestService 追加依赖与方法**

字段区追加 `private final AbTestReportDao reportDao;`（import `com.qingyu.care.repository.AbTestReportDao`、`com.qingyu.care.dto.AbTestReportDTO`）。类末尾追加：

```java
    /** 实验对比报表：先 JPA 取实验（租户/应用过滤校验归属），再 native 按变体聚合 */
    public AbTestReportDTO getReport(Long id) {
        AbExperiment exp = getById(id);
        var sessionRows = reportDao.aggregateSessions(id).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AbTestReportDao.SessionRow::variantName, r -> r));
        var messageRows = reportDao.aggregateMessages(id).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AbTestReportDao.MessageRow::variantName, r -> r));
        List<AbTestReportDTO.VariantReport> variants = exp.getVariants().stream().map(v -> {
            var s = sessionRows.get(v.getName());
            var m = messageRows.get(v.getName());
            long sessionCount = s != null ? s.sessionCount() : 0;
            long handover = s != null ? s.handoverCount() : 0;
            long resolved = s != null ? s.resolvedCount() : 0;
            long ended = s != null ? s.endedCount() : 0;
            long question = m != null ? m.questionCount() : 0;
            long aiMsg = m != null ? m.aiMsgCount() : 0;
            long up = m != null ? m.feedbackUp() : 0;
            long down = m != null ? m.feedbackDown() : 0;
            return new AbTestReportDTO.VariantReport(
                    v.getName(), v.getWeight(),
                    sessionCount, question, aiMsg,
                    m != null ? m.avgConfidence() : null,
                    up, down,
                    rate(up, up + down),
                    rate(m != null ? m.fallbackCount() : 0, aiMsg),
                    rate(m != null ? m.lowConfidenceCount() : 0, aiMsg),
                    rate(m != null ? m.hitCount() : 0, aiMsg),
                    rate(handover, sessionCount),
                    rate(resolved, ended));
        }).toList();
        return new AbTestReportDTO(exp.getId(), exp.getName(), exp.getStatus().name(),
                exp.getStartedAt(), exp.getEndedAt(), variants);
    }

    /** 纯函数：比率保留 4 位小数；分母 0 → null（static 免容器单测） */
    static Double rate(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }
```

同时在 `AbTestServiceTest`、`AbTestAssignTest` 各补字段 `@Mock AbTestReportDao reportDao;`（import `com.qingyu.care.repository.AbTestReportDao`）。

- [ ] **Step 6: 运行验证通过 + 全量单测回归**

Run: `cd backend && mvn test -Dtest=AbTestReportTest -q` → Tests run: 3, Failures: 0
Run: `cd backend && mvn test -q` → BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/repository/AbTestReportDao.java backend/src/main/java/com/qingyu/care/dto/AbTestReportDTO.java backend/src/main/java/com/qingyu/care/service/AbTestService.java backend/src/test/java/com/qingyu/care/service/AbTestReportTest.java backend/src/test/java/com/qingyu/care/service/AbTestServiceTest.java backend/src/test/java/com/qingyu/care/service/AbTestAssignTest.java
git commit -m "feat: A/B 实验对比报表（native 按变体实时聚合 + 派生率）"
```

---

### Task 8: 管理控制器

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/controller/AbTestController.java`

**Interfaces:**
- Consumes: `AbTestService` 全部公开方法（Task 2/7）。
- Produces: REST 端点 `/v1/ab-experiments`（上下文路径 /api 下即 `/api/v1/ab-experiments`，与 `ChatStrategyController` 的 `/v1/strategies` 惯例一致）。

- [ ] **Step 1: 实现控制器**

```java
package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.AbTestReportDTO;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.service.AbTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A/B 实验管理 API（应用级，需 X-App-Id 请求头；实体继承 BaseTenantEntity，
 * 查询范围由多租户过滤器自动限定）。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/ab-experiments")
@RequiredArgsConstructor
public class AbTestController {

    private final AbTestService abTestService;

    @GetMapping
    public ApiResponse<List<AbExperiment>> list() {
        return ApiResponse.success(abTestService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<AbExperiment> getById(@PathVariable Long id) {
        return ApiResponse.success(abTestService.getById(id));
    }

    @PostMapping
    @AuditLog(action = "ABTEST_CREATE", targetType = "AbExperiment")
    public ApiResponse<AbExperiment> create(@RequestBody AbExperiment experiment) {
        return ApiResponse.success("实验已创建", abTestService.create(experiment));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "ABTEST_UPDATE", targetType = "AbExperiment")
    public ApiResponse<AbExperiment> update(@PathVariable Long id,
                                            @RequestBody AbExperiment experiment) {
        return ApiResponse.success("实验已更新", abTestService.update(id, experiment));
    }

    @PostMapping("/{id}/start")
    @AuditLog(action = "ABTEST_START", targetType = "AbExperiment")
    public ApiResponse<AbExperiment> start(@PathVariable Long id) {
        return ApiResponse.success("实验已启动", abTestService.start(id));
    }

    @PostMapping("/{id}/end")
    @AuditLog(action = "ABTEST_END", targetType = "AbExperiment")
    public ApiResponse<AbExperiment> end(@PathVariable Long id) {
        return ApiResponse.success("实验已结束", abTestService.end(id));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<AbTestReportDTO> report(@PathVariable Long id) {
        return ApiResponse.success(abTestService.getReport(id));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "ABTEST_DELETE", targetType = "AbExperiment")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        abTestService.delete(id);
        return ApiResponse.success("实验已删除", null);
    }
}
```

- [ ] **Step 2: 编译 + 全量单测回归**

Run: `cd backend && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/controller/AbTestController.java
git commit -m "feat: A/B 实验管理端点（CRUD/启停/报表 + 审计日志）"
```

---

### Task 9: 集成测试 AbTestFlowIT

**Files:**
- Test: `backend/src/test/java/com/qingyu/care/it/AbTestFlowIT.java`

**Interfaces:**
- Consumes: 全部前序任务；`AbstractPostgresIT.seedApp(String, String)`；`SpringAiResponses.of(String, int)`（`com.qingyu.care.it` 包既有工具，StreamPersistenceIT 同款）；`TenantContext.setTenantId/setAppId/clear`。
- 关键技巧：变体策略设 `allowNoKnowledge=false` + 专属兜底语 → 无知识库时回复即兜底语，**不经 AI 即可断言变体策略生效**（确定性）；默认策略路径才需要 mock AI。

- [ ] **Step 1: 编写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A/B 实验全链路 IT：开放路径分桶落标 → 对话走变体策略 → 报表聚合 →
 * 结束实验回归默认；多租户隔离；后台入口不分流由单测覆盖（ChatServiceAbWiringTest）。
 * mock ModelRouterService（不 mock VectorStore——会破坏共享表，见 StreamPersistenceIT 教训）。
 * 变体策略 allowNoKnowledge=false + 专属兜底语 → 断言变体生效无需 AI，确定性强。
 *
 * @author ruan
 */
class AbTestFlowIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatStrategyRepository strategyRepository;
    @Autowired AbExperimentRepository experimentRepository;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired AbTestService abTestService;

    @MockitoBean
    private ModelRouterService modelRouterService;

    /** 播种策略：显式双设 tenantId+appId（IT 线程无 TenantContext，appFilter 无 NULL 兜底） */
    private ChatStrategy seedStrategy(Application app, String name,
                                      boolean allowNoKnowledge, String fallbackMessage) {
        ChatStrategy s = ChatStrategy.builder()
                .name(name).scenario("default")
                .topK(3).similarityThreshold(0.7)
                .allowNoKnowledge(allowNoKnowledge)
                .fallbackMessage(fallbackMessage)
                .enabled(true).isDefault(false)
                .build();
        s.setTenantId(app.getTenant().getId());
        s.setAppId(app.getId());
        return strategyRepository.saveAndFlush(s);
    }

    /** 播种运行中实验：A 权重 0 / B 权重 100，保证确定性分桶到 B */
    private AbExperiment seedRunningExperiment(Application app, Long strategyA, Long strategyB) {
        AbExperiment exp = AbExperiment.builder()
                .name("IT实验").status(ExperimentStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .variants(new ArrayList<>(List.of(
                        AbVariant.builder().name("A").strategyId(strategyA).weight(0).build(),
                        AbVariant.builder().name("B").strategyId(strategyB).weight(100).build())))
                .build();
        exp.setTenantId(app.getTenant().getId());
        exp.setAppId(app.getId());
        return experimentRepository.saveAndFlush(exp);
    }

    /** IT 线程内以指定应用的租户上下文执行（服务层/JPA 查询需过滤器上下文） */
    private <T> T withTenantContext(Application app, Supplier<T> action) {
        try {
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private String completions(Application app, String message) throws Exception {
        return mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 全链路_分桶走变体策略_报表聚合正确() throws Exception {
        Application app = seedApp("ab" + System.nanoTime(), "http://ab.example.com");
        ChatStrategy sA = seedStrategy(app, "变体A策略", true, null);
        ChatStrategy sB = seedStrategy(app, "变体B策略", false, "变体B专属兜底");
        AbExperiment exp = seedRunningExperiment(app, sA.getId(), sB.getId());

        // 开放对话：无知识库命中 + B 策略不允许自由发挥 → 回复即 B 的兜底语（证明变体策略生效）
        String body = completions(app, "问一个问题");
        assertThat((String) JsonPath.read(body, "$.reply")).isEqualTo("变体B专属兜底");

        // 会话三字段落库
        String sessionId = JsonPath.read(body, "$.sessionId");
        ChatSession session = withTenantContext(app, () ->
                sessionRepository.findBySessionId(sessionId).orElseThrow());
        assertThat(session.getExperimentId()).isEqualTo(exp.getId());
        assertThat(session.getVariantName()).isEqualTo("B");
        assertThat(session.getVariantStrategyId()).isEqualTo(sB.getId());

        // 反馈一个赞（兜底消息也有 messageId）
        int messageId = JsonPath.read(body, "$.messageId");
        mockMvc.perform(post("/open/v1/chat/feedback")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"messageId\":" + messageId
                                + ",\"feedback\":1}"))
                .andExpect(status().isOk());

        // 报表：B 有数据，A 全 0
        var report = withTenantContext(app, () -> abTestService.getReport(exp.getId()));
        var variantB = report.variants().stream()
                .filter(v -> v.variantName().equals("B")).findFirst().orElseThrow();
        assertThat(variantB.sessionCount()).isEqualTo(1);
        assertThat(variantB.questionCount()).isEqualTo(1);
        assertThat(variantB.aiMsgCount()).isEqualTo(1);
        assertThat(variantB.fallbackRate()).isEqualTo(1.0);
        assertThat(variantB.feedbackUp()).isEqualTo(1);
        assertThat(variantB.likeRate()).isEqualTo(1.0);
        var variantA = report.variants().stream()
                .filter(v -> v.variantName().equals("A")).findFirst().orElseThrow();
        assertThat(variantA.sessionCount()).isZero();
    }

    @Test
    void 结束实验后_新会话回归默认策略() throws Exception {
        Application app = seedApp("abend" + System.nanoTime(), "http://abe.example.com");
        ChatStrategy sA = seedStrategy(app, "A策略", true, null);
        ChatStrategy sB = seedStrategy(app, "B策略", false, "结束前兜底");
        AbExperiment exp = seedRunningExperiment(app, sA.getId(), sB.getId());

        withTenantContext(app, () -> abTestService.end(exp.getId()));

        // 默认策略（平台种子）allowNoKnowledge=true → 走 AI，需 mock
        Mockito.when(modelRouterService.callWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(SpringAiResponses.of("默认AI回复", 10));

        String body = completions(app, "结束后的提问");
        assertThat((String) JsonPath.read(body, "$.reply")).isEqualTo("默认AI回复");

        String sessionId = JsonPath.read(body, "$.sessionId");
        ChatSession session = withTenantContext(app, () ->
                sessionRepository.findBySessionId(sessionId).orElseThrow());
        assertThat(session.getExperimentId()).isNull();
        assertThat(session.getVariantName()).isNull();
    }

    @Test
    void 多租户隔离_他租户实验不分流() throws Exception {
        Application appA = seedApp("abiso1" + System.nanoTime(), "http://i1.example.com");
        Application appB = seedApp("abiso2" + System.nanoTime(), "http://i2.example.com");
        ChatStrategy sA = seedStrategy(appA, "A策略", true, null);
        ChatStrategy sB = seedStrategy(appA, "B策略", false, "A租户兜底");
        seedRunningExperiment(appA, sA.getId(), sB.getId());

        Mockito.when(modelRouterService.callWithFallback(
                        ArgumentMatchers.eq("default"), ArgumentMatchers.any()))
                .thenReturn(SpringAiResponses.of("B租户默认回复", 10));

        String body = completions(appB, "B租户提问");
        assertThat((String) JsonPath.read(body, "$.reply")).isEqualTo("B租户默认回复");

        String sessionId = JsonPath.read(body, "$.sessionId");
        ChatSession session = withTenantContext(appB, () ->
                sessionRepository.findBySessionId(sessionId).orElseThrow());
        assertThat(session.getExperimentId()).isNull();
    }
}
```

注意事项：
- `SpringAiResponses` 为 `com.qingyu.care.it` 包既有工具（StreamPersistenceIT 使用），若签名不符以实际为准调整。
- `OpenChatResponse` 字段名（`reply`/`sessionId`/`messageId`）以 `dto/open/OpenChatResponse.java` 实际字段为准，编写前先读一眼。
- 若 `JsonPath` 不在测试依赖中，改用 `jsonPath()` MockMvc 匹配器 + `andReturn()` 手工截取（`spring-boot-starter-test` 自带 com.jayway.jsonpath）。

- [ ] **Step 2: 运行 IT（需 Docker Desktop）**

Run: `cd backend && mvn verify -Dit.test=AbTestFlowIT`
Expected: Tests run: 3, Failures: 0, BUILD SUCCESS

- [ ] **Step 3: 全量 IT 回归**

Run: `cd backend && mvn verify`
Expected: BUILD SUCCESS（全部既有 IT 不回归）

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/qingyu/care/it/AbTestFlowIT.java
git commit -m "test: A/B 实验全链路集成回归网（分桶/变体策略/报表/结束回归/租户隔离）"
```

---

### Task 10: 前端 API 服务与实验管理页

**Files:**
- Create: `frontend/src/api/abtest.ts`
- Create: `frontend/src/pages/AbTestPage.tsx`
- Modify: `frontend/src/App.tsx`（路由）
- Modify: `frontend/src/layouts/MainLayout.tsx`（菜单项）

**Interfaces:**
- Consumes: Task 8 端点；`api/client.ts` 单例；`chatStrategyService.getAll()`（策略下拉）。
- Produces: `abTestService`（list/create/update/start/end/delete/report）与类型 `AbExperiment`/`AbVariant`/`AbTestReport`/`VariantReport`（Task 11 报表视图使用）。

- [ ] **Step 1: 创建 api/abtest.ts**

```typescript
import api from './client';
import type { ApiResponse } from '../types';

export type ExperimentStatus = 'DRAFT' | 'RUNNING' | 'ENDED';

export interface AbVariant {
    name: string;
    strategyId: number;
    weight: number;
}

export interface AbExperiment {
    id: number;
    name: string;
    description: string | null;
    status: ExperimentStatus;
    variants: AbVariant[];
    startedAt: string | null;
    endedAt: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface VariantReport {
    variantName: string;
    weight: number;
    sessionCount: number;
    questionCount: number;
    aiMsgCount: number;
    avgConfidence: number | null;
    feedbackUp: number;
    feedbackDown: number;
    likeRate: number | null;
    fallbackRate: number | null;
    lowConfidenceRate: number | null;
    hitRate: number | null;
    handoverRate: number | null;
    resolvedRate: number | null;
}

export interface AbTestReport {
    experimentId: number;
    name: string;
    status: ExperimentStatus;
    startedAt: string | null;
    endedAt: string | null;
    variants: VariantReport[];
}

export const abTestService = {
    list: (): Promise<ApiResponse<AbExperiment[]>> => api.get('/ab-experiments'),
    create: (data: Partial<AbExperiment>): Promise<ApiResponse<AbExperiment>> =>
        api.post('/ab-experiments', data),
    update: (id: number, data: Partial<AbExperiment>): Promise<ApiResponse<AbExperiment>> =>
        api.put(`/ab-experiments/${id}`, data),
    start: (id: number): Promise<ApiResponse<AbExperiment>> =>
        api.post(`/ab-experiments/${id}/start`),
    end: (id: number): Promise<ApiResponse<AbExperiment>> =>
        api.post(`/ab-experiments/${id}/end`),
    delete: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/ab-experiments/${id}`),
    report: (id: number): Promise<ApiResponse<AbTestReport>> =>
        api.get(`/ab-experiments/${id}/report`),
};
```

- [ ] **Step 2: 创建 AbTestPage.tsx（列表 + 创建/编辑对话框 + 生命周期操作）**

实现要求（UI 组件导入与对话框/表格/Toast 写法**照抄 `StrategyConfigPage.tsx` 的既有惯例**，以下为功能结构）：

- 页面状态：`experiments: AbExperiment[]`、`strategies: ChatStrategy[]`（`chatStrategyService.getAll()` 供下拉）、`dialogOpen`、`editing: AbExperiment | null`、表单态 `{ name, description, variants: AbVariant[] }`。
- 挂载时并行加载实验列表与策略列表。
- **列表表格**列：实验名 / 状态徽章（DRAFT 灰、RUNNING 绿、ENDED 中性——用既有 Badge 组件变体）/ 变体概要（如 `A 50% · B 50%`）/ 开始时间 / 结束时间 / 操作按钮。
- **操作按钮按状态显隐**：DRAFT → 编辑、启动、删除；RUNNING → 结束、查看报表；ENDED → 查看报表、删除。所有操作成功后 toast + 重新加载列表；失败 toast 错误信息（`error.message`）。
- **创建/编辑对话框**：实验名（必填）、描述、变体行列表（初始两行 A/B，各 50）。每行：变体名输入 + 策略下拉（选项 = `strategies.filter(s => s.enabled)`，显示 `name`）+ 权重数字输入（0~100）+ 删行按钮（行数 > 2 时可用）；「添加变体」按钮追加行。提交前前端校验：名称非空、变体 ≥2、变体名不重复、每行已选策略、权重和 = 100（不满足则 toast 并阻止提交）。
- 报表按钮先占位（`onClick` 留空函数 + `// Task 11 接线`），Task 11 实现。

- [ ] **Step 3: 注册路由与菜单**

`App.tsx`：import `AbTestPage`，在 `integration` 路由行后添加：

```tsx
          <Route path="ab-tests" element={<RequireApp><AbTestPage /></RequireApp>} />
```

`MainLayout.tsx`：import `FlaskConical`（lucide-react），在「问答策略」菜单项所在分组内、紧随其后添加（roles 与「问答策略」项保持一致；若该项无 roles 字段则同样不加）：

```tsx
            { label: 'A/B 实验', path: '/ab-tests', icon: FlaskConical },
```

- [ ] **Step 4: 构建与 lint 验证**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建成功，无 lint 错误

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/abtest.ts frontend/src/pages/AbTestPage.tsx frontend/src/App.tsx frontend/src/layouts/MainLayout.tsx
git commit -m "feat: A/B 实验管理页（列表/创建编辑/启停删除 + 路由菜单）"
```

---

### Task 11: 前端报表视图

**Files:**
- Create: `frontend/src/pages/abtest/ReportDialog.tsx`
- Modify: `frontend/src/pages/AbTestPage.tsx`（接线报表按钮）

**Interfaces:**
- Consumes: `abTestService.report(id)`、类型 `AbTestReport`/`VariantReport`（Task 10）。
- Produces: `<ReportDialog experimentId={number|null} onClose={() => void} />`。

- [ ] **Step 1: 实现 ReportDialog**

功能要求（Dialog/Table 组件写法照抄项目 `components/ui` 既有用法）：

- `experimentId` 非 null 时打开并请求 `abTestService.report(id)`；加载中显示既有 loading 惯例；失败 toast。
- **对比表格：行 = 指标，列 = 变体**。表头：`指标 | A (50%) | B (50%) ...`（变体名 + 权重）。
- 行定义（顺序固定）：

```typescript
const METRIC_ROWS: { label: string; render: (v: VariantReport) => string }[] = [
    { label: '会话数', render: v => String(v.sessionCount) },
    { label: '提问数', render: v => String(v.questionCount) },
    { label: 'AI 回复数', render: v => String(v.aiMsgCount) },
    { label: '平均置信度', render: v => fmtNum(v.avgConfidence) },
    { label: '点赞率', render: v => fmtPct(v.likeRate) },
    { label: '赞 / 踩', render: v => `${v.feedbackUp} / ${v.feedbackDown}` },
    { label: '兜底率', render: v => fmtPct(v.fallbackRate) },
    { label: '低置信率', render: v => fmtPct(v.lowConfidenceRate) },
    { label: '知识命中率', render: v => fmtPct(v.hitRate) },
    { label: '转人工率', render: v => fmtPct(v.handoverRate) },
    { label: 'AI 解决率', render: v => fmtPct(v.resolvedRate) },
];

const fmtPct = (v: number | null) => (v == null ? '—' : `${(v * 100).toFixed(1)}%`);
const fmtNum = (v: number | null) => (v == null ? '—' : v.toFixed(2));
```

- 对话框标题带实验名与状态；正文顶部一行小字显示起止时间。

- [ ] **Step 2: AbTestPage 接线**

`AbTestPage` 增加 `reportId: number | null` 状态；报表按钮 `onClick={() => setReportId(exp.id)}`；页面末尾渲染 `<ReportDialog experimentId={reportId} onClose={() => setReportId(null)} />`。

- [ ] **Step 3: 构建与 lint 验证**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建成功，无 lint 错误

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/abtest/ReportDialog.tsx frontend/src/pages/AbTestPage.tsx
git commit -m "feat: A/B 实验变体对比报表视图（指标行 × 变体列）"
```

---

### Task 12: 收尾（文档对齐 + 全量回归）

**Files:**
- Modify: `SaaS平台规划.md`（§4.4 勾选 A/B 测试 + 实施优先级 + 当前状态）
- Modify: `MVP → 企业版 功能里程碑.md`（企业版 A/B 测试 ⬜ → ✅）
- Modify: `README.md`（「规划中」A/B 移入「已实现」、路线图 Phase 4 更新）
- Modify: `docs/superpowers/specs/2026-07-22-phase4-ab-testing-design.md`（状态改「已实现」，如有实施偏差补记）

- [ ] **Step 1: 全量回归验证**

Run: `cd backend && mvn clean install`（单测）
Expected: BUILD SUCCESS
Run: `cd backend && mvn verify`（IT，需 Docker Desktop）
Expected: BUILD SUCCESS
Run: `cd frontend && pnpm build && pnpm lint`
Expected: 成功

- [ ] **Step 2: 更新四份文档**

- `SaaS平台规划.md`：§4.4 `- [ ] A/B 测试` → `- [x] A/B 测试（策略即变体、会话级分桶、仅开放流量、实时对比报表）`；「实施优先级」第 4 条剩余项去掉 A/B；「当前状态」Phase 4 剩余只留 4.2 多渠道。
- `MVP → 企业版 功能里程碑.md`：`- ⬜ **A/B 测试**` → `- ✅ **A/B 测试**：策略变体实验（会话级权重分桶 + 变体质量指标对比报表）`；企业版状态注释更新为「仅剩多渠道接入」。
- `README.md`：「规划中」删 A/B 行，「已实现」追加 `- ✅ **A/B 测试** - 问答策略变体实验（开放流量会话级权重分桶、变体质量指标实时对比）`；路线图 Phase 4 进度条与说明更新。
- spec 头部 `状态：设计已确认，待实施` → `状态：已实现（2026-07-XX）`，实施偏差如有则在文末补「实施偏差记录」小节。

- [ ] **Step 3: Commit**

```bash
git add "SaaS平台规划.md" "MVP → 企业版 功能里程碑.md" README.md docs/superpowers/specs/2026-07-22-phase4-ab-testing-design.md
git commit -m "docs: A/B 测试完成收尾（规划勾选 + spec 置为已实现）"
```

---

## 完成定义

- `mvn clean install` 与 `mvn verify` 全绿；`pnpm build`/`pnpm lint` 通过。
- 分支 `feature/phase4-ab-testing` 上按任务粒度提交，等待用户验收后决定合并方式（不自行 push/merge 到 master）。

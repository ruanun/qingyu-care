# 消息已读回执与坐席多会话 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 双向已读回执（访客看到坐席已读 / 坐席看到访客已读），并让坐席工作台能同时持有多个已接管会话、每个会话显示持久化的未读数。

**Architecture:** 新增 `message_read_receipt` 表存「读者 → 水位线」，一个会话下 `(AGENT, 坐席id)` 与 `(VISITOR, 会话id)` 各一行、互不干扰。水位线只增不减，靠一条带条件的原生 UPDATE 收敛并发。两端各自在「界面真的可见」时上报，服务端先落库再经 SSE `read` 事件推给对方。坐席未读数由「会话 LEFT JOIN 回执」聚合实时算出，不冗余计数器。

**Tech Stack:** JDK 17 / Spring Boot 4.1 / JPA + 原生 SQL / JUnit 5 + Mockito + AssertJ / Testcontainers(pgvector:pg17) / React 19 + vitest / widget 原生 TS + vitest

**Spec:** `docs/superpowers/specs/2026-08-19-message-read-receipt-design.md`

## Global Constraints

- JDK 17。后端命令在 `backend/` 下跑；`mvn test` 免 Docker，`mvn verify` 需 Docker Desktop。
- ⚠️ **跑 `mvn verify` 前必须先停掉本机在跑的 dev 后端**（`:8080`），否则 `WebhookDeliveryIT` 必红（与 dev 后端共用本机 Redis 消费组）。
- 注释、测试方法名、commit 信息一律**简体中文**；`@author ruan`。
- 新增测试优先写成 Surefire 单测（纯 Mockito，不启 Spring 容器）；只有必须验证数据库约束/原生 SQL 的才写 IT（类名 `*IT`，放 `it/` 包，继承 `support/AbstractPostgresIT`）。
- 前端 `frontend/` 测试**不开 `globals: true`**，必须显式 `import { describe, it, expect, vi } from 'vitest'`；推进时间一律 `await vi.advanceTimersByTimeAsync(ms)` 并包在 `act` 里。
- ⚠️ **widget 改动后必须 `pnpm build` 并提交产物 `backend/src/main/resources/static/widget.js`**，否则 `scripts/buildFreshness.test.ts` 转红。
- 基线：后端单测 **713** 全绿；前端 lint 零 error；widget 109 条全绿。每个任务结束报实际值。
- 水位线语义全程一致：**只增不减**，`upToMessageId` 含义是「读到这条为止（含）」。

---

### Task 1: 回执数据模型与原子 upsert

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/enums/ReaderType.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/MessageReadReceipt.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/MessageReadReceiptRepository.java`
- Create: `backend/src/main/resources/migration/2026-08-20-add-message-read-receipt.sql`
- Test: `backend/src/test/java/com/qingyu/care/it/MessageReadReceiptIT.java`

**Interfaces:**
- Consumes: 无（本任务是地基）
- Produces:
  - `enum ReaderType { AGENT, VISITOR }`
  - `MessageReadReceipt`（Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`），字段 `id/sessionId/readerType/readerId/upToMessageId/readAt`，继承 `BaseTenantEntity`
  - `int MessageReadReceiptRepository.advanceWatermark(Long sessionId, String readerType, Long readerId, Long upTo, LocalDateTime now)` — 返回受影响行数，0 表示尚无该行需 insert
  - `Optional<MessageReadReceipt> findBySessionIdAndReaderTypeAndReaderId(Long, ReaderType, Long)`

- [ ] **Step 1: 建枚举与实体**

`ReaderType.java`：

```java
package com.qingyu.care.enums;

/**
 * 已读回执的读者类型。两个值都有真实写入路径（坐席回执 / 访客回执）。
 *
 * <p>⚠️ 将来若砍掉其中一侧，<b>枚举值要跟着删</b>——留一个永不写入的值就是技术债第 4 项
 * {@code ChatMessage.isRead} 死字段的形态（「留一个永远为 false 的列会持续误导读代码的人」）。
 *
 * @author ruan
 */
public enum ReaderType {
    /** 人工坐席，{@code readerId} 是坐席的 user id。 */
    AGENT,
    /** 访客，{@code readerId} 是该会话的 {@code chat_session.id}（一个会话只有一个访客）。 */
    VISITOR
}
```

`MessageReadReceipt.java`：

```java
package com.qingyu.care.entity;

import com.qingyu.care.enums.ReaderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 「某个读者在某个会话里读到哪条消息」的水位线。一个会话下坐席与访客各一行，互不干扰。
 *
 * <p>⚠️ {@code readerId} <b>绝不能为 null</b>：PostgreSQL 唯一约束里 {@code NULL != NULL}，
 * {@code (session_id, 'VISITOR', NULL)} 可插入任意多行，唯一约束形同虚设，水位线变成多行并存、
 * 读出来哪一行全看运气——且不报错，只表现为已读状态间歇性错乱。访客用 {@code chat_session.id}
 * 作 {@code readerId}。
 *
 * @author ruan
 */
@Entity
@Table(name = "message_read_receipt",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_receipt_session_reader",
           columnNames = {"session_id", "reader_type", "reader_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MessageReadReceipt extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reader_type", nullable = false, length = 20)
    private ReaderType readerType;

    /** 见类注释：绝不能为 null。 */
    @Column(name = "reader_id", nullable = false)
    private Long readerId;

    @Column(name = "up_to_message_id", nullable = false)
    private Long upToMessageId;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
```

- [ ] **Step 2: 建 Repository（原子 UPDATE）**

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @author ruan
 */
@Repository
public interface MessageReadReceiptRepository extends JpaRepository<MessageReadReceipt, Long> {

    /**
     * 水位线只增不减地推进，返回受影响行数（0 表示该行不存在需 insert，或传入值不比现值大）。
     *
     * <p><b>不能先读后写</b>：坐席开两个标签页并发上报、或 SSE 乱序到达时，先读后写会让水位线
     * 回退，对端界面上「已读」会闪一下消失。带 {@code up_to_message_id < :upTo} 条件的原子
     * UPDATE 让并发自然收敛到最大值。
     *
     * <p>{@code @Modifying} 更新必须在事务中执行；调用方 {@code ReadReceiptService.markRead}
     * 刻意不加方法级事务（避免唯一键冲突把整方法标记 rollback-only），故在此让本方法各走独立
     * 事务——同 {@code SysApiUsageRepository.incrementUsage} 的既有范式。
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE message_read_receipt SET up_to_message_id = :upTo, read_at = :now "
            + "WHERE session_id = :sessionId AND reader_type = :readerType AND reader_id = :readerId "
            + "AND up_to_message_id < :upTo", nativeQuery = true)
    int advanceWatermark(@Param("sessionId") Long sessionId,
                         @Param("readerType") String readerType,
                         @Param("readerId") Long readerId,
                         @Param("upTo") Long upTo,
                         @Param("now") LocalDateTime now);

    Optional<MessageReadReceipt> findBySessionIdAndReaderTypeAndReaderId(
            Long sessionId, ReaderType readerType, Long readerId);
}
```

⚠️ `readerType` 参数用 `String` 而非枚举：原生 SQL 下 Hibernate 不会把枚举转成 `EnumType.STRING`
的字面量，传枚举会绑成序号导致永远匹配不上（0 行受影响，静默失效）。调用方传 `.name()`。

- [ ] **Step 3: 写迁移脚本**

`backend/src/main/resources/migration/2026-08-20-add-message-read-receipt.sql`：

```sql
-- 消息已读回执水位线表。
-- ⚠️ ddl-auto: update 能自动建表，但唯一约束在存量库上不保证跟着建（Hibernate 对已存在表的
-- 约束变更处理不一致）。本脚本幂等，可重复执行。
-- 缺唯一约束的后果：同一 (会话, 读者) 会积累多行水位线，读出来哪行看运气，且不报错。

CREATE TABLE IF NOT EXISTS message_read_receipt (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT       NOT NULL,
    reader_type      VARCHAR(20)  NOT NULL,
    reader_id        BIGINT       NOT NULL,
    up_to_message_id BIGINT       NOT NULL,
    read_at          TIMESTAMP    NOT NULL,
    tenant_id        BIGINT,
    app_id           BIGINT
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_receipt_session_reader
    ON message_read_receipt (session_id, reader_type, reader_id);

-- 未读数聚合按 (session_id, reader_type) 查回执，建一条覆盖索引
CREATE INDEX IF NOT EXISTS idx_receipt_session_type
    ON message_read_receipt (session_id, reader_type);
```

- [ ] **Step 4: 写 IT（先写，验证约束真的生效）**

`backend/src/test/java/com/qingyu/care/it/MessageReadReceiptIT.java`：

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.it.support.AbstractPostgresIT;
import com.qingyu.care.repository.MessageReadReceiptRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守护回执表的两条数据库级约束——都是纯 Mockito 单测证明不了的。
 *
 * @author ruan
 */
class MessageReadReceiptIT extends AbstractPostgresIT {

    @Autowired MessageReadReceiptRepository repository;

    private MessageReadReceipt receipt(Long sessionId, ReaderType type, Long readerId, Long upTo) {
        return MessageReadReceipt.builder()
                .sessionId(sessionId).readerType(type).readerId(readerId)
                .upToMessageId(upTo).readAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("同一会话同一读者不能有两条水位线")
    void 唯一约束阻止重复水位线() {
        repository.saveAndFlush(receipt(1L, ReaderType.AGENT, 100L, 5L));

        assertThatThrownBy(() -> repository.saveAndFlush(receipt(1L, ReaderType.AGENT, 100L, 9L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("同一会话下坐席与访客各一条水位线，互不干扰")
    void 坐席与访客的水位线互不干扰() {
        repository.saveAndFlush(receipt(2L, ReaderType.AGENT, 100L, 5L));
        repository.saveAndFlush(receipt(2L, ReaderType.VISITOR, 2L, 8L));

        int agentUpdated = repository.advanceWatermark(
                2L, ReaderType.AGENT.name(), 100L, 7L, LocalDateTime.now());

        assertThat(agentUpdated).isEqualTo(1);
        assertThat(repository.findBySessionIdAndReaderTypeAndReaderId(2L, ReaderType.AGENT, 100L))
                .get().extracting(MessageReadReceipt::getUpToMessageId).isEqualTo(7L);
        assertThat(repository.findBySessionIdAndReaderTypeAndReaderId(2L, ReaderType.VISITOR, 2L))
                .as("推进坐席水位线不该动到访客那一行")
                .get().extracting(MessageReadReceipt::getUpToMessageId).isEqualTo(8L);
    }

    @Test
    @DisplayName("传入更小的 upToMessageId 不推进，返回 0 行")
    void 水位线不回退() {
        repository.saveAndFlush(receipt(3L, ReaderType.AGENT, 100L, 10L));

        int updated = repository.advanceWatermark(
                3L, ReaderType.AGENT.name(), 100L, 4L, LocalDateTime.now());

        assertThat(updated).as("比现值小，不该更新").isZero();
        assertThat(repository.findBySessionIdAndReaderTypeAndReaderId(3L, ReaderType.AGENT, 100L))
                .get().extracting(MessageReadReceipt::getUpToMessageId).isEqualTo(10L);
    }
}
```

- [ ] **Step 5: 跑 IT**

先确认本机 dev 后端已停（`:8080` 无监听），Docker Desktop 已启动。

Run: `cd backend && mvn verify -Dit.test=MessageReadReceiptIT`
Expected: 3 条全绿。

⚠️ 若 `唯一约束阻止重复水位线` 失败（没抛异常），说明 `@UniqueConstraint` 没落到库上，
检查 Step 3 的索引名与实体注解是否一致。

- [ ] **Step 6: 变异验证约束真的在起作用**

把实体上的 `uniqueConstraints = @UniqueConstraint(...)` 整段临时删掉，重跑上面的 IT。

Expected: `唯一约束阻止重复水位线` 转红（没抛 `DataIntegrityViolationException`）。

⚠️ 若仍然绿，说明测试库里那张表是上一次跑留下的、带着索引——需要先让 Testcontainers 重建
（改动实体后容器内表结构由 `ddl-auto` 重建；必要时 `mvn clean`）。**确认变异真的落地再下结论**，
否则「变异没生效」与「守护失效」在输出上无法区分。

改回来，重跑确认恢复绿。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/enums/ReaderType.java \
        backend/src/main/java/com/qingyu/care/entity/MessageReadReceipt.java \
        backend/src/main/java/com/qingyu/care/repository/MessageReadReceiptRepository.java \
        backend/src/main/resources/migration/2026-08-20-add-message-read-receipt.sql \
        backend/src/test/java/com/qingyu/care/it/MessageReadReceiptIT.java
git commit -m "feat(read-receipt): 回执表与原子水位线推进

一个会话下坐席与访客各一条水位线，靠 (session_id, reader_type, reader_id)
唯一约束区分。水位线只增不减，用带条件的原生 UPDATE 收敛并发——先读后写会在
坐席多标签页并发上报时让水位线回退，对端「已读」闪一下消失。

⚠️ readerType 参数传 String 而非枚举：原生 SQL 下 Hibernate 不按 EnumType.STRING
转换，传枚举会绑成序号，永远匹配不上（0 行受影响，静默失效）。

⚠️ readerId 对访客用 chat_session.id 而非 null：PG 唯一约束里 NULL != NULL，
留 null 会让约束形同虚设、水位线多行并存且不报错。IT 里有一条专门守这个。

附幂等迁移脚本：ddl-auto 能建表但唯一约束在存量库上不保证跟着建。"
```

---

### Task 2: 回执服务 `ReadReceiptService`

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/ReadReceiptService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ReadReceiptServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `MessageReadReceiptRepository.advanceWatermark(...)`、`ReaderType`、`MessageReadReceipt`
- Produces:
  - `long ReadReceiptService.markRead(Long sessionId, ReaderType readerType, Long readerId, Long upToMessageId)` — 返回**生效后的**水位线（可能大于传入值，因为并发下别人推得更远）；传入值不比现值大时返回现值
  - `Long ReadReceiptService.currentWatermark(Long sessionId, ReaderType readerType, Long readerId)` — 无回执时返回 `null`

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.repository.MessageReadReceiptRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadReceiptServiceTest {

    @Mock MessageReadReceiptRepository repository;
    @InjectMocks ReadReceiptService service;

    @Test
    @DisplayName("已有水位线且传入值更大时，走原子 UPDATE、不再 insert")
    void 推进已有水位线() {
        when(repository.advanceWatermark(eq(1L), eq("AGENT"), eq(100L), eq(9L), any()))
                .thenReturn(1);

        long result = service.markRead(1L, ReaderType.AGENT, 100L, 9L);

        assertThat(result).isEqualTo(9L);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("首次回执：UPDATE 影响 0 行时插入新行")
    void 首次回执插入新行() {
        when(repository.advanceWatermark(anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(0);
        when(repository.findBySessionIdAndReaderTypeAndReaderId(1L, ReaderType.AGENT, 100L))
                .thenReturn(Optional.empty());

        long result = service.markRead(1L, ReaderType.AGENT, 100L, 9L);

        assertThat(result).isEqualTo(9L);
        verify(repository).save(any(MessageReadReceipt.class));
    }

    @Test
    @DisplayName("传入值不比现值大时不回退，返回现值且不插入")
    void 水位线不回退() {
        when(repository.advanceWatermark(anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(0);
        when(repository.findBySessionIdAndReaderTypeAndReaderId(1L, ReaderType.AGENT, 100L))
                .thenReturn(Optional.of(MessageReadReceipt.builder()
                        .sessionId(1L).readerType(ReaderType.AGENT).readerId(100L)
                        .upToMessageId(20L).readAt(LocalDateTime.now()).build()));

        long result = service.markRead(1L, ReaderType.AGENT, 100L, 5L);

        assertThat(result).as("并发下别人已推到 20，本次传 5 不该让它退回去").isEqualTo(20L);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("readerType 必须以字符串字面量传给原生 SQL，不能传枚举序号")
    void 原生SQL收到的是枚举名() {
        when(repository.advanceWatermark(anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(1);

        service.markRead(1L, ReaderType.VISITOR, 7L, 3L);

        // 传成序号（"1"）会让 WHERE reader_type = '1' 永远匹配不上，静默 0 行
        verify(repository).advanceWatermark(eq(1L), eq("VISITOR"), eq(7L), eq(3L), any());
    }

    @Test
    @DisplayName("无回执时 currentWatermark 返回 null，不是 0")
    void 无回执时水位线为null() {
        when(repository.findBySessionIdAndReaderTypeAndReaderId(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.currentWatermark(1L, ReaderType.AGENT, 100L)).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=ReadReceiptServiceTest`
Expected: 编译失败，`ReadReceiptService` 不存在。

- [ ] **Step 3: 实现**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.repository.MessageReadReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 已读水位线的读写。水位线语义：{@code upToMessageId} 表示「读到这条为止（含）」，只增不减。
 *
 * <p>⚠️ <b>本类刻意不加方法级 {@code @Transactional}</b>：首次回执走「UPDATE 0 行 → insert」，
 * 而并发首次回执会撞唯一键；若整个方法在一个事务里，那次 {@code DataIntegrityViolationException}
 * 会把事务标成 rollback-only，catch 住也救不回来。让 UPDATE 与 insert 各走独立事务，冲突时重试
 * 一次 UPDATE 即可真正提交——同 {@code ApiUsageService.record} 的既有范式。
 *
 * @author ruan
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private final MessageReadReceiptRepository repository;

    /**
     * 推进水位线，返回<b>生效后</b>的值。
     *
     * <p>返回值可能大于 {@code upToMessageId}——并发下别的标签页已经推得更远，此时不回退。
     */
    public long markRead(Long sessionId, ReaderType readerType, Long readerId, Long upToMessageId) {
        int updated = repository.advanceWatermark(
                sessionId, readerType.name(), readerId, upToMessageId, LocalDateTime.now());
        if (updated > 0) {
            return upToMessageId;
        }

        // 0 行有两种情况：该行还不存在（需 insert），或传入值不比现值大（不回退）
        Optional<MessageReadReceipt> existing =
                repository.findBySessionIdAndReaderTypeAndReaderId(sessionId, readerType, readerId);
        if (existing.isPresent()) {
            return existing.get().getUpToMessageId();
        }

        try {
            repository.save(MessageReadReceipt.builder()
                    .sessionId(sessionId)
                    .readerType(readerType)
                    .readerId(readerId)
                    .upToMessageId(upToMessageId)
                    .readAt(LocalDateTime.now())
                    .build());
            return upToMessageId;
        } catch (DataIntegrityViolationException conflict) {
            // 并发首次回执：另一方刚插进去。重试 UPDATE——未加方法级事务，此重试能真正提交。
            repository.advanceWatermark(
                    sessionId, readerType.name(), readerId, upToMessageId, LocalDateTime.now());
            return currentOrDefault(sessionId, readerType, readerId, upToMessageId);
        }
    }

    /** 当前水位线；从无回执时返回 {@code null}（区别于 0——0 是合法的「什么都没读」）。 */
    public Long currentWatermark(Long sessionId, ReaderType readerType, Long readerId) {
        return repository.findBySessionIdAndReaderTypeAndReaderId(sessionId, readerType, readerId)
                .map(MessageReadReceipt::getUpToMessageId)
                .orElse(null);
    }

    private long currentOrDefault(Long sessionId, ReaderType readerType, Long readerId, Long fallback) {
        Long current = currentWatermark(sessionId, readerType, readerId);
        return current != null ? current : fallback;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=ReadReceiptServiceTest`
Expected: 5 条全绿。

- [ ] **Step 5: 变异验证**

把 `markRead` 里 `repository.advanceWatermark(..., readerType.name(), ...)` 改成传
`String.valueOf(readerType.ordinal())`，重跑。

Expected: `原生SQL收到的是枚举名` 转红。改回来确认恢复绿。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ReadReceiptService.java \
        backend/src/test/java/com/qingyu/care/service/ReadReceiptServiceTest.java
git commit -m "feat(read-receipt): 回执服务，水位线只增不减

markRead 返回生效后的水位线而非传入值——并发下别的标签页可能已推得更远，
此时不回退，调用方要拿真实生效值去推送给对端。

⚠️ 刻意不加方法级 @Transactional：首次回执走「UPDATE 0 行 → insert」，并发首次
会撞唯一键，若整方法在一个事务里，那次 DataIntegrityViolationException 会把事务
标成 rollback-only，catch 住也救不回来。UPDATE 与 insert 各走独立事务，冲突时
重试一次 UPDATE 即可真正提交——同 ApiUsageService.record 的既有范式。"
```

---

### Task 3: 坐席回执接口 + 接管瞬间置顶水位线

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java`（在 `acceptSession` 附近新增端点）
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatService.java`（接口加方法）
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（`acceptSession` 内置顶 + 新增 `markAgentRead`）
- Create: `backend/src/main/java/com/qingyu/care/dto/ReadReceiptRequest.java`
- Test: `backend/src/test/java/com/qingyu/care/service/AgentReadReceiptTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ReadReceiptService.markRead(...)`；`ReaderType.AGENT`
- Produces:
  - `class ReadReceiptRequest { private Long upToMessageId; }`（**不写字段初始化器**）
  - `long ChatService.markAgentRead(String sessionId, Long agentId, Long upToMessageId)` — 返回生效后的水位线
  - `POST /v1/chat/session/{sessionId}/read` → `ApiResponse<Long>`

⚠️ **当前坐席 id 由控制器取好传进来，service 层不自己取**。这是仓库既有范式：
`ChatController.acceptSession`（`ChatController.java:150-153`）就是 `SecurityUtil.requireAgent()` +
`StpUtil.getLoginIdAsLong()` 取到 `agentId`，再调用两参的
`chatService.acceptSession(sessionId, agentId)`。
`SecurityUtil` 只有角色判断方法（`getRole`/`isPlatformAdmin`/`isTenantAdmin`/`isTenantMember`/
三个 `requireXxx`），**没有取 user id 的方法**，不要去调一个不存在的 `getCurrentUserId()`。
好处之一：service 层不碰 `StpUtil`，本任务的单测因此**不需要任何静态桩**。

- [ ] **Step 1: 建请求 DTO**

```java
package com.qingyu.care.dto;

import lombok.Data;

/**
 * 已读回执上报请求。坐席端与访客端共用。
 *
 * <p>⚠️ <b>字段不写初始化器</b>：本仓库既有约定（{@code UpdateRequestNoInitializerTest} 守着
 * {@code *UpdateRequest}，本类虽不在其扫描范围内，约定同样适用）——Jackson 走无参构造会应用
 * 初始化器，让「请求体没给这个字段」与「给了默认值」无法区分。
 *
 * @author ruan
 */
@Data
public class ReadReceiptRequest {
    private Long upToMessageId;
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.impl.ChatServiceImpl;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坐席回执的归属校验与接管置顶。
 *
 * <p>⚠️ 归属校验是安全边界：缺了它，任何坐席都能靠遍历 sessionId 给<b>别人接管的会话</b>
 * 写水位线，进而伪造一条发给那个访客的「已读」。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentReadReceiptTest {

    // 装配照 ChatSessionListScopeTest（同一个被测类）。ChatServiceImpl 有 19 个依赖，
    // 这里只声明测试路径用得到的；Mockito 会把其余注入为 null，不碰就没事。
    // ⚠️ ObjectMapper 来自 tools.jackson.databind（Jackson 3），不是 com.fasterxml。
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
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @Mock ReadReceiptService readReceiptService;
    @InjectMocks ChatServiceImpl chatService;

    /** 当前坐席的 user id。由控制器取好传进来，service 层不碰 StpUtil，故无需任何静态桩。 */
    private static final Long ME = 100L;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatSession session(Long agentId, SessionStatus status) {
        ChatSession s = ChatSession.builder().id(7L).sessionId("sess-1").status(status).build();
        s.setAgentId(agentId);
        s.setTenantId(1L);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    @DisplayName("非本人接管的会话拒绝回执，且一次都不写水位线")
    void 拒绝给别人的会话写水位线() {
        session(999L, SessionStatus.AGENT_PROCESSING);   // 别人接管的

        assertThatThrownBy(() -> chatService.markAgentRead("sess-1", ME, 42L))
                .isInstanceOf(InvalidOperationException.class);
        verify(readReceiptService, never()).markRead(any(), any(), any(), any());
    }

    @Test
    @DisplayName("本人接管的会话正常写入，readerType=AGENT、readerId=坐席 user id")
    void 本人会话正常回执() {
        ChatSession s = session(ME, SessionStatus.AGENT_PROCESSING);
        when(readReceiptService.markRead(s.getId(), ReaderType.AGENT, ME, 42L)).thenReturn(42L);

        chatService.markAgentRead("sess-1", ME, 42L);

        verify(readReceiptService).markRead(s.getId(), ReaderType.AGENT, ME, 42L);
        verify(realtimeNotifier).readReceiptToUser("sess-1", 42L);
    }

    @Test
    @DisplayName("会话已结束时拒绝回执")
    void 会话结束后拒绝回执() {
        session(ME, SessionStatus.ENDED);

        assertThatThrownBy(() -> chatService.markAgentRead("sess-1", ME, 42L))
                .isInstanceOf(InvalidOperationException.class);
        verify(readReceiptService, never()).markRead(any(), any(), any(), any());
    }

    @Test
    @DisplayName("推送的是生效后的水位线，不是传入值")
    void 推送生效值而非传入值() {
        ChatSession s = session(ME, SessionStatus.AGENT_PROCESSING);
        // 并发下另一个标签页已推到 88，本次传 42 不回退
        when(readReceiptService.markRead(s.getId(), ReaderType.AGENT, ME, 42L)).thenReturn(88L);

        chatService.markAgentRead("sess-1", ME, 42L);

        verify(realtimeNotifier).readReceiptToUser("sess-1", 88L);
    }

    @Test
    @DisplayName("acceptSession 把水位线置为当时最新消息 id")
    void 接管瞬间置顶水位线() {
        ChatSession s = session(null, SessionStatus.WAITING_AGENT);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepository.findTopBySessionIdOrderByIdDesc(s.getId()))
                .thenReturn(Optional.of(ChatMessage.builder().id(77L).build()));

        chatService.acceptSession("sess-1", ME);   // 既有签名就是两参

        verify(readReceiptService).markRead(s.getId(), ReaderType.AGENT, ME, 77L);
    }
}
```

⚠️ **实施时先跑一次确认装配可用**：`ChatServiceImpl` 有 19 个 `private final` 依赖，上面只声明了
测试路径用得到的。若 `@InjectMocks` 注入失败或 `acceptSession` 走到未 mock 的依赖上抛 NPE，
按报错补 `@Mock`——**不要改测试意图**。

- [ ] **Step 3: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=AgentReadReceiptTest`
Expected: 编译失败或断言失败（`markAgentRead` 不存在）。

- [ ] **Step 4: 实现服务层**

在 `ChatService` 接口加：

```java
    /**
     * 坐席上报已读水位线。
     *
     * @param agentId       当前坐席的 user id，<b>由控制器取好传入</b>（同 {@code acceptSession}
     *                      的既有范式：`StpUtil.getLoginIdAsLong()` 在控制器层取）。service 层
     *                      不碰 Sa-Token，单测因此不需要静态桩。
     * @param upToMessageId 坐席端当前<b>已渲染</b>的最新一条消息 id。服务端不替客户端推断
     *                      「最新是哪条」——坐席实际看到的截止位置只有客户端知道，服务端若取库里
     *                      最新的一条，会把「消息刚到、还没渲染出来」也算成已读。
     * @return 生效后的水位线
     */
    long markAgentRead(String sessionId, Long agentId, Long upToMessageId);
```

在 `ChatServiceImpl` 实现：

```java
    @Override
    public long markAgentRead(String sessionId, Long agentId, Long upToMessageId) {
        ChatSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在: " + sessionId));

        // 安全边界：不校验就能靠遍历 sessionId 给别人接管的会话写水位线，
        // 进而伪造一条发给那个访客的「已读」
        if (session.getStatus() != SessionStatus.AGENT_PROCESSING
                || !Objects.equals(session.getAgentId(), agentId)) {
            throw new InvalidOperationException("该会话不在您的接待中，无法上报已读");
        }

        long effective = readReceiptService.markRead(
                session.getId(), ReaderType.AGENT, agentId, upToMessageId);
        realtimeNotifier.readReceiptToUser(sessionId, effective);   // Task 5 补上此方法
        return effective;
    }
```

⚠️ **先落库再推送**：`markRead` 返回后才推。顺序颠倒的话，访客收到已读后若立刻重连拉历史，
会读到旧水位线，界面上「已读」闪一下又消失——同 CLAUDE.md 记载的「attach 必须早于 notify」。

⚠️ Task 5 尚未实现 `readReceiptToUser`，本任务先**注释掉这一行**并留 `// TODO(Task 5)` 标记，
Task 5 里放开。（这是任务间唯一允许的临时占位，因为两个任务都要动这个方法。）

在 `acceptSession(String sessionId, Long agentId)` 内、`sessionRepository.save(session)` 之后加
（`agentId` 直接用它已有的方法参数）：

```java
        // 接管瞬间视为读完全部历史：坐席界面此刻就会加载整个会话，
        // 访客侧同时看到排队期间发的消息集体变「已读」，时序正确
        messageRepository.findTopBySessionIdOrderByIdDesc(session.getId())
                .ifPresent(latest -> readReceiptService.markRead(
                        session.getId(), ReaderType.AGENT, agentId, latest.getId()));
```

⚠️ `ChatMessageRepository` **确认没有** `findTopBySessionIdOrderByIdDesc`（只有
`findTop20BySessionIdOrderByCreatedAtDesc`），本任务顺带加上——Spring Data 方法名派生，
返回 `Optional<ChatMessage>`，无需写 SQL。

- [ ] **Step 5: 加控制器端点**

在 `ChatController` 的 `acceptSession` 附近：

```java
    @PostMapping("/session/{sessionId}/read")
    @Operation(summary = "坐席上报已读", description = "推进本坐席在该会话的已读水位线，并推送给访客")
    public ApiResponse<Long> markAgentRead(@PathVariable String sessionId,
                                           @RequestBody ReadReceiptRequest request) {
        SecurityUtil.requireAgent();
        // 取当前坐席 id 的方式与同文件的 acceptSession 完全一致
        Long agentId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        return ApiResponse.success(
                chatService.markAgentRead(sessionId, agentId, request.getUpToMessageId()));
    }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=AgentReadReceiptTest`
Expected: 4 条全绿。

- [ ] **Step 7: 变异验证归属校验**

把 `markAgentRead` 里 `|| !Objects.equals(session.getAgentId(), agentId)` 整段删掉，重跑。

Expected: `拒绝给别人的会话写水位线` 转红。改回来。

- [ ] **Step 8: 全量回归 + 提交**

Run: `cd backend && mvn test`
Expected: 713 + 本任务新增条数，全绿。

```bash
git add -A backend/src/main/java/com/qingyu/care/controller/ChatController.java \
        backend/src/main/java/com/qingyu/care/service/ChatService.java \
        backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java \
        backend/src/main/java/com/qingyu/care/dto/ReadReceiptRequest.java \
        backend/src/test/java/com/qingyu/care/service/AgentReadReceiptTest.java
git commit -m "feat(read-receipt): 坐席回执接口与接管置顶

POST /v1/chat/session/{id}/read 推进本坐席水位线。

⚠️ 归属校验是安全边界：不校验 session.agentId == 当前坐席，任何坐席都能靠遍历
sessionId 给别人接管的会话写水位线，伪造一条发给那个访客的「已读」。变异验证过。

upToMessageId 由客户端传「已渲染的最新一条」，服务端不替它推断——取库里最新的
会把「消息刚到、还没渲染出来」也算成已读。

acceptSession 里把水位线置为当时最新消息 id：坐席接管时界面就加载了全部历史，
访客侧同时看到排队期间的消息集体变已读，时序正确。"
```

---

### Task 4: 访客回执接口（开放 API）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatService.java` + `impl/ChatServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/service/VisitorReadReceiptTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ReadReceiptService`；Task 3 的 `ReadReceiptRequest`
- Produces:
  - `long ChatService.markVisitorRead(String sessionId, Long upToMessageId)`
  - `POST /open/v1/sessions/{sessionId}/read` → `ApiResponse<Long>`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/qingyu/care/service/VisitorReadReceiptTest.java`。
**装配与 Task 3 的 `AgentReadReceiptTest` 完全一致**（同一个被测类 `ChatServiceImpl`，
同一批 `@Mock` 字段 + `@InjectMocks`，`ObjectMapper` 同样来自 `tools.jackson.databind`），
差别是访客路径不需要 `StpUtil` 桩（开放 API 不走登录态）。

```java
    private static final Long SESSION_PK = 7L;

    private ChatSession session(Long appId, Long agentId) {
        ChatSession s = ChatSession.builder()
                .id(SESSION_PK).sessionId("sess-1").status(SessionStatus.AGENT_PROCESSING).build();
        s.setAgentId(agentId);
        s.setTenantId(1L);
        s.setAppId(appId);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    @DisplayName("访客回执写入 (VISITOR, chat_session.id) 这一行")
    void 访客回执用会话id作readerId() {
        ChatSession s = session(1L, 100L);
        TenantContext.setAppId(1L);
        when(readReceiptService.markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 42L))
                .thenReturn(42L);

        chatService.markVisitorRead("sess-1", 42L);

        // ⚠️ readerId 必须是 chat_session.id，绝不能是 null——PG 唯一约束里 NULL != NULL，
        //    留 null 会让 (session_id,'VISITOR',NULL) 插入任意多行，约束形同虚设且不报错
        verify(readReceiptService).markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 42L);
    }

    @Test
    @DisplayName("有坐席接管时把访客已读推给该坐席，载荷带 sessionId")
    void 推给坐席() {
        session(1L, 100L);
        TenantContext.setAppId(1L);
        when(readReceiptService.markRead(anyLong(), any(), anyLong(), anyLong())).thenReturn(42L);

        chatService.markVisitorRead("sess-1", 42L);

        verify(realtimeNotifier).readReceiptToAgent(1L, 100L, "sess-1", 42L);
    }

    @Test
    @DisplayName("无坐席接管时不推送，但水位线照常落库")
    void 无坐席时只落库不推送() {
        session(1L, null);           // agentId 为 null
        TenantContext.setAppId(1L);
        when(readReceiptService.markRead(anyLong(), any(), anyLong(), anyLong())).thenReturn(42L);

        chatService.markVisitorRead("sess-1", 42L);

        verify(readReceiptService).markRead(SESSION_PK, ReaderType.VISITOR, SESSION_PK, 42L);
        verify(realtimeNotifier, never()).readReceiptToAgent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("会话不属于当前 app 时拒绝")
    void 拒绝跨应用回执() {
        session(2L, 100L);           // 会话属于 app 2
        TenantContext.setAppId(1L);  // 调用方是 app 1

        assertThatThrownBy(() -> chatService.markVisitorRead("sess-1", 42L))
                .isInstanceOf(InvalidOperationException.class);
        verify(readReceiptService, never()).markRead(any(), any(), any(), any());
    }

    @Test
    @DisplayName("会话不存在时拒绝")
    void 拒绝不存在的会话() {
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.markVisitorRead("nope", 42L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=VisitorReadReceiptTest`
Expected: 编译失败（`markVisitorRead` 不存在）。

- [ ] **Step 3: 实现**

```java
    @Override
    public long markVisitorRead(String sessionId, Long upToMessageId) {
        ChatSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在: " + sessionId));

        // 开放 API 的身份是「这个 app」，sessionId(UUID) 是第二重能力凭证——
        // 与附件下载端点要求 ?sessionId= 的定级一致
        Long appId = TenantContext.getAppId();
        if (appId != null && !Objects.equals(session.getAppId(), appId)) {
            throw new InvalidOperationException("会话不属于当前应用");
        }

        // readerId 用 chat_session.id：一个会话只有一个访客，用会话 id 即可唯一标识它。
        // ⚠️ 绝不能传 null——PG 唯一约束里 NULL != NULL，会让约束形同虚设
        long effective = readReceiptService.markRead(
                session.getId(), ReaderType.VISITOR, session.getId(), upToMessageId);

        if (session.getAgentId() != null) {
            realtimeNotifier.readReceiptToAgent(   // Task 5 补上此方法
                    session.getTenantId(), session.getAgentId(), sessionId, effective);
        }
        return effective;
    }
```

⚠️ 同 Task 3：`readReceiptToAgent` 由 Task 5 实现，本任务先注释掉并留 `// TODO(Task 5)`。

控制器（`OpenChatController`）：

```java
    @PostMapping("/sessions/{sessionId}/read")
    @Operation(summary = "访客上报已读")
    public ApiResponse<Long> markVisitorRead(@PathVariable String sessionId,
                                              @RequestBody ReadReceiptRequest request) {
        return ApiResponse.success(chatService.markVisitorRead(sessionId, request.getUpToMessageId()));
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=VisitorReadReceiptTest`
Expected: 3 条全绿。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(read-receipt): 访客回执接口

POST /open/v1/sessions/{id}/read。开放 API 的身份是「这个 app」，故额外校验会话
属于当前 app；sessionId(UUID) 本身是第二重能力凭证，与附件下载端点同一定级。

⚠️ readerId 用 chat_session.id 而非 null：PG 唯一约束里 NULL != NULL，留 null 会
让 (session_id,'VISITOR',NULL) 可插入任意多行，水位线多行并存、读哪行看运气，
且全程不报错。"
```

---

### Task 5: SSE `read` 事件双向推送

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeNotifier.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（放开 Task 3/4 注释掉的两行）
- Test: `backend/src/test/java/com/qingyu/care/service/realtime/ReadReceiptNotifyTest.java`

**Interfaces:**
- Consumes: `RealtimeHub.publish(String key, String event, String data)`、`RealtimeKeys.session(...)`/`.agent(...)`
- Produces:
  - `void RealtimeNotifier.readReceiptToUser(String sessionId, Long upToMessageId)`
  - `void RealtimeNotifier.readReceiptToAgent(Long tenantId, Long agentUserId, String sessionId, Long upToMessageId)`

- [ ] **Step 1: 写失败测试**

```java
@Test
@DisplayName("推给访客：事件名是 read，走会话 inbox 键")
void 推给访客走read事件() {
    notifier.readReceiptToUser("sess-1", 42L);
    verify(hub).publish(eq(RealtimeKeys.session("sess-1")), eq("read"), captor.capture());
    assertThat(captor.getValue()).contains("\"upToMessageId\":42").contains("\"sessionId\":\"sess-1\"");
}

@Test
@DisplayName("推给坐席：同样是 read 事件，走坐席单播键，载荷必须带 sessionId")
void 推给坐席走read事件() {
    notifier.readReceiptToAgent(1L, 100L, "sess-1", 42L);
    verify(hub).publish(eq(RealtimeKeys.agent(1L, 100L)), eq("read"), captor.capture());
    // ⚠️ sessionId 必须在载荷里：坐席可能正看着别的会话，收到时要按 sessionId 归位
    assertThat(captor.getValue()).contains("\"sessionId\":\"sess-1\"");
}

@Test
@DisplayName("事件名不是 session——复用 session 会让 widget 把它当文案显示出来")
void 不能复用session事件名() {
    notifier.readReceiptToUser("sess-1", 42L);
    verify(hub, never()).publish(any(), eq("session"), any());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=ReadReceiptNotifyTest`
Expected: 编译失败（两个方法不存在）。

- [ ] **Step 3: 实现**

在 `RealtimeNotifier` 里，紧挨 `typing(...)` 加：

```java
    /**
     * A：坐席读了 → 推给访客 inbox。
     *
     * <p>⚠️ <b>必须用独立的 {@code read} 事件，不能复用 {@link #toUserSessionEvent}</b>：
     * widget（{@code widget/src/main.ts}）对 {@code session} 事件是<b>无条件当文案展示</b>
     * （{@code ui.showSessionState(p.text || sessionStateText(p.kind))}），复用会让访客每次被读
     * 都看到一条莫名的状态提示。走独立事件类型天然免疫，且不依赖「将来加 kind 时记得加分支」。
     */
    public void readReceiptToUser(String sessionId, Long upToMessageId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("upToMessageId", upToMessageId);
        hub.publish(RealtimeKeys.session(sessionId), "read", json(m));
    }

    /**
     * B：访客读了 → 推给该会话的坐席。
     *
     * <p>与 {@link #readReceiptToUser} 用<b>同一个事件名</b>，靠订阅键区分方向。
     * ⚠️ 载荷必须带 {@code sessionId}：坐席工作台是多会话的，收到时可能正看着别的会话，
     * 必须按 sessionId 归位而不能默认标在当前会话上。
     */
    public void readReceiptToAgent(Long tenantId, Long agentUserId, String sessionId, Long upToMessageId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("upToMessageId", upToMessageId);
        hub.publish(RealtimeKeys.agent(tenantId, agentUserId), "read", json(m));
    }
```

- [ ] **Step 4: 放开 Task 3/4 留下的两处 TODO**

在 `ChatServiceImpl.markAgentRead` 与 `markVisitorRead` 里取消注释那两行推送调用，
删掉 `// TODO(Task 5)` 标记。全仓搜索确认无残留：

Run: `grep -rn "TODO(Task 5)" backend/src/main/java`
Expected: 无输出。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest='ReadReceiptNotifyTest,AgentReadReceiptTest,VisitorReadReceiptTest'`
Expected: 全绿。

⚠️ Task 3/4 的测试此时要补上「推送被调用一次」的断言（原先推送行被注释掉，那两条断言当时写不了）。

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(read-receipt): SSE read 事件双向推送

两个方向用同一个事件名 read，靠订阅键区分（访客订会话 inbox、坐席订单播键）。

⚠️ 不能复用 toUserSessionEvent：widget/src/main.ts 对 session 事件是无条件当文案
展示，复用会让访客每次被读都看到一条莫名提示。独立事件类型天然免疫，也不依赖
「将来加 kind 时记得加分支」这种约定。测试里有一条专门断言不发 session 事件。

⚠️ 推给坐席的载荷必须带 sessionId：工作台是多会话的，收到时可能正看着别的会话，
要按 sessionId 归位而非默认标在当前会话。"
```

---

### Task 6: 「我的会话」列表与未读数聚合

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/AgentSessionDTO.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/AgentSessionDao.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java`
- Modify: `ChatService.java` + `ChatServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/it/AgentSessionUnreadIT.java`

**Interfaces:**
- Consumes: Task 1 的表结构
- Produces:
  - `record AgentSessionDTO(Long id, String sessionId, String userId, SessionStatus status, LocalDateTime lastMessageAt, long unreadCount)`
  - `List<AgentSessionDTO> AgentSessionDao.findMySessions(Long tenantId, Long appId, Long agentId, int limit, int offset)`
  - `GET /v1/chat/sessions/mine` → `ApiResponse<List<AgentSessionDTO>>`

- [ ] **Step 1: 写 IT（先写，这条 SQL 是本任务的全部风险）**

`backend/src/test/java/com/qingyu/care/it/AgentSessionUnreadIT.java`，继承 `AbstractPostgresIT`。

⚠️ **播种直接用 `JdbcTemplate` 写库，不走 Repository**：`ChatSession` 继承 `BaseTenantEntity`，
经 Repository 保存会被 `TenantEntityListener` 按 `TenantContext` 回填租户字段，而本 IT 正是要
构造「两个不同租户」的数据来验证隔离——用 Repository 播种会让两条数据落进同一个租户，
断言退化成永真。

```java
package com.qingyu.care.it;

import com.qingyu.care.dto.AgentSessionDTO;
import com.qingyu.care.it.support.AbstractPostgresIT;
import com.qingyu.care.repository.AgentSessionDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「我的会话 + 未读数」那条原生 SQL 的口径与租户隔离。
 *
 * <p>⚠️ 原生 SQL 绕开 Hibernate {@code @Filter}，租户隔离必须靠 SQL 里显式的 tenant_id 条件，
 * 而那正是本仓库反复出事的地方，故必须有一条真库用例守着。
 *
 * @author ruan
 */
class AgentSessionUnreadIT extends AbstractPostgresIT {

    @Autowired AgentSessionDao dao;
    @Autowired JdbcTemplate jdbc;

    private static final Long TENANT_A = 1001L;
    private static final Long TENANT_B = 1002L;
    private static final Long APP_A = 2001L;
    private static final Long AGENT = 3001L;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM message_read_receipt");
        jdbc.update("DELETE FROM chat_message WHERE session_id IN "
                + "(SELECT id FROM chat_session WHERE tenant_id IN (?, ?))", TENANT_A, TENANT_B);
        jdbc.update("DELETE FROM chat_session WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
    }

    /** 播种一个已接管会话，返回它的主键。 */
    private Long seedSession(Long tenantId, Long appId, Long agentId, String sessionId) {
        jdbc.update("INSERT INTO chat_session (session_id, status, agent_id, tenant_id, app_id, "
                + "created_at, updated_at) VALUES (?, 'AGENT_PROCESSING', ?, ?, ?, ?, ?)",
                sessionId, agentId, tenantId, appId, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject(
                "SELECT id FROM chat_session WHERE session_id = ?", Long.class, sessionId);
    }

    /** 播种一条消息，返回它的主键。 */
    private Long seedMessage(Long sessionPk, String role, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO chat_message (session_id, role, content, created_at) "
                + "VALUES (?, ?, '内容', ?)", sessionPk, role, createdAt);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM chat_message WHERE session_id = ?", Long.class, sessionPk);
    }

    private void seedReceipt(Long sessionPk, Long upTo) {
        jdbc.update("INSERT INTO message_read_receipt "
                + "(session_id, reader_type, reader_id, up_to_message_id, read_at) "
                + "VALUES (?, 'AGENT', ?, ?, ?)", sessionPk, AGENT, upTo, LocalDateTime.now());
    }

    @Test
    @DisplayName("未读数只数访客消息、且只数水位线之后的")
    void 未读数口径正确() {
        Long sid = seedSession(TENANT_A, APP_A, AGENT, "s-1");
        LocalDateTime base = LocalDateTime.now().minusMinutes(10);
        seedMessage(sid, "USER", base);                       // 水位线之前
        Long second = seedMessage(sid, "AGENT", base.plusMinutes(1));
        seedMessage(sid, "USER", base.plusMinutes(2));        // 未读 1
        seedMessage(sid, "AGENT", base.plusMinutes(3));       // 坐席自己发的，不算未读
        seedMessage(sid, "USER", base.plusMinutes(4));        // 未读 2
        seedReceipt(sid, second);

        List<AgentSessionDTO> result = dao.findMySessions(TENANT_A, APP_A, AGENT, 20, 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).unreadCount())
                .as("只数水位线之后的 USER 消息，AGENT 消息不算")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("从无回执时未读数等于全部访客消息数")
    void 无回执时未读数为全部访客消息() {
        Long sid = seedSession(TENANT_A, APP_A, AGENT, "s-2");
        LocalDateTime base = LocalDateTime.now().minusMinutes(5);
        seedMessage(sid, "USER", base);
        seedMessage(sid, "USER", base.plusMinutes(1));
        seedMessage(sid, "AGENT", base.plusMinutes(2));
        // 不播种回执，走 COALESCE(up_to_message_id, 0) 兜底

        List<AgentSessionDTO> result = dao.findMySessions(TENANT_A, APP_A, AGENT, 20, 0);

        assertThat(result.get(0).unreadCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("跨租户查不到别人的会话")
    void 租户隔离() {
        seedSession(TENANT_A, APP_A, AGENT, "s-mine");
        seedSession(TENANT_B, APP_A, AGENT, "s-other");   // 同一个 agentId，但属于别的租户

        List<AgentSessionDTO> result = dao.findMySessions(TENANT_A, APP_A, AGENT, 20, 0);

        assertThat(result).extracting(AgentSessionDTO::sessionId)
                .as("原生 SQL 绕开 @Filter，租户隔离只能靠 SQL 里显式的 tenant_id 条件")
                .containsExactly("s-mine");
    }

    @Test
    @DisplayName("按最后一条消息时间倒序，不受 updated_at 影响")
    void 排序用最后消息时间() {
        LocalDateTime base = LocalDateTime.now().minusHours(1);
        Long older = seedSession(TENANT_A, APP_A, AGENT, "s-old");
        Long newer = seedSession(TENANT_A, APP_A, AGENT, "s-new");
        seedMessage(older, "USER", base);                    // 最后消息更早
        seedMessage(newer, "USER", base.plusMinutes(30));    // 最后消息更晚
        // 把「最后消息更早」那条的 updated_at 改成最新——若按 updated_at 排序，它会错误地排前面
        jdbc.update("UPDATE chat_session SET updated_at = ? WHERE id = ?", LocalDateTime.now(), older);

        List<AgentSessionDTO> result = dao.findMySessions(TENANT_A, APP_A, AGENT, 20, 0);

        assertThat(result).extracting(AgentSessionDTO::sessionId)
                .as("排序键必须是 MAX(m.created_at)；用 updated_at 会被打标签/改摘要/转接带动")
                .containsExactly("s-new", "s-old");
    }
}
```

⚠️ **播种 SQL 的列名以实际表结构为准**：`chat_session` / `chat_message` 的列可能与本计划写作时
不同（如 `chat_session` 还有 `no_hit_streak` 等非空列）。先跑一次，按报错补上必需列，
**不要改测试意图**。

- [ ] **Step 2: 跑 IT 确认失败**

Run: `cd backend && mvn verify -Dit.test=AgentSessionUnreadIT`
Expected: 编译失败（`AgentSessionDao` 不存在）。

- [ ] **Step 3: 实现 DAO**

```java
package com.qingyu.care.repository;

import com.qingyu.care.dto.AgentSessionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 坐席「我的进行中会话」及其未读数。
 *
 * <p>⚠️ <b>必须原生 SQL 且显式带 tenant_id / app_id 条件</b>：{@code ChatSession} 的
 * Hibernate {@code @Filter} 保护不到 JOIN 聚合查询，且 {@code TenantDataAspect} 对
 * PLATFORM_ADMIN 整体不启用过滤器。同 {@code getWaitingAgentSessions} 已有的手工收窄，
 * 以及 {@code AnalyticsRollupDao} / {@code findDueWebPageIds} 的既有模式。
 *
 * <p>⚠️ 排序键用 {@code MAX(m.created_at)} 而非 {@code chat_session.updated_at}：后者会被与
 * 消息无关的更新（打标签、改摘要、转接）带动，排序会莫名跳动。
 *
 * @author ruan
 */
@Repository
@RequiredArgsConstructor
public class AgentSessionDao {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL = """
            SELECT s.id, s.session_id, s.user_id, s.status,
                   MAX(m.created_at) AS last_message_at,
                   COUNT(*) FILTER (
                       WHERE m.role = 'USER'
                         AND m.id > COALESCE(r.up_to_message_id, 0)
                   ) AS unread_count
              FROM chat_session s
              LEFT JOIN chat_message m ON m.session_id = s.id
              LEFT JOIN message_read_receipt r
                     ON r.session_id = s.id
                    AND r.reader_type = 'AGENT'
                    AND r.reader_id = ?
             WHERE s.status = 'AGENT_PROCESSING'
               AND s.agent_id = ?
               AND s.tenant_id = ?
               AND (? IS NULL OR s.app_id = ?)
             GROUP BY s.id, s.session_id, s.user_id, s.status, r.up_to_message_id
             ORDER BY last_message_at DESC NULLS LAST
             LIMIT ? OFFSET ?
            """;

    public List<AgentSessionDTO> findMySessions(Long tenantId, Long appId, Long agentId,
                                                int limit, int offset) {
        return jdbcTemplate.query(SQL,
                (rs, i) -> new AgentSessionDTO(
                        rs.getLong("id"),
                        rs.getString("session_id"),
                        rs.getString("user_id"),
                        com.qingyu.care.enums.SessionStatus.valueOf(rs.getString("status")),
                        rs.getObject("last_message_at", java.time.LocalDateTime.class),
                        rs.getLong("unread_count")),
                agentId, agentId, tenantId, appId, appId, limit, offset);
    }
}
```

- [ ] **Step 4: 服务层与控制器**

`ChatServiceImpl`：

```java
    @Override
    public List<AgentSessionDTO> getMySessions(Long agentId, int page, int size) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return List.of();   // 平台管理员无租户上下文，返空而非裸查（同 getWaitingAgentSessions）
        }
        return agentSessionDao.findMySessions(
                tenantId, TenantContext.getAppId(), agentId, size, page * size);
    }
```

⚠️ 同 Task 3：`agentId` **由控制器取好传入**，service 层不碰 Sa-Token
（`SecurityUtil` 没有取 user id 的方法）。

`ChatController`：

```java
    @GetMapping("/sessions/mine")
    @Operation(summary = "我接管中的会话", description = "含每个会话的未读消息数，按最后消息时间倒序")
    public ApiResponse<List<AgentSessionDTO>> getMySessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        SecurityUtil.requireAgent();
        Long agentId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        return ApiResponse.success(chatService.getMySessions(agentId, page, size));
    }
```

- [ ] **Step 5: 跑 IT 确认通过**

Run: `cd backend && mvn verify -Dit.test=AgentSessionUnreadIT`
Expected: 4 条全绿。

- [ ] **Step 6: 变异验证租户隔离**

把 SQL 里 `AND s.tenant_id = ?` 删掉（参数也相应调整），重跑。

Expected: `租户隔离` 转红。改回来。

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat(read-receipt): 我的会话列表与未读数聚合

GET /v1/chat/sessions/mine，一条原生 SQL 聚合出整页的未读数与最后消息时间。

⚠️ 必须原生 SQL 且显式带 tenant_id/app_id：ChatSession 的 @Filter 保护不到 JOIN
聚合查询，且 TenantDataAspect 对 PLATFORM_ADMIN 整体不启用过滤器。IT 里有一条
租户隔离用例，变异验证过（删掉 tenant_id 条件即转红）。

⚠️ 排序用 MAX(m.created_at) 而非 chat_session.updated_at：后者会被打标签/改摘要/
转接带动，排序莫名跳动。"
```

---

### Task 7: 契约补齐（`userMessageId` + 两端历史带水位线）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/dto/open/OpenChatResponse.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（START 事件与非流式响应组装处）
- Modify: `backend/src/main/java/com/qingyu/care/dto/ChatMessageDTO.java` 的消费端点（历史响应）
- Test: `backend/src/test/java/com/qingyu/care/service/ReadWatermarkContractTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ReadReceiptService.currentWatermark(...)`
- Produces:
  - `OpenChatResponse.userMessageId`（Long）
  - 流式 START 事件载荷新增 `userMessageId`
  - 访客历史响应新增 `agentReadUpToMessageId`
  - 坐席历史响应新增 `visitorReadUpToMessageId`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/qingyu/care/service/ReadWatermarkContractTest.java`。前两条是纯
DTO/装配断言，第三条桩 `ReadReceiptService` 即可，都不需要真库。

```java
    @Test
    @DisplayName("OpenChatResponse 的 userMessageId 与 messageId 是两个不同的字段")
    void 非流式响应带userMessageId() {
        // ⚠️ 现有 messageId 是 AI 回复的 id（供反馈按钮使用）；访客发出的那条自身的 id
        //    此前从未透出，导致 widget 无法与已读水位线比对。
        OpenChatResponse resp = OpenChatResponse.builder()
                .sessionId("s1")
                .messageId(200L)        // AI 回复
                .userMessageId(199L)    // 访客自己那条
                .reply("您好")
                .build();

        assertThat(resp.getUserMessageId()).isEqualTo(199L);
        assertThat(resp.getMessageId())
                .as("两者不能混用：混了就会把 AI 回复的 id 拿去跟访客侧水位线比对")
                .isNotEqualTo(resp.getUserMessageId());
    }

    @Test
    @DisplayName("userMessageId 无初始化器：请求未产生用户消息时必须是 null 而非 0")
    void userMessageId无初始化器() {
        // 若写成 `private Long userMessageId = 0L;`，widget 会把 0 当成一个真实 id 去比对，
        // 而水位线 >= 0 恒成立 → 首条消息一发出就显示「已读」。
        assertThat(new OpenChatResponse().getUserMessageId()).isNull();
    }

    @Test
    @DisplayName("访客历史带坐席水位线，坐席历史带访客水位线，各只带对方的")
    void 两端历史各带对方水位线() {
        // ⚠️ 各只需要「对方」的：把自己的也返回会诱使前端拿它当本地状态的事实源，
        //    而本地水位线的推进条件（可见性）只有客户端自己知道。
        when(readReceiptService.currentWatermark(7L, ReaderType.AGENT, 100L)).thenReturn(42L);
        when(readReceiptService.currentWatermark(7L, ReaderType.VISITOR, 7L)).thenReturn(88L);

        var visitorView = chatService.getVisitorHistory("sess-1");
        var agentView = chatService.getAgentHistory("sess-1");

        assertThat(visitorView.getAgentReadUpToMessageId()).isEqualTo(42L);
        assertThat(agentView.getVisitorReadUpToMessageId()).isEqualTo(88L);
    }
```

⚠️ 第三条里 `getVisitorHistory` / `getAgentHistory` 是**本任务要确定的方法名**——仓库现有的
历史查询入口是 `ChatController.getSessionMessages`（坐席端）与 widget 侧的会话恢复接口。
实施时**不要新造两个方法**，而是给这两条既有路径的响应各加一个字段；测试相应改成断言那两个
既有端点的返回值。保持断言意图不变：**各自只拿到对方的水位线**。

- [ ] **Step 2-4: 跑失败 → 实现 → 跑通过**

`OpenChatResponse` 加字段（**不写初始化器**）：

```java
    /**
     * 访客<b>自己发出</b>的那条消息的 id。
     *
     * <p>⚠️ 与 {@link #messageId} 区分：那个是 <b>AI 回复</b>的 id（供反馈按钮使用）。
     * 访客要拿本字段与已读水位线比对，才知道自己哪条消息被坐席读了。
     */
    private Long userMessageId;
```

历史响应的两个字段按 §4.4 加到各自的响应包装里；取值走
`readReceiptService.currentWatermark(sessionId, ReaderType.AGENT/VISITOR, ...)`。

Run: `cd backend && mvn test -Dtest=ReadWatermarkContractTest`
Expected: 3 条全绿。

- [ ] **Step 5: 全量回归 + 提交**

Run: `cd backend && mvn test`
Expected: 全绿。

```bash
git add -A && git commit -m "feat(read-receipt): 补齐 userMessageId 与两端历史水位线

⚠️ 现有 OpenChatResponse.messageId 是 AI 回复的 id（供反馈按钮），访客发出的那条
自身的 id 从未透出，无法与已读水位线比对。非流式响应与流式 START 各补一个
userMessageId。

不能省成「收到 read 事件就把最后一条标已读」：访客发了新消息而坐席正在看别的
会话时，会收到针对更早消息的回执，那样会把并未被读的最新消息错标成已读。

两端历史各带对方的水位线，用于刷新后恢复；各只带对方的——把自己的也返回会诱使
前端拿它当本地状态的事实源，而本地推进条件（可见性）只有客户端知道。"
```

---

### Task 8: 坐席台「我的会话」列表与未读徽章

**Files:**
- Modify: `frontend/src/api/agent.ts`
- Modify: `frontend/src/pages/AgentPage.tsx`
- Modify: `frontend/src/types/*`（`AgentSession` 类型）
- Test: `frontend/src/pages/AgentPage.test.tsx`（新建）

**Interfaces:**
- Consumes: Task 6 的 `GET /v1/chat/sessions/mine`
- Produces:
  - `agentService.getMySessions(page?, size?): Promise<ApiResponse<AgentSession[]>>`
  - `AgentPage` 左栏两段结构；`mySessions` state

- [ ] **Step 1: 写失败测试**

`frontend/src/pages/AgentPage.test.tsx`。桩到 `api/*.ts` 的 service 对象（这一层正是
「HTTP 200 + `body.code`」约定发生的位置），**不引入 MSW**。模板见
`frontend/src/components/knowledge/CrawlSiteList.test.tsx`。

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
// ⚠️ frontend/ 故意不开 globals：测试文件必须显式 import。
// ⚠️ src/test/setup.ts 里那行 afterEach(cleanup) 不能删——RTL 只在 afterEach 是全局函数时
//    才自己注册 cleanup，漏掉的症状是「单个测试跑绿、整套一起跑红」。
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AgentPage from './AgentPage';

// ⚠️ vi.mock 的工厂被提到所有 import 之前执行，引用模块顶层 const 会撞 TDZ，
//    桩函数必须用 vi.hoisted 一起提上去。
const stubs = vi.hoisted(() => ({
    getWaitingAgentSessions: vi.fn(),
    getMySessions: vi.fn(),
    acceptSession: vi.fn(),
    getOnlineAgents: vi.fn(),
    markRead: vi.fn(),
    getSessionMessages: vi.fn(),
}));

vi.mock('@/api/agent', () => ({ agentService: stubs }));
vi.mock('@/api/chat', () => ({ chatService: { getSessionMessages: stubs.getSessionMessages } }));
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }));

const ok = <T,>(data: T) => ({ code: 200, message: 'ok', data });
const page = <T,>(content: T[]) => ok({ content, totalElements: content.length });

const mySession = (sessionId: string, unreadCount: number) => ({
    id: 1, sessionId, userId: 'u1', status: 'AGENT_PROCESSING',
    lastMessageAt: '2026-08-20T10:00:00', unreadCount,
});

beforeEach(() => {
    Object.values(stubs).forEach((s) => s.mockReset());
    stubs.getWaitingAgentSessions.mockResolvedValue(page([]));
    stubs.getMySessions.mockResolvedValue(ok([]));
    stubs.getOnlineAgents.mockResolvedValue(ok([]));
    stubs.getSessionMessages.mockResolvedValue(ok([]));
    stubs.markRead.mockResolvedValue(ok(0));
});

afterEach(() => {
    vi.useRealTimers();
});

describe('坐席台我的会话列表', () => {
    it('渲染未读徽章', async () => {
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 3)]));

        render(<AgentPage />);

        expect(await screen.findByText('3')).toBeInTheDocument();
    });

    it('未读徽章挂在我的会话上，不在待接队列上', async () => {
        // ⚠️ 这条守的正是那个死代码缺陷：原实现把 unread 渲染在 waitingSessions.map 里，
        //    而 unread 的 key 来自 toAgentMessage 推送（AGENT_PROCESSING 会话），
        //    两个集合按定义不相交，徽章永远不显示。
        stubs.getWaitingAgentSessions.mockResolvedValue(page([
            { id: 9, sessionId: 'waiting-1', status: 'WAITING_AGENT' },
        ]));
        stubs.getMySessions.mockResolvedValue(ok([mySession('mine-1', 5)]));

        render(<AgentPage />);

        const mineRow = await screen.findByTestId('my-session-mine-1');
        expect(mineRow).toHaveTextContent('5');
        expect(await screen.findByTestId('waiting-session-waiting-1')).not.toHaveTextContent('5');
    });

    it('列表刷新后未读数以后端值为准，不与本地累加叠加', async () => {
        // ⚠️ 守 spec §6.4：SSE 到达时的本地 +1 只是过渡态。
        //    若实现改成以本地累加为准，会漂移且不报错——只是徽章数字不对。
        stubs.getMySessions.mockResolvedValueOnce(ok([mySession('s1', 3)]));
        render(<AgentPage />);
        expect(await screen.findByText('3')).toBeInTheDocument();

        // 后端聚合出的新值是 7（不是 3 + 本地增量）
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 7)]));
        await act(async () => {
            window.dispatchEvent(new CustomEvent('agent-sse-message', {
                detail: { sessionId: 's1' },
            }));
        });

        expect(await screen.findByText('7')).toBeInTheDocument();
        expect(screen.queryByText('11')).not.toBeInTheDocument();
    });
});
```

⚠️ **实施时的两处适配**，按实际实现调整但**不要改测试意图**：
- 上面用 `data-testid`（`my-session-{sessionId}` / `waiting-session-{sessionId}`）定位行，
  实现时需要在对应元素上加这两个 testid；
- 第三条用一个自定义事件模拟 SSE 到达。若 `AgentPage` 的 SSE 是内部 `fetch` + `ReadableStream`
  （真人坐席那两条流就是这么做的，`EventSource` 不能带 header），改为直接桩那条流、或把
  「收到消息」抽成一个可测的回调再驱动它。**关键是驱动到「本地 +1 之后又刷新列表」这条路径**，
  只断言初始渲染是没有判别力的。

- [ ] **Step 2: 跑失败 → 实现 → 跑通过**

Run: `cd frontend && pnpm test AgentPage`

实现要点：
- `mySessions` state + `fetchMySessions()`；
- 接管成功后调 `fetchMySessions()`（该会话此时进入"我的会话"）；
- 左栏两段（待接队列在上、我的会话在下），徽章绑 `session.unreadCount`；
- SSE `message` 事件：`sid === selectedSession?.sessionId` 则追加消息，否则该会话 `unreadCount + 1`；
- **任何一次 `fetchMySessions()` 的结果直接覆盖本地值**，注释写明「本地 +1 只是过渡态，
  别改成以本地累加为准——会漂移且不报错」。

- [ ] **Step 3: lint + 提交**

Run: `cd frontend && pnpm lint && pnpm test`
Expected: 零 error，全绿。

```bash
git add -A && git commit -m "feat(read-receipt): 坐席台我的会话列表与未读徽章

左栏改为两段（待接队列 / 我的会话），修掉两个现存缺陷：

1. 接管第二个会话会丢掉第一个——handleAcceptSession 只做 setSelectedSession，
   而该会话同时因状态变更从待接队列消失，此前又没有「我的会话」查询接口，
   刷新也回不去
2. 未读徽章是死代码永不显示——unread 的 key 来自 toAgentMessage 推送（即
   AGENT_PROCESSING 且 agentId==我 的会话），而徽章渲染在 waitingSessions.map
   里（WAITING_AGENT 状态），两个集合按定义不相交

⚠️ 未读数以后端聚合值为准，SSE 到达时的本地 +1 只是过渡态，任何一次列表刷新都
直接覆盖。改成以本地累加为准会漂移，且漂移不报错、只是数字不对。"
```

---

### Task 9: 坐席台回执上报（A）与访客已读渲染（B）

**Files:**
- Modify: `frontend/src/api/agent.ts`（加 `markRead`）
- Modify: `frontend/src/pages/AgentPage.tsx`
- Test: `frontend/src/pages/AgentPage.test.tsx`（追加）

**Interfaces:**
- Consumes: Task 3 的 `POST /v1/chat/session/{id}/read`；Task 5 的坐席侧 `read` 事件；Task 7 的 `visitorReadUpToMessageId`
- Produces: 无（终端消费者）

- [ ] **Step 1: 写失败测试**

追加到 Task 8 建的 `AgentPage.test.tsx`（复用那里的 `stubs` / `ok` / `mySession` 辅助）。

```tsx
/** 桩标签页可见性。jsdom 下 visibilityState 只读，必须用 defineProperty 覆盖。 */
function stubVisibility(state: 'visible' | 'hidden') {
    Object.defineProperty(document, 'visibilityState', {
        configurable: true,
        get: () => state,
    });
}

describe('坐席台已读回执', () => {
    it('选中会话且页面可见时上报已读', async () => {
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 2)]));
        stubs.getSessionMessages.mockResolvedValue(ok([
            { id: 41, role: 'USER', content: '你好', createdAt: '2026-08-20T10:00:00' },
            { id: 42, role: 'USER', content: '在吗', createdAt: '2026-08-20T10:01:00' },
        ]));

        render(<AgentPage />);
        await userEvent.click(await screen.findByTestId('my-session-s1'));

        // upToMessageId 传「已渲染的最新一条」，不是服务端库里最新的
        expect(stubs.markRead).toHaveBeenCalledWith('s1', 42);
    });

    it('页面在后台时不上报', async () => {
        // ⚠️ 守的是「工作台开着但人去开会了」那类假已读。
        //    只判「选中会话」挡不住它——变异验证时把 visibilityState 条件去掉，本条必须转红。
        stubVisibility('hidden');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 2)]));
        stubs.getSessionMessages.mockResolvedValue(ok([
            { id: 42, role: 'USER', content: '在吗', createdAt: '2026-08-20T10:01:00' },
        ]));

        render(<AgentPage />);
        await userEvent.click(await screen.findByTestId('my-session-s1'));

        expect(stubs.markRead).not.toHaveBeenCalled();
    });

    it('收到非当前会话的 read 事件时，标记落在正确的会话上', async () => {
        // ⚠️ 守 spec §6.6：最容易写成「直接标在当前会话」，在多会话工作台下是错的。
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('jia', 0), mySession('yi', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok([
            { id: 50, role: 'AGENT', content: '您好，有什么可以帮您', createdAt: '2026-08-20T10:00:00' },
        ]));

        render(<AgentPage />);
        await userEvent.click(await screen.findByTestId('my-session-jia'));   // 正在看「甲」

        await act(async () => {
            window.dispatchEvent(new CustomEvent('agent-sse-read', {
                detail: { sessionId: 'yi', upToMessageId: 50 },   // 「乙」的访客读了
            }));
        });

        // 甲的消息区不该出现已读——那条回执不是它的
        expect(screen.queryByTestId('read-mark-50')).not.toBeInTheDocument();
    });
});
```

⚠️ 第三条同样依赖「怎么把 SSE 事件喂进组件」这个适配点（见 Task 8 的说明）。
断言的核心是**当前会话不能被别的会话的回执污染**；若实现把 `visitorReadMap` 暴露得更易断言
（例如切到乙之后才渲染），可改为「切到乙 → 出现已读；切回甲 → 不出现」，判别力等价。

- [ ] **Step 2: 跑失败 → 实现 → 跑通过**

实现要点：
- `reportRead(sessionId, upToMessageId)`：`fail-soft`，失败只 `console.debug`，不弹 toast
  （坐席正在服务客户，不该被"已读上报失败"打断）；
- 三个触发点：切换会话、收到该会话新消息、`visibilitychange` 转为 visible；
- 条件：`selectedSession?.sessionId === sid && document.visibilityState === 'visible'`；
- SSE `read` 事件：按载荷 `sessionId` 归位，存进 `visitorReadMap: Record<string, number>`；
- 渲染：当前会话中 id ≤ `visitorReadMap[当前会话]` 的**最后一条 AGENT 消息**下方显示"已读"。

Run: `cd frontend && pnpm test AgentPage`

- [ ] **Step 3: 变异验证**

把 `document.visibilityState === 'visible'` 条件去掉，重跑。
Expected: `页面在后台时不上报` 转红。改回来。

- [ ] **Step 4: lint + 提交**

```bash
cd frontend && pnpm lint && pnpm test
git add -A && git commit -m "feat(read-receipt): 坐席台上报已读与渲染访客已读

上报条件是「选中会话 且 标签页前台可见」——只判选中挡不住「工作台开着但人去
开会了」这类假已读，变异验证过。

⚠️ 收到 read 事件必须按载荷 sessionId 归位，不能默认标在当前会话上：工作台现在
是多会话的，坐席可能正看着甲而乙的访客读了消息。这是多会话列表引入的新失效模式。

上报 fail-soft：失败只 console.debug，不弹 toast——坐席正在服务客户，不该被
「已读上报失败」打断。"
```

---

### Task 10: widget 渲染已读（A）与上报已读（B）

**Files:**
- Modify: `widget/src/ui.ts`（`addUserMessage` 改为返回句柄；新增 `markReadUpTo`）
- Modify: `widget/src/main.ts`（`read` 事件分支、上报触发点）
- Modify: `widget/src/api.ts`（`markRead`）
- Test: `widget/src/main.test.ts`、`widget/src/ui.test.ts`（追加）
- Build: `backend/src/main/resources/static/widget.js`（产物，必须一起提交）

**Interfaces:**
- Consumes: Task 4 的 `POST /open/v1/sessions/{id}/read`；Task 5 的访客侧 `read` 事件；Task 7 的 `userMessageId` 与 `agentReadUpToMessageId`
- Produces:
  - `addUserMessage(text, attachments?) => { setMessageId(id: number): void }` —— **签名变更**
  - `markReadUpTo(upToMessageId: number): void`

- [ ] **Step 1: 写失败测试**

widget 侧**开了** `globals: true`（与 `frontend/` 不同），可直接用 `describe`/`it`/`expect`。
追加到 `widget/src/ui.test.ts` 与 `widget/src/main.test.ts`。

`ui.test.ts`：

```ts
it('按 upToMessageId 只标最后一条已读的访客消息', () => {
    const ui = mountUi(brand, handlers);          // 照本文件既有用例的挂载方式
    ui.addUserMessage('第一条').setMessageId(1);
    ui.addUserMessage('第二条').setMessageId(2);
    ui.addUserMessage('第三条').setMessageId(3);

    ui.markReadUpTo(2);

    // 只标最后一条已读的：id=1 虽然也已读，但水位线语义下它被 id=2 蕴含
    expect(root.querySelectorAll('[data-read-mark]')).toHaveLength(1);
    expect(root.querySelector('[data-message-id="2"] [data-read-mark]')).not.toBeNull();
    expect(root.querySelector('[data-message-id="3"] [data-read-mark]')).toBeNull();
});

it('水位线前移后旧标记被清除，不会同时出现两个已读', () => {
    const ui = mountUi(brand, handlers);
    ui.addUserMessage('第一条').setMessageId(1);
    ui.addUserMessage('第二条').setMessageId(2);

    ui.markReadUpTo(1);
    ui.markReadUpTo(2);

    expect(root.querySelectorAll('[data-read-mark]')).toHaveLength(1);
    expect(root.querySelector('[data-message-id="2"] [data-read-mark]')).not.toBeNull();
});
```

`main.test.ts`：

```ts
it('read 事件不产生任何文案节点', () => {
    // ⚠️ 变异测试的好目标：把实现改成 ui.showSessionState('已读')（即复用 session 事件那条
    //    路径），本条必须转红。这守的是「访客界面不冒莫名提示」。
    const showSessionState = vi.fn();
    // 照本文件既有用例的方式挂载 main，并把 ui 桩成含 showSessionState / markReadUpTo 的对象
    emitSse('read', { sessionId: 's1', upToMessageId: 7 });

    expect(showSessionState).not.toHaveBeenCalled();
    expect(markReadUpTo).toHaveBeenCalledWith(7);
});

it('widget 收起时不上报已读', () => {
    // ⚠️ 收起是 widget 自己的内部状态，与页面可见性是两个独立条件，缺任一判断都产生假已读
    setPanelOpen(false);
    stubVisibility('visible');

    emitSse('message', { sessionId: 's1', messageId: 9, role: 'AGENT', content: '在的' });

    expect(api.markRead).not.toHaveBeenCalled();
});

it('页面在后台时不上报已读，即使 widget 是展开的', () => {
    setPanelOpen(true);
    stubVisibility('hidden');

    emitSse('message', { sessionId: 's1', messageId: 9, role: 'AGENT', content: '在的' });

    expect(api.markRead).not.toHaveBeenCalled();
});

it('widget 展开且页面可见时上报，upToMessageId 是最新那条', () => {
    setPanelOpen(true);
    stubVisibility('visible');

    emitSse('message', { sessionId: 's1', messageId: 9, role: 'AGENT', content: '在的' });

    expect(api.markRead).toHaveBeenCalledWith('s1', 9);
});
```

⚠️ `emitSse` / `setPanelOpen` / `stubVisibility` 是**本任务要在测试文件里写的小辅助**，不是
现成 API：`emitSse` 照 `main.test.ts` 既有用例驱动 SSE 的方式实现；`setPanelOpen` 用真实的
展开/收起入口（点击悬浮按钮）而不是直接改内部变量——直接改内部状态测不出「按钮点击后
状态真的变了」；`stubVisibility` 同 Task 9 的 `Object.defineProperty` 写法。

- [ ] **Step 2: 跑失败**

Run: `cd widget && pnpm test`

- [ ] **Step 3: 实现**

`ui.ts`：

```ts
export interface WidgetUi {
  /**
   * 渲染一条访客消息，返回句柄用于回填消息 id。
   *
   * ⚠️ 签名从 `void` 改为返回句柄：已读标记要按 messageId 定位到具体气泡，而访客发消息时
   * 是乐观渲染（先上屏、id 由服务端响应带回），必须有办法事后把 id 绑到那个 DOM 节点上。
   * 形态照既有的 `beginBotMessage` 返回 `StreamingBotMessage`。
   */
  addUserMessage: (text: string, attachments?: WidgetAttachment[]) => UserMessageHandle;

  /** 把 id ≤ upToMessageId 的最后一条访客消息标为「已读」，其余清除标记。 */
  markReadUpTo: (upToMessageId: number) => void;
  // ...既有成员不变
}

export interface UserMessageHandle {
  setMessageId: (id: number) => void;
}
```

实现时给访客消息气泡加 `data-message-id` 属性，`markReadUpTo` 据此查找。

`main.ts` 事件分支：

```ts
      } else if (event === 'read') {
        // ⚠️ 独立事件类型，绝不走 showSessionState——那会在访客界面冒出一条莫名提示
        if (typeof p.upToMessageId === 'number') ui.markReadUpTo(p.upToMessageId);
      }
```

上报条件：`isOpen && document.visibilityState === 'visible'`，三个触发点见 spec §5.4。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd widget && pnpm test`
Expected: 109 + 新增，全绿。

- [ ] **Step 5: 变异验证**

把 `read` 分支改成 `ui.showSessionState('已读')`，重跑。
Expected: `read 事件不产生任何文案节点` 转红。改回来。

- [ ] **Step 6: 构建产物并提交**

⚠️ **必须 build**，否则 `scripts/buildFreshness.test.ts` 转红（它把 `widget/src/**/*.ts` 的
sha256 指纹写进产物，测试重算比对）。

```bash
cd widget && pnpm build && pnpm test
cd .. && git add widget/src backend/src/main/resources/static/widget.js
git commit -m "feat(read-receipt): widget 渲染已读与上报已读

⚠️ addUserMessage 签名从 void 改为返回句柄：已读标记要按 messageId 定位气泡，而
访客发消息是乐观渲染（先上屏、id 由响应带回），必须能事后把 id 绑到 DOM 上。
形态照既有 beginBotMessage 返回 StreamingBotMessage。

⚠️ read 走独立事件分支，绝不能走 showSessionState——那会在访客界面冒出一条莫名
提示。测试里有一条专门断言它不产生任何文案节点，变异验证过。

上报条件是「widget 展开 且 页面前台可见」两个条件同时成立：收起是 widget 自己的
内部状态，与页面可见性无关，缺任一判断都会产生假已读。

产物 widget.js 一并提交（buildFreshness 哨兵要求）。"
```

---

### Task 11: 文档与手工验证清单

**Files:**
- Modify: `CLAUDE.md`（新增一节）
- Modify: `scripts/manual-smoke-hints.sh`（加路径 → 章节映射）
- Modify: `SaaS平台规划.md`（Phase 5.2 该项打勾）

**Interfaces:**
- Consumes: 前 10 个任务的全部产出
- Produces: 无

- [ ] **Step 1: CLAUDE.md 加一节**

在「改动聊天附件链路后必须手工验证」之后加：

```markdown
### 改动已读回执链路后必须手工验证

自动化测试覆盖不到的七条：

1. **多标签页并发**：坐席开两个标签页同看一个会话，回执不打架、水位线不回退。jsdom 造不出真实并发。
2. **Page Visibility 真实行为**：切到别的标签页不上报、切回来立即上报。jsdom 里 `visibilityState` 是测试自己 stub 的，等于在测桩。
3. **访客刷新后已读保持**：真机刷新 widget，已读标记仍在（走的是历史响应里的 `agentReadUpToMessageId`）。
4. **多实例下 `read` 事件到达**：`RealtimeHub` 走 Redis 桥接，单实例测不出跨实例路径（同 `WebhookQueueIT` 面对的问题）。
5. **访客界面无视觉噪音**：坐席反复读，访客侧只出现「已读」二字，不冒任何状态提示条。⚠️ 复用 `session` 事件就会踩这条。
6. **B 的双向真机联调**：坐席发消息 → widget 展开着 → 坐席端出现「已读」；把 widget 收起再让坐席发一条 → 坐席端**不该**出现已读。收起是 widget 内部状态，jsdom 里测的是我们自己的桩。
7. **坐席正看着别的会话时收到 B 回执**：开两个已接管会话，看着甲，让乙的访客读消息 → 标记必须落在乙上。这是多会话列表引入的新失效模式，只在真机多开时现形。
```

- [ ] **Step 2: 更新 hints 脚本**

在 `scripts/manual-smoke-hints.sh` 里加映射：`ReadReceipt|read_receipt|AgentPage|widget/src/main.ts`
→ `改动已读回执链路后必须手工验证`。

⚠️ 该脚本会校验引用的章节标题在 CLAUDE.md 中存在，标题写错会 `exit 1`。

- [ ] **Step 3: 验证脚本**

Run: `./scripts/manual-smoke-hints.sh master...HEAD`
Expected: 退出码 0，且列出「改动已读回执链路后必须手工验证」这一节。

- [ ] **Step 4: 规划文档打勾**

`SaaS平台规划.md` 的 Phase 5.2「消息已读 / 送达状态」条目改为 `[x]`，注明：
只做已读不做送达（理由见 spec §2.2）、多坐席协作单独立项（§2.7）。

- [ ] **Step 5: 全量回归**

```bash
cd backend && mvn test          # 全绿
cd ../frontend && pnpm lint && pnpm test && pnpm build
cd ../widget && pnpm test && pnpm build
cd .. && git status --short     # widget.js 若有变更必须提交
```

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "docs: 已读回执的手工验证清单与规划打勾

七条手工验证项，每条都写明自动化为何盖不住。其中两条是本轮新引入的失效模式：
widget 收起状态（jsdom 里测的是自己的桩）、坐席正看着别的会话时收到回执
（多会话列表引入，只在真机多开时现形）。

hints 脚本加路径映射；脚本会校验章节标题存在，改标题需同步。"
```

---

## 自查记录

**Spec 覆盖**：§3.1/3.2/3.2.1/3.3/3.4 → Task 1；§4.1+§5.2 → Task 3；§4.2 → Task 6；
§4.3 → Task 4；§4.4+§4.5 → Task 7；§5.1 → Task 5；§5.3 → Task 3/4（先落库再推送）；
§5.4 → Task 9/10；§6.1/6.2 → Task 8；§6.3/6.4 → Task 8；§6.5 → Task 10；§6.6 → Task 9；
§7.1 → Task 2/3/4；§7.2 → Task 1/6；§7.3 → Task 8/9；§7.4 → Task 10；§7.5 → Task 9/10 的
fail-soft；§8 → Task 11。§2 全部是决策依据，已分散进各任务的注释与 commit 说明。

**类型一致性**：`markRead` 在 Task 2 定义为返回 `long`（生效后水位线），Task 3/4 按此消费；
`ReadReceiptRequest` 在 Task 3 建、Task 4 复用；`AgentSessionDTO` 在 Task 6 定义，Task 8 消费；
`userMessageId` 在 Task 7 产出，Task 10 消费；`addUserMessage` 的签名变更集中在 Task 10。

**已知的任务间临时占位**：Task 3/4 会写下 `// TODO(Task 5)` 注释掉推送调用，Task 5 的 Step 4
负责放开并用 `grep` 确认无残留。这是唯一允许的跨任务占位，因为推送方法与调用点分属两个任务。

**自查中修正的问题**：初稿里 Task 3/4/6/7/8/9/10 的测试步骤写成了「注释描述期望 + 空方法体」
的骨架。子代理看不到彼此的上下文，骨架等于让它自由发挥断言口径——而本计划里好几条测试的
判别力恰恰在细节上（如「只标最后一条」而非「标所有已读的」、「本地 +1 后被后端值覆盖」而非
「显示了某个数」）。已全部补成可跑代码。

**留给实施者的适配点**（都标了 ⚠️，共 5 处）：这些是「写计划时无法确定、但实施时一跑就知道」
的细节，每处都写明了**允许调整什么、不允许改什么**：
1. Task 3 —— `ChatServiceImpl` 的 19 个依赖清单（上面只声明了测试路径用得到的）；
2. Task 6 —— `chat_session` / `chat_message` 的非空列（播种 SQL 要补齐）；
3. Task 8/9 —— 怎么把 SSE 事件喂进 `AgentPage`（真人坐席那两条流走的是 `fetch` +
   `ReadableStream`，不是 `EventSource`）；
4. Task 10 —— `emitSse` / `setPanelOpen` 两个测试辅助的实现方式；
5. Task 7 —— 两端历史响应的既有端点名（**不要新造方法**，给既有路径加字段）。

一律「按报错调整装配，不要改测试意图」。

# 检索失败事务隔离（fail-soft 修复）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `ChatServiceImpl.chat()`/`streamChat()` 的知识检索跑在独立只读事务上，检索 SQL 失败只毒化该独立连接、不再毒化 chat 写事务连接，闭合「检索 fail-soft 被事务毒化 → chat 500」缺陷。

**Architecture:** `ChatServiceImpl` 自注入 `self`（仿 `KnowledgeServiceImpl` 的 `@Lazy` setter 范式）；2 参 `searchKnowledge(String, ChatStrategy)` 由 private 改 public 并标注 `@Transactional(propagation=REQUIRES_NEW, readOnly=true)`；仅 `chat()`/`streamChat()` 两处经 `self.searchKnowledge(...)` 走代理触发新事务，公开入口 `searchKnowledge(String)` 内部保持直调（不经代理、注解失效）以保住检索类 IT 的同事务播种可见性。

**Tech Stack:** Spring Boot 3 / Spring `@Transactional`（REQUIRES_NEW）/ JUnit5 + Mockito（单测）/ Testcontainers pgvector（IT）。

## Global Constraints

- 全部简体中文：代码注释、commit 信息、文档。作者署名 `ruan`。
- 本项目允许 `git commit`，且仅在特性分支 `fix/retrieval-tx-isolation` 上提交；未获授权**不 push**。
- 单元测试纯 Mockito（`@ExtendWith(MockitoExtension.class)`，不启 Spring 容器、不需 Docker）；集成测试类名以 `IT` 结尾、继承 `support/AbstractPostgresIT`（需 Docker Desktop）。
- **不重构 `chat()` 整体事务边界，不改检索业务逻辑 / RRF / RetrievalScope。** 只做事务传播隔离。
- **绝不把公开入口 `searchKnowledge(String)`（`ChatServiceImpl` 约 520 行）内部对 2 参方法的调用改成 `self.`**——保持 `searchKnowledge(query, strategy)` 直调，否则 `HybridRetrievalIT`/`DocVisibilityRetrievalIT`/`MultiKbRetrievalIT`（类级 `@Transactional`、同事务先播种再检索）会因 REQUIRES_NEW 新连接看不见未提交播种而全崩。

---

## 文件结构

- `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（改）：自注入 `self` + setter；2 参 `searchKnowledge` 改 public + REQUIRES_NEW 注解；`chat()`/`streamChat()` 两处调用改 `self.`；`import Propagation`。
- `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceSessionOwnershipTest.java`（改）：`@BeforeEach` 补 `chatService.setSelf(chatService)` 防 `self` 为 null 的 NPE 回归。
- `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceRetrievalTxTest.java`（新）：路由单测（`self` 注入 mock、verify 检索经代理）+ 注解守卫（反射断言 REQUIRES_NEW/readOnly）。
- `backend/src/test/java/com/qingyu/care/it/RetrievalTxIsolationIT.java`（新）：机制级验证——`TransactionTemplate` REQUIRES_NEW 探针，正例（隔离后外层不被毒化）+ 反例（同事务失败毒化外层）。

---

### Task 1: 检索走 REQUIRES_NEW 独立只读事务 + 自注入（含单测与回归修复）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Modify: `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceSessionOwnershipTest.java`
- Create/Test: `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceRetrievalTxTest.java`

**Interfaces:**
- Produces：`ChatServiceImpl` 新增公开方法
  `public void setSelf(ChatServiceImpl self)` 与
  `public List<Document> searchKnowledge(String query, ChatStrategy strategy)`（带 `@Transactional(propagation=REQUIRES_NEW, readOnly=true)`）。
- Consumes：现有 `public List<Document> searchKnowledge(String query)`（接口方法，签名不变）、`ChatStrategy`、`RetrievalScopeResolver`、`HybridRetrievalService`、`VectorStore`。

- [ ] **Step 1：先写路由 + 注解守卫单测（新建 `ChatServiceRetrievalTxTest`）**

新建 `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceRetrievalTxTest.java`：

```java
package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索事务隔离修复的单测：验证 chat() 经 self 代理路由检索（REQUIRES_NEW 才生效），
 * 以及 2 参 searchKnowledge 带 REQUIRES_NEW 只读事务注解（守住核心配置不被误删）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceRetrievalTxTest {

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
    @Mock ConfigService configService;
    @Mock ChatToolService chatToolService;
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void chat_检索经self代理路由_而非私有自调用() {
        // self 注入为 mock：证明 chat() 调的是 self.searchKnowledge（经代理 REQUIRES_NEW 才生效），
        // 而非绕过代理的私有自调用。空命中 + allowNoKnowledge=false → 走兜底分支返回，不触达 LLM。
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(false).fallbackMessage("请稍等").build());

        chatService.chat(ChatRequest.builder().message("你好").build());

        verify(selfMock).searchKnowledge(eq("你好"), any(ChatStrategy.class));
    }

    @Test
    void 二参检索方法_public且带REQUIRES_NEW只读事务注解() throws Exception {
        Method m = ChatServiceImpl.class.getDeclaredMethod(
                "searchKnowledge", String.class, ChatStrategy.class);
        assertThat(Modifier.isPublic(m.getModifiers()))
                .as("2 参 searchKnowledge 须为 public，代理事务才能拦截自注入调用").isTrue();
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).as("2 参 searchKnowledge 须带 @Transactional").isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(tx.readOnly()).isTrue();
    }
}
```

- [ ] **Step 2：运行新单测，确认失败**

Run: `cd backend && mvn -q test -Dtest=ChatServiceRetrievalTxTest`
Expected: FAIL。`chat_检索经self代理路由` 因 `chat()` 仍直调私有方法（`selfMock.searchKnowledge` 从未被调用）而 `verify` 失败或 `setSelf` 方法不存在编译失败；`二参检索方法_public且带...` 因方法当前为 private 且无 `@Transactional` 而断言失败。

- [ ] **Step 3：改 `ChatServiceImpl`——加 `Propagation` 导入**

在 `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java` 的
`import org.springframework.transaction.annotation.Transactional;` 之后新增一行：

```java
import org.springframework.transaction.annotation.Propagation;
```

- [ ] **Step 4：改 `ChatServiceImpl`——自注入 `self` 字段 + `@Lazy` setter**

在类字段块末尾（`private final ChatToolService chatToolService;` 之后、`@Override public ChatResponse chat(...)` 之前）插入：

```java

    // 自注入解决 @Transactional 同类自调用不经代理不生效问题（仿 KnowledgeServiceImpl.self）
    private ChatServiceImpl self;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    public void setSelf(ChatServiceImpl self) {
        this.self = self;
    }
```

说明：`self` 为**非 final** 字段，不会进入 Lombok `@RequiredArgsConstructor` 生成的构造器，避免构造期自引用循环；由 setter + `@Lazy` 注入代理自身。

- [ ] **Step 5：改 `ChatServiceImpl`——`chat()` 的检索调用改经 self 路由**

将 `chat()` 内（约 130 行）：

```java
        // 检索相关知识（是否走混合检索由 strategy.hybridEnabled 决定）
        List<Document> relevantDocs = searchKnowledge(request.getMessage(), strategy);
```

改为：

```java
        // 检索相关知识（是否走混合检索由 strategy.hybridEnabled 决定）；
        // 经 self 走代理 → searchKnowledge 的 REQUIRES_NEW 只读事务生效，检索 SQL 失败不毒化本写事务
        List<Document> relevantDocs = self.searchKnowledge(request.getMessage(), strategy);
```

- [ ] **Step 6：改 `ChatServiceImpl`——`streamChat()` 的检索调用改经 self 路由**

将 3 参 `streamChat(...)` 内（约 226 行）：

```java
        ChatStrategy strategy = resolveStrategy(session);
        List<Document> relevantDocs = searchKnowledge(request.getMessage(), strategy);
```

改为：

```java
        ChatStrategy strategy = resolveStrategy(session);
        // 同 chat()：经 self 走代理，检索在独立只读事务上执行
        List<Document> relevantDocs = self.searchKnowledge(request.getMessage(), strategy);
```

- [ ] **Step 7：改 `ChatServiceImpl`——2 参 `searchKnowledge` 改 public + REQUIRES_NEW 注解**

将（约 529 行）：

```java
    private List<Document> searchKnowledge(String query, ChatStrategy strategy) {
```

改为（保留其上方原 javadoc，并在方法上追加事务注解与说明）：

```java
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Document> searchKnowledge(String query, ChatStrategy strategy) {
```

同时在该方法上方 javadoc 末尾补一句（紧接原有「整体 fail-soft…」句后）：

```
     * <p>事务隔离：经 chat()/streamChat() 的 self 代理调用时以 REQUIRES_NEW 只读新事务执行——
     * 检索 SQL（关键词全文/向量）失败只毒化该独立连接，chat 写事务连接不受影响，真正 fail-soft。
     * 公开入口 searchKnowledge(String) 内部对本方法保持直调（自调用不经代理、注解失效），
     * 以保住检索类 IT 的同事务播种可见性——切勿改成 self.searchKnowledge。
```

> 注意：`searchKnowledge(String)`（约 517–521 行、`@Override`）内部的 `return searchKnowledge(query, strategy);` **保持不变**（直调，不加 `self.`）。

- [ ] **Step 8：改 `ChatServiceSessionOwnershipTest`——`@BeforeEach` 补 `setSelf` 防回归 NPE**

在 `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceSessionOwnershipTest.java`：

新增 import：

```java
import org.junit.jupiter.api.BeforeEach;
```

在 `@InjectMocks ChatServiceImpl chatService;` 之后、`@AfterEach void clear()` 之前插入：

```java
    @BeforeEach
    void wireSelf() {
        // chat() 现经 self.searchKnowledge 路由；单测无 Spring，self 需手动指向本实例防 NPE
        chatService.setSelf(chatService);
    }
```

- [ ] **Step 9：运行相关单测，确认通过**

Run: `cd backend && mvn -q test -Dtest=ChatServiceRetrievalTxTest,ChatServiceSessionOwnershipTest,ChatServiceSearchScopeTest`
Expected: PASS（`ChatServiceSearchScopeTest` 走 `searchKnowledge(String)` 直调路径、不触达 `self`，无需改动即应绿）。

- [ ] **Step 10：跑全量单测，确认无回归**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS，全部单测通过（Surefire 跳过 `*IT`）。

- [ ] **Step 11：提交**

```bash
cd backend && git add src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java \
  src/test/java/com/qingyu/care/service/impl/ChatServiceRetrievalTxTest.java \
  src/test/java/com/qingyu/care/service/impl/ChatServiceSessionOwnershipTest.java
git commit -m "fix: 检索走 REQUIRES_NEW 只读事务隔离，检索 SQL 失败不再毒化 chat 写事务

ChatServiceImpl 自注入 self；2 参 searchKnowledge 改 public + REQUIRES_NEW；
chat()/streamChat() 经 self 路由；1 参入口保持直调保 IT 同事务可见性。"
```

---

### Task 2: 事务隔离机制 IT（REQUIRES_NEW 探针，真库验证不变式）

**Files:**
- Create/Test: `backend/src/test/java/com/qingyu/care/it/RetrievalTxIsolationIT.java`

**Interfaces:**
- Consumes：Spring 容器内的 `PlatformTransactionManager`、`JdbcTemplate`（`AbstractPostgresIT` 的共享上下文已具备）。不依赖 Task 1 的任何新符号——本 IT 验证的是修复所依赖的 Spring/PG 事务隔离不变式，独立成立。

- [ ] **Step 1：写机制级 IT（正例 + 反例）**

新建 `backend/src/test/java/com/qingyu/care/it/RetrievalTxIsolationIT.java`：

```java
package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 机制级验证：检索失败事务隔离所依赖的 Spring/PG 不变式。
 *
 * <p>用 SELECT 1/0（PG division_by_zero，SQLSTATE 22012）确定性地触发一次「会毒化事务」的 SQL 失败，
 * 镜像 ChatServiceImpl.searchKnowledge 中关键词/向量检索 SQL 失败的效果（且吞异常做 fail-soft）：
 * <ul>
 *   <li>正例：失败 SQL 跑在 REQUIRES_NEW 只读新事务里 → 只毒化该独立连接，外层事务连接干净、后续查询正常；</li>
 *   <li>反例：失败 SQL 跑在外层同一事务里（吞异常也救不回）→ 外层连接被毒化，后续查询报
 *       「current transaction is aborted」——坐实「隔离是必需的」。</li>
 * </ul>
 *
 * <p>不经 chat() 全链路：关键词 SQL 用 plainto_tsquery（任意输入不报错）、真实 SQL 失败只源于
 * schema 级（content_tsv 缺失，共享容器里恒存在）无法自然复现，且 chat() 端到端受 LLM 依赖阻塞
 * （沿用既定 LLM-IT 跳过取舍）。故以本不变式验证替代，chat() 完整链路 fail-soft 待真机 re-smoke 复验。
 *
 * @author ruan
 */
@Transactional
class RetrievalTxIsolationIT extends AbstractPostgresIT {

    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 独立只读新事务里的检索SQL失败_不毒化外层事务() {
        TransactionTemplate inner = new TransactionTemplate(txManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        inner.setReadOnly(true);
        inner.executeWithoutResult(status -> {
            try {
                jdbcTemplate.queryForObject("SELECT 1/0", Integer.class);
            } catch (Exception e) {
                // fail-soft：镜像 searchKnowledge 顶层 try/catch 吞异常返回空
            }
        });

        // 外层（类级 @Transactional）连接未被毒化，仍可正常查询
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void 反例_同事务里检索SQL失败_毒化外层_证明隔离是必需的() {
        try {
            jdbcTemplate.queryForObject("SELECT 1/0", Integer.class);
        } catch (Exception e) {
            // 即便吞掉异常，外层事务连接已在 PG 端被置为 aborted
        }

        assertThatThrownBy(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("current transaction is aborted");
    }
}
```

- [ ] **Step 2：运行本 IT，确认通过**

Run: `cd backend && mvn -q verify -Dit.test=RetrievalTxIsolationIT`
Expected: PASS（需 Docker Desktop；Failsafe 用 `it.test` 而非 `test`）。两个用例均绿——正例证明 REQUIRES_NEW 隔离有效，反例证明不隔离即毒化。

- [ ] **Step 3：提交**

```bash
cd backend && git add src/test/java/com/qingyu/care/it/RetrievalTxIsolationIT.java
git commit -m "test: 补检索事务隔离机制 IT（REQUIRES_NEW 正例不毒化 + 同事务反例毒化）"
```

---

## 收尾（全部任务后）

- [ ] 跑全量回归：`cd backend && mvn -q verify`（单测 + 全部 IT，需 Docker Desktop），确认无回归——重点关注 `HybridRetrievalIT`/`DocVisibilityRetrievalIT`/`MultiKbRetrievalIT` 仍绿（验证 1 参入口未被误路由）。
- [ ] 广域终审（whole-branch review）后，用 `superpowers:finishing-a-development-branch` 合并到 master。
- [ ] 留债登记：`chat()` 全链路 fail-soft 无端到端 IT（LLM 依赖阻塞），待 DashScope 额度恢复真机 re-smoke 复验。

## Self-Review

**1. Spec coverage：** spec §2 方案（REQUIRES_NEW 只读）→ Task 1 Step 7；§3 自注入/两处路由/1 参不改 → Task 1 Step 4–7 + Global Constraints；§4 路由单测 + 注解守卫 + 回归 setSelf → Task 1 Step 1/8；§4 机制 IT 正例+反例 → Task 2；§4 已知局限 → 收尾留债。无遗漏。

**2. Placeholder scan：** 无 TBD/TODO/“类似上文”；每个改码步骤均含完整代码与确切命令。

**3. Type consistency：** `setSelf(ChatServiceImpl)`、`searchKnowledge(String, ChatStrategy)`、`Propagation.REQUIRES_NEW`、`readOnly=true`、`TransactionDefinition.PROPAGATION_REQUIRES_NEW` 前后一致；单测 mock 集合与 `ChatServiceImpl` 构造依赖（含 `ConfigService`/`ChatToolService`/`TransactionTemplate`）对齐。

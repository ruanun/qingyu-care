# 坐席消息 role 修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `agentReply` 落库写 `MessageRole.AGENT`，使坐席消息与 AI 消息在 `role` 上真正可区分，修掉「访客刷新后坐席说的话显示成 AI 气泡」。

**Architecture:** 根因只有一行（落库的枚举值），但它连带三处：LLM 历史上下文要新增 `AGENT` 分支（否则退回 AI 后 AI 看不到坐席说过的话）、坐席台已读判据与其乐观渲染要同步改（两处都硬编码了 role）、存量数据要按精确判据迁移。widget 与 13 处分析 SQL 经核查不需要改。

**Tech Stack:** Spring Boot 4.1 / Spring AI 2.0 / JDK 17 / Maven；React 19 + vitest；PostgreSQL。

**Spec:** `docs/superpowers/specs/2026-08-20-agent-message-role-design.md`

## Global Constraints

- 分支 `fix/agent-message-role`（已创建）。允许 `git commit`，**不要 `git push`**。
- 对话、代码注释、commit 信息一律简体中文。新增文件的 author 写 `ruan`。
- 后端单测必须是纯 Mockito（`@ExtendWith(MockitoExtension.class)`），**不启 Spring 容器、不需要 Docker**。
- 前端 `frontend/` **故意不开 `globals: true`**：测试文件必须显式 `import { describe, it, expect, vi } from 'vitest'`。
- `widget/src/**` 一个字节都不要改。改了就必须重新 `pnpm build` 并提交 `widget.js`，而本次不需要。
- 迁移脚本放 `backend/src/main/resources/migration/`，命名 `YYYY-MM-DD-<主题>.sql`，必须幂等。

---

### Task 1: 后端落库 role 与 LLM 历史上下文

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java:1122`（`buildMessages` 改 package-private）、`:1142`（加 `AGENT` 分支）、`:1415`（落库改 `AGENT`）
- Modify: `backend/src/test/java/com/qingyu/care/service/impl/AgentNotifyEventTest.java:134`
- Modify: `backend/src/test/java/com/qingyu/care/service/ChatFeedbackServiceTest.java`
- Create: `backend/src/test/java/com/qingyu/care/service/impl/ChatAgentMessageRoleTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: `ChatMessage.role` 对坐席消息恒为 `MessageRole.AGENT`。Task 2 的前端判据、Task 3 的迁移判据都依赖这一点。`buildMessages` 的可见性从 `private` 变为 package-private，签名不变：
  `List<Message> buildMessages(ChatSession session, String userMessage, String context, ChatStrategy strategy, java.util.List<String> skillMarkdowns, boolean withCards)`

- [ ] **Step 1: 写失败测试**

新建 `backend/src/test/java/com/qingyu/care/service/impl/ChatAgentMessageRoleTest.java`：

```java
package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.UiSpecService;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 坐席消息的 role 语义单测。
 *
 * <p>守的是一个真实缺陷：agentReply 长期落库写 ASSISTANT，而 MessageRole.AGENT 从未被写入，
 * 导致访客刷新页面后坐席说过的话被 widget 的历史恢复分支当成 AI 回复渲染
 * （widget/src/history.ts 按 role 分派）。详见
 * docs/superpowers/specs/2026-08-20-agent-message-role-design.md
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatAgentMessageRoleTest {

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
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock UiSpecService uiSpecService;
    @Mock ObjectMapper objectMapper;
    @Mock ChatAttachmentService chatAttachmentService;
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void agentReply_落库role为AGENT而非ASSISTANT() {
        ChatSession session = ChatSession.builder().sessionId("sess-1").build();
        session.setTenantId(2L);
        session.setAppId(5L);
        session.setStatus(SessionStatus.AGENT_PROCESSING);
        session.setAgentId(10L);
        when(sessionRepository.findBySessionId("sess-1")).thenReturn(Optional.of(session));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage msg = inv.getArgument(0);
            msg.setId(99L);
            return msg;
        });

        chatService.agentReply("sess-1", "您好，我是人工客服", 10L);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(MessageRole.AGENT);
    }

    /**
     * ⚠️ 别删这条，也别把 buildMessages 里的 AGENT 分支当成冗余代码「清理」掉。
     * 少了那个分支，坐席消息会从 LLM 历史里整段消失——会话从人工退回 AI 之后，
     * AI 看不到坐席刚说过的话，会重复提问或给出与坐席矛盾的答复。
     */
    @Test
    void buildMessages_历史里的AGENT消息也进LLM上下文() {
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        // findTop20...OrderByCreatedAtDesc 返回的是倒序（最新在前），buildMessages 从后往前遍历
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of(
                ChatMessage.builder().role(MessageRole.AGENT).content("已为您登记退款申请").build(),
                ChatMessage.builder().role(MessageRole.USER).content("帮我登记一下").build()));

        List<Message> messages = chatService.buildMessages(
                ChatSession.builder().sessionId("sess-1").build(),
                "现在到哪一步了",
                "暂无相关知识库内容。",
                ChatStrategy.builder().name("默认").scenario("default").build(),
                List.of(),
                false);

        assertThat(messages).filteredOn(AssistantMessage.class::isInstance)
                .extracting(m -> ((AssistantMessage) m).getText())
                .contains("已为您登记退款申请");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=ChatAgentMessageRoleTest
```

预期两条都 FAIL：第一条断言 `AGENT` 但实际拿到 `ASSISTANT`；第二条**编译失败**（`buildMessages` 此刻还是 `private`）。编译失败也是有效的红——它证明这条测试确实在碰那个方法。

- [ ] **Step 3: 改 ChatServiceImpl 三处**

`:1415`（`agentReply` 里构建坐席消息），把 `ASSISTANT` 改成 `AGENT`：

```java
        // 保存人工客服消息。role 必须是 AGENT 而非 ASSISTANT：widget 的历史恢复按 role 分派
        // （history.ts），写成 ASSISTANT 会让访客刷新后把坐席说的话显示成 AI 气泡。
        ChatMessage agentMsg = ChatMessage.builder()
                .session(session)
                .role(MessageRole.AGENT)
                .content(message)
                .build();
```

`:1122`，去掉 `private`（同 `resolveStrategy` / `resolveModelScenario` 的既有做法，为可测性留 package-private）：

```java
    /** package-private 供单测直接构造历史上下文断言，见 ChatAgentMessageRoleTest。 */
    List<Message> buildMessages(ChatSession session, String userMessage, String context,
                                ChatStrategy strategy, java.util.List<String> skillMarkdowns,
                                boolean withCards) {
```

`:1142`，历史遍历里补 `AGENT` 分支：

```java
            if (msg.getRole() == MessageRole.USER) {
                messages.add(new UserMessage(msg.getContent()));
            } else if (msg.getRole() == MessageRole.ASSISTANT || msg.getRole() == MessageRole.AGENT) {
                // AGENT（真人坐席）对 LLM 而言同样是助手方发言：会话退回 AI 后，
                // AI 必须看得到坐席刚说过的话，否则会重复提问或与坐席自相矛盾。
                messages.add(new AssistantMessage(msg.getContent()));
            }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=ChatAgentMessageRoleTest
```

预期 PASS（2 条）。

- [ ] **Step 5: 更新既有断言 AgentNotifyEventTest**

`AgentNotifyEventTest.java:134` 现在断言推给用户的消息 role 是 `ASSISTANT`，改成：

```java
        assertThat(pushed.getRole()).isEqualTo(MessageRole.AGENT);
```

跑：

```bash
cd backend && mvn test -Dtest=AgentNotifyEventTest
```

预期 PASS。（这条断言此前会因 Step 3 转红——它是既有守护，不是新写的。）

- [ ] **Step 6: 补 ChatFeedbackServiceTest 的语义用例**

在 `ChatFeedbackServiceTest` 的 `用户消息不可评价` 用例后面追加：

```java
    /**
     * 坐席消息不可评价。此前坐席消息的 role 落成 ASSISTANT，能被当作 AI 回复点赞/点踩，
     * 反馈数据会混进 AI 质量指标；role 修正为 AGENT 之后这条校验自然成立，用例把它钉住。
     */
    @Test
    void 坐席消息不可评价() {
        ChatSession session = ChatSession.builder().sessionId("s-1").build();
        ChatMessage agentMsg = ChatMessage.builder().id(12L).session(session)
                .role(MessageRole.AGENT).content("我是人工客服").build();
        when(messageRepository.findById(12L)).thenReturn(Optional.of(agentMsg));
        assertThatThrownBy(() -> service.submitFeedback("s-1", 12L, 1))
                .isInstanceOf(BusinessException.class);
    }
```

`ChatFeedbackService` 的代码**不需要改**（第 55 行 `!= MessageRole.ASSISTANT` 已经能拦住 `AGENT`）。

- [ ] **Step 7: 跑全量后端单测**

```bash
cd backend && mvn test
```

预期 BUILD SUCCESS。⚠️ 若有其它测试转红，八成是它也硬编码了坐席消息的 role——逐个看断言意图再改，**不要**为了让测试变绿把产品代码改回 `ASSISTANT`。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java \
        backend/src/test/java/com/qingyu/care/service/impl/ChatAgentMessageRoleTest.java \
        backend/src/test/java/com/qingyu/care/service/impl/AgentNotifyEventTest.java \
        backend/src/test/java/com/qingyu/care/service/ChatFeedbackServiceTest.java
git commit -m "fix(chat): 坐席消息落库 role 改为 AGENT，并让它进入 LLM 历史上下文

agentReply 长期写 ASSISTANT，MessageRole.AGENT 从未被写入，导致访客刷新后
坐席说过的话被 widget 历史恢复分支当成 AI 气泡渲染。

buildMessages 同步加 AGENT 分支：少了它，会话退回 AI 后 AI 看不到坐席
刚说过的话。buildMessages 改 package-private 供单测直接断言历史上下文。"
```

---

### Task 2: 坐席台已读判据与乐观渲染

**Files:**
- Modify: `frontend/src/pages/AgentPage.tsx:341`（乐观渲染的 role）、`:393-403`（注释）、`:410`（已读判据）
- Modify: `frontend/src/pages/AgentPage.test.tsx`

**Interfaces:**
- Consumes: Task 1 的 `MessageRole.AGENT`——后端返回的坐席消息 role 已是 `AGENT`
- Produces: 无下游任务依赖

- [ ] **Step 1: 写失败测试**

在 `AgentPage.test.tsx` 最后一个 `it(...)` 之后、`});` 之前追加两条。

第一条把既有 fixture 的 role 换成 `AGENT`（守后端返回值这条路径）：

```tsx
    it('历史里的 AGENT 消息能被标已读', async () => {
        stubVisibility('hidden'); // 与上报动作解耦：恢复逻辑读的是历史响应
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({
            messages: [
                { id: 50, role: 'AGENT', content: '您好，有什么可以帮您', createdAt: '2026-08-20T10:00:00' },
            ],
            visitorReadUpToMessageId: 50,
        }));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));

        expect(await screen.findByTestId('read-mark-50')).toBeInTheDocument();
    });
```

第二条守**接缝**——本地乐观插入的那条消息 role 是前端自己硬编码的，不来自后端：

```tsx
    it('坐席刚发出的消息（本地乐观渲染）也能被标已读', async () => {
        // ⚠️ 这条守的是 AgentPage 里硬编码的那个 role：它不来自后端响应，
        // 判据改成 AGENT 后若这里仍写 ASSISTANT，坐席自己刚发的消息匹配不上判据，
        // 已读标记要等整页刷新（重新从后端拿到真实 role）才出现。
        stubVisibility('visible');
        stubs.getMySessions.mockResolvedValue(ok([mySession('s1', 0)]));
        stubs.getSessionMessages.mockResolvedValue(ok({ messages: [], visitorReadUpToMessageId: null }));
        stubs.agentReply.mockResolvedValue(ok(77));

        render(<AgentPage />);
        fireEvent.click(await screen.findByTestId('my-session-s1'));

        const input = await screen.findByPlaceholderText('输入回复内容...');
        fireEvent.change(input, { target: { value: '这就为您处理' } });
        fireEvent.keyDown(input, { key: 'Enter' });

        await screen.findByText('这就为您处理');

        await act(async () => {
            sse.onEvent?.('read', JSON.stringify({ sessionId: 's1', upToMessageId: 77 }));
        });

        expect(await screen.findByTestId('read-mark-77')).toBeInTheDocument();
    });
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm test -- AgentPage
```

预期两条都 FAIL（找不到 `read-mark-50` / `read-mark-77`——判据此刻还是 `ASSISTANT`）。

- [ ] **Step 3: 改判据与乐观渲染**

`AgentPage.tsx:341`，乐观插入的 role：

```tsx
                role: 'AGENT',
```

`AgentPage.tsx:393-403` 那整段注释替换为：

```tsx
    // 判据是 role === 'AGENT'：坐席消息落库写的就是 MessageRole.AGENT
    // （ChatServiceImpl#agentReply）。⚠️ 上面本地乐观插入的那条消息 role 是前端硬编码的，
    // 必须与这里保持一致，否则坐席自己刚发的消息标不上已读、要刷新才对——
    // 见 docs/superpowers/specs/2026-08-20-agent-message-role-design.md §4.2。
    // 历史沿革：本判据曾经是 'ASSISTANT'，因为那时 agentReply 落库写的是 ASSISTANT、
    // MessageRole.AGENT 从未被写入。该根因已于 2026-08-20 修复。
```

`AgentPage.tsx:410`：

```tsx
        (acc, m) => (m.role === 'AGENT' && !m.pending && m.id <= visitorReadUpTo ? m.id : acc),
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm test -- AgentPage
```

预期全部 PASS。

- [ ] **Step 5: 跑全量前端测试与 lint**

```bash
cd frontend && pnpm test && pnpm lint
```

预期测试全绿；lint 的既有 error 基线**零新增**。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/pages/AgentPage.tsx frontend/src/pages/AgentPage.test.tsx
git commit -m "fix(agent): 坐席台已读判据改用 AGENT，含本地乐观渲染那条接缝

判据与乐观插入的 role 各写一次、分处两地，只改判据会让坐席自己刚发的
消息标不上已读且要刷新才对。补两条测试分别守后端返回值与本地插入两条路径。"
```

---

### Task 3: 存量数据迁移

**Files:**
- Create: `backend/src/main/resources/migration/2026-08-20-fix-agent-message-role.sql`

**Interfaces:**
- Consumes: Task 1 确立的「坐席消息 role = AGENT」语义
- Produces: 无下游任务依赖

- [ ] **Step 1: 写迁移脚本**

```sql
-- 把存量坐席消息的 role 从 ASSISTANT 修正为 AGENT。
--
-- 判据 confidence_score IS NULL 是精确的、不是启发式：写入 role='ASSISTANT' 的路径
-- 只有三条，且已逐一核实——
--   ChatServiceImpl.saveMessages（AI 回复）：参数是基本类型 double，语法上不可能为 null
--   DemoDataSeeder（演示数据）：显式 .confidenceScore(confidence)
--   ChatServiceImpl.agentReply（坐席回复）：从不设置 → null
-- 详见 docs/superpowers/specs/2026-08-20-agent-message-role-design.md §6.1
--
-- 幂等：改完之后这些行的 role 已是 AGENT，WHERE 不再命中，可重复执行。
--
-- ⚠️ 执行前先单独跑一次下面的 ① 人工核对内容确实是坐席说的话。脚本里保留 ① 是为了
-- 让执行时的输出成为审计记录。
--
-- 一次性手动执行（项目未接入 Flyway/Liquibase）：
--   docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
--     < backend/src/main/resources/migration/2026-08-20-fix-agent-message-role.sql

-- ① 待修正的行
SELECT id, session_id, created_at, left(content, 40) AS content_preview
FROM chat_message
WHERE role = 'ASSISTANT' AND confidence_score IS NULL
ORDER BY id;

-- ② 修正
UPDATE chat_message
SET role = 'AGENT'
WHERE role = 'ASSISTANT' AND confidence_score IS NULL;
```

- [ ] **Step 2: 在本机 dev 库先只跑核对查询**

```bash
docker exec qingyu-postgres psql -U postgres -d qingyu_care -c "
SELECT id, session_id, created_at, left(content, 40) AS content_preview
FROM chat_message WHERE role = 'ASSISTANT' AND confidence_score IS NULL ORDER BY id;"
```

**人工读一遍 content_preview**，确认每一条都是坐席说的话（客服口吻），不是 AI 回复。若出现明显是 AI 生成的长文，**停下**——判据在这个库里不成立，需要重新分析后再动。

- [ ] **Step 3: 执行迁移**

```bash
cd /d/Code/m/qingyu-care && docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
  < backend/src/main/resources/migration/2026-08-20-fix-agent-message-role.sql
```

- [ ] **Step 4: 验证结果与幂等**

```bash
docker exec qingyu-postgres psql -U postgres -d qingyu_care -c "
SELECT role, count(*) FROM chat_message GROUP BY role ORDER BY role;"
```

预期出现 `AGENT` 分组。再把 Step 3 原样重跑一次，预期 `UPDATE 0`。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/migration/2026-08-20-fix-agent-message-role.sql
git commit -m "chore(db): 存量坐席消息 role 修正为 AGENT 的迁移脚本

判据 confidence_score IS NULL 精确等价于坐席消息（三条写入路径已逐一核实）。
脚本幂等，每套部署各跑一次；不跑的后果是该库历史坐席消息继续显示成 AI 气泡。"
```

---

### Task 4: 文档同步

**Files:**
- Modify: `docs/superpowers/specs/2026-08-19-message-read-receipt-design.md`（§2.3 实施订正段、§9 首条）
- Modify: `CLAUDE.md`（「改动已读回执链路后必须手工验证」一节）

**Interfaces:**
- Consumes: Task 1、Task 2 的最终判据
- Produces: 无

- [ ] **Step 1: 反转已读回执 spec 的 §2.3**

把 §2.3 里那段以「⚠️ **实施订正：「坐席消息」的判据是 `role === 'ASSISTANT'`...**」开头、到「...是一个先于本特性存在、且本特性没有修的既有问题。」结束的整段，替换为：

```markdown
⚠️ **「坐席消息」的判据是 `role === 'AGENT'`**。本特性实施时它曾经是 `'ASSISTANT'`——
那时 `agentReply` 落库写的就是 `MessageRole.ASSISTANT`，`MessageRole.AGENT` 从未被写入，
判据只能将错就错。该根因已于 2026-08-20 单独立项修复，见
`2026-08-20-agent-message-role-design.md`，本节连同 §9 的记录一并订正。
```

- [ ] **Step 2: 把 §9 首条改成已修复并留指针**

把 §9 里那条以「**既有缺陷（非本特性引入，实施中发现）：坐席消息与 AI 消息在 `ChatMessage.role` 上不可区分**」开头的整条，替换为：

```markdown
- **~~既有缺陷：坐席消息与 AI 消息在 `ChatMessage.role` 上不可区分~~（已于 2026-08-20 修复）**。
  `agentReply` 曾落库写 `ASSISTANT` 而 `MessageRole.AGENT` 从未被写入，后果是访客刷新页面后
  坐席说过的话显示成 AI 气泡。本特性的已读判据（§2.3、§6.6）当时依赖这个不区分性才能成立，
  但那只是绕过了它。修复设计见 `2026-08-20-agent-message-role-design.md`，连带改了三处：
  LLM 历史上下文新增 `AGENT` 分支、坐席台已读判据与其乐观渲染、存量数据迁移。
  ⚠️ **保留这条记录而不是删掉**：不然下一个人会重新踩「为什么已读判据要用 ASSISTANT」这个坑。
```

- [ ] **Step 3: 更新 CLAUDE.md**

在「### 改动已读回执链路后必须手工验证」小节的七条清单**之后**追加一段：

```markdown
⚠️ **B 侧「坐席消息」的判据是 `role === 'AGENT'`**（`AgentPage.tsx` 与 widget 的 `history.ts` 各一份）。
这个判据分散在两个地方，且坐席台里还有第三处——本地乐观插入消息时硬编码的 role，
它不来自后端响应。三处必须一致，只改其中之一的症状是「坐席自己刚发的消息标不上已读、
刷新一下又对了」。历史沿革见 `docs/superpowers/specs/2026-08-20-agent-message-role-design.md`。
```

- [ ] **Step 4: 校验点名脚本仍然自洽**

```bash
./scripts/manual-smoke-hints.sh master...HEAD
```

预期 exit 0。⚠️ 该脚本在引用的章节标题找不到时会 exit 1——本任务没有改标题，若报错说明改错了地方。

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-08-19-message-read-receipt-design.md CLAUDE.md
git commit -m "docs: 反转已读回执 spec 与 CLAUDE.md 里「判据用 ASSISTANT」的记载

根因已修，判据改为 AGENT。§9 那条既有缺陷记录改成「已修复 + 指针」而非删除：
它是墓碑，删掉后下一个人会重新踩「为什么判据要用 ASSISTANT」这个坑。"
```

---

### Task 5: 全量验证与真机复验

**Files:** 无（只跑验证）

**Interfaces:**
- Consumes: Task 1-4 全部改动
- Produces: 无

- [ ] **Step 1: 后端全量单测**

```bash
cd backend && mvn test
```

预期 BUILD SUCCESS。

- [ ] **Step 2: 前端与 widget 测试**

```bash
cd frontend && pnpm test
cd ../widget && pnpm test
```

两边都预期全绿。widget 源码未改，`buildFreshness` 哨兵应保持绿——**若它转红说明误改了 `widget/src/`**，回退那处改动，不要去重新 build。

- [ ] **Step 3: 重启后端加载新代码**

停掉在跑的 dev 后端，重新启动：

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo
```

- [ ] **Step 4: 真机复验——访客刷新后坐席消息显示为客服气泡**

1. 访客侧打开挂了 widget 的宿主页，展开面板，发一条消息，点「转人工」。
2. 坐席台（`demo_agent` / `demo123456`）进「人工工作台」，接管该会话，回一条。
3. 访客侧**整页刷新**，重新展开面板。
4. 断言：那条坐席消息带**「客服」标识**（`.qy-msg` 行内有「客服」小标签），而不是与 AI 回复长得一模一样。

在访客页控制台跑这段可直接读出结论：

```js
(() => {
  const sr = Array.from(document.querySelectorAll('*')).find(e => e.shadowRoot).shadowRoot;
  return Array.from(sr.querySelectorAll('.qy-msg')).map(r => ({
    cls: r.className, text: r.textContent.replace(/\s+/g, ' ').slice(0, 30),
  }));
})()
```

修复前的对照证据（同一批坐席消息刷新前后不一致）记在
`docs/superpowers/specs/2026-08-20-agent-message-role-design.md` §1。

- [ ] **Step 5: 真机复验——已读标记不需要刷新就落在坐席自己刚发的消息上**

坐席再发一条，访客侧保持面板展开且标签页在前台。坐席端**不刷新**，那条新消息下方应出现「已读」。
这条守 Task 2 的接缝：判据与乐观渲染 role 不一致时，现象正是「要刷新一下才出现」。

- [ ] **Step 6: 真机复验——退回 AI 后 AI 看得到坐席说过的话**

在坐席台点「回归 AI」，然后访客侧问一句与坐席刚才说过的内容相关的问题
（例如坐席说过「已为您登记退款申请」，就问「我的退款登记好了吗」）。
AI 的回复应当体现出它知道这件事，而不是从头重新问一遍。
这条守 `buildMessages` 的 `AGENT` 分支——**没有任何自动化测试能覆盖真实 LLM 的上下文理解**。

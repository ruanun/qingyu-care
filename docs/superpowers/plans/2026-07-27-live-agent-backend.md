# 真人坐席 Live Agent（后端）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在既有坐席骨架上打通后端双向实时通道（SSE 下行 + POST 上行）、收口坐席身份、对齐 streamChat 的转人工/AI 静默，并补齐回归 AI / presence / typing / 事件——产出独立可测可合的后端。

**Architecture:** 新增内存 `RealtimeHub`（响应式 `Sinks.Many` 注册表，沿用仓库既有 `Flux<ServerSentEvent<String>>` SSE 风格）做传输层；`RealtimeNotifier` 做领域发布（构造事件 JSON 调 hub）。用户端每会话一条 inbox SSE（走开放 API 鉴权），坐席端每人一条 stream SSE（Sa-Token 登录 + 坐席角色门禁）。业务动作（accept/agentReply/用户消息/returnToAI/typing）落库后经 notifier 尽力推送——**SSE 只做加速分发，DB 消息为事实源**。

**Tech Stack:** Spring Boot 3 MVC + Reactor（`Sinks.Many`/`Flux<ServerSentEvent>`）、Sa-Token（登录+角色）、JUnit5 + Mockito + reactor-test（`StepVerifier`）单测、Testcontainers pgvector IT。

## Global Constraints

- 全部简体中文：代码注释、commit 信息、文档。作者署名 `ruan`。
- 本项目允许 `git commit`，且仅在特性分支 `feat/live-agent` 上提交；未获授权**不 push**。
- 单元测试纯 Mockito（`@ExtendWith(MockitoExtension.class)`，不启 Spring、不需 Docker）；集成测试类名以 `IT` 结尾、继承 `com.qingyu.care.support.AbstractPostgresIT`（需 Docker Desktop）。IT 播种走真实 HTTP（MockMvc）或用不继承 `BaseTenantEntity` 的实体。
- 用户 inbox 端点在 `/open/v1/**` 下（经 `OpenApiAuthInterceptor` 鉴权，`SaTokenConfig` 已放行）；坐席端点在 `/v1/chat/**` 下，需登录 + **坐席角色门禁**（`role ∈ {TENANT_ADMIN, TENANT_MEMBER}`）。
- `agentId` 一律取 `StpUtil.getLoginIdAsLong()`，**不信任请求入参**。
- **SSE 是加速分发、DB 消息是事实源**：推送 best-effort、失败即摘除 emitter、**绝不影响业务落库**；无订阅者时丢弃。
- 单实例内存注册表（多实例 Redis 广播留后续）。
- 本计划**只做后端**；前端坐席台实时化 + widget inbox 渲染 + 重建 `widget.js` 属后续 Plan 2。
- 参考既有范式：响应式 SSE 见 `controller/open/OpenChatController.java:67-84`（`Flux<ServerSentEvent<String>>` + `Sinks`）；角色守卫见 `util/SecurityUtil.java`；webhook 事件发布见 `ChatServiceImpl` 现有 `eventPublisher.publishEvent(new WebhookDomainEvent(...))`（6 参：eventType/appId/tenantId/sessionId/trigger/occurredAt）。

---

## 文件结构

**新增**
- `service/realtime/RealtimeHub.java` — SSE 传输层：订阅键 → 事件流注册表（`Sinks.Many`），`subscribe/publish/isOnline/onlineKeys`。
- `service/realtime/RealtimeKeys.java` — 订阅键构造（`session(id)` / `agent(tid,uid)` / `agentPrefix(tid)`），单一来源防拼写漂移。
- `service/realtime/RealtimeNotifier.java` — 领域发布：把 `ChatMessage`/会话状态/typing/presence 构造成事件 JSON 调 `RealtimeHub`。
- `controller/AgentStreamController.java` — 坐席 SSE 流 `GET /v1/chat/agent/stream` + 在线坐席 `GET /v1/chat/agents/online` + typing `POST /v1/chat/session/{id}/typing`（坐席侧）。
- `dto/TypingRequest.java` —（若需要）typing 请求体；MVP 用 path + 无体，省。
- 单测：`RealtimeHubTest`、`RealtimeNotifierTest`、`AgentIdentityBindingTest`、`StreamChatHandoverTest`、`ReturnToAiTest`、`WaitingQueueAppScopeTest`。
- IT：`AgentEndpointAuthIT`、`LiveAgentFlowIT`、`RealtimeSseIT`。

**修改**
- `util/SecurityUtil.java` — 加 `isTenantMember()` + `requireAgent()`。
- `controller/ChatController.java` — accept/agentReply 端点加 `requireAgent()` + `agentId` 取登录态；`/session/{id}/return-to-ai` 新端点。
- `controller/open/OpenSessionController.java` — 加用户 inbox `GET /open/v1/sessions/{id}/inbox` + 用户 typing `POST /open/v1/sessions/{id}/typing`。
- `dto/AgentReplyRequest.java` — 删 `agentId` 字段。
- `service/impl/ChatServiceImpl.java` — 抽取共享转人工/静默逻辑供 `chat()`/`streamChat()` 复用；`acceptSession`/`agentReply` 接 notifier + 新事件；新增 `returnToAI`；`getWaitingAgentSessions` 加 appId 收窄。
- `service/ChatService.java` — 加 `returnToAI(String sessionId)` 接口方法。

---

### Task A1: RealtimeHub 传输层 + RealtimeKeys

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeKeys.java`
- Create: `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeHub.java`
- Test: `backend/src/test/java/com/qingyu/care/service/realtime/RealtimeHubTest.java`

**Interfaces:**
- Produces:
  - `RealtimeKeys.session(String sessionId) -> String`（`"session:"+sessionId`）
  - `RealtimeKeys.agent(Long tenantId, Long agentUserId) -> String`（`"agent:"+tid+":"+uid`）
  - `RealtimeKeys.agentPrefix(Long tenantId) -> String`（`"agent:"+tid+":"`）
  - `RealtimeHub.subscribe(String key) -> Flux<ServerSentEvent<String>>`（含心跳，断连自动清理）
  - `RealtimeHub.publish(String key, String event, String data)`（best-effort，无订阅者丢弃）
  - `RealtimeHub.isOnline(String key) -> boolean`
  - `RealtimeHub.onlineKeys(String prefix) -> java.util.List<String>`

- [ ] **Step 1：确认 reactor-test 依赖可用**

Run: `cd backend && mvn -q dependency:tree -Dincludes=io.projectreactor:reactor-test 2>&1 | grep reactor-test || echo MISSING`
Expected: 打印 `reactor-test`（Spring Boot 起步依赖 `spring-boot-starter-webflux`/`reactor` 通常带 test scope；若 MISSING 则在 `pom.xml` 加 `io.projectreactor:reactor-test`（test scope）后再继续）。

- [ ] **Step 2：写 RealtimeKeys**

`RealtimeKeys.java`：

```java
package com.qingyu.care.service.realtime;

/** 实时订阅键构造（单一来源，防各处手拼漂移）。 */
public final class RealtimeKeys {

    private RealtimeKeys() {}

    /** 用户端每会话一条 inbox 流。 */
    public static String session(String sessionId) {
        return "session:" + sessionId;
    }

    /** 坐席端每人一条流（含租户，隔离 + 便于按租户枚举在线坐席）。 */
    public static String agent(Long tenantId, Long agentUserId) {
        return "agent:" + tenantId + ":" + agentUserId;
    }

    /** 某租户下所有坐席键前缀（枚举在线坐席 / 广播用）。 */
    public static String agentPrefix(Long tenantId) {
        return "agent:" + tenantId + ":";
    }
}
```

- [ ] **Step 3：写 RealtimeHubTest（先失败）**

`RealtimeHubTest.java`（纯 Mockito 无关，直接测 `RealtimeHub`，用 `StepVerifier`）：

```java
package com.qingyu.care.service.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeHubTest {

    @Test
    void 订阅者收到发布的命名事件() {
        RealtimeHub hub = new RealtimeHub();
        Flux<ServerSentEvent<String>> stream = hub.subscribe("k1");

        StepVerifier.create(stream)
                .then(() -> hub.publish("k1", "message", "{\"x\":1}"))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("message");
                    assertThat(sse.data()).isEqualTo("{\"x\":1}");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void 发布到无订阅者的键_安全丢弃不抛异常() {
        RealtimeHub hub = new RealtimeHub();
        hub.publish("nobody", "message", "{}");   // 不应抛
        assertThat(hub.isOnline("nobody")).isFalse();
    }

    @Test
    void 有活跃订阅者时isOnline为真_取消后为假() {
        RealtimeHub hub = new RealtimeHub();
        assertThat(hub.isOnline("k2")).isFalse();
        reactor.core.Disposable d = hub.subscribe("k2").subscribe();
        assertThat(hub.isOnline("k2")).isTrue();
        d.dispose();
        assertThat(hub.isOnline("k2")).isFalse();
    }

    @Test
    void onlineKeys按前缀枚举在线键() {
        RealtimeHub hub = new RealtimeHub();
        reactor.core.Disposable a = hub.subscribe("agent:9:1").subscribe();
        reactor.core.Disposable b = hub.subscribe("agent:9:2").subscribe();
        reactor.core.Disposable c = hub.subscribe("agent:8:1").subscribe();
        assertThat(hub.onlineKeys("agent:9:")).containsExactlyInAnyOrder("agent:9:1", "agent:9:2");
        a.dispose(); b.dispose(); c.dispose();
    }
}
```

- [ ] **Step 4：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=RealtimeHubTest`
Expected: 编译失败（`RealtimeHub` 不存在）。

- [ ] **Step 5：写 RealtimeHub**

`RealtimeHub.java`：

```java
package com.qingyu.care.service.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时 SSE 传输层：订阅键 → 多播事件流。响应式 Sinks 实现，沿用 OpenChatController 的
 * Flux&lt;ServerSentEvent&gt; SSE 风格。best-effort：无订阅者/发送失败均丢弃，不影响业务
 * （DB 消息为事实源，客户端重连后补拉）。单实例内存注册表，多实例广播留后续。
 *
 * @author ruan
 */
@Component
@Slf4j
public class RealtimeHub {

    /** 每订阅键一个多播 Sink + 活跃订阅者计数（计数归零即摘除，兼作 presence）。 */
    private record Channel(Sinks.Many<ServerSentEvent<String>> sink, AtomicInteger subscribers) {}

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    /** 心跳周期：保活长连、探测死连接（注释事件，客户端忽略）。 */
    private static final Duration HEARTBEAT = Duration.ofSeconds(25);

    /**
     * 订阅某键的事件流。多播（同键多标签页共享），断连（cancel/complete）时订阅者计数减一、
     * 归零则摘除该 channel。心跳 merge 进流保活。
     */
    public Flux<ServerSentEvent<String>> subscribe(String key) {
        Channel ch = channels.computeIfAbsent(key,
                k -> new Channel(Sinks.many().multicast().directBestEffort(), new AtomicInteger(0)));
        ch.subscribers().incrementAndGet();
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT, HEARTBEAT)
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
        return Flux.merge(ch.sink().asFlux(), heartbeat)
                .doFinally(sig -> {
                    if (ch.subscribers().decrementAndGet() <= 0) {
                        channels.remove(key, ch);
                    }
                });
    }

    /** 向某键的订阅者尽力推送一条命名事件。无订阅者或发送失败均安全丢弃。 */
    public void publish(String key, String event, String data) {
        Channel ch = channels.get(key);
        if (ch == null) {
            return;
        }
        ServerSentEvent<String> sse = ServerSentEvent.<String>builder(data).event(event).build();
        Sinks.EmitResult r = ch.sink().tryEmitNext(sse);
        if (r.isFailure()) {
            log.debug("实时推送丢弃 key={} event={} result={}", key, event, r);
        }
    }

    /** 该键当前是否有活跃订阅者（presence 判定）。 */
    public boolean isOnline(String key) {
        Channel ch = channels.get(key);
        return ch != null && ch.subscribers().get() > 0;
    }

    /** 枚举当前在线且键以 prefix 开头的订阅键（如某租户在线坐席）。 */
    public List<String> onlineKeys(String prefix) {
        return channels.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && e.getValue().subscribers().get() > 0)
                .map(Map.Entry::getKey)
                .toList();
    }
}
```

- [ ] **Step 6：运行确认通过**

Run: `cd backend && mvn -q test -Dtest=RealtimeHubTest`
Expected: PASS（4/4）。

- [ ] **Step 7：提交**

```bash
cd backend && git add src/main/java/com/qingyu/care/service/realtime/RealtimeKeys.java \
  src/main/java/com/qingyu/care/service/realtime/RealtimeHub.java \
  src/test/java/com/qingyu/care/service/realtime/RealtimeHubTest.java
git commit -m "feat: 实时 SSE 传输层 RealtimeHub（Sinks 多播注册表 + 心跳 + presence 计数）"
```

---

### Task A2: RealtimeNotifier 领域发布

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeNotifier.java`
- Test: `backend/src/test/java/com/qingyu/care/service/realtime/RealtimeNotifierTest.java`

**Interfaces:**
- Consumes: `RealtimeHub`（Task A1）、`RealtimeKeys`、`ChatMessage`（`entity/`）、`ChatSession`。
- Produces（供 ChatServiceImpl / 控制器调用）：
  - `toUserMessage(String sessionId, ChatMessage msg)` — 向用户 inbox 推 `message`
  - `toUserSessionEvent(String sessionId, String kind, String text)` — 向用户推 `session`（kind ∈ accepted/ended/returned_to_ai）
  - `toAgentMessage(Long tenantId, Long agentUserId, ChatMessage msg)` — 向指定坐席推 `message`
  - `toTenantAgents(Long tenantId, String event, String data)` — 广播给本租户所有在线坐席（new-waiting / presence）
  - `newWaitingToTenantAgents(Long tenantId, ChatSession session)` — 便捷：广播新待接
  - `typing(String key, String sessionId, String from)` — 推 `typing`
  - `presenceToTenantAgents(Long tenantId, Long agentUserId, boolean online)` — 广播 `presence`

- [ ] **Step 1：写 RealtimeNotifierTest（先失败）**

`RealtimeNotifierTest.java`（Mockito，mock `RealtimeHub`，验证发布到正确键 + 事件名）：

```java
package com.qingyu.care.service.realtime;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeNotifierTest {

    @Mock RealtimeHub hub;
    @InjectMocks RealtimeNotifier notifier;

    @Test
    void 坐席消息推到用户inbox_message事件_含content() {
        ChatMessage msg = ChatMessage.builder().role(MessageRole.ASSISTANT).content("您好").build();
        notifier.toUserMessage("sess-1", msg);

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(hub).publish(org.mockito.ArgumentMatchers.eq("session:sess-1"),
                org.mockito.ArgumentMatchers.eq("message"), data.capture());
        assertThat(data.getValue()).contains("您好").contains("ASSISTANT");
    }

    @Test
    void 新待接广播给本租户每个在线坐席() {
        when(hub.onlineKeys("agent:9:")).thenReturn(List.of("agent:9:1", "agent:9:2"));
        ChatSession s = ChatSession.builder().sessionId("sess-2").build();
        s.setTenantId(9L);

        notifier.newWaitingToTenantAgents(9L, s);

        verify(hub).publish(org.mockito.ArgumentMatchers.eq("agent:9:1"),
                org.mockito.ArgumentMatchers.eq("session"), org.mockito.ArgumentMatchers.contains("sess-2"));
        verify(hub).publish(org.mockito.ArgumentMatchers.eq("agent:9:2"),
                org.mockito.ArgumentMatchers.eq("session"), org.mockito.ArgumentMatchers.contains("sess-2"));
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=RealtimeNotifierTest`
Expected: 编译失败（`RealtimeNotifier` 不存在）。

- [ ] **Step 3：写 RealtimeNotifier**

`RealtimeNotifier.java`（用 `ObjectMapper` 构 JSON；广播用 `hub.onlineKeys(prefix)`）：

```java
package com.qingyu.care.service.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时领域发布：把聊天消息 / 会话状态 / typing / presence 构造成事件 JSON 交给 RealtimeHub。
 * 全部 best-effort（hub 内部丢弃失败），不抛给业务。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeNotifier {

    private final RealtimeHub hub;
    private final ObjectMapper objectMapper;

    /** 向用户 inbox 推一条聊天消息。 */
    public void toUserMessage(String sessionId, ChatMessage msg) {
        hub.publish(RealtimeKeys.session(sessionId), "message", messageJson(sessionId, msg));
    }

    /** 向用户 inbox 推一条会话状态事件（accepted/ended/returned_to_ai）。 */
    public void toUserSessionEvent(String sessionId, String kind, String text) {
        hub.publish(RealtimeKeys.session(sessionId), "session",
                json(Map.of("sessionId", sessionId, "kind", kind, "text", text)));
    }

    /** 向指定坐席推一条聊天消息（用户新消息）。 */
    public void toAgentMessage(Long tenantId, Long agentUserId, ChatMessage msg) {
        hub.publish(RealtimeKeys.agent(tenantId, agentUserId), "message",
                messageJson(msg.getSession() != null ? msg.getSession().getSessionId() : null, msg));
    }

    /** 广播一条事件给本租户所有在线坐席。 */
    public void toTenantAgents(Long tenantId, String event, String data) {
        for (String key : hub.onlineKeys(RealtimeKeys.agentPrefix(tenantId))) {
            hub.publish(key, event, data);
        }
    }

    /** 便捷：广播「新待接会话」给本租户在线坐席。 */
    public void newWaitingToTenantAgents(Long tenantId, ChatSession session) {
        toTenantAgents(tenantId, "session",
                json(Map.of("sessionId", session.getSessionId(), "kind", "waiting")));
    }

    /** 推 typing 短事件（不落库）。 */
    public void typing(String key, String sessionId, String from) {
        hub.publish(key, "typing", json(Map.of("sessionId", sessionId, "from", from)));
    }

    /** 广播坐席上/下线 presence 给本租户其他在线坐席。 */
    public void presenceToTenantAgents(Long tenantId, Long agentUserId, boolean online) {
        toTenantAgents(tenantId, "presence",
                json(Map.of("agentUserId", agentUserId, "online", online)));
    }

    private String messageJson(String sessionId, ChatMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("messageId", msg.getId());
        m.put("role", msg.getRole() != null ? msg.getRole().name() : null);
        m.put("content", msg.getContent());
        m.put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
        return json(m);
    }

    private String json(Map<String, ?> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            log.warn("实时事件 JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}
```

> 注：`ChatMessage` 的 getter（`getId/getRole/getContent/getCreatedAt/getSession`）与 `ChatSession.getSessionId()` 均为既有；实现前用 Read 核对字段名（`entity/ChatMessage.java`、`entity/ChatSession.java`）。

- [ ] **Step 4：运行确认通过**

Run: `cd backend && mvn -q test -Dtest=RealtimeNotifierTest`
Expected: PASS（2/2）。

- [ ] **Step 5：提交**

```bash
cd backend && git add src/main/java/com/qingyu/care/service/realtime/RealtimeNotifier.java \
  src/test/java/com/qingyu/care/service/realtime/RealtimeNotifierTest.java
git commit -m "feat: 实时领域发布 RealtimeNotifier（消息/会话/typing/presence 事件构造 + 广播）"
```

---

### Task A3: 两端 SSE 端点 + 坐席角色门禁

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/util/SecurityUtil.java`（加 `isTenantMember()` + `requireAgent()`）
- Create: `backend/src/main/java/com/qingyu/care/controller/AgentStreamController.java`（坐席 stream）
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenSessionController.java`（用户 inbox）
- Test: `backend/src/test/java/com/qingyu/care/it/AgentEndpointAuthIT.java`

**Interfaces:**
- Consumes: `RealtimeHub.subscribe`、`RealtimeKeys`、`SecurityUtil.requireAgent`、`StpUtil.getLoginIdAsLong`、`TenantContext.getTenantId/getAppId`。
- Produces:
  - `SecurityUtil.requireAgent()`（role ∈ {TENANT_ADMIN, TENANT_MEMBER} 否则抛 403）
  - `GET /v1/chat/agent/stream`（坐席 SSE，键 `agent:tid:uid`）
  - `GET /open/v1/sessions/{sessionId}/inbox`（用户 SSE，键 `session:sessionId`）

- [ ] **Step 1：SecurityUtil 加坐席守卫**

在 `util/SecurityUtil.java` 的 `isTenantAdmin()` 后加：

```java
    public static boolean isTenantMember() {
        return UserRole.TENANT_MEMBER.name().equals(getRole());
    }

    /** 要求坐席身份（本租户管理员或成员），否则抛 403。平台管理员无租户上下文，不作为坐席。 */
    public static void requireAgent() {
        if (!isTenantAdmin() && !isTenantMember()) {
            throw new BusinessException(403, "无权限：该操作仅限本租户坐席");
        }
    }
```

- [ ] **Step 2：坐席 SSE 控制器 AgentStreamController**

`controller/AgentStreamController.java`：

```java
package com.qingyu.care.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.service.realtime.RealtimeKeys;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.qingyu.care.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 坐席实时通道：SSE 收「新待接/用户新消息/typing/presence」；在线坐席列表；坐席侧 typing。
 * 全部要求坐席身份（requireAgent）。agentId 一律取登录态，不信任入参。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/chat")
@RequiredArgsConstructor
@Tag(name = "Agent Realtime", description = "坐席实时通道")
public class AgentStreamController {

    private final RealtimeHub realtimeHub;
    private final RealtimeNotifier realtimeNotifier;

    @GetMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "坐席实时流", description = "坐席订阅新待接/用户新消息/typing/presence")
    public Flux<ServerSentEvent<String>> agentStream() {
        SecurityUtil.requireAgent();
        Long tenantId = TenantContext.getTenantId();
        Long agentUserId = StpUtil.getLoginIdAsLong();
        String key = RealtimeKeys.agent(tenantId, agentUserId);
        // 订阅即上线、断连即下线：presence 广播给同租户其他坐席
        realtimeNotifier.presenceToTenantAgents(tenantId, agentUserId, true);
        return realtimeHub.subscribe(key)
                .doFinally(sig -> realtimeNotifier.presenceToTenantAgents(tenantId, agentUserId, false));
    }

    @GetMapping("/agents/online")
    @Operation(summary = "在线坐席列表", description = "本租户当前在线坐席的用户 ID 列表")
    public com.qingyu.care.dto.ApiResponse<List<Long>> onlineAgents() {
        SecurityUtil.requireAgent();
        Long tenantId = TenantContext.getTenantId();
        List<Long> ids = realtimeHub.onlineKeys(RealtimeKeys.agentPrefix(tenantId)).stream()
                .map(k -> Long.parseLong(k.substring(k.lastIndexOf(':') + 1)))
                .toList();
        return com.qingyu.care.dto.ApiResponse.success(ids);
    }

    @PostMapping("/session/{sessionId}/typing")
    @Operation(summary = "坐席正在输入", description = "向该会话用户端推 typing 事件（不落库）")
    public com.qingyu.care.dto.ApiResponse<Void> agentTyping(@PathVariable String sessionId) {
        SecurityUtil.requireAgent();
        realtimeNotifier.typing(RealtimeKeys.session(sessionId), sessionId, "agent");
        return com.qingyu.care.dto.ApiResponse.success(null);
    }
}
```

- [ ] **Step 3：用户 inbox 端点（OpenSessionController）**

在 `controller/open/OpenSessionController.java` 加（沿用其现有类结构/注入；用 `RealtimeHub`、`RealtimeKeys`）：

```java
    @org.springframework.web.bind.annotation.GetMapping(
            value = "/{sessionId}/inbox",
            produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> inbox(
            @org.springframework.web.bind.annotation.PathVariable String sessionId) {
        // 鉴权由 OpenApiAuthInterceptor 完成（appId + Origin/appKey）；订阅该会话 inbox
        return realtimeHub.subscribe(com.qingyu.care.service.realtime.RealtimeKeys.session(sessionId));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{sessionId}/typing")
    public java.util.Map<String, Object> userTyping(
            @org.springframework.web.bind.annotation.PathVariable String sessionId) {
        // 推给该会话当前接管坐席（未接管 / 无 agentId 则不推）
        com.qingyu.care.entity.ChatSession s = chatService.getSession(sessionId);
        if (s.getAgentId() != null) {
            realtimeNotifier.typing(
                    com.qingyu.care.service.realtime.RealtimeKeys.agent(s.getTenantId(), s.getAgentId()),
                    sessionId, "user");
        }
        return java.util.Map.of("success", true);
    }
```

> 注：在 `OpenSessionController` 补注入 `RealtimeHub realtimeHub` / `RealtimeNotifier realtimeNotifier` / `ChatService chatService`（若其现有构造未含）。inbox 端点在 `/open/v1/**` 下由 `OpenApiAuthInterceptor` 鉴权；SSE 长连仅在建连时过一次拦截器（限流按单次请求计，非按事件），可接受。

- [ ] **Step 4：写 AgentEndpointAuthIT（角色门禁）**

`it/AgentEndpointAuthIT.java`（继承 `AbstractPostgresIT`，MockMvc）：验证 `GET /v1/chat/agents/online` 未登录 → 401（Sa-Token）；以非坐席角色（如平台管理员会话）→ 403。参考既有鉴权类 IT（如 `OpenApiAuthIT` 或任一 `*IT` 里登录态构造方式）设置会话角色。断言：

```java
// 未登录
mockMvc.perform(get("/api/v1/chat/agents/online"))
        .andExpect(status().isUnauthorized());
// 平台管理员（非坐席）登录态 → 403（requireAgent 抛 BusinessException(403)）
// 用项目既有的登录态设置手法（StpUtil 登录 + session set role=PLATFORM_ADMIN），再请求断言 403
```

> 实现前用 Read 看一个既有需要登录的 `*IT`（如 `it/OpenApiAuthIT.java` 或搜索 `StpUtil.login` 的测试）确认本仓库 IT 里构造登录态 + 角色的确切写法，照搬。上下文路径为 `/api`（`server.servlet.context-path`），MockMvc 路径含 `/api` 前缀（按既有 IT 惯例）。

- [ ] **Step 5：运行确认通过 + 编译**

Run: `cd backend && mvn -q verify -Dit.test=AgentEndpointAuthIT -DfailIfNoTests=false`
Expected: PASS（需 Docker）。

- [ ] **Step 6：提交**

```bash
cd backend && git add src/main/java/com/qingyu/care/util/SecurityUtil.java \
  src/main/java/com/qingyu/care/controller/AgentStreamController.java \
  src/main/java/com/qingyu/care/controller/open/OpenSessionController.java \
  src/test/java/com/qingyu/care/it/AgentEndpointAuthIT.java
git commit -m "feat: 两端 SSE 端点（坐席 stream + 用户 inbox）+ 坐席角色门禁 requireAgent"
```

---

### Task B1: 坐席身份收口（agentId 绑登录 + 端点门禁）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/dto/AgentReplyRequest.java`（删 agentId）
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java`（accept/agentReply 门禁 + agentId 取登录态）
- Test: `backend/src/test/java/com/qingyu/care/service/AgentIdentityBindingTest.java`（控制器层薄，改在 IT 覆盖）→ 实际用 IT `AgentEndpointAuthIT` 扩展或新增行为断言

**Interfaces:**
- Consumes: `StpUtil.getLoginIdAsLong()`、`SecurityUtil.requireAgent()`、既有 `chatService.acceptSession(sessionId, agentId)` / `chatService.agentReply(sessionId, message, agentId)`（签名不变）。
- Produces: `AgentReplyRequest` 去掉 `agentId` 字段（仅 `sessionId`/`message`）。

- [ ] **Step 1：AgentReplyRequest 删 agentId**

`dto/AgentReplyRequest.java` 删除 `agentId` 字段及其 `@NotNull`、`import NotNull`（若仅此处用）。结果仅保留 `sessionId`（@NotBlank）+ `message`（@NotBlank）。

- [ ] **Step 2：ChatController 收口 accept/agentReply**

改 `controller/ChatController.java`：

`acceptSession`（约 122-129）：
```java
    @PostMapping("/session/{sessionId}/accept")
    @Operation(summary = "接管会话", description = "客服接管等待中的会话")
    public ApiResponse<ChatSession> acceptSession(@PathVariable String sessionId) {
        SecurityUtil.requireAgent();
        Long agentId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        ChatSession session = chatService.acceptSession(sessionId, agentId);
        return ApiResponse.success("会话已接管", session);
    }
```

`agentReply`（约 131-136）：
```java
    @PostMapping("/agent/reply")
    @Operation(summary = "客服回复", description = "客服发送消息（绕过AI）")
    public ApiResponse<Void> agentReply(@Valid @RequestBody AgentReplyRequest request) {
        SecurityUtil.requireAgent();
        Long agentId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        chatService.agentReply(request.getSessionId(), request.getMessage(), agentId);
        return ApiResponse.success("消息已发送", null);
    }
```

加 import `com.qingyu.care.util.SecurityUtil;`。`getWaitingAgentSessions`/`getSessionMessages` 也加 `SecurityUtil.requireAgent();` 首行（坐席专属）。

- [ ] **Step 3：IT 断言身份收口行为**

在 `it/AgentEndpointAuthIT.java` 加用例：以坐席角色登录，`POST /api/v1/chat/agent/reply`（体仅 `{sessionId,message}`，无 agentId）→ 对一条本租户 `AGENT_PROCESSING` 会话，断言 200 且落库的 ASSISTANT 消息存在（`getSessionMessages` 可见）；`agentId` 为登录用户 id（如会话 `agentId` 已被 accept 绑定则跳过该断言）。播种一条 WAITING→accept→reply 全走 MockMvc 真实请求（使过滤器/上下文生效）。

- [ ] **Step 4：运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=AgentEndpointAuthIT -DfailIfNoTests=false`
Expected: PASS。

- [ ] **Step 5：提交**

```bash
cd backend && git add src/main/java/com/qingyu/care/dto/AgentReplyRequest.java \
  src/main/java/com/qingyu/care/controller/ChatController.java \
  src/test/java/com/qingyu/care/it/AgentEndpointAuthIT.java
git commit -m "fix: 坐席身份收口——agentId 取登录态、坐席端点加 requireAgent、AgentReplyRequest 去 agentId"
```

---

### Task B2: streamChat 对齐（抽取共享转人工/AI 静默）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/service/impl/StreamChatHandoverTest.java`

**Interfaces:**
- Consumes: `RealtimeNotifier`（注入 `ChatServiceImpl`）、`handoverRuleService.checkKeywordTrigger`、既有 `SessionStatus`。
- Produces（`ChatServiceImpl` 私有/包私有）：
  - `boolean isAgentControlled(ChatSession session)` — status ∈ {AGENT_PROCESSING, WAITING_AGENT}
  - `boolean triggerKeywordHandoverIfHit(ChatSession session, String message)` — 命中则置 WAITING_AGENT+存消息+发事件+notifier 广播新待接，返回 true
  - `void persistUserMessageDuringHandover(ChatSession session, String message)` — 存用户消息 +（AGENT_PROCESSING 时）notifier.toAgentMessage 推给接管坐席

- [ ] **Step 1：写 StreamChatHandoverTest（先失败）**

`service/impl/StreamChatHandoverTest.java`（Mockito，注入全套 mock 含新 `RealtimeNotifier`；沿用 `ChatServiceSessionOwnershipTest` 的 mock 集合，注意本任务后 `ChatServiceImpl` 新增依赖 `RealtimeNotifier` 需补 `@Mock`；若 Task 1 的 `self` 自注入已在，需 `@BeforeEach setSelf`）：

```java
// 断言 streamChat 在 AGENT_PROCESSING 会话下：不调用 AI（modelRouterService 无交互），
// 返回的 Flux 含固定静默文案，且 messageRepository.save 落用户消息、notifier.toAgentMessage 被调
@Test
void streamChat_人工接管态_静默不调AI_推用户消息给坐席() {
    ChatSession session = ChatSession.builder().sessionId("s1").build();
    session.setStatus(SessionStatus.AGENT_PROCESSING);
    session.setTenantId(9L); session.setAgentId(3L);
    when(sessionRepository.findBySessionId("s1")).thenReturn(java.util.Optional.of(session));
    when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    // TenantContext 归属放行（无上下文即放行，见 belongsToCurrentContext）

    java.util.List<String> out = chatService.streamChat(
            ChatRequest.builder().sessionId("s1").message("你好").build()).collectList().block();

    assertThat(String.join("", out)).contains("人工客服");
    verify(modelRouterService, never()).getChatClientForScenario(any());
    verify(realtimeNotifier).toAgentMessage(eq(9L), eq(3L), any());
}
```

（同类再加一个 `chat()` 的等价静默用例仍绿的回归断言，确认抽取未破坏同步路径。）

- [ ] **Step 2：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=StreamChatHandoverTest`
Expected: FAIL（streamChat 未做静默，会走 AI；或 `realtimeNotifier` 依赖未注入编译失败）。

- [ ] **Step 3：ChatServiceImpl 注入 notifier + 抽取共享逻辑 + streamChat 前置**

1) 在 `ChatServiceImpl` 字段区加 `private final RealtimeNotifier realtimeNotifier;`（`@RequiredArgsConstructor` 自动纳入）。
2) 抽取三个辅助方法（放在 `searchKnowledge` 附近）：

```java
    /** 会话是否处于人工掌控（等待接管 / 已接管）。 */
    private boolean isAgentControlled(ChatSession session) {
        return session.getStatus() == SessionStatus.AGENT_PROCESSING
                || session.getStatus() == SessionStatus.WAITING_AGENT;
    }

    /** 命中转人工关键词则置 WAITING_AGENT、存消息、发事件、广播新待接，返回是否命中。 */
    private boolean triggerKeywordHandoverIfHit(ChatSession session, String message) {
        if (!handoverRuleService.checkKeywordTrigger(message)) {
            return false;
        }
        session.setStatus(SessionStatus.WAITING_AGENT);
        sessionRepository.save(session);
        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.handover_requested", session.getAppId(), session.getTenantId(),
                session.getSessionId(), "keyword", LocalDateTime.now()));
        ChatMessage userMsg = ChatMessage.builder().session(session)
                .role(MessageRole.USER).content(message).build();
        messageRepository.save(userMsg);
        realtimeNotifier.newWaitingToTenantAgents(session.getTenantId(), session);
        return true;
    }

    /** 人工掌控态：存用户消息；若已被坐席接管则实时推给该坐席。 */
    private void persistUserMessageDuringHandover(ChatSession session, String message) {
        ChatMessage userMsg = ChatMessage.builder().session(session)
                .role(MessageRole.USER).content(message).build();
        messageRepository.save(userMsg);
        if (session.getStatus() == SessionStatus.AGENT_PROCESSING && session.getAgentId() != null) {
            realtimeNotifier.toAgentMessage(session.getTenantId(), session.getAgentId(), userMsg);
        }
    }
```

3) `chat()` 用这三个方法替换其内联的关键词分支（约 100-124）与 AI 静默分支（约 80-97），保持返回文案不变（复用固定话术），并在关键词分支/静默分支调用上述方法。**不改对外行为**，只是抽取 + 加 notifier 推送。

4) `streamChat(...)`（3 参，约 218-244）在 `getOrCreateSession` 之后、检索之前插入前置：
```java
        // 与 chat() 对齐：先做转人工关键词触发 + 人工接管态 AI 静默
        if (triggerKeywordHandoverIfHit(session, request.getMessage())) {
            onTokensUsed.accept(0);
            onAiMessageSaved.accept(-1L);
            return Flux.just("正在为您转接人工客服，请稍候...");
        }
        if (isAgentControlled(session)) {
            persistUserMessageDuringHandover(session, request.getMessage());
            onTokensUsed.accept(0);
            onAiMessageSaved.accept(-1L);
            return Flux.just("[会话已转接人工客服，请等待回复]");
        }
```

> 实现注意：`streamChat` 非 @Transactional，`sessionRepository.save`/`messageRepository.save` 各自提交，OK。固定文案与 `chat()` 保持一致字符串。

- [ ] **Step 4：运行确认通过 + 回归**

Run: `cd backend && mvn -q test -Dtest=StreamChatHandoverTest,ChatServiceSessionOwnershipTest,ChatServiceRetrievalTxTest`
Expected: PASS（含既有 chat 静默/归属用例不回归；注意这些既有测试类可能需补 `@Mock RealtimeNotifier` 才能 `@InjectMocks` 构造成功——一并补上）。

- [ ] **Step 5：提交**

```bash
cd backend && git add -A && git commit -m "feat: streamChat 对齐 chat()——共享转人工关键词触发 + 人工接管 AI 静默 + 实时推坐席"
```

---

### Task B3: 双向推送 + 会话事件（accept/agentReply）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（acceptSession/agentReply 接 notifier + 事件；getWaitingAgentSessions 加 appId）
- Test: `backend/src/test/java/com/qingyu/care/service/impl/AgentNotifyEventTest.java`、`backend/src/test/java/com/qingyu/care/service/WaitingQueueAppScopeTest.java`

**Interfaces:**
- Consumes: `realtimeNotifier`、`eventPublisher`、`TenantContext.getAppId()`、既有 `sessionRepository.findByTenantIdAndAppIdAndStatus...`（若无则新增 repo 方法）。
- Produces: `acceptSession`/`agentReply` 内的实时推送 + 新事件 `session.accepted`/`agent.replied`。

- [ ] **Step 1：写测试（先失败）**

`AgentNotifyEventTest.java`（Mockito）：
- `acceptSession` 成功后：`realtimeNotifier.toUserSessionEvent(sessionId,"accepted",...)` 被调 + 发布 `session.accepted` 事件（capture `WebhookDomainEvent`，断 eventType）。
- `agentReply` 成功后：`realtimeNotifier.toUserMessage(sessionId, <ASSISTANT msg>)` 被调 + 发布 `agent.replied` 事件。

`WaitingQueueAppScopeTest.java`（Mockito）：`getWaitingAgentSessions` 在有 tenantId+appId 上下文时调用**按 tenantId+appId** 收窄的 repo 方法（verify 参数含 appId），无 tenantId 返空页（沿用现有空页语义）。

- [ ] **Step 2：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=AgentNotifyEventTest,WaitingQueueAppScopeTest`
Expected: FAIL。

- [ ] **Step 3：实现**

1) `acceptSession(sessionId, agentId)`（约 716-726）末尾（`sessionRepository.save(session)` 之后）加：
```java
        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.accepted", session.getAppId(), session.getTenantId(),
                session.getSessionId(), null, LocalDateTime.now()));
        realtimeNotifier.toUserSessionEvent(session.getSessionId(), "accepted", "客服已接入，正在为您服务");
```

2) `agentReply(sessionId, message, agentId)`（约 732-745）：拿到保存的 `agentMsg` 后加：
```java
        eventPublisher.publishEvent(new WebhookDomainEvent(
                "agent.replied", session.getAppId(), session.getTenantId(),
                session.getSessionId(), null, LocalDateTime.now()));
        realtimeNotifier.toUserMessage(session.getSessionId(), agentMsg);
```
（把现有 `messageRepository.save(agentMsg)` 的返回接住为 `agentMsg`。）

3) `getWaitingAgentSessions`（约 748-757）：改为按 tenantId + appId 收窄。若 `ChatSessionRepository` 无 `findByTenantIdAndAppIdAndStatusOrderByCreatedAtDesc`，新增之；`appId = TenantContext.getAppId()`，appId 为 null 时按仅 tenantId（保持兼容）或返空——**MVP 取：appId 非空时双条件收窄，appId 为空时退回仅 tenantId**（与现有行为兼容，避免管理端无 appId 场景炸空）。

- [ ] **Step 4：运行确认通过**

Run: `cd backend && mvn -q test -Dtest=AgentNotifyEventTest,WaitingQueueAppScopeTest`
Expected: PASS。

- [ ] **Step 5：提交**

```bash
cd backend && git add -A && git commit -m "feat: 坐席接管/回复双向实时推送 + session.accepted/agent.replied 事件 + 待接队列 appId 收窄"
```

---

### Task C1: 回归 AI（returnToAI）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatService.java`（加接口方法）
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（实现）
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java`（端点）
- Test: `backend/src/test/java/com/qingyu/care/service/impl/ReturnToAiTest.java`

**Interfaces:**
- Produces: `ChatService.returnToAI(String sessionId)`；`POST /v1/chat/session/{sessionId}/return-to-ai`（requireAgent）。

- [ ] **Step 1：写 ReturnToAiTest（先失败）**

`ReturnToAiTest.java`（Mockito）：`AGENT_PROCESSING` 会话 → `returnToAI` 后 status=`AI_PROCESSING`、`agentId` 清空、`realtimeNotifier.toUserSessionEvent(id,"returned_to_ai",...)` 被调；非 `AGENT_PROCESSING`（如 `AI_PROCESSING`）→ 抛 `InvalidOperationException`。

- [ ] **Step 2：运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ReturnToAiTest`
Expected: FAIL（方法不存在）。

- [ ] **Step 3：实现**

`ChatService.java` 加：`void returnToAI(String sessionId);`

`ChatServiceImpl` 加（`@Transactional`）：
```java
    @Override
    @Transactional
    public void returnToAI(String sessionId) {
        ChatSession session = getSession(sessionId);
        if (session.getStatus() != SessionStatus.AGENT_PROCESSING) {
            throw new InvalidOperationException("该会话不在人工处理状态，无法交还 AI：" + session.getStatus());
        }
        session.setStatus(SessionStatus.AI_PROCESSING);
        session.setAgentId(null);
        sessionRepository.save(session);
        realtimeNotifier.toUserSessionEvent(sessionId, "returned_to_ai", "已切回智能助手为您服务");
        log.info("Session {} returned to AI by agent", sessionId);
    }
```

`ChatController` 加：
```java
    @PostMapping("/session/{sessionId}/return-to-ai")
    @Operation(summary = "交还 AI", description = "坐席把会话交还 AI 续答")
    public ApiResponse<Void> returnToAI(@PathVariable String sessionId) {
        SecurityUtil.requireAgent();
        chatService.returnToAI(sessionId);
        return ApiResponse.success("已交还 AI", null);
    }
```

- [ ] **Step 4：运行确认通过**

Run: `cd backend && mvn -q test -Dtest=ReturnToAiTest`
Expected: PASS。

- [ ] **Step 5：提交**

```bash
cd backend && git add -A && git commit -m "feat: 回归 AI（returnToAI，AGENT_PROCESSING→AI_PROCESSING + 清 agentId + 通知用户）"
```

---

### Task C2: 端到端实时流 IT（accept→reply→SSE 收到）

**Files:**
- Test: `backend/src/test/java/com/qingyu/care/it/LiveAgentFlowIT.java`
- Test: `backend/src/test/java/com/qingyu/care/it/RealtimeSseIT.java`

**Interfaces:** 纯测试，消费前述所有产出。

- [ ] **Step 1：RealtimeSseIT — SSE 端到端**

`RealtimeSseIT extends AbstractPostgresIT`：直接注入 `RealtimeHub` bean，`subscribe("session:x")` 拿 Flux，`StepVerifier` 起订阅后另一线程 `publish` 一条 `message`，断言收到。（不经 HTTP，验证 bean 装配 + 多播在真实上下文成立。）亦可用 MockMvc async 对 `GET /api/open/v1/sessions/{id}/inbox` 发起异步请求 + 触发 publish 断言 body 含事件——若 MockMvc async SSE 断言过脆，退回 bean 级验证并注明。

- [ ] **Step 2：LiveAgentFlowIT — 坐席全流程状态流转**

`LiveAgentFlowIT extends AbstractPostgresIT`（MockMvc + 坐席登录态）：播种 app + 一条会话；`handover`→`WAITING_AGENT`；`accept`→`AGENT_PROCESSING`+agentId=登录用户；`agent/reply`→落 ASSISTANT 消息；`return-to-ai`→`AI_PROCESSING`+agentId 清空；`getSessionMessages` 断言消息序列。全走 `/api/v1/chat/**` 真实请求。

- [ ] **Step 3：运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=RealtimeSseIT,LiveAgentFlowIT -DfailIfNoTests=false`
Expected: PASS。

- [ ] **Step 4：提交**

```bash
cd backend && git add -A && git commit -m "test: 真人坐席端到端 IT（SSE 收发 + accept→reply→return-to-ai 状态流转）"
```

---

### Task E1: 后端收尾全量回归

- [ ] **Step 1：全量 verify**

Run: `cd backend && mvn -q verify`
Expected: BUILD SUCCESS，全部单测 + IT 绿。若红先修再继续。

- [ ] **Step 2：登记留债 + 广域终审准备**

无代码改动。确认无越权/隔离回退，SSE fail-open、DB 事实源语义落实。前端坐席台实时化 + widget inbox + 重建 widget.js 属 **Plan 2（后续）**。

## Self-Review

**1. Spec coverage：** spec §3 RealtimeHub/事件模型→A1/A2；§3 两端端点/鉴权→A3；§4 身份收口→B1；§5 streamChat 对齐→B2；§5/§6 双向推送+accept/agentReply 事件+§6 appId 队列→B3；§6 回归 AI→C1；§7 presence（连接存活+在线列表+presence 事件）→A3（stream 上线/下线广播 + online 端点）；§7 typing→A3（两端 typing 端点）；§9 多租户/§10 fail-open→贯穿；§11 测试→各任务单测+IT+E1。**前端 §8 + widget 归 Plan 2（本计划 Global Constraints 已声明）。**
**2. Placeholder scan：** 无 TBD；A3 Step3 的 `agentTypingTargetForSession` 为显式标注的「按注释落地」占位并给了确切实现指引（查 session.agentId 后 `RealtimeKeys.agent`），非模糊要求；各测试步给了断言。
**3. Type consistency：** `RealtimeHub.subscribe/publish/isOnline/onlineKeys`、`RealtimeKeys.session/agent/agentPrefix`、`RealtimeNotifier.toUserMessage/toUserSessionEvent/toAgentMessage/toTenantAgents/newWaitingToTenantAgents/typing/presenceToTenantAgents`、`SecurityUtil.requireAgent`、`ChatService.returnToAI`、`WebhookDomainEvent`(6 参) 前后一致。事件名 `session.accepted`/`agent.replied`/`session.handover_requested` 一致。

# 对话内工具调用 + Skill 机制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让非流式 `chat()` 在回答前把「当前应用配置的 HTTP 工具 + Skill 能力包」交给模型，由 Spring AI 自动跑工具调用循环。

**Architecture:** 两个继承 `BaseTenantEntity` 的实体（`AiTool` 可执行原语 / `AiSkill` Markdown 能力包，绑定工具）；运行时 `ChatToolService.resolve()` 组装 `{skillMarkdowns, List<ToolCallback>}`（`HttpToolCallback` 实现 Spring AI `ToolCallback`）；`ChatServiceImpl.chat()` 把 skill 正文追加进系统提示、把工具挂到 `.toolCallbacks(...)`。无配置时逐字节零回归。

**Tech Stack:** Spring Boot 3.5 / Spring AI 1.1.8（`ToolCallback`、`DefaultToolDefinition`、`ChatClient.prompt().toolCallbacks()`）/ JPA + pgvector / React 19 + shadcn。

## Global Constraints

- 仅**非流式 `chat()`** 支持工具/skill；`streamChat()` 本期不变（buildMessages 传空 skillMarkdowns）。
- 仅**只读查询工具**（无写/动作、无确认流）。
- **SSRF 守卫为硬门槛**：出站前拒 loopback/link-local/site-local/anylocal/multicast 地址。
- 工具**只经 skill 激活**：未被任何 enabled skill 绑定的 `AiTool` 不暴露。
- **应用级隔离**：两实体继承 `BaseTenantEntity`（`tenant_id`/`app_id` 自动填充+过滤）。
- Skill **全量注入**，无路由。
- Token 计量：多轮往返 `getUsage()` 只反映末轮，**接受少计 + 打日志**。
- `AiTool.authHeader` 明文存储为已知技术债，与 `AiModelConfig.apiKey` 同源，本期不扩大治理。
- git：允许 commit（在 `feat/chat-tool-calling` 分支上），未授权不 push。
- 测试：单测优先纯 Mockito（`@ExtendWith(MockitoExtension.class)`，免 Docker）；IT 继承 `AbstractPostgresIT`（Failsafe，需 Docker）。
- 已核实 Spring AI 1.1.8 API：`HttpToolCallback` 直接实现 `org.springframework.ai.tool.ToolCallback`；`getToolDefinition()` 用 `DefaultToolDefinition.builder().name().description().inputSchema(jsonStr).build()`；挂载 `client.prompt().messages(...).toolCallbacks(list).call().chatResponse()`；工具执行无需 `ToolContext`（构造期已绑定租户配置）。

---

## 文件结构

**新增（后端）**
- `entity/AiTool.java` — HTTP 工具实体
- `entity/AiSkill.java` — Skill 能力包实体
- `repository/AiToolRepository.java` / `repository/AiSkillRepository.java`
- `config/SsrfGuard.java` — SSRF 校验纯工具
- `service/tool/HttpToolExecutor.java`（接口）/ `service/tool/RestClientHttpToolExecutor.java`（实现 bean）
- `service/tool/HttpToolCallback.java` — 实现 `ToolCallback`
- `service/ChatToolService.java` — resolve() 运行时 + CRUD 管理（合并设计稿的 `ChatToolResolver`，遵循 `ModelRouterService` 一服务兼运行时+CRUD 的先例）
- `controller/AiToolController.java` / `controller/AiSkillController.java`

**修改（后端）**
- `service/impl/ChatServiceImpl.java` — 注入 `ChatToolService`；`buildMessages` 加 `List<String> skillMarkdowns` 参数；`chat()` 组装并挂工具；新增静态 `appendSkillMarkdowns`。

**新增（前端）**
- `api/chat-tool.ts` — AiTool/AiSkill 接口 + service
- `pages/ToolConfigPage.tsx` / `pages/SkillConfigPage.tsx`

**修改（前端）**
- `App.tsx` — 加 `/ai-tools`、`/ai-skills` 路由（包 `RequireApp`）
- `layouts/MainLayout.tsx` — 「AI 模型」组加两个导航项

**测试**
- `test/.../config/SsrfGuardTest.java`、`service/tool/HttpToolCallbackTest.java`、`service/ChatToolServiceResolveTest.java`、`service/impl/ChatServiceSkillPromptTest.java`
- `test/.../it/AiSkillPersistenceIT.java`

---

## Phase A — 后端核心

### Task A1: SSRF 守卫

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/SsrfGuard.java`
- Test: `backend/src/test/java/com/qingyu/care/config/SsrfGuardTest.java`

**Interfaces:**
- Produces: `SsrfGuard.assertAllowed(String url)`（越权抛 `IllegalArgumentException`）、`SsrfGuard.isBlocked(InetAddress addr)`（package-private，纯函数）。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.config;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.assertj.core.api.Assertions.*;

class SsrfGuardTest {

    @Test
    void blocksLoopbackLinkLocalAndSiteLocal() throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("169.254.169.254"))).isTrue(); // 云元数据
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("192.168.1.1"))).isTrue();
    }

    @Test
    void allowsPublicAddress() throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void assertAllowedRejectsInternalUrl() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://127.0.0.1/orders/1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assertAllowedPassesPublicUrl() {
        assertThatCode(() -> SsrfGuard.assertAllowed("http://8.8.8.8/orders/1"))
                .doesNotThrowAnyException();
    }

    @Test
    void assertAllowedRejectsHostlessUrl() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=SsrfGuardTest`
Expected: 编译失败 / `SsrfGuard` 不存在。

- [ ] **Step 3: 写最小实现**

```java
package com.qingyu.care.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 守卫：工具调用由平台代租户出站，必须阻止租户把工具 URL 指向内网/环回/云元数据
 * （如 169.254.169.254）借平台身份探测或窃取凭据。发请求前对解析出的每个 IP 做校验。
 *
 * @author ruan
 */
public final class SsrfGuard {

    private SsrfGuard() {
    }

    /** 校验 URL 目标地址均为公网可达，否则抛 {@link IllegalArgumentException}。 */
    public static void assertAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法工具 URL: " + url, e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("工具 URL 缺少合法主机: " + url);
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isBlocked(addr)) {
                    throw new IllegalArgumentException("工具 URL 指向内网/环回地址，已拒绝: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("工具 URL 主机无法解析: " + host, e);
        }
    }

    /** 纯函数：判断地址是否属于应拒绝的内网/特殊网段。 */
    static boolean isBlocked(InetAddress a) {
        return a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=SsrfGuardTest`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/config/SsrfGuard.java backend/src/test/java/com/qingyu/care/config/SsrfGuardTest.java
git commit -m "feat: 新增工具调用 SSRF 守卫 SsrfGuard"
```

---

### Task A2: AiTool 实体与仓库

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/entity/AiTool.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/AiToolRepository.java`

**Interfaces:**
- Produces: `AiTool`（字段：id/name/description/inputSchema/httpMethod/urlTemplate/authHeader/timeoutMs/maxResponseChars/enabled/createdAt/updatedAt，继承 `BaseTenantEntity`）；`AiToolRepository.findByIdInAndEnabledTrue(Collection<Long>)`、`findAll()`。

- [ ] **Step 1: 写实体**

```java
package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 工具（HTTP）：模型可调用的可执行原语。urlTemplate 由租户管理员固定，模型只填参数不选 URL。
 */
@Entity
@Table(name = "ai_tool")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTool extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 暴露给 LLM 的工具名（合法标识符，如 query_order） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 给 LLM 判断何时调用的用途描述（命中率关键） */
    @Column(nullable = false, length = 500)
    private String description;

    /** JSON Schema 字符串，描述参数；LLM 据此填参 */
    @Column(columnDefinition = "text")
    private String inputSchema;

    /** HTTP 方法：GET / POST */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String httpMethod = "GET";

    /** 端点模板，含占位符，如 https://api.example.com/orders/{orderId} */
    @Column(nullable = false, length = 1000)
    private String urlTemplate;

    /** 静态鉴权头（如 "Authorization: Bearer xxx"），明文存储（技术债，同 AiModelConfig.apiKey） */
    @Column(length = 1000)
    private String authHeader;

    /** 出站超时（毫秒） */
    @Column(nullable = false)
    @Builder.Default
    private Integer timeoutMs = 5000;

    /** 回灌 LLM 的响应体截断长度 */
    @Column(nullable = false)
    @Builder.Default
    private Integer maxResponseChars = 4000;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 写仓库**

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.AiTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AiToolRepository extends JpaRepository<AiTool, Long> {

    /** 按 id 集合取启用工具（skill 激活时用） */
    List<AiTool> findByIdInAndEnabledTrue(Collection<Long> ids);
}
```

- [ ] **Step 3: 编译确认**

Run: `cd backend && mvn -q -o test-compile`
Expected: BUILD SUCCESS（实体/仓库编译通过）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/AiTool.java backend/src/main/java/com/qingyu/care/repository/AiToolRepository.java
git commit -m "feat: 新增 AiTool 实体与仓库"
```

---

### Task A3: AiSkill 实体与仓库

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/entity/AiSkill.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/AiSkillRepository.java`

**Interfaces:**
- Produces: `AiSkill`（字段：id/name/description/content/toolIds(`Set<Long>` @ElementCollection)/enabled/时间戳，继承 `BaseTenantEntity`）；`AiSkillRepository.findByEnabledTrue()`。

- [ ] **Step 1: 写实体**

```java
package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AI Skill：Markdown 能力包。content 命中时注入系统提示，并激活其绑定的工具。
 */
@Entity
@Table(name = "ai_skill")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSkill extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 用途描述（本期文档用途，为未来命中触发预留） */
    @Column(length = 500)
    private String description;

    /** Markdown 正文：注入系统提示的 SOP/指令 */
    @Column(columnDefinition = "text")
    private String content;

    /** 绑定的 AiTool id 集合（元素集合，避开 join 表与租户过滤器纠缠） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ai_skill_tool", joinColumns = @JoinColumn(name = "skill_id"))
    @Column(name = "tool_id")
    @Builder.Default
    private Set<Long> toolIds = new LinkedHashSet<>();

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 写仓库**

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.AiSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiSkillRepository extends JpaRepository<AiSkill, Long> {

    /** 取当前应用启用的 skill（全量注入用） */
    List<AiSkill> findByEnabledTrue();
}
```

- [ ] **Step 3: 编译确认**

Run: `cd backend && mvn -q -o test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/AiSkill.java backend/src/main/java/com/qingyu/care/repository/AiSkillRepository.java
git commit -m "feat: 新增 AiSkill 实体与仓库（@ElementCollection 绑定工具）"
```

---

### Task A4: HTTP 执行器（可测缝）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/tool/HttpToolExecutor.java`
- Create: `backend/src/main/java/com/qingyu/care/service/tool/RestClientHttpToolExecutor.java`

**Interfaces:**
- Produces: `HttpToolExecutor.execute(String method, String url, String authHeader, String body, int timeoutMs)` 返回响应体 `String`；`RestClientHttpToolExecutor`（`@Component` bean）。
- Consumes: `SsrfGuard`（A1）。

- [ ] **Step 1: 写接口**

```java
package com.qingyu.care.service.tool;

/**
 * HTTP 执行缝：把「发请求」与 {@link HttpToolCallback} 的参数拼装/截断逻辑解耦，
 * 便于对 callback 做纯 Mockito 单测（stub 本接口即可，无需真发请求）。
 */
public interface HttpToolExecutor {

    /**
     * @param method     GET / POST
     * @param url        已填充占位符的完整 URL
     * @param authHeader 形如 "Authorization: Bearer xxx" 的整行头，可空
     * @param body       请求体（POST 用），可空
     * @param timeoutMs  超时
     * @return 响应体文本
     */
    String execute(String method, String url, String authHeader, String body, int timeoutMs);
}
```

- [ ] **Step 2: 写实现**

```java
package com.qingyu.care.service.tool;

import com.qingyu.care.config.SsrfGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 基于 RestClient 的 HTTP 执行器。出站前经 {@link SsrfGuard} 校验目标地址。
 *
 * @author ruan
 */
@Component
public class RestClientHttpToolExecutor implements HttpToolExecutor {

    @Override
    public String execute(String method, String url, String authHeader, String body, int timeoutMs) {
        SsrfGuard.assertAllowed(url);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        RestClient client = RestClient.builder().requestFactory(factory).build();

        RestClient.RequestBodySpec spec = client
                .method("POST".equalsIgnoreCase(method) ? org.springframework.http.HttpMethod.POST
                        : org.springframework.http.HttpMethod.GET)
                .uri(url);
        applyAuthHeader(spec, authHeader);
        if ("POST".equalsIgnoreCase(method) && body != null && !body.isBlank()) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        String resp = spec.retrieve().body(String.class);
        return resp == null ? "" : resp;
    }

    /** 把 "Header-Name: value" 整行头拆成键值加到请求上。 */
    private void applyAuthHeader(RestClient.RequestBodySpec spec, String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return;
        }
        int idx = authHeader.indexOf(':');
        if (idx > 0) {
            spec.header(authHeader.substring(0, idx).trim(), authHeader.substring(idx + 1).trim());
        } else {
            spec.header(HttpHeaders.AUTHORIZATION, authHeader.trim());
        }
    }
}
```

- [ ] **Step 3: 编译确认**

Run: `cd backend && mvn -q -o test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/tool/HttpToolExecutor.java backend/src/main/java/com/qingyu/care/service/tool/RestClientHttpToolExecutor.java
git commit -m "feat: 新增 HttpToolExecutor 缝与 RestClient 实现（出站经 SSRF 守卫）"
```

---

### Task A5: HttpToolCallback（实现 Spring AI ToolCallback）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/tool/HttpToolCallback.java`
- Test: `backend/src/test/java/com/qingyu/care/service/tool/HttpToolCallbackTest.java`

**Interfaces:**
- Consumes: `AiTool`（A2）、`HttpToolExecutor`（A4）、`com.fasterxml.jackson.databind.ObjectMapper`。
- Produces: `new HttpToolCallback(AiTool, HttpToolExecutor, ObjectMapper)` 实现 `org.springframework.ai.tool.ToolCallback`；`getToolDefinition()` 出 name/description/inputSchema；`call(String)` 填参→发 HTTP→截断。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AiTool tool() {
        return AiTool.builder()
                .name("query_order").description("查订单")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}")
                .httpMethod("GET").urlTemplate("http://8.8.8.8/orders/{orderId}")
                .authHeader("Authorization: Bearer k").timeoutMs(3000).maxResponseChars(10)
                .enabled(true).build();
    }

    @Test
    void definitionCarriesNameDescriptionSchema() {
        HttpToolCallback cb = new HttpToolCallback(tool(), (m, u, a, b, t) -> "", mapper);
        assertThat(cb.getToolDefinition().name()).isEqualTo("query_order");
        assertThat(cb.getToolDefinition().description()).isEqualTo("查订单");
        assertThat(cb.getToolDefinition().inputSchema()).contains("orderId");
    }

    @Test
    void callFillsUrlPlaceholderAndPassesAuthAndTimeout() {
        String[] captured = new String[4];
        HttpToolExecutor exec = (method, url, auth, body, timeout) -> {
            captured[0] = method; captured[1] = url; captured[2] = auth;
            captured[3] = String.valueOf(timeout);
            return "OK";
        };
        HttpToolCallback cb = new HttpToolCallback(tool(), exec, mapper);

        String result = cb.call("{\"orderId\":\"A123\"}");

        assertThat(captured[0]).isEqualTo("GET");
        assertThat(captured[1]).isEqualTo("http://8.8.8.8/orders/A123");
        assertThat(captured[2]).isEqualTo("Authorization: Bearer k");
        assertThat(captured[3]).isEqualTo("3000");
        assertThat(result).isEqualTo("OK");
    }

    @Test
    void callTruncatesResponseToMaxResponseChars() {
        HttpToolCallback cb = new HttpToolCallback(tool(), (m, u, a, b, t) -> "0123456789ABCDEF", mapper);
        String result = cb.call("{\"orderId\":\"A123\"}");
        assertThat(result).hasSize(10); // maxResponseChars=10
    }

    @Test
    void callReturnsErrorTextInsteadOfThrowing() {
        HttpToolCallback cb = new HttpToolCallback(tool(), (m, u, a, b, t) -> {
            throw new RuntimeException("boom");
        }, mapper);
        String result = cb.call("{\"orderId\":\"A123\"}");
        assertThat(result).contains("工具调用失败");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=HttpToolCallbackTest`
Expected: 编译失败 / `HttpToolCallback` 不存在。

- [ ] **Step 3: 写实现**

```java
package com.qingyu.care.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 把一个 {@link AiTool} 配置包装成 Spring AI 可调用的工具。构造期已绑定该租户的工具配置
 * （URL/authHeader 等），故执行时无需 ToolContext / TenantContext。
 *
 * @author ruan
 */
@Slf4j
public class HttpToolCallback implements ToolCallback {

    private final AiTool tool;
    private final HttpToolExecutor executor;
    private final ObjectMapper mapper;

    public HttpToolCallback(AiTool tool, HttpToolExecutor executor, ObjectMapper mapper) {
        this.tool = tool;
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String schema = (tool.getInputSchema() == null || tool.getInputSchema().isBlank())
                ? "{\"type\":\"object\",\"properties\":{}}"
                : tool.getInputSchema();
        return DefaultToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .inputSchema(schema)
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().build();
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> args = parseArgs(toolInput);
            String url = fillTemplate(tool.getUrlTemplate(), args);
            String body = "POST".equalsIgnoreCase(tool.getHttpMethod()) ? toolInput : null;
            // 审计：记录工具名与参数键（不记值，避免泄露）
            log.info("调用工具 {} 参数键={}", tool.getName(), args.keySet());
            String resp = executor.execute(tool.getHttpMethod(), url, tool.getAuthHeader(), body, tool.getTimeoutMs());
            return truncate(resp, tool.getMaxResponseChars());
        } catch (Exception e) {
            log.warn("工具 {} 调用失败: {}", tool.getName(), e.getMessage());
            return "工具调用失败: " + e.getMessage();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    private Map<String, Object> parseArgs(String toolInput) throws Exception {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(toolInput, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /** 把 {key} 占位符用 URL 编码后的参数值替换。 */
    private String fillTemplate(String template, Map<String, Object> args) {
        String url = template;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String enc = URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8);
            url = url.replace("{" + e.getKey() + "}", enc);
        }
        return url;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

> **实现说明（对执行者）**：若 `ToolMetadata.builder()` 或 `getToolMetadata()` 在 1.1.8 的确切签名与此不符，用 context7 查 `/spring-projects/spring-ai/v1.1.8` 的 `ToolCallback` / `ToolMetadata` 后微调；`getToolMetadata()` 很可能是接口默认方法，可整段删除本方法。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=HttpToolCallbackTest`
Expected: PASS（4 个用例）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/tool/HttpToolCallback.java backend/src/test/java/com/qingyu/care/service/tool/HttpToolCallbackTest.java
git commit -m "feat: HttpToolCallback 实现 Spring AI ToolCallback（填参/发HTTP/截断/审计）"
```

---

### Task A6: ChatToolService.resolve()

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/ChatToolService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ChatToolServiceResolveTest.java`

**Interfaces:**
- Consumes: `AiToolRepository`（A2）、`AiSkillRepository`（A3）、`HttpToolExecutor`（A4）、`ObjectMapper`。
- Produces: `ChatToolService.resolve()` 返回 `ChatToolService.ToolBundle`（`record ToolBundle(List<String> skillMarkdowns, List<ToolCallback> toolCallbacks)`，含 `empty()`/`isEmpty()`）。

- [ ] **Step 1: 写失败测试**

```java
package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatToolServiceResolveTest {

    @Mock AiToolRepository toolRepository;
    @Mock AiSkillRepository skillRepository;

    private ChatToolService service() {
        return new ChatToolService(toolRepository, skillRepository,
                (m, u, a, b, t) -> "", new ObjectMapper());
    }

    @Test
    void emptyWhenNoEnabledSkills() {
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of());
        assertThat(service().resolve().isEmpty()).isTrue();
    }

    @Test
    void collectsMarkdownAndBoundToolCallbacks() {
        AiSkill skill = AiSkill.builder().name("退货").content("处理退货的步骤...")
                .toolIds(Set.of(1L)).enabled(true).build();
        AiTool tool = AiTool.builder().name("query_order").description("查订单")
                .urlTemplate("http://8.8.8.8/o/{id}").httpMethod("GET")
                .timeoutMs(3000).maxResponseChars(100).enabled(true).build();
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(toolRepository.findByIdInAndEnabledTrue(Set.of(1L))).thenReturn(List.of(tool));

        ChatToolService.ToolBundle bundle = service().resolve();

        assertThat(bundle.skillMarkdowns()).containsExactly("处理退货的步骤...");
        assertThat(bundle.toolCallbacks()).hasSize(1);
        assertThat(bundle.toolCallbacks().get(0).getToolDefinition().name()).isEqualTo("query_order");
    }

    @Test
    void skipsSkillWithNoTools_stillInjectsMarkdown() {
        AiSkill skill = AiSkill.builder().name("礼貌用语").content("始终礼貌")
                .toolIds(Set.of()).enabled(true).build();
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));

        ChatToolService.ToolBundle bundle = service().resolve();

        assertThat(bundle.skillMarkdowns()).containsExactly("始终礼貌");
        assertThat(bundle.toolCallbacks()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ChatToolServiceResolveTest`
Expected: 编译失败 / `ChatToolService` 不存在。

- [ ] **Step 3: 写实现（本任务只含 resolve() + ToolBundle；CRUD 在 Phase B 追加）**

```java
package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.service.tool.HttpToolCallback;
import com.qingyu.care.service.tool.HttpToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具/Skill 运行时解析 + 管理 CRUD。合并设计稿的 ChatToolResolver，遵循 ModelRouterService
 * 一服务兼运行时与 CRUD 的先例。resolve() 须在请求线程调用（依赖 TenantContext 的应用隔离）。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatToolService {

    private final AiToolRepository toolRepository;
    private final AiSkillRepository skillRepository;
    private final HttpToolExecutor httpToolExecutor;
    private final ObjectMapper objectMapper;

    /** 运行时解析包：待注入系统提示的 skill 正文 + 待挂载的工具回调。 */
    public record ToolBundle(List<String> skillMarkdowns, List<ToolCallback> toolCallbacks) {
        public static ToolBundle empty() {
            return new ToolBundle(List.of(), List.of());
        }

        public boolean isEmpty() {
            return skillMarkdowns.isEmpty() && toolCallbacks.isEmpty();
        }
    }

    /**
     * 取当前应用 enabled 的 skill，收集正文 + 其绑定工具的并集，组装为 ToolBundle。
     * 工具只经 skill 激活；无 skill 时返回 empty（chat() 据此保持零回归）。
     */
    public ToolBundle resolve() {
        List<AiSkill> skills = skillRepository.findByEnabledTrue();
        if (skills.isEmpty()) {
            return ToolBundle.empty();
        }
        List<String> markdowns = new ArrayList<>();
        Set<Long> toolIds = new LinkedHashSet<>();
        for (AiSkill s : skills) {
            if (s.getContent() != null && !s.getContent().isBlank()) {
                markdowns.add(s.getContent());
            }
            if (s.getToolIds() != null) {
                toolIds.addAll(s.getToolIds());
            }
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        if (!toolIds.isEmpty()) {
            for (AiTool t : toolRepository.findByIdInAndEnabledTrue(toolIds)) {
                callbacks.add(new HttpToolCallback(t, httpToolExecutor, objectMapper));
            }
        }
        return new ToolBundle(markdowns, callbacks);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=ChatToolServiceResolveTest`
Expected: PASS（3 个用例）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatToolService.java backend/src/test/java/com/qingyu/care/service/ChatToolServiceResolveTest.java
git commit -m "feat: ChatToolService.resolve() 组装 skill 正文与工具回调"
```

---

### Task A7: 接入 ChatServiceImpl.chat()

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Test: `backend/src/test/java/com/qingyu/care/service/impl/ChatServiceSkillPromptTest.java`

**Interfaces:**
- Consumes: `ChatToolService`（A6）。
- Produces: 静态 `ChatServiceImpl.appendSkillMarkdowns(String systemPrompt, List<String> markdowns)`（package-private，纯函数）。

- [ ] **Step 1: 写失败测试（纯静态方法，免容器）**

```java
package com.qingyu.care.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceSkillPromptTest {

    @Test
    void appendsEachSkillMarkdownAfterBasePrompt() {
        String out = ChatServiceImpl.appendSkillMarkdowns("你是客服。", List.of("退货流程A", "改签流程B"));
        assertThat(out).startsWith("你是客服。");
        assertThat(out).contains("退货流程A").contains("改签流程B");
    }

    @Test
    void returnsPromptUnchangedWhenNoMarkdowns() {
        assertThat(ChatServiceImpl.appendSkillMarkdowns("你是客服。", List.of())).isEqualTo("你是客服。");
        assertThat(ChatServiceImpl.appendSkillMarkdowns("你是客服。", null)).isEqualTo("你是客服。");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ChatServiceSkillPromptTest`
Expected: 编译失败 / `appendSkillMarkdowns` 不存在。

- [ ] **Step 3: 加静态方法 + 改 buildMessages 签名 + chat() 接线 + 注入依赖**

3a. 在 `ChatServiceImpl` 加导入与字段：

```java
// 顶部 import
import com.qingyu.care.service.ChatToolService;
import org.springframework.ai.tool.ToolCallback;
```

在 `@RequiredArgsConstructor` 的 final 字段区（如 `configService` 后）追加：

```java
    private final ChatToolService chatToolService;
```

3b. 加静态纯方法（放在 `meterContentStream` 等静态方法附近）：

```java
    /**
     * 纯方法：把各 skill 的 Markdown 正文追加到系统提示末尾（分隔符包裹）。空/ null 原样返回。
     * static 免容器单测，同 meterContentStream 惯例。
     */
    static String appendSkillMarkdowns(String systemPrompt, java.util.List<String> markdowns) {
        if (markdowns == null || markdowns.isEmpty()) {
            return systemPrompt;
        }
        StringBuilder sb = new StringBuilder(systemPrompt);
        for (String md : markdowns) {
            sb.append("\n\n--- 技能指引 ---\n").append(md);
        }
        return sb.toString();
    }
```

3c. 改 `buildMessages` 签名，追加 `skillMarkdowns` 参数并在拼完 systemPrompt 后注入：

```java
    private List<Message> buildMessages(ChatSession session, String userMessage, String context,
                                        ChatStrategy strategy, java.util.List<String> skillMarkdowns) {
        List<Message> messages = new ArrayList<>();

        String systemTemplate = resolveSystemTemplate(strategy);
        String systemPrompt = systemTemplate.replace("{context}", context);
        systemPrompt = appendSkillMarkdowns(systemPrompt, skillMarkdowns);   // ← 新增
        messages.add(new SystemMessage(systemPrompt));
        // ...（历史消息与当前用户消息部分保持不变）
        List<ChatMessage> history = messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(session.getId());
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (msg.getRole() == MessageRole.USER) {
                messages.add(new UserMessage(msg.getContent()));
            } else if (msg.getRole() == MessageRole.ASSISTANT) {
                messages.add(new AssistantMessage(msg.getContent()));
            }
        }
        messages.add(new UserMessage(userMessage));
        return messages;
    }
```

3d. 在 `chat()` 里，把 `buildMessages(...)` 调用替换为解析 skill/工具后调用，并挂工具：

原来（约 line 150-159）：
```java
        String context = buildContext(relevantDocs);
        List<Message> messages = buildMessages(session, request.getMessage(), context, strategy);
        final List<Message> finalMessages = messages;
        org.springframework.ai.chat.model.ChatResponse aiResp =
                modelRouterService.callWithFallback(resolveModelScenario(), client ->
                        client.prompt().messages(finalMessages).call().chatResponse());
```

改为：
```java
        String context = buildContext(relevantDocs);
        // 解析当前应用的 skill/工具（请求线程内，依赖 TenantContext）
        ChatToolService.ToolBundle toolBundle = chatToolService.resolve();
        List<Message> messages = buildMessages(session, request.getMessage(), context, strategy,
                toolBundle.skillMarkdowns());
        final List<Message> finalMessages = messages;
        final List<ToolCallback> tools = toolBundle.toolCallbacks();
        org.springframework.ai.chat.model.ChatResponse aiResp =
                modelRouterService.callWithFallback(resolveModelScenario(), client -> {
                    var spec = client.prompt().messages(finalMessages);
                    if (!tools.isEmpty()) {
                        spec = spec.toolCallbacks(tools);   // 无工具时不挂载 → 与现状逐字节一致
                    }
                    return spec.call().chatResponse();
                });
        if (!tools.isEmpty()) {
            log.debug("本轮启用 {} 个工具；带工具调用时 usage 仅反映末轮，token 计量可能少计（已知局限）",
                    tools.size());
        }
```

3e. 改 `streamChat(...)` 里的 `buildMessages` 调用（约 line 212），传空列表保持流式不变：

```java
        List<Message> messages = buildMessages(session, request.getMessage(), context, strategy,
                java.util.List.of());
```

- [ ] **Step 4: 运行确认通过（新单测 + 既有 chat 相关单测不回归）**

Run: `cd backend && mvn -q test -Dtest=ChatServiceSkillPromptTest,ChatServiceStrategyResolveTest,MeterContentStreamTest`
Expected: PASS（新用例通过，既有 ChatServiceImpl 相关单测不受影响）。

- [ ] **Step 5: 全量单测确认零回归**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS（全部单元测试通过）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java backend/src/test/java/com/qingyu/care/service/impl/ChatServiceSkillPromptTest.java
git commit -m "feat: chat() 注入 skill 正文并挂载工具回调（无配置零回归）"
```

---

## Phase B — 管理面

### Task B1: AiTool CRUD + 控制器

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatToolService.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/AiToolController.java`

**Interfaces:**
- Consumes: `TenantOwnershipGuard`、`ResourceNotFoundException`。
- Produces: `ChatToolService`：`listTools()`/`getTool(Long)`/`saveTool(AiTool)`/`deleteTool(Long)`；`AiToolController` at `/v1/ai-tools`。

- [ ] **Step 1: 给 ChatToolService 加 AiTool CRUD**

在 `ChatToolService` 追加导入与方法：

```java
// import
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.exception.ResourceNotFoundException;
import org.springframework.transaction.annotation.Transactional;

// ===== AiTool CRUD =====

    public List<AiTool> listTools() {
        return toolRepository.findAll();
    }

    /**
     * 按 id 取工具。含明文 authHeader，用写级守卫（同 ModelRouterService.getById 对 apiKey 的处理）：
     * 越权按「不存在」处理，防枚举探测与跨租户读取凭据。
     */
    public AiTool getTool(Long id) {
        AiTool t = toolRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("工具", id.toString()));
        if (!TenantOwnershipGuard.mutableInCurrentContext(t.getTenantId(), t.getAppId())) {
            throw new ResourceNotFoundException("工具", id.toString());
        }
        return t;
    }

    @Transactional
    public AiTool saveTool(AiTool t) {
        return toolRepository.save(t);
    }

    @Transactional
    public void deleteTool(Long id) {
        AiTool t = getTool(id);
        toolRepository.delete(t);
    }
```

- [ ] **Step 2: 写控制器（仿 AiModelController）**

```java
package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.service.ChatToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** AI 工具（HTTP）管理 API */
@RestController
@RequestMapping("/v1/ai-tools")
@RequiredArgsConstructor
public class AiToolController {

    private final ChatToolService chatToolService;

    @GetMapping
    public ApiResponse<List<AiTool>> list() {
        return ApiResponse.success(chatToolService.listTools());
    }

    @GetMapping("/{id}")
    public ApiResponse<AiTool> getById(@PathVariable Long id) {
        return ApiResponse.success(chatToolService.getTool(id));
    }

    @AuditLog(action = "TOOL_CREATE", targetType = "AiTool")
    @PostMapping
    public ApiResponse<AiTool> create(@RequestBody AiTool tool) {
        tool.setId(null);
        return ApiResponse.success("工具已创建", chatToolService.saveTool(tool));
    }

    @AuditLog(action = "TOOL_UPDATE", targetType = "AiTool")
    @PutMapping("/{id}")
    public ApiResponse<AiTool> update(@PathVariable Long id, @RequestBody AiTool tool) {
        AiTool existing = chatToolService.getTool(id);
        if (tool.getName() != null) existing.setName(tool.getName());
        if (tool.getDescription() != null) existing.setDescription(tool.getDescription());
        if (tool.getInputSchema() != null) existing.setInputSchema(tool.getInputSchema());
        if (tool.getHttpMethod() != null) existing.setHttpMethod(tool.getHttpMethod());
        if (tool.getUrlTemplate() != null) existing.setUrlTemplate(tool.getUrlTemplate());
        if (tool.getAuthHeader() != null && !tool.getAuthHeader().isEmpty()) existing.setAuthHeader(tool.getAuthHeader());
        if (tool.getTimeoutMs() != null) existing.setTimeoutMs(tool.getTimeoutMs());
        if (tool.getMaxResponseChars() != null) existing.setMaxResponseChars(tool.getMaxResponseChars());
        if (tool.getEnabled() != null) existing.setEnabled(tool.getEnabled());
        return ApiResponse.success("工具已更新", chatToolService.saveTool(existing));
    }

    @AuditLog(action = "TOOL_DELETE", targetType = "AiTool")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        chatToolService.deleteTool(id);
        return ApiResponse.success("工具已删除", null);
    }
}
```

- [ ] **Step 3: 编译 + 全量单测确认**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatToolService.java backend/src/main/java/com/qingyu/care/controller/AiToolController.java
git commit -m "feat: AiTool CRUD 与 AiToolController（归属守卫 + 审计）"
```

---

### Task B2: AiSkill CRUD + 控制器

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatToolService.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/AiSkillController.java`

**Interfaces:**
- Produces: `ChatToolService`：`listSkills()`/`getSkill(Long)`/`saveSkill(AiSkill)`/`deleteSkill(Long)`；`AiSkillController` at `/v1/ai-skills`。

- [ ] **Step 1: 给 ChatToolService 加 AiSkill CRUD**

```java
// ===== AiSkill CRUD =====

    public List<AiSkill> listSkills() {
        return skillRepository.findAll();
    }

    /** 按 id 取 skill（无密钥，用读级守卫 belongsToCurrentContext）。 */
    public AiSkill getSkill(Long id) {
        AiSkill s = skillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("技能", id.toString()));
        if (!TenantOwnershipGuard.belongsToCurrentContext(s.getTenantId(), s.getAppId())) {
            throw new ResourceNotFoundException("技能", id.toString());
        }
        return s;
    }

    @Transactional
    public AiSkill saveSkill(AiSkill s) {
        return skillRepository.save(s);
    }

    @Transactional
    public void deleteSkill(Long id) {
        AiSkill s = getSkill(id);
        skillRepository.delete(s);
    }
```

> 追加导入：`import com.qingyu.care.entity.AiSkill;`（若尚未导入）。

- [ ] **Step 2: 写控制器**

```java
package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.service.ChatToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** AI Skill（能力包）管理 API */
@RestController
@RequestMapping("/v1/ai-skills")
@RequiredArgsConstructor
public class AiSkillController {

    private final ChatToolService chatToolService;

    @GetMapping
    public ApiResponse<List<AiSkill>> list() {
        return ApiResponse.success(chatToolService.listSkills());
    }

    @GetMapping("/{id}")
    public ApiResponse<AiSkill> getById(@PathVariable Long id) {
        return ApiResponse.success(chatToolService.getSkill(id));
    }

    @AuditLog(action = "SKILL_CREATE", targetType = "AiSkill")
    @PostMapping
    public ApiResponse<AiSkill> create(@RequestBody AiSkill skill) {
        skill.setId(null);
        return ApiResponse.success("技能已创建", chatToolService.saveSkill(skill));
    }

    @AuditLog(action = "SKILL_UPDATE", targetType = "AiSkill")
    @PutMapping("/{id}")
    public ApiResponse<AiSkill> update(@PathVariable Long id, @RequestBody AiSkill skill) {
        AiSkill existing = chatToolService.getSkill(id);
        if (skill.getName() != null) existing.setName(skill.getName());
        if (skill.getDescription() != null) existing.setDescription(skill.getDescription());
        if (skill.getContent() != null) existing.setContent(skill.getContent());
        if (skill.getToolIds() != null) existing.setToolIds(skill.getToolIds());
        if (skill.getEnabled() != null) existing.setEnabled(skill.getEnabled());
        return ApiResponse.success("技能已更新", chatToolService.saveSkill(existing));
    }

    @AuditLog(action = "SKILL_DELETE", targetType = "AiSkill")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        chatToolService.deleteSkill(id);
        return ApiResponse.success("技能已删除", null);
    }
}
```

- [ ] **Step 3: 编译 + 全量单测确认**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatToolService.java backend/src/main/java/com/qingyu/care/controller/AiSkillController.java
git commit -m "feat: AiSkill CRUD 与 AiSkillController（归属守卫 + 审计）"
```

---

### Task B3: 前端 API 客户端

**Files:**
- Create: `frontend/src/api/chat-tool.ts`

**Interfaces:**
- Produces: `aiToolService`、`aiSkillService`，及 `AiTool`/`AiSkill` 类型。

- [ ] **Step 1: 写 API 客户端**

```ts
import api from './client';
import type { ApiResponse } from '../types';

export interface AiTool {
    id: number;
    name: string;
    description: string;
    inputSchema: string | null;
    httpMethod: string;
    urlTemplate: string;
    authHeader: string | null;
    timeoutMs: number;
    maxResponseChars: number;
    enabled: boolean;
    createdAt: string;
    updatedAt: string;
}

export interface AiSkill {
    id: number;
    name: string;
    description: string | null;
    content: string | null;
    toolIds: number[];
    enabled: boolean;
    createdAt: string;
    updatedAt: string;
}

export const aiToolService = {
    list: (): Promise<ApiResponse<AiTool[]>> => api.get('/ai-tools'),
    getById: (id: number): Promise<ApiResponse<AiTool>> => api.get(`/ai-tools/${id}`),
    create: (data: Partial<AiTool>): Promise<ApiResponse<AiTool>> => api.post('/ai-tools', data),
    update: (id: number, data: Partial<AiTool>): Promise<ApiResponse<AiTool>> => api.put(`/ai-tools/${id}`, data),
    remove: (id: number): Promise<ApiResponse<void>> => api.delete(`/ai-tools/${id}`),
};

export const aiSkillService = {
    list: (): Promise<ApiResponse<AiSkill[]>> => api.get('/ai-skills'),
    getById: (id: number): Promise<ApiResponse<AiSkill>> => api.get(`/ai-skills/${id}`),
    create: (data: Partial<AiSkill>): Promise<ApiResponse<AiSkill>> => api.post('/ai-skills', data),
    update: (id: number, data: Partial<AiSkill>): Promise<ApiResponse<AiSkill>> => api.put(`/ai-skills/${id}`, data),
    remove: (id: number): Promise<ApiResponse<void>> => api.delete(`/ai-skills/${id}`),
};
```

- [ ] **Step 2: 编译确认**

Run: `cd frontend && pnpm build`
Expected: 构建成功（tsc 无类型错误）。

- [ ] **Step 3: 提交**

```bash
git add frontend/src/api/chat-tool.ts
git commit -m "feat(frontend): 工具/技能 API 客户端"
```

---

### Task B4: 工具配置页 + 路由 + 导航

**Files:**
- Create: `frontend/src/pages/ToolConfigPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`

**Interfaces:**
- Consumes: `aiToolService`（B3）。

- [ ] **Step 1: 写工具配置页（仿 ModelConfigPage 结构）**

```tsx
import React, { useState, useEffect } from 'react';
import { Wrench, Plus, Edit2, Trash2, Globe } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
    Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import {
    Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { aiToolService } from '@/api/chat-tool';
import type { AiTool } from '@/api/chat-tool';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const ToolConfigPage: React.FC = () => {
    const [tools, setTools] = useState<AiTool[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editing, setEditing] = useState<Partial<AiTool> | null>(null);
    const { toast } = useToast();

    const fetchTools = async () => {
        try {
            setIsLoading(true);
            const res = await aiToolService.list();
            if (res.code === 200) setTools(res.data);
        } catch (e: any) {
            toast({ variant: 'destructive', title: '获取工具列表失败', description: e.message });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => { fetchTools(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

    const handleToggle = async (t: AiTool) => {
        try {
            const res = await aiToolService.update(t.id, { enabled: !t.enabled });
            if (res.code === 200) { toast({ title: t.enabled ? '工具已禁用' : '工具已启用' }); fetchTools(); }
        } catch (e: any) { toast({ variant: 'destructive', title: '操作失败', description: e.message }); }
    };

    const handleSave = async () => {
        if (!editing?.name || !editing?.description || !editing?.urlTemplate) {
            toast({ variant: 'destructive', title: '请填写名称、描述与 URL 模板' });
            return;
        }
        try {
            const res = editing.id
                ? await aiToolService.update(editing.id, editing)
                : await aiToolService.create(editing);
            if (res.code === 200) {
                toast({ title: editing.id ? '工具已更新' : '工具已创建' });
                setIsDialogOpen(false); fetchTools();
            }
        } catch (e: any) { toast({ variant: 'destructive', title: '保存失败', description: e.message }); }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('确定要删除该工具吗？绑定它的技能将失去该工具。')) return;
        try {
            const res = await aiToolService.remove(id);
            if (res.code === 200) { toast({ title: '工具已删除' }); fetchTools(); }
        } catch (e: any) { toast({ variant: 'destructive', title: '删除失败', description: e.message }); }
    };

    const openCreate = () => {
        setEditing({
            name: '', description: '', inputSchema: '{\n  "type": "object",\n  "properties": {}\n}',
            httpMethod: 'GET', urlTemplate: '', authHeader: '', timeoutMs: 5000, maxResponseChars: 4000, enabled: true,
        });
        setIsDialogOpen(true);
    };

    const openEdit = (t: AiTool) => { setEditing({ ...t, authHeader: '' }); setIsDialogOpen(true); };

    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-6xl mx-auto space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-2"><Wrench className="w-6 h-6" />工具（HTTP）</h1>
                        <p className="text-muted-foreground text-sm mt-1">配置 AI 可调用的业务查询接口，模型只填参数不选 URL</p>
                    </div>
                    <Button onClick={openCreate}><Plus className="w-4 h-4 mr-2" />添加工具</Button>
                </div>

                {isLoading ? (
                    <div className="flex items-center justify-center py-20">
                        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
                    </div>
                ) : tools.length === 0 ? (
                    <Card className="border-dashed py-12">
                        <CardContent className="flex flex-col items-center justify-center text-muted-foreground">
                            <Wrench className="w-12 h-12 mb-4 opacity-20" /><p>尚未配置任何工具</p>
                        </CardContent>
                    </Card>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {tools.map((t) => (
                            <Card key={t.id} className={cn('flex flex-col hover:shadow-md', !t.enabled && 'opacity-60')}>
                                <CardHeader className="pb-3">
                                    <div className="flex items-center justify-between mb-2">
                                        <Badge variant="secondary" className="font-mono text-[10px]">{t.httpMethod}</Badge>
                                        <Switch checked={t.enabled} onCheckedChange={() => handleToggle(t)} />
                                    </div>
                                    <CardTitle className="text-lg font-mono">{t.name}</CardTitle>
                                    <p className="text-xs text-muted-foreground line-clamp-2 mt-1">{t.description}</p>
                                </CardHeader>
                                <CardContent className="flex-1 py-2">
                                    <div className="text-[10px] text-muted-foreground/60 flex items-center gap-1 font-mono truncate">
                                        <Globe className="w-3 h-3" />{t.urlTemplate}
                                    </div>
                                </CardContent>
                                <CardFooter className="pt-2 flex justify-end gap-2 border-t mt-auto">
                                    <Button variant="outline" size="sm" onClick={() => openEdit(t)}><Edit2 className="w-3 h-3" /></Button>
                                    <Button variant="outline" size="sm" className="text-red-500 hover:bg-red-50" onClick={() => handleDelete(t.id)}><Trash2 className="w-3 h-3" /></Button>
                                </CardFooter>
                            </Card>
                        ))}
                    </div>
                )}
            </div>

            <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                <DialogContent className="sm:max-w-[560px]">
                    <DialogHeader>
                        <DialogTitle>{editing?.id ? '编辑工具' : '添加工具'}</DialogTitle>
                        <DialogDescription>URL 模板中的 {'{参数名}'} 会被模型填入的参数替换；鉴权头保存后不回显。</DialogDescription>
                    </DialogHeader>
                    <div className="grid gap-3 py-2 max-h-[60vh] overflow-y-auto">
                        <label className="text-sm font-medium">工具名（英文标识符）</label>
                        <Input value={editing?.name || ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder="query_order" />
                        <label className="text-sm font-medium">描述（给模型判断何时调用）</label>
                        <Input value={editing?.description || ''} onChange={(e) => setEditing({ ...editing, description: e.target.value })} placeholder="根据订单号查询订单状态" />
                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <label className="text-sm font-medium">方法</label>
                                <Select value={editing?.httpMethod || 'GET'} onValueChange={(v) => setEditing({ ...editing, httpMethod: v })}>
                                    <SelectTrigger><SelectValue /></SelectTrigger>
                                    <SelectContent><SelectItem value="GET">GET</SelectItem><SelectItem value="POST">POST</SelectItem></SelectContent>
                                </Select>
                            </div>
                            <div>
                                <label className="text-sm font-medium">超时(ms)</label>
                                <Input type="number" value={editing?.timeoutMs ?? 5000} onChange={(e) => setEditing({ ...editing, timeoutMs: parseInt(e.target.value) })} />
                            </div>
                        </div>
                        <label className="text-sm font-medium">URL 模板</label>
                        <Input value={editing?.urlTemplate || ''} onChange={(e) => setEditing({ ...editing, urlTemplate: e.target.value })} placeholder="https://api.example.com/orders/{orderId}" />
                        <label className="text-sm font-medium">参数 JSON Schema</label>
                        <textarea className="w-full min-h-[120px] rounded-md border px-3 py-2 text-sm font-mono" value={editing?.inputSchema || ''} onChange={(e) => setEditing({ ...editing, inputSchema: e.target.value })} />
                        <label className="text-sm font-medium">鉴权头（可选，如 Authorization: Bearer xxx）</label>
                        <Input type="password" value={editing?.authHeader || ''} onChange={(e) => setEditing({ ...editing, authHeader: e.target.value })} placeholder={editing?.id ? '留空表示不修改' : 'Authorization: Bearer ...'} />
                        <label className="text-sm font-medium">响应截断长度</label>
                        <Input type="number" value={editing?.maxResponseChars ?? 4000} onChange={(e) => setEditing({ ...editing, maxResponseChars: parseInt(e.target.value) })} />
                    </div>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => setIsDialogOpen(false)}>取消</Button>
                        <Button onClick={handleSave}>保存</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default ToolConfigPage;
```

- [ ] **Step 2: App.tsx 加路由**

在 import 区加：
```tsx
import ToolConfigPage from './pages/ToolConfigPage';
```
在 `<Route path="ai-models" .../>` 之后加：
```tsx
          <Route path="ai-tools" element={<RequireApp><ToolConfigPage /></RequireApp>} />
```

- [ ] **Step 3: MainLayout.tsx 加导航项**

在 `lucide-react` 导入里补 `Wrench`；在「AI 模型」组 items 里（`转人工规则` 之后）加：
```tsx
                    { label: '工具', path: '/ai-tools', icon: Wrench },
```

- [ ] **Step 4: 构建 + lint 确认**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建成功，lint 无错误。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/pages/ToolConfigPage.tsx frontend/src/App.tsx frontend/src/layouts/MainLayout.tsx
git commit -m "feat(frontend): 工具配置页 + 路由与导航"
```

---

### Task B5: 技能配置页 + 路由 + 导航

**Files:**
- Create: `frontend/src/pages/SkillConfigPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`

**Interfaces:**
- Consumes: `aiSkillService`、`aiToolService`（B3）。

- [ ] **Step 1: 写技能配置页（含勾选绑定工具）**

```tsx
import React, { useState, useEffect } from 'react';
import { Sparkles, Plus, Edit2, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
    Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { aiSkillService, aiToolService } from '@/api/chat-tool';
import type { AiSkill, AiTool } from '@/api/chat-tool';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';

const SkillConfigPage: React.FC = () => {
    const [skills, setSkills] = useState<AiSkill[]>([]);
    const [tools, setTools] = useState<AiTool[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isDialogOpen, setIsDialogOpen] = useState(false);
    const [editing, setEditing] = useState<Partial<AiSkill> | null>(null);
    const { toast } = useToast();

    const fetchAll = async () => {
        try {
            setIsLoading(true);
            const [s, t] = await Promise.all([aiSkillService.list(), aiToolService.list()]);
            if (s.code === 200) setSkills(s.data);
            if (t.code === 200) setTools(t.data);
        } catch (e: any) {
            toast({ variant: 'destructive', title: '加载失败', description: e.message });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => { fetchAll(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

    const handleToggle = async (s: AiSkill) => {
        try {
            const res = await aiSkillService.update(s.id, { enabled: !s.enabled });
            if (res.code === 200) { toast({ title: s.enabled ? '技能已禁用' : '技能已启用' }); fetchAll(); }
        } catch (e: any) { toast({ variant: 'destructive', title: '操作失败', description: e.message }); }
    };

    const handleSave = async () => {
        if (!editing?.name || !editing?.content) {
            toast({ variant: 'destructive', title: '请填写名称与正文' });
            return;
        }
        try {
            const res = editing.id
                ? await aiSkillService.update(editing.id, editing)
                : await aiSkillService.create(editing);
            if (res.code === 200) {
                toast({ title: editing.id ? '技能已更新' : '技能已创建' });
                setIsDialogOpen(false); fetchAll();
            }
        } catch (e: any) { toast({ variant: 'destructive', title: '保存失败', description: e.message }); }
    };

    const handleDelete = async (id: number) => {
        if (!confirm('确定要删除该技能吗？')) return;
        try {
            const res = await aiSkillService.remove(id);
            if (res.code === 200) { toast({ title: '技能已删除' }); fetchAll(); }
        } catch (e: any) { toast({ variant: 'destructive', title: '删除失败', description: e.message }); }
    };

    const openCreate = () => { setEditing({ name: '', description: '', content: '', toolIds: [], enabled: true }); setIsDialogOpen(true); };
    const openEdit = (s: AiSkill) => { setEditing({ ...s }); setIsDialogOpen(true); };

    const toggleTool = (toolId: number) => {
        const cur = editing?.toolIds || [];
        const next = cur.includes(toolId) ? cur.filter((x) => x !== toolId) : [...cur, toolId];
        setEditing({ ...editing, toolIds: next });
    };

    return (
        <div className="p-8 h-full overflow-y-auto">
            <div className="max-w-6xl mx-auto space-y-6">
                <div className="flex items-center justify-between">
                    <div>
                        <h1 className="text-2xl font-bold flex items-center gap-2"><Sparkles className="w-6 h-6" />技能（Skill）</h1>
                        <p className="text-muted-foreground text-sm mt-1">Markdown 能力包，正文注入系统提示并激活其绑定的工具（全量注入）</p>
                    </div>
                    <Button onClick={openCreate}><Plus className="w-4 h-4 mr-2" />添加技能</Button>
                </div>

                {isLoading ? (
                    <div className="flex items-center justify-center py-20">
                        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
                    </div>
                ) : skills.length === 0 ? (
                    <Card className="border-dashed py-12">
                        <CardContent className="flex flex-col items-center justify-center text-muted-foreground">
                            <Sparkles className="w-12 h-12 mb-4 opacity-20" /><p>尚未配置任何技能</p>
                        </CardContent>
                    </Card>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {skills.map((s) => (
                            <Card key={s.id} className={cn('flex flex-col hover:shadow-md', !s.enabled && 'opacity-60')}>
                                <CardHeader className="pb-3">
                                    <div className="flex items-center justify-between mb-2">
                                        <Badge variant="secondary" className="text-[10px]">{(s.toolIds?.length || 0)} 个工具</Badge>
                                        <Switch checked={s.enabled} onCheckedChange={() => handleToggle(s)} />
                                    </div>
                                    <CardTitle className="text-lg">{s.name}</CardTitle>
                                    <p className="text-xs text-muted-foreground line-clamp-2 mt-1">{s.description || '无描述'}</p>
                                </CardHeader>
                                <CardContent className="flex-1 py-2">
                                    <p className="text-[11px] text-muted-foreground/70 line-clamp-3 whitespace-pre-wrap">{s.content}</p>
                                </CardContent>
                                <CardFooter className="pt-2 flex justify-end gap-2 border-t mt-auto">
                                    <Button variant="outline" size="sm" onClick={() => openEdit(s)}><Edit2 className="w-3 h-3" /></Button>
                                    <Button variant="outline" size="sm" className="text-red-500 hover:bg-red-50" onClick={() => handleDelete(s.id)}><Trash2 className="w-3 h-3" /></Button>
                                </CardFooter>
                            </Card>
                        ))}
                    </div>
                )}
            </div>

            <Dialog open={isDialogOpen} onOpenChange={setIsDialogOpen}>
                <DialogContent className="sm:max-w-[560px]">
                    <DialogHeader>
                        <DialogTitle>{editing?.id ? '编辑技能' : '添加技能'}</DialogTitle>
                        <DialogDescription>正文用 Markdown 描述处理某类场景的步骤；勾选本技能激活的工具。</DialogDescription>
                    </DialogHeader>
                    <div className="grid gap-3 py-2 max-h-[60vh] overflow-y-auto">
                        <label className="text-sm font-medium">名称</label>
                        <Input value={editing?.name || ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder="退货处理" />
                        <label className="text-sm font-medium">描述（可选）</label>
                        <Input value={editing?.description || ''} onChange={(e) => setEditing({ ...editing, description: e.target.value })} />
                        <label className="text-sm font-medium">正文（Markdown）</label>
                        <textarea className="w-full min-h-[160px] rounded-md border px-3 py-2 text-sm" value={editing?.content || ''} onChange={(e) => setEditing({ ...editing, content: e.target.value })} placeholder="当用户咨询退货时：\n1. 先用 query_order 查询订单状态\n2. ..." />
                        <label className="text-sm font-medium">绑定工具</label>
                        <div className="border rounded-md p-3 space-y-2 max-h-[140px] overflow-y-auto">
                            {tools.length === 0 ? (
                                <p className="text-xs text-muted-foreground">尚无工具，请先到「工具」页创建</p>
                            ) : tools.map((t) => (
                                <label key={t.id} className="flex items-center gap-2 text-sm cursor-pointer">
                                    <input type="checkbox" checked={(editing?.toolIds || []).includes(t.id)} onChange={() => toggleTool(t.id)} />
                                    <span className="font-mono">{t.name}</span>
                                    <span className="text-muted-foreground text-xs truncate">{t.description}</span>
                                </label>
                            ))}
                        </div>
                    </div>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => setIsDialogOpen(false)}>取消</Button>
                        <Button onClick={handleSave}>保存</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default SkillConfigPage;
```

- [ ] **Step 2: App.tsx 加路由**

import 区加：
```tsx
import SkillConfigPage from './pages/SkillConfigPage';
```
`<Route path="ai-tools" .../>` 之后加：
```tsx
          <Route path="ai-skills" element={<RequireApp><SkillConfigPage /></RequireApp>} />
```

- [ ] **Step 3: MainLayout.tsx 加导航项**

在 `lucide-react` 导入里补 `Sparkles`；在「AI 模型」组 items 里（`工具` 之后）加：
```tsx
                    { label: '技能', path: '/ai-skills', icon: Sparkles },
```

- [ ] **Step 4: 构建 + lint 确认**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建成功，lint 无错误。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/pages/SkillConfigPage.tsx frontend/src/App.tsx frontend/src/layouts/MainLayout.tsx
git commit -m "feat(frontend): 技能配置页（绑定工具）+ 路由与导航"
```

---

## Phase C — 收尾

### Task C1: AiSkill @ElementCollection 持久化 IT

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/AiSkillPersistenceIT.java`

**Interfaces:**
- Consumes: `AbstractPostgresIT`、`AiSkillRepository`、`AiToolRepository`。

> 说明：本 IT 只验证 `AiSkill.toolIds`（`@ElementCollection`）在真实 pgvector 容器里持久化+回读正确——这是本特性唯一非平凡的 JPA 映射。工具/skill 的**应用级过滤隔离**由既有多租户测试网覆盖，不在此重复；带 LLM 的端到端工具循环由 `HttpToolCallbackTest` + Spring AI 框架保证，不做真模型 IT（沿用重 IT 从简的既定取舍）。

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 AiSkill.toolIds（@ElementCollection）在真实 Postgres 容器持久化+回读正确。
 *
 * @author ruan
 */
class AiSkillPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private AiSkillRepository skillRepository;
    @Autowired
    private AiToolRepository toolRepository;

    @Test
    void skillToolIdsRoundTrip() {
        AiTool t = toolRepository.saveAndFlush(AiTool.builder()
                .name("query_order").description("查订单")
                .urlTemplate("https://api.example.com/o/{id}").httpMethod("GET")
                .timeoutMs(5000).maxResponseChars(4000).enabled(true).build());

        AiSkill saved = skillRepository.saveAndFlush(AiSkill.builder()
                .name("退货").content("处理退货...").toolIds(Set.of(t.getId()))
                .enabled(true).build());

        AiSkill reloaded = skillRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getToolIds()).containsExactly(t.getId());
        assertThat(reloaded.getContent()).isEqualTo("处理退货...");
    }

    @Test
    void findByEnabledTrueReturnsEnabledSkill() {
        skillRepository.saveAndFlush(AiSkill.builder()
                .name("礼貌").content("始终礼貌").toolIds(Set.of()).enabled(true).build());
        assertThat(skillRepository.findByEnabledTrue())
                .anyMatch(s -> "始终礼貌".equals(s.getContent()));
    }
}
```

- [ ] **Step 2: 运行 IT（需 Docker Desktop）**

Run: `cd backend && mvn -q verify -Dit.test=AiSkillPersistenceIT`
Expected: PASS（2 个用例）。

- [ ] **Step 3: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/AiSkillPersistenceIT.java
git commit -m "test: AiSkill @ElementCollection 持久化 IT"
```

---

### Task C2: 全量回归 + 文档收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-07-24-chat-tool-calling-design.md`（仅当实现中 API 与 §11 待核实项有出入时更新）

- [ ] **Step 1: 后端全量单测**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 2: 后端集成测试（需 Docker）**

Run: `cd backend && mvn -q verify`
Expected: BUILD SUCCESS（含 `AiSkillPersistenceIT` 与既有 IT，无回归）。

- [ ] **Step 3: 前端构建 + lint**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建成功，lint 无错误。

- [ ] **Step 4: 若实现中 `ToolCallback`/`ToolMetadata`/`DefaultToolDefinition` 的实际签名与设计稿 §11 不同，更新设计稿对应句，并提交**

```bash
git add docs/superpowers/specs/2026-07-24-chat-tool-calling-design.md
git commit -m "docs: 按实现校正工具调用设计稿 Spring AI API 细节"
```

（若无出入，跳过本步。）

---

## Self-Review

**1. Spec coverage（对照设计稿逐节）**
- §3.1 AiTool → Task A2 ✓；§3.2 AiSkill（含 @ElementCollection toolIds）→ A3 ✓
- §4.1 HttpToolCallback → A5 ✓；§4.2 ChatToolService.resolve()/ToolBundle → A6 ✓
- §4.3 注入点（buildMessages 加 skillMarkdowns、chat() 挂 toolCallbacks、streamChat 传空、零回归）→ A7 ✓
- §5.1 SSRF 守卫 → A1 + A4（执行器出站前调用）✓；§5.2 超时/截断 → A2 字段 + A4/A5 ✓；§5.3 审计 → A5（HttpToolCallback 日志）+ B1/B2（@AuditLog）✓；§5.4 明文债 → Global Constraints 标注 ✓
- §6 计量少计日志 → A7 step 3d ✓
- §7 控制器 + 前端页 → B1/B2 + B4/B5 ✓
- §8 单测（SsrfGuard/HttpToolCallback/ChatToolService/系统提示）→ A1/A5/A6/A7 ✓；IT（此处为持久化 IT，非桩模型 IT——见 C1 说明的取舍调整）→ C1 ✓
- §9 分期 A/B/C → 三阶段 ✓
- §10 延后项 → 均未纳入任务 ✓

**2. Placeholder scan：** 无 TBD/TODO；A5 的「实现说明」是对 1.1.8 API 的核实指引，非占位（已给出完整可编译实现，仅提示如签名不符则微调）。

**3. Type consistency：**
- `ChatToolService.ToolBundle(List<String> skillMarkdowns, List<ToolCallback> toolCallbacks)` 在 A6 定义、A7 消费，字段访问器 `skillMarkdowns()`/`toolCallbacks()` 一致 ✓
- `HttpToolExecutor.execute(String,String,String,String,int)` 在 A4 定义、A5 stub、A6/实现调用，签名一致 ✓
- `HttpToolCallback(AiTool, HttpToolExecutor, ObjectMapper)` 构造签名 A5 定义、A6 使用一致 ✓
- `AiToolRepository.findByIdInAndEnabledTrue(Collection<Long>)`、`AiSkillRepository.findByEnabledTrue()` 定义与调用一致 ✓
- 前端 `aiToolService`/`aiSkillService` 方法名（list/getById/create/update/remove）B3 定义、B4/B5 使用一致 ✓

**调整记录：** 设计稿 §8 原写「桩 ChatModel IT」；因 `chat()` 的模型由 `ModelRouterService` 内部按 `AiModelConfig` 现场构造（非注入的 ChatModel bean），真端到端需 WireMock 模拟 OpenAI 工具协议，重且脆。改为 C1 的持久化 IT + 强单测覆盖风险逻辑；端到端留作后续（若需要）。此偏差已在 C1 说明标注。

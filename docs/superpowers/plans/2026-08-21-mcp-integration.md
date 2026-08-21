# MCP 接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 租户在管理后台登记远端 MCP server、同步其工具清单、逐个标注只读并勾进 skill，对话时由 LLM 调用。

**Architecture:** 独立 `mcp_server` / `mcp_tool` 两张表（不复用 `ai_tool`）。工具清单落库，`ChatToolService.resolve()` 只读数据库、不连远端；连接在 LLM 真正调用工具的那一刻建立、用完即弃。协议细节全部关在 `service/mcp/McpConnectionFactory` 后面，对上只暴露 `ToolCallback`。

**Tech Stack:** Spring Boot 4.1 / Spring AI 2.0 / `org.springframework.ai:spring-ai-mcp` + `mcp-spring-webflux` / MCP Java SDK 2.0.0 / PostgreSQL / React 19 + Vite

**Spec:** `docs/superpowers/specs/2026-08-21-mcp-integration-design.md`

## Global Constraints

- **JDK 17**，后端全部代码注释与 commit 信息用**简体中文**，新建类的 javadoc 带 `@author ruan`。
- **分支 `feat/mcp-integration` 已建**，spec 已提交（`a29fd0d`、`fa7cd42`）。不要在 `master` 上直接改。
- **依赖只加 `org.springframework.ai:spring-ai-mcp` 与 `org.springframework.ai:mcp-spring-webflux`**，版本由现有 `spring-ai-bom` 托管，**不写 `<version>`**。
- ⚠️ **绝不能引 `spring-ai-starter-mcp-client*`**：starter 带 `McpClientAutoConfiguration`，按静态 yaml 建连接，与本设计冲突。
- ⚠️ **绝不能用 httpclient 版传输**：它基于 JDK `HttpClient`，不支持自定义 DNS 解析，SSRF 钉 IP 那层会静默失效。只用 `WebClientStreamableHttpTransport` / `WebFluxSseClientTransport`。
- 单元测试用纯 Mockito（`@ExtendWith(MockitoExtension.class)`），**不启 Spring 容器、不需要 Docker**，文件名不以 `IT` 结尾。集成测试类名以 `IT` 结尾、放 `it/` 包、继承 `support/AbstractPostgresIT`。
- 前端所有写操作必须显式判 `if (res.code !== 200) throw new Error(res.message)`——业务异常是「HTTP 200 + body.code」约定，axios 不会 reject。
- 跑 `mvn verify` 前必须先停掉本机在跑的 dev 后端（:8080），否则 `WebhookDeliveryIT` 必红。

## MCP SDK API 速查（已用 javap 核实，2.0.0）

写代码时照这个来，不要凭印象：

```java
McpSyncClient client = McpClient.sync(transport).build();
client.initialize();
List<McpSchema.Tool> tools = client.listTools().tools();
McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(name, argsMap));
client.closeGracefully();
```

| 成员 | 返回类型 | 陷阱 |
|---|---|---|
| `Tool.name()` | `String` | — |
| `Tool.description()` | `String` | 可能 null |
| `Tool.inputSchema()` | **`Map<String,Object>`** | ⚠️ **不是 String**，落库前必须序列化成 JSON 文本 |
| `Tool.annotations()` | `McpSchema.ToolAnnotations` | ⚠️ **可能整个为 null** |
| `ToolAnnotations.readOnlyHint()` | **`Boolean`** | ⚠️ 装箱类型，**可能为 null**，不能直接拆箱 |
| `CallToolResult.content()` | `List<McpSchema.Content>` | 取文本要判 `instanceof McpSchema.TextContent` 再 `.text()` |
| `CallToolResult.isError()` | `Boolean` | 可能 null |

---

### Task 1: 数据层地基（依赖 + 枚举 + 实体 + Repository）

**Files:**
- Modify: `backend/pom.xml`（Spring AI 依赖块之后）
- Create: `backend/src/main/java/com/qingyu/care/enums/McpTransport.java`
- Create: `backend/src/main/java/com/qingyu/care/enums/McpToolStatus.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/McpServer.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/McpTool.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/McpServerRepository.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/McpToolRepository.java`
- Test: `backend/src/test/java/com/qingyu/care/entity/McpEntityDefaultsTest.java`

**Interfaces:**
- Produces: `McpServer`（含 `getCode()`/`getUrl()`/`getAuthHeader()`/`getTimeoutMs()`/`getMaxResponseChars()`/`getTransport()`）、`McpTool`（含 `getMcpServerId()`/`getName()`/`getInputSchema()`/`getStatus()`/`getReadOnly()`/`getReadOnlyHint()`）、`McpToolRepository.findByIdInAndStatusAndReadOnlyTrue(Collection<Long>, McpToolStatus)`、`McpToolRepository.findByMcpServerId(Long)`、`McpServerRepository.findByAppId(Long)`

- [ ] **Step 1: 加依赖**

在 `backend/pom.xml` 的 `spring-ai-tika-document-reader` 依赖之后插入：

```xml
        <!-- MCP 客户端。刻意不用 spring-ai-starter-mcp-client*：starter 会带
             McpClientAutoConfiguration，它按静态 yaml 建连接，与「运行时按租户配置建、
             用完即弃」的设计冲突。
             ⚠️ 只能用 webflux 这条线：httpclient 版基于 JDK HttpClient，不支持自定义
             DNS 解析，SSRF 的钉 IP 防护会静默失效（详见 MCP 设计文档 §9）。 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-mcp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>mcp-spring-webflux</artifactId>
        </dependency>
```

- [ ] **Step 2: 验证依赖可解析且应用仍是 Servlet 容器**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS，无 ERROR。

⚠️ 这一步不是走过场：引入 webflux 后 `WebApplicationType` 是按 classpath 推导的。spike 已验证仍判定 SERVLET（`spring-boot-starter-web` 带 `DispatcherServlet`），若这里编译期出现 webflux 相关的自动配置报错，立刻停下核对是不是误引了 starter。

- [ ] **Step 3: 建两个枚举**

`enums/McpTransport.java`：

```java
package com.qingyu.care.enums;

/**
 * MCP 远端传输方式。
 *
 * <p>刻意不含 stdio：它要求在平台服务器上 fork 子进程执行租户填写的命令行，
 * 等于给租户一个远程命令执行入口，多租户 SaaS 下没有安全的做法。
 *
 * @author ruan
 */
public enum McpTransport {
    /** 现行推荐传输 */
    STREAMABLE_HTTP,
    /** 兼容仅支持旧 SSE 传输的 server */
    SSE
}
```

`enums/McpToolStatus.java`：

```java
package com.qingyu.care.enums;

/**
 * MCP 工具在本地清单中的状态。
 *
 * @author ruan
 */
public enum McpToolStatus {
    /** 上次同步时远端仍提供该工具 */
    ACTIVE,
    /**
     * 上次同步时远端已不再提供该工具。
     *
     * <p>刻意标记而非删行：工具可能已被 skill 的 mcpToolIds 绑定，删行会让绑定悬空
     * （resolve 查不到、界面也看不到，运营完全无从得知）。
     */
    DEPRECATED
}
```

- [ ] **Step 4: 建 `McpServer` 实体**

```java
package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.enums.OwnerScope;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 远端 MCP server 的连接配置。一个 server 提供 N 个工具（{@link McpTool}）。
 *
 * @author ruan
 */
@Entity
@Table(name = "mcp_server")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
// 无参构造显式标 @JsonCreator：Jackson 3 会把 @AllArgsConstructor 当隐式创建器，
// 请求体缺省的属性会以 null 传入并覆盖 @Builder.Default 初值（同 AiTool）。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class McpServer extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名，可中文（如「订单系统」）。仅界面展示，不参与任何标识。 */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 标识符，参与 LLM 可见的工具名（{@code {code}__{toolName}}）。
     *
     * <p>与 name 分开是刻意的：工具名必须是合法标识符，但不该因此逼租户把服务器叫
     * order_system 而不是「订单系统」——那是把数据模型的便利转嫁给用户。范式同 Tenant.code。
     */
    @Column(nullable = false, length = 50)
    private String code;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'STREAMABLE_HTTP'")
    @Builder.Default
    private McpTransport transport = McpTransport.STREAMABLE_HTTP;

    @Column(nullable = false, length = 1000)
    private String url;

    /** 静态鉴权头（如 "Authorization: Bearer xxx"），明文存储（技术债，同 AiTool.authHeader） */
    @Column(length = 1000)
    private String authHeader;

    /** 出站超时（毫秒）。比 HTTP 工具的 5000 宽，因为多一次 initialize 握手。 */
    @Column(nullable = false)
    @Builder.Default
    private Integer timeoutMs = 10000;

    /** 回灌 LLM 的响应体截断长度 */
    @Column(nullable = false)
    @Builder.Default
    private Integer maxResponseChars = 4000;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 归属范围。本期恒为 TENANT，为将来的平台共享预留。
     *
     * <p>@ColumnDefault 是必需的而非美化：非空表加不带 default 的 NOT NULL 列时，
     * 单条 DDL 会被 PostgreSQL 拒绝，而 SchemaUpdate 对单条 DDL 失败只打 WARN 不阻断启动
     * ——后果是列压根没建出来、应用照常起、运行时查询才炸（见 AiTool.scope 注释记录的事故）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'TENANT'")
    @Builder.Default
    private OwnerScope scope = OwnerScope.TENANT;

    /** 最后一次成功同步时间 */
    private LocalDateTime lastSyncedAt;

    /** 最后一次同步的失败原因，成功时置 null */
    @Column(length = 1000)
    private String lastSyncError;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 5: 建 `McpTool` 实体**

```java
package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.qingyu.care.enums.McpToolStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 从远端 MCP server 同步下来的单个工具。
 *
 * @author ruan
 */
@Entity
@Table(name = "mcp_tool",
        uniqueConstraints = @UniqueConstraint(name = "uk_mcp_tool_server_name",
                columnNames = {"mcp_server_id", "name"}))
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class McpTool extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属 server。
     *
     * <p>⚠️ 存 Long 而不是 @ManyToOne：detached 的 LAZY 代理上除 getId() 外任何取值都会在
     * 非请求线程抛 LazyInitializationException，把代理塞进 Set/调 equals 也会触发
     * （CrawlSite.knowledgeBase 踩过）。省下的一次查询不值得留这颗雷。
     */
    @Column(name = "mcp_server_id", nullable = false)
    private Long mcpServerId;

    /** 远端工具名，同步时写入，不可编辑 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 来自远端，每次同步覆盖 */
    @Column(length = 1000)
    private String description;

    /**
     * 来自远端的 JSON Schema 文本，每次同步覆盖。
     *
     * <p>⚠️ MCP SDK 的 {@code Tool.inputSchema()} 返回的是 Map 而非 String，落库前必须序列化。
     */
    @Column(columnDefinition = "text")
    private String inputSchema;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'ACTIVE'")
    @Builder.Default
    private McpToolStatus status = McpToolStatus.ACTIVE;

    /**
     * 租户标注的只读位，**唯一的安全判据**。默认 false（未知一律当可写、不挂载）。
     *
     * <p>⚠️ 同步绝不能覆盖它：一旦被远端的下一次 schema 更新冲掉，一个可写工具会重新
     * 出现在 resolve() 结果里被 LLM 调用，且完全静默。
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private Boolean readOnly = false;

    /**
     * 远端自报的 readOnlyHint annotation，**仅作界面上的默认建议值，不参与任何安全判定**。
     * 服务端自报、可选不填，不可信。
     */
    private Boolean readOnlyHint;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 6: 建两个 Repository**

`repository/McpServerRepository.java`：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.McpServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * MCP server 仓库。全部为 filter-bound 查询——McpServer 继承 BaseTenantEntity，
 * @Filter 自动挡掉别的租户的行，**本期不需要也不应该写 native 查询绕过过滤器**
 * （那正是平台共享工具那条路径反复出安全问题的地方）。
 *
 * @author ruan
 */
public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    List<McpServer> findByAppId(Long appId);

    Optional<McpServer> findByCode(String code);
}
```

`repository/McpToolRepository.java`：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.McpToolStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MCP 工具仓库。同 McpServerRepository，全部 filter-bound。
 *
 * @author ruan
 */
public interface McpToolRepository extends JpaRepository<McpTool, Long> {

    List<McpTool> findByMcpServerId(Long mcpServerId);

    Optional<McpTool> findByMcpServerIdAndName(Long mcpServerId, String name);

    /**
     * 运行时解析用：只取「远端仍存在」且「租户标注为只读」的工具。
     * 只读门槛与 DEPRECATED 过滤都落在这一条查询上，不在界面上拦。
     */
    List<McpTool> findByIdInAndStatusAndReadOnlyTrue(Collection<Long> ids, McpToolStatus status);

    void deleteByMcpServerId(Long mcpServerId);
}
```

- [ ] **Step 7: 写实体默认值测试**

`backend/src/test/java/com/qingyu/care/entity/McpEntityDefaultsTest.java`：

```java
package com.qingyu.care.entity;

import com.qingyu.care.enums.McpToolStatus;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.enums.OwnerScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体默认值。readOnly 默认 false 是安全默认，被改成 true 会让未标注的工具直接可挂载。
 *
 * @author ruan
 */
class McpEntityDefaultsTest {

    @Test
    @DisplayName("McpTool 默认不可挂载：readOnly=false、status=ACTIVE")
    void mcpTool_defaults_areFailClosed() {
        McpTool tool = McpTool.builder().mcpServerId(1L).name("search").build();

        assertThat(tool.getReadOnly()).isFalse();
        assertThat(tool.getStatus()).isEqualTo(McpToolStatus.ACTIVE);
    }

    @Test
    @DisplayName("McpServer 默认走 STREAMABLE_HTTP、归属 TENANT")
    void mcpServer_defaults() {
        McpServer server = McpServer.builder().name("订单系统").code("order").url("https://x/mcp").build();

        assertThat(server.getTransport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        assertThat(server.getScope()).isEqualTo(OwnerScope.TENANT);
        assertThat(server.getTimeoutMs()).isEqualTo(10000);
        assertThat(server.getEnabled()).isTrue();
    }
}
```

- [ ] **Step 8: 跑测试**

Run: `cd backend && mvn test -Dtest=McpEntityDefaultsTest`
Expected: PASS，2 个测试全绿。

- [ ] **Step 9: 提交**

```bash
git add backend/pom.xml backend/src/main/java/com/qingyu/care/enums/Mcp*.java backend/src/main/java/com/qingyu/care/entity/Mcp*.java backend/src/main/java/com/qingyu/care/repository/Mcp*.java backend/src/test/java/com/qingyu/care/entity/McpEntityDefaultsTest.java
git commit -m "feat(mcp): 数据层地基——依赖、枚举、实体与仓库

刻意不引 spring-ai-starter-mcp-client*（会带按静态 yaml 建连接的自动配置），
也不用 httpclient 版传输（JDK HttpClient 不支持自定义 DNS 解析，SSRF 钉 IP
会静默失效）。

McpTool.readOnly 默认 false 是安全默认：未知一律当可写、不挂载。"
```

---

### Task 2: `McpConnectionFactory`（SSRF 守卫）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/mcp/McpConnectionFactory.java`
- Test: `backend/src/test/java/com/qingyu/care/service/mcp/McpConnectionFactoryTest.java`

**Interfaces:**
- Consumes: `McpServer`（Task 1）、既有 `com.qingyu.care.config.SsrfGuard.resolveAndValidate(String host)`
- Produces: `McpConnectionFactory.create(McpServer) -> io.modelcontextprotocol.client.McpSyncClient`（已 `initialize()` 完毕）、`McpConnectionFactory.buildHttpClient(String host, InetAddress[] pinned, int timeoutMs) -> reactor.netty.http.client.HttpClient`（包级可见，供测试断言）

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/qingyu/care/service/mcp/McpConnectionFactoryTest.java`：

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF 守卫。这两条守的是「静默失效」——漏掉 resolver 或 followRedirect(false) 后
 * 代码照常编译、连接照常建立、功能照常可用，只是防护没了。
 *
 * @author ruan
 */
class McpConnectionFactoryTest {

    private final McpConnectionFactory factory = new McpConnectionFactory();

    @Test
    @DisplayName("出站客户端必须钉住已校验 IP 并禁用重定向")
    void buildHttpClient_pinsResolverAndDisablesRedirect() throws Exception {
        InetAddress[] pinned = {InetAddress.getByName("93.184.216.34")};

        var client = McpConnectionFactory.buildHttpClient("example.com", pinned, 10000);

        // configuration() 暴露实际生效的传输配置：resolver 非默认即说明自定义解析器已挂上
        assertThat(client.configuration().resolver()).isNotNull();
        assertThat(client.configuration().followRedirectPredicate()).isNull();
    }

    @Test
    @DisplayName("指向内网地址的 server 在建连接前就被拒")
    void create_rejectsInternalAddress() {
        McpServer server = McpServer.builder()
                .name("恶意")
                .code("evil")
                .url("http://169.254.169.254/latest/meta-data")
                .timeoutMs(5000)
                .build();

        assertThatThrownBy(() -> factory.create(server))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=McpConnectionFactoryTest`
Expected: 编译失败，`McpConnectionFactory` 不存在。

- [ ] **Step 3: 实现 `McpConnectionFactory`**

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.config.SsrfGuard;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.enums.McpTransport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.ai.mcp.client.webflux.transport.WebFluxSseClientTransport;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;

/**
 * 按 {@link McpServer} 建一个已初始化的 {@link McpSyncClient}。**唯一碰 MCP 协议的地方**。
 *
 * <p>出站前经 {@link SsrfGuard} 解析并校验目标地址，并把「校验期解析出的 IP」钉入连接期，
 * 杜绝 SSRF 的 DNS rebinding/TOCTOU——语义与 {@code RestClientHttpToolExecutor} 对等，
 * 只是那边用 Apache HttpClient 的 DnsResolver、这边用 Reactor Netty 的 AddressResolverGroup。
 *
 * <p>⚠️ 两处防护都是**静默失效**型的，改动时别删：
 * <ul>
 *   <li>{@code .resolver(...)} 一旦去掉，退回真实 DNS，「校验时公网、连接时翻内网」即可绕过；
 *   <li>{@code .followRedirect(false)} 一旦去掉，302 能把请求带去未经校验的主机。
 * </ul>
 * 两者去掉后代码照常编译、连接照常建立、功能照常可用，没有任何现象提醒。
 *
 * <p>⚠️ 传输只能用 webflux 这条线。httpclient 版基于 JDK HttpClient，不支持自定义 DNS
 * 解析，钉 IP 无从谈起（见 MCP 设计文档 §9）。
 *
 * @author ruan
 */
@Component
@Slf4j
public class McpConnectionFactory {

    /**
     * 建立并初始化一个 MCP 客户端。调用方负责 {@code closeGracefully()}。
     *
     * @throws IllegalArgumentException 目标地址未通过 SSRF 校验
     */
    public McpSyncClient create(McpServer server) {
        String host = URI.create(server.getUrl()).getHost();
        if (host == null) {
            throw new IllegalArgumentException("MCP server URL 无法解析主机名: " + server.getUrl());
        }
        InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);

        HttpClient httpClient = buildHttpClient(host, pinned, server.getTimeoutMs());
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (server.getAuthHeader() != null && !server.getAuthHeader().isBlank()) {
            applyAuthHeader(webClientBuilder, server.getAuthHeader());
        }

        McpClientTransport transport = server.getTransport() == McpTransport.SSE
                ? WebFluxSseClientTransport.builder(webClientBuilder).build()
                : WebClientStreamableHttpTransport.builder(webClientBuilder)
                        .endpoint(server.getUrl())
                        .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(server.getTimeoutMs()))
                .build();
        client.initialize();
        return client;
    }

    /**
     * 构造钉住已校验地址、禁用重定向的出站客户端。包级可见供测试断言这两处防护还在。
     */
    static HttpClient buildHttpClient(String host, InetAddress[] pinned, int timeoutMs) {
        AddressResolverGroup<?> pinnedResolver = new PinnedAddressResolverGroup(host, pinned);
        return HttpClient.create()
                .resolver(pinnedResolver)
                .followRedirect(false)
                .responseTimeout(Duration.ofMillis(timeoutMs));
    }

    /** 把 "Header-Name: value" 整行头拆成键值（格式同 AiTool.authHeader）。 */
    private void applyAuthHeader(WebClient.Builder builder, String authHeader) {
        int idx = authHeader.indexOf(':');
        if (idx > 0) {
            builder.defaultHeader(authHeader.substring(0, idx).trim(),
                    authHeader.substring(idx + 1).trim());
        } else {
            builder.defaultHeader("Authorization", authHeader.trim());
        }
    }

    /** 默认解析器的占位实现由 {@link PinnedAddressResolverGroup} 提供，见同包类。 */
    static AddressResolverGroup<?> defaultResolver() {
        return DefaultAddressResolverGroup.INSTANCE;
    }
}
```

- [ ] **Step 4: 实现 `PinnedAddressResolverGroup`**

Create: `backend/src/main/java/com/qingyu/care/service/mcp/PinnedAddressResolverGroup.java`

```java
package com.qingyu.care.service.mcp;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.resolver.SimpleNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * 只对指定 host 返回「已经过 SsrfGuard 校验」的地址，其它 host 一律 fail-closed 拒绝。
 *
 * <p>作用与 {@code RestClientHttpToolExecutor} 里那个自定义 DnsResolver 完全一致：让校验用的
 * 解析结果 == 实际连接用的地址，两次解析之间无法翻转 IP（DNS rebinding / TOCTOU）。
 * 主机名在 URL 中原样保留，故 TLS 的 SNI 与证书按主机名校验，不破坏合法 HTTPS。
 *
 * @author ruan
 */
class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final String allowedHost;
    private final List<InetAddress> pinned;

    PinnedAddressResolverGroup(String allowedHost, InetAddress[] pinned) {
        this.allowedHost = allowedHost;
        this.pinned = Arrays.asList(pinned);
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        SimpleNameResolver<InetAddress> nameResolver = new SimpleNameResolver<>(executor) {
            @Override
            protected void doResolve(String inetHost, Promise<InetAddress> promise) {
                if (!allowedHost.equalsIgnoreCase(inetHost)) {
                    promise.setFailure(new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + inetHost));
                    return;
                }
                promise.setSuccess(pinned.get(0));
            }

            @Override
            protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
                if (!allowedHost.equalsIgnoreCase(inetHost)) {
                    promise.setFailure(new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + inetHost));
                    return;
                }
                promise.setSuccess(pinned);
            }
        };
        return new InetSocketAddressResolver(executor, nameResolver);
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `cd backend && mvn test -Dtest=McpConnectionFactoryTest`
Expected: PASS，2 个测试全绿。

若 `client.configuration().resolver()` 的断言编译不过，用 `javap -cp <reactor-netty-core jar> reactor.netty.transport.ClientTransportConfig` 核实取值方法名后改断言——**不要删掉这条断言**，它守的是最容易静默失效的一处。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/mcp/ backend/src/test/java/com/qingyu/care/service/mcp/
git commit -m "feat(mcp): 连接工厂与 SSRF 守卫

把校验期解析出的 IP 钉进连接期，语义与 RestClientHttpToolExecutor 对等，
只是那边用 Apache DnsResolver、这边用 Reactor Netty AddressResolverGroup。

resolver 与 followRedirect(false) 两处都是静默失效型防护：去掉后代码照常
编译、连接照常建立、功能照常可用，故用单测钉住。"
```

---

### Task 3: `McpToolExecutor`（协议操作的可测边界）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/mcp/McpToolExecutor.java`
- Create: `backend/src/main/java/com/qingyu/care/service/mcp/RemoteMcpToolExecutor.java`
- Create: `backend/src/main/java/com/qingyu/care/service/mcp/McpToolDescriptor.java`
- Test: `backend/src/test/java/com/qingyu/care/service/mcp/RemoteMcpToolExecutorTest.java`

**Interfaces:**
- Consumes: `McpConnectionFactory.create(McpServer)`（Task 2）
- Produces: `McpToolExecutor.listTools(McpServer) -> List<McpToolDescriptor>`、`McpToolExecutor.callTool(McpServer, String toolName, Map<String,Object> args) -> String`、record `McpToolDescriptor(String name, String description, String inputSchemaJson, Boolean readOnlyHint)`

- [ ] **Step 1: 定义描述符与接口**

`McpToolDescriptor.java`：

```java
package com.qingyu.care.service.mcp;

/**
 * 远端工具的本地投影。刻意不直接把 MCP SDK 的 McpSchema.Tool 传出协议层——
 * 那样上层就被 SDK 的类型绑住，且 inputSchema 是 Map 而库里存 JSON 文本。
 *
 * @param inputSchemaJson 已序列化的 JSON Schema 文本（SDK 给的是 Map）
 * @param readOnlyHint    远端自报，可能为 null，**仅作界面建议值、不参与安全判定**
 * @author ruan
 */
public record McpToolDescriptor(String name, String description, String inputSchemaJson, Boolean readOnlyHint) {
}
```

`McpToolExecutor.java`：

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议操作的抽象边界。存在的意义是让上层（同步服务、ToolCallback）的单测能 mock 掉
 * 真实连接——范式同 HttpToolExecutor / RestClientHttpToolExecutor。
 *
 * @author ruan
 */
public interface McpToolExecutor {

    /** 拉取远端工具清单。每次调用独立建连接、用完即弃。 */
    List<McpToolDescriptor> listTools(McpServer server);

    /**
     * 调用一个远端工具，返回拼接后的文本结果。
     *
     * @param toolName 远端的原始工具名（不带 {@code {code}__} 前缀）
     */
    String callTool(McpServer server, String toolName, Map<String, Object> args);
}
```

- [ ] **Step 2: 写失败的测试**

`RemoteMcpToolExecutorTest.java`：

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class RemoteMcpToolExecutorTest {

    @Mock
    private McpConnectionFactory connectionFactory;
    @Mock
    private McpSyncClient client;

    private RemoteMcpToolExecutor executor;

    private final McpServer server = McpServer.builder()
            .name("订单系统").code("order").url("https://mcp.example.com/mcp").timeoutMs(10000)
            .maxResponseChars(4000).build();

    private RemoteMcpToolExecutor newExecutor() {
        return new RemoteMcpToolExecutor(connectionFactory, new ObjectMapper());
    }

    @Test
    @DisplayName("listTools 把 Map 形态的 inputSchema 序列化成 JSON 文本")
    void listTools_serializesInputSchema() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("query_order")
                .description("查订单")
                .inputSchema(Map.of("type", "object"))
                .build();
        when(connectionFactory.create(server)).thenReturn(client);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        executor = newExecutor();

        List<McpToolDescriptor> result = executor.listTools(server);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("query_order");
        assertThat(result.get(0).inputSchemaJson()).contains("\"type\"").contains("\"object\"");
        verify(client).closeGracefully();
    }

    @Test
    @DisplayName("annotations 整个为 null 时 readOnlyHint 取 null 而不是 NPE")
    void listTools_toleratesNullAnnotations() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search")
                .inputSchema(Map.of("type", "object"))
                .build();
        when(connectionFactory.create(server)).thenReturn(client);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        executor = newExecutor();

        List<McpToolDescriptor> result = executor.listTools(server);

        assertThat(result.get(0).readOnlyHint()).isNull();
    }

    @Test
    @DisplayName("callTool 拼接 TextContent 并始终关闭连接")
    void callTool_joinsTextContentAndCloses() {
        when(connectionFactory.create(server)).thenReturn(client);
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("订单已发货")), false, null, null));
        executor = newExecutor();

        String out = executor.callTool(server, "query_order", Map.of("id", "A1"));

        assertThat(out).isEqualTo("订单已发货");
        verify(client).closeGracefully();
    }

    @Test
    @DisplayName("远端抛异常时连接仍被关闭，不泄漏")
    void callTool_closesOnFailure() {
        when(connectionFactory.create(server)).thenReturn(client);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenThrow(new IllegalStateException("boom"));
        executor = newExecutor();

        try {
            executor.callTool(server, "query_order", Map.of());
        } catch (RuntimeException ignored) {
            // 预期抛出，本用例只关心连接被关掉
        }

        verify(client).closeGracefully();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=RemoteMcpToolExecutorTest`
Expected: 编译失败，`RemoteMcpToolExecutor` 不存在。

- [ ] **Step 4: 实现 `RemoteMcpToolExecutor`**

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于官方 MCP Java SDK 的执行器。每次操作独立建连接、用完即弃——见 MCP 设计文档 §2.4：
 * 用一次握手往返换掉整类连接池生命周期 bug（失效策略、陈旧连接、并发复用、跨租户串号）。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemoteMcpToolExecutor implements McpToolExecutor {

    private final McpConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpToolDescriptor> listTools(McpServer server) {
        McpSyncClient client = connectionFactory.create(server);
        try {
            List<McpToolDescriptor> out = new ArrayList<>();
            for (McpSchema.Tool t : client.listTools().tools()) {
                out.add(new McpToolDescriptor(
                        t.name(),
                        t.description(),
                        writeSchema(t.inputSchema()),
                        // ⚠️ annotations() 整个可能为 null，readOnlyHint() 也是装箱 Boolean
                        t.annotations() == null ? null : t.annotations().readOnlyHint()));
            }
            return out;
        } finally {
            closeQuietly(client);
        }
    }

    @Override
    public String callTool(McpServer server, String toolName, Map<String, Object> args) {
        McpSyncClient client = connectionFactory.create(server);
        try {
            McpSchema.CallToolResult result =
                    client.callTool(new McpSchema.CallToolRequest(toolName, args));
            return joinText(result);
        } finally {
            closeQuietly(client);
        }
    }

    /** SDK 给的 inputSchema 是 Map，库里存 JSON 文本，此处做转换。 */
    private String writeSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(schema);
    }

    /** 只取 TextContent；图片等其它内容类型本期不回灌给 LLM。 */
    private String joinText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) {
            return "";
        }
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    /** 关闭失败不该盖掉业务异常，也不该阻断流程。 */
    private void closeQuietly(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (Exception e) {
            log.warn("关闭 MCP 连接失败: {}", e.getClass().getSimpleName());
        }
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `cd backend && mvn test -Dtest=RemoteMcpToolExecutorTest`
Expected: PASS，4 个测试全绿。

若 `McpSchema.Tool.builder()` 的链式方法名与此处不符，用 `javap -cp <mcp-core jar> 'io.modelcontextprotocol.spec.McpSchema$Tool$Builder'` 核实后调整**测试里的构造方式**（生产代码只读不写，不受影响）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/mcp/ backend/src/test/java/com/qingyu/care/service/mcp/
git commit -m "feat(mcp): 协议执行器与工具描述符

listTools/callTool 每次独立建连接、finally 关闭。SDK 的 inputSchema 是 Map
而库里存 JSON 文本，在此转换；annotations 整个可能为 null，取 readOnlyHint
时不能直接拆箱。"
```

---

### Task 4: `McpToolCallback`（挂给 LLM 的那一层）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/mcp/McpToolCallback.java`
- Test: `backend/src/test/java/com/qingyu/care/service/mcp/McpToolCallbackTest.java`

**Interfaces:**
- Consumes: `McpToolExecutor`（Task 3）、`McpServer`/`McpTool`（Task 1）
- Produces: `new McpToolCallback(McpServer, McpTool, McpToolExecutor, ObjectMapper)`，实现 `org.springframework.ai.tool.ToolCallback`；静态 `McpToolCallback.exposedName(McpServer, McpTool) -> String`

- [ ] **Step 1: 写失败的测试**

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpToolCallbackTest {

    @Mock
    private McpToolExecutor executor;

    private final McpServer server = McpServer.builder()
            .name("订单系统").code("order").url("https://mcp.example.com/mcp")
            .timeoutMs(10000).maxResponseChars(10).build();

    private McpToolCallback callbackFor(McpTool tool) {
        return new McpToolCallback(server, tool, executor, new ObjectMapper());
    }

    private McpTool tool(String name, String schema) {
        return McpTool.builder().mcpServerId(1L).name(name).description("查订单").inputSchema(schema).build();
    }

    @Test
    @DisplayName("暴露给 LLM 的名字带 server code 前缀，调用时用原名")
    void definitionUsesPrefixedName_callUsesRawName() {
        McpToolCallback cb = callbackFor(tool("query_order", "{\"type\":\"object\"}"));
        when(executor.callTool(eq(server), eq("query_order"), anyMap())).thenReturn("ok");

        assertThat(cb.getToolDefinition().name()).isEqualTo("order__query_order");
        cb.call("{\"id\":\"A1\"}");
        verify(executor).callTool(eq(server), eq("query_order"), eq(Map.of("id", "A1")));
    }

    @Test
    @DisplayName("inputSchema 为空时兜底成合法空对象 schema")
    void definition_fallsBackToEmptySchema() {
        McpToolCallback cb = callbackFor(tool("search", null));

        assertThat(cb.getToolDefinition().inputSchema()).isEqualTo("{\"type\":\"object\",\"properties\":{}}");
    }

    @Test
    @DisplayName("远端失败时 fail-soft 返回文本，不抛异常中断整轮对话")
    void call_failsSoft() {
        McpToolCallback cb = callbackFor(tool("query_order", "{}"));
        when(executor.callTool(any(), anyString(), anyMap())).thenThrow(new IllegalStateException("连接超时"));

        String out = cb.call("{}");

        assertThat(out).startsWith("工具调用失败");
    }

    @Test
    @DisplayName("响应按 maxResponseChars 截断")
    void call_truncates() {
        McpToolCallback cb = callbackFor(tool("query_order", "{}"));
        when(executor.callTool(any(), anyString(), anyMap())).thenReturn("0123456789ABCDEF");

        assertThat(cb.call("{}")).hasSize(10);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=McpToolCallbackTest`
Expected: 编译失败，`McpToolCallback` 不存在。

- [ ] **Step 3: 实现**

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 把一个已落库的 {@link McpTool} 包装成 Spring AI 可调用的工具。结构与 HttpToolCallback 对称：
 * 构造期绑定该租户的配置，执行时无需 ToolContext / TenantContext。
 *
 * <p>连接不在这里持有——每次 {@link #call} 才经 {@link McpToolExecutor} 建、用完即弃。
 *
 * @author ruan
 */
@Slf4j
public class McpToolCallback implements ToolCallback {

    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final McpServer server;
    private final McpTool tool;
    private final McpToolExecutor executor;
    private final ObjectMapper mapper;

    public McpToolCallback(McpServer server, McpTool tool, McpToolExecutor executor, ObjectMapper mapper) {
        this.server = server;
        this.tool = tool;
        this.executor = executor;
        this.mapper = mapper;
    }

    /**
     * LLM 可见的工具名 = {@code {server.code}__{tool.name}}。
     *
     * <p>同一 skill 可能绑两个 server 的同名工具（search / query / get_user 撞名概率很高），
     * 不加命名空间会让 LLM 收到两个同名工具，行为未定义。
     */
    public static String exposedName(McpServer server, McpTool tool) {
        return server.getCode() + "__" + tool.getName();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String schema = (tool.getInputSchema() == null || tool.getInputSchema().isBlank())
                ? EMPTY_SCHEMA
                : tool.getInputSchema();
        return DefaultToolDefinition.builder()
                .name(exposedName(server, tool))
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
            // 审计口径同 HttpToolCallback：记工具名与参数键，不记值，避免泄露
            log.info("调用 MCP 工具 {} 参数键={}", exposedName(server, tool), args.keySet());
            // ⚠️ 发给远端的是原始工具名，前缀只存在于暴露给 LLM 的那一层
            String resp = executor.callTool(server, tool.getName(), args);
            return truncate(resp, server.getMaxResponseChars());
        } catch (Exception e) {
            // fail-soft：抛出会中断整轮对话，同 HttpToolCallback
            log.warn("MCP 工具 {} 调用失败: {}", exposedName(server, tool), e.getClass().getSimpleName());
            return "工具调用失败: " + e.getMessage();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    private Map<String, Object> parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(toolInput, new TypeReference<Map<String, Object>>() {});
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn test -Dtest=McpToolCallbackTest`
Expected: PASS，4 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/mcp/McpToolCallback.java backend/src/test/java/com/qingyu/care/service/mcp/McpToolCallbackTest.java
git commit -m "feat(mcp): McpToolCallback

暴露给 LLM 的名字加 server code 前缀避免跨 server 撞名，调用时用原名发给远端。
失败 fail-soft 返回文本而非抛出——抛出会中断整轮对话（同 HttpToolCallback）。"
```

---

### Task 5: `McpServerService` —— CRUD 与租户守卫

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/mcp/McpServerService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/mcp/McpServerServiceCrudTest.java`

**Interfaces:**
- Consumes: `McpServerRepository` / `McpToolRepository`（Task 1）、既有 `TenantContext.getAppId()`、`TenantOwnershipGuard.mutableInCurrentContext(Long tenantId, Long appId)`、`ResourceNotFoundException`
- Produces: `McpServerService.listServers() -> List<McpServer>`、`getServer(Long) -> McpServer`、`saveServer(McpServer) -> McpServer`、`listTools(Long serverId) -> List<McpTool>`、`markReadOnly(List<Long> toolIds, boolean readOnly) -> int`

- [ ] **Step 1: 写失败的测试**

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceCrudTest {

    @Mock
    private McpServerRepository serverRepository;
    @Mock
    private McpToolRepository toolRepository;
    @Mock
    private McpToolExecutor executor;

    @InjectMocks
    private McpServerService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("未选应用时服务器列表为空，而不是裸查全表")
    void listServers_withoutApp_returnsEmpty() {
        assertThat(service.listServers()).isEmpty();
        verify(serverRepository, never()).findAll();
    }

    @Test
    @DisplayName("越权取他人 server 按「不存在」处理，防枚举探测")
    void getServer_crossTenant_throwsNotFound() {
        McpServer other = McpServer.builder().name("别人的").code("other").url("https://x/mcp").build();
        other.setTenantId(999L);
        other.setAppId(999L);
        when(serverRepository.findById(7L)).thenReturn(Optional.of(other));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);

        assertThatThrownBy(() -> service.getServer(7L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("code 非法时拒绝保存——它要参与 LLM 可见的工具名")
    void saveServer_rejectsIllegalCode() {
        McpServer server = McpServer.builder().name("订单系统").code("订单").url("https://x/mcp").build();

        assertThatThrownBy(() -> service.saveServer(server)).isInstanceOf(BusinessException.class);
        verify(serverRepository, never()).save(any());
    }

    @Test
    @DisplayName("批量标注只读逐条过租户守卫，越权的那条被跳过")
    void markReadOnly_guardsEveryId() {
        McpTool mine = McpTool.builder().mcpServerId(1L).name("a").build();
        mine.setTenantId(1L);
        mine.setAppId(1L);
        McpTool other = McpTool.builder().mcpServerId(2L).name("b").build();
        other.setTenantId(999L);
        other.setAppId(999L);
        when(toolRepository.findById(1L)).thenReturn(Optional.of(mine));
        when(toolRepository.findById(2L)).thenReturn(Optional.of(other));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);

        int updated = service.markReadOnly(List.of(1L, 2L), true);

        assertThat(updated).isEqualTo(1);
        assertThat(mine.getReadOnly()).isTrue();
        assertThat(other.getReadOnly()).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=McpServerServiceCrudTest`
Expected: 编译失败，`McpServerService` 不存在。

- [ ] **Step 3: 实现 CRUD 部分**

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * MCP server / tool 的管理面服务：CRUD + 工具清单同步。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpServerService {

    /** code 参与 LLM 可见的工具名，必须是合法标识符 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final McpToolExecutor executor;

    /**
     * 按当前应用收窄的 server 列表。
     *
     * <p>TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，裸 findAll 会让平台管理员
     * 跨应用读全平台配置，故显式按 appId 收窄（同 ChatToolService.listTools 的处理）。
     */
    public List<McpServer> listServers() {
        Long appId = TenantContext.getAppId();
        return appId == null ? List.of() : serverRepository.findByAppId(appId);
    }

    /**
     * 按 id 取 server。含明文 authHeader，故用写级守卫：越权按「不存在」处理，
     * 防枚举探测与跨租户读取凭据（同 ChatToolService.getTool）。
     */
    public McpServer getServer(Long id) {
        McpServer s = serverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MCP 服务", id.toString()));
        if (!TenantOwnershipGuard.mutableInCurrentContext(s.getTenantId(), s.getAppId())) {
            throw new ResourceNotFoundException("MCP 服务", id.toString());
        }
        return s;
    }

    @Transactional
    public McpServer saveServer(McpServer server) {
        if (server.getCode() == null || !CODE_PATTERN.matcher(server.getCode()).matches()) {
            throw new BusinessException("标识符只能是小写字母开头的字母、数字、下划线组合");
        }
        return serverRepository.save(server);
    }

    public List<McpTool> listTools(Long serverId) {
        getServer(serverId); // 守卫：不是自己的 server 直接按不存在处理
        return toolRepository.findByMcpServerId(serverId);
    }

    /**
     * 批量标注只读位，返回实际更新条数。
     *
     * <p>⚠️ 每个 id 都要单独过租户守卫，不能因为是批量就只校验第一个——否则遍历 id
     * 就能改别人的工具（readOnly 是唯一的安全判据，改掉它等于放开可写工具）。
     */
    @Transactional
    public int markReadOnly(List<Long> toolIds, boolean readOnly) {
        int updated = 0;
        for (Long id : toolIds) {
            Optional<McpTool> found = toolRepository.findById(id);
            if (found.isEmpty()) {
                continue;
            }
            McpTool t = found.get();
            if (!TenantOwnershipGuard.mutableInCurrentContext(t.getTenantId(), t.getAppId())) {
                log.warn("跳过越权的只读标注请求，工具 id={}", id);
                continue;
            }
            t.setReadOnly(readOnly);
            toolRepository.save(t);
            updated++;
        }
        return updated;
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn test -Dtest=McpServerServiceCrudTest`
Expected: PASS，4 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/mcp/McpServerService.java backend/src/test/java/com/qingyu/care/service/mcp/McpServerServiceCrudTest.java
git commit -m "feat(mcp): server/tool 管理面 CRUD

列表按 appId 显式收窄（平台管理员不受 @Filter 约束，裸 findAll 会跨应用读）；
取 server 用写级守卫（含明文凭据）；批量标注只读逐条过守卫，不能只校验第一个。"
```

---

### Task 6: 工具清单同步（三分支 + `readOnly` 保护）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/mcp/McpServerService.java`（追加 `sync` 方法）
- Test: `backend/src/test/java/com/qingyu/care/service/mcp/McpServerServiceSyncTest.java`

**Interfaces:**
- Consumes: `McpToolExecutor.listTools(McpServer)`（Task 3）
- Produces: `McpServerService.sync(Long serverId) -> List<McpTool>`（返回同步后的完整清单）

- [ ] **Step 1: 写失败的测试**

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.McpToolStatus;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 同步的三条分支与两条不变量。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceSyncTest {

    @Mock
    private McpServerRepository serverRepository;
    @Mock
    private McpToolRepository toolRepository;
    @Mock
    private McpToolExecutor executor;

    @InjectMocks
    private McpServerService service;

    private McpServer server;

    @BeforeEach
    void setUp() {
        server = McpServer.builder().name("订单系统").code("order").url("https://x/mcp").build();
        server.setTenantId(1L);
        server.setAppId(1L);
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);
        lenient().when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        lenient().when(toolRepository.save(any(McpTool.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("远端有、库里无 → 新建，且默认不可挂载")
    void sync_newTool_isCreatedFailClosed() {
        when(executor.listTools(server)).thenReturn(List.of(
                new McpToolDescriptor("query_order", "查订单", "{\"type\":\"object\"}", true)));
        when(toolRepository.findByMcpServerIdAndName(1L, "query_order")).thenReturn(Optional.empty());
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of());

        service.sync(1L);

        var saved = org.mockito.ArgumentCaptor.forClass(McpTool.class);
        verify(toolRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("query_order");
        assertThat(saved.getValue().getStatus()).isEqualTo(McpToolStatus.ACTIVE);
        // 远端自报 readOnlyHint=true，但落库的安全判据仍是 false
        assertThat(saved.getValue().getReadOnlyHint()).isTrue();
        assertThat(saved.getValue().getReadOnly()).isFalse();
    }

    @Test
    @DisplayName("⚠️ 同步绝不覆盖租户标注的 readOnly")
    void sync_neverOverwritesReadOnly() {
        McpTool existing = McpTool.builder()
                .mcpServerId(1L).name("query_order").description("旧描述")
                .inputSchema("{\"old\":true}").readOnly(true).build();
        when(executor.listTools(server)).thenReturn(List.of(
                new McpToolDescriptor("query_order", "新描述", "{\"new\":true}", false)));
        when(toolRepository.findByMcpServerIdAndName(1L, "query_order")).thenReturn(Optional.of(existing));
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of(existing));

        service.sync(1L);

        assertThat(existing.getDescription()).isEqualTo("新描述");
        assertThat(existing.getInputSchema()).isEqualTo("{\"new\":true}");
        // 这一条是安全关键：被冲掉会让一个可写工具重新出现在 resolve() 结果里，且完全静默
        assertThat(existing.getReadOnly()).isTrue();
    }

    @Test
    @DisplayName("库里有、远端无 → 标 DEPRECATED 而不是删行")
    void sync_vanishedTool_isDeprecatedNotDeleted() {
        McpTool stale = McpTool.builder().mcpServerId(1L).name("old_tool").build();
        when(executor.listTools(server)).thenReturn(List.of());
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of(stale));

        service.sync(1L);

        assertThat(stale.getStatus()).isEqualTo(McpToolStatus.DEPRECATED);
        verify(toolRepository, never()).delete(any());
        verify(toolRepository, never()).deleteByMcpServerId(any());
    }

    @Test
    @DisplayName("重新出现的工具从 DEPRECATED 复活成 ACTIVE")
    void sync_reappearedTool_isReactivated() {
        McpTool revived = McpTool.builder()
                .mcpServerId(1L).name("query_order").status(McpToolStatus.DEPRECATED).build();
        when(executor.listTools(server)).thenReturn(List.of(
                new McpToolDescriptor("query_order", "查订单", "{}", null)));
        when(toolRepository.findByMcpServerIdAndName(1L, "query_order")).thenReturn(Optional.of(revived));
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of(revived));

        service.sync(1L);

        assertThat(revived.getStatus()).isEqualTo(McpToolStatus.ACTIVE);
    }

    @Test
    @DisplayName("⚠️ 同步失败只记 lastSyncError，一行工具都不动")
    void sync_failure_leavesToolsUntouched() {
        McpTool existing = McpTool.builder().mcpServerId(1L).name("query_order").build();
        when(executor.listTools(server)).thenThrow(new IllegalStateException("连接超时"));

        assertThatThrownBy(() -> service.sync(1L)).isInstanceOf(RuntimeException.class);

        // 一次网络抖动不该让整套工具集体变 DEPRECATED 进而从对话里静默消失
        assertThat(existing.getStatus()).isEqualTo(McpToolStatus.ACTIVE);
        verify(toolRepository, never()).save(any());
        assertThat(server.getLastSyncError()).contains("连接超时");
        assertThat(server.getLastSyncedAt()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=McpServerServiceSyncTest`
Expected: 编译失败，`sync` 方法不存在。

- [ ] **Step 3: 在 `McpServerService` 追加 `sync`**

在类中追加（并在文件头补 `import com.qingyu.care.enums.McpToolStatus;`、`import java.time.LocalDateTime;`、`import java.util.*;`）：

```java
    /**
     * 手动同步远端工具清单。请求线程内执行（一次往返、秒级，不进长任务池）。
     *
     * <p>三条分支：
     * <ul>
     *   <li>远端有、库里有 → 更新 description/inputSchema/readOnlyHint，status 置 ACTIVE
     *   <li>远端有、库里无 → 新建，readOnly=false（未知一律当可写）
     *   <li>库里有、远端无 → 标 DEPRECATED，<b>不删行</b>——工具可能已被 skill 绑定，
     *       删行会让绑定悬空（resolve 查不到、界面也看不到，运营无从得知）
     * </ul>
     *
     * <p>⚠️ <b>绝不覆盖 readOnly</b>：它是租户的安全标注，被远端的 schema 更新顺手冲掉，
     * 等于静默把一个可写工具放回只读白名单。
     *
     * <p>⚠️ 失败时只写 lastSyncError、<b>一行工具都不动</b>：一次网络抖动不该让整套工具
     * 集体变 DEPRECATED 进而从对话里消失（语义同 WebPageSyncService 的「抓取失败保留旧内容」）。
     */
    @Transactional
    public List<McpTool> sync(Long serverId) {
        McpServer server = getServer(serverId);

        List<McpToolDescriptor> remote;
        try {
            remote = executor.listTools(server);
        } catch (Exception e) {
            server.setLastSyncError(e.getMessage());
            serverRepository.save(server);
            log.warn("MCP 同步失败 serverId={} 原因={}", serverId, e.getClass().getSimpleName());
            throw new BusinessException("同步失败：" + e.getMessage());
        }

        Set<String> remoteNames = new LinkedHashSet<>();
        for (McpToolDescriptor d : remote) {
            remoteNames.add(d.name());
            McpTool tool = toolRepository.findByMcpServerIdAndName(serverId, d.name())
                    .orElseGet(() -> McpTool.builder()
                            .mcpServerId(serverId)
                            .name(d.name())
                            .readOnly(false) // 未知一律当可写、不挂载
                            .build());
            tool.setDescription(d.description());
            tool.setInputSchema(d.inputSchemaJson());
            tool.setReadOnlyHint(d.readOnlyHint());
            tool.setStatus(McpToolStatus.ACTIVE);
            // ⚠️ 这里刻意不碰 tool.setReadOnly(...)，见方法注释
            toolRepository.save(tool);
        }

        for (McpTool local : toolRepository.findByMcpServerId(serverId)) {
            if (!remoteNames.contains(local.getName()) && local.getStatus() != McpToolStatus.DEPRECATED) {
                local.setStatus(McpToolStatus.DEPRECATED);
                toolRepository.save(local);
            }
        }

        server.setLastSyncedAt(LocalDateTime.now());
        server.setLastSyncError(null);
        serverRepository.save(server);
        return toolRepository.findByMcpServerId(serverId);
    }
```

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn test -Dtest=McpServerServiceSyncTest`
Expected: PASS，5 个测试全绿。

- [ ] **Step 5: 变异验证「readOnly 不被覆盖」这条测试真的有判别力**

临时在 `sync` 的 upsert 分支加一行 `tool.setReadOnly(d.readOnlyHint() != null && d.readOnlyHint());`，重跑：

Run: `cd backend && mvn test -Dtest=McpServerServiceSyncTest`
Expected: `sync_neverOverwritesReadOnly` **转红**。确认后立刻删掉这行再跑一次确认转绿。

⚠️ 这一步不能跳过。这是全计划里唯一一条「守的是静默安全降级」的测试，它自己失效了不会有任何现象。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/mcp/McpServerService.java backend/src/test/java/com/qingyu/care/service/mcp/McpServerServiceSyncTest.java
git commit -m "feat(mcp): 工具清单同步

三分支：新增 / 更新 / 远端消失标 DEPRECATED（不删行，否则 skill 绑定悬空）。

两条不变量各有测试守护并做过变异验证：同步绝不覆盖租户标注的 readOnly
（被冲掉等于静默放开可写工具）；同步失败只记 lastSyncError、一行工具不动
（一次抖动不该让整套工具从对话里消失）。"
```

---

### Task 7: `AiSkill.mcpToolIds` 与 `resolve()` 合并

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/AiSkill.java`
- Modify: `backend/src/main/java/com/qingyu/care/dto/AiSkillUpdateRequest.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatToolService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ChatToolServiceMcpTest.java`

**Interfaces:**
- Consumes: `McpToolRepository.findByIdInAndStatusAndReadOnlyTrue`（Task 1）、`McpToolCallback`（Task 4）、`McpServerRepository`
- Produces: `AiSkill.getMcpToolIds() -> Set<Long>`；`ChatToolService.resolve()` 的 `ToolBundle.toolCallbacks()` 现在同时含 HTTP 与 MCP 两类

- [ ] **Step 1: 给 `AiSkill` 加字段**

在 `toolIds` 字段之后追加：

```java
    /**
     * 绑定的 MCP 工具 id 集合（{@code mcp_tool.id}），与 toolIds 并列。
     *
     * <p>独立字段而非混进 toolIds：两者指向不同的表，混用会让「这个 id 该去哪张表查」
     * 变成猜测。
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ai_skill_mcp_tool", joinColumns = @JoinColumn(name = "skill_id"))
    @Column(name = "mcp_tool_id")
    @Builder.Default
    private Set<Long> mcpToolIds = new LinkedHashSet<>();
```

- [ ] **Step 2: 给 `AiSkillUpdateRequest` 加字段**

```java
    /** ⚠️ 不写初始化器：UpdateRequestNoInitializerTest 会强制这一条 */
    private Set<Long> mcpToolIds;
```

并在更新路径上补 `setMcpToolIds`（与 `toolIds` 那行紧邻，位置由 `UpdateRequestPropagationTest` 登记的更新路径决定）：

```java
        if (request.getMcpToolIds() != null) {
            skill.setMcpToolIds(request.getMcpToolIds());
        }
```

- [ ] **Step 3: 写失败的测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.enums.McpToolStatus;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.service.mcp.McpToolExecutor;
import com.qingyu.care.service.tool.HttpToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * resolve() 对 MCP 工具的合并。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatToolServiceMcpTest {

    @Mock private AiToolRepository toolRepository;
    @Mock private AiSkillRepository skillRepository;
    @Mock private McpToolRepository mcpToolRepository;
    @Mock private McpServerRepository mcpServerRepository;
    @Mock private HttpToolExecutor httpToolExecutor;
    @Mock private McpToolExecutor mcpToolExecutor;
    @Mock private com.qingyu.care.config.SharedCredentialMasker masker;

    private ChatToolService service() {
        return new ChatToolService(toolRepository, skillRepository, httpToolExecutor,
                new ObjectMapper(), masker, mcpToolRepository, mcpServerRepository, mcpToolExecutor);
    }

    private McpServer server(String code) {
        McpServer s = McpServer.builder().name("订单系统").code(code).url("https://x/mcp").build();
        s.setId(1L);
        return s;
    }

    @Test
    @DisplayName("skill 绑定的 MCP 工具被解析成带前缀的 callback")
    void resolve_includesMcpTools() {
        AiSkill skill = AiSkill.builder().name("售后").content("SOP").mcpToolIds(Set.of(10L)).build();
        McpTool tool = McpTool.builder().mcpServerId(1L).name("query_order").inputSchema("{}").readOnly(true).build();
        tool.setId(10L);
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(anyCollection(), eq(McpToolStatus.ACTIVE)))
                .thenReturn(List.of(tool));
        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(server("order")));

        var bundle = service().resolve();

        assertThat(bundle.toolCallbacks()).hasSize(1);
        assertThat(bundle.toolCallbacks().get(0).getToolDefinition().name()).isEqualTo("order__query_order");
    }

    @Test
    @DisplayName("没有绑定 MCP 工具时完全不打 mcp 相关的库")
    void resolve_withoutMcpBinding_skipsQuery() {
        AiSkill skill = AiSkill.builder().name("售后").content("SOP").build();
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));

        service().resolve();

        verify(mcpToolRepository, never()).findByIdInAndStatusAndReadOnlyTrue(anyCollection(), any());
    }

    @Test
    @DisplayName("MCP 工具与 HTTP 工具重名时跳过后来者，不把同名工具一起交给 LLM")
    void resolve_skipsDuplicateNames() {
        AiSkill skill = AiSkill.builder().name("售后").content("SOP")
                .toolIds(Set.of(1L)).mcpToolIds(Set.of(10L)).build();
        AiTool http = AiTool.builder().name("order__query_order").description("d")
                .urlTemplate("https://x").httpMethod("GET").build();
        http.setId(1L);
        McpTool mcp = McpTool.builder().mcpServerId(1L).name("query_order").inputSchema("{}").readOnly(true).build();
        mcp.setId(10L);
        when(skillRepository.findByEnabledTrue()).thenReturn(List.of(skill));
        when(toolRepository.findByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of(http));
        when(mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(anyCollection(), eq(McpToolStatus.ACTIVE)))
                .thenReturn(List.of(mcp));
        when(mcpServerRepository.findById(1L)).thenReturn(Optional.of(server("order")));

        var bundle = service().resolve();

        assertThat(bundle.toolCallbacks()).hasSize(1);
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=ChatToolServiceMcpTest`
Expected: 编译失败，`ChatToolService` 的构造参数不匹配。

- [ ] **Step 5: 改 `ChatToolService`**

在字段区追加三个依赖（`@RequiredArgsConstructor` 会自动进构造器，顺序须与测试一致）：

```java
    private final McpToolRepository mcpToolRepository;
    private final McpServerRepository mcpServerRepository;
    private final McpToolExecutor mcpToolExecutor;
```

在 `resolve()` 中，收集 skill 时同时收集 MCP 工具 id：

```java
        Set<Long> toolIds = new LinkedHashSet<>();
        Set<Long> mcpToolIds = new LinkedHashSet<>();
        for (AiSkill s : skills) {
            if (s.getContent() != null && !s.getContent().isBlank()) {
                markdowns.add(s.getContent());
            }
            if (s.getToolIds() != null) {
                toolIds.addAll(s.getToolIds());
            }
            if (s.getMcpToolIds() != null) {
                mcpToolIds.addAll(s.getMcpToolIds());
            }
        }
```

在造完 HTTP callback 之后追加 MCP 分支，并做全局重名去重：

```java
        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> exposedNames = new LinkedHashSet<>();
        if (!toolIds.isEmpty()) {
            for (AiTool t : resolveTools(toolIds)) {
                if (!exposedNames.add(t.getName())) {
                    log.warn("工具名重复，跳过 HTTP 工具 {}", t.getName());
                    continue;
                }
                callbacks.add(new HttpToolCallback(t, httpToolExecutor, objectMapper));
            }
        }
        if (!mcpToolIds.isEmpty()) {
            callbacks.addAll(resolveMcpCallbacks(mcpToolIds, exposedNames));
        }
        return new ToolBundle(markdowns, callbacks);
```

新增私有方法：

```java
    /**
     * 解析 MCP 工具为 callback。
     *
     * <p><b>只读门槛与 DEPRECATED 过滤都落在这条查询上</b>，不在界面上拦——界面拦得住的
     * 东西，接口层拦不住。
     *
     * <p>查询是 filter-bound 的普通 JPA 查询：McpTool 继承 BaseTenantEntity，@Filter 会
     * 挡掉别的租户的 id。⚠️ <b>不要改成 native</b>，本期没有平台共享，绕过过滤器只会引入
     * 跨租户越权（那正是共享工具那条路径反复出问题的地方）。
     */
    private List<ToolCallback> resolveMcpCallbacks(Set<Long> mcpToolIds, Set<String> exposedNames) {
        List<McpTool> tools =
                mcpToolRepository.findByIdInAndStatusAndReadOnlyTrue(mcpToolIds, McpToolStatus.ACTIVE);
        List<ToolCallback> out = new ArrayList<>();
        Map<Long, McpServer> serverCache = new LinkedHashMap<>();
        for (McpTool t : tools) {
            McpServer server = serverCache.computeIfAbsent(t.getMcpServerId(),
                    id -> mcpServerRepository.findById(id).orElse(null));
            if (server == null || !Boolean.TRUE.equals(server.getEnabled())) {
                continue;
            }
            String exposed = McpToolCallback.exposedName(server, t);
            if (!exposedNames.add(exposed)) {
                log.warn("工具名重复，跳过 MCP 工具 {}", exposed);
                continue;
            }
            out.add(new McpToolCallback(server, t, mcpToolExecutor, objectMapper));
        }
        return out;
    }
```

- [ ] **Step 6: 跑测试**

Run: `cd backend && mvn test -Dtest=ChatToolServiceMcpTest,ChatToolServiceTest`
Expected: 全绿。若既有 `ChatToolServiceTest` 因构造参数变化编译失败，补上三个新 mock 参数——**不要改它的断言**。

- [ ] **Step 7: 跑四道 update 哨兵**

Run: `cd backend && mvn test -Dtest=UpdateRequest*Test,PutEndpointBodyRegistrationTest`
Expected: 全绿。若 `UpdateRequestFieldCoverageTest` 红，说明 `mcpToolIds` 没进 DTO；若 `UpdateRequestPropagationTest` 红，说明更新路径上少了 `setMcpToolIds`。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/AiSkill.java backend/src/main/java/com/qingyu/care/dto/AiSkillUpdateRequest.java backend/src/main/java/com/qingyu/care/service/ChatToolService.java backend/src/test/java/com/qingyu/care/service/ChatToolServiceMcpTest.java
git commit -m "feat(mcp): skill 绑定 MCP 工具并接入 resolve()

只读门槛与 DEPRECATED 过滤都落在解析查询上，不在界面拦。查询保持
filter-bound，不用 native——本期没有平台共享，绕过过滤器只会引入越权。

追加全局工具名去重：同名工具一起交给 LLM 行为未定义。"
```

---

### Task 8: 删除 server 时清理 skill 绑定

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/mcp/McpServerService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/mcp/McpServerServiceDeleteTest.java`

**Interfaces:**
- Consumes: `AiSkillRepository`（既有）
- Produces: `McpServerService.deleteServer(Long serverId)`、`McpServerService.countSkillsUsing(Long serverId) -> long`

- [ ] **Step 1: 写失败的测试**

```java
package com.qingyu.care.service.mcp;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceDeleteTest {

    @Mock private McpServerRepository serverRepository;
    @Mock private McpToolRepository toolRepository;
    @Mock private McpToolExecutor executor;
    @Mock private AiSkillRepository skillRepository;

    @InjectMocks
    private McpServerService service;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("删 server 时把 skill 里指向它的工具 id 一并摘掉，不留悬空绑定")
    void deleteServer_cleansSkillBindings() {
        McpServer server = McpServer.builder().name("订单").code("order").url("https://x/mcp").build();
        server.setTenantId(1L);
        server.setAppId(1L);
        McpTool t1 = McpTool.builder().mcpServerId(1L).name("a").build();
        t1.setId(10L);
        AiSkill skill = AiSkill.builder().name("售后")
                .mcpToolIds(new LinkedHashSet<>(Set.of(10L, 99L))).build();
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        when(toolRepository.findByMcpServerId(1L)).thenReturn(List.of(t1));
        when(skillRepository.findAll()).thenReturn(List.of(skill));
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);

        service.deleteServer(1L);

        // 悬空的 10 被摘掉，属于别的 server 的 99 保留
        assertThat(skill.getMcpToolIds()).containsExactly(99L);
        verify(skillRepository).save(skill);
        verify(toolRepository).deleteByMcpServerId(1L);
        verify(serverRepository).delete(server);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=McpServerServiceDeleteTest`
Expected: 编译失败，`deleteServer` 不存在。

- [ ] **Step 3: 实现**

在 `McpServerService` 追加 `private final AiSkillRepository skillRepository;` 字段（放在其它字段之后，保持构造顺序与测试一致）与两个方法：

```java
    /** 统计有多少 skill 绑定了该 server 的工具，供前端删除确认弹窗显示。 */
    public long countSkillsUsing(Long serverId) {
        Set<Long> owned = ownedToolIds(serverId);
        if (owned.isEmpty()) {
            return 0;
        }
        return skillRepository.findAll().stream()
                .filter(s -> s.getMcpToolIds() != null
                        && s.getMcpToolIds().stream().anyMatch(owned::contains))
                .count();
    }

    /**
     * 删除 server：连带删工具行，<b>并摘掉 skill 里指向它们的绑定</b>。
     *
     * <p>⚠️ 不清理绑定的后果是静默降级：resolve() 的 filter-bound 查询查不到就自然跳过，
     * 不报错也不打日志——skill 在界面上看起来仍绑着 N 个工具，实际挂给 LLM 的少了几个。
     * 同「删站点勾选删文档必须逐篇走 deleteDocument」是同一类问题。
     *
     * <p>⚠️ 遍历 skill 用普通 JPA 查询即可：删除发生在请求线程、TenantContext 齐备，
     * @Filter 会挡掉别的租户；<b>不要为此写 native SQL</b>，那会连别人的 skill 一起扫到。
     */
    @Transactional
    public void deleteServer(Long serverId) {
        McpServer server = getServer(serverId);
        Set<Long> owned = ownedToolIds(serverId);

        if (!owned.isEmpty()) {
            for (AiSkill skill : skillRepository.findAll()) {
                if (skill.getMcpToolIds() == null || skill.getMcpToolIds().isEmpty()) {
                    continue;
                }
                if (skill.getMcpToolIds().removeIf(owned::contains)) {
                    skillRepository.save(skill);
                }
            }
        }
        toolRepository.deleteByMcpServerId(serverId);
        serverRepository.delete(server);
    }

    private Set<Long> ownedToolIds(Long serverId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (McpTool t : toolRepository.findByMcpServerId(serverId)) {
            ids.add(t.getId());
        }
        return ids;
    }
```

补 import：`com.qingyu.care.entity.AiSkill`、`com.qingyu.care.repository.AiSkillRepository`。

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn test -Dtest=McpServerServiceDeleteTest,McpServerServiceCrudTest,McpServerServiceSyncTest`
Expected: 全绿（前两个类因构造参数增加需补 `@Mock AiSkillRepository skillRepository;`，`@InjectMocks` 会自动注入）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/mcp/McpServerService.java backend/src/test/java/com/qingyu/care/service/mcp/
git commit -m "feat(mcp): 删 server 时清理 skill 绑定

不清理的后果是静默降级：resolve 的查询查不到就跳过，skill 界面上仍显示绑着
N 个工具、实际挂给 LLM 的少了几个，无报错无日志。"
```

---

### Task 9: 端点与 DTO（四道哨兵）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/McpServerUpdateRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/McpToolReadOnlyRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/McpServerController.java`
- Test: `backend/src/test/java/com/qingyu/care/controller/McpServerControllerTest.java`

**Interfaces:**
- Consumes: `McpServerService`（Task 5/6/8）
- Produces: REST 端点 `/api/v1/mcp/**`

- [ ] **Step 1: 建 `McpServerUpdateRequest`**

```java
package com.qingyu.care.dto;

import com.qingyu.care.enums.McpTransport;
import lombok.Data;

/**
 * MCP server 局部更新请求。
 *
 * <p>⚠️ <b>所有字段都不能写初始化器</b>（含 @Builder.Default）：实体字段的初始化器在
 * Jackson 走无参构造反序列化时照样生效，请求体没给的字段会拿到类默认值而非 null，
 * {@code if (x != null)} 恒真，局部 PUT 时把库里的值静默覆盖回默认值。
 * UpdateRequestNoInitializerTest 会反射扫描强制这一条。
 *
 * @author ruan
 */
@Data
public class McpServerUpdateRequest {

    private String name;
    private String code;
    private String description;
    private McpTransport transport;
    private String url;
    /** ⚠️ 凭据字段，判空必须是 != null && !isBlank()，见控制器 */
    private String authHeader;
    private Integer timeoutMs;
    private Integer maxResponseChars;
    private Boolean enabled;
}
```

- [ ] **Step 2: 建批量只读请求 DTO**

```java
package com.qingyu.care.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量标注 MCP 工具只读位。
 *
 * @author ruan
 */
@Data
public class McpToolReadOnlyRequest {

    @NotEmpty(message = "工具 id 不能为空")
    private List<Long> toolIds;

    @NotNull(message = "必须指定只读位")
    private Boolean readOnly;
}
```

- [ ] **Step 3: 建控制器**

```java
package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.McpServerUpdateRequest;
import com.qingyu.care.dto.McpToolReadOnlyRequest;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.service.mcp.McpServerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务管理端点。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP 服务", description = "远端 MCP server 与其工具清单")
public class McpServerController {

    private final McpServerService mcpServerService;

    @GetMapping("/servers")
    @Operation(summary = "服务器列表")
    public ApiResponse<List<McpServer>> listServers() {
        return ApiResponse.success(mcpServerService.listServers());
    }

    @PostMapping("/servers")
    @Operation(summary = "新建服务器")
    @AuditLog(action = "创建MCP服务", targetType = "McpServer")
    public ApiResponse<McpServer> createServer(@RequestBody McpServer server) {
        // 不信任请求体里的归属字段：BaseTenantEntity 的 tenantId/appId 是 READ_ONLY，
        // 由 TenantEntityListener 回填
        server.setId(null);
        return ApiResponse.success("创建成功", mcpServerService.saveServer(server));
    }

    @PutMapping("/servers/{id}")
    @Operation(summary = "更新服务器")
    @AuditLog(action = "更新MCP服务", targetType = "McpServer")
    public ApiResponse<McpServer> updateServer(@PathVariable Long id,
                                               @RequestBody McpServerUpdateRequest request) {
        McpServer server = mcpServerService.getServer(id);
        if (request.getName() != null) {
            server.setName(request.getName());
        }
        if (request.getCode() != null) {
            server.setCode(request.getCode());
        }
        if (request.getDescription() != null) {
            server.setDescription(request.getDescription());
        }
        if (request.getTransport() != null) {
            server.setTransport(request.getTransport());
        }
        if (request.getUrl() != null) {
            server.setUrl(request.getUrl());
        }
        // ⚠️ 凭据字段额外判 isBlank：前端不回显凭据、编辑弹窗把该字段初始化为空串
        // （或粘贴带来的全空格）却照样提交，只判 != null 挡不住，会把已存的凭据清空
        if (request.getAuthHeader() != null && !request.getAuthHeader().isBlank()) {
            server.setAuthHeader(request.getAuthHeader());
        }
        if (request.getTimeoutMs() != null) {
            server.setTimeoutMs(request.getTimeoutMs());
        }
        if (request.getMaxResponseChars() != null) {
            server.setMaxResponseChars(request.getMaxResponseChars());
        }
        if (request.getEnabled() != null) {
            server.setEnabled(request.getEnabled());
        }
        return ApiResponse.success("更新成功", mcpServerService.saveServer(server));
    }

    @DeleteMapping("/servers/{id}")
    @Operation(summary = "删除服务器", description = "连带删除其工具行并摘掉 skill 绑定")
    @AuditLog(action = "删除MCP服务", targetType = "McpServer")
    public ApiResponse<Void> deleteServer(@PathVariable Long id) {
        mcpServerService.deleteServer(id);
        return ApiResponse.success("删除成功", null);
    }

    @GetMapping("/servers/{id}/usage")
    @Operation(summary = "统计有多少 skill 在用该服务的工具", description = "供删除确认弹窗显示")
    public ApiResponse<Map<String, Long>> usage(@PathVariable Long id) {
        return ApiResponse.success(Map.of("skillCount", mcpServerService.countSkillsUsing(id)));
    }

    @PostMapping("/servers/{id}/sync")
    @Operation(summary = "同步工具清单")
    @AuditLog(action = "同步MCP工具", targetType = "McpServer")
    public ApiResponse<List<McpTool>> sync(@PathVariable Long id) {
        return ApiResponse.success("同步成功", mcpServerService.sync(id));
    }

    @GetMapping("/servers/{id}/tools")
    @Operation(summary = "该服务的工具清单")
    public ApiResponse<List<McpTool>> listTools(@PathVariable Long id) {
        return ApiResponse.success(mcpServerService.listTools(id));
    }

    @PutMapping("/tools/read-only")
    @Operation(summary = "批量标注只读位")
    @AuditLog(action = "标注MCP工具只读", targetType = "McpTool")
    public ApiResponse<Integer> markReadOnly(@Valid @RequestBody McpToolReadOnlyRequest request) {
        return ApiResponse.success("标注成功",
                mcpServerService.markReadOnly(request.getToolIds(), request.getReadOnly()));
    }
}
```

- [ ] **Step 4: 写控制器测试**

```java
package com.qingyu.care.controller;

import com.qingyu.care.dto.McpServerUpdateRequest;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.service.mcp.McpServerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class McpServerControllerTest {

    @Mock
    private McpServerService mcpServerService;

    @InjectMocks
    private McpServerController controller;

    private McpServer existing() {
        return McpServer.builder().name("订单系统").code("order")
                .url("https://x/mcp").authHeader("Authorization: Bearer real").build();
    }

    @Test
    @DisplayName("局部更新只改传了的字段，没传的保持原值")
    void update_isPartial() {
        McpServer server = existing();
        when(mcpServerService.getServer(1L)).thenReturn(server);
        when(mcpServerService.saveServer(any())).thenAnswer(inv -> inv.getArgument(0));
        McpServerUpdateRequest req = new McpServerUpdateRequest();
        req.setName("新名字");

        controller.updateServer(1L, req);

        assertThat(server.getName()).isEqualTo("新名字");
        assertThat(server.getUrl()).isEqualTo("https://x/mcp");
        assertThat(server.getTimeoutMs()).isEqualTo(10000);
    }

    @Test
    @DisplayName("凭据传空串时保留原值——前端不回显凭据、编辑弹窗会把它初始化为空串照样提交")
    void update_blankCredential_keepsExisting() {
        McpServer server = existing();
        when(mcpServerService.getServer(1L)).thenReturn(server);
        when(mcpServerService.saveServer(any())).thenAnswer(inv -> inv.getArgument(0));
        McpServerUpdateRequest req = new McpServerUpdateRequest();
        req.setAuthHeader("   ");

        controller.updateServer(1L, req);

        assertThat(server.getAuthHeader()).isEqualTo("Authorization: Bearer real");
    }
}
```

- [ ] **Step 5: 跑测试与全部哨兵**

Run: `cd backend && mvn test -Dtest=McpServerControllerTest,UpdateRequest*Test,PutEndpointBodyRegistrationTest`
Expected: 全绿。

⚠️ `PutEndpointBodyRegistrationTest` 大概率会**红**，因为新增了两个 PUT 端点。按它的报错提示把 `McpServerUpdateRequest`（喂 `PUT /v1/mcp/servers/{id}`）与 `McpToolReadOnlyRequest`（喂 `PUT /v1/mcp/tools/read-only`）登记进对应的表；`UpdateRequestPropagationTest` 要求登记「更新路径」——本例映射代码在控制器里，登记为控制器方法。

- [ ] **Step 6: 全量单测**

Run: `cd backend && mvn test`
Expected: 全绿。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/dto/Mcp*.java backend/src/main/java/com/qingyu/care/controller/McpServerController.java backend/src/test/java/com/qingyu/care/controller/McpServerControllerTest.java
git commit -m "feat(mcp): 管理端点与更新 DTO

局部更新用专用 UpdateRequest、字段不写初始化器；凭据字段额外判 isBlank——
前端不回显凭据、编辑弹窗初始化为空串照样提交，只判 != null 会把已存凭据清空。"
```

---

### Task 10: 集成测试（租户隔离）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/McpTenantIsolationIT.java`

**Interfaces:**
- Consumes: 全部后端实现；`support/AbstractPostgresIT` 的 `seedApp(...)`

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.it.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MCP 的跨租户隔离。
 *
 * <p>⚠️ 必须走真实 MockMvc 请求：多租户过滤器只在请求流经 TenantInterceptor 时生效，
 * 直调 service 断言会静默退化成永真。
 *
 * @author ruan
 */
class McpTenantIsolationIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("A 租户建的 MCP server，B 租户按 id 取不到（按不存在处理）")
    void crossTenant_cannotReadServer() throws Exception {
        // 具体的 seed 与登录方式沿用同目录既有 IT（如 SharedToolIT）的写法：
        // seedApp(...) 播种两个租户与应用，取各自的 token 与 appId
        String bodyA = """
                {"name":"订单系统","code":"order","url":"https://mcp.example.com/mcp",
                 "transport":"STREAMABLE_HTTP","authHeader":"Authorization: Bearer secret"}
                """;

        String created = mockMvc.perform(post("/v1/mcp/servers")
                        .header("satoken", tokenOfTenantA())
                        .header("X-App-Id", appIdOfTenantA())
                        .contentType("application/json")
                        .content(bodyA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        Long serverId = extractId(created);

        // B 租户拿着同一个 id 取——必须是「不存在」而不是 403，避免枚举探测
        mockMvc.perform(get("/v1/mcp/servers/" + serverId + "/tools")
                        .header("satoken", tokenOfTenantB())
                        .header("X-App-Id", appIdOfTenantB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("B 租户批量标注只读时，A 租户的工具不受影响")
    void crossTenant_cannotMarkOthersReadOnly() throws Exception {
        Long foreignToolId = seedMcpToolForTenantA();

        mockMvc.perform(put("/v1/mcp/tools/read-only")
                        .header("satoken", tokenOfTenantB())
                        .header("X-App-Id", appIdOfTenantB())
                        .contentType("application/json")
                        .content("{\"toolIds\":[" + foreignToolId + "],\"readOnly\":true}"))
                .andExpect(status().isOk())
                // 逐条过守卫，越权那条被跳过，实际更新 0 条
                .andExpect(jsonPath("$.data").value(0));
    }
}
```

⚠️ 上面的 `tokenOfTenantA()` / `appIdOfTenantA()` / `extractId(...)` / `seedMcpToolForTenantA()` 是占位命名——**实现时照同目录既有 IT（`SharedToolIT`、`CreateTenantInjectionIT`）的真实写法替换**，不要新造一套种子机制。

- [ ] **Step 2: 停掉本机 dev 后端**

⚠️ 跑 IT 前必须先停掉 :8080 上的 dev 后端，否则 `WebhookDeliveryIT` 必红（它与 dev 后端共用本机 Redis 的消费组 `webhook-workers`，消息会被抢走 ACK，表现为一条看不出与 Redis 有关的 10 秒超时）。

Run: `netstat -ano | grep ":8080.*LISTENING"`
Expected: 无输出。有则先停。

- [ ] **Step 3: 跑 IT**

Run: `cd backend && mvn verify -Dit.test=McpTenantIsolationIT`
Expected: PASS。需要 Docker Desktop 运行。

- [ ] **Step 4: 跑全量 verify**

Run: `cd backend && mvn verify`
Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/McpTenantIsolationIT.java
git commit -m "test(mcp): 跨租户隔离 IT

走真实 MockMvc 请求——多租户过滤器只在请求流经 TenantInterceptor 时生效，
直调 service 的断言会静默退化成永真。"
```

---

### Task 11: 前端 API 与 MCP 服务页

**Files:**
- Create: `frontend/src/api/mcp.ts`
- Create: `frontend/src/pages/McpServerPage.tsx`
- Create: `frontend/src/components/mcp/McpToolList.tsx`
- Modify: `frontend/src/App.tsx`（加路由，包 `<RequireApp>`）
- Modify: `frontend/src/layouts/MainLayout.tsx`（加侧边菜单项）
- Test: `frontend/src/pages/McpServerPage.test.tsx`

**Interfaces:**
- Consumes: 后端 `/v1/mcp/**`（Task 9）
- Produces: `mcpApi.listServers()` / `createServer(body)` / `updateServer(id, body)` / `deleteServer(id)` / `syncTools(id)` / `listTools(id)` / `markReadOnly(toolIds, readOnly)`

- [ ] **Step 1: 建 api service**

`frontend/src/api/mcp.ts`：

```ts
import client from './client'
import type { ApiResponse } from './types'

export interface McpServer {
  id: number
  name: string
  code: string
  description?: string
  transport: 'STREAMABLE_HTTP' | 'SSE'
  url: string
  authHeader?: string
  timeoutMs: number
  maxResponseChars: number
  enabled: boolean
  lastSyncedAt?: string
  lastSyncError?: string
}

export interface McpTool {
  id: number
  mcpServerId: number
  name: string
  description?: string
  inputSchema?: string
  status: 'ACTIVE' | 'DEPRECATED'
  readOnly: boolean
  readOnlyHint?: boolean
}

export const mcpApi = {
  listServers: () => client.get<never, ApiResponse<McpServer[]>>('/v1/mcp/servers'),
  createServer: (body: Partial<McpServer>) =>
    client.post<never, ApiResponse<McpServer>>('/v1/mcp/servers', body),
  updateServer: (id: number, body: Partial<McpServer>) =>
    client.put<never, ApiResponse<McpServer>>(`/v1/mcp/servers/${id}`, body),
  deleteServer: (id: number) =>
    client.delete<never, ApiResponse<void>>(`/v1/mcp/servers/${id}`),
  usage: (id: number) =>
    client.get<never, ApiResponse<{ skillCount: number }>>(`/v1/mcp/servers/${id}/usage`),
  syncTools: (id: number) =>
    client.post<never, ApiResponse<McpTool[]>>(`/v1/mcp/servers/${id}/sync`),
  listTools: (id: number) =>
    client.get<never, ApiResponse<McpTool[]>>(`/v1/mcp/servers/${id}/tools`),
  markReadOnly: (toolIds: number[], readOnly: boolean) =>
    client.put<never, ApiResponse<number>>('/v1/mcp/tools/read-only', { toolIds, readOnly }),
}
```

- [ ] **Step 2: 写失败的测试**

`frontend/src/pages/McpServerPage.test.tsx`：

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const { mockApi, mockToast } = vi.hoisted(() => ({
  mockApi: {
    listServers: vi.fn(),
    syncTools: vi.fn(),
    listTools: vi.fn(),
    markReadOnly: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/api/mcp', () => ({ mcpApi: mockApi }))
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: mockToast }) }))

import McpServerPage from './McpServerPage'

describe('McpServerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.listServers.mockResolvedValue({
      code: 200, message: 'ok',
      data: [{ id: 1, name: '订单系统', code: 'order', url: 'https://x/mcp', transport: 'STREAMABLE_HTTP', timeoutMs: 10000, maxResponseChars: 4000, enabled: true }],
    })
    mockApi.listTools.mockResolvedValue({ code: 200, message: 'ok', data: [] })
  })

  it('同步失败时弹 destructive toast，不静默吞掉业务错误', async () => {
    // 业务异常是 HTTP 200 + body.code，axios 不会 reject
    mockApi.syncTools.mockResolvedValue({ code: 400, message: '同步失败：连接超时', data: null })
    render(<McpServerPage />)
    await waitFor(() => expect(screen.getByText('订单系统')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: /同步工具/ }))

    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'destructive', description: '同步失败：连接超时' }),
      ),
    )
  })

  it('同步成功后刷新工具清单', async () => {
    mockApi.syncTools.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    render(<McpServerPage />)
    await waitFor(() => expect(screen.getByText('订单系统')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: /同步工具/ }))

    await waitFor(() => expect(mockApi.listTools).toHaveBeenCalledWith(1))
  })
})
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd frontend && pnpm test McpServerPage`
Expected: FAIL，模块不存在。

- [ ] **Step 4: 实现页面**

关键约束（写代码时逐条对照）：

1. **每个写操作都要判 `res.code !== 200`**：
```tsx
const handleSync = async (serverId: number) => {
  try {
    const res = await mcpApi.syncTools(serverId)
    if (res.code !== 200) throw new Error(res.message)
    toast({ description: '同步成功' })
    await loadTools(serverId)
  } catch (e) {
    toast({ variant: 'destructive', description: e instanceof Error ? e.message : '同步失败' })
  }
}
```
2. **工具清单要有「按远端建议全选只读」批量按钮**，一次调 `markReadOnly(ids, true)`，`ids` 取 `tools.filter(t => t.readOnlyHint).map(t => t.id)`。没有它，接一个 20 工具的 server 要点 20 次，用户会转而要求「自动信任远端」。
3. **`DEPRECATED` 工具显示徽标**并禁用其只读开关（远端已下线，标了也没用）。
4. **删除确认弹窗先调 `usage(id)`** 显示「该服务的工具正被 N 个技能使用」。
5. 页面顶部显示 `lastSyncedAt`；`lastSyncError` 非空时显示为警示条。

- [ ] **Step 5: 加路由与菜单**

`App.tsx` 中与其它应用级配置页并列（必须包 `<RequireApp>`，未选应用时显示空状态）：

```tsx
<Route path="/mcp" element={<RequireApp><McpServerPage /></RequireApp>} />
```

`MainLayout.tsx` 的菜单配置里加一项（放在「工具配置」附近），角色可见性与工具配置一致。

- [ ] **Step 6: 跑测试与 lint**

Run: `cd frontend && pnpm test && pnpm lint && pnpm build`
Expected: 测试全绿；lint 的既有 error 基线**零新增**；build 成功。

- [ ] **Step 7: 提交**

```bash
git add frontend/src/api/mcp.ts frontend/src/pages/McpServerPage.tsx frontend/src/components/mcp/ frontend/src/App.tsx frontend/src/layouts/MainLayout.tsx frontend/src/pages/McpServerPage.test.tsx
git commit -m "feat(mcp): 前端 MCP 服务页

写操作显式判 res.code——业务异常是 HTTP 200 + body.code，不判会静默吞掉。

工具清单带「按远端建议全选只读」批量按钮：摩擦要在 UI 层解决，否则用户会
转而要求自动信任远端自报的 readOnlyHint，安全判据就是这样被磨掉的。"
```

---

### Task 12: Skill 编辑页勾选 MCP 工具

**Files:**
- Modify: `frontend/src/pages/SkillConfigPage.tsx`（或既有的 skill 编辑组件，先 grep `toolIds` 定位）
- Test: 在既有 skill 页测试文件追加用例（无则新建 `SkillConfigPage.test.tsx`）

**Interfaces:**
- Consumes: `mcpApi.listServers()` / `mcpApi.listTools(serverId)`（Task 11）
- Produces: skill 表单提交时同时带 `toolIds` 与 `mcpToolIds`

- [ ] **Step 1: 定位既有实现**

Run: `cd frontend && grep -rn "toolIds" src/ --include=*.tsx --include=*.ts | grep -v test`
按输出定位 skill 编辑表单所在文件。

- [ ] **Step 2: 写失败的测试**

在该页测试中追加：

```tsx
it('保存 skill 时把勾选的 MCP 工具作为 mcpToolIds 一并提交', async () => {
  // 桩 mcpApi 返回一个 server 与其下一个只读工具
  await userEvent.click(screen.getByLabelText('order__query_order'))
  await userEvent.click(screen.getByRole('button', { name: /保存/ }))

  await waitFor(() =>
    expect(mockSkillApi.update).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ mcpToolIds: [10] }),
    ),
  )
})
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd frontend && pnpm test Skill`
Expected: FAIL。

- [ ] **Step 4: 实现**

工具勾选区分成两组渲染：「HTTP 工具」（现有 `toolIds`）与「MCP 工具」（新 `mcpToolIds`，按 server 分组、标签显示 `{code}__{name}`）。

⚠️ **MCP 工具列表只列出 `readOnly === true 且 status === 'ACTIVE'` 的**——与后端 `resolve()` 的过滤条件保持一致。列出勾不上的工具只会让人以为是 bug。同时给一句说明文案指向 MCP 服务页去标注只读。

- [ ] **Step 5: 跑测试**

Run: `cd frontend && pnpm test && pnpm lint`
Expected: 全绿，lint 零新增 error。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/pages/ frontend/src/components/
git commit -m "feat(mcp): skill 编辑页勾选 MCP 工具

只列 readOnly 且 ACTIVE 的工具，与后端 resolve() 的过滤条件一致——列出勾不上
的工具只会让人以为是 bug。"
```

---

### Task 13: 文档同步与收尾

**Files:**
- Modify: `CLAUDE.md`（架构要点新增一节 + 手工验证清单）
- Modify: `SaaS平台规划.md`（Phase 5.2 的 MCP 项打勾）
- Modify: `docs/superpowers/specs/2026-07-24-chat-tool-calling-design.md`（§10 第 4 项、§11 最后一条标记已闭合）
- Modify: `scripts/manual-smoke-hints.sh`（新增路径 → 章节标题映射）
- Modify: `docs/使用手册.md`（新增 MCP 服务的操作说明）

- [ ] **Step 1: 给 CLAUDE.md 加架构要点**

在「平台共享工具（`AiTool.scope`）」一节之后新增「MCP 接入」一节，内容取自 spec 的 §2/§5/§6，**只写会写错的地方**，不复述设计：连接不缓存的理由、只读门槛落在解析查询上、同步不覆盖 `readOnly`、`DEPRECATED` 不删行、工具名前缀、SSRF 只能走 webflux 那条线、删 server 要清理 skill 绑定。

- [ ] **Step 2: 加手工验证清单**

新增一节「改动 MCP 接入链路后必须手工验证」（标题要与 `manual-smoke-hints.sh` 里登记的一致）：

1. **真连一个 MCP server** 走完「登记 → 同步 → 标只读 → 绑进 skill → 对话触发」，确认出站请求带着配置的凭据。⚠️ 凭据若在某个环节被清掉，症状是第三方返 401、模型拿着错误响应继续编答案，**全程无异常无日志**（同平台共享工具那条）。
2. **`DEPRECATED` 路径**：远端下线一个工具后再同步，确认是标记而非删行、界面上有提示、且该工具从 skill 的可勾选列表消失。
3. **SSRF**：URL 指向 `169.254.169.254` 确认被拒；再用一个 TTL=0、解析结果会翻转的域名验证 rebinding 也被挡。
4. **凭据不被空串清空**：编辑 server 只改名字保存，确认 `authHeader` 仍在（前端不回显凭据）。

- [ ] **Step 3: 更新 `manual-smoke-hints.sh`**

加一条映射：`backend/src/main/java/com/qingyu/care/service/mcp/` 与 `frontend/src/pages/McpServerPage.tsx` → 「改动 MCP 接入链路后必须手工验证」。

⚠️ 脚本**只存路径 → 章节标题的映射，不复述条目内容**——复述会产生第二份会漂移的文案。

Run: `./scripts/manual-smoke-hints.sh`
Expected: 退出码 0（章节标题在 CLAUDE.md 里能找到；找不到会 exit 1）。

- [ ] **Step 4: 标记规划与旧 spec**

- `SaaS平台规划.md` §5.2 的「MCP 接入」条目打勾，注明完成日期与设计文档路径。
- `2026-07-24-chat-tool-calling-design.md` §10 第 4 项标记已完成、§11 最后一条「MCP 连接缓存的生命周期」标记已闭合（答案：不缓存）。

- [ ] **Step 5: 全量验证**

Run: `cd backend && mvn verify`（先确认 :8080 无 dev 后端）
Run: `cd frontend && pnpm test && pnpm lint && pnpm build`
Expected: 全绿。

⚠️ 本期**没有**改 `widget/src/`，所以不需要重新 build `widget.js`。若因故改了，必须 `cd widget && pnpm build` 并提交产物——`buildFreshness.test.ts` 会红。

- [ ] **Step 6: 提交**

```bash
git add CLAUDE.md SaaS平台规划.md scripts/manual-smoke-hints.sh docs/
git commit -m "docs(mcp): 同步开发指引、规划与手工验证清单

CLAUDE.md 只记会写错的地方，不复述设计；提示脚本只存路径到章节标题的映射，
不复述条目内容（复述会产生第二份会漂移的文案）。"
```

---

## 自查（写完计划后对照 spec 跑的）

**Spec 覆盖**：§1 目标 → Task 1-12；§2.1 不用 starter → Task 1 Step 1 + Global Constraints；§2.2 只做 HTTP 类传输 → Task 1 枚举 + Task 2；§2.3 只读判据 → Task 1 实体 + Task 6 + Task 7 查询 + Task 11 批量按钮；§2.4 连接不缓存 → Task 3；§2.5 只手动同步 → Task 6（无调度器）；§2.6 独立表 → Task 1；§3 数据模型 → Task 1；§4 组件划分 → Task 2-6；§5 运行时 → Task 3-4-7；§6.1 readOnly 不覆盖 → Task 6 Step 3+5（含变异验证）；§6.2 传输选错 → Task 2 类注释 + 单测；§6.3 门槛在解析处 → Task 7；§6.4 DEPRECATED 过滤 → Task 7 查询；§6.5 同步失败不误伤 → Task 6 测试；§6.6 删 server 清理绑定 → Task 8；§7 端点与前端 → Task 9/11/12；§8 测试策略 → 各任务内 + Task 10；§9 SSRF → Task 2；§10 范围外 → 无任务（正确）。

**无遗漏项。**

**类型一致性**：`McpToolDescriptor` 四个组件名在 Task 3 定义、Task 6 使用一致；`exposedName(McpServer, McpTool)` 在 Task 4 定义、Task 7 使用一致；`findByIdInAndStatusAndReadOnlyTrue(Collection, McpToolStatus)` 在 Task 1 定义、Task 7 使用一致；`markReadOnly(List<Long>, boolean)` 在 Task 5 定义、Task 9 使用一致。

**已知的两处「实现时按实际写法替换」**（不是占位符，是有意的指引）：Task 10 的 IT 种子方法要照同目录既有 IT 的真实写法写；Task 12 要先 grep 定位 skill 编辑表单所在文件。两处都给了定位方式与约束，不是「TBD」。


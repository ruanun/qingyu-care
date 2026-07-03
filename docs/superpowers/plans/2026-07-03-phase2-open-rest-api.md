# Phase 2 · 开放 REST API 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付外部系统可凭 appKey/appSecret 调用的开放对话 REST API，含 Redis 分布式限流、按天用量计量，以及应用级「接入配置」管理页。

**Architecture:** 独立前缀 `/open/v1/**` + 独立包 `controller.open`，与内部 Sa-Token 接口物理隔离。认证拦截器校验 appKey/appSecret 后把 tenantId/appId 写入 `TenantContext`，对话逻辑全部委托现有 `ChatService`。限流用 Bucket4j + Lettuce(Redis)，计量用 `sys_api_usage` 按 (appId,日期) 聚合异步累加，对外错误走标准 HTTP 状态码。

**Tech Stack:** Spring Boot 3.4.1 / Java 17 / Spring Data JPA / Lettuce(Redis) / Bucket4j 8.10.1 / JUnit5 + Mockito；React 19 + Vite + shadcn/ui。

设计文档：`docs/superpowers/specs/2026-07-03-phase2-open-rest-api-design.md`

---

## 约定与前置（所有 Task 通用）

- 后端根目录 `D:/Code/m/qingyu-care/backend`，前端 `D:/Code/m/qingyu-care/frontend`（Windows，bash，正斜杠）。
- **检查点 = 编译 + 跑相关单测**（前端为 `pnpm build`）。本计划由编排者在每个里程碑评审通过后统一 commit，子代理**不各自 git 提交**。
- 编译：`cd /d/Code/m/qingyu-care/backend && mvn -q compile`；单测：`mvn -q -Dtest=<类名> test`（新测试用 `@ExtendWith(MockitoExtension.class)`，不连库/Redis）。
- 已有可复用：`ChatService`（chat/streamChat/getSession/requestHandover/getSessionMessages）、`ChatRequest`（sessionId?/userId/message/knowledgeBaseId?/stream）、`ChatResponse`（sessionId/content/confidenceScore/knowledgeHits/suggestHandover/**tokenUsage**）、`Application`（appKey/appSecret/enabled/tenant）、`ApplicationRepository.findByAppKey`、`TenantContext`（tenantId/appId）、`@Async`（AsyncConfig 已启用）。
- **限流依赖版本**：Bucket4j `8.10.1`（`bucket4j_jdk17-*` 系列）。若该版本/坐标在 Maven Central 解析失败，用 context7 或 Maven Central 确认当前可用版本后对齐（仅此依赖坐标允许按实调整）。

---

## 文件结构总览

**后端新增**
- `dto/open/OpenChatRequest.java`、`OpenChatResponse.java`、`OpenSessionResponse.java`、`OpenErrorResponse.java`
- `exception/OpenApiException.java`
- `controller/open/OpenApiExceptionHandler.java`、`OpenChatController.java`、`OpenSessionController.java`
- `service/OpenApiAuthService.java`
- `config/OpenApiAuthInterceptor.java`、`RateLimitConfig.java`
- `service/RateLimitService.java`
- `entity/SysApiUsage.java`、`repository/SysApiUsageRepository.java`、`service/ApiUsageService.java`

**后端修改**
- `pom.xml`、`src/main/resources/application.yml`
- `config/SaTokenConfig.java`（排除 `/open/v1/**`）、`config/WebConfig.java`（注册开放拦截器）
- `entity/Application.java`（加 `rateLimitPerMinute`）、`controller/ApplicationController.java`（update 透传）

**前端新增**：`api/integration.ts`、`pages/IntegrationPage.tsx`
**前端修改**：`App.tsx`、`layouts/MainLayout.tsx`

---

# 里程碑 M1：依赖与开关

## Task 1: 引入 Redis + Bucket4j 依赖、配置、Sa-Token 放行

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/qingyu/care/config/SaTokenConfig.java`

- [ ] **Step 1: pom.xml 加依赖**

在 `<properties>` 加：

```xml
        <bucket4j.version>8.10.1</bucket4j.version>
```

在 `<dependencies>` 加（Redis 提供受管 Lettuce 与健康检查；Bucket4j 提供分布式限流）：

```xml
        <!-- Redis (Lettuce) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <!-- Bucket4j 分布式限流 (Lettuce) -->
        <dependency>
            <groupId>com.bucket4j</groupId>
            <artifactId>bucket4j_jdk17-core</artifactId>
            <version>${bucket4j.version}</version>
        </dependency>
        <dependency>
            <groupId>com.bucket4j</groupId>
            <artifactId>bucket4j_jdk17-redis-common</artifactId>
            <version>${bucket4j.version}</version>
        </dependency>
        <dependency>
            <groupId>com.bucket4j</groupId>
            <artifactId>bucket4j_jdk17-lettuce</artifactId>
            <version>${bucket4j.version}</version>
        </dependency>
```

- [ ] **Step 2: application.yml 加 Redis 与默认限流额度**

在 `spring:` 下加（与 `datasource` 同级）：

```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

在 `qingyu.care:` 下加：

```yaml
    # 开放 API 配置
    openapi:
      # 单个应用默认每分钟请求上限（应用可覆盖）
      default-rate-limit-per-minute: 60
```

- [ ] **Step 3: SaTokenConfig 放行开放 API**

在 `notMatch(...)` 参数列表末尾加一行 `"/open/v1/**"`：

```java
                    .notMatch(
                            "/v1/auth/login",
                            "/v1/auth/register",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/actuator/**",
                            "/open/v1/**"
                    )
```

- [ ] **Step 4: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS（依赖成功下载；若 bucket4j 坐标解析失败，按「约定与前置」调整版本）

---

# 里程碑 M2：认证与对外异常

## Task 2: OpenApiException + OpenApiAuthService（TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/exception/OpenApiException.java`
- Create: `backend/src/main/java/com/qingyu/care/service/OpenApiAuthService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/OpenApiAuthServiceTest.java`

- [ ] **Step 1: 创建 OpenApiException**

```java
package com.qingyu.care.exception;

/**
 * 开放 API 专用异常，携带标准 HTTP 状态码。
 */
public class OpenApiException extends RuntimeException {
    private final int status;

    public OpenApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
```

- [ ] **Step 2: 先写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.repository.ApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenApiAuthServiceTest {

    @Mock ApplicationRepository applicationRepository;
    @InjectMocks OpenApiAuthService service;

    private Application app(String key, String secret, boolean enabled) {
        return Application.builder()
                .id(1L).name("测试应用").appKey(key).appSecret(secret).enabled(enabled)
                .tenant(Tenant.builder().id(9L).build())
                .build();
    }

    @Test
    void authenticate_returnsApp_whenCredentialsValid() {
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(app("app_x", "sec", true)));

        Application result = service.authenticate("app_x", "sec");

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void authenticate_401_whenHeaderMissing() {
        assertThatThrownBy(() -> service.authenticate(null, "sec"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticate_401_whenAppKeyNotFound() {
        when(applicationRepository.findByAppKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("nope", "sec"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticate_401_whenSecretMismatch() {
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(app("app_x", "right", true)));

        assertThatThrownBy(() -> service.authenticate("app_x", "wrong"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticate_403_whenAppDisabled() {
        when(applicationRepository.findByAppKey("app_x")).thenReturn(Optional.of(app("app_x", "sec", false)));

        assertThatThrownBy(() -> service.authenticate("app_x", "sec"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=OpenApiAuthServiceTest test`
Expected: 编译失败（OpenApiAuthService 不存在）

- [ ] **Step 4: 实现 OpenApiAuthService**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.repository.ApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 开放 API 认证：校验 appKey/appSecret，返回对应应用。
 */
@Service
@RequiredArgsConstructor
public class OpenApiAuthService {

    private final ApplicationRepository applicationRepository;

    public Application authenticate(String appId, String apiKey) {
        if (appId == null || appId.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new OpenApiException(401, "缺少 X-App-Id / X-Api-Key");
        }
        Application app = applicationRepository.findByAppKey(appId)
                .orElseThrow(() -> new OpenApiException(401, "无效的凭据"));
        if (!apiKey.equals(app.getAppSecret())) {
            throw new OpenApiException(401, "无效的凭据");
        }
        if (Boolean.FALSE.equals(app.getEnabled())) {
            throw new OpenApiException(403, "应用已停用");
        }
        return app;
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=OpenApiAuthServiceTest test`
Expected: Tests run: 5, Failures: 0

## Task 3: 对外错误体 + OpenApiExceptionHandler（TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/open/OpenErrorResponse.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/open/OpenApiExceptionHandler.java`
- Test: `backend/src/test/java/com/qingyu/care/controller/open/OpenApiExceptionHandlerTest.java`

- [ ] **Step 1: 创建 OpenErrorResponse**

```java
package com.qingyu.care.dto.open;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 开放 API 统一错误体：{ "error": { "code": 401, "message": "..." } }
 */
@Data
public class OpenErrorResponse {
    private final Error error;

    @Data
    @AllArgsConstructor
    public static class Error {
        private int code;
        private String message;
    }

    public static OpenErrorResponse of(int code, String message) {
        return new OpenErrorResponse(new Error(code, message));
    }
}
```

- [ ] **Step 2: 先写失败测试**

```java
package com.qingyu.care.controller.open;

import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiExceptionHandlerTest {

    private final OpenApiExceptionHandler handler = new OpenApiExceptionHandler();

    @Test
    void mapsOpenApiExceptionToItsStatus() {
        ResponseEntity<OpenErrorResponse> resp =
                handler.handleOpenApi(new OpenApiException(429, "太频繁"));
        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(resp.getBody().getError().getCode()).isEqualTo(429);
        assertThat(resp.getBody().getError().getMessage()).isEqualTo("太频繁");
    }

    @Test
    void mapsResourceNotFoundTo404() {
        ResponseEntity<OpenErrorResponse> resp =
                handler.handleNotFound(new ResourceNotFoundException("会话", "s1"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsGenericTo500() {
        ResponseEntity<OpenErrorResponse> resp =
                handler.handleGeneric(new RuntimeException("boom"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=OpenApiExceptionHandlerTest test`
Expected: 编译失败（OpenApiExceptionHandler 不存在）

- [ ] **Step 4: 实现 OpenApiExceptionHandler**

```java
package com.qingyu.care.controller.open;

import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.exception.ResourceNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 开放 API 异常处理：仅作用于 controller.open 包，返回标准 HTTP 状态码，
 * 优先级最高，避免落到内部 GlobalExceptionHandler 的 body-code 处理。
 */
@RestControllerAdvice(basePackages = "com.qingyu.care.controller.open")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpenApiExceptionHandler {

    @ExceptionHandler(OpenApiException.class)
    public ResponseEntity<OpenErrorResponse> handleOpenApi(OpenApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(OpenErrorResponse.of(e.getStatus(), e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<OpenErrorResponse> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(OpenErrorResponse.of(404, e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public ResponseEntity<OpenErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(OpenErrorResponse.of(400, "请求参数错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenErrorResponse> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(OpenErrorResponse.of(500, "服务器内部错误"));
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=OpenApiExceptionHandlerTest test`
Expected: Tests run: 3, Failures: 0

## Task 4: OpenApiAuthInterceptor（认证部分）+ 注册

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/OpenApiAuthInterceptor.java`
- Modify: `backend/src/main/java/com/qingyu/care/config/WebConfig.java`

- [ ] **Step 1: 创建 OpenApiAuthInterceptor（本 Task 只做认证 + 上下文；限流在 M4 加入）**

```java
package com.qingyu.care.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.open.OpenErrorResponse;
import com.qingyu.care.entity.Application;
import com.qingyu.care.exception.OpenApiException;
import com.qingyu.care.service.OpenApiAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 开放 API 拦截器：校验 appKey/appSecret，将 tenantId/appId 写入 TenantContext，
 * 供下游 ChatService 复用多租户隔离。认证失败直接写出标准 HTTP 状态码。
 */
@Component
@RequiredArgsConstructor
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_APP = "openApiApplication";

    private final OpenApiAuthService openApiAuthService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        try {
            Application app = openApiAuthService.authenticate(
                    request.getHeader("X-App-Id"), request.getHeader("X-Api-Key"));
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            request.setAttribute(ATTR_APP, app);
            return true;
        } catch (OpenApiException e) {
            writeError(response, e.getStatus(), e.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(OpenErrorResponse.of(status, message)));
    }
}
```

- [ ] **Step 2: WebConfig 注册开放拦截器**

给 `WebConfig` 加字段 `private final OpenApiAuthInterceptor openApiAuthInterceptor;`（已有 `@RequiredArgsConstructor`），并在 `addInterceptors` 内追加注册（放在 tenantInterceptor 注册之后）：

```java
        // 注册开放 API 拦截器（仅 /open/v1/**）
        registry.addInterceptor(openApiAuthInterceptor)
                .addPathPatterns("/open/v1/**");
```

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

---

# 里程碑 M3：开放端点

## Task 5: 开放 DTO + OpenChatController（对话/流式）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/open/OpenChatRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/open/OpenChatResponse.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`

- [ ] **Step 1: 创建 OpenChatRequest**

```java
package com.qingyu.care.dto.open;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 开放对话请求 */
@Data
public class OpenChatRequest {
    private String sessionId;
    private String userId;
    @NotBlank(message = "消息内容不能为空")
    private String message;
    private Long knowledgeBaseId;
}
```

- [ ] **Step 2: 创建 OpenChatResponse**

```java
package com.qingyu.care.dto.open;

import com.qingyu.care.dto.ChatResponse;
import lombok.Builder;
import lombok.Data;

/** 开放对话响应（由内部 ChatResponse 映射，仅暴露对外必要字段） */
@Data
@Builder
public class OpenChatResponse {
    private String sessionId;
    private String reply;
    private boolean handoverSuggested;

    public static OpenChatResponse from(ChatResponse r) {
        return OpenChatResponse.builder()
                .sessionId(r.getSessionId())
                .reply(r.getContent())
                .handoverSuggested(r.isSuggestHandover())
                .build();
    }
}
```

- [ ] **Step 3: 创建 OpenChatController**

```java
package com.qingyu.care.controller.open;

import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.open.OpenChatRequest;
import com.qingyu.care.dto.open.OpenChatResponse;
import com.qingyu.care.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 开放对话 API（认证/限流由 OpenApiAuthInterceptor 处理；租户上下文已注入）。
 */
@RestController
@RequestMapping("/open/v1/chat")
@RequiredArgsConstructor
public class OpenChatController {

    private final ChatService chatService;

    @PostMapping("/completions")
    public OpenChatResponse completions(@Valid @RequestBody OpenChatRequest req) {
        ChatResponse resp = chatService.chat(toInternal(req, false));
        return OpenChatResponse.from(resp);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody OpenChatRequest req) {
        return chatService.streamChat(toInternal(req, true));
    }

    private ChatRequest toInternal(OpenChatRequest req, boolean stream) {
        return ChatRequest.builder()
                .sessionId(req.getSessionId())
                .userId(req.getUserId())
                .message(req.getMessage())
                .knowledgeBaseId(req.getKnowledgeBaseId())
                .stream(stream)
                .build();
    }
}
```

> 计量将在 Task 11 加入本控制器（completions 成功后累加）。

- [ ] **Step 4: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

## Task 6: OpenSessionController（会话查询 + 转人工）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/open/OpenSessionResponse.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/open/OpenSessionController.java`

- [ ] **Step 1: 创建 OpenSessionResponse**

```java
package com.qingyu.care.dto.open;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 开放会话响应：会话状态 + 消息历史 */
@Data
@Builder
public class OpenSessionResponse {
    private String sessionId;
    private String status;
    private List<Msg> messages;

    @Data
    @Builder
    public static class Msg {
        private String role;
        private String content;
        private LocalDateTime createdAt;
    }

    public static OpenSessionResponse of(ChatSession session, List<ChatMessage> messages) {
        return OpenSessionResponse.builder()
                .sessionId(session.getSessionId())
                .status(session.getStatus() != null ? session.getStatus().name() : null)
                .messages(messages.stream()
                        .map(m -> Msg.builder()
                                .role(m.getRole() != null ? m.getRole().name() : null)
                                .content(m.getContent())
                                .createdAt(m.getCreatedAt())
                                .build())
                        .toList())
                .build();
    }
}
```

> 依赖字段：`ChatSession.getSessionId()/getStatus()`、`ChatMessage.getRole()/getContent()/getCreatedAt()`（均为既有实体字段）。实现时若某字段名不符，按实体实际字段调整。

- [ ] **Step 2: 创建 OpenSessionController**

```java
package com.qingyu.care.controller.open;

import com.qingyu.care.dto.open.OpenSessionResponse;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 开放会话 API：查询会话 + 请求转人工。
 */
@RestController
@RequestMapping("/open/v1/sessions")
@RequiredArgsConstructor
public class OpenSessionController {

    private final ChatService chatService;

    @GetMapping("/{sessionId}")
    public OpenSessionResponse getSession(@PathVariable String sessionId) {
        ChatSession session = chatService.getSession(sessionId);
        return OpenSessionResponse.of(session, chatService.getSessionMessages(sessionId));
    }

    @PostMapping("/{sessionId}/handover")
    public Map<String, Object> handover(@PathVariable String sessionId) {
        chatService.requestHandover(sessionId);
        return Map.of("sessionId", sessionId, "status", "handover_requested");
    }
}
```

> 会话归属隔离：`ChatService.getSession/getSessionMessages` 经 `TenantContext`（拦截器已按应用注入 tenantId/appId）过滤，跨应用会话不可见。

- [ ] **Step 3: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

---

# 里程碑 M4：限流

## Task 7: 限流配置 + RateLimitService（额度解析 TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/RateLimitConfig.java`
- Create: `backend/src/main/java/com/qingyu/care/service/RateLimitService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/RateLimitServiceTest.java`

- [ ] **Step 1: 创建 RateLimitConfig（Lettuce 连接 + ProxyManager Bean）**

```java
package com.qingyu.care.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 限流所需的 Redis(Lettuce) + Bucket4j ProxyManager 装配。
 */
@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return RedisClient.create("redis://" + host + ":" + port);
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> rateLimitProxyManager(StatefulRedisConnection<String, byte[]> connection) {
        return Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(120)))
                .build();
    }
}
```

> 若 `io.github.bucket4j.*` 包路径与安装版本不符（8.x 曾用 `io.github.bucket4j`，新版为 `io.github.bucket4j`/`com.bucket4j` 混用），以 IDE/编译报错为准对齐 import（坐标已在 Task 1 固定）。

- [ ] **Step 2: 先写失败测试（只测额度解析纯逻辑，不连 Redis）**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

    @Test
    void resolveLimit_usesAppOverride_whenPresent() {
        Application app = Application.builder().rateLimitPerMinute(120).build();
        assertThat(RateLimitService.resolveLimit(app, 60)).isEqualTo(120);
    }

    @Test
    void resolveLimit_usesDefault_whenAppLimitNull() {
        Application app = Application.builder().rateLimitPerMinute(null).build();
        assertThat(RateLimitService.resolveLimit(app, 60)).isEqualTo(60);
    }

    @Test
    void resolveLimit_usesDefault_whenAppLimitNonPositive() {
        Application app = Application.builder().rateLimitPerMinute(0).build();
        assertThat(RateLimitService.resolveLimit(app, 60)).isEqualTo(60);
    }
}
```

> 本测试依赖 `Application.rateLimitPerMinute`（Task 9 加入）。**执行顺序上先做 Task 9 的字段新增再跑本测试**；或在本 Task 内先只加该字段。为避免依赖倒置，实现时按 Task 9 先加字段。

- [ ] **Step 3: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=RateLimitServiceTest test`
Expected: 编译失败（RateLimitService / resolveLimit 不存在）

- [ ] **Step 4: 实现 RateLimitService**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.Application;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 基于 Bucket4j + Redis 的按 appKey 分布式限流。Redis 故障时 fail-open（放行）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final ProxyManager<String> proxyManager;

    /** 解析生效额度：应用级覆盖优先，非正/为空回落全局默认 */
    public static int resolveLimit(Application app, int defaultLimit) {
        Integer override = app.getRateLimitPerMinute();
        return (override != null && override > 0) ? override : defaultLimit;
    }

    /** 消费一个令牌；被限流返回 false。Redis 异常时放行（true）。 */
    public boolean tryConsume(String appKey, int limitPerMinute) {
        try {
            Supplier<BucketConfiguration> cfg = () -> BucketConfiguration.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(limitPerMinute)
                            .refillGreedy(limitPerMinute, Duration.ofMinutes(1))
                            .build())
                    .build();
            Bucket bucket = proxyManager.getProxy("ratelimit:" + appKey, cfg);
            return bucket.tryConsume(1);
        } catch (Exception e) {
            log.warn("限流组件异常，放行本次请求: {}", e.getMessage());
            return true;
        }
    }
}
```

> `Bandwidth`/`Bucket`/`BucketConfiguration` 的包名以固定版本 8.10.1 为准；若 import 报错，按编译提示对齐（同一 Bucket4j 版本内 API 稳定）。

- [ ] **Step 5: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=RateLimitServiceTest test`
Expected: Tests run: 3, Failures: 0

## Task 8: 把限流接入拦截器

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/config/OpenApiAuthInterceptor.java`

- [ ] **Step 1: 拦截器注入依赖并在认证后限流**

给 `OpenApiAuthInterceptor` 增加两个字段（`@RequiredArgsConstructor` 自动注入）：

```java
    private final com.qingyu.care.service.RateLimitService rateLimitService;

    @org.springframework.beans.factory.annotation.Value("${qingyu.care.openapi.default-rate-limit-per-minute:60}")
    private int defaultRateLimit;
```

在 `preHandle` 里，认证成功、设置上下文之后、`return true` 之前插入限流检查：

```java
            int limit = com.qingyu.care.service.RateLimitService.resolveLimit(app, defaultRateLimit);
            if (!rateLimitService.tryConsume(app.getAppKey(), limit)) {
                response.setHeader("Retry-After", "60");
                writeError(response, 429, "请求过于频繁");
                return false;
            }
```

> `@Value` 注入需要字段非 final，因此把这两个字段用普通字段声明并保留构造注入的 `rateLimitService`（`@RequiredArgsConstructor` 只注入 final 字段；`rateLimitService` 设为 final，`defaultRateLimit` 用 `@Value` 普通字段）。

- [ ] **Step 2: 检查点 — 编译**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q compile`
Expected: BUILD SUCCESS

---

# 里程碑 M5：用量计量

## Task 9: Application 加 rateLimitPerMinute + 应用更新透传

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/Application.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ApplicationController.java`

- [ ] **Step 1: Application 加字段**

在 `enabled` 字段之后、`createdAt` 之前插入：

```java
    /**
     * 开放 API 每分钟请求上限（为空则用平台默认）
     */
    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;
```

- [ ] **Step 2: ApplicationController.update 透传该字段**

在 `update(...)` 方法的字段拷贝块内（`enabled` 之后）加：

```java
        if (application.getRateLimitPerMinute() != null) existing.setRateLimitPerMinute(application.getRateLimitPerMinute());
```

- [ ] **Step 3: 检查点 — 编译 + 回归 RateLimitServiceTest**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=RateLimitServiceTest test`
Expected: Tests run: 3, Failures: 0（此时 `rateLimitPerMinute` 已存在）

## Task 10: SysApiUsage 实体/仓库 + ApiUsageService（TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/entity/SysApiUsage.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/SysApiUsageRepository.java`
- Create: `backend/src/main/java/com/qingyu/care/service/ApiUsageService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ApiUsageServiceTest.java`

- [ ] **Step 1: 创建 SysApiUsage**

```java
package com.qingyu.care.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 开放 API 用量（按 应用+日期 聚合）。不继承 BaseTenantEntity。
 */
@Entity
@Table(name = "sys_api_usage",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_api_usage_app_date", columnNames = {"app_id", "usage_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysApiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "call_count", nullable = false)
    @Builder.Default
    private long callCount = 0;

    @Column(name = "message_count", nullable = false)
    @Builder.Default
    private long messageCount = 0;

    @Column(name = "token_count", nullable = false)
    @Builder.Default
    private long tokenCount = 0;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 创建 SysApiUsageRepository（原子累加 + 查询）**

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.SysApiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface SysApiUsageRepository extends JpaRepository<SysApiUsage, Long> {

    /**
     * 原子累加当天用量，返回受影响行数（0 表示当天尚无记录，需 insert）。
     */
    @Modifying
    @Query(value = "UPDATE sys_api_usage SET call_count = call_count + 1, " +
            "message_count = message_count + :messages, token_count = token_count + :tokens, " +
            "updated_at = now() WHERE app_id = :appId AND usage_date = :date", nativeQuery = true)
    int incrementUsage(@Param("appId") Long appId, @Param("date") LocalDate date,
                       @Param("messages") long messages, @Param("tokens") long tokens);
}
```

- [ ] **Step 3: 先写失败测试**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.SysApiUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiUsageServiceTest {

    @Mock SysApiUsageRepository repository;
    @InjectMocks ApiUsageService service;

    @Test
    void record_incrementsExistingRow_whenUpdateAffectsRow() {
        when(repository.incrementUsage(eq(1L), any(LocalDate.class), eq(1L), eq(10L))).thenReturn(1);

        service.record(9L, 1L, 1L, 10L);

        verify(repository).incrementUsage(eq(1L), any(LocalDate.class), eq(1L), eq(10L));
        verify(repository, never()).save(any());
    }

    @Test
    void record_insertsNewRow_whenUpdateAffectsNothing() {
        when(repository.incrementUsage(eq(1L), any(LocalDate.class), eq(1L), eq(10L))).thenReturn(0);

        service.record(9L, 1L, 1L, 10L);

        verify(repository).save(argThat(u ->
                u.getAppId().equals(1L) && u.getTenantId().equals(9L)
                        && u.getCallCount() == 1 && u.getMessageCount() == 1 && u.getTokenCount() == 10));
    }

    @Test
    void record_swallowsExceptions() {
        when(repository.incrementUsage(anyLong(), any(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("db down"));
        // 不应抛出
        service.record(9L, 1L, 1L, 10L);
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=ApiUsageServiceTest test`
Expected: 编译失败（ApiUsageService 不存在）

- [ ] **Step 5: 实现 ApiUsageService**

```java
package com.qingyu.care.service;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.SysApiUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 开放 API 用量计量：按 (appId, 当天) 原子累加。异步、异常不外抛。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiUsageService {

    private final SysApiUsageRepository repository;

    @Async
    @Transactional
    public void record(Long tenantId, Long appId, long messages, long tokens) {
        try {
            LocalDate today = LocalDate.now();
            int updated = repository.incrementUsage(appId, today, messages, tokens);
            if (updated == 0) {
                try {
                    repository.save(SysApiUsage.builder()
                            .tenantId(tenantId).appId(appId).usageDate(today)
                            .callCount(1).messageCount(messages).tokenCount(tokens)
                            .build());
                } catch (Exception insertConflict) {
                    // 并发下另一个线程已插入，重试一次累加
                    repository.incrementUsage(appId, today, messages, tokens);
                }
            }
        } catch (Exception e) {
            log.warn("写开放 API 用量失败 appId={}: {}", appId, e.getMessage());
        }
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=ApiUsageServiceTest test`
Expected: Tests run: 3, Failures: 0

## Task 11: 把计量接入开放对话端点

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`

- [ ] **Step 1: OpenChatController 注入 ApiUsageService，对话后累加**

给类加字段 `private final com.qingyu.care.service.ApiUsageService apiUsageService;`（已 `@RequiredArgsConstructor`）。将 `completions` 改为在 `chat` 成功后记账（token 从 `ChatResponse.tokenUsage` 取，可能为 null）：

```java
    @PostMapping("/completions")
    public OpenChatResponse completions(@Valid @RequestBody OpenChatRequest req) {
        ChatResponse resp = chatService.chat(toInternal(req, false));
        long tokens = resp.getTokenUsage() != null ? resp.getTokenUsage() : 0L;
        apiUsageService.record(
                com.qingyu.care.config.TenantContext.getTenantId(),
                com.qingyu.care.config.TenantContext.getAppId(),
                1L, tokens);
        return OpenChatResponse.from(resp);
    }
```

> 流式端点 `stream` 因返回 Flux 难以同步取 token，本子项仅对 `completions` 计量（call+message+token）；流式计量列为后续增强。

- [ ] **Step 2: 检查点 — 编译 + 全量新单测回归**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=OpenApiAuthServiceTest,OpenApiExceptionHandlerTest,RateLimitServiceTest,ApiUsageServiceTest test`
Expected: 四个测试类全绿（合计 14 个）

---

# 里程碑 M6：前端接入配置页

## Task 12: 接入配置 api + 页面 + 路由/菜单

**Files:**
- Create: `frontend/src/api/integration.ts`
- Create: `frontend/src/pages/IntegrationPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`
- Modify: `frontend/src/api/application.ts`（若 Application 类型缺 `rateLimitPerMinute` 则补）

- [ ] **Step 1: api/integration.ts（复用既有应用接口 + 重置密钥）**

先确认 `src/api/application.ts` 里 `Application` 类型是否有 `appKey/appSecret/rateLimitPerMinute`；若缺则补上字段。然后新建 `integration.ts`：

```typescript
import api from './client';
import type { ApiResponse } from '../types';
import type { Application } from './application';

export const integrationService = {
  // 获取应用详情（含 appKey/appSecret/限流）
  getApp: (id: number): Promise<ApiResponse<Application>> =>
    api.get(`/applications/${id}`),
  // 重置密钥
  regenerateSecret: (id: number): Promise<ApiResponse<Application>> =>
    api.post(`/applications/${id}/regenerate-secret`),
  // 更新限流额度（透传应用更新接口）
  updateRateLimit: (id: number, rateLimitPerMinute: number | null): Promise<ApiResponse<Application>> =>
    api.put(`/applications/${id}`, { rateLimitPerMinute }),
};
```

> 若 `application.ts` 无 `Application` 具名导出，则在本文件内定义所需最小接口 `{ id, name, appKey, appSecret, rateLimitPerMinute }`。

- [ ] **Step 2: IntegrationPage.tsx**

```tsx
import React, { useEffect, useState } from 'react';
import { useAppStore } from '@/stores/appStore';
import { integrationService } from '@/api/integration';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useToast } from '@/hooks/use-toast';

const IntegrationPage: React.FC = () => {
  const { toast } = useToast();
  const { currentApp } = useAppStore();
  const [appKey, setAppKey] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [showSecret, setShowSecret] = useState(false);
  const [rateLimit, setRateLimit] = useState<string>('');

  const load = () => {
    if (!currentApp) return;
    integrationService.getApp(currentApp.id).then((r) => {
      if (r.code !== 200) throw new Error(r.message);
      setAppKey(r.data.appKey ?? '');
      setAppSecret(r.data.appSecret ?? '');
      setRateLimit(r.data.rateLimitPerMinute != null ? String(r.data.rateLimitPerMinute) : '');
    }).catch((e) => toast({ title: '加载失败', description: e.message, variant: 'destructive' }));
  };

  useEffect(() => { load(); /* eslint-disable-next-line */ }, [currentApp?.id]);

  const copy = (text: string) => {
    navigator.clipboard?.writeText(text);
    toast({ title: '已复制' });
  };

  const regenerate = async () => {
    if (!currentApp) return;
    try {
      const r = await integrationService.regenerateSecret(currentApp.id);
      if (r.code !== 200) throw new Error(r.message);
      setAppSecret(r.data.appSecret ?? '');
      toast({ title: '密钥已重置' });
    } catch (e: any) { toast({ title: '重置失败', description: e.message, variant: 'destructive' }); }
  };

  const saveRateLimit = async () => {
    if (!currentApp) return;
    try {
      const val = rateLimit.trim() === '' ? null : Number(rateLimit);
      const r = await integrationService.updateRateLimit(currentApp.id, val);
      if (r.code !== 200) throw new Error(r.message);
      toast({ title: '已保存' });
    } catch (e: any) { toast({ title: '保存失败', description: e.message, variant: 'destructive' }); }
  };

  if (!currentApp) return <div className="p-6 text-muted-foreground">请先在上方选择应用</div>;

  const maskedSecret = appSecret ? appSecret.slice(0, 6) + '••••••••' + appSecret.slice(-4) : '';
  const curl = `curl -X POST http://localhost:8080/api/open/v1/chat/completions \\
  -H "X-App-Id: ${appKey}" \\
  -H "X-Api-Key: ${appSecret}" \\
  -H "Content-Type: application/json" \\
  -d '{"message":"你好"}'`;

  return (
    <div className="p-6 space-y-6 max-w-2xl overflow-y-auto h-full">
      <h3 className="text-lg font-semibold">接入配置 · {currentApp.name}</h3>

      <div className="space-y-2">
        <label className="text-sm font-medium">App Id（X-App-Id）</label>
        <div className="flex gap-2">
          <Input readOnly value={appKey} />
          <Button variant="outline" onClick={() => copy(appKey)}>复制</Button>
        </div>
      </div>

      <div className="space-y-2">
        <label className="text-sm font-medium">App Secret（X-Api-Key）</label>
        <div className="flex gap-2">
          <Input readOnly value={showSecret ? appSecret : maskedSecret} />
          <Button variant="outline" onClick={() => setShowSecret((s) => !s)}>{showSecret ? '隐藏' : '显示'}</Button>
          <Button variant="outline" onClick={() => copy(appSecret)}>复制</Button>
          <Button variant="destructive" onClick={regenerate}>重置</Button>
        </div>
      </div>

      <div className="space-y-2">
        <label className="text-sm font-medium">每分钟请求上限（留空用平台默认）</label>
        <div className="flex gap-2">
          <Input type="number" value={rateLimit} onChange={(e) => setRateLimit(e.target.value)} className="w-40" />
          <Button onClick={saveRateLimit}>保存</Button>
        </div>
      </div>

      <div className="space-y-2">
        <label className="text-sm font-medium">调用示例</label>
        <pre className="text-xs bg-muted p-3 rounded-md overflow-x-auto whitespace-pre-wrap">{curl}</pre>
      </div>

      <div className="space-y-2">
        <label className="text-sm font-medium">Widget 嵌入代码</label>
        <p className="text-xs text-muted-foreground">Widget 子项完成后在此生成一行嵌入代码（暂未生效）。</p>
      </div>
    </div>
  );
};

export default IntegrationPage;
```

> 依赖 `useAppStore().currentApp`（含 `id/name`，既有 store）。

- [ ] **Step 3: App.tsx 加路由**

import：`import IntegrationPage from './pages/IntegrationPage';`
在「应用配置」路由区（需选应用）加：

```tsx
          <Route path="integration" element={<RequireApp><IntegrationPage /></RequireApp>} />
```

- [ ] **Step 4: MainLayout.tsx 加菜单项**

在 lucide import 补一个图标（如 `Plug`）。在 `title: '应用配置'` 组 `items` 末尾加：

```tsx
            { label: '接入配置', path: '/integration', icon: Plug },
```

（该组已有 `requireApp: true`，无需 roles。图标名以 lucide-react 实际导出为准，不存在则换 `Settings`/`Link2`。）

- [ ] **Step 5: 检查点 — 前端构建**

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 构建成功，无类型错误

---

# 里程碑 M7：回归与联调

## Task 13: 全量回归 + 手测说明

- [ ] **Step 1: 后端编译 + 全部新单测**

Run: `cd /d/Code/m/qingyu-care/backend && mvn -q -Dtest=OpenApiAuthServiceTest,OpenApiExceptionHandlerTest,RateLimitServiceTest,ApiUsageServiceTest test`
Expected: 四类全绿（合计 14 个）

- [ ] **Step 2: 前端构建**

Run: `cd /d/Code/m/qingyu-care/frontend && pnpm build`
Expected: 构建成功

- [ ] **Step 3: 人工联调（需本地 PostgreSQL + Redis + 后端 + 前端，由用户执行）**

1. `cd docker && docker compose up -d`（含 Redis 7）。设 `DASHSCOPE_API_KEY`。
2. 后端 `mvn spring-boot:run -Dspring-boot.run.profiles=dev`（首启自动建 `sys_api_usage` 表、`sys_application.rate_limit_per_minute` 列）。
3. 前端登录后进入「应用配置 → 接入配置」，复制 appKey/appSecret。
4. 用页面给出的 curl 调 `POST /api/open/v1/chat/completions`：
   - 正确 key → 200 返回 `{sessionId, reply}`；
   - 错误 key → 401；停用应用 → 403；
   - 快速超过限额 → 429（带 Retry-After）。
5. 查库 `sys_api_usage`：对应 appId 当天行的 call_count/message_count/token_count 递增。

- [ ] **Step 4: 检查点** — 手测通过即完成本子项。

---

## 自检（对照 spec）

**Spec 覆盖：**
- 认证（appKey/appSecret + TenantContext 注入 + Sa-Token 放行）→ Task 1/2/4 ✅
- 端点（completions/stream/session/handover，委托 ChatService）→ Task 5/6 ✅
- 限流（Redis+Bucket4j 按 appKey、应用级可覆盖、429、fail-open）→ Task 1/7/8 ✅
- 计量（sys_api_usage 按 appId+日期聚合、异步原子累加、token 取 ChatResponse.tokenUsage）→ Task 9/10/11 ✅
- 对外标准 HTTP 状态码 + 独立异常处理 → Task 3 ✅
- 接入配置管理页（key 展示/重置、限流设置、curl、Widget 占位）→ Task 12 ✅
- 测试（认证/异常映射/额度解析/计量累加）→ Task 2/3/7/10 ✅

**占位符扫描：** 无 TODO/TBD。少数「按实体实际字段/图标/import 对齐」为对现有代码/固定版本 API 的适配提示，非逻辑占位。

**类型/命名一致性：**
- `OpenApiException(status,msg)` 贯穿 service/interceptor/handler ✅
- `RateLimitService.resolveLimit`/`tryConsume`、`ApiUsageService.record`、`incrementUsage` 定义与调用一致 ✅
- `OpenChatResponse.from(ChatResponse)`、`OpenSessionResponse.of(...)` 映射自既有实体/DTO 字段 ✅
- 前端 `integrationService` 路径与后端 `/v1/applications/*` 一致，开放 API `/open/v1/*` 与后端控制器一致 ✅

**执行顺序提示：** Task 9（加 `rateLimitPerMinute` 字段）需先于 Task 7 的 `RateLimitServiceTest` 真正跑绿；实现时先加字段再写限流测试，或将 Task 9 Step 1 提前。

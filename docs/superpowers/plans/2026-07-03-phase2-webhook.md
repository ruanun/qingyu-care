---
title: Phase 2 · Webhook 事件推送 实现计划
author: ruan
date: 2026-07-03
status: 待执行
spec: docs/superpowers/specs/2026-07-03-phase2-webhook-design.md
---

# Webhook 事件推送 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **本项目协作约定**：子代理**不自行 git 提交**；每个 Task 末尾的「提交」步骤由**编排者**在该单元通过审查后，按显式文件路径提交到分支 `feature/phase2-webhook`。全部完成后 `--ff-only` 合并 master 并删分支。

**Goal:** 当会话发生转人工请求 / 会话结束时，向应用登记的回调 URL 推送带 HMAC 签名的 JSON 事件，落库并自动重试（至少一次语义）。

**Architecture:** `ChatServiceImpl` 状态变更处发布内存领域事件 → `@TransactionalEventListener(AFTER_COMMIT)` 过滤订阅后落 `sys_webhook_delivery` 表并触发 `@Async` 首投 → `@Scheduled` 扫描做退避重试。投递用 Spring Boot 内置 `RestClient`，签名 HMAC-SHA256。管理后台配置 URL/密钥/事件并可发测试事件。

**Tech Stack:** 后端 Spring Boot 3.4.1 + JPA + Spring 事件/@Async/@Scheduled + RestClient + JUnit5/Mockito/AssertJ；前端 React 19 + Vite。

**测试约定（见项目记忆）**：纯单测用 `@ExtendWith(MockitoExtension.class)`，不加载 Spring context、不连 DB/Redis，跑得快。验证读 `backend/target/surefire-reports/*.txt`。

---

## File Structure

**后端新建（`backend/src/main/java/com/qingyu/care/`）**
- `enums/WebhookDeliveryStatus.java` — 投递状态枚举
- `entity/WebhookDelivery.java` — 投递记录实体（不继承 BaseTenantEntity）
- `repository/WebhookDeliveryRepository.java` — 认领 + 扫描查询
- `config/WebhookProperties.java` — `@ConfigurationProperties(prefix=qingyu.care.webhook)`
- `config/WebhookConfig.java` — `RestClient` bean（超时）+ `@EnableScheduling`
- `service/webhook/WebhookSignature.java` — HMAC-SHA256
- `service/webhook/WebhookHttpClient.java` — HTTP 客户端接口
- `service/webhook/RestClientWebhookHttpClient.java` — RestClient 实现
- `service/webhook/WebhookPayloadBuilder.java` — 事件信封 JSON
- `service/webhook/WebhookSender.java` — `@Async` 投递 + 认领 + 退避 + 同步 send
- `event/WebhookDomainEvent.java` — 统一领域事件 POJO
- `service/webhook/WebhookDispatcher.java` — `@TransactionalEventListener` 落库
- `service/webhook/WebhookRetryScheduler.java` — `@Scheduled` 扫描重试
- `dto/WebhookTestResult.java` — 测试事件返回体

**后端修改**
- `entity/Application.java` — 加 `webhookUrl/webhookSecret/webhookEvents`
- `service/impl/ChatServiceImpl.java` — 3 处 `publishEvent`
- `controller/ApplicationController.java` — update 透传 + 重置密钥 + 测试端点
- `src/main/resources/application.yml` — `qingyu.care.webhook` 段

**后端测试**
- `service/webhook/WebhookSignatureTest.java`
- `service/webhook/WebhookPayloadBuilderTest.java`
- `service/webhook/WebhookSenderTest.java`
- `service/webhook/WebhookDispatcherTest.java`

**前端修改（`frontend/src/`）**
- `types/index.ts` — `Application` 加 webhook 字段
- `api/integration.ts` — updateWebhook / regenerateWebhookSecret / sendWebhookTest
- `pages/IntegrationPage.tsx` — Webhook 配置区

---

## 里程碑 A：后端核心

### Task 1: 投递状态枚举 + 实体 + 仓库

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/enums/WebhookDeliveryStatus.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/WebhookDelivery.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/WebhookDeliveryRepository.java`

- [ ] **Step 1: 枚举**

`enums/WebhookDeliveryStatus.java`：

```java
package com.qingyu.care.enums;

/** Webhook 投递状态。 */
public enum WebhookDeliveryStatus {
    /** 待投递（含待重试）。 */
    PENDING,
    /** 已被某线程认领、投递中。 */
    PROCESSING,
    /** 投递成功（2xx）。 */
    SUCCESS,
    /** 重试耗尽仍失败。 */
    FAILED
}
```

- [ ] **Step 2: 实体**

`entity/WebhookDelivery.java`：

```java
package com.qingyu.care.entity;

import com.qingyu.care.enums.WebhookDeliveryStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Webhook 投递记录（按事件一行）。参照 SysApiUsage 不继承 BaseTenantEntity——
 * 投递发生在异步/定时线程，无租户过滤上下文，显式存 tenantId/appId 更稳。
 *
 * @author ruan
 */
@Entity
@Table(name = "sys_webhook_delivery")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    /** 事件类型，如 session.handover_requested。 */
    @Column(nullable = false, length = 64)
    private String event;

    /** 投递目标 URL 快照。 */
    @Column(name = "target_url", columnDefinition = "TEXT")
    private String targetUrl;

    /** 事件信封 JSON。 */
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: 仓库**

`repository/WebhookDeliveryRepository.java`：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Webhook 投递仓库：条件认领 + 到期扫描。
 *
 * @author ruan
 */
@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /**
     * 认领一条待投递记录：仅当仍为 PENDING 时置 PROCESSING。
     * 返回受影响行数（1=认领成功，0=已被他人领走）。自带事务，立即提交，
     * 让其它线程/实例看到 PROCESSING，避免重复投递。
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE sys_webhook_delivery SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int claim(@Param("id") Long id);

    /** 扫描到期待重试的投递（PENDING 且 next_attempt_at 已到）。 */
    List<WebhookDelivery> findByStatusAndNextAttemptAtLessThanEqual(
            WebhookDeliveryStatus status, LocalDateTime now, Pageable pageable);
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS（`ddl-auto:update` 启动时自动建 `sys_webhook_delivery` 表）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/enums/WebhookDeliveryStatus.java backend/src/main/java/com/qingyu/care/entity/WebhookDelivery.java backend/src/main/java/com/qingyu/care/repository/WebhookDeliveryRepository.java
git commit -m "feat(webhook): 投递记录实体、状态枚举与仓库（认领/扫描）"
```

---

### Task 2: Application 新增 webhook 字段

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/Application.java`

- [ ] **Step 1: 加字段**

在 `allowedOrigins` 字段之后（第 94 行 `private String allowedOrigins;` 之后）插入：

```java

    /**
     * Webhook 回调 URL。为空表示未启用 Webhook 事件推送。
     */
    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl;

    /**
     * Webhook 签名密钥（HMAC-SHA256）。启用/重置时生成。
     */
    @Column(name = "webhook_secret", length = 128)
    private String webhookSecret;

    /**
     * 订阅的事件类型（逗号分隔，如 session.handover_requested,session.ended）。
     */
    @Column(name = "webhook_events", columnDefinition = "TEXT")
    private String webhookEvents;
```

- [ ] **Step 2: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS（自动加 3 列，旧行 NULL 向后兼容）

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/Application.java
git commit -m "feat(webhook): Application 新增 webhookUrl/webhookSecret/webhookEvents"
```

---

### Task 3: 配置属性 + application.yml + RestClient/调度配置

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/WebhookProperties.java`
- Create: `backend/src/main/java/com/qingyu/care/config/WebhookConfig.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: WebhookProperties**

`config/WebhookProperties.java`：

```java
package com.qingyu.care.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Webhook 投递配置（qingyu.care.webhook.*）。
 *
 * @author ruan
 */
@Component
@ConfigurationProperties(prefix = "qingyu.care.webhook")
@Data
public class WebhookProperties {

    /** 最大投递尝试次数（含首次）。 */
    private int maxAttempts = 5;

    /** HTTP 连接/读取超时（毫秒）。 */
    private int timeoutMs = 5000;

    /** 重试扫描间隔（毫秒）。 */
    private long retryScanIntervalMs = 30000;

    /** 各次失败后的退避分钟数（第 n 次失败取 index min(n-1, size-1)）。 */
    private List<Integer> backoffMinutes = List.of(1, 5, 15, 30, 60);
}
```

- [ ] **Step 2: WebhookConfig（RestClient bean + 开启调度）**

`config/WebhookConfig.java`：

```java
package com.qingyu.care.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Webhook 专用 RestClient（带超时）与定时任务开关。
 *
 * @author ruan
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class WebhookConfig {

    private final WebhookProperties properties;

    @Bean
    public RestClient webhookRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
```

- [ ] **Step 3: application.yml 加配置**

在 `src/main/resources/application.yml` 的 `qingyu.care.openapi` 段之后（`default-rate-limit-per-minute: 60` 那段后）插入（注意 YAML 缩进为 `qingyu.care` 下二级）：

```yaml
    # Webhook 事件推送配置
    webhook:
      # 最大投递尝试次数（含首次）
      max-attempts: 5
      # HTTP 连接/读取超时（毫秒）
      timeout-ms: 5000
      # 重试扫描间隔（毫秒）
      retry-scan-interval-ms: 30000
      # 各次失败后的退避分钟数
      backoff-minutes: [1, 5, 15, 30, 60]
```

- [ ] **Step 4: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/config/WebhookProperties.java backend/src/main/java/com/qingyu/care/config/WebhookConfig.java backend/src/main/resources/application.yml
git commit -m "feat(webhook): 配置属性、RestClient（超时）与开启定时调度"
```

---

### Task 4: WebhookSignature（HMAC-SHA256，TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/webhook/WebhookSignature.java`
- Test: `backend/src/test/java/com/qingyu/care/service/webhook/WebhookSignatureTest.java`

- [ ] **Step 1: 写失败测试**

`service/webhook/WebhookSignatureTest.java`：

```java
package com.qingyu.care.service.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private final WebhookSignature signature = new WebhookSignature();

    @Test
    void sign_producesStableSha256HexWithPrefix() {
        // 已知向量：HMAC-SHA256(key="secret", msg="hello") 的十六进制
        String result = signature.sign("secret", "hello");
        assertThat(result).isEqualTo(
                "sha256=88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b");
    }

    @Test
    void sign_differentBodyDiffersSignature() {
        assertThat(signature.sign("secret", "a")).isNotEqualTo(signature.sign("secret", "b"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=WebhookSignatureTest test`
Expected: 编译失败（`WebhookSignature` 尚不存在）

- [ ] **Step 3: 实现**

`service/webhook/WebhookSignature.java`：

```java
package com.qingyu.care.service.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Webhook 请求体签名：HMAC-SHA256(secret, rawBody)，输出 "sha256=<hex>"。
 * 对齐 GitHub/Stripe 惯例，接收方以同法比对。
 *
 * @author ruan
 */
@Component
public class WebhookSignature {

    public String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Webhook 签名计算失败", e);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=WebhookSignatureTest test`
Expected: PASS（2 用例。断言中的十六进制已用 `openssl dgst -sha256 -hmac secret`（对 "hello"）核实为真值 `88aab3ede8...847c0b`）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/webhook/WebhookSignature.java backend/src/test/java/com/qingyu/care/service/webhook/WebhookSignatureTest.java
git commit -m "feat(webhook): HMAC-SHA256 请求体签名"
```

---

### Task 5: WebhookHttpClient 接口 + RestClient 实现

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/webhook/WebhookHttpClient.java`
- Create: `backend/src/main/java/com/qingyu/care/service/webhook/RestClientWebhookHttpClient.java`

- [ ] **Step 1: 接口**

`service/webhook/WebhookHttpClient.java`：

```java
package com.qingyu.care.service.webhook;

import java.util.Map;

/**
 * Webhook HTTP 投递客户端抽象。抽出接口以便 Sender 逻辑用纯 Mockito 单测，
 * 不依赖真实 HTTP。
 *
 * @author ruan
 */
public interface WebhookHttpClient {

    /**
     * POST body（含 headers），返回 HTTP 状态码（含 4xx/5xx，不抛异常）。
     * 传输失败/超时抛 RuntimeException。
     */
    int post(String url, Map<String, String> headers, String body);
}
```

- [ ] **Step 2: RestClient 实现**

`service/webhook/RestClientWebhookHttpClient.java`：

```java
package com.qingyu.care.service.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 基于 Spring RestClient 的 Webhook 投递实现。用 exchange() 拿到状态码而不因
 * 非 2xx 抛异常（默认状态处理器被 exchange 绕过）；传输错误由 RestClient 抛出。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class RestClientWebhookHttpClient implements WebhookHttpClient {

    private final RestClient webhookRestClient;

    @Override
    public int post(String url, Map<String, String> headers, String body) {
        return webhookRestClient.post()
                .uri(url)
                .headers(h -> headers.forEach(h::set))
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/webhook/WebhookHttpClient.java backend/src/main/java/com/qingyu/care/service/webhook/RestClientWebhookHttpClient.java
git commit -m "feat(webhook): HTTP 投递客户端接口与 RestClient 实现"
```

---

### Task 6: WebhookPayloadBuilder（事件信封，TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/webhook/WebhookPayloadBuilder.java`
- Test: `backend/src/test/java/com/qingyu/care/service/webhook/WebhookPayloadBuilderTest.java`

- [ ] **Step 1: 写失败测试**

`service/webhook/WebhookPayloadBuilderTest.java`：

```java
package com.qingyu.care.service.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebhookPayloadBuilder builder = new WebhookPayloadBuilder(mapper);

    @Test
    void build_producesEnvelopeWithIdEventCreatedAtAndData() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", "s-1");
        data.put("appId", 12);

        String json = builder.build("d-1", "session.ended",
                LocalDateTime.of(2026, 7, 3, 8, 0, 0), data);

        JsonNode node = mapper.readTree(json);
        assertThat(node.get("id").asText()).isEqualTo("d-1");
        assertThat(node.get("event").asText()).isEqualTo("session.ended");
        assertThat(node.get("createdAt").asText()).isEqualTo("2026-07-03T08:00:00");
        assertThat(node.get("data").get("sessionId").asText()).isEqualTo("s-1");
        assertThat(node.get("data").get("appId").asInt()).isEqualTo(12);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=WebhookPayloadBuilderTest test`
Expected: 编译失败（`WebhookPayloadBuilder` 尚不存在）

- [ ] **Step 3: 实现**

`service/webhook/WebhookPayloadBuilder.java`：

```java
package com.qingyu.care.service.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构造 Webhook 事件信封 JSON：{ id, event, createdAt, data }。
 * createdAt 用 ISO_LOCAL_DATE_TIME 显式格式化，避免依赖 Jackson 时间模块配置。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class WebhookPayloadBuilder {

    private final ObjectMapper objectMapper;

    public String build(String id, String event, LocalDateTime createdAt, Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("event", event);
        envelope.put("createdAt", createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        envelope.put("data", data);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Webhook payload 序列化失败", e);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=WebhookPayloadBuilderTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/webhook/WebhookPayloadBuilder.java backend/src/test/java/com/qingyu/care/service/webhook/WebhookPayloadBuilderTest.java
git commit -m "feat(webhook): 事件信封 payload 构造"
```

---

### Task 7: WebhookSender（投递 + 认领 + 退避，TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/webhook/WebhookSender.java`
- Test: `backend/src/test/java/com/qingyu/care/service/webhook/WebhookSenderTest.java`

- [ ] **Step 1: 写失败测试**

`service/webhook/WebhookSenderTest.java`：

```java
package com.qingyu.care.service.webhook;

import com.qingyu.care.config.WebhookProperties;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookSenderTest {

    @Mock WebhookDeliveryRepository deliveryRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock WebhookHttpClient httpClient;
    @Mock WebhookSignature signature;
    WebhookProperties properties;
    WebhookSender sender;

    @BeforeEach
    void setup() {
        properties = new WebhookProperties(); // maxAttempts=5, backoff=[1,5,15,30,60]
        sender = new WebhookSender(deliveryRepository, applicationRepository, httpClient, signature, properties);
    }

    private WebhookDelivery pending(long id, int attempts) {
        return WebhookDelivery.builder().id(id).appId(9L).event("session.ended")
                .targetUrl("https://cb.example.com/hook").payload("{}")
                .status(WebhookDeliveryStatus.PROCESSING).attempts(attempts).build();
    }

    @Test
    void deliverAsync_skips_whenClaimFails() {
        when(deliveryRepository.claim(1L)).thenReturn(0);
        sender.deliverAsync(1L);
        verify(deliveryRepository, never()).findById(anyLong());
        verify(httpClient, never()).post(any(), any(), any());
    }

    @Test
    void deliverAsync_marksSuccess_on2xx() {
        when(deliveryRepository.claim(1L)).thenReturn(1);
        when(deliveryRepository.findById(1L)).thenReturn(Optional.of(pending(1L, 0)));
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(
                Application.builder().id(9L).webhookSecret("sec").build()));
        when(signature.sign(eq("sec"), anyString())).thenReturn("sha256=x");
        when(httpClient.post(any(), any(), any())).thenReturn(200);

        sender.deliverAsync(1L);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(cap.getValue().getResponseStatus()).isEqualTo(200);
    }

    @Test
    void deliverAsync_reschedules_onNon2xx_whenAttemptsRemain() {
        when(deliveryRepository.claim(1L)).thenReturn(1);
        when(deliveryRepository.findById(1L)).thenReturn(Optional.of(pending(1L, 0)));
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(
                Application.builder().id(9L).webhookSecret("sec").build()));
        when(httpClient.post(any(), any(), any())).thenReturn(500);

        sender.deliverAsync(1L);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().getAttempts()).isEqualTo(1);
        assertThat(cap.getValue().getNextAttemptAt()).isNotNull();
    }

    @Test
    void deliverAsync_marksFailed_whenAttemptsExhausted() {
        when(deliveryRepository.claim(1L)).thenReturn(1);
        when(deliveryRepository.findById(1L)).thenReturn(Optional.of(pending(1L, 4))); // 第 5 次失败
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(
                Application.builder().id(9L).webhookSecret("sec").build()));
        when(httpClient.post(any(), any(), any())).thenReturn(500);

        sender.deliverAsync(1L);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(cap.getValue().getAttempts()).isEqualTo(5);
    }

    @Test
    void deliverAsync_reschedules_onTransportException() {
        when(deliveryRepository.claim(1L)).thenReturn(1);
        when(deliveryRepository.findById(1L)).thenReturn(Optional.of(pending(1L, 0)));
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(
                Application.builder().id(9L).webhookSecret("sec").build()));
        when(httpClient.post(any(), any(), any())).thenThrow(new RuntimeException("connect timeout"));

        sender.deliverAsync(1L);

        ArgumentCaptor<WebhookDelivery> cap = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(cap.getValue().getLastError()).contains("connect timeout");
    }

    @Test
    void send_setsSignatureAndEventHeaders() {
        when(signature.sign("sec", "{}")).thenReturn("sha256=abc");
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        when(httpClient.post(eq("https://u"), headers.capture(), eq("{}"))).thenReturn(204);

        int status = sender.send("https://u", "sec", "session.ended", "d-1", "{}");

        assertThat(status).isEqualTo(204);
        assertThat(headers.getValue()).containsEntry("X-Qingyu-Event", "session.ended");
        assertThat(headers.getValue()).containsEntry("X-Qingyu-Delivery-Id", "d-1");
        assertThat(headers.getValue()).containsEntry("X-Qingyu-Signature", "sha256=abc");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=WebhookSenderTest test`
Expected: 编译失败（`WebhookSender` 尚不存在）

- [ ] **Step 3: 实现**

`service/webhook/WebhookSender.java`：

```java
package com.qingyu.care.service.webhook;

import com.qingyu.care.config.WebhookProperties;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook 投递执行器：认领→发送→按结果更新状态/退避重排。
 * 参照 ApiUsageService：不加方法级 @Transactional，各仓库操作各走独立事务
 * （claim 自带事务立即提交、save 自带事务），投递期间不占用数据库事务。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookSender {

    private final WebhookDeliveryRepository deliveryRepository;
    private final ApplicationRepository applicationRepository;
    private final WebhookHttpClient httpClient;
    private final WebhookSignature signature;
    private final WebhookProperties properties;

    /** 异步投递单条：先认领防重复，再发送并落状态。 */
    @Async
    public void deliverAsync(Long deliveryId) {
        if (deliveryRepository.claim(deliveryId) != 1) {
            return; // 已被即时投递或扫描器/其它实例领走
        }
        WebhookDelivery d = deliveryRepository.findById(deliveryId).orElse(null);
        if (d == null) {
            return;
        }
        try {
            String secret = applicationRepository.findById(d.getAppId())
                    .map(Application::getWebhookSecret).orElse(null);
            int status = send(d.getTargetUrl(), secret, d.getEvent(), String.valueOf(d.getId()), d.getPayload());
            if (status >= 200 && status < 300) {
                d.setStatus(WebhookDeliveryStatus.SUCCESS);
                d.setResponseStatus(status);
                d.setLastError(null);
            } else {
                fail(d, status, "HTTP " + status);
            }
        } catch (Exception e) {
            log.warn("Webhook 投递异常 id={}: {}", deliveryId, e.getMessage());
            fail(d, null, e.getMessage());
        }
        deliveryRepository.save(d);
    }

    /**
     * 同步发送一次，返回 HTTP 状态码（供异步投递与测试端点复用）。
     * 传输失败/超时抛异常，由调用方处理。
     */
    public int send(String url, String secret, String event, String deliveryId, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Qingyu-Event", event);
        headers.put("X-Qingyu-Delivery-Id", deliveryId);
        headers.put("X-Qingyu-Timestamp", String.valueOf(System.currentTimeMillis()));
        if (secret != null && !secret.isBlank()) {
            headers.put("X-Qingyu-Signature", signature.sign(secret, body));
        }
        return httpClient.post(url, headers, body);
    }

    /** 失败：attempts++，未耗尽则退避重排为 PENDING，否则 FAILED。 */
    private void fail(WebhookDelivery d, Integer responseStatus, String error) {
        int attempts = d.getAttempts() + 1;
        d.setAttempts(attempts);
        d.setResponseStatus(responseStatus);
        d.setLastError(truncate(error));
        if (attempts < properties.getMaxAttempts()) {
            d.setStatus(WebhookDeliveryStatus.PENDING);
            d.setNextAttemptAt(LocalDateTime.now().plusMinutes(backoffMinutes(attempts)));
        } else {
            d.setStatus(WebhookDeliveryStatus.FAILED);
        }
    }

    /** 第 attempts 次失败（从 1 起）对应的退避分钟数。 */
    long backoffMinutes(int attempts) {
        List<Integer> b = properties.getBackoffMinutes();
        int idx = Math.min(Math.max(attempts - 1, 0), b.size() - 1);
        return b.get(idx);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=WebhookSenderTest test`
Expected: PASS（6 用例）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/webhook/WebhookSender.java backend/src/test/java/com/qingyu/care/service/webhook/WebhookSenderTest.java
git commit -m "feat(webhook): 投递执行器（认领/签名头/退避重试）"
```

---

### Task 8: WebhookDomainEvent + WebhookDispatcher（事务事件落库，TDD）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/event/WebhookDomainEvent.java`
- Create: `backend/src/main/java/com/qingyu/care/service/webhook/WebhookDispatcher.java`
- Test: `backend/src/test/java/com/qingyu/care/service/webhook/WebhookDispatcherTest.java`

- [ ] **Step 1: 领域事件 POJO**

`event/WebhookDomainEvent.java`：

```java
package com.qingyu.care.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Webhook 领域事件（内存事件，由 ChatServiceImpl 在状态变更后发布）。
 * 携带 appId/tenantId，使监听器无需依赖 TenantContext。
 *
 * @author ruan
 */
@Getter
@AllArgsConstructor
public class WebhookDomainEvent {

    /** 事件类型，如 session.handover_requested / session.ended。 */
    private final String eventType;
    private final Long appId;
    private final Long tenantId;
    private final String sessionId;
    /** 触发来源（keyword / user_request），无则 null。 */
    private final String trigger;
    private final LocalDateTime occurredAt;
}
```

- [ ] **Step 2: 写失败测试**

`service/webhook/WebhookDispatcherTest.java`：

```java
package com.qingyu.care.service.webhook;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.event.WebhookDomainEvent;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    @Mock ApplicationRepository applicationRepository;
    @Mock WebhookDeliveryRepository deliveryRepository;
    @Mock WebhookPayloadBuilder payloadBuilder;
    @Mock WebhookSender sender;
    @InjectMocks WebhookDispatcher dispatcher;

    private WebhookDomainEvent event() {
        return new WebhookDomainEvent("session.ended", 9L, 1L, "s-1", null, LocalDateTime.now());
    }

    private Application app(String url, String events) {
        return Application.builder().id(9L).webhookUrl(url).webhookEvents(events).build();
    }

    @Test
    void skips_whenAppIdNull() {
        dispatcher.onDomainEvent(new WebhookDomainEvent("session.ended", null, 1L, "s-1", null, LocalDateTime.now()));
        verifyNoInteractions(deliveryRepository, sender);
    }

    @Test
    void skips_whenNoWebhookUrl() {
        when(applicationRepository.findById(9L)).thenReturn(Optional.of(app(null, "session.ended")));
        dispatcher.onDomainEvent(event());
        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(sender);
    }

    @Test
    void skips_whenEventNotSubscribed() {
        when(applicationRepository.findById(9L)).thenReturn(
                Optional.of(app("https://u", "session.handover_requested")));
        dispatcher.onDomainEvent(event());
        verify(deliveryRepository, never()).save(any());
        verifyNoInteractions(sender);
    }

    @Test
    void persistsAndDispatches_whenSubscribed() {
        when(applicationRepository.findById(9L)).thenReturn(
                Optional.of(app("https://u", "session.ended,session.handover_requested")));
        when(deliveryRepository.save(any())).thenAnswer(inv -> {
            WebhookDelivery d = inv.getArgument(0);
            if (d.getId() == null) d.setId(100L);
            return d;
        });
        when(payloadBuilder.build(any(), any(), any(), any())).thenReturn("{\"id\":\"100\"}");

        dispatcher.onDomainEvent(event());

        verify(deliveryRepository, atLeastOnce()).save(any());
        verify(sender).deliverAsync(100L);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=WebhookDispatcherTest test`
Expected: 编译失败（`WebhookDispatcher` 尚不存在）

- [ ] **Step 4: 实现**

`service/webhook/WebhookDispatcher.java`：

```java
package com.qingyu.care.service.webhook;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.event.WebhookDomainEvent;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 监听领域事件（事务提交后），过滤订阅、落投递行并触发异步首投。
 * 用 AFTER_COMMIT 确保只推送已提交状态；异步 send 由 WebhookSender 完成，
 * 此处只做落库，保持轻量。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcher {

    private final ApplicationRepository applicationRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookPayloadBuilder payloadBuilder;
    private final WebhookSender sender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(WebhookDomainEvent event) {
        if (event.getAppId() == null) {
            return; // 内部会话无 appId，不推送
        }
        Application app = applicationRepository.findById(event.getAppId()).orElse(null);
        if (app == null || app.getWebhookUrl() == null || app.getWebhookUrl().isBlank()) {
            return;
        }
        if (!isSubscribed(app.getWebhookEvents(), event.getEventType())) {
            return;
        }

        // 先落 PENDING 行拿到 id（兼作 Delivery-Id 与 payload.id）
        WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.builder()
                .tenantId(event.getTenantId())
                .appId(event.getAppId())
                .event(event.getEventType())
                .targetUrl(app.getWebhookUrl())
                .status(WebhookDeliveryStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(event.getOccurredAt())
                .build());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", event.getSessionId());
        data.put("appId", event.getAppId());
        if (event.getTrigger() != null) {
            data.put("trigger", event.getTrigger());
        }
        delivery.setPayload(payloadBuilder.build(
                String.valueOf(delivery.getId()), event.getEventType(), event.getOccurredAt(), data));
        deliveryRepository.save(delivery);

        sender.deliverAsync(delivery.getId());
    }

    /** 逗号拆分、trim 后精确匹配事件类型。 */
    private boolean isSubscribed(String subscribed, String eventType) {
        if (subscribed == null || subscribed.isBlank()) {
            return false;
        }
        for (String s : subscribed.split(",")) {
            if (s.trim().equals(eventType)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=WebhookDispatcherTest test`
Expected: PASS（4 用例）

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/event/WebhookDomainEvent.java backend/src/main/java/com/qingyu/care/service/webhook/WebhookDispatcher.java backend/src/test/java/com/qingyu/care/service/webhook/WebhookDispatcherTest.java
git commit -m "feat(webhook): 领域事件与事务监听落库分发"
```

---

### Task 9: WebhookRetryScheduler（定时扫描重试）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/webhook/WebhookRetryScheduler.java`

- [ ] **Step 1: 实现**

`service/webhook/WebhookRetryScheduler.java`：

```java
package com.qingyu.care.service.webhook;

import com.qingyu.care.entity.WebhookDelivery;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时扫描到期待重试的投递并交给 WebhookSender。
 * 首投失败后的兜底；间隔由 qingyu.care.webhook.retry-scan-interval-ms 控制。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookRetryScheduler {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSender sender;

    @Scheduled(fixedDelayString = "${qingyu.care.webhook.retry-scan-interval-ms:30000}")
    public void scanAndRetry() {
        List<WebhookDelivery> due = deliveryRepository.findByStatusAndNextAttemptAtLessThanEqual(
                WebhookDeliveryStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, 100));
        if (due.isEmpty()) {
            return;
        }
        log.debug("Webhook 重试扫描：{} 条待投递", due.size());
        for (WebhookDelivery d : due) {
            sender.deliverAsync(d.getId());
        }
    }
}
```

- [ ] **Step 2: 编译并跑已写的 webhook 单测**

Run: `mvn -f backend/pom.xml -q -Dtest="Webhook*Test" test`
Expected: BUILD SUCCESS，全部 PASS

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/webhook/WebhookRetryScheduler.java
git commit -m "feat(webhook): 定时扫描重试调度"
```

---

### Task 10: ChatServiceImpl 发布领域事件

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`

**背景**：三处状态变更点（关键词转人工 L80-82、主动 `requestHandover` L241-242、`endSession` L191-193）均在 `@Transactional` 方法内，发布的事件由 `WebhookDispatcher` 在提交后消费。

- [ ] **Step 1: 加导入与字段**

在 import 区加（`import org.springframework.stereotype.Service;` 附近）：

```java
import org.springframework.context.ApplicationEventPublisher;
import com.qingyu.care.event.WebhookDomainEvent;
```

在字段区（`private final ChatStrategyService chatStrategyService;` 之后，第 51 行后）加：

```java
    private final ApplicationEventPublisher eventPublisher;
```

（`@RequiredArgsConstructor` 会自动纳入构造参数。）

- [ ] **Step 2: 关键词转人工发布事件**

在 `chat()` 关键词分支 `sessionRepository.save(session);`（约 L82）与 `log.info("关键词触发转人工...`之后、保存用户消息之前插入：

```java
            eventPublisher.publishEvent(new WebhookDomainEvent(
                    "session.handover_requested", session.getAppId(), session.getTenantId(),
                    session.getSessionId(), "keyword", java.time.LocalDateTime.now()));
```

- [ ] **Step 3: 主动 requestHandover 发布事件**

在 `requestHandover()` 的 `sessionRepository.save(session);`（约 L242）与 `log.info(...)` 之后插入：

```java
        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.handover_requested", session.getAppId(), session.getTenantId(),
                session.getSessionId(), "user_request", java.time.LocalDateTime.now()));
```

- [ ] **Step 4: endSession 发布事件**

在 `endSession()` 的 `sessionRepository.save(session);`（约 L193）与 `log.info("Session {} ended"...)` 之后、`generateSummaryAsync(session);` 之前插入：

```java
        eventPublisher.publishEvent(new WebhookDomainEvent(
                "session.ended", session.getAppId(), session.getTenantId(),
                session.getSessionId(), null, java.time.LocalDateTime.now()));
```

- [ ] **Step 5: 编译并跑 webhook 单测（确保既有对话逻辑不受影响）**

Run: `mvn -f backend/pom.xml -q -Dtest="Webhook*Test" test`
Expected: BUILD SUCCESS，全部 PASS。
说明：现无任何测试构造 `ChatServiceImpl`（已核实），故新增构造参数 `ApplicationEventPublisher` 不会破坏既有测试；发布事件不改变对话返回值，无监听器时事件被丢弃。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java
git commit -m "feat(webhook): ChatServiceImpl 在转人工/会话结束处发布领域事件"
```

---

## 里程碑 B：管理端点

### Task 11: ApplicationController 透传 + 重置密钥 + 测试端点

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/WebhookTestResult.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ApplicationController.java`

- [ ] **Step 1: 测试结果 DTO**

`dto/WebhookTestResult.java`：

```java
package com.qingyu.care.dto;

/**
 * Webhook 测试事件投递结果。
 *
 * @author ruan
 */
public record WebhookTestResult(boolean delivered, Integer responseStatus, String error) {
}
```

- [ ] **Step 2: update 透传 webhook 字段**

在 `ApplicationController.update` 方法中 `allowedOrigins` 透传行（第 111 行 `if (application.getAllowedOrigins() != null) existing.setAllowedOrigins(application.getAllowedOrigins());`）之后插入：

```java
        if (application.getWebhookUrl() != null) existing.setWebhookUrl(application.getWebhookUrl());
        if (application.getWebhookEvents() != null) existing.setWebhookEvents(application.getWebhookEvents());
```

- [ ] **Step 3: 加导入与依赖字段**

在 import 区加：

```java
import com.qingyu.care.dto.WebhookTestResult;
import com.qingyu.care.service.webhook.WebhookPayloadBuilder;
import com.qingyu.care.service.webhook.WebhookSender;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
```

在字段区（`private final AuthService authService;` 之后）加：

```java
    private final WebhookSender webhookSender;
    private final WebhookPayloadBuilder webhookPayloadBuilder;
```

- [ ] **Step 4: 重置 webhook 密钥端点 + 测试端点**

在 `regenerateSecret` 方法之后（第 141 行后）插入：

```java
    /**
     * 重新生成 Webhook 签名密钥
     */
    @AuditLog(action = "APP_REGENERATE_WEBHOOK_SECRET", targetType = "Application")
    @PostMapping("/{id}/regenerate-webhook-secret")
    public ApiResponse<Application> regenerateWebhookSecret(@PathVariable Long id) {
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);

        app.setWebhookSecret(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        return ApiResponse.success("Webhook 密钥已重新生成", applicationRepository.save(app));
    }

    /**
     * 发送一条测试事件，同步投递并返回结果（仅验连通性，不落投递表、不受订阅过滤）。
     */
    @PostMapping("/{id}/webhook/test")
    public ApiResponse<WebhookTestResult> testWebhook(@PathVariable Long id) {
        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("应用", id.toString()));
        checkAppAccess(app);
        if (app.getWebhookUrl() == null || app.getWebhookUrl().isBlank()) {
            throw new InvalidOperationException("请先配置 Webhook 回调 URL");
        }

        String deliveryId = UUID.randomUUID().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appId", app.getId());
        data.put("message", "测试事件");
        String payload = webhookPayloadBuilder.build(deliveryId, "webhook.test", LocalDateTime.now(), data);
        try {
            int status = webhookSender.send(app.getWebhookUrl(), app.getWebhookSecret(),
                    "webhook.test", deliveryId, payload);
            boolean ok = status >= 200 && status < 300;
            return ApiResponse.success(new WebhookTestResult(ok, status, ok ? null : "HTTP " + status));
        } catch (Exception e) {
            return ApiResponse.success(new WebhookTestResult(false, null, e.getMessage()));
        }
    }
```

- [ ] **Step 5: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/dto/WebhookTestResult.java backend/src/main/java/com/qingyu/care/controller/ApplicationController.java
git commit -m "feat(webhook): 应用更新透传 webhook 字段 + 重置密钥 + 测试事件端点"
```

---

## 里程碑 C：管理后台接入页

### Task 12: IntegrationPage 增 Webhook 配置区

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/api/integration.ts`
- Modify: `frontend/src/pages/IntegrationPage.tsx`

- [ ] **Step 1: Application 类型加字段**

在 `frontend/src/types/index.ts` 的 `Application` 接口 `allowedOrigins?: string | null;` 之后加：

```ts
    webhookUrl?: string | null;
    webhookSecret?: string | null;
    webhookEvents?: string | null;
```

- [ ] **Step 2: integration.ts 加方法**

在 `frontend/src/api/integration.ts` 的 `updateAllowedOrigins` 之后加：

```ts

    // 更新 Webhook 配置（回调 URL 与订阅事件，逗号分隔）
    updateWebhook: (id: number, payload: { webhookUrl: string; webhookEvents: string }): Promise<ApiResponse<Application>> =>
        api.put(`/applications/${id}`, payload),

    // 重置 Webhook 签名密钥
    regenerateWebhookSecret: (id: number): Promise<ApiResponse<Application>> =>
        api.post(`/applications/${id}/regenerate-webhook-secret`),

    // 发送测试事件
    sendWebhookTest: (id: number): Promise<ApiResponse<{ delivered: boolean; responseStatus: number | null; error: string | null }>> =>
        api.post(`/applications/${id}/webhook/test`),
```

- [ ] **Step 3: IntegrationPage 加 state 与加载**

在 `frontend/src/pages/IntegrationPage.tsx` 的 `const [allowedOrigins, setAllowedOrigins] = useState('');` 之后加：

```tsx
    const [webhookUrl, setWebhookUrl] = useState('');
    const [webhookSecret, setWebhookSecret] = useState('');
    const [webhookEvents, setWebhookEvents] = useState('');
```

在 `load()` 内 `setAllowedOrigins(r.data.allowedOrigins ?? '');` 之后加：

```tsx
            setWebhookUrl(r.data.webhookUrl ?? '');
            setWebhookSecret(r.data.webhookSecret ?? '');
            setWebhookEvents(r.data.webhookEvents ?? '');
```

- [ ] **Step 4: 加处理函数**

在 `saveAllowedOrigins` 之后加（`WEBHOOK_EVENTS` 常量供事件勾选复用）：

```tsx
    const WEBHOOK_EVENTS = [
        { value: 'session.handover_requested', label: '转人工请求' },
        { value: 'session.ended', label: '会话结束' },
    ];

    const toggleWebhookEvent = (value: string) => {
        const set = new Set(webhookEvents.split(',').map((s) => s.trim()).filter(Boolean));
        if (set.has(value)) set.delete(value); else set.add(value);
        setWebhookEvents(Array.from(set).join(','));
    };

    const saveWebhook = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.updateWebhook(currentApp.id, { webhookUrl: webhookUrl.trim(), webhookEvents });
            if (r.code !== 200) throw new Error(r.message);
            toast({ title: '已保存' });
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '保存失败', description: msg, variant: 'destructive' });
        }
    };

    const resetWebhookSecret = async () => {
        if (!currentApp) return;
        if (!confirm('确认重置 Webhook 密钥？重置后旧密钥立即失效，接收方需更新。')) return;
        try {
            const r = await integrationService.regenerateWebhookSecret(currentApp.id);
            if (r.code !== 200) throw new Error(r.message);
            setWebhookSecret(r.data.webhookSecret ?? '');
            toast({ title: 'Webhook 密钥已重置' });
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '重置失败', description: msg, variant: 'destructive' });
        }
    };

    const testWebhook = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.sendWebhookTest(currentApp.id);
            if (r.code !== 200) throw new Error(r.message);
            if (r.data.delivered) {
                toast({ title: '测试事件已送达', description: `HTTP ${r.data.responseStatus}` });
            } else {
                toast({ title: '测试事件投递失败', description: r.data.error ?? `HTTP ${r.data.responseStatus}`, variant: 'destructive' });
            }
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '测试失败', description: msg, variant: 'destructive' });
        }
    };
```

- [ ] **Step 5: 加 Webhook 配置区 UI**

在 IntegrationPage 的 JSX 中，「Widget 嵌入代码」区块（含 `{embed}` 的 `</div>`）之后、组件最外层 `</div>` 之前插入：

```tsx
            <div className="space-y-2">
                <label className="text-sm font-medium">Webhook 回调 URL</label>
                <input
                    className="w-full text-sm rounded-md border p-2 bg-background"
                    value={webhookUrl}
                    onChange={(e) => setWebhookUrl(e.target.value)}
                    placeholder="https://your-system.com/webhooks/qingyu"
                />
                <p className="text-xs text-muted-foreground">留空表示未启用 Webhook。事件发生时向此地址 POST 带签名的 JSON。</p>

                <label className="text-sm font-medium">订阅事件</label>
                <div className="flex flex-col gap-1">
                    {WEBHOOK_EVENTS.map((ev) => (
                        <label key={ev.value} className="flex items-center gap-2 text-sm">
                            <input
                                type="checkbox"
                                checked={webhookEvents.split(',').map((s) => s.trim()).includes(ev.value)}
                                onChange={() => toggleWebhookEvent(ev.value)}
                            />
                            {ev.label}（{ev.value}）
                        </label>
                    ))}
                </div>

                <div className="flex gap-2">
                    <Button onClick={saveWebhook}>保存 Webhook</Button>
                    <Button variant="outline" onClick={testWebhook}>发送测试事件</Button>
                </div>

                <label className="text-sm font-medium">Webhook 签名密钥</label>
                <div className="flex items-center gap-2">
                    <code className="text-xs bg-muted px-2 py-1 rounded">
                        {webhookSecret ? webhookSecret.slice(0, 6) + '••••••••' + webhookSecret.slice(-4) : '（重置后生成）'}
                    </code>
                    <Button variant="outline" onClick={resetWebhookSecret}>重置密钥</Button>
                </div>
                <p className="text-xs text-muted-foreground">
                    接收方用此密钥校验请求头 <code>X-Qingyu-Signature</code>（<code>sha256=HMAC-SHA256(secret, 请求体)</code>），并按 <code>X-Qingyu-Delivery-Id</code> 幂等去重。
                </p>
            </div>
```

- [ ] **Step 6: 前端构建校验**

Run: `pnpm -C frontend build`
Expected: 构建通过（tsc -b + vite），无类型错误

- [ ] **Step 7: 提交**

```bash
git add frontend/src/types/index.ts frontend/src/api/integration.ts frontend/src/pages/IntegrationPage.tsx
git commit -m "feat(webhook): 接入页 Webhook 配置区（URL/事件/密钥/测试）"
```

---

## 收尾

### Task 13: 全量验证

- [ ] **Step 1: 后端全部 webhook 单测**

Run: `mvn -f backend/pom.xml -q -Dtest="Webhook*Test" test`
Expected: 全部 PASS（Signature/PayloadBuilder/Sender/Dispatcher）
读 `backend/target/surefire-reports/*.txt` 确认 `Failures: 0, Errors: 0`。

- [ ] **Step 2: 既有相关单测不回归**

Run: `mvn -f backend/pom.xml -q -Dtest="OpenApiAuthServiceTest,ApiUsageServiceTest,RateLimitServiceTest" test`
Expected: 全部 PASS（这些是现存的纯单测；webhook 改动未触及它们，作回归保险）

- [ ] **Step 3: 前端构建**

Run: `pnpm -C frontend build`
Expected: 构建通过

- [ ] **Step 4: （无需额外提交）**

若前几步均无改动，本 Task 仅验证。若发现问题修复，则按修复内容提交。

---

## 联调验收（人工，非自动化）

1. 启后端（`mvn -f backend/pom.xml spring-boot:run`）。
2. 用 [webhook.site](https://webhook.site) 取一个回调 URL，在某应用「接入配置」页填入、勾选「转人工请求 / 会话结束」、保存；「重置密钥」拿到 secret。
3. 点「发送测试事件」→ webhook.site 应收到 `webhook.test`，校验 `X-Qingyu-Signature`。
4. 通过开放 API `/open/v1/sessions/{id}/handover` 或 widget 转人工按钮触发转人工 → webhook.site 收到 `session.handover_requested`（trigger=user_request）。
5. 结束会话 → 收到 `session.ended`。
6. 反例：把回调 URL 改成一个返回 500 的地址，触发事件 → 观察 `sys_webhook_delivery` 行 `attempts` 递增、`status` 由 PENDING 最终转 FAILED（退避约 1/5/15/30/60 分钟，可临时把 backoff 调小验证）。

---

## Self-Review（作者自查）

**Spec 覆盖**（对照 spec §10 交付物）：
- Application 三字段 → Task 2 ✅；`WebhookDelivery` + 仓库（claim/scan）→ Task 1 ✅；领域事件 + 3 处 publish → Task 8/10 ✅；`WebhookDispatcher`(AFTER_COMMIT 落库) → Task 8 ✅；`WebhookSender`(@Async 认领/退避) → Task 7 ✅；`@Scheduled` 重试 + `@EnableScheduling` → Task 9/3 ✅；`WebhookSignature`(HMAC) → Task 4 ✅；`RestClient` 配置(超时) → Task 3/5 ✅；`WebhookProperties` → Task 3 ✅；`ApplicationController` 透传 + regenerate-webhook-secret + webhook/test → Task 11 ✅；单测 → Task 4/6/7/8 ✅。
- 接入页 Webhook 配置区（URL/密钥重置/事件多选/发送测试）→ Task 12 ✅；`integration.ts` + 类型 → Task 12 ✅。
- 验签说明文案 → Task 12 Step 5（接入页说明）✅。

**类型/签名一致性**：`WebhookSender.deliverAsync(Long)`、`send(url,secret,event,deliveryId,body):int`、`backoffMinutes(int):long`；`WebhookHttpClient.post(url,headers,body):int`；`WebhookPayloadBuilder.build(id,event,createdAt,data):String`；`WebhookSignature.sign(secret,body):String`；`WebhookDomainEvent(eventType,appId,tenantId,sessionId,trigger,occurredAt)`；`WebhookDeliveryRepository.claim(id):int` / `findByStatusAndNextAttemptAtLessThanEqual(...)` —— 各 Task 间引用一致。

**无占位符**：所有步骤含完整代码与命令。签名已知向量若与实测不符，Task 4 Step 4 说明以实测更正断言（算法正确即可），非占位。

**已知非目标**（不实现）：全量消息镜像、投递日志页、消息队列、多端点、SSRF 出站过滤、密钥加密、`agent_accepted` 等额外事件——均列后续。

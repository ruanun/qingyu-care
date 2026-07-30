# Spring Boot 4.1 + Spring AI 2.0 迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 qingyu-care 后端从 Spring Boot 3.5.16 + Spring AI 1.1.8 升到 Spring Boot 4.1.0 + Spring AI 2.0.0，JDK 保持 17，功能行为零回归。

**Architecture:** 单分支 `chore/boot4-springai2`，按 6 个提交推进。前 3 个提交仍在 Boot 3.5 基线上完成、各自全量测试绿（把 Testcontainers 2.0、bucket4j 升级、废弃项清理这些"能提前做掉的"从大跳里摘出去）；第 4 个提交跳 Boot 4.1（Spring AI 仍是 1.1.8，此时 IT 预期红）；第 5 个提交跳 Spring AI 2.0 并要求全量绿；第 6 个提交做真机冒烟修正。

**Tech Stack:** Java 17、Maven、Spring Boot 4.1.0、Spring AI 2.0.0、Hibernate 7.4.1、Jackson 3.1.4、Testcontainers 2.0.5、Sa-Token 1.45.0、springdoc-openapi 3.0.3、bucket4j 8.19.0、PostgreSQL(pgvector)、Redis。

设计稿：`docs/superpowers/specs/2026-07-30-boot4-springai2-migration-design.md`

## Global Constraints

- **JDK 保持 17**，不升 21。`pom.xml` 的 `<java.version>17</java.version>` 不动。
- 所有产出用**简体中文**：代码注释、commit message、文档。新建文件的作者署名用 `ruan`。
- 分支 `chore/boot4-springai2` **已从 master 创建并已有一个 docs 提交**（`965b2c1`）。允许 `git commit`，**禁止 `git push`**，禁止 `git merge` / `git rebase` / `git reset`。
- 所有 Maven 命令的工作目录是 `backend/`。
- 单元测试必须是纯 Mockito（`@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`），不启 Spring 容器、不需要 Docker。
- 集成测试类名必须以 `IT` 结尾、放在 `com.qingyu.care.it` 包、继承 `com.qingyu.care.support.AbstractPostgresIT`。
- `mvn test` = Surefire 单测（当前基线 **390 个**，免 Docker）；`mvn verify` = 单测 + Failsafe 集成测试（当前基线 **97 个 IT**，需 Docker Desktop 运行）。
- 基础设施先起：`cd docker && docker compose up -d`（PostgreSQL(pgvector) + RustFS + Redis）。
- 后端启动需环境变量 `DASHSCOPE_API_KEY`。
- **禁止修改 `widget/` 与 `frontend/`**，本次迁移只动 `backend/` 与 `docs/`。

---

### Task 1: Testcontainers 1.21.3 → 2.0.5（Boot 3.5 基线，要求全绿）

Testcontainers 2.0 把模块坐标加了 `testcontainers-` 前缀，容器类下沉到模块专属包。已核实 `testcontainers-2.0.5.jar` 与 `testcontainers-postgresql-2.0.5.jar` 的实际类清单：`GenericContainer`、`DockerImageName`、`ImageFromDockerfile` **仍在原包**（`WebhookQueueIT`、`RealtimeRedisBridgeIT` 零改动）；`PostgreSQLContainer` 新旧包并存，但要主动迁到新包，避免依赖将来会移除的兼容位置。

**Files:**
- Modify: `backend/pom.xml`（`testcontainers-bom` 版本 + 两处 test 依赖坐标）
- Modify: `backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java:16`（一行 import）

**Interfaces:**
- Consumes: 无
- Produces: 无（纯依赖与 import 迁移，不改任何签名）

- [ ] **Step 1: 记录基线**

```bash
cd backend
mvn -q test 2>&1 | tail -20
```

记下 `Tests run: ...` 的总数，应为 390。

- [ ] **Step 2: 改 pom 的 testcontainers-bom 版本**

`backend/pom.xml` 的 `<dependencyManagement>` 里：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>2.0.5</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 3: 改两处 test 依赖坐标**

`backend/pom.xml` 的 `<dependencies>` 里，把这两块：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

改成：

```xml
<!-- Testcontainers 2.0 起模块坐标统一加 testcontainers- 前缀 -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: 改 AbstractPostgresIT 的 import**

`backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java` 第 16 行：

```java
import org.testcontainers.containers.PostgreSQLContainer;
```

改成：

```java
// Testcontainers 2.0 起容器类下沉到模块专属包（旧包仅作兼容保留）
import org.testcontainers.postgresql.PostgreSQLContainer;
```

其余 import（`org.testcontainers.images.builder.ImageFromDockerfile`、`org.testcontainers.utility.DockerImageName`）**保持不变**。

- [ ] **Step 5: 确认没有遗漏的 Testcontainers import**

```bash
cd backend && grep -rn "^import org.testcontainers" src/
```

预期只剩 4 行：`AbstractPostgresIT` 的 3 行（postgresql.PostgreSQLContainer / images.builder.ImageFromDockerfile / utility.DockerImageName）+ `WebhookQueueIT` 与 `RealtimeRedisBridgeIT` 各一行 `containers.GenericContainer`。若出现编译不到的类，说明该类也下沉了，按 `unzip -l` 实际包名调整。

- [ ] **Step 6: 全量回归**

```bash
cd backend && mvn clean verify
```

预期：单测 390 绿、IT 97 绿。若 IT 报容器启动失败，先确认 Docker Desktop 在运行。

- [ ] **Step 7: 提交**

```bash
git add backend/pom.xml backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java
git commit -m "chore(backend): Testcontainers 1.21.3→2.0.5(坐标加 testcontainers- 前缀+PostgreSQLContainer 迁模块包)"
```

---

### Task 2: bucket4j 8.14 → 8.19 + 新增真 Redis 限流 IT（Boot 3.5 基线，要求全绿）

**这是整条链路最关键的一步。** 限流是 fail-open 的（`RateLimitService.tryConsume` catch 一切异常后 `return true` 放行），且当前只有 Mockito 单测 `RateLimitServiceTest`，**没有任何真 Redis 的集成验证**。Task 5 升到 Boot 4.1 后 Lettuce 会从 6.x 跳到 7.5.2，而 `bucket4j_jdk17-lettuce` 是按 `lettuce-core:6.1.8 provided` 编译的、官方未声明支持 Lettuce 7 —— 一旦不兼容，异常会被 fail-open 吞掉，**全部测试仍然绿、而线上限流已经失效**。

所以这一步要先在 Lettuce 6 下建立一张"限流真的会返回 429"的网，并且**用变异测试证明这张网抓得住失效**。

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/RateLimitRedisIT.java`
- Modify: `backend/pom.xml`（`bucket4j.version` 属性）

**Interfaces:**
- Consumes:
  - `AbstractPostgresIT.seedApp(String suffix, String allowedOrigins) -> Application`（已有）
  - `RateLimitService.tryConsume(String appKey, int limitPerMinute) -> boolean`（已有，不改）
  - `RateLimitService.resolveLimit(Application app, int defaultLimit) -> int`（已有静态方法，不改）
  - 受限端点：`GET /open/v1/widget/config`，鉴权走 `X-App-Id`(= `app.getAppKey()`) + `Origin` 白名单
- Produces: 无生产代码接口变更

- [ ] **Step 1: 写这个失败测试**

创建 `backend/src/test/java/com/qingyu/care/it/RateLimitRedisIT.java`：

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开放 API 限流真 Redis 端到端 IT。
 *
 * <p>存在的唯一理由：限流是 fail-open 的（Redis 侧任何异常都放行），
 * 所以「限流组件坏掉」不会让任何现有测试变红——只会静默放行。本类断言
 * 「超额请求必须真的拿到 429 + Retry-After」，是升级 Lettuce/bucket4j 时
 * 唯一能发现静默失效的哨兵。改动限流链路后必须跑通本类。
 *
 * <p>注意：本类自带 Redis 动态属性，会另起一个 Spring 上下文，不与其它 IT 共享
 * （与 {@link WebhookQueueIT} 同款写法）。
 *
 * @author ruan
 */
class RateLimitRedisIT extends AbstractPostgresIT {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    // applicationRepository 由 AbstractPostgresIT 以 protected 字段提供，不要在此重复 @Autowired
    @Autowired MockMvc mockMvc;

    @Test
    void 超出每分钟额度的请求返回429并带RetryAfter() throws Exception {
        // 每个用例用独立 appKey，避免复用同一 Redis 令牌桶导致相互干扰
        Application app = seedApp("ratelimit", "http://it.example.com");
        app.setRateLimitPerMinute(2);
        applicationRepository.save(app);

        // 容量 2：前两次放行
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/open/v1/widget/config")
                            .header("X-App-Id", app.getAppKey())
                            .header("Origin", "http://it.example.com"))
                    .andExpect(status().isOk());
        }

        // 第 3 次必须被限流。refillGreedy(2, 1min) 折算约 30s 补 1 个令牌，
        // 紧接着的这次请求不可能补上，故断言稳定。
        mockMvc.perform(get("/open/v1/widget/config")
                        .header("X-App-Id", app.getAppKey())
                        .header("Origin", "http://it.example.com"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void 额度内的请求全部放行() throws Exception {
        Application app = seedApp("ratelimit-ok", "http://it.example.com");
        app.setRateLimitPerMinute(5);
        applicationRepository.save(app);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/open/v1/widget/config")
                            .header("X-App-Id", app.getAppKey())
                            .header("Origin", "http://it.example.com"))
                    .andExpect(status().isOk());
        }
    }
}
```

- [ ] **Step 2: 跑它，确认在当前代码下是绿的**

```bash
cd backend && mvn verify -Dit.test=RateLimitRedisIT
```

预期：2 个用例 PASS。（这是一张回归网而非新功能，所以它一开始就该绿。）

若报 `Retry-After` 断言失败，去 `OpenApiAuthInterceptor.java:73` 核对实际写入的头值，以生产代码为准修正断言 —— **不要改生产代码去迁就测试**。

- [ ] **Step 3: 变异验证 —— 证明这张网抓得住静默失效**

临时把 `backend/src/main/java/com/qingyu/care/service/RateLimitService.java` 的 `tryConsume` 方法体第一行改成直接放行，模拟"限流组件坏掉被 fail-open 吞掉"：

```java
    public boolean tryConsume(String appKey, int limitPerMinute) {
        if (true) return true; // ← 临时变异，验证 IT 有效性后必须删掉
        try {
```

```bash
cd backend && mvn verify -Dit.test=RateLimitRedisIT
```

**预期：`超出每分钟额度的请求返回429并带RetryAfter` 变红**（实际 200，期望 429）。若它仍然绿，说明这张网是假的，必须先修测试再继续 —— 这一步不通过就不要往下走。

- [ ] **Step 4: 撤销变异**

删掉 Step 3 加的那行 `if (true) return true;`，然后确认已还原：

```bash
cd backend && git diff src/main/java/com/qingyu/care/service/RateLimitService.java
```

预期：**无输出**（该文件本任务不应有任何生产代码改动）。

- [ ] **Step 5: 升 bucket4j 到 8.19.0**

`backend/pom.xml` 的 `<properties>`：

```xml
<bucket4j.version>8.19.0</bucket4j.version>
```

三个 bucket4j 依赖（`bucket4j_jdk17-core`、`bucket4j_jdk17-redis-common`、`bucket4j_jdk17-lettuce`）都用 `${bucket4j.version}`，无需逐个改。

- [ ] **Step 6: 全量回归**

```bash
cd backend && mvn clean verify
```

预期：单测 390 绿、IT 99 绿（97 + 本次新增 2 个）。

若 `RateLimitConfig` 编译报错，看 `Bucket4jLettuce.casBasedBuilder(...)` 或 `ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(...)` 在 8.19 是否改名，按编译器提示修正并在注释里写明改了什么。

- [ ] **Step 7: 提交**

```bash
git add backend/pom.xml backend/src/test/java/com/qingyu/care/it/RateLimitRedisIT.java
git commit -m "test: 新增 RateLimitRedisIT 真 Redis 限流哨兵(429+Retry-After,守 fail-open 静默失效)+bucket4j 8.14→8.19"
```

---

### Task 3: 属性与废弃 API 预清理（Boot 3.5 基线，要求全绿）

Boot 4.0 会移除所有在 3.x 中标记废弃的 API 与属性。在还能编译运行的 3.5 基线上先把它们扫出来清掉，可以让 Task 4 的失败面只剩"真正的 Boot 4 破坏性变更"。

**Files:**
- Modify: `backend/pom.xml`（临时加 `spring-boot-properties-migrator`，本任务结束前移除）
- Modify: 视扫描结果而定 —— `backend/src/main/resources/application.yml`、`application-dev.yml`、以及编译告警指向的源文件

**Interfaces:**
- Consumes: 无
- Produces: 无

- [ ] **Step 1: 临时挂上属性迁移器**

`backend/pom.xml` 的 `<dependencies>` 末尾临时加入：

```xml
<!-- 临时：扫描废弃/改名的配置属性，扫完即移除 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-properties-migrator</artifactId>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: 启动一次，收集属性告警**

先确保基础设施在跑（`cd docker && docker compose up -d`），然后：

```bash
cd backend && DASHSCOPE_API_KEY=dummy mvn spring-boot:run -Dspring-boot.run.profiles=dev 2>&1 | grep -iE "deprecat|renamed|properties migration|WARN.*property" | head -40
```

启动成功后 Ctrl-C 停掉。把输出里所有 "Property source ... deprecated / renamed" 条目记下来。

- [ ] **Step 3: 按告警改配置**

逐条改 `backend/src/main/resources/application.yml` 与 `application-dev.yml`。每改一处加一行中文注释说明改名来源，例如：

```yaml
  # Boot 4 起 spring.dao.exceptiontranslation.enabled 改名为 spring.persistence.exceptiontranslation.enabled
```

若 Step 2 无任何告警输出，本步跳过并在提交信息里注明"属性扫描无命中"。

- [ ] **Step 4: 收集废弃 API 编译告警**

```bash
cd backend && mvn clean compile -Dmaven.compiler.showDeprecation=true 2>&1 | grep -iE "deprecat" | sort -u | head -40
```

- [ ] **Step 5: 清理指向 Spring 自身废弃 API 的告警**

只处理**告警来源是 Spring / Jakarta 的**条目（这些在 Boot 4 会真的消失）。第三方库（Sa-Token、x-file-storage、hutool）自身的废弃告警**不要动**，它们不受 Boot 4 影响。每处改动加中文注释说明替换理由。

若无 Spring 侧废弃告警，本步跳过并在提交信息里注明。

- [ ] **Step 6: 摘掉属性迁移器**

删除 Step 1 加入的 `spring-boot-properties-migrator` 依赖块。

- [ ] **Step 7: 全量回归**

```bash
cd backend && mvn clean verify
```

预期：单测 390 绿、IT 99 绿。

- [ ] **Step 8: 提交**

```bash
git add backend/
git commit -m "chore(backend): Boot 4 预清理(properties-migrator 扫描配置属性+清理 Spring 侧废弃 API)"
```

若 Step 3 与 Step 5 都无命中导致没有任何改动，则跳过本提交，直接进入 Task 4，并在 Task 4 的提交信息里注明"预清理无命中"。

---

### Task 4: Spring Boot 3.5.16 → 4.1.0（Spring AI 仍 1.1.8，IT 预期红）

这是"一把梭"里唯一一个不要求全绿的提交 —— 设计稿 §2.1 已论证 Spring AI 1.1.8 与 Boot 4 无重叠区间。但**门槛不是"随便红"**：本仓单测是纯 Mockito、不启 Spring 容器，所以 **`mvn test` 的 390 个单测必须全绿**；只有需要 Spring 上下文的 IT 允许红。

**Files:**
- Modify: `backend/pom.xml`（parent 版本、Sa-Token 坐标、springdoc 版本）
- Modify: 52 个含 Jackson import 的源文件（`backend/src/main` 与 `backend/src/test`）
- Modify: `backend/src/main/java/com/qingyu/care/service/HandoverRuleService.java`（3 处 catch）
- Modify: `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeNotifier.java`（1 处 catch）
- Modify: `backend/src/main/java/com/qingyu/care/service/webhook/WebhookPayloadBuilder.java`（1 处 catch）

**Interfaces:**
- Consumes: 无
- Produces: 无签名变更（`ObjectMapper` 仍叫 `ObjectMapper`，只是包从 `com.fasterxml.jackson.databind` 变成 `tools.jackson.databind`）

- [ ] **Step 1: 换 parent 到 4.1.0**

`backend/pom.xml`：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
</parent>
```

`<java.version>17</java.version>` **保持不动**（Boot 4.1 最低就是 Java 17）。

- [ ] **Step 2: 换 Sa-Token 到 Boot 4 starter**

`backend/pom.xml`，把：

```xml
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot3-starter</artifactId>
    <version>1.45.0</version>
</dependency>
```

改成：

```xml
<!-- Sa-Token 权限认证。Boot 4 需用 spring-boot4 系列 starter：
     与 boot3 版同为 1.45.0、jar 内只有占位类，差异纯在 POM
     （依赖 sa-token-jackson3 而非 sa-token-jackson），业务 API 不变。 -->
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot4-starter</artifactId>
    <version>1.45.0</version>
</dependency>
```

- [ ] **Step 3: 升 springdoc 到 3.0.3**

`backend/pom.xml`：

```xml
<!-- springdoc 3.0.x 是适配 Spring Boot 4 的版本线（2.8.x 只支持 Boot 3） -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.0.3</version>
</dependency>
```

- [ ] **Step 4: 机械替换 Jackson 2 包名到 Jackson 3**

Jackson 3 的 databind / core 迁到 `tools.jackson.*`，但**注解仍留在 `com.fasterxml.jackson.annotation`**（9 处不能动）。所以替换必须精确限定到 `databind` 与 `core` 两个子包：

```bash
cd backend
grep -rl "com\.fasterxml\.jackson\.\(databind\|core\)" src/ \
  | xargs sed -i 's/com\.fasterxml\.jackson\.databind/tools.jackson.databind/g; s/com\.fasterxml\.jackson\.core/tools.jackson.core/g'
```

- [ ] **Step 5: 校验替换结果**

```bash
cd backend
echo "--- 应只剩 annotation（9 处） ---"
grep -rn "com\.fasterxml\.jackson" src/ | grep -v "com\.fasterxml\.jackson\.annotation"
echo "--- 新包应出现 ---"
grep -rc "tools\.jackson" src/ | grep -v ":0" | wc -l
```

第一条命令预期**无输出**；第二条预期 52。

- [ ] **Step 6: 修 checked → unchecked 异常**

Jackson 3 里 `JsonProcessingException` 不存在，异常基类改为 `tools.jackson.core.JacksonException` 且是 **unchecked**。Step 4 的 sed 已把 import 改成 `tools.jackson.core.JsonProcessingException`（不存在的类），需手工改这 5 处 catch。

`backend/src/main/java/com/qingyu/care/service/HandoverRuleService.java`：import 改为

```java
import tools.jackson.core.JacksonException;
```

三处 `} catch (JsonProcessingException e) {`（原第 163、185、203 行）改为：

```java
} catch (JacksonException e) {
```

同样处理 `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeNotifier.java`（原第 94 行）与 `backend/src/main/java/com/qingyu/care/service/webhook/WebhookPayloadBuilder.java`（原第 33 行）。

改完检查是否有方法签名上残留 `throws JsonProcessingException`：

```bash
cd backend && grep -rn "JsonProcessingException" src/
```

预期**无输出**。

- [ ] **Step 7: 扁平化 Spring AI 属性（提前做，Task 5 不再动 yml）**

Spring AI 2.0 移除了 `.options` 中缀。`backend/src/main/resources/application.yml` 的 `spring.ai.openai` 段改为：

```yaml
  ai:
    openai:
      # 通义千问 DashScope OpenAI 兼容端点
      api-key: ${DASHSCOPE_API_KEY:your-dashscope-api-key}
      base-url: ${AI_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}
      # Spring AI 2.0 起配置属性去掉 .options 中缀（chat.options.model → chat.model）
      chat:
        model: ${AI_CHAT_MODEL:qwen3.7-flash-2026-07-15}  # 通义千问 flash（2026-07-15 快照）；可用环境变量 AI_CHAT_MODEL 覆盖
        temperature: 0.7
      embedding:
        model: ${AI_EMBEDDING_MODEL:qwen3.7-text-embedding}
        # 显式钉死输出维度，不再依赖模型默认值——必须与下方 pgvector.dimensions 完全一致。
        # 两处共用 AI_EMBEDDING_DIMENSIONS，绑定同一变量以防各自漂移导致向量写入维度不匹配。
        # 注意：dimensions 仅 text-embedding-3 及以后 / DashScope text-embedding-v3+ 支持；
        # 若换用不支持该参数的模型，需先移除本项。
        dimensions: ${AI_EMBEDDING_DIMENSIONS:1024}
```

`spring.ai.vectorstore.pgvector` 段**保持不变**（已核实 2.0 属性面一致）。

同步改 `backend/src/main/java/com/qingyu/care/service/ModelRouterService.java:40`：

```java
    @Value("${spring.ai.openai.chat.model:gpt-4o-mini}")
    private String defaultModel;
```

- [ ] **Step 8: 编译并跑单测**

```bash
cd backend && mvn clean test
```

预期：**编译通过、390 个单测全绿**。

若编译失败，按错误逐个处理，常见来源：
- Jackson 3 的 `ObjectMapper` 某些方法改名（如 `readValue` 抛的异常类型）
- Hibernate 7 移除的 `Session#save/get/load`（本仓不用，若报错说明有遗漏调用点）
- Jakarta EE 11 的 Servlet 6.1 API 变化

若某个单测红，**必须修到绿**再继续 —— 单测不启 Spring 容器，它红说明是真的代码问题，不是 Spring AI 版本错配。

- [ ] **Step 9: 确认 IT 的红是"预期的红"**

```bash
cd backend && mvn verify 2>&1 | tail -60
```

预期：IT 大面积红，失败原因应集中在 Spring 上下文启动失败 / `NoClassDefFoundError: com/fasterxml/jackson/databind/...`（Spring AI 1.1.8 的 jar 仍按 Jackson 2 编译）。

**把失败摘要记下来**，Task 5 结束后要逐条确认它们都消失了。若看到与 Spring AI 无关的失败（例如 Hibernate 建表失败、Sa-Token 拦截器装配失败），那是真问题，**必须在本任务里修掉**。

- [ ] **Step 10: 提交**

```bash
git add backend/
git commit -m "chore(backend)!: Spring Boot 3.5.16→4.1.0(Sa-Token boot4 starter+springdoc 3.0.3+Jackson 2→3 全量+spring.ai 属性扁平化)

本提交单测绿、IT 预期红：Spring AI 1.1.8 硬绑 Boot 3.5，与 Boot 4 无重叠区间，
需 Task 5 升到 Spring AI 2.0 后才能恢复全绿。拆成两个提交仅为评审时 diff 可读。"
```

> **已预先排除的风险**：设计稿 §5 列的 P2「Hibernate 7 把 `char`/`Character` 由 `char(1)` 改映射为 `varchar(1)`，叠加 `ddl-auto: update` 可能改表」经核查不成立 —— `backend/src/main/java/com/qingyu/care/entity/` 下没有任何 `char`/`Character` 字段。本任务无需为此做额外检查。

---

### Task 5: Spring AI 1.1.8 → 2.0.0（要求全量绿）

Spring AI 2.0 把 `spring-ai-openai` 整体换成官方 `com.openai:openai-java-core:4.39.1`，`org.springframework.ai.openai.api.OpenAiApi` 已删除。已通过 `javap` 反编译 `OpenAiChatModel$Builder.build()` 确认：**未显式提供 `openAiClient` 时，它会用 options 上的 `getBaseUrl()` / `getApiKey()` / `getTimeout()` / `getMaxRetries()` / `getProxy()` / `getCustomHeaders()` 调 `OpenAiSetup.setupSyncClient(...)` 自行装配客户端**。所以多租户动态端点路由直接由 options 承载即可，不需要手工构造 SDK 客户端。

**Files:**
- Modify: `backend/pom.xml`（`spring-ai.version` 属性）
- Modify: `backend/src/main/java/com/qingyu/care/service/ModelRouterService.java:189-214`（`createOpenAiCompatibleClient`）

**Interfaces:**
- Consumes: `AiModelConfig.getEndpoint() / getApiKey() / getModelId() / getProvider()`（已有实体，不改）
- Produces: `ModelRouterService.createOpenAiCompatibleClient(AiModelConfig) -> ChatClient` —— **签名不变**，只换内部实现。`createChatClient`、`getChatClientForScenario`、`getDefaultChatClient`、`callWithFallback` 全部保持原签名，调用方（`ChatServiceImpl`、`QaGenerationService`）零改动。

- [ ] **Step 1: 升 BOM 到 2.0.0**

`backend/pom.xml` 的 `<properties>`：

```xml
<spring-ai.version>2.0.0</spring-ai.version>
```

三个 Spring AI 依赖坐标（`spring-ai-starter-model-openai`、`spring-ai-starter-vector-store-pgvector`、`spring-ai-tika-document-reader`）**不变** —— 已核实这三个 artifactId 在 2.0.0 均存在。

- [ ] **Step 2: 先编译，把断点暴露出来**

```bash
cd backend && mvn clean compile 2>&1 | grep -E "ERROR.*\.java" | head -20
```

预期至少看到 `ModelRouterService.java` 报 `OpenAiApi` 找不到。

- [ ] **Step 3: 重写 createOpenAiCompatibleClient**

`backend/src/main/java/com/qingyu/care/service/ModelRouterService.java`，删掉这行 import：

```java
import org.springframework.ai.openai.api.OpenAiApi;
```

把 `createOpenAiCompatibleClient` 方法整体替换为：

```java
    /**
     * 创建 OpenAI 兼容的 ChatClient。
     *
     * <p>Spring AI 2.0 起 spring-ai-openai 改用官方 openai-java SDK，
     * {@code OpenAiApi} 已移除。baseUrl / apiKey 下沉到 {@link OpenAiChatOptions}：
     * {@code OpenAiChatModel.Builder#build()} 在未显式给 openAiClient 时，
     * 会用 options 上的 baseUrl/apiKey 自行装配 SDK 客户端——正好承载我们
     * 按租户动态换端点的路由需求。
     */
    private ChatClient createOpenAiCompatibleClient(AiModelConfig config) {
        String baseUrl = config.getEndpoint();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getDefaultEndpoint(config.getProvider());
        }

        // streamUsage(true)：让 OpenAI 兼容端点在流式末尾 chunk 携带 usage 信息，
        // 供 ChatServiceImpl.meterContentStream() 的 doOnNext 捕获 total_tokens。
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getModelId())
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .streamUsage(true)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .build();

        return ChatClient.builder(chatModel).build();
    }
```

- [ ] **Step 4: 编译**

```bash
cd backend && mvn clean compile
```

预期：BUILD SUCCESS。

若还有 Spring AI 相关编译错误，对照设计稿 §3.1 的"2.0 API 形态"逐个处理。设计稿已核实这些类在 2.0 存在且包路径不变：`ChatClient`、`AssistantMessage`/`SystemMessage`/`UserMessage`/`Message`、`ChatResponseMetadata`、`DefaultUsage`、`ChatResponse`、`Generation`、`ToolContext`、`Document`、`ToolCallback`、`ToolDefinition`/`DefaultToolDefinition`、`ToolMetadata`、`VectorStore`、`SearchRequest`、`PgVectorStore`、`TikaDocumentReader`、`TokenTextSplitter`。

- [ ] **Step 5: 跑单测**

```bash
cd backend && mvn test
```

预期：390 绿。重点关注 `MeterContentStreamTest`（token 计量）与 `ChatServiceModelScenarioTest`（模型路由）。

- [ ] **Step 6: 全量回归**

```bash
cd backend && mvn clean verify
```

预期：单测 390 绿、IT 99 绿。

逐条对照 Task 4 Step 9 记下的失败摘要，确认全部消失。若仍有 IT 红：
- `RateLimitRedisIT` 红 → **P0 命中，Lettuce 7 与 bucket4j 不兼容**。退路依次尝试：① 查是否有更高版 bucket4j 声明支持 Lettuce 7；② 在 `<dependencyManagement>` 里 pin `io.lettuce:lettuce-core` 到 6.x 并验证 spring-data-redis 2026.0.0 能否接受；③ 改用 spring-data-redis 原生 Lua 令牌桶自实现。**不要**通过放宽 `RateLimitRedisIT` 的断言来"修复"。
- `OpenSessionResponseTest` / 卡片相关 IT 红 → P1 命中，Jackson 3 的构造参数名绑定未生效，需显式配置 `ObjectMapper`。
- `*FallbackIT`（`ChatStrategyFallbackIT` / `PromptTemplateFallbackIT` / `ModelRouterDefaultIT` / `HandoverRuleFallbackIT`）红 → 大概率是 Hibernate 7 的"隐式 SELECT 需显式结果类型"影响了 native 回退查询，按报错给查询补显式结果类型。

- [ ] **Step 7: 复查依赖树冲突**

```bash
cd backend && mvn dependency:tree -Dincludes=org.apache.tika:tika-core,com.squareup.okhttp3:okhttp,io.lettuce:lettuce-core
```

确认 `tika-core` 收敛到 Spring AI 需要的 3.x（x-file-storage-core 带的是 2.4.1）。若收敛到 2.4.1，在 `x-file-storage-spring` 依赖上加 exclusion 并重跑 `mvn verify`。

- [ ] **Step 8: 提交**

```bash
git add backend/
git commit -m "feat(backend)!: Spring AI 1.1.8→2.0.0(OpenAiApi 移除,baseUrl/apiKey 下沉 OpenAiChatOptions,官方 openai-java SDK)"
```

---

### Task 6: 真机冒烟与收尾

单测和 IT 都不会真的打到 DashScope，所以官方 SDK 对 `base-url` 的处理（是否仍自动补 `/v1`）只能真机验证 —— 这是设计稿里剩下的最后一个 P0。

**Files:**
- Modify（视冒烟结果而定）: `backend/src/main/resources/application.yml`、`backend/src/main/java/com/qingyu/care/service/ModelRouterService.java`（`getDefaultEndpoint`）
- Modify: `docs/superpowers/specs/2026-07-30-boot4-springai2-migration-design.md`（补迁移结论）

**Interfaces:**
- Consumes: Task 5 产出的 `ModelRouterService.createOpenAiCompatibleClient`
- Produces: 无

- [ ] **Step 1: 起基础设施与后端**

```bash
cd docker && docker compose up -d
cd ../backend && DASHSCOPE_API_KEY=<真实key> mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

- [ ] **Step 2: 冒烟 —— RAG 非流式对话（验 base-url 语义，P0）**

前端 `cd frontend && pnpm dev`，浏览器走：登录 → 建应用 → 上传一份文档等摄取完成 → 在对话页问一个文档里有答案的问题。

预期：拿到基于知识库的回答。

若报 404 / `Not Found` / `invalid URL`，说明官方 SDK 不再自动补 `/v1`。此时把 `application.yml` 的 `spring.ai.openai.base-url` 默认值改成 `https://dashscope.aliyuncs.com/compatible-mode/v1`，并同步核对 `ModelRouterService.getDefaultEndpoint()` 里四个 provider 的默认端点是否都带 `/v1`（`openai` 分支当前是 `https://api.openai.com`，缺 `/v1`）。改完重启复验。

- [ ] **Step 3: 冒烟 —— 流式对话 + token 计量**

用开放 API 打流式端点（`X-App-Id` + `X-Api-Key` 见应用详情页）：

```bash
curl -N -X POST http://localhost:8080/api/open/v1/chat/stream \
  -H "X-App-Id: <appKey>" -H "X-Api-Key: <appSecret>" \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

预期：SSE 逐字返回。随后查 `sys_api_usage` 表确认本次调用落了非零 token：

```bash
docker exec -i $(docker ps -qf name=postgres) psql -U postgres -d qingyu_care \
  -c "select app_id, tokens_used, created_at from sys_api_usage order by id desc limit 5;"
```

预期：最新一行 `tokens_used > 0`。若为 0，说明 `streamUsage(true)` 在新 SDK 下未生效，需排查 `ChatServiceImpl.meterContentStream()` 拿到的 `ChatResponse` 元数据结构。

- [ ] **Step 4: 冒烟 —— 其余关键链路**

逐项确认：
- 开放 API 限流：连续快打超过应用配额，应返回 429 + `Retry-After`
- Webhook 投递：触发一次转人工，确认 `webhook_delivery` 表出现 `SUCCESS` 行
- 坐席 SSE：坐席台能收到实时消息
- Swagger UI：`http://localhost:8080/api/swagger-ui.html` 能正常渲染（验 springdoc 3.0.3）
- 文件上传：知识库上传文档成功（验 x-file-storage 在 Boot 4 下装配正常）

- [ ] **Step 5: 若 Step 2-4 有修改，重跑全量回归**

```bash
cd backend && mvn clean verify
```

预期：单测 390 绿、IT 99 绿。

- [ ] **Step 6: 把迁移结论补回设计稿**

在 `docs/superpowers/specs/2026-07-30-boot4-springai2-migration-design.md` 末尾追加一节：

```markdown
## 8. 迁移结论（实施后回填）

- 实际落地版本：Spring Boot 4.1.0 / Spring AI 2.0.0 / JDK 17
- P0「bucket4j × Lettuce 7」：<实际结果，兼容或采用了哪条退路>
- P0「base-url 语义」：<是否需要补 /v1>
- P1「Jackson 3 构造参数名绑定」：<是否需要显式配置>
- P1「tika-core 版本收敛」：<实际收敛版本，是否加了 exclusion>
- P2「Hibernate 7 隐式 SELECT」：<是否命中，改了哪些查询>
- 最终测试规模：<单测数> 单测 + <IT 数> IT 全绿
- 真机冒烟：<通过项清单>
```

- [ ] **Step 7: 提交**

```bash
git add backend/ docs/
git commit -m "docs: 回填 Boot 4.1 + Spring AI 2.0 迁移结论(真机冒烟通过)"
```

- [ ] **Step 8: 交付给用户决定是否合入**

向用户汇报：全量测试结果、真机冒烟清单、遇到的实际问题与处理方式。**不要自行 merge 到 master，也不要 push** —— 等用户确认。

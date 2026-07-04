# 集成回归网 + 种子归属审计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 Testcontainers 建一张集成回归网，把本次联调修复的 6 类集成 bug 固化为自动化测试，并审计消除同类「默认种子被租户过滤」隐患。

**Architecture:** 新增 `maven-failsafe-plugin`，集成测试命名 `*IT.java` 绑 `verify` 阶段；`mvn test` 保持纯单测免 Docker。基类 `AbstractPostgresIT` 用单例 `PostgreSQLContainer(pgvector/pgvector:pg17)` + `@DynamicPropertySource` 提供真实 PG，MockMvc 驱动真实拦截器/CORS/资源链。每条已修 bug 一条 IT；对 3 处 `@PostConstruct` 种子逐一审计。

**Tech Stack:** JUnit 5、Testcontainers（postgresql + junit-jupiter）、Awaitility、Spring Boot Test（MockMvc）、Maven Failsafe。

**参考 spec:** `docs/superpowers/specs/2026-07-04-integration-regression-net-design.md`

---

## 文件结构

**新增/修改：**
- 修改 `backend/pom.xml`：加 Testcontainers BOM + `postgresql`/`junit-jupiter`/`awaitility`（test scope）+ `maven-failsafe-plugin`。
- 新增 `backend/src/test/resources/db/init-vector.sql`：容器 init 脚本（`CREATE EXTENSION vector`）。
- 修改 `backend/src/test/resources/application-test.yml`：加 `spring.ai.vectorstore.pgvector.initialize-schema: true`。
- 新增 `backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java`：集成测试基类 + 播种辅助。
- 新增 7 个 IT：`SmokeIT`、`LoginIT`、`OpenApiAuthIT`、`CorsPreflightIT`、`WidgetJsIT`、`ApiUsageMeteringIT`、`PromptTemplateFallbackIT`、`WebhookDeliveryIT`（均置于 `backend/src/test/java/com/qingyu/care/it/`）。
- 审计产物：`ChatStrategyService`/`HandoverRuleService`/`ModelRouterService` 各一条 `*FallbackIT`，硬失败者附对应 repository/service 修复。

**约定：**
- MockMvc 路径**不含** context-path `/api`（`@AutoConfigureMockMvc` 默认剥离），即用 `/v1/auth/login`、`/open/v1/...`、`/widget.js`。
- 非异步 IT 用 `@Transactional` 自动回滚隔离；**WebhookDeliveryIT / ApiUsageMeteringIT 不加** `@Transactional`（依赖真实提交/异步），用唯一键 + `@AfterEach` 清理。

---

## Task 1: 构建接入（Testcontainers 依赖 + Failsafe）

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: 加 Testcontainers BOM 到 `<dependencyManagement>`**

在 `backend/pom.xml` 的 `<dependencyManagement><dependencies>` 内（spring-ai-bom 旁）加：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.20.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 2: 加测试依赖**

在 `<dependencies>` 内（`spring-boot-starter-test` 旁）加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
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
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: 加 Failsafe 插件**

在 `<build><plugins>` 内加（surefire 用默认，failsafe 拾取 `*IT.java`）：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 4: 验证依赖可解析、单测不受影响**

Run: `cd backend && mvn -q -DskipTests dependency:resolve && mvn test -Dtest='!QingyuCareApplicationTests'`
Expected: 依赖解析成功；60 个单测仍全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/pom.xml
git commit -m "build: 引入 Testcontainers + Failsafe（mvn verify 跑集成测试）"
```

---

## Task 2: 集成测试基座 + 冒烟用例

**Files:**
- Create: `backend/src/test/resources/db/init-vector.sql`
- Modify: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java`
- Create: `backend/src/test/java/com/qingyu/care/it/SmokeIT.java`

- [ ] **Step 1: 写 SmokeIT（先失败：基类还不存在）**

`backend/src/test/java/com/qingyu/care/it/SmokeIT.java`：

```java
package com.qingyu.care.it;

import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** 上下文能对着真实 PG(容器) 启动，且默认管理员已建。 */
class SmokeIT extends AbstractPostgresIT {

    @Autowired
    private UserRepository userRepository;

    @Test
    void 上下文加载且默认管理员存在() {
        assertThat(userRepository.findByUsername("admin")).isPresent();
    }
}
```

- [ ] **Step 2: 运行确认失败（基类缺失，编译错）**

Run: `cd backend && mvn -q test-compile`
Expected: 编译失败 `cannot find symbol: AbstractPostgresIT`。

- [ ] **Step 3: 写 init 脚本**

`backend/src/test/resources/db/init-vector.sql`：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 4: 改 test profile 加向量 schema 自建**

`backend/src/test/resources/application-test.yml`，在 `spring.ai.openai` 同级的 `spring.ai` 下补 `vectorstore`（与 `openai` 平级）：

```yaml
  ai:
    openai:
      api-key: test-key
    vectorstore:
      pgvector:
        initialize-schema: true
```

- [ ] **Step 5: 写基类（含播种辅助）**

`backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java`：

```java
package com.qingyu.care.support;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：单例 pgvector 容器 + MockMvc + 播种辅助。
 * 容器 static 单例，JVM 内跨全部 IT 复用（不随类停启，加速）。
 *
 * @author ruan
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
                    .withInitScript("db/init-vector.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // 不打真实 LLM：本计划所有用例都不驱动成功的 chat 生成（OpenApiAuthIT 的 chat 请求在鉴权处即 401）。
    // 若后续新增“整条 chat”用例，在该 IT 内用 @MockitoBean ModelRouterService 局部返回固定响应即可，基类不 mock。

    @Autowired
    protected TenantRepository tenantRepository;
    @Autowired
    protected ApplicationRepository applicationRepository;

    /** 播种一个租户 + 应用（Application 未继承 BaseTenantEntity，tenant_id 走关系映射，无需 TenantContext）。 */
    protected Application seedApp(String suffix, String allowedOrigins) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("IT租户-" + suffix).code("IT_" + suffix)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        Application app = new Application();
        app.setName("IT应用-" + suffix);
        app.setTenant(tenant);
        app.setAppKey("app_it_" + suffix);
        app.setAppSecret("sec_it_" + suffix);
        app.setEnabled(true);
        app.setAllowedOrigins(allowedOrigins);
        return applicationRepository.save(app);
    }
}
```

- [ ] **Step 6: 运行冒烟 IT（需 Docker）**

Run: `cd backend && mvn -q verify -Dit.test=SmokeIT -Dtest=none -DfailIfNoTests=false`
Expected: 拉起 pgvector 容器、上下文启动、`SmokeIT` 绿。

- [ ] **Step 7: 确认 `mvn test` 仍免 Docker（Failsafe 不在 test 阶段跑 IT）**

Run: `cd backend && mvn -q test -Dtest='!QingyuCareApplicationTests'`
Expected: 60 单测绿，未拉容器（IT 归 failsafe/verify）。

- [ ] **Step 8: 提交**

```bash
git add backend/src/test/resources/db/init-vector.sql backend/src/test/resources/application-test.yml \
        backend/src/test/java/com/qingyu/care/support/AbstractPostgresIT.java \
        backend/src/test/java/com/qingyu/care/it/SmokeIT.java
git commit -m "test: 集成测试基座（pgvector 容器 + MockMvc + 播种辅助）+ 冒烟 IT"
```

---

## Task 3: LoginIT（回归 #1 登录被租户过滤挡死）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/LoginIT.java`

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 未登录态下 findByUsername 不得被租户过滤挡掉（否则谁都登不上）。 */
class LoginIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 平台管理员可登录并拿到token() throws Exception {
        mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("PLATFORM_ADMIN"));
    }

    @Test
    void 未登录访问受保护接口返回401() throws Exception {
        mockMvc.perform(get("/v1/applications"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 运行确认通过（修复已在）**

Run: `cd backend && mvn -q verify -Dit.test=LoginIT -Dtest=none -DfailIfNoTests=false`
Expected: 绿。

- [ ] **Step 3: 验证该 IT 确实守住 bug（临时还原 #1）**

临时把 `TenantDataAspect.enableFilters()` 里的
```java
        if (isPlatformAdmin() || !isLoggedIn()) {
            return;
        }
```
改回旧逻辑（删掉 `!isLoggedIn()`，即 `if (isPlatformAdmin()) return;`）。
Run: `cd backend && mvn -q verify -Dit.test=LoginIT -Dtest=none -DfailIfNoTests=false`
Expected: `平台管理员可登录并拿到token` **FAIL**（登录 400）。随后**还原**该改动，重跑 → 绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/LoginIT.java
git commit -m "test: LoginIT 固化登录不被租户过滤挡死（回归 #1）"
```

---

## Task 4: OpenApiAuthIT（回归 开放 API 鉴权 401/403/200）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/OpenApiAuthIT.java`

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.Application;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 开放 API 双路径鉴权：服务端 Api-Key、浏览器 Origin 白名单。 */
class OpenApiAuthIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void widget配置_白名单Origin通过_非白名单403_未知appKey401() throws Exception {
        Application app = seedApp("auth", "http://it.example.com");

        mockMvc.perform(get("/open/v1/widget/config")
                        .header("X-App-Id", app.getAppKey())
                        .header("Origin", "http://it.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value(app.getName()));

        mockMvc.perform(get("/open/v1/widget/config")
                        .header("X-App-Id", app.getAppKey())
                        .header("Origin", "http://evil.example.com"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/open/v1/widget/config")
                        .header("X-App-Id", "app_不存在")
                        .header("Origin", "http://it.example.com"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 对话端点_无凭据401_错secret401() throws Exception {
        Application app = seedApp("auth2", "http://it.example.com");

        mockMvc.perform(post("/open/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/open/v1/chat/completions")
                        .header("X-App-Id", app.getAppKey()).header("X-Api-Key", "错的")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

> 注：`seedApp` 在测试内保存；本 IT 加 `@org.springframework.transaction.annotation.Transactional` 使播种与请求同事务、测试后回滚。

- [ ] **Step 2: 给类加 `@Transactional` 隔离**

在 `class OpenApiAuthIT` 上加注解：

```java
import org.springframework.transaction.annotation.Transactional;
// ...
@Transactional
class OpenApiAuthIT extends AbstractPostgresIT {
```

- [ ] **Step 3: 运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=OpenApiAuthIT -Dtest=none -DfailIfNoTests=false`
Expected: 绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/OpenApiAuthIT.java
git commit -m "test: OpenApiAuthIT 固化开放 API 双路径鉴权"
```

---

## Task 5: CorsPreflightIT（回归 #5 CORS 预检 401）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/CorsPreflightIT.java`

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 浏览器跨域预检 OPTIONS 必须 2xx，否则开放 API 全被拦。 */
class CorsPreflightIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 开放API的CORS预检返回2xx() throws Exception {
        mockMvc.perform(options("/open/v1/widget/config")
                        .header("Origin", "http://it.example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-app-id"))
                .andExpect(status().is2xxSuccessful());
    }
}
```

- [ ] **Step 2: 运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=CorsPreflightIT -Dtest=none -DfailIfNoTests=false`
Expected: 绿。

- [ ] **Step 3: 验证守住 bug（临时还原 #5）**

临时删掉 `OpenApiAuthInterceptor.preHandle` 开头的
```java
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
```
Run: 同 Step 2。Expected: **FAIL**（预检 401）。还原后重跑 → 绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/CorsPreflightIT.java
git commit -m "test: CorsPreflightIT 固化开放 API 预检放行（回归 #5）"
```

---

## Task 6: WidgetJsIT（回归 #3/#4 静态脚本鉴权 + MIME）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/WidgetJsIT.java`

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 公开挂件脚本：免登录可取(200) + Content-Type 为 JavaScript + 非空脚本体。 */
class WidgetJsIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void widgetJs免登录返回200且ContentType为javascript() throws Exception {
        mockMvc.perform(get("/widget.js").param("appKey", "any"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("function")));
    }
}
```

- [ ] **Step 2: 运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=WidgetJsIT -Dtest=none -DfailIfNoTests=false`
Expected: 绿（200 + `text/javascript`）。

- [ ] **Step 3: 验证守住 bug（临时还原 #3）**

临时从 `SaTokenConfig` 的 `notMatch(...)` 删掉 `"/widget.js"`。
Run: 同 Step 2。Expected: **FAIL**（401）。还原后重跑 → 绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/WidgetJsIT.java
git commit -m "test: WidgetJsIT 固化挂件脚本放行 + JS MIME（回归 #3/#4）"
```

---

## Task 7: ApiUsageMeteringIT（回归 #6 计量 @Modifying 缺事务）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/ApiUsageMeteringIT.java`

- [ ] **Step 1: 写 IT（不加 @Transactional：record 为 @Async 独立事务）**

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.service.ApiUsageService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** @Modifying 累加缺事务会抛 TransactionRequiredException 被吞、计量静默丢失。 */
class ApiUsageMeteringIT extends AbstractPostgresIT {

    private static final Long TENANT = 90001L;
    private static final Long APP = 90002L;

    @Autowired
    private ApiUsageService apiUsageService;
    @Autowired
    private SysApiUsageRepository repository;

    @AfterEach
    void cleanup() {
        repository.findAll().stream()
                .filter(u -> APP.equals(u.getAppId()))
                .forEach(repository::delete);
    }

    @Test
    void record成功落库并累加() {
        apiUsageService.record(TENANT, APP, 1, 5);
        apiUsageService.record(TENANT, APP, 1, 7);

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<SysApiUsage> rows = repository.findAll().stream()
                    .filter(u -> APP.equals(u.getAppId())).toList();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getCallCount()).isEqualTo(2);
            assertThat(rows.get(0).getMessageCount()).isEqualTo(2);
            assertThat(rows.get(0).getTokenCount()).isEqualTo(12);
        });
    }
}
```

- [ ] **Step 2: 运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=ApiUsageMeteringIT -Dtest=none -DfailIfNoTests=false`
Expected: 绿（一行、call=2、msg=2、token=12）。

- [ ] **Step 3: 验证守住 bug（临时还原 #6）**

临时删掉 `SysApiUsageRepository.incrementUsage` 上的 `@Transactional`。
Run: 同 Step 2。Expected: **FAIL**（await 超时，无行）。还原后重跑 → 绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/ApiUsageMeteringIT.java
git commit -m "test: ApiUsageMeteringIT 固化用量计量落库（回归 #6）"
```

---

## Task 8: PromptTemplateFallbackIT（回归 Prompt 平台级回退）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/PromptTemplateFallbackIT.java`

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.PromptTemplate;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** 某租户/应用无自有激活模板时，getActiveTemplate 应回退到平台默认(tenant_id=NULL) 而非 404。 */
class PromptTemplateFallbackIT extends AbstractPostgresIT {

    @Autowired
    private PromptTemplateService promptTemplateService;

    @AfterEach
    void clearCtx() {
        TenantContext.clear();
    }

    @Test
    void 租户无自有模板时回退平台默认() {
        // @PostConstruct 已建平台默认模板(tenant_id=NULL, is_active=true)
        TenantContext.setTenantId(90101L);   // 无自有模板的租户
        TenantContext.setAppId(90102L);

        PromptTemplate active = promptTemplateService.getActiveTemplate();

        assertThat(active).isNotNull();
        assertThat(active.getTenantId()).isNull();   // 命中平台默认
    }
}
```

- [ ] **Step 2: 运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=PromptTemplateFallbackIT -Dtest=none -DfailIfNoTests=false`
Expected: 绿。

- [ ] **Step 3: 验证守住 bug（临时还原回退）**

临时把 `PromptTemplateService.getActiveTemplate` 的 `.or(repository::findPlatformDefaultActive)` 删掉。
Run: 同 Step 2。Expected: **FAIL**（抛 ResourceNotFoundException）。还原后重跑 → 绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/PromptTemplateFallbackIT.java
git commit -m "test: PromptTemplateFallbackIT 固化 Prompt 平台级回退"
```

---

## Task 9: WebhookDeliveryIT（回归 #2 AFTER_COMMIT 落库 + 投递 + 验签）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/WebhookDeliveryIT.java`

- [ ] **Step 1: 写 IT（内嵌 HttpServer 收投递 + Awaitility 等异步 + HMAC 验签）**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.WebhookDeliveryStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.WebhookDeliveryRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 触发 handover → 事务提交后落 sys_webhook_delivery(REQUIRES_NEW) → 异步投递到本地桩 → HMAC 验签。 */
class WebhookDeliveryIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    private HttpServer receiver;
    private int port;
    private final ConcurrentMap<String, String> captured = new ConcurrentHashMap<>();
    private Application app;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        // 起本地 webhook 接收桩，记录 body 与签名头
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                captured.put("body", body);
                String sig = exchange.getRequestHeaders().getFirst("X-Qingyu-Signature");
                if (sig != null) captured.put("signature", sig);
            }
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(ok); }
        });
        receiver.start();
        port = receiver.getAddress().getPort();

        // 播种应用（订阅 handover 事件，配 webhookUrl 指向接收桩）
        app = seedApp("wh", "http://it.example.com");
        app.setWebhookUrl("http://127.0.0.1:" + port + "/hook");
        app.setWebhookSecret("whsec_it_001");
        app.setWebhookEvents("session.ended,session.handover_requested");
        app = applicationRepository.save(app);

        // 播种会话（ChatSession 继承 BaseTenantEntity，需在 TenantContext 下落 tenant/app_id）
        sessionId = "it-sess-" + port;
        try {
            TenantContext.setTenantId(app.getTenant().getId());
            TenantContext.setAppId(app.getId());
            ChatSession s = new ChatSession();
            s.setSessionId(sessionId);
            s.setStatus(SessionStatus.AI_PROCESSING);
            s.setUserId("it-user");
            chatSessionRepository.save(s);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        if (receiver != null) receiver.stop(0);
        webhookDeliveryRepository.findAll().stream()
                .filter(d -> app.getId().equals(d.getAppId()))
                .forEach(webhookDeliveryRepository::delete);
    }

    @Test
    void 转人工触发webhook落库投递并验签() throws Exception {
        mockMvc.perform(post("/open/v1/sessions/" + sessionId + "/handover")
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret()))
                .andExpect(status().isOk());

        // 等异步投递完成：落库 SUCCESS 且接收桩收到
        await().atMost(10, SECONDS).untilAsserted(() -> {
            boolean delivered = webhookDeliveryRepository.findAll().stream()
                    .anyMatch(d -> app.getId().equals(d.getAppId())
                            && d.getStatus() == WebhookDeliveryStatus.SUCCESS);
            assertThat(delivered).isTrue();
            assertThat(captured).containsKey("body").containsKey("signature");
        });

        // HMAC-SHA256 验签
        String body = captured.get("body");
        String expected = "sha256=" + hmacSha256("whsec_it_001", body);
        assertThat(captured.get("signature")).isEqualTo(expected);
        assertThat(body).contains("session.handover_requested");
    }

    private static String hmacSha256(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
```

> 注：本 IT **不加** `@Transactional`（AFTER_COMMIT 监听器只在真实提交后触发；异步投递在独立线程/连接）。`@AfterEach` 清理投递行并停桩。若 `ChatSession` 无无参构造/setter，改用其 builder（参照实体定义调整）。

- [ ] **Step 2: 运行确认通过**

Run: `cd backend && mvn -q verify -Dit.test=WebhookDeliveryIT -Dtest=none -DfailIfNoTests=false`
Expected: 绿（SUCCESS 落库 + 桩收到 + 验签相等）。

- [ ] **Step 3: 验证守住 bug（临时还原 #2）**

临时删掉 `WebhookDispatcher.onDomainEvent` 上的 `@Transactional(propagation = Propagation.REQUIRES_NEW)`。
Run: 同 Step 2。Expected: **FAIL**（await 超时，无 SUCCESS 行、桩零捕获）。还原后重跑 → 绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/WebhookDeliveryIT.java
git commit -m "test: WebhookDeliveryIT 固化 Webhook 落库+投递+验签（回归 #2）"
```

---

## Task 10: 审计 ChatStrategyService 种子归属

**Files:**
- Read: `backend/src/main/java/com/qingyu/care/service/ChatStrategyService.java`、`.../repository/ChatStrategyRepository.java`
- Create: `backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java`
- （若发现硬失败）Modify: 上述 repository/service

- [ ] **Step 1: 调查种子与解析路径**

Run:
```bash
cd backend && sed -n '1,80p' src/main/java/com/qingyu/care/service/ChatStrategyService.java
grep -nE "findBy|@Query|scenario|Optional|默认|default" src/main/java/com/qingyu/care/repository/ChatStrategyRepository.java
```
判断：`@PostConstruct` 建的默认策略 `tenant_id=NULL`，在**租户上下文**下被解析方法查询时，是被租户过滤挡掉（硬失败/取空）还是已有兜底（如 `.or(...)`、原生查询、或代码默认值）。

- [ ] **Step 2: 写审计 IT（表达“租户无自有策略时仍能拿到默认策略”）**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计：某租户无自有策略时，策略解析不得因租户过滤而拿不到默认策略。 */
class ChatStrategyFallbackIT extends AbstractPostgresIT {

    @Autowired
    private ChatStrategyService chatStrategyService;

    @AfterEach
    void clearCtx() {
        TenantContext.clear();
    }

    @Test
    void 租户无自有策略时能拿到默认策略() {
        TenantContext.setTenantId(90201L);
        TenantContext.setAppId(90202L);

        // 用实际解析方法名替换 getStrategy(...)（见 Step 1 调查结果）
        var strategy = chatStrategyService.getStrategy("default");

        assertThat(strategy).isNotNull();
    }
}
```

> 按 Step 1 查到的真实方法名/签名调整 `getStrategy("default")` 与断言。

- [ ] **Step 3: 运行**

Run: `cd backend && mvn -q verify -Dit.test=ChatStrategyFallbackIT -Dtest=none -DfailIfNoTests=false`

- [ ] **Step 4: 按结果分支处理**

- **若绿（已有兜底）**：IT 直接作为回归固化，进入 Step 5。
- **若红（硬失败/取空）**：套用与 Prompt 同款修复——
  在 `ChatStrategyRepository` 加原生回退查询：
  ```java
  @Query(value = "SELECT * FROM chat_strategy WHERE scenario = :scenario AND tenant_id IS NULL ORDER BY id LIMIT 1",
          nativeQuery = true)
  java.util.Optional<com.qingyu.care.entity.ChatStrategy> findPlatformDefault(@org.springframework.data.repository.query.Param("scenario") String scenario);
  ```
  在解析方法里当租户范围取空时 `.or(() -> repository.findPlatformDefault(scenario))` 回退（参照 `PromptTemplateService.getActiveTemplate` 的写法），再重跑至绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/ChatStrategyFallbackIT.java
# 若有修复一并 add 对应 repository/service
git commit -m "test: ChatStrategy 种子归属审计 + 回归 IT"
```

---

## Task 11: 审计 HandoverRuleService 种子归属

**Files:**
- Read: `backend/src/main/java/com/qingyu/care/service/HandoverRuleService.java`、`.../repository/HandoverRuleRepository.java`
- Create: `backend/src/test/java/com/qingyu/care/it/HandoverRuleFallbackIT.java`
- （若发现硬失败）Modify: 上述 repository/service

- [ ] **Step 1: 调查**

Run:
```bash
cd backend && sed -n '1,90p' src/main/java/com/qingyu/care/service/HandoverRuleService.java
grep -nE "findBy|@Query|Enabled|Optional|List|默认|default" src/main/java/com/qingyu/care/repository/HandoverRuleRepository.java
```
判断默认转人工规则（`tenant_id=NULL`）在租户上下文下能否被解析取到；注意转人工规则常是「取列表逐条判定」——列表为空通常是**优雅退化**（不触发转人工）而非报错。

- [ ] **Step 2: 写审计 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatCode;

/** 审计：租户无自有转人工规则时，规则查询不因租户过滤而异常（列表空=不触发，属正常退化）。 */
class HandoverRuleFallbackIT extends AbstractPostgresIT {

    @Autowired
    private HandoverRuleService handoverRuleService;

    @AfterEach
    void clearCtx() {
        TenantContext.clear();
    }

    @Test
    void 租户无自有规则时解析不抛异常() {
        TenantContext.setTenantId(90301L);
        TenantContext.setAppId(90302L);

        // 用实际查询/判定方法名替换（见 Step 1）
        assertThatCode(() -> handoverRuleService.getEnabledRules()).doesNotThrowAnyException();
    }
}
```

> 按 Step 1 调整方法名。若默认规则本应对所有租户生效但被过滤挡住（业务判定为 bug），套用 Task 10 同款平台级回退修复。

- [ ] **Step 3: 运行 + 按结果分支（同 Task 10 Step 3/4 逻辑）**

Run: `cd backend && mvn -q verify -Dit.test=HandoverRuleFallbackIT -Dtest=none -DfailIfNoTests=false`

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/HandoverRuleFallbackIT.java
git commit -m "test: HandoverRule 种子归属审计 + 回归 IT"
```

---

## Task 12: 审计 ModelRouterService 种子归属

**Files:**
- Read: `backend/src/main/java/com/qingyu/care/service/ModelRouterService.java`
- Create: `backend/src/test/java/com/qingyu/care/it/ModelRouterDefaultIT.java`

- [ ] **Step 1: 调查**

Run:
```bash
cd backend && grep -nE "getModelsForScenario|mergeTenantAndShared|findShared|scope|@PostConstruct|scenario|default" src/main/java/com/qingyu/care/service/ModelRouterService.java
```
确认默认模型种子（`ai_model_config`，`@PostConstruct` 建）在租户上下文下如何可见——联调中 app=2 对话跑通，说明 `mergeTenantAndShared` 的 `findSharedModelsByScenario`/scope 提供了跨租户可见路径。核实其机制并用 IT 固化。

- [ ] **Step 2: 写审计 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计：租户无自有模型时，default 场景仍能解析到平台默认模型（联调中对话跑通依赖此）。 */
class ModelRouterDefaultIT extends AbstractPostgresIT {

    @Autowired
    private ModelRouterService modelRouterService;

    @AfterEach
    void clearCtx() {
        TenantContext.clear();
    }

    @Test
    void 租户无自有模型时default场景能解析到平台默认() {
        TenantContext.setTenantId(90401L);
        TenantContext.setAppId(90402L);

        List<AiModelConfig> models = modelRouterService.getModelsForScenario("default");

        assertThat(models).isNotEmpty();
    }
}
```

> 基类已不 mock `ModelRouterService`（见 Task 2），故此处 `@Autowired` 拿到的是真实 bean。

- [ ] **Step 3: 运行本 IT**

Run: `cd backend && mvn -q verify -Dit.test=ModelRouterDefaultIT -Dtest=none -DfailIfNoTests=false`
Expected: 绿（default 场景非空）。若红，说明默认模型也被租户过滤挡住 → 套用平台级回退修复（同 Task 10 模式，针对 `AiModelConfigRepository`）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/ModelRouterDefaultIT.java
git commit -m "test: ModelRouter 默认模型可见性审计 + 回归 IT"
```

---

## Task 13: 全量 `mvn verify` + 收尾

**Files:** 无新增

- [ ] **Step 1: 跑全部单测 + 集成测试**

Run: `cd backend && mvn verify`
Expected: `mvn test` 段 60 单测绿；failsafe 段全部 `*IT` 绿；`BUILD SUCCESS`。

- [ ] **Step 2: 确认 `mvn test` 仍免 Docker**

Run: `cd backend && mvn -q test -Dtest='!QingyuCareApplicationTests'`
Expected: 60 单测绿、未拉容器。

- [ ] **Step 3: 更新 spec 状态为已实现**

把 `docs/superpowers/specs/2026-07-04-integration-regression-net-design.md` frontmatter `status: 待评审` 改为 `status: 已实现`；在 §3.3 补一段「审计结论」（3 处种子各一句：有无 bug、如何处理）。

- [ ] **Step 4: 提交**

```bash
git add docs/superpowers/specs/2026-07-04-integration-regression-net-design.md
git commit -m "docs: 集成回归网落地，补种子归属审计结论"
```

---

## 完成标准

- `mvn test`：纯单测、免 Docker、全绿（不受本改动影响）。
- `mvn verify`：额外跑通全部 `*IT`（Login/OpenApiAuth/CorsPreflight/WidgetJs/ApiUsageMetering/PromptTemplateFallback/WebhookDelivery + 3 条审计 IT）。
- 6 个已修 bug 各有 IT，且「临时还原修复 → 对应 IT 变红」验证过回归网有效。
- 3 处 `@PostConstruct` 种子归属有明确结论，硬失败者已修并有 IT。

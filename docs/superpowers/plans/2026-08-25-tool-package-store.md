# 工具商店（能力包）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 平台管理员把自己调通的一组工具 + skill 导出成「能力包」上架，租户挑一个包、填自己的凭据，一键安装成自己名下可改可停用的副本。

**Architecture:** 三个纯函数类（`PackageManifestBuilder` 导出、`PackageManifestParser` 解析校验、`PackageVariableApplier` 变量填充）关住全部规则，两个 `@Service`（`ToolPackageService` 平台侧编排、`PackageInstallService` 安装编排）只做取数与落库，两个控制器按角色分开（`/v1/platform/packages/**` 要 `PLATFORM_ADMIN`，`/v1/packages/**` 要 `TENANT_ADMIN`）。安装分两段：段一在事务内建 `AiTool`/`McpServer`/`AiSkill`，段二在事务外 best-effort 同步 MCP 工具清单并回填 skill 绑定。

**Tech Stack:** Spring Boot 4.1 / Spring AI 2.0 / JPA(Hibernate 6) / PostgreSQL / Jackson 3（`tools.jackson.databind`）/ JUnit 5 + Mockito + AssertJ / Testcontainers / React 19 + Vite + vitest。

**Spec:** `docs/superpowers/specs/2026-08-25-tool-package-store-design.md`（执行者必须连同本计划一起读，本计划的每条约束都在论证 spec 的某一节）

---

## Global Constraints

以下取自 spec 与 CLAUDE.md，**每个任务的要求都隐含包含本节**：

- **Java 包名用 `pkg` 而非 `package`**（spec §2.5）：`service/pkg/`、`dto/pkg/`。`package` 是 Java 关键字，`com.qingyu.care.service.package` 编译不过。
- **`ToolPackage` 不继承 `BaseTenantEntity`**（spec §2.4）：它没有租户维度，平台建、所有租户可读。沿用 `SysAuditLog`/`WebhookDelivery`/`SysApiUsage` 那批显式不继承的先例。
- **`manifest` 列必须是 `text`，不设列宽**（spec §3.1 / §6.5）：长度由包内容决定，无可预估上限。`McpTool.description` 曾设 `varchar(1000)`，真机接入 context7 时整个 server 接不进来。
- **manifest 里没有 `scope` / `tenantId` / `appId`**（spec §3.4 / §6.3）：安装时一律由服务端强制 `OwnerScope.TENANT` + 当前 `TenantContext`。**DTO 里就不该有这些字段——没有比判空更可靠**。
- **导出全程只读实体，绝不 setter 置空**（spec §4.1 / §6.2）：本项目未配 `spring.jpa.open-in-view`（即 Boot 默认 `true`），OSIV 让查出来的实体在整个请求期保持受管，`setAuthHeader(null)` 会被脏检查刷回库，**把平台管理员自己的凭据真的删掉**。
- **`sync()` 绝不进安装事务**（spec §4.3 / §6.4）：它是网络出站 + 自己写库且刻意不带 `@Transactional`。
- **所有包写操作打 `@AuditLog`**（spec §4.2）：导出、更新、发布、下架、删除、安装。安装尤其要——它一次性在租户名下建出一批带凭据的行。
- **业务异常一律 `BusinessException`**：HTTP 200 + `body.code != 200`，前端必须 `throwIfFailed(res)`（`frontend/src/lib/apiResult.ts`）。不判就是「点了安装毫无反应」（spec §6.6）。
- **新增测试优先写成纯 Mockito 单测**（Surefire，免 Docker）；IT 类名以 `IT` 结尾、放 `it/` 包、继承 `support/AbstractPostgresIT`。
- **注释与 commit 信息一律简体中文**；新文件的 `@author` 写 `ruan`。
- **本仓库允许 `git commit`**，当前分支已是 `feat/tool-package-store`，不要切分支、不要 push。

### 本计划相对 spec 的三处补充（都是 spec 没钉死、不钉死就会静默出错的地方）

1. **变量引用语法定为 `${VAR_NAME}`，变量名匹配 `[A-Z][A-Z0-9_]*`**（spec 只说了「字段级引用 + URL 类字段替换后结构校验」，没定行内语法）。
2. **⚠️ 替换后必须断言四个白名单字段里不再残留任何 `${`** —— 这条是本计划新增的硬校验，理由见 Task 6：`UrlTemplateFiller` 的占位符正则是 `\{([^{}]+)}`，**它会把 `${WEATHER_KEY}` 里的 `{WEATHER_KEY}` 当成 LLM 参数占位符**。残留在 query 段时该段被**整段静默删除**（鉴权参数消失 → 第三方 401 → 模型拿着错误响应继续编答案，全程无异常无日志），残留在 path 段时报「缺少必填参数 WEATHER_KEY」这种误导性错误。
3. **`authHeaderVar` 是字段级引用，变量值即 `authHeader` 的完整值**（含 `Authorization: ` 这样的头名前缀）。导出时自动生成的变量 `help` 必须把原头名带出来，否则租户不知道要填整行。取舍：严格照 spec §3.2 的字段名与「字段级引用」语义，不做行内模板切分（那要在导出侧猜「头名 : 值」的分界，是启发式）；代价是租户填的字符串更长，用 `help` 文案补偿。**若将来要改成行内模板，改动点只在 `PackageManifestBuilder.升格凭据` 与 `PackageVariableApplier.apply` 两处。**

另有一处 spec 自身的笔误，Task 14 顺手修：**§9「变量只能出现在三个白名单字段」应为「四个」**，§3.5 正文列的是四个（`AiTool.authHeader`、`AiTool.urlTemplate`、`McpServer.authHeader`、`McpServer.url`）。

---

## File Structure

**后端主代码（新建）**

| 文件 | 职责 |
|---|---|
| `entity/ToolPackage.java` | 包本体。**不继承** `BaseTenantEntity` |
| `enums/PackageStatus.java` | `DRAFT` / `PUBLISHED` / `ARCHIVED` |
| `repository/ToolPackageRepository.java` | 包 CRUD 查询 |
| `dto/pkg/PackageManifest.java` | manifest 的类型化表示（含 5 个嵌套 record） |
| `dto/pkg/PackageExportRequest.java` | 导出请求：勾选的 id 三件套 + 包文案 |
| `dto/pkg/PackageExportResult.java` | 导出结果：包 + warnings |
| `dto/pkg/ToolPackageUpdateRequest.java` | 平台侧局部更新请求体 |
| `dto/pkg/PackageInstallRequest.java` | 安装请求：变量取值 |
| `dto/pkg/PackageInstallResult.java` | 安装摘要：建了什么、哪些要后续处理 |
| `dto/pkg/PackageDetailDTO.java` | 租户侧包详情（含 variables，供安装表单渲染） |
| `service/pkg/PackageManifestBuilder.java` | **纯函数**：实体 → manifest + warnings |
| `service/pkg/PackageManifestParser.java` | **纯函数**：JSON ↔ manifest + 结构校验 |
| `service/pkg/PackageVariableApplier.java` | **纯函数**：manifest + 变量取值 → 已填充 manifest |
| `service/pkg/ToolPackageService.java` | 平台侧 CRUD、导出编排、发布/下架 |
| `service/pkg/PackageInstallService.java` | 安装编排（冲突检测 + 两段事务） |
| `controller/PlatformPackageController.java` | `/v1/platform/packages/**`，`PLATFORM_ADMIN` |
| `controller/ToolPackageController.java` | `/v1/packages/**`，只读 + 安装，`TENANT_ADMIN` |

**后端测试（新建）**

| 文件 | 类型 |
|---|---|
| `controller/PlatformEndpointGuardTest.java` | 扫描哨兵（Task 1） |
| `dto/pkg/PackageManifestFieldCoverageTest.java` | 扫描哨兵（Task 3） |
| `service/pkg/PackageManifestParserTest.java` | 单测 |
| `service/pkg/PackageManifestBuilderTest.java` | 单测 |
| `service/pkg/PackageVariableApplierTest.java` | 单测 |
| `service/pkg/PackageInstallServiceTest.java` | 单测 |
| `it/PackageInstallIT.java` | IT |
| `it/PackageExportIT.java` | IT |

**后端测试（修改）**：`dto/UpdateRequestFieldCoverageTest.java`、`controller/UpdateRequestPropagationTest.java`（Task 7）

**前端（新建）**：`api/toolPackage.ts`、`pages/PlatformPackagePage.tsx`、`pages/PackageStorePage.tsx`、`components/pkg/PackageExportDialog.tsx`、`components/pkg/PackageInstallDialog.tsx`、`pages/PackageStorePage.test.tsx`、`pages/PlatformPackagePage.test.tsx`
**前端（修改）**：`App.tsx`、`layouts/MainLayout.tsx`

**文档（修改）**：`CLAUDE.md`、`docs/使用手册.md`、`scripts/manual-smoke-hints.sh`、spec §9

---

## Task 1: 平台端点授权哨兵

**为什么先做它**：本特性要新增 `PlatformPackageController`，是补这条哨兵最自然的时机。现存 12 个 `/v1/platform` 端点里 11 个都调了 `SecurityUtil.requirePlatformAdmin()`、**没有任何现存漏洞**（已审计），所以这是纯预防性守护：漏写守卫的后果是任意登录用户（含任意租户的普通成员）能读写平台共享资源，而端点照常返回 200，没有任何现象提醒。

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/controller/PlatformEndpointGuardTest.java`

**Interfaces:**
- Consumes: 无（独立哨兵）
- Produces: 无产品代码。后续 Task 10 新增的 `PlatformPackageController` 每个端点都必须让本哨兵通过。

- [ ] **Step 1: 写哨兵测试**

创建 `backend/src/test/java/com/qingyu/care/controller/PlatformEndpointGuardTest.java`：

```java
package com.qingyu.care.controller;

import com.qingyu.care.util.SecurityUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描式守护「{@code /v1/platform/**} 下的每个端点都必须调用
 * {@link SecurityUtil#requirePlatformAdmin()}」。
 *
 * <p><b>这条约定为什么需要机器强制。</b>本仓库没有声明式的角色注解（没有 Sa-Token 的
 * {@code @SaCheckRole}、也没有 Spring Security 的 {@code @PreAuthorize}），平台端点的授权
 * 完全靠每个方法体第一行手写一句 {@code SecurityUtil.requirePlatformAdmin();}。漏写的后果
 * 是<b>任意登录用户</b>（含任意租户的普通成员）能读写平台共享资源——共享工具与共享模型的
 * 明文凭据就在响应体里（{@code listSharedTools()} 刻意不脱敏，见 {@code ChatToolService}
 * 那条注释），而端点照常返回 200，没有任何现象提醒。
 *
 * <p><b>补它的时机是新增 {@code PlatformPackageController} 那一刻</b>：写这条哨兵时全仓 12 个
 * 平台端点里 11 个守卫齐备、1 个是刻意例外（见 {@link #ALLOWLIST}），即<b>没有存量漏洞可修</b>，
 * 它守的完全是「以后新增平台端点时忘了写那一行」。
 *
 * <p><b>为什么是「反射拿路径 + 源码找方法体」的混合方案。</b>类级路径用反射取最稳
 * （{@code @RequestMapping} 的 value 可能写成常量或用 {@code value =} 具名形式，源码正则都会
 * 漏）；「方法体里有没有调那一句」则只能扫源码——字节码方案（ASM）更精确但要引依赖。范式与
 * {@code AsyncDispatchOutsideTransactionTest} 同源（那条也是反射拿 {@code @Async} 方法名 +
 * 源码扫方法体），连同它的两条防空转断言一起抄过来。
 *
 * <p>⚠️ <b>已知盲区：只认「方法体里直接出现那一句」</b>。把守卫藏进私有辅助方法再调用，
 * 本哨兵会漏掉——同 {@code AsyncDispatchOutsideTransactionTest} 对间接投递的盲区，
 * 覆盖它要做调用图分析，代价大于收益。
 *
 * <p>⚠️ <b>本哨兵只管「有没有调用守卫」，不管「守卫对不对」</b>：一个端点即使调了
 * {@code requirePlatformAdmin()}，仍可能在后续逻辑里用别人的 id 越权操作（那类问题由
 * {@code PlatformController.updateSharedTool} 里的 {@code scope != PLATFORM} 二次校验挡，
 * 见那些方法的注释）。两者是正交的两层，别因为有了本哨兵就删掉那些校验。
 *
 * @author ruan
 */
class PlatformEndpointGuardTest {

    private static final String CONTROLLER_PACKAGE = "com.qingyu.care.controller";

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** 受本哨兵管辖的路径前缀。 */
    private static final String PLATFORM_PREFIX = "/v1/platform";

    /** 要求出现在方法体里的那一句（只匹配调用本身，允许 {@code SecurityUtil.} 前缀在与不在）。 */
    private static final Pattern GUARD_CALL =
            Pattern.compile("requirePlatformAdmin\\s*\\(\\s*\\)");

    /**
     * 刻意不要求平台管理员的例外，格式 {@code 类简单名#方法名}，值是理由。
     *
     * <p>往里加条目前先确认：这个端点返回的东西<b>对任意登录用户都是安全的</b>。平台端点的
     * 默认答案是「不安全」——共享工具/模型的响应体里带着明文凭据。
     */
    private static final Map<String, String> ALLOWLIST = Map.of(
            "PlatformConfigController#announcement",
            "系统公告，任意登录用户可读（管理后台顶部横幅要用，见 MainLayout.tsx 的 getAnnouncement）。"
                    + "它自己调 StpUtil.checkLogin() 要求登录，且只返回单个配置项 platform.announcement 的值，"
                    + "不经过任何带凭据的实体");

    @Test
    @DisplayName("/v1/platform/** 下每个端点方法都调用了 SecurityUtil.requirePlatformAdmin()")
    void 平台端点必须显式要求平台管理员() {
        List<Endpoint> endpoints = scanPlatformEndpoints();

        // 扫描坏掉（包名改了、路径前缀变了、注解换了）时下面的循环会空转、断言退化成
        // 「空==空」永真——本仓库反复栽过这个坑，先把「扫到的端点数非空」钉成一条断言。
        assertThat(endpoints)
                .as("%s 包下一个 %s 端点都没扫到——扫描逻辑本身坏了，下面的守卫断言会全部空转",
                        CONTROLLER_PACKAGE, PLATFORM_PREFIX)
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        Set<String> usedAllowlist = new LinkedHashSet<>();

        for (Endpoint endpoint : endpoints) {
            boolean guarded = GUARD_CALL.matcher(endpoint.body()).find();
            if (guarded) {
                if (ALLOWLIST.containsKey(endpoint.id())) {
                    violations.add(endpoint.id() + " 在例外名单里，但它其实调用了 requirePlatformAdmin()"
                            + "——该条例外已名不副实，请删除，否则它会掩护一个同名的新端点");
                }
                continue;
            }
            if (ALLOWLIST.containsKey(endpoint.id())) {
                usedAllowlist.add(endpoint.id());
                continue;
            }
            violations.add(endpoint.id() + " 是 " + PLATFORM_PREFIX
                    + " 下的端点却没有调用 SecurityUtil.requirePlatformAdmin()："
                    + "本仓库没有声明式角色注解，漏掉这一句 = 任意登录用户可读写平台资源"
                    + "（共享工具与模型的响应体里带明文凭据），且端点照常返回 200 没有任何现象。"
                    + "确属例外请加进 ALLOWLIST 并写明为什么它对任意登录用户安全");
        }

        assertThat(violations)
                .as("平台端点的授权守卫有缺口或例外名单已过时")
                .isEmpty();
        assertThat(usedAllowlist)
                .as("例外名单里有条目已不再命中（端点被删/改名/加上了守卫），请同步清理——"
                        + "留着它会掩护一个同名的新违规")
                .containsExactlyInAnyOrderElementsOf(ALLOWLIST.keySet());
    }

    /** 一个平台端点：id 用于白名单寻址，body 是它在源码里的方法体全文。 */
    private record Endpoint(String id, String body) {
    }

    /**
     * 反射发现 {@code /v1/platform} 下的控制器与端点方法，再去源码里取各自的方法体。
     */
    private static List<Endpoint> scanPlatformEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Controller")));

        List<Endpoint> endpoints = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            String className = definition.getBeanClassName();
            Class<?> controller;
            try {
                controller = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的控制器无法加载: " + className, e);
            }

            RequestMapping classMapping =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            if (classMapping == null || !hasPlatformPath(classMapping)) {
                continue;
            }

            List<String> lines = readLines(sourceFileOf(controller));
            for (Method method : controller.getDeclaredMethods()) {
                // @GetMapping/@PostMapping/... 都以 @RequestMapping 为元注解，findMergedAnnotation
                // 一次覆盖全部五种写法，不必逐个枚举注解类型。
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                    continue;
                }
                String id = controller.getSimpleName() + "#" + method.getName();
                endpoints.add(new Endpoint(id, methodBody(lines, method.getName(), id)));
            }
        }
        return endpoints;
    }

    private static boolean hasPlatformPath(RequestMapping mapping) {
        for (String path : mapping.value()) {
            if (path.startsWith(PLATFORM_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static Path sourceFileOf(Class<?> controller) {
        Path file = SOURCE_ROOT.resolve(controller.getName().replace('.', '/') + ".java");
        assertThat(file)
                .as("找不到 %s 的源文件（测试的工作目录不是模块根目录？）——这时方法体会全是空串，"
                        + "守卫断言会全部误判为违规", controller.getSimpleName())
                .exists();
        return file;
    }

    /**
     * 取源码里该方法的方法体全文（含签名行）。同名重载全部拼接——两个重载都要有守卫才算过，
     * 这是保守的方向。
     */
    private static String methodBody(List<String> lines, String methodName, String id) {
        Pattern signature = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!signature.matcher(line).find() || !line.stripTrailing().endsWith("{")) {
                continue;
            }
            int end = findBlockEnd(lines, i);
            body.append(String.join("\n", lines.subList(i, end + 1))).append('\n');
            i = end;
        }
        assertThat(body.length())
                .as("在源码里没找到 %s 的方法体——签名可能换行了（本方法只认「方法名(... 且行尾是 {」"
                        + "这一种形状），此时守卫断言会误判为违规", id)
                .isPositive();
        return body.toString();
    }

    /** 方法体收尾行：从签名行起做大括号配对（同 AsyncDispatchOutsideTransactionTest）。 */
    private static int findBlockEnd(List<String> lines, int signature) {
        int depth = 0;
        boolean started = false;
        for (int j = signature; j < lines.size(); j++) {
            for (char c : lines.get(j).toCharArray()) {
                if (c == '{') {
                    depth++;
                    started = true;
                } else if (c == '}') {
                    depth--;
                    if (started && depth == 0) {
                        return j;
                    }
                }
            }
        }
        return lines.size() - 1;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读不了源文件: " + file, e);
        }
    }
}
```

- [ ] **Step 2: 跑一遍，确认当前代码库是绿的**

```bash
cd backend && mvn test -Dtest=PlatformEndpointGuardTest
```

预期：**PASS**。现存 12 个平台端点里 11 个有守卫、`PlatformConfigController#announcement` 命中白名单。

⚠️ 若报「一个 /v1/platform 端点都没扫到」，说明反射扫描没生效（检查 `ClassPathScanningCandidateComponentProvider(false)` 的 useDefaultFilters 参数与包名）；若报「找不到源文件」，说明 Maven 的工作目录不是 `backend/`（本仓库 Surefire 默认就在模块根，与 `AsyncDispatchOutsideTransactionTest` 相同）。

- [ ] **Step 3: 变异验证 A —— 摘掉一个真守卫必须转红**

临时编辑 `backend/src/main/java/com/qingyu/care/controller/PlatformController.java`，把 `listSharedTools()` 里的 `SecurityUtil.requirePlatformAdmin();` 那一行删掉，重跑：

```bash
cd backend && mvn test -Dtest=PlatformEndpointGuardTest
```

预期：**FAIL**，消息含 `PlatformController#listSharedTools 是 /v1/platform 下的端点却没有调用`。

**验证完立刻把那一行加回去**，再跑一次确认恢复绿。

- [ ] **Step 4: 变异验证 B —— 三条独立的守护路径各验一次**

本哨兵有**三条**互不相同的守护路径，一次变异只能观察到其中一条：

| 路径 | 代码位置 | 触发条件 |
|---|---|---|
| ① 未守卫且不在白名单 | `violations.add(... 却没有调用 ...)` | 端点漏写守卫 |
| ② 已守卫却在白名单里 | `violations.add(... 在例外名单里，但它其实调用了 ...)` | 补了守卫忘删白名单条目 |
| ③ 白名单条目无端点命中 | `assertThat(usedAllowlist).containsExactly...` | 白名单里有死键 |

⚠️ **①②走的是同一条 `assertThat(violations).isEmpty()`，③走的是另一条独立的 AssertJ 硬断言。
第一条失败即抛异常中断方法，第三条永远执行不到**——所以必须分三次做，否则某条路径的守护能力
从未被观察到生效过，只剩「照理说应该会red」的推理。本仓库反复栽在「论证代替实测」上。

**B-1（验路径①）**：临时把 `ALLOWLIST` 里的 key 改成 `"PlatformConfigController#announcementXXX"`，重跑。

预期：**FAIL**，消息含 `PlatformConfigController#announcement 是 /v1/platform 下的端点却没有调用`。
⚠️ 这次变异**同时**破坏了①和③，但③被①短路、看不到——这正是要拆成三次的原因。恢复。

**B-2（验路径②）**：临时给 `PlatformConfigController.announcement()` 方法体**加上**一行
`SecurityUtil.requirePlatformAdmin();`，重跑。

预期：**FAIL**，消息含 `PlatformConfigController#announcement 在例外名单里，但它其实调用了
requirePlatformAdmin()`。对应真实场景：**有人给公告端点补了守卫，却忘了从白名单里删掉那条**，
留着它会掩护下一个同名端点。恢复。

**B-3（验路径③）**：**保留** `ALLOWLIST` 里真实的 `announcement` 条目不动，**额外添加**一条
指向不存在方法的假条目，如：

```java
            "PlatformController#nonExistentMethod",
            "变异验证用的假条目，验完删除",
```

（`Map.of` 支持多对，直接加一对即可。）重跑。

预期：**FAIL**，消息是 `例外名单里有条目已不再命中（方法被删/改名/加上了守卫），请同步清理`。
⚠️ 这次 `violations` 是**空的**（没有任何端点被错判），所以第一条断言通过、执行流真的走到了
第三条断言——这是唯一能干净隔离路径③的变异。若 B-3 跑出来是绿的，说明那条反向断言是摆设，
必须报回来。恢复代码，确认转绿。

- [ ] **Step 5: 变异验证 C —— 扫描空转必须转红（防永真）**

临时把 `PLATFORM_PREFIX` 改成 `"/v1/nonexistent"`，重跑。

预期：**FAIL**，消息是 `一个 /v1/nonexistent 端点都没扫到——扫描逻辑本身坏了`。这条最重要：它证明「扫不到东西」不会退化成「全部通过」。改回去，确认恢复绿。

- [ ] **Step 6: 跑全量后端单测确认无副作用**

```bash
cd backend && mvn test
```

预期：全部 PASS（含新增的 1 个测试类 / 1 条测试）。

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/java/com/qingyu/care/controller/PlatformEndpointGuardTest.java
git commit -m "test(guard): 平台端点必须显式要求平台管理员（扫描哨兵）"
```

---

## Task 2: `ToolPackage` 实体 + 枚举 + repository

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/enums/PackageStatus.java`
- Create: `backend/src/main/java/com/qingyu/care/entity/ToolPackage.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/ToolPackageRepository.java`
- Test: `backend/src/test/java/com/qingyu/care/it/PackageEntityIT.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum PackageStatus { DRAFT, PUBLISHED, ARCHIVED }`
  - `ToolPackage`：`Long getId()` / `String getCode()` / `String getName()` / `String getDescription()` / `PackageStatus getStatus()` / `String getManifest()` / `LocalDateTime getCreatedAt()` / `LocalDateTime getUpdatedAt()`，Lombok `@Data` 全套 setter，`ToolPackage.builder()`
  - `ToolPackageRepository extends JpaRepository<ToolPackage, Long>`：`Optional<ToolPackage> findByCode(String)`、`List<ToolPackage> findByStatusOrderByNameAsc(PackageStatus)`、`List<ToolPackage> findAllByOrderByCreatedAtDesc()`、`boolean existsByCode(String)`

- [ ] **Step 1: 写失败的 IT（钉住 manifest 是 text 而非 varchar）**

创建 `backend/src/test/java/com/qingyu/care/it/PackageEntityIT.java`：

```java
package com.qingyu.care.it;

import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tool_package.manifest} 必须是 {@code text}，不能设列宽。
 *
 * <p>长度由包里有多少工具、skill 的 markdown 有多长决定，没有可预估的上限。
 * {@code McpTool.description} 就是设成 {@code varchar(1000)} 之后在真机接入 context7 时
 * 直接抛 {@code value too long for type character varying(1000)}，整个 server 接不进来
 * （CLAUDE.md「MCP 接入」第 10 条）。本 IT 与 {@code McpToolLongDescriptionIT} 同源，
 * 同一个坑不换位置再踩一次。
 *
 * <p>⚠️ 本 IT 只守新建库。存量库另需迁移 SQL——{@code ddl-auto: update} 只加列、
 * 不改已有列的类型，改了注解也不会动线上那一列，症状与改之前一模一样。
 *
 * @author ruan
 */
class PackageEntityIT extends AbstractPostgresIT {

    @Autowired
    private ToolPackageRepository packageRepository;

    @Test
    void 超长manifest能落库() {
        // 2000 字符，稳超任何 varchar(1000)/varchar(255)；真实包的 manifest 轻易过万
        String longManifest = "{\"manifestVersion\":1,\"note\":\"" + "x".repeat(2000) + "\"}";

        ToolPackage saved = packageRepository.saveAndFlush(ToolPackage.builder()
                .code("long_manifest_pkg")
                .name("超长 manifest 包")
                .status(PackageStatus.DRAFT)
                .manifest(longManifest)
                .build());

        ToolPackage reloaded = packageRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getManifest()).isEqualTo(longManifest);
    }

    @Test
    void 按code查唯一且能按状态过滤() {
        packageRepository.saveAndFlush(ToolPackage.builder()
                .code("published_pkg").name("已发布包")
                .status(PackageStatus.PUBLISHED).manifest("{}").build());
        packageRepository.saveAndFlush(ToolPackage.builder()
                .code("draft_pkg").name("草稿包")
                .status(PackageStatus.DRAFT).manifest("{}").build());

        assertThat(packageRepository.findByCode("published_pkg")).isPresent();
        assertThat(packageRepository.existsByCode("draft_pkg")).isTrue();
        assertThat(packageRepository.findByStatusOrderByNameAsc(PackageStatus.PUBLISHED))
                .extracting(ToolPackage::getCode)
                .contains("published_pkg")
                .doesNotContain("draft_pkg");
    }
}
```

- [ ] **Step 2: 跑它确认编译失败**

```bash
cd backend && mvn verify -Dit.test=PackageEntityIT
```

预期：**编译错误**，`ToolPackage`/`PackageStatus`/`ToolPackageRepository` 都不存在。

⚠️ `mvn verify` 需要 Docker Desktop 在跑；**跑前先停掉本机 `:8080` 的 dev 后端**（CLAUDE.md 测试约定那条，`WebhookDeliveryIT` 会因共用 Redis 消费组而必红）。

- [ ] **Step 3: 写枚举**

创建 `backend/src/main/java/com/qingyu/care/enums/PackageStatus.java`：

```java
package com.qingyu.care.enums;

/**
 * 能力包的上架状态。
 *
 * <p>⚠️ 租户侧列表<b>只返回 {@link #PUBLISHED}</b>：{@link #DRAFT} 是「导出后还没补完文案与
 * 变量说明」的中间态，让租户看见等于让人装一个变量连标签都没有的包。
 *
 * @author ruan
 */
public enum PackageStatus {
    /** 草稿：刚导出，平台管理员补文案中，租户不可见 */
    DRAFT,
    /** 已发布：租户可见可装 */
    PUBLISHED,
    /** 已下架：租户不可见；已装出来的副本<b>不受影响</b>（安装是一次性的，装完与包断开） */
    ARCHIVED
}
```

- [ ] **Step 4: 写实体**

创建 `backend/src/main/java/com/qingyu/care/entity/ToolPackage.java`：

```java
package com.qingyu.care.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.qingyu.care.enums.PackageStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 能力包：平台管理员把一组调通的工具 + skill 导出成的可安装单元。
 *
 * <p>⚠️ <b>刻意不继承 {@link BaseTenantEntity}</b>（spec §2.4）：它没有租户维度——平台建、
 * 所有租户可读。继承了反而要为「租户读平台行」再写一遍绕过 {@code @Filter} 的 native 查询
 * （「多级配置回退」那套的成本）。写入侧的隔离靠<b>端点分离</b>：平台端点
 * {@code /v1/platform/packages/**} 要 PLATFORM_ADMIN（由 {@code PlatformEndpointGuardTest}
 * 强制），租户端点只读 + 安装。沿用 {@code SysAuditLog}/{@code WebhookDelivery}/
 * {@code SysApiUsage} 那批显式不继承、自己带字段的先例。
 *
 * <p>⚠️ <b>刻意没有 {@code version} 字段</b>（spec §3.1）：安装是一次性的、装完与包断开，
 * 没有任何逻辑会消费版本号，而一个纯展示的版本号会诱使人以为存在版本管理——「我装的是哪
 * 一版」恰恰是查不出来的，因为不记安装记录（spec §2.3）。
 *
 * @author ruan
 */
@Entity
@Table(name = "tool_package",
        uniqueConstraints = @UniqueConstraint(name = "uk_tool_package_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
// 无参构造显式标 @JsonCreator：Jackson 3 会把 @AllArgsConstructor 当隐式创建器，
// 请求体缺省的属性会以 null 传入并覆盖 @Builder.Default 初值（同 AiTool）。
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class ToolPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 唯一标识，租户安装端点用它寻址（{@code POST /v1/packages/{code}/install}）。
     *
     * <p>全局唯一（不分租户），故 {@code findByCode} 返回 {@code Optional} 是安全的。
     * ⚠️ 别照搬 {@code McpServerRepository} 那条「不要加回 findByCode」的警告——那条针对的是
     * {@code McpServer.code}，它只在同一 appId 范围内唯一，全局按 code 查天生返回 0~N 行。
     */
    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * 上架状态。⚠️ 只能经专用端点 {@code publish}/{@code archive} 修改，不走通用 update
     * （同 {@code AbExperiment.status} 由 start/end 驱动的既有取舍）。
     *
     * <p>{@code @ColumnDefault} 对这张<b>新表</b>本不是必需的（建表时没有存量行要回填），
     * 保留是因为下一个往本表加 NOT NULL 列的人会照着现有列写：非空表加不带 default 的
     * NOT NULL 列时，单条 DDL 会被 PostgreSQL 拒绝，而 SchemaUpdate 对单条 DDL 失败只打
     * WARN 不阻断启动——列压根没建出来、应用照常起、运行时查询才炸（见 {@code AiTool.scope}
     * 注释记录的真实事故）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'DRAFT'")
    @Builder.Default
    private PackageStatus status = PackageStatus.DRAFT;

    /**
     * manifest 的 JSON 序列化结果。
     *
     * <p>⚠️ <b>必须是 {@code text}，不能设列宽</b>：长度由包里有多少工具、skill 的 markdown
     * 有多长决定，没有可预估的上限。{@code McpTool.description} 就是设成 {@code varchar(1000)}
     * 之后在真机接入 context7 时整个 server 接不进来（CLAUDE.md「MCP 接入」第 10 条）。
     * 由 {@code PackageEntityIT} 守护；⚠️ 存量库另需迁移 SQL——{@code ddl-auto: update}
     * 只加列、不改已有列的类型。
     */
    @Column(columnDefinition = "text", nullable = false)
    private String manifest;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 5: 写 repository**

创建 `backend/src/main/java/com/qingyu/care/repository/ToolPackageRepository.java`：

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 能力包仓库。
 *
 * <p>⚠️ 这里<b>不需要</b> native 查询绕 {@code @Filter}：{@link ToolPackage} 不继承
 * {@code BaseTenantEntity}，压根没有过滤器作用在它身上，普通 JPA 查询对平台与租户是同一份
 * 结果。这正是 spec §2.4 选择不继承的收益——否则「租户读平台行」又要写一遍
 * 「多级配置回退」那套 native 查询。
 *
 * <p>⚠️ {@link #findByCode} 返回 {@code Optional} 是安全的：{@code code} 上有唯一约束
 * {@code uk_tool_package_code} 且不分租户，全局唯一。别照搬 {@code McpServerRepository}
 * 那条「不要加回 findByCode」的警告，那条针对的是只在同 appId 内唯一的 {@code McpServer.code}。
 *
 * @author ruan
 */
@Repository
public interface ToolPackageRepository extends JpaRepository<ToolPackage, Long> {

    Optional<ToolPackage> findByCode(String code);

    /** 租户侧列表：只给已发布的（DRAFT 文案没补完、ARCHIVED 已下架）。 */
    List<ToolPackage> findByStatusOrderByNameAsc(PackageStatus status);

    /** 平台侧列表：三种状态全给，最近导出的排前面。 */
    List<ToolPackage> findAllByOrderByCreatedAtDesc();

    boolean existsByCode(String code);
}
```

- [ ] **Step 6: 跑 IT 确认通过**

```bash
cd backend && mvn verify -Dit.test=PackageEntityIT
```

预期：**2 条 PASS**。

- [ ] **Step 7: 变异验证 —— manifest 设了列宽必须转红**

临时把实体上的 `@Column(columnDefinition = "text", nullable = false)` 改成 `@Column(length = 1000, nullable = false)`，**删掉 Testcontainers 的复用容器让表重建**（最省事的做法是改一下表名再改回来，或直接 `docker rm` 掉那个测试容器），重跑：

预期：**FAIL**，`value too long for type character varying(1000)`。

⚠️ 若改了注解重跑仍绿，说明表已经建好了、`ddl-auto: update` 不改已有列类型——**这恰恰就是那条警告本身**（存量库需要迁移 SQL）。这种情况下把容器删掉重来，别当成「测试没守住」。改回 `text`，确认恢复绿。

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/enums/PackageStatus.java \
        backend/src/main/java/com/qingyu/care/entity/ToolPackage.java \
        backend/src/main/java/com/qingyu/care/repository/ToolPackageRepository.java \
        backend/src/test/java/com/qingyu/care/it/PackageEntityIT.java
git commit -m "feat(pkg): 能力包实体与仓库（manifest 存 text）"
```

---

## Task 3: manifest 类型化表示 + 字段覆盖哨兵

**这个任务是本特性唯一的结构性弱点所在**（spec §6.8）：导出时的字段抄写会随实体演进漂移——实体加了字段、导出没跟上，就会静默少装一个字段。哨兵把「静默遗漏」变成「刻意排除」。

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/pkg/PackageManifest.java`
- Test: `backend/src/test/java/com/qingyu/care/dto/pkg/PackageManifestFieldCoverageTest.java`

**Interfaces:**
- Consumes: Task 2 的 `PackageStatus`（不直接用，仅同包）
- Produces（后续所有任务都依赖这些确切的组件名）：
  - `PackageManifest(Integer manifestVersion, List<ToolSpec> tools, List<McpServerSpec> mcpServers, List<SkillSpec> skills, List<VariableSpec> variables)`
  - `PackageManifest.ToolSpec(String key, String name, String description, String inputSchema, String httpMethod, String urlTemplate, Integer timeoutMs, Integer maxResponseChars, String authHeaderVar)`
  - `PackageManifest.McpServerSpec(String key, String name, String code, String description, McpTransport transport, String url, Integer timeoutMs, Integer maxResponseChars, String authHeaderVar)`
  - `PackageManifest.SkillSpec(String key, String name, String description, String content, List<String> toolKeys, List<McpRef> mcpRefs)`
  - `PackageManifest.McpRef(String serverKey, List<String> toolNames)`
  - `PackageManifest.VariableSpec(String name, String label, boolean required, boolean secret, String help)`
  - 静态常量 `PackageManifest.CURRENT_VERSION = 1`
  - 静态方法 `List<T> nullSafe(List<T>)`（把 null 列表归一成空列表，供后续任务复用）

- [ ] **Step 1: 写 manifest record**

创建 `backend/src/main/java/com/qingyu/care/dto/pkg/PackageManifest.java`：

```java
package com.qingyu.care.dto.pkg;

import com.qingyu.care.enums.McpTransport;

import java.util.List;

/**
 * 能力包 manifest 的类型化表示（spec §3.2）。序列化后存进 {@code tool_package.manifest}。
 *
 * <p><b>引用一律用包内 {@code key}，绝不用 id</b>（spec §3.3）：{@code AiSkill} 绑的是
 * {@code toolIds}（{@code Set<Long>}），而安装时工具是新建行、id 全新。安装期间维护一张
 * {@code key → 新 id} 映射表，建完工具再回填 skill。
 * ⚠️ 漏掉这层映射的后果是<b>完全静默</b>：{@code ChatToolService.resolve()} 的 filter-bound
 * 查询查不到那个 id 就自然跳过，不报错、不打日志，界面上 skill 仍显示绑着 N 个工具，实际
 * 挂给 LLM 的少了几个——与 CLAUDE.md「MCP 接入」第 7 条同一个失效模式。
 *
 * <p><b>这里没有 {@code scope} / {@code tenantId} / {@code appId}</b>（spec §3.4 / §6.3）：
 * 安装时一律由服务端强制 {@code OwnerScope.TENANT} + 当前 {@code TenantContext}。
 * {@code scope} 是客户端可控字段这条已经真实栽过一次——租户 POST {@code /v1/ai-tools} 时在
 * 请求体里自封 {@code "scope":"PLATFORM"}，落库后带着自己的 {@code authHeader} 出现在所有
 * 租户的共享列表里（CLAUDE.md「平台共享工具」第 1 条）。manifest 是另一条能把值送进实体的
 * 路径，<b>字段压根不存在比判空更可靠</b>。
 *
 * <p><b>凭据不进 manifest</b>：{@code authHeader} 在导出时升格成一个 {@code secret} 变量，
 * 字段位置只留变量名（{@code authHeaderVar}）。
 *
 * @author ruan
 */
public record PackageManifest(
        Integer manifestVersion,
        List<ToolSpec> tools,
        List<McpServerSpec> mcpServers,
        List<SkillSpec> skills,
        List<VariableSpec> variables) {

    /**
     * 当前 manifest 版本。
     *
     * <p>⚠️ 它与「包没有版本号」（spec §3.1）不是一回事：这是 <b>manifest 结构</b>的版本，
     * 由解析器强制校验、拒绝未知版本；那是<b>包内容</b>的版本，刻意不做。
     */
    public static final int CURRENT_VERSION = 1;

    /**
     * 一个 HTTP 工具。字段与 {@code AiTool} 一一对应，缺的那些由
     * {@code PackageManifestFieldCoverageTest} 的白名单逐条说明为什么不在这里。
     *
     * @param key           包内符号，仅用于 skill 引用，<b>不落库</b>
     * @param authHeaderVar 引用 {@link VariableSpec#name()}，可空（工具不需要鉴权时）。
     *                      ⚠️ 变量值就是 {@code authHeader} 的<b>完整值</b>（含
     *                      {@code "Authorization: "} 这样的头名前缀），导出时生成的
     *                      {@code help} 会把原头名带出来告诉租户要填整行
     */
    public record ToolSpec(
            String key,
            String name,
            String description,
            String inputSchema,
            String httpMethod,
            String urlTemplate,
            Integer timeoutMs,
            Integer maxResponseChars,
            String authHeaderVar) {
    }

    /**
     * 一个远端 MCP server。
     *
     * <p>⚠️ 安装那一刻 {@code mcp_tool} 表里<b>一行都还没有</b>（工具清单要连远端同步才有），
     * 所以 skill 对 MCP 工具的引用只能按<b>工具名</b>走 {@link McpRef}，不能按 id。
     */
    public record McpServerSpec(
            String key,
            String name,
            String code,
            String description,
            McpTransport transport,
            String url,
            Integer timeoutMs,
            Integer maxResponseChars,
            String authHeaderVar) {
    }

    /**
     * 一个 skill。
     *
     * @param toolKeys 引用 {@link ToolSpec#key()}
     * @param mcpRefs  引用 {@link McpServerSpec#key()} + 远端工具名
     */
    public record SkillSpec(
            String key,
            String name,
            String description,
            String content,
            List<String> toolKeys,
            List<McpRef> mcpRefs) {
    }

    /** skill 对某个 MCP server 上若干工具的引用（按工具名，见 {@link McpServerSpec} 的说明）。 */
    public record McpRef(String serverKey, List<String> toolNames) {
    }

    /**
     * 一个安装时要租户填的变量。
     *
     * @param name     变量名，格式 {@code [A-Z][A-Z0-9_]*}，在 {@code ${NAME}} 与
     *                 {@code authHeaderVar} 两处被引用
     * @param label    表单标签，导出时自动生成一个能用的，平台管理员发布前应改成人话
     * @param required 必填。缺失时安装直接拒绝；非必填缺失时其引用位置替换成空串
     * @param secret   前端用 password 输入框，且不回显
     * @param help     填写说明。对升格来的凭据变量，这里会带出原来的请求头名
     */
    public record VariableSpec(
            String name,
            String label,
            boolean required,
            boolean secret,
            String help) {
    }

    /**
     * 把可能为 null 的列表归一成空列表。
     *
     * <p>manifest 是 JSON 来的，任何一个数组字段都可能缺省；调用方遍历前一律过这一道，
     * 好过在五个地方各写一次判空（漏掉一处就是一个 NPE，而它只在「包里恰好没有 MCP server」
     * 这类偏门组合下才现形）。
     */
    public static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
```

- [ ] **Step 2: 写字段覆盖哨兵**

创建 `backend/src/test/java/com/qingyu/care/dto/pkg/PackageManifestFieldCoverageTest.java`：

```java
package com.qingyu.care.dto.pkg;

import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描式守护「实体可变字段必须要么进 manifest、要么被显式声明为刻意排除」。
 *
 * <p><b>这是本特性方案唯一的结构性弱点</b>（spec §6.8 / §7）：导出时的字段抄写是手写的，
 * 会随实体演进漂移。实体加了字段、{@code PackageManifestBuilder} 没跟上，后果是<b>静默少装
 * 一个字段</b>——装出来的工具比平台调通的那个少一项配置，而包照常安装、界面照常显示、
 * 没有任何报错。
 *
 * <p>范式复刻 {@code UpdateRequestFieldCoverageTest}：目标不是「不许漏」，而是把<b>静默遗漏</b>
 * 变成<b>刻意排除</b>——差集里的每个字段都必须挑一个 {@link ExclusionCategory} 并写明说明。
 *
 * <p>⚠️ <b>为什么不复用 {@code UpdateRequestFieldCoverageTest.ExclusionCategory} 而是自己定义
 * 一份</b>（spec §7 说「连同它的 ExclusionCategory 枚举」，指的是那套<b>模式</b>而非同一个
 * Java 枚举）：那个类有一条自检 {@code 每个类别都必须至少有一条白名单条目在用()}，它扫的是
 * <b>它自己那张白名单</b>。两个哨兵共用一个枚举时，本类为 manifest 场景新增的类别（凭据升格、
 * 符号引用……）在那张白名单里一条都没有，那条自检会立刻转红——而且是<b>与它自己毫不相干的
 * 原因</b>。两类哨兵排除的是不同种类的字段，类别集合本来就该各自独立。
 *
 * <p>⚠️ <b>本类只守「实体 → manifest」这一跳。</b>「manifest → 安装时真的 setX」那一跳没有
 * 哨兵，由 {@code PackageInstallServiceTest} 的逐字段断言与 {@code PackageInstallIT} 覆盖
 * ——同 {@code UpdateRequestFieldCoverageTest} 与 {@code UpdateRequestPropagationTest} 的
 * 分工，两者缺一不可：manifest 里有这个字段、安装时漏了那行 setter，本类<b>全绿</b>而症状
 * 完全静默。
 *
 * @author ruan
 */
class PackageManifestFieldCoverageTest {

    /** 全局排除：主键与时间戳，任何 manifest 都不该带。 */
    private static final Set<String> GLOBAL_EXCLUDED_FIELDS =
            Set.of("id", "createdAt", "updatedAt", "tenantId", "appId");

    /** 实体类 → manifest 里对应的 record。 */
    private static final Map<Class<?>, Class<?>> ENTITY_TO_SPEC = Map.of(
            AiTool.class, PackageManifest.ToolSpec.class,
            AiSkill.class, PackageManifest.SkillSpec.class,
            McpServer.class, PackageManifest.McpServerSpec.class);

    /**
     * 刻意排除的类别。每条白名单条目必须挑一个。
     *
     * <p>类别把「随手编一句话」变成「挑一个评审能对着<b>字段性质</b>核验的分类」——分类选错，
     * 对着字段看一眼就能发现。
     */
    enum ExclusionCategory {
        /**
         * 凭据字段，导出时升格成 {@code secret} 变量，明文绝不进 manifest。
         * 判据：字段是密钥/鉴权头类，且 manifest 的对应 record 里有一个 {@code *Var} 字段接它。
         */
        CREDENTIAL_PROMOTED_TO_VARIABLE,
        /**
         * id 集合，manifest 里改用包内 key/名字表示（安装时目标行的 id 全新，抄 id 必然悬空）。
         * 判据：字段类型是 {@code Set<Long>} 之类的外键集合，且 record 里有对应的符号引用字段。
         */
        SYMBOLIC_REFERENCE,
        /**
         * 安装时由服务端强制取值，manifest 说了不算。
         * 判据：能指出安装代码里那行无条件的 {@code setX(...)}。
         */
        INSTALL_FORCED,
        /**
         * 运行中由系统写入的状态/时间戳，装出来是全新一行、天然为空。
         * 判据：能指出写它的那行代码。
         */
        SYSTEM_MANAGED
    }

    /** 白名单条目：{@code category} 供评审对着字段性质核验，{@code note} 承载具体信息。 */
    record Exclusion(ExclusionCategory category, String note) {
    }

    private static Exclusion ex(ExclusionCategory category, String note) {
        return new Exclusion(category, note);
    }

    /** 白名单：实体简单类名 →（字段名 → 排除的类别与说明）。 */
    private static final Map<String, Map<String, Exclusion>> EXCLUSION_WHITELIST = buildWhitelist();

    private static Map<String, Map<String, Exclusion>> buildWhitelist() {
        Map<String, Map<String, Exclusion>> whitelist = new LinkedHashMap<>();

        Map<String, Exclusion> aiTool = new LinkedHashMap<>();
        aiTool.put("authHeader", ex(ExclusionCategory.CREDENTIAL_PROMOTED_TO_VARIABLE,
                "明文凭据。导出时升格成 secret 变量，manifest 里只留 ToolSpec.authHeaderVar 指向它；"
                        + "导出明文等于把平台管理员的密钥随包发给每一个租户"));
        aiTool.put("enabled", ex(ExclusionCategory.INSTALL_FORCED,
                "安装产物一律 enabled=true（PackageInstallService.buildTool 里那行 setEnabled(true)）。"
                        + "「平台管理员当时把它停用了」是平台侧的临时状态，不该随包传播给租户"));
        aiTool.put("scope", ex(ExclusionCategory.INSTALL_FORCED,
                "安装时强制 OwnerScope.TENANT。manifest 里有这个字段就是把「租户自封 PLATFORM」"
                        + "那条真实事故（CLAUDE.md 平台共享工具第 1 条）换条路径复活"));
        whitelist.put("AiTool", aiTool);

        Map<String, Exclusion> aiSkill = new LinkedHashMap<>();
        aiSkill.put("toolIds", ex(ExclusionCategory.SYMBOLIC_REFERENCE,
                "指向 ai_tool 的 id 集合。安装时工具是新建行、id 全新，manifest 改用 "
                        + "SkillSpec.toolKeys 走包内符号，安装期间按 key→新 id 映射回填"));
        aiSkill.put("mcpToolIds", ex(ExclusionCategory.SYMBOLIC_REFERENCE,
                "指向 mcp_tool 的 id 集合。安装那一刻 mcp_tool 表里一行都没有（要先同步），"
                        + "manifest 改用 SkillSpec.mcpRefs 走「server key + 远端工具名」"));
        aiSkill.put("enabled", ex(ExclusionCategory.INSTALL_FORCED,
                "安装产物一律 enabled=true，同 AiTool.enabled"));
        whitelist.put("AiSkill", aiSkill);

        Map<String, Exclusion> mcpServer = new LinkedHashMap<>();
        mcpServer.put("authHeader", ex(ExclusionCategory.CREDENTIAL_PROMOTED_TO_VARIABLE,
                "明文凭据，同 AiTool.authHeader，升格成变量后由 McpServerSpec.authHeaderVar 指向"));
        mcpServer.put("enabled", ex(ExclusionCategory.INSTALL_FORCED,
                "安装产物一律 enabled=true，同 AiTool.enabled"));
        mcpServer.put("scope", ex(ExclusionCategory.INSTALL_FORCED,
                "安装时强制 OwnerScope.TENANT，同 AiTool.scope"));
        mcpServer.put("lastSyncedAt", ex(ExclusionCategory.SYSTEM_MANAGED,
                "同步产生的时间戳，由 McpServerService.sync 成功时写入。装出来是全新一行、"
                        + "还没同步过，抄平台那边的时间戳会让「从没同步过」看起来像「刚同步过」"));
        mcpServer.put("lastSyncError", ex(ExclusionCategory.SYSTEM_MANAGED,
                "同步产生的失败原因，由 McpServerService.sync 写入/清空，同 lastSyncedAt"));
        whitelist.put("McpServer", mcpServer);

        return whitelist;
    }

    @Test
    @DisplayName("实体可变字段与 manifest record 的差集必须全部在白名单中声明类别与理由")
    void 实体字段与manifest的差集必须全部在白名单中声明() {
        // 映射表空掉时下面的循环会空转、断言退化成「空==空」永真——先钉住它非空。
        assertThat(ENTITY_TO_SPEC)
                .as("ENTITY_TO_SPEC 是空的，下面的差集断言会全部空转")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Map.Entry<Class<?>, Class<?>> entry : ENTITY_TO_SPEC.entrySet()) {
            Class<?> entityClass = entry.getKey();
            Class<?> specClass = entry.getValue();

            Set<String> specFields = fieldNames(specClass);
            Set<String> entityFields = fieldNames(entityClass);
            Map<String, Exclusion> whitelist =
                    EXCLUSION_WHITELIST.getOrDefault(entityClass.getSimpleName(), Map.of());

            // 实体字段一条都没读到 = 反射方式坏了，同样会让断言空转
            assertThat(entityFields)
                    .as("%s 上一个字段都没扫到——反射逻辑坏了", entityClass.getSimpleName())
                    .isNotEmpty();

            for (String entityField : entityFields) {
                if (GLOBAL_EXCLUDED_FIELDS.contains(entityField)
                        || specFields.contains(entityField)
                        || whitelist.containsKey(entityField)) {
                    continue;
                }
                violations.add(entityClass.getSimpleName() + "." + entityField + " 既没进 "
                        + specClass.getSimpleName() + "，也没在 EXCLUSION_WHITELIST 里被声明——"
                        + "这是一次静默遗漏：包照常安装、界面照常显示，只是装出来的行少了这项配置。"
                        + "要么加进 manifest（并同步改 PackageManifestBuilder 与 PackageInstallService），"
                        + "要么加进白名单并挑一个 ExclusionCategory");
            }
        }

        assertThat(violations).as("manifest 的字段覆盖有缺口").isEmpty();
    }

    /** 说明的最小长度：挡不住「随手编一句话」（那半由类别挡），但挡得住留空/占位符。 */
    private static final int MIN_NOTE_LENGTH = 8;

    @Test
    @DisplayName("白名单条目必须仍对得上实体与 manifest 的真实差集，且类别与说明齐备")
    void 白名单条目不得腐烂() {
        Map<String, Class<?>> entityBySimpleName = new LinkedHashMap<>();
        ENTITY_TO_SPEC.keySet().forEach(c -> entityBySimpleName.put(c.getSimpleName(), c));

        List<String> invalidEntries = new ArrayList<>();
        for (Map.Entry<String, Map<String, Exclusion>> entityEntry : EXCLUSION_WHITELIST.entrySet()) {
            String entitySimpleName = entityEntry.getKey();
            Class<?> entityClass = entityBySimpleName.get(entitySimpleName);
            if (entityClass == null) {
                invalidEntries.add("白名单里的 " + entitySimpleName
                        + " 已不在 ENTITY_TO_SPEC 中，该整条应删除");
                continue;
            }
            Set<String> entityFields = fieldNames(entityClass);
            Set<String> specFields = fieldNames(ENTITY_TO_SPEC.get(entityClass));

            for (Map.Entry<String, Exclusion> fieldEntry : entityEntry.getValue().entrySet()) {
                String field = fieldEntry.getKey();
                Exclusion exclusion = fieldEntry.getValue();

                if (exclusion.category() == null) {
                    invalidEntries.add(entitySimpleName + "." + field
                            + " 没有声明 ExclusionCategory——类别是让评审对着字段性质核验的那一半");
                }
                String note = exclusion.note();
                if (note == null || note.isBlank() || note.trim().length() < MIN_NOTE_LENGTH) {
                    invalidEntries.add(entitySimpleName + "." + field + " 的说明为空或过短（少于 "
                            + MIN_NOTE_LENGTH + " 字）——类别表达不了「是哪行代码、哪次事故」");
                }
                if (!entityFields.contains(field)) {
                    invalidEntries.add(entitySimpleName + "." + field
                            + " 在实体上已不存在，该条白名单已过时，应删除");
                } else if (specFields.contains(field)) {
                    invalidEntries.add(entitySimpleName + "." + field
                            + " 已经进了 manifest，该条白名单已过时，应删除");
                }
            }
        }

        assertThat(invalidEntries)
                .as("白名单条目已名不副实，留着会变成没人敢删的死名单，或掩盖一个真正的遗漏")
                .isEmpty();
    }

    @Test
    @DisplayName("每个 ExclusionCategory 取值都必须至少有一条白名单条目在用")
    void 每个类别都必须至少有一条条目在用() {
        Set<ExclusionCategory> used = EnumSet.noneOf(ExclusionCategory.class);
        for (Map<String, Exclusion> byField : EXCLUSION_WHITELIST.values()) {
            for (Exclusion exclusion : byField.values()) {
                if (exclusion.category() != null) {
                    used.add(exclusion.category());
                }
            }
        }

        assertThat(used)
                .as("白名单里一条带类别的条目都没有——此时类别机制形同虚设，下面的差集断言会空转")
                .isNotEmpty();

        Set<ExclusionCategory> unused = EnumSet.allOf(ExclusionCategory.class);
        unused.removeAll(used);

        assertThat(unused)
                .as("以下 ExclusionCategory 没有任何条目在用。没人用的取值只会扩大可挑选的面、"
                        + "给「随手挑一个」提供空间——要么删掉，要么说明它服务于哪个真实条目")
                .isEmpty();
    }

    /**
     * 取类自身及父类（到 Object 为止）的全部非 static、非合成字段名。
     *
     * <p>record 的组件在字节码里就是 private final 字段，同一套反射对 record 与实体都成立。
     */
    private static Set<String> fieldNames(Class<?> clazz) {
        Set<String> names = new LinkedHashSet<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || field.isSynthetic()) {
                    continue;
                }
                names.add(field.getName());
            }
            current = current.getSuperclass();
        }
        return names;
    }
}
```

- [ ] **Step 3: 跑它，确认三条全绿**

```bash
cd backend && mvn test -Dtest=PackageManifestFieldCoverageTest
```

预期：**3 条 PASS**。

- [ ] **Step 4: 变异验证 A —— 实体加字段必须转红**

临时给 `backend/src/main/java/com/qingyu/care/entity/AiTool.java` 加一个字段：

```java
    /** 变异实验用，验完删除 */
    @Column(length = 50)
    private String mutationProbe;
```

重跑，预期：**FAIL**，`AiTool.mutationProbe 既没进 ToolSpec，也没在 EXCLUSION_WHITELIST 里被声明`。删掉该字段，确认恢复绿。

- [ ] **Step 5: 变异验证 B —— 白名单条目过时必须转红**

临时把白名单里 `aiTool.put("authHeader", ...)` 的 key 改成 `"authHeaderXXX"`，重跑。

预期：**FAIL** 且是**两条**：`AiTool.authHeader 既没进 ToolSpec...`（差集那条）+ `AiTool.authHeaderXXX 在实体上已不存在`（腐烂那条）。改回去，确认恢复绿。

- [ ] **Step 6: 变异验证 C —— 死类别必须转红**

临时往 `ExclusionCategory` 加一个取值 `MUTATION_PROBE`，重跑。

预期：**FAIL**，`以下 ExclusionCategory 没有任何条目在用`。删掉，确认恢复绿。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/dto/pkg/PackageManifest.java \
        backend/src/test/java/com/qingyu/care/dto/pkg/PackageManifestFieldCoverageTest.java
git commit -m "feat(pkg): manifest 类型化表示 + 字段覆盖扫描哨兵"
```

---

## Task 4: `PackageManifestParser`（纯函数：JSON ↔ manifest + 结构校验）

**这个类是安装路径上唯一的门槛**。manifest 可能被平台管理员在界面上手工编辑（发布前要改变量的 label/help），所以进安装流程之前必须全面校验——**含长度校验**：不校验的后果是安装时 `save` 抛 `DataIntegrityViolationException`，被 `GlobalExceptionHandler` 接成 HTTP 500 + 完整 INSERT 语句回给客户端（CLAUDE.md「MCP 接入」第 9 条那个坑，换个位置复发）。

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/pkg/PackageManifestParser.java`
- Test: `backend/src/test/java/com/qingyu/care/service/pkg/PackageManifestParserTest.java`

**Interfaces:**
- Consumes: `PackageManifest` 及其全部子 record（Task 3）
- Produces:
  - `@Component PackageManifestParser`，构造参数 `(tools.jackson.databind.ObjectMapper objectMapper)`
  - `PackageManifest parse(String json)` —— 反序列化 + 全量结构校验，失败抛 `BusinessException`
  - `String toJson(PackageManifest manifest)` —— 序列化，失败抛 `BusinessException`
  - `static final Pattern VARIABLE_REF = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}")` —— **后续 Task 5/6 必须复用这一个常量，不得各写一份**
  - `static Set<String> referencedVariables(String text)` —— 抽出文本里被引用的变量名

- [ ] **Step 1: 写失败的单测**

创建 `backend/src/test/java/com/qingyu/care/service/pkg/PackageManifestParserTest.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PackageManifestParser} 的规则测试。纯函数、无 Mockito。
 *
 * <p>⚠️ 每条「拒绝」都必须断言<b>错误消息内容</b>而不只是「抛了 BusinessException」：这些
 * 消息是平台管理员编辑 manifest 时唯一的反馈，说不清楚就等于没有校验（同 OpenAPI 导入那条
 * 「302 必须给出可读错误」的取舍）。
 *
 * @author ruan
 */
class PackageManifestParserTest {

    private final PackageManifestParser parser = new PackageManifestParser(new ObjectMapper());

    /** 一份最小可用 manifest：一个工具 + 一个 skill 引用它 + 一个凭据变量。 */
    private static final String VALID = """
            {
              "manifestVersion": 1,
              "tools": [{
                "key": "weather_query",
                "name": "weather_query",
                "description": "查询指定城市的天气",
                "inputSchema": "{\\"type\\":\\"object\\",\\"properties\\":{\\"city\\":{\\"type\\":\\"string\\"}}}",
                "httpMethod": "GET",
                "urlTemplate": "https://api.example.com/weather?city={city}",
                "timeoutMs": 5000,
                "maxResponseChars": 4000,
                "authHeaderVar": "WEATHER_API_KEY"
              }],
              "mcpServers": [],
              "skills": [{
                "key": "weather_skill",
                "name": "天气助手",
                "description": "回答天气问题",
                "content": "# 天气助手\\n用户问天气时调用 weather_query。",
                "toolKeys": ["weather_query"],
                "mcpRefs": []
              }],
              "variables": [{
                "name": "WEATHER_API_KEY",
                "label": "和风天气 API Key",
                "required": true,
                "secret": true,
                "help": "在控制台获取"
              }]
            }
            """;

    @Test
    void 合法manifest能解析出全部结构() {
        PackageManifest manifest = parser.parse(VALID);

        assertThat(manifest.manifestVersion()).isEqualTo(1);
        assertThat(manifest.tools()).hasSize(1);
        PackageManifest.ToolSpec tool = manifest.tools().get(0);
        assertThat(tool.key()).isEqualTo("weather_query");
        assertThat(tool.httpMethod()).isEqualTo("GET");
        assertThat(tool.urlTemplate()).isEqualTo("https://api.example.com/weather?city={city}");
        assertThat(tool.authHeaderVar()).isEqualTo("WEATHER_API_KEY");
        assertThat(manifest.skills().get(0).toolKeys()).containsExactly("weather_query");
        assertThat(manifest.variables().get(0).secret()).isTrue();
    }

    @Test
    void 序列化后能原样解析回来() {
        PackageManifest original = parser.parse(VALID);
        PackageManifest roundTripped = parser.parse(parser.toJson(original));
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    @DisplayName("未知 manifestVersion 直接拒绝，不按当前版本猜着解析")
    void 未知版本被拒绝() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"manifestVersion\": 1", "\"manifestVersion\": 2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("manifest 版本");
    }

    @Test
    void 非法JSON给出可读错误而不是抛解析器原始异常() {
        assertThatThrownBy(() -> parser.parse("{ 这不是 JSON"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("manifest 不是合法的 JSON");
    }

    @Test
    void 空包被拒绝() {
        assertThatThrownBy(() -> parser.parse("""
                {"manifestVersion":1,"tools":[],"mcpServers":[],"skills":[],"variables":[]}"""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少要包含一个");
    }

    @Test
    @DisplayName("skill 引用了不存在的工具 key —— 悬空引用必须拒绝，装出来是个绑不上工具的空壳")
    void skill引用不存在的工具key被拒绝() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"toolKeys\": [\"weather_query\"]",
                "\"toolKeys\": [\"not_exists\"]")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not_exists");
    }

    @Test
    void mcpRef引用不存在的serverKey被拒绝() {
        String json = VALID.replace("\"mcpRefs\": []",
                "\"mcpRefs\": [{\"serverKey\":\"ghost\",\"toolNames\":[\"search\"]}]");
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void authHeaderVar引用不存在的变量被拒绝() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"authHeaderVar\": \"WEATHER_API_KEY\"",
                "\"authHeaderVar\": \"NOT_DECLARED\"")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("NOT_DECLARED");
    }

    @Test
    @DisplayName("urlTemplate 里的 ${VAR} 也必须在 variables 里声明过")
    void 行内变量引用未声明被拒绝() {
        String json = VALID.replace("https://api.example.com/weather?city={city}",
                "https://${UNDECLARED_HOST}/weather?city={city}");
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UNDECLARED_HOST");
    }

    @Test
    void 重复的工具key被拒绝() {
        String twoTools = VALID.replace("\"mcpServers\": []", "\"mcpServers\": []")
                .replace("}],\n  \"mcpServers\"", """
                        }, {
                          "key": "weather_query",
                          "name": "weather_query_2",
                          "description": "重复 key",
                          "httpMethod": "GET",
                          "urlTemplate": "https://api.example.com/x",
                          "timeoutMs": 5000,
                          "maxResponseChars": 4000
                        }],
                          "mcpServers\"""");
        assertThatThrownBy(() -> parser.parse(twoTools))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("httpMethod 只收 GET/POST —— 非 POST 在运行时一律降级成 GET，导进来只会语义错得无声无息")
    void 非GET_POST被拒绝() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"httpMethod\": \"GET\"",
                "\"httpMethod\": \"DELETE\"")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GET");
    }

    @Test
    @DisplayName("超出实体列宽的值必须在这里被拒绝，不能留给 save 抛 DataIntegrityViolationException")
    void 超长字段被拒绝() {
        String longName = "n".repeat(101);   // AiTool.name 列宽 100
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"name\": \"weather_query\"",
                "\"name\": \"" + longName + "\"")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("MCP 服务描述超列宽必须在这里被拒绝——它是本类注释自称覆盖、却最容易漏掉的一个")
    void MCP服务描述超长被拒绝() {
        String longDescription = "d".repeat(501);   // McpServer.description 列宽 500
        String json = """
                {
                  "manifestVersion": 1, "tools": [],
                  "mcpServers": [{
                    "key": "ctx7", "name": "Context7", "code": "ctx7",
                    "description": "%s",
                    "transport": "STREAMABLE_HTTP", "url": "https://mcp.example.com/mcp",
                    "timeoutMs": 10000, "maxResponseChars": 4000
                  }],
                  "skills": [], "variables": []
                }
                """.formatted(longDescription);
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("timeoutMs 缺失必须拒绝——安装时是直接 setter 搬运，null 会一路撞到 NOT NULL 列")
    void 缺少timeoutMs被拒绝() {
        String json = """
                {"manifestVersion":1,"tools":[{
                  "key":"t","name":"t","description":"d","httpMethod":"GET",
                  "urlTemplate":"https://example.com/x","maxResponseChars":4000}],
                 "mcpServers":[],"skills":[],"variables":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    void 非正数的maxResponseChars被拒绝() {
        String json = """
                {"manifestVersion":1,"tools":[{
                  "key":"t","name":"t","description":"d","httpMethod":"GET",
                  "urlTemplate":"https://example.com/x","timeoutMs":5000,"maxResponseChars":0}],
                 "mcpServers":[],"skills":[],"variables":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正整数");
    }

    @Test
    void 变量名格式非法被拒绝() {
        String json = VALID.replace("\"name\": \"WEATHER_API_KEY\"", "\"name\": \"weather-key\"")
                .replace("\"authHeaderVar\": \"WEATHER_API_KEY\"", "\"authHeaderVar\": \"weather-key\"");
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("变量名");
    }

    @Test
    void mcpServer的code必须是合法标识符() {
        String json = """
                {
                  "manifestVersion": 1,
                  "tools": [],
                  "mcpServers": [{
                    "key": "ctx7", "name": "Context7", "code": "Ctx-7",
                    "transport": "STREAMABLE_HTTP", "url": "https://mcp.example.com/mcp",
                    "timeoutMs": 10000, "maxResponseChars": 4000
                  }],
                  "skills": [], "variables": []
                }
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标识符");
    }

    @Test
    void 合法的MCP包能解析() {
        String json = """
                {
                  "manifestVersion": 1,
                  "tools": [],
                  "mcpServers": [{
                    "key": "ctx7", "name": "Context7", "code": "ctx7",
                    "description": "文档检索",
                    "transport": "STREAMABLE_HTTP", "url": "https://mcp.example.com/mcp",
                    "timeoutMs": 10000, "maxResponseChars": 4000,
                    "authHeaderVar": "CTX7_TOKEN"
                  }],
                  "skills": [{
                    "key": "doc_skill", "name": "文档助手", "content": "查文档时用 ctx7",
                    "toolKeys": [],
                    "mcpRefs": [{"serverKey": "ctx7", "toolNames": ["resolve-library-id"]}]
                  }],
                  "variables": [{
                    "name": "CTX7_TOKEN", "label": "Context7 令牌",
                    "required": false, "secret": true, "help": "可留空"
                  }]
                }
                """;
        PackageManifest manifest = parser.parse(json);
        assertThat(manifest.mcpServers()).hasSize(1);
        assertThat(manifest.mcpServers().get(0).transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        assertThat(manifest.skills().get(0).mcpRefs())
                .extracting(PackageManifest.McpRef::serverKey).containsExactly("ctx7");
    }

    @Test
    @DisplayName("缺省的数组字段按空列表处理，不是 NPE")
    void 缺省数组不抛NPE() {
        PackageManifest manifest = parser.parse("""
                {"manifestVersion":1,"tools":[{
                  "key":"t","name":"t","description":"d","httpMethod":"GET",
                  "urlTemplate":"https://example.com/x","timeoutMs":5000,"maxResponseChars":4000}]}
                """);
        assertThat(manifest.skills()).isEmpty();
        assertThat(manifest.mcpServers()).isEmpty();
        assertThat(manifest.variables()).isEmpty();
    }

    @Test
    void referencedVariables抽得出行内引用() {
        assertThat(PackageManifestParser.referencedVariables(
                "https://${HOST}/v1/${PATH_SEG}?city={city}"))
                .containsExactlyInAnyOrder("HOST", "PATH_SEG");
        assertThat(PackageManifestParser.referencedVariables("https://a.example.com/{city}")).isEmpty();
        assertThat(PackageManifestParser.referencedVariables(null)).isEmpty();
    }

    @Test
    @DisplayName("SSE 传输在本仓库连不上，包里出现它必须在解析期就拒绝")
    void SSE传输被拒绝() {
        String json = """
                {
                  "manifestVersion": 1, "tools": [],
                  "mcpServers": [{
                    "key": "s", "name": "S", "code": "s",
                    "transport": "SSE", "url": "https://mcp.example.com/sse",
                    "timeoutMs": 10000, "maxResponseChars": 4000
                  }],
                  "skills": [], "variables": []
                }
                """;
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SSE");
    }
}
```

- [ ] **Step 2: 跑它确认编译失败**

```bash
cd backend && mvn test -Dtest=PackageManifestParserTest
```

预期：**编译错误**，`PackageManifestParser` 不存在。

- [ ] **Step 3: 实现 parser**

创建 `backend/src/main/java/com/qingyu/care/service/pkg/PackageManifestParser.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.exception.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * manifest 的 JSON ↔ 对象转换与<b>全量结构校验</b>。纯函数，不碰数据库、不碰网络。
 *
 * <p><b>为什么校验要做到这么细。</b>manifest 有两条入口：导出时由
 * {@link PackageManifestBuilder} 生成（可信），以及平台管理员在界面上<b>手工编辑</b>后提交
 * （发布前要补变量的 label/help，spec §4.1）。后者意味着任何字段都可能被改坏，而安装是在
 * 租户名下批量建行——不在这里拦住，就会在 {@code save} 那一刻抛
 * {@code DataIntegrityViolationException}，被 {@code GlobalExceptionHandler} 接成 HTTP 500 +
 * 完整 INSERT 语句回给客户端（CLAUDE.md「MCP 接入」第 9 条那个坑，换个位置复发）。
 *
 * <p><b>长度上限直接对齐实体列宽</b>，每个常量都注明来源。⚠️ 改实体列宽时必须同步改这里，
 * 否则要么这里白拦（更严，用户被无谓挡住），要么拦不住（更松，500 复发）。
 *
 * @author ruan
 */
@Component
public class PackageManifestParser {

    /**
     * 包变量的行内引用语法。
     *
     * <p>⚠️ <b>Task 5/6 必须复用这一个常量，不得各写一份</b>——两处正则各自漂移的后果是
     * 「导出时写进去的引用，安装时认不出来」，而认不出来 = 变量没被替换 = 见下条。
     *
     * <p>⚠️ <b>它与 {@code UrlTemplateFiller.PLACEHOLDER}（{@code \{([^{}]+)}}）部分重叠</b>：
     * {@code ${VAR}} 里的 {@code {VAR}} 会被后者当成一个 LLM 参数占位符。所以变量必须在<b>安装
     * 落库前</b>被替换干净，残留的后果见 {@link PackageVariableApplier} 的类注释。
     */
    static final Pattern VARIABLE_REF = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    /** 变量名格式。与 {@link #VARIABLE_REF} 的捕获组同口径。 */
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** MCP server 的 code 参与 LLM 可见的工具名，必须是合法标识符（同 McpServerService.CODE_PATTERN）。 */
    private static final Pattern MCP_CODE = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** AiTool.name / AiSkill.name / McpServer.name 的列宽 */
    private static final int MAX_NAME = 100;
    /** AiTool.description / AiSkill.description / McpServer.description 的列宽 */
    private static final int MAX_DESCRIPTION = 500;
    /** AiTool.urlTemplate / McpServer.url 的列宽 */
    private static final int MAX_URL = 1000;
    /** McpServer.code 的列宽 */
    private static final int MAX_MCP_CODE = 50;
    /** 包内 key 不落库，给一个够用的上限只为挡住畸形输入 */
    private static final int MAX_KEY = 100;

    private final ObjectMapper objectMapper;

    public PackageManifestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 反序列化 + 全量结构校验。任何问题都抛 {@link BusinessException}，消息面向平台管理员。 */
    public PackageManifest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException("manifest 为空");
        }
        PackageManifest manifest;
        try {
            manifest = objectMapper.readValue(json, PackageManifest.class);
        } catch (Exception e) {
            // ⚠️ 不把解析器原始异常抛出去：那是给开发看的（含行列号与内部类型名），
            // 平台管理员在文本框里编辑 manifest 时看不懂也修不了
            throw new BusinessException("manifest 不是合法的 JSON：" + rootMessage(e));
        }
        validate(manifest);
        // ⚠️ 归一化后再返回，别直接 return 反序列化出来的那个对象：Jackson 对 JSON 里缺省的
        // 数组字段给的是 null，而 validate() 内部的 nullSafe 只作用于局部变量、改不了 record
        // 的组件值。不归一化的话，调用方拿到的 manifest.skills() 可能是 null。
        return new PackageManifest(
                manifest.manifestVersion(),
                PackageManifest.nullSafe(manifest.tools()),
                PackageManifest.nullSafe(manifest.mcpServers()),
                PackageManifest.nullSafe(manifest.skills()),
                PackageManifest.nullSafe(manifest.variables()));
    }

    public String toJson(PackageManifest manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new BusinessException("manifest 序列化失败：" + rootMessage(e));
        }
    }

    /** 抽出一段文本里引用到的变量名。null / 无引用都返回空集合。 */
    static Set<String> referencedVariables(String text) {
        Set<String> names = new LinkedHashSet<>();
        if (text == null) {
            return names;
        }
        Matcher m = VARIABLE_REF.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private void validate(PackageManifest manifest) {
        Integer version = manifest.manifestVersion();
        if (version == null || version != PackageManifest.CURRENT_VERSION) {
            // 拒绝而不是「按当前版本猜着解析」：未知版本大概率有本代码不认识的字段，
            // 猜着装的后果是装出来的行少了配置且没人知道
            throw new BusinessException("不支持的 manifest 版本：" + version
                    + "，当前只支持 " + PackageManifest.CURRENT_VERSION);
        }

        List<PackageManifest.ToolSpec> tools = PackageManifest.nullSafe(manifest.tools());
        List<PackageManifest.McpServerSpec> servers = PackageManifest.nullSafe(manifest.mcpServers());
        List<PackageManifest.SkillSpec> skills = PackageManifest.nullSafe(manifest.skills());
        List<PackageManifest.VariableSpec> variables = PackageManifest.nullSafe(manifest.variables());

        if (tools.isEmpty() && servers.isEmpty() && skills.isEmpty()) {
            throw new BusinessException("包里至少要包含一个工具、MCP 服务或技能");
        }

        Set<String> variableNames = validateVariables(variables);
        Set<String> toolKeys = new LinkedHashSet<>();
        Set<String> serverKeys = new LinkedHashSet<>();

        for (PackageManifest.ToolSpec tool : tools) {
            requireKey(tool.key(), "工具", toolKeys);
            requireText(tool.name(), MAX_NAME, "工具「" + tool.key() + "」的名称");
            requireText(tool.description(), MAX_DESCRIPTION, "工具「" + tool.key() + "」的描述");
            requireText(tool.urlTemplate(), MAX_URL, "工具「" + tool.key() + "」的 urlTemplate");
            if (!"GET".equals(tool.httpMethod()) && !"POST".equals(tool.httpMethod())) {
                // 运行时 RestClientHttpToolExecutor 对非 POST 一律降级成 GET，
                // 放 PUT/DELETE 进来只会让语义错得无声无息（同 OpenAPI 导入的既有约束）
                throw new BusinessException("工具「" + tool.key() + "」的 httpMethod 只能是 GET 或 POST");
            }
            requirePositive(tool.timeoutMs(), "工具「" + tool.key() + "」的 timeoutMs");
            requirePositive(tool.maxResponseChars(), "工具「" + tool.key() + "」的 maxResponseChars");
            requireDeclared(tool.authHeaderVar(), variableNames,
                    "工具「" + tool.key() + "」的 authHeaderVar");
            requireInlineVarsDeclared(tool.urlTemplate(), variableNames,
                    "工具「" + tool.key() + "」的 urlTemplate");
        }

        for (PackageManifest.McpServerSpec server : servers) {
            requireKey(server.key(), "MCP 服务", serverKeys);
            requireText(server.name(), MAX_NAME, "MCP 服务「" + server.key() + "」的名称");
            requireText(server.code(), MAX_MCP_CODE, "MCP 服务「" + server.key() + "」的标识符");
            // description 是可选列（McpServer.description 没有 nullable=false），非空时才校验长度
            requireLengthIfPresent(server.description(), MAX_DESCRIPTION,
                    "MCP 服务「" + server.key() + "」的描述");
            requirePositive(server.timeoutMs(), "MCP 服务「" + server.key() + "」的 timeoutMs");
            requirePositive(server.maxResponseChars(),
                    "MCP 服务「" + server.key() + "」的 maxResponseChars");
            if (!MCP_CODE.matcher(server.code()).matches()) {
                throw new BusinessException("MCP 服务「" + server.key()
                        + "」的标识符只能是小写字母开头的字母、数字、下划线组合");
            }
            requireText(server.url(), MAX_URL, "MCP 服务「" + server.key() + "」的地址");
            if (server.transport() == McpTransport.SSE) {
                // McpConnectionFactory.create() 遇到 SSE 直接抛 UnsupportedOperationException，
                // 装进来的 server 只会在第一次调用时炸；在解析期拒绝好过让人装完才发现
                throw new BusinessException("MCP 服务「" + server.key()
                        + "」使用 SSE 传输，本平台暂不支持，请改用 STREAMABLE_HTTP");
            }
            requireDeclared(server.authHeaderVar(), variableNames,
                    "MCP 服务「" + server.key() + "」的 authHeaderVar");
            requireInlineVarsDeclared(server.url(), variableNames,
                    "MCP 服务「" + server.key() + "」的地址");
        }

        Set<String> skillKeys = new LinkedHashSet<>();
        for (PackageManifest.SkillSpec skill : skills) {
            requireKey(skill.key(), "技能", skillKeys);
            requireText(skill.name(), MAX_NAME, "技能「" + skill.key() + "」的名称");
            requireLengthIfPresent(skill.description(), MAX_DESCRIPTION,
                    "技能「" + skill.key() + "」的描述");
            if (skill.content() == null || skill.content().isBlank()) {
                // 空正文的 skill 装出来什么都不会发生：resolve() 只收集非空 content，
                // 而它绑的工具也就跟着挂不上（工具只经 skill 激活）
                throw new BusinessException("技能「" + skill.key() + "」的正文为空");
            }
            for (String toolKey : PackageManifest.nullSafe(skill.toolKeys())) {
                if (!toolKeys.contains(toolKey)) {
                    throw new BusinessException("技能「" + skill.key() + "」引用了不存在的工具 key「"
                            + toolKey + "」");
                }
            }
            for (PackageManifest.McpRef ref : PackageManifest.nullSafe(skill.mcpRefs())) {
                if (ref.serverKey() == null || !serverKeys.contains(ref.serverKey())) {
                    throw new BusinessException("技能「" + skill.key()
                            + "」引用了不存在的 MCP 服务 key「" + ref.serverKey() + "」");
                }
                if (PackageManifest.nullSafe(ref.toolNames()).isEmpty()) {
                    throw new BusinessException("技能「" + skill.key() + "」对 MCP 服务「"
                            + ref.serverKey() + "」的引用没有列出任何工具名");
                }
            }
        }
    }

    /** 校验变量声明本身，返回全部变量名。 */
    private Set<String> validateVariables(List<PackageManifest.VariableSpec> variables) {
        Set<String> names = new LinkedHashSet<>();
        for (PackageManifest.VariableSpec variable : variables) {
            String name = variable.name();
            if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
                throw new BusinessException("变量名「" + name
                        + "」非法：只能是大写字母开头的大写字母、数字、下划线组合");
            }
            if (!names.add(name)) {
                throw new BusinessException("变量名「" + name + "」重复声明");
            }
            if (variable.label() == null || variable.label().isBlank()) {
                // label 是安装表单上唯一的提示，空着等于让租户对着变量名猜要填什么
                throw new BusinessException("变量「" + name + "」缺少表单标签（label）");
            }
        }
        return names;
    }

    private void requireKey(String key, String what, Set<String> seen) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(what + "缺少包内 key");
        }
        if (key.length() > MAX_KEY) {
            throw new BusinessException(what + "的 key「" + key + "」过长");
        }
        if (!seen.add(key)) {
            throw new BusinessException(what + "的 key「" + key + "」重复");
        }
    }

    private void requireText(String value, int max, String what) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(what + "为空");
        }
        if (value.length() > max) {
            throw new BusinessException(what + "超过 " + max + " 个字符");
        }
    }

    /** 可选字段：可以不填，填了就不能超列宽（对应实体上没有 {@code nullable = false} 的列）。 */
    private void requireLengthIfPresent(String value, int max, String what) {
        if (value != null && value.length() > max) {
            throw new BusinessException(what + "超过 " + max + " 个字符");
        }
    }

    /**
     * 对应实体上 {@code @Column(nullable = false)} 的数值列，manifest 里必须给且必须为正。
     *
     * <p>⚠️ 不能只判 null 就放过：{@code AiTool.timeoutMs} / {@code maxResponseChars} 与
     * {@code McpServer} 的同名字段都是非空列，而安装时是<b>直接 setter 搬运</b>
     * （{@code tool.setTimeoutMs(spec.timeoutMs())}）——manifest 里给了 null，实体上的
     * {@code @Builder.Default} 也救不了（那只在走 builder 时生效，setter 会把 null 原样写进去），
     * 最终在 {@code save()} 抛 {@code DataIntegrityViolationException} → HTTP 500 + 完整 INSERT
     * 语句回给客户端。正是本类存在的理由要挡住的那类事故。
     */
    private void requirePositive(Integer value, String what) {
        if (value == null) {
            throw new BusinessException(what + "未填写");
        }
        if (value <= 0) {
            throw new BusinessException(what + "必须是正整数，当前是 " + value);
        }
    }

    /** 字段级变量引用（{@code authHeaderVar}）必须指向一个已声明的变量；为 null 表示不需要鉴权。 */
    private void requireDeclared(String varName, Set<String> declared, String what) {
        if (varName == null || varName.isBlank()) {
            return;
        }
        if (!declared.contains(varName)) {
            throw new BusinessException(what + "引用了未声明的变量「" + varName + "」");
        }
    }

    /** 行内变量引用（{@code ${VAR}}）同样必须指向已声明的变量。 */
    private void requireInlineVarsDeclared(String text, Set<String> declared, String what) {
        for (String name : referencedVariables(text)) {
            if (!declared.contains(name)) {
                throw new BusinessException(what + "引用了未声明的变量「" + name + "」");
            }
        }
    }

    /** 取异常链最内层的消息，避免把一长串包装异常的 toString 丢给用户。 */
    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
cd backend && mvn test -Dtest=PackageManifestParserTest
```

预期：**全部 PASS**（18 条左右）。

⚠️ 若 `重复的工具key被拒绝` 那条因为字符串拼接没造出预期的 JSON 而报了别的错，**改测试里的 JSON 构造方式而不是放宽实现**——那条 `replace` 依赖 `VALID` 的确切排版。最稳的写法是直接写一份完整的双工具 JSON 文本字面量。

- [ ] **Step 5: 变异验证 —— 去掉悬空引用检查必须转红**

临时把 `validate` 里 `技能「...」引用了不存在的工具 key` 那个 `if` 整段注释掉，重跑。

预期：`skill引用不存在的工具key被拒绝` 转红。恢复，确认全绿。

理由：这条检查守的是 spec §3.3 那条「完全静默」的失效模式——悬空引用装下去，`resolve()` 查不到就跳过，界面上 skill 仍显示绑着 N 个工具。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/pkg/PackageManifestParser.java \
        backend/src/test/java/com/qingyu/care/service/pkg/PackageManifestParserTest.java
git commit -m "feat(pkg): manifest 解析与结构校验（纯函数）"
```

---

## Task 5: `PackageManifestBuilder`（纯函数：实体 → manifest + warnings）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/pkg/PackageManifestBuilder.java`
- Test: `backend/src/test/java/com/qingyu/care/service/pkg/PackageManifestBuilderTest.java`

**Interfaces:**
- Consumes: `PackageManifest` 及子 record（Task 3）。⚠️ **不依赖 `PackageManifestParser`**：导出侧只把凭据换成变量名，不产生也不解析 `${VAR}` 行内引用（那是平台管理员事后手工编辑 manifest 时才写进去的）
- Produces:
  - `@Component PackageManifestBuilder`（无构造参数）
  - `record BuildInput(List<AiTool> tools, List<McpServer> mcpServers, List<AiSkill> skills, Map<Long, String> toolNamesById, Map<Long, McpToolRef> mcpToolsById)`
  - `record McpToolRef(Long serverId, String toolName)`
  - `record BuildResult(PackageManifest manifest, List<String> warnings)`
  - `BuildResult build(BuildInput input)`

- [ ] **Step 1: 写失败的单测**

创建 `backend/src/test/java/com/qingyu/care/service/pkg/PackageManifestBuilderTest.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.enums.McpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PackageManifestBuilder} 的规则测试。纯函数、无 Mockito。
 *
 * @author ruan
 */
class PackageManifestBuilderTest {

    private final PackageManifestBuilder builder = new PackageManifestBuilder();

    private static AiTool tool(Long id, String name, String authHeader) {
        AiTool t = AiTool.builder()
                .name(name).description("描述 " + name)
                .inputSchema("{\"type\":\"object\"}")
                .httpMethod("GET")
                .urlTemplate("https://api.example.com/" + name + "?city={city}")
                .timeoutMs(5000).maxResponseChars(4000)
                .enabled(true)
                .build();
        t.setId(id);
        t.setAuthHeader(authHeader);
        return t;
    }

    private static AiSkill skill(Long id, String name, Set<Long> toolIds, Set<Long> mcpToolIds) {
        AiSkill s = AiSkill.builder()
                .name(name).description("技能描述").content("# " + name + "\n正文")
                .toolIds(toolIds).mcpToolIds(mcpToolIds).enabled(true)
                .build();
        s.setId(id);
        return s;
    }

    private static McpServer server(Long id, String code, String authHeader) {
        McpServer m = McpServer.builder()
                .name("服务 " + code).code(code).description("MCP 描述")
                .transport(McpTransport.STREAMABLE_HTTP)
                .url("https://mcp.example.com/" + code)
                .timeoutMs(10000).maxResponseChars(4000).enabled(true)
                .build();
        m.setId(id);
        m.setAuthHeader(authHeader);
        return m;
    }

    private static PackageManifestBuilder.BuildInput input(
            List<AiTool> tools, List<McpServer> servers, List<AiSkill> skills) {
        return new PackageManifestBuilder.BuildInput(tools, servers, skills, Map.of(), Map.of());
    }

    @Test
    void 工具字段被逐项抄进manifest() {
        PackageManifestBuilder.BuildResult result =
                builder.build(input(List.of(tool(1L, "weather_query", null)), List.of(), List.of()));

        PackageManifest.ToolSpec spec = result.manifest().tools().get(0);
        assertThat(spec.key()).isEqualTo("weather_query");
        assertThat(spec.name()).isEqualTo("weather_query");
        assertThat(spec.description()).isEqualTo("描述 weather_query");
        assertThat(spec.inputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(spec.httpMethod()).isEqualTo("GET");
        assertThat(spec.urlTemplate()).isEqualTo("https://api.example.com/weather_query?city={city}");
        assertThat(spec.timeoutMs()).isEqualTo(5000);
        assertThat(spec.maxResponseChars()).isEqualTo(4000);
        assertThat(spec.authHeaderVar()).isNull();
        assertThat(result.manifest().manifestVersion()).isEqualTo(PackageManifest.CURRENT_VERSION);
    }

    @Test
    @DisplayName("凭据升格成 secret 变量，manifest 里绝不出现明文")
    void 凭据升格成变量() {
        PackageManifestBuilder.BuildResult result = builder.build(
                input(List.of(tool(1L, "weather_query", "Authorization: Bearer sk-super-secret")),
                        List.of(), List.of()));

        PackageManifest.ToolSpec spec = result.manifest().tools().get(0);
        assertThat(spec.authHeaderVar()).isEqualTo("WEATHER_QUERY_AUTH");

        PackageManifest.VariableSpec variable = result.manifest().variables().get(0);
        assertThat(variable.name()).isEqualTo("WEATHER_QUERY_AUTH");
        assertThat(variable.secret()).isTrue();
        assertThat(variable.required()).isTrue();
        // 头名要带出来，否则租户不知道要填整行
        assertThat(variable.help()).contains("Authorization");

        // ⚠️ 核心：整份 manifest 的任何角落都不能出现明文密钥
        assertThat(result.manifest().toString()).doesNotContain("sk-super-secret");
        assertThat(variable.help()).doesNotContain("sk-super-secret");
    }

    @Test
    @DisplayName("导出全程只读实体：源工具的 authHeader 一个字节都不能被改动")
    void 导出不修改源实体() {
        AiTool source = tool(1L, "weather_query", "Authorization: Bearer sk-keep-me");

        builder.build(input(List.of(source), List.of(), List.of()));

        // OSIV 下实体是受管的，任何 setter 都会被脏检查刷回库——本意是防泄漏，
        // 结果是把平台管理员自己的凭据真的删掉（spec §4.1 / §6.2）
        assertThat(source.getAuthHeader()).isEqualTo("Authorization: Bearer sk-keep-me");
    }

    @Test
    void skill的工具绑定转成包内key引用() {
        AiTool t1 = tool(1L, "weather_query", null);
        AiTool t2 = tool(2L, "order_query", null);
        AiSkill s = skill(10L, "客服技能", Set.of(1L, 2L), Set.of());

        PackageManifest manifest = builder.build(input(List.of(t1, t2), List.of(), List.of(s))).manifest();

        assertThat(manifest.skills().get(0).toolKeys())
                .containsExactlyInAnyOrder("weather_query", "order_query");
    }

    @Test
    @DisplayName("skill 绑了不在导出集合内的工具 → 记 warning，不阻断（spec §4.1）")
    void 集合外的工具引用记warning() {
        AiTool t1 = tool(1L, "weather_query", null);
        AiSkill s = skill(10L, "客服技能", Set.of(1L, 99L), Set.of());

        PackageManifestBuilder.BuildResult result = builder.build(
                new PackageManifestBuilder.BuildInput(List.of(t1), List.of(), List.of(s),
                        Map.of(99L, "order_query"), Map.of()));

        assertThat(result.manifest().skills().get(0).toolKeys()).containsExactly("weather_query");
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("order_query").contains("不在本次导出"));
    }

    @Test
    void MCP工具绑定转成serverKey加工具名() {
        McpServer m = server(5L, "ctx7", null);
        AiSkill s = skill(10L, "文档技能", Set.of(), Set.of(100L, 101L));

        PackageManifest manifest = builder.build(new PackageManifestBuilder.BuildInput(
                List.of(), List.of(m), List.of(s), Map.of(),
                Map.of(100L, new PackageManifestBuilder.McpToolRef(5L, "resolve-library-id"),
                        101L, new PackageManifestBuilder.McpToolRef(5L, "get-docs")))).manifest();

        assertThat(manifest.skills().get(0).mcpRefs()).hasSize(1);
        PackageManifest.McpRef ref = manifest.skills().get(0).mcpRefs().get(0);
        assertThat(ref.serverKey()).isEqualTo("ctx7");
        assertThat(ref.toolNames()).containsExactlyInAnyOrder("resolve-library-id", "get-docs");
    }

    @Test
    void 指向集合外MCP服务的工具引用记warning() {
        AiSkill s = skill(10L, "文档技能", Set.of(), Set.of(100L));

        PackageManifestBuilder.BuildResult result = builder.build(new PackageManifestBuilder.BuildInput(
                List.of(), List.of(), List.of(s), Map.of(),
                Map.of(100L, new PackageManifestBuilder.McpToolRef(5L, "resolve-library-id"))));

        assertThat(result.manifest().skills().get(0).mcpRefs()).isEmpty();
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("不在本次导出"));
    }

    @Test
    @DisplayName("URL 里疑似嵌了密钥 → 记 warning（启发式，最终仍要人逐字看一遍）")
    void URL里疑似密钥记warning() {
        AiTool t = AiTool.builder()
                .name("weather_query").description("查天气").httpMethod("GET")
                .urlTemplate("https://api.example.com/weather?apikey=abcd1234&city={city}")
                .timeoutMs(5000).maxResponseChars(4000).enabled(true).build();
        t.setId(1L);

        PackageManifestBuilder.BuildResult result = builder.build(input(List.of(t), List.of(), List.of()));

        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("weather_query").contains("密钥"));
    }

    @Test
    @DisplayName("key 冲突时加序号，不能让两个工具共用一个 key（skill 引用会指错对象）")
    void key冲突时加序号() {
        AiTool t1 = tool(1L, "query", null);
        AiTool t2 = tool(2L, "query", null);

        PackageManifest manifest = builder.build(input(List.of(t1, t2), List.of(), List.of())).manifest();

        assertThat(manifest.tools()).extracting(PackageManifest.ToolSpec::key)
                .containsExactly("query", "query_2");
    }

    @Test
    @DisplayName("中文名的 skill 也要产出合法 key")
    void 中文名产出合法key() {
        AiSkill s = skill(10L, "客服技能", Set.of(), Set.of());

        PackageManifest manifest = builder.build(input(List.of(), List.of(), List.of(s))).manifest();

        assertThat(manifest.skills().get(0).key()).matches("[a-z][a-z0-9_]*");
        assertThat(manifest.skills().get(0).name()).isEqualTo("客服技能");
    }

    @Test
    void 两个凭据变量名不冲突() {
        PackageManifest manifest = builder.build(input(
                List.of(tool(1L, "query", "Authorization: Bearer a"),
                        tool(2L, "query", "X-Api-Key: b")),
                List.of(), List.of())).manifest();

        assertThat(manifest.variables()).extracting(PackageManifest.VariableSpec::name)
                .containsExactly("QUERY_AUTH", "QUERY_2_AUTH");
    }
}
```

- [ ] **Step 2: 跑它确认编译失败**

```bash
cd backend && mvn test -Dtest=PackageManifestBuilderTest
```

预期：**编译错误**，`PackageManifestBuilder` 不存在。

- [ ] **Step 3: 实现 builder**

创建 `backend/src/main/java/com/qingyu/care/service/pkg/PackageManifestBuilder.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 实体 → manifest 的转换与导出告警。<b>纯函数</b>：不碰数据库、不碰网络。
 *
 * <p>⚠️ <b>全程只读实体，绝不 setter 置空</b>（spec §4.1 / §6.2）。本项目未配
 * {@code spring.jpa.open-in-view}，即 Spring Boot 默认的 {@code true}，OSIV 让查出来的实体在
 * 整个请求期保持<b>受管</b>——为了「不导出明文凭据」而写一句 {@code tool.setAuthHeader(null)}
 * 会被脏检查刷回库，结果是<b>把平台管理员自己的凭据真的删掉</b>（{@code SharedCredentialMasker}
 * 那次就是靠 {@code em.detach} 才闭合的）。这里连 {@code detach} 都不需要：只读 getter 拼
 * manifest，从头不碰实体状态。⚠️ 直调本类的单测<b>证明不了</b>这条（实体本来就是分离的，断言
 * 会静默退化成永真），真正钉住它的是 {@code PackageExportIT} 那条走真实 MockMvc 的测试。
 *
 * <p><b>warning 一律不阻断</b>（spec §4.1）：它们是启发式，最终仍要平台管理员逐字看一遍
 * manifest 预览再发布——同噪声剔除，没法自动化。
 *
 * @author ruan
 */
@Component
public class PackageManifestBuilder {

    /** URL 里疑似嵌了密钥的启发式特征。命中只记 warning，不阻断。 */
    private static final Pattern SECRET_IN_URL =
            Pattern.compile("(?i)[?&](api_?key|key|token|secret|access_?token)=");

    /** 包内 key 的合法形状，与 MCP server 的 code 同口径。 */
    private static final Pattern KEY_SHAPE = Pattern.compile("[^a-z0-9_]");

    /** 导出输入。 */
    public record BuildInput(
            List<AiTool> tools,
            List<McpServer> mcpServers,
            List<AiSkill> skills,
            /** 全部可见工具的 id → name，仅用于把「集合外引用」的 id 翻译成人能看懂的名字 */
            Map<Long, String> toolNamesById,
            /** 全部可见 MCP 工具的 id → (所属 server id, 远端工具名)，用于把 mcpToolIds 转成 mcpRefs */
            Map<Long, McpToolRef> mcpToolsById) {
    }

    /** 一个 MCP 工具的身份：它属于哪个 server、远端叫什么名字。 */
    public record McpToolRef(Long serverId, String toolName) {
    }

    public record BuildResult(PackageManifest manifest, List<String> warnings) {
    }

    public BuildResult build(BuildInput input) {
        List<String> warnings = new ArrayList<>();
        List<PackageManifest.VariableSpec> variables = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        Set<String> usedVariableNames = new LinkedHashSet<>();

        // ===== 工具 =====
        Map<Long, String> toolKeyById = new LinkedHashMap<>();
        List<PackageManifest.ToolSpec> toolSpecs = new ArrayList<>();
        for (AiTool tool : PackageManifest.nullSafe(input.tools())) {
            String key = uniqueKey(slug(tool.getName(), "tool"), usedKeys);
            toolKeyById.put(tool.getId(), key);

            String authVar = promoteCredential(tool.getAuthHeader(), key, tool.getName(),
                    variables, usedVariableNames);

            if (tool.getUrlTemplate() != null && SECRET_IN_URL.matcher(tool.getUrlTemplate()).find()) {
                warnings.add("工具「" + tool.getName() + "」的地址里疑似直接写了密钥（?key= / ?token= 之类）"
                        + "，它会原样随包发给每个租户。请改成鉴权头，或把该段改成 ${变量名} 并在变量表里声明");
            }

            toolSpecs.add(new PackageManifest.ToolSpec(
                    key,
                    tool.getName(),
                    tool.getDescription(),
                    tool.getInputSchema(),
                    tool.getHttpMethod(),
                    tool.getUrlTemplate(),
                    tool.getTimeoutMs(),
                    tool.getMaxResponseChars(),
                    authVar));
        }

        // ===== MCP server =====
        Map<Long, String> serverKeyById = new LinkedHashMap<>();
        List<PackageManifest.McpServerSpec> serverSpecs = new ArrayList<>();
        for (McpServer server : PackageManifest.nullSafe(input.mcpServers())) {
            String key = uniqueKey(slug(server.getCode(), "mcp"), usedKeys);
            serverKeyById.put(server.getId(), key);

            String authVar = promoteCredential(server.getAuthHeader(), key, server.getName(),
                    variables, usedVariableNames);

            if (server.getUrl() != null && SECRET_IN_URL.matcher(server.getUrl()).find()) {
                warnings.add("MCP 服务「" + server.getName() + "」的地址里疑似直接写了密钥，"
                        + "它会原样随包发给每个租户");
            }

            serverSpecs.add(new PackageManifest.McpServerSpec(
                    key,
                    server.getName(),
                    server.getCode(),
                    server.getDescription(),
                    server.getTransport(),
                    server.getUrl(),
                    server.getTimeoutMs(),
                    server.getMaxResponseChars(),
                    authVar));
        }

        // ===== skill（引用转换必须在工具与 server 都编好 key 之后）=====
        List<PackageManifest.SkillSpec> skillSpecs = new ArrayList<>();
        for (AiSkill skill : PackageManifest.nullSafe(input.skills())) {
            String key = uniqueKey(slug(skill.getName(), "skill"), usedKeys);

            List<String> toolKeys = new ArrayList<>();
            for (Long toolId : nullSafeSet(skill.getToolIds())) {
                String toolKey = toolKeyById.get(toolId);
                if (toolKey == null) {
                    String name = input.toolNamesById() == null
                            ? null : input.toolNamesById().get(toolId);
                    warnings.add("技能「" + skill.getName() + "」绑定的工具「"
                            + (name != null ? name : "id=" + toolId)
                            + "」不在本次导出范围内，装出来的技能会少绑这个工具");
                    continue;
                }
                toolKeys.add(toolKey);
            }

            // 按 server 归并：一个 mcpRef 承载「某个 server 上的若干工具名」
            Map<String, List<String>> byServerKey = new LinkedHashMap<>();
            for (Long mcpToolId : nullSafeSet(skill.getMcpToolIds())) {
                McpToolRef ref = input.mcpToolsById() == null
                        ? null : input.mcpToolsById().get(mcpToolId);
                if (ref == null) {
                    warnings.add("技能「" + skill.getName() + "」绑定的 MCP 工具 id=" + mcpToolId
                            + " 已查不到（可能对应的服务已删除），装出来的技能会少绑它");
                    continue;
                }
                String serverKey = serverKeyById.get(ref.serverId());
                if (serverKey == null) {
                    warnings.add("技能「" + skill.getName() + "」绑定的 MCP 工具「" + ref.toolName()
                            + "」所属的服务不在本次导出范围内，装出来的技能会少绑它");
                    continue;
                }
                byServerKey.computeIfAbsent(serverKey, k -> new ArrayList<>()).add(ref.toolName());
            }
            List<PackageManifest.McpRef> mcpRefs = new ArrayList<>();
            byServerKey.forEach((serverKey, names) ->
                    mcpRefs.add(new PackageManifest.McpRef(serverKey, names)));

            skillSpecs.add(new PackageManifest.SkillSpec(
                    key,
                    skill.getName(),
                    skill.getDescription(),
                    skill.getContent(),
                    toolKeys,
                    mcpRefs));
        }

        PackageManifest manifest = new PackageManifest(
                PackageManifest.CURRENT_VERSION, toolSpecs, serverSpecs, skillSpecs, variables);
        return new BuildResult(manifest, warnings);
    }

    /**
     * 把一个明文 {@code authHeader} 升格成 {@code secret} 变量，返回变量名；{@code authHeader}
     * 为空时返回 null（该工具不需要鉴权）。
     *
     * <p>⚠️ <b>变量值是 authHeader 的完整值</b>（含 {@code "Authorization: "} 这样的头名前缀）
     * ——本项目 {@code AiTool.authHeader} 存的就是整行。所以 {@code help} 里必须把原来的头名
     * 带出来，否则租户只会填一个裸 token，工具静默 401（模型拿着错误响应继续编答案，
     * 全程无异常无日志）。
     *
     * <p>⚠️ <b>头名可以导出，值绝对不行</b>：头名不是密钥，且它正是租户填对格式所必需的信息。
     */
    private String promoteCredential(String authHeader, String key, String displayName,
                                     List<PackageManifest.VariableSpec> variables,
                                     Set<String> usedVariableNames) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String varName = uniqueVariableName(key.toUpperCase(Locale.ROOT) + "_AUTH", usedVariableNames);
        int colon = authHeader.indexOf(':');
        String headerName = colon > 0 ? authHeader.substring(0, colon).trim() : null;
        String help = headerName != null
                ? "请填写完整的一行请求头，格式为「" + headerName + ": <你的密钥>」"
                : "请填写完整的一行请求头，格式为「头名: 值」";

        variables.add(new PackageManifest.VariableSpec(
                varName,
                displayName + " 的鉴权头",
                true,
                true,
                help));
        return varName;
    }

    /**
     * 名字 → 包内 key：小写、非法字符换成下划线、掐掉首尾下划线；产不出合法 key 时用 fallback。
     *
     * <p>skill 名常是中文（「客服技能」），规范化后会整个变空，此时回落到 {@code skill} 前缀 +
     * 序号（序号由 {@link #uniqueKey} 补）。
     */
    private String slug(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String slug = KEY_SHAPE.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("_");
        slug = slug.replaceAll("^_+", "").replaceAll("_+$", "").replaceAll("_{2,}", "_");
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) {
            return fallback;
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    /**
     * key 去重：冲突时加 {@code _2}、{@code _3}……
     *
     * <p>⚠️ 两个对象共用一个 key 不是「不好看」而是<b>会指错对象</b>：skill 的 toolKeys 按 key
     * 查工具，重了就会绑到另一个工具上。
     */
    private String uniqueKey(String base, Set<String> used) {
        if (used.add(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + "_" + i;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private String uniqueVariableName(String base, Set<String> used) {
        if (used.add(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + "_" + i;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private Set<Long> nullSafeSet(Set<Long> set) {
        return set == null ? Set.of() : set;
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
cd backend && mvn test -Dtest=PackageManifestBuilderTest
```

预期：**全部 PASS**。

⚠️ `两个凭据变量名不冲突` 那条依赖 `uniqueKey` 先把第二个工具编成 `query_2`，于是变量名自然是 `QUERY_2_AUTH`。若实现改成先编变量名再编 key，这条会红——**那说明顺序反了，改实现不要改测试**。

- [ ] **Step 5: 变异验证 —— 导出明文凭据必须转红**

临时把 `promoteCredential` 里的 `help` 改成 `"原值：" + authHeader`（即把明文塞进 manifest），重跑。

预期：`凭据升格成变量` 转红，`doesNotContain("sk-super-secret")` 失败。恢复，确认全绿。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/pkg/PackageManifestBuilder.java \
        backend/src/test/java/com/qingyu/care/service/pkg/PackageManifestBuilderTest.java
git commit -m "feat(pkg): 实体导出为 manifest（凭据升格成变量）"
```

---

## Task 6: `PackageVariableApplier`（纯函数：变量填充 + 残留检测）

**⚠️ 本任务是整个特性最危险的一段**，理由写在类注释里：`UrlTemplateFiller` 的占位符正则 `\{([^{}]+)}` **会匹配 `${VAR}` 里的 `{VAR}`**。一个没被替换掉的变量残留在 `urlTemplate` 的 query 段，运行时会被**整段静默删除**（鉴权参数消失 → 第三方 401 → 模型拿着错误响应继续编答案，全程无异常无日志）。所以「替换后不得残留 `${`」是一条硬断言，不是可选的防御。

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/pkg/PackageVariableApplier.java`
- Test: `backend/src/test/java/com/qingyu/care/service/pkg/PackageVariableApplierTest.java`

**Interfaces:**
- Consumes: `PackageManifest`（Task 3）、`PackageManifestParser.VARIABLE_REF` / `referencedVariables`（Task 4）
- Produces:
  - `@Component PackageVariableApplier`（无构造参数）
  - `record AppliedPackage(PackageManifest manifest, Map<String, String> toolAuthHeaders, Map<String, String> serverAuthHeaders)` —— 两张 map 的 key 是包内 key，value 是该行最终要落库的 `authHeader`（无鉴权时该 key 不出现）
  - `AppliedPackage apply(PackageManifest manifest, Map<String, String> values)`

- [ ] **Step 1: 写失败的单测**

创建 `backend/src/test/java/com/qingyu/care/service/pkg/PackageVariableApplierTest.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.enums.McpTransport;
import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PackageVariableApplier} 的规则测试。纯函数、无 Mockito。
 *
 * @author ruan
 */
class PackageVariableApplierTest {

    private final PackageVariableApplier applier = new PackageVariableApplier();

    private static PackageManifest manifestWith(PackageManifest.ToolSpec tool,
                                                PackageManifest.VariableSpec... variables) {
        return new PackageManifest(PackageManifest.CURRENT_VERSION,
                List.of(tool), List.of(), List.of(), List.of(variables));
    }

    private static PackageManifest.ToolSpec tool(String urlTemplate, String authHeaderVar) {
        return new PackageManifest.ToolSpec("weather_query", "weather_query", "查天气",
                "{\"type\":\"object\"}", "GET", urlTemplate, 5000, 4000, authHeaderVar);
    }

    private static PackageManifest.VariableSpec variable(String name, boolean required) {
        return new PackageManifest.VariableSpec(name, name + " 标签", required, false, "说明");
    }

    @Test
    void 行内变量被替换成租户填的值() {
        PackageManifest manifest = manifestWith(
                tool("https://${WEATHER_HOST}/v1/weather?city={city}", null),
                variable("WEATHER_HOST", true));

        PackageVariableApplier.AppliedPackage applied =
                applier.apply(manifest, Map.of("WEATHER_HOST", "api.qweather.com"));

        assertThat(applied.manifest().tools().get(0).urlTemplate())
                .isEqualTo("https://api.qweather.com/v1/weather?city={city}");
    }

    @Test
    @DisplayName("LLM 参数占位符 {city} 必须原样留着——它不是包变量")
    void LLM占位符不被当成变量() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/weather?city={city}&day={day}", null));

        PackageVariableApplier.AppliedPackage applied = applier.apply(manifest, Map.of());

        assertThat(applied.manifest().tools().get(0).urlTemplate())
                .isEqualTo("https://api.example.com/weather?city={city}&day={day}");
    }

    @Test
    void authHeaderVar的取值进authHeaders表而不进manifest() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/weather?city={city}", "WEATHER_AUTH"),
                variable("WEATHER_AUTH", true));

        PackageVariableApplier.AppliedPackage applied = applier.apply(manifest,
                Map.of("WEATHER_AUTH", "Authorization: Bearer sk-tenant-own"));

        assertThat(applied.toolAuthHeaders()).containsEntry("weather_query",
                "Authorization: Bearer sk-tenant-own");
        // manifest 本身不带凭据——它是要展示给人看的
        assertThat(applied.manifest().tools().get(0).authHeaderVar()).isEqualTo("WEATHER_AUTH");
    }

    @Test
    @DisplayName("必填变量缺失 → 一次列全部缺的，别让人试一次填一个")
    void 必填变量缺失时一次列全() {
        PackageManifest manifest = new PackageManifest(PackageManifest.CURRENT_VERSION,
                List.of(tool("https://${HOST}/x?city={city}", "AUTH")), List.of(), List.of(),
                List.of(variable("HOST", true), variable("AUTH", true)));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HOST")
                .hasMessageContaining("AUTH");
    }

    @Test
    void 全空格的必填变量等同于缺失() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?key=${K}&city={city}", null),
                variable("K", true));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of("K", "   ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("K");
    }

    @Test
    @DisplayName("非必填变量缺失 → 替换成空串，替换后仍须通过 URL 结构校验")
    void 非必填变量缺失替换成空串() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?region=${REGION}&city={city}", null),
                variable("REGION", false));

        PackageVariableApplier.AppliedPackage applied = applier.apply(manifest, Map.of());

        assertThat(applied.manifest().tools().get(0).urlTemplate())
                .isEqualTo("https://api.example.com/x?region=&city={city}");
    }

    /**
     * ⚠️ 本条与下一条共同守着整个特性最危险的一处：残留的 ${VAR} 会被 UrlTemplateFiller 的
     * \{([^{}]+)} 当成 LLM 参数占位符——在 query 段会被<b>整段静默删除</b>。
     *
     * <p>这一条走的是「变量名合法但没声明」：{@code ${GHOST}} 能被 VARIABLE_REF 匹配到，
     * 所以它<b>不会</b>留下 {@code ${}——若实现只有「替换后查 ${」那一道兜底，它会被悄悄换成
     * 空串然后放行。必须显式拿 variables 里声明的集合去卡。
     */
    @Test
    @DisplayName("引用了没声明的变量必须拒绝，不能悄悄替换成空串")
    void 未声明的变量引用被拒绝() {
        // 正常路径下 parser 会先拦下这种 manifest，这里直接构造对象绕过 parser，
        // 钉住 applier 自己也有这道兜底
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?token=${GHOST}&city={city}", null));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GHOST");
    }

    /**
     * 这一条走的是另一半：<b>变量名写错了大小写</b>（或别的形状），VARIABLE_REF 根本匹配不到，
     * 替换整个跳过、{@code ${...}} 原样留在串里。两条走的是实现里两道不同的检查，缺一不可。
     */
    @Test
    @DisplayName("语法写错的变量引用（如小写名）会残留 ${...}，必须拒绝")
    void 语法写错的变量引用被拒绝() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?token=${lowercase}&city={city}", null));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    @DisplayName("变量取值里含大括号会造出假的 LLM 占位符，必须拒绝")
    void 变量取值不得含大括号() {
        PackageManifest manifest = manifestWith(
                tool("https://${HOST}/x?city={city}", null),
                variable("HOST", true));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of("HOST", "evil{city}host.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HOST");
    }

    @Test
    @DisplayName("只被 authHeaderVar 引用的变量不受大括号限制——它不进 URL 模板")
    void 凭据变量允许含大括号() {
        PackageManifest manifest = manifestWith(
                tool("https://api.example.com/x?city={city}", "AUTH"),
                variable("AUTH", true));

        PackageVariableApplier.AppliedPackage applied =
                applier.apply(manifest, Map.of("AUTH", "X-Token: {abc}"));

        assertThat(applied.toolAuthHeaders()).containsEntry("weather_query", "X-Token: {abc}");
    }

    @Test
    @DisplayName("替换后不是 http/https 绝对地址必须拒绝")
    void 替换后URL结构非法被拒绝() {
        PackageManifest manifest = manifestWith(
                tool("${BASE}/weather?city={city}", null),
                variable("BASE", true));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of("BASE", "ftp://evil.example.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("替换后 host 段为空同样拒绝（非必填变量恰好构成整个 host 时会出现）")
    void 替换后host为空被拒绝() {
        PackageManifest manifest = manifestWith(
                tool("https://${HOST}/weather?city={city}", null),
                variable("HOST", false));

        assertThatThrownBy(() -> applier.apply(manifest, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("地址");
    }

    @Test
    void MCP服务的url与凭据同样被处理() {
        PackageManifest manifest = new PackageManifest(PackageManifest.CURRENT_VERSION,
                List.of(), List.of(new PackageManifest.McpServerSpec("ctx7", "Context7", "ctx7",
                        "文档", McpTransport.STREAMABLE_HTTP, "https://${MCP_HOST}/mcp",
                        10000, 4000, "CTX7_TOKEN")),
                List.of(),
                List.of(variable("MCP_HOST", true), variable("CTX7_TOKEN", true)));

        PackageVariableApplier.AppliedPackage applied = applier.apply(manifest, Map.of(
                "MCP_HOST", "mcp.context7.com",
                "CTX7_TOKEN", "Authorization: Bearer ctx-key"));

        assertThat(applied.manifest().mcpServers().get(0).url()).isEqualTo("https://mcp.context7.com/mcp");
        assertThat(applied.serverAuthHeaders()).containsEntry("ctx7", "Authorization: Bearer ctx-key");
    }

    @Test
    @DisplayName("skill 正文与 inputSchema 不做任何替换——变量只允许出现在四个白名单字段")
    void 白名单外字段不做替换() {
        PackageManifest manifest = new PackageManifest(PackageManifest.CURRENT_VERSION,
                List.of(new PackageManifest.ToolSpec("t", "t", "d",
                        "{\"note\":\"${HOST}\"}", "GET", "https://api.example.com/x", 5000, 4000, null)),
                List.of(),
                List.of(new PackageManifest.SkillSpec("s", "技能", "描述",
                        "正文里写了 ${HOST} 但不该被替换", List.of("t"), List.of())),
                List.of(variable("HOST", true)));

        PackageVariableApplier.AppliedPackage applied =
                applier.apply(manifest, Map.of("HOST", "api.example.com"));

        assertThat(applied.manifest().tools().get(0).inputSchema()).contains("${HOST}");
        assertThat(applied.manifest().skills().get(0).content()).contains("${HOST}");
    }

    @Test
    void 多出来的取值被忽略而不是报错() {
        PackageManifest manifest = manifestWith(tool("https://api.example.com/x?city={city}", null));

        Map<String, String> values = new HashMap<>();
        values.put("UNUSED", "whatever");

        assertThat(applier.apply(manifest, values).manifest().tools()).hasSize(1);
    }
}
```

- [ ] **Step 2: 跑它确认编译失败**

```bash
cd backend && mvn test -Dtest=PackageVariableApplierTest
```

预期：**编译错误**。

- [ ] **Step 3: 实现 applier**

创建 `backend/src/main/java/com/qingyu/care/service/pkg/PackageVariableApplier.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 把租户填的变量取值套进 manifest。<b>纯函数</b>：不碰数据库、不碰网络。
 *
 * <p><b>变量只允许出现在四个字段</b>（spec §3.5）：{@code AiTool.authHeader}、
 * {@code AiTool.urlTemplate}、{@code McpServer.authHeader}、{@code McpServer.url}。
 * <b>不做全文模板替换</b>——那意味着租户填的值能落进 manifest 的任意位置（包括 skill 的
 * markdown、{@code inputSchema}），既难审计又给注入留了口子。
 *
 * <p>⚠️⚠️ <b>替换后必须断言不残留任何 <code>${</code></b>，这是本类存在的首要理由：
 * {@code UrlTemplateFiller.PLACEHOLDER} 是 <code>\{([^{}]+)}</code>，它<b>会匹配
 * <code>${VAR}</code> 里的 <code>{VAR}</code></b>。一个没被替换掉的变量落库之后：
 * <ul>
 *   <li>残留在 <b>query 段</b> → 运行时该段被<b>整段静默删除</b>（{@code UrlTemplateFiller}
 *       对「段内仍有占位符」的处理就是丢弃整段）。鉴权参数就此消失 → 第三方返 401 →
 *       模型拿着错误响应继续编答案，<b>全程无异常无日志</b>；
 *   <li>残留在 <b>path 段</b> → 抛「缺少必填参数 VAR」，一个 LLM 永远填不出来的参数名，
 *       排查的人会一路去查 inputSchema 而不是想到包变量。
 * </ul>
 * 同理，<b>变量取值本身不得含 <code>{</code> 或 <code>}</code></b>（仅限被行内引用的变量）
 * ——租户填一个 {@code evil{city}host.com} 就等于凭空造出一个假的 LLM 占位符。
 *
 * @author ruan
 */
@Component
public class PackageVariableApplier {

    /** 一次安装的产物：填充后的 manifest + 两张「包内 key → 最终 authHeader」表。 */
    public record AppliedPackage(
            PackageManifest manifest,
            Map<String, String> toolAuthHeaders,
            Map<String, String> serverAuthHeaders) {
    }

    public AppliedPackage apply(PackageManifest manifest, Map<String, String> values) {
        Map<String, String> given = values == null ? Map.of() : values;
        List<PackageManifest.VariableSpec> variables = PackageManifest.nullSafe(manifest.variables());

        // ① 必填校验：一次列全，别让人试一次填一个
        List<String> missing = new ArrayList<>();
        for (PackageManifest.VariableSpec variable : variables) {
            if (variable.required() && isBlank(given.get(variable.name()))) {
                missing.add(variable.name() + "（" + variable.label() + "）");
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("以下必填项没有填写：" + String.join("、", missing));
        }

        // ② 被行内引用的变量，其取值不得含大括号——会凭空造出假的 LLM 参数占位符
        Set<String> inlineReferenced = collectInlineReferenced(manifest);
        for (String name : inlineReferenced) {
            String value = given.get(name);
            if (value != null && (value.indexOf('{') >= 0 || value.indexOf('}') >= 0)) {
                throw new BusinessException("变量「" + name + "」的取值里不能包含大括号 { }："
                        + "它会被当成给 AI 填写的参数占位符，导致请求地址出错");
            }
        }

        // ③ 替换 + 结构校验
        // declared 是「manifest 里声明过的变量名」。⚠️ 它不能省：${GHOST} 这种「名字合法但没
        // 声明」的引用能被 VARIABLE_REF 匹配到，若只靠替换后查 ${ 那道兜底，它会被悄悄换成
        // 空串然后放行——鉴权参数就此凭空消失，正是本类要防的那个静默失效。
        Set<String> declared = new LinkedHashSet<>();
        variables.forEach(v -> declared.add(v.name()));

        Map<String, String> toolAuthHeaders = new LinkedHashMap<>();
        List<PackageManifest.ToolSpec> tools = new ArrayList<>();
        for (PackageManifest.ToolSpec tool : PackageManifest.nullSafe(manifest.tools())) {
            String url = substitute(tool.urlTemplate(), given, declared,
                    "工具「" + tool.name() + "」的地址");
            requireHttpUrl(url, "工具「" + tool.name() + "」的地址");
            authHeaderOf(tool.authHeaderVar(), given)
                    .ifPresent(header -> toolAuthHeaders.put(tool.key(), header));
            tools.add(new PackageManifest.ToolSpec(
                    tool.key(), tool.name(), tool.description(), tool.inputSchema(),
                    tool.httpMethod(), url, tool.timeoutMs(), tool.maxResponseChars(),
                    tool.authHeaderVar()));
        }

        Map<String, String> serverAuthHeaders = new LinkedHashMap<>();
        List<PackageManifest.McpServerSpec> servers = new ArrayList<>();
        for (PackageManifest.McpServerSpec server : PackageManifest.nullSafe(manifest.mcpServers())) {
            String url = substitute(server.url(), given, declared,
                    "MCP 服务「" + server.name() + "」的地址");
            requireHttpUrl(url, "MCP 服务「" + server.name() + "」的地址");
            authHeaderOf(server.authHeaderVar(), given)
                    .ifPresent(header -> serverAuthHeaders.put(server.key(), header));
            servers.add(new PackageManifest.McpServerSpec(
                    server.key(), server.name(), server.code(), server.description(),
                    server.transport(), url, server.timeoutMs(), server.maxResponseChars(),
                    server.authHeaderVar()));
        }

        // skills / variables 原样带过——变量不进 skill 正文（见类注释）
        PackageManifest applied = new PackageManifest(
                manifest.manifestVersion(), tools, servers,
                PackageManifest.nullSafe(manifest.skills()), variables);
        return new AppliedPackage(applied, toolAuthHeaders, serverAuthHeaders);
    }

    /** {@code authHeaderVar} 指向的取值；变量没引用或取值为空时表示「这行不设鉴权头」。 */
    private java.util.Optional<String> authHeaderOf(String authHeaderVar, Map<String, String> given) {
        if (authHeaderVar == null || authHeaderVar.isBlank()) {
            return java.util.Optional.empty();
        }
        String value = given.get(authHeaderVar);
        return isBlank(value) ? java.util.Optional.empty() : java.util.Optional.of(value);
    }

    /** manifest 里全部被 {@code ${VAR}} 行内引用到的变量名。 */
    private Set<String> collectInlineReferenced(PackageManifest manifest) {
        Set<String> names = new LinkedHashSet<>();
        for (PackageManifest.ToolSpec tool : PackageManifest.nullSafe(manifest.tools())) {
            names.addAll(PackageManifestParser.referencedVariables(tool.urlTemplate()));
        }
        for (PackageManifest.McpServerSpec server : PackageManifest.nullSafe(manifest.mcpServers())) {
            names.addAll(PackageManifestParser.referencedVariables(server.url()));
        }
        return names;
    }

    /**
     * 行内替换：已声明且有取值的换成取值，已声明但没取值的换成空串（非必填变量走到这里）。
     *
     * <p><b>两道检查缺一不可，它们守的是两种不同的写错方式</b>：
     * <ul>
     *   <li><b>没声明</b>（{@code ${GHOST}}）→ 立刻抛。这种引用<b>能</b>被 VARIABLE_REF 匹配到，
     *       所以下面那道「查 {@code ${}」的兜底<b>看不见它</b>——不显式卡住的话它会被悄悄换成
     *       空串然后放行，鉴权参数凭空消失；
     *   <li><b>语法写错</b>（{@code ${lowercase}}）→ 匹配不到、替换整个跳过、{@code ${...}}
     *       原样留在串里，由下面那道兜底抓。
     * </ul>
     * 两种残留落库后的后果都写在类注释里那条「最危险」。
     */
    private String substitute(String template, Map<String, String> given,
                              Set<String> declared, String what) {
        if (template == null) {
            return null;
        }
        Matcher m = PackageManifestParser.VARIABLE_REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (!declared.contains(name)) {
                throw new BusinessException(what + "引用了未声明的变量「" + name
                        + "」：请先在包的变量表里声明它，否则安装时它会被替换成空值");
            }
            String value = given.getOrDefault(name, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        String result = sb.toString();

        int leftover = result.indexOf("${");
        if (leftover >= 0) {
            throw new BusinessException(what + "里还有没被替换的变量引用「"
                    + result.substring(leftover, Math.min(result.length(), leftover + 40))
                    + "」：变量名必须是大写字母开头的大写字母、数字、下划线组合，且已在变量表里声明");
        }
        return result;
    }

    /**
     * 替换后的地址必须仍是 http/https 绝对地址且有非空 host。
     *
     * <p>⚠️ <b>不能用 {@code URI.create} 校验</b>：{@code urlTemplate} 里合法地留着
     * {@code {city}} 这样的 LLM 占位符，而 {@code {} 不是合法的 URI 字符，会直接抛异常。
     * 故这里只做前缀 + host 段的字符串校验。
     */
    private void requireHttpUrl(String url, String what) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(what + "为空");
        }
        String rest;
        if (url.startsWith("https://")) {
            rest = url.substring("https://".length());
        } else if (url.startsWith("http://")) {
            rest = url.substring("http://".length());
        } else {
            throw new BusinessException(what + "必须是 http:// 或 https:// 开头的完整地址，当前是：" + url);
        }
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String host = rest.substring(0, end);
        if (host.isBlank() || host.indexOf(' ') >= 0) {
            throw new BusinessException(what + "的域名部分为空或含空格，替换后的结果是：" + url);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
cd backend && mvn test -Dtest=PackageVariableApplierTest
```

预期：**全部 PASS**。

- [ ] **Step 5: 变异验证 A —— 两道残留检查各摘一次，必须各红各的那条**

两道检查守的是两种不同的写错方式，**必须分别验证**——只验一道时，另一道可能从来没被执行过。

① 临时把 `substitute` 里 `if (!declared.contains(name)) { throw ... }` 整段注释掉，重跑：

预期：**只有** `未声明的变量引用被拒绝` 转红（`语法写错的变量引用被拒绝` 仍绿，它走的是另一道）。

② 恢复①，改为把 `if (leftover >= 0) { throw ... }` 整段注释掉，重跑：

预期：**只有** `语法写错的变量引用被拒绝` 转红。

⚠️ 若①摘掉后两条都绿，说明 `declared` 检查是死代码（可能 declared 集合算错了）；若②摘掉后 `未声明的变量引用被拒绝` 也跟着红，说明它其实一直靠 leftover 那道兜底——那道兜底对 `${GHOST}` 是**看不见**的（它能被 VARIABLE_REF 匹配、会被替换成空串），出现这种结果就是实现与预期不符，回头核对 `substitute` 的替换顺序。恢复代码，确认全绿。

- [ ] **Step 6: 变异验证 B —— 去掉大括号检查必须转红**

临时把 `apply` 里第 ② 步整段注释掉，重跑。

预期：`变量取值不得含大括号` 转红。恢复，确认全绿。

- [ ] **Step 7: 跑全量单测**

```bash
cd backend && mvn test
```

预期：全部 PASS。

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/pkg/PackageVariableApplier.java \
        backend/src/test/java/com/qingyu/care/service/pkg/PackageVariableApplierTest.java
git commit -m "feat(pkg): 变量填充与残留检测（纯函数）"
```

---

## Task 7: `ToolPackageService`（平台侧 CRUD + 导出编排）+ 四道 DTO 哨兵登记

**⚠️ 本任务有两个一定会撞上的机械陷阱，先说在前面：**

1. **`UpdateRequestFieldCoverageTest.DTO_TO_ENTITY` 现在正好用满 `Map.of` 的 10 对上限**（`Map.of` 只有 0..10 对的重载）。加第 11 对**编译不过**，必须整体改写成 `Map.ofEntries(Map.entry(...), ...)`。
2. **导出必须显式拒绝 `tenantId != null` 的行** —— 这是 spec 没写、但 `TenantOwnershipGuard.mutableInCurrentContext` 的语义逼出来的：**上下文租户为空（平台管理员登录后 `tenantId` 恒为 null）时它无条件放行**。也就是说平台管理员切到任意租户的应用后，`chatToolService.getTool(任意 id)` 都能取到那个租户的工具，导出就会把**租户私有的工具配置随包发给所有租户**（凭据虽已升格成变量不泄漏明文，但 `name`/`description`/`urlTemplate` 会原样导出）。判据用 `tenantId == null` 而不是「应用范围」：平台管理员在任何应用下建的行 `tenant_id` 都是 null（`TenantEntityListener` 从空上下文回填），所以这条守卫精确等于「只导出平台管理员自己建的配置」，不影响正常使用。

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/pkg/ToolPackageUpdateRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/pkg/PackageExportRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/pkg/PackageExportResult.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/pkg/PackageDetailDTO.java`
- Create: `backend/src/main/java/com/qingyu/care/service/pkg/ToolPackageService.java`
- Modify: `backend/src/test/java/com/qingyu/care/dto/UpdateRequestFieldCoverageTest.java`
- Modify: `backend/src/test/java/com/qingyu/care/controller/UpdateRequestPropagationTest.java`
- Test: `backend/src/test/java/com/qingyu/care/service/pkg/ToolPackageServiceTest.java`

**Interfaces:**
- Consumes: `ToolPackageRepository`（T2）、`PackageManifest`（T3）、`PackageManifestParser`（T4）、`PackageManifestBuilder`（T5）、既有的 `ChatToolService` / `McpServerService` / `McpToolRepository`
- Produces:
  - `ToolPackageUpdateRequest`：`String name` / `String description` / `String manifest`（**三个字段都不写初始化器**）
  - `PackageExportRequest`：`String code` / `String name` / `String description` / `List<Long> toolIds` / `List<Long> mcpServerIds` / `List<Long> skillIds`
  - `record PackageExportResult(ToolPackage toolPackage, List<String> warnings)`
  - `record PackageDetailDTO(String code, String name, String description, List<PackageManifest.VariableSpec> variables, List<String> toolNames, List<String> mcpServerNames, List<String> skillNames)`
  - `@Service ToolPackageService`：`List<ToolPackage> listAll()` / `List<ToolPackage> listPublished()` / `ToolPackage getById(Long)` / `ToolPackage getPublished(String code)` / `PackageDetailDTO detailOf(ToolPackage)` / `PackageExportResult export(PackageExportRequest)` / `ToolPackage update(Long, ToolPackageUpdateRequest)` / `ToolPackage publish(Long)` / `ToolPackage archive(Long)` / `void delete(Long)`

- [ ] **Step 1: 写四个 DTO**

`backend/src/main/java/com/qingyu/care/dto/pkg/ToolPackageUpdateRequest.java`：

```java
package com.qingyu.care.dto.pkg;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 能力包的局部更新请求体。
 *
 * <p>⚠️ <b>三个字段都不写初始化器</b>（含 {@code @Builder.Default}）：实体字段的初始化器在
 * Jackson 走无参构造反序列化时照样生效，请求体没给的字段会拿到类默认值而非 {@code null}，
 * 于是 {@code if (x != null)} 恒真、局部 PUT 把库里的值静默覆盖回默认值。本仓库为此付过一次
 * 真实代价（点一下工具启用开关，{@code httpMethod}/{@code timeoutMs} 被打回默认值），由
 * {@code UpdateRequestNoInitializerTest} 反射强制。
 *
 * <p>⚠️ <b>刻意没有 {@code code} 与 {@code status}</b>：前者是包的寻址标识（租户安装端点用它），
 * 后者只能经 {@code publish}/{@code archive} 两个专用端点改——理由与登记见
 * {@code UpdateRequestFieldCoverageTest} 的白名单条目。
 *
 * @author ruan
 */
@Data
@NoArgsConstructor
public class ToolPackageUpdateRequest {

    private String name;

    private String description;

    /** manifest 全文（JSON）。给了就必须能解析通过，解析不过整个更新被拒。 */
    private String manifest;
}
```

`backend/src/main/java/com/qingyu/care/dto/pkg/PackageExportRequest.java`：

```java
package com.qingyu.care.dto.pkg;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 导出请求：平台管理员勾选自己已经调通的工具 / MCP 服务 / 技能，连同包的文案一起提交。
 *
 * <p>spec §2.2：上架方式是「从现成配置导出」而不是包编辑器——导出路径复用的是他本来就要走的
 * 配置流程，产出的东西<b>被真实对话验证过</b>。
 *
 * @author ruan
 */
@Data
@NoArgsConstructor
public class PackageExportRequest {

    /** 包的唯一标识，租户安装端点用它寻址。格式同 MCP code：小写字母开头的字母数字下划线。 */
    private String code;

    private String name;

    private String description;

    private List<Long> toolIds;

    private List<Long> mcpServerIds;

    private List<Long> skillIds;
}
```

`backend/src/main/java/com/qingyu/care/dto/pkg/PackageExportResult.java`：

```java
package com.qingyu.care.dto.pkg;

import com.qingyu.care.entity.ToolPackage;

import java.util.List;

/**
 * 导出结果。
 *
 * <p>{@code warnings} <b>不阻断导出</b>（spec §4.1）：它们是启发式（skill 绑了集合外的工具、
 * URL 里疑似嵌了密钥），最终仍要平台管理员逐字看一遍 manifest 预览再发布——同噪声剔除，
 * 没法自动化。前端必须把它们显眼地展示出来，不能只在某个折叠区里带一句。
 *
 * @author ruan
 */
public record PackageExportResult(ToolPackage toolPackage, List<String> warnings) {
}
```

`backend/src/main/java/com/qingyu/care/dto/pkg/PackageDetailDTO.java`：

```java
package com.qingyu.care.dto.pkg;

import java.util.List;

/**
 * 租户侧看到的包详情：够他决定装不装、以及知道要填什么。
 *
 * <p>⚠️ <b>刻意不返回 manifest 全文</b>：里面的 {@code urlTemplate} / MCP 地址是平台侧的实现
 * 细节，租户拿到既无用又扩大暴露面（含平台内网地址的可能）。租户真正需要的是变量表——那是
 * 他要填的东西——与「里面有些什么」的名字清单。
 *
 * @author ruan
 */
public record PackageDetailDTO(
        String code,
        String name,
        String description,
        List<PackageManifest.VariableSpec> variables,
        List<String> toolNames,
        List<String> mcpServerNames,
        List<String> skillNames) {
}
```

- [ ] **Step 2: 写 `ToolPackageService` 的失败单测**

创建 `backend/src/test/java/com/qingyu/care/service/pkg/ToolPackageServiceTest.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.pkg.PackageExportRequest;
import com.qingyu.care.dto.pkg.PackageExportResult;
import com.qingyu.care.dto.pkg.ToolPackageUpdateRequest;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.mcp.McpServerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolPackageService} 的编排规则测试。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolPackageServiceTest {

    @Mock ToolPackageRepository packageRepository;
    @Mock ChatToolService chatToolService;
    @Mock McpServerService mcpServerService;
    @Mock McpToolRepository mcpToolRepository;

    // 三个纯函数类用真货而不是 mock：它们没有 IO，mock 掉等于把本类要验的编排结果抹平
    @Spy PackageManifestBuilder manifestBuilder = new PackageManifestBuilder();
    @Spy PackageManifestParser manifestParser = new PackageManifestParser(new ObjectMapper());

    @InjectMocks ToolPackageService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static AiTool platformTool(Long id, String name) {
        AiTool t = AiTool.builder()
                .name(name).description("描述").httpMethod("GET")
                .urlTemplate("https://api.example.com/" + name)
                .timeoutMs(5000).maxResponseChars(4000).enabled(true).build();
        t.setId(id);
        t.setTenantId(null);   // 平台管理员建的行
        return t;
    }

    private static PackageExportRequest exportRequest(Long... toolIds) {
        PackageExportRequest r = new PackageExportRequest();
        r.setCode("weather_pack");
        r.setName("天气能力包");
        r.setDescription("查天气");
        r.setToolIds(List.of(toolIds));
        return r;
    }

    @Test
    void 导出产出DRAFT状态的包并落库() {
        when(chatToolService.getTool(1L)).thenReturn(platformTool(1L, "weather_query"));
        when(packageRepository.existsByCode("weather_pack")).thenReturn(false);
        when(packageRepository.save(any(ToolPackage.class))).thenAnswer(i -> i.getArgument(0));

        PackageExportResult result = service.export(exportRequest(1L));

        assertThat(result.toolPackage().getStatus()).isEqualTo(PackageStatus.DRAFT);
        assertThat(result.toolPackage().getCode()).isEqualTo("weather_pack");
        assertThat(result.toolPackage().getManifest()).contains("weather_query");
        verify(packageRepository).save(any(ToolPackage.class));
    }

    /**
     * ⚠️ 核心安全断言：{@code TenantOwnershipGuard.mutableInCurrentContext} 对平台管理员
     * （上下文 tenantId 为 null）<b>无条件放行</b>，所以 {@code chatToolService.getTool} 能取到
     * 任意租户的工具。不在这里拦住，平台管理员切到某个租户的应用后，导出就会把该租户私有的
     * 工具配置随包发给所有租户。
     */
    @Test
    @DisplayName("勾中了属于某个租户的行 → 整个导出被拒绝，不能把租户配置发成平台包")
    void 拒绝导出租户私有的行() {
        AiTool tenantOwned = platformTool(1L, "tenant_private");
        tenantOwned.setTenantId(42L);
        when(chatToolService.getTool(1L)).thenReturn(tenantOwned);

        assertThatThrownBy(() -> service.export(exportRequest(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenant_private");
        verify(packageRepository, never()).save(any());
    }

    @Test
    void code重复被拒绝() {
        when(packageRepository.existsByCode("weather_pack")).thenReturn(true);
        when(chatToolService.getTool(1L)).thenReturn(platformTool(1L, "weather_query"));

        assertThatThrownBy(() -> service.export(exportRequest(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("weather_pack");
    }

    @Test
    void code格式非法被拒绝() {
        PackageExportRequest r = exportRequest(1L);
        r.setCode("Weather-Pack");
        when(chatToolService.getTool(1L)).thenReturn(platformTool(1L, "weather_query"));

        assertThatThrownBy(() -> service.export(r))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标识");
    }

    @Test
    void 一个都没勾被拒绝() {
        PackageExportRequest r = new PackageExportRequest();
        r.setCode("empty_pack");
        r.setName("空包");

        assertThatThrownBy(() -> service.export(r))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少");
    }

    @Test
    @DisplayName("发布前 manifest 必须能解析通过——发布出去就装得下去，不能留到租户点安装才炸")
    void 发布时校验manifest() {
        ToolPackage broken = ToolPackage.builder().id(1L).code("p").name("包")
                .status(PackageStatus.DRAFT).manifest("{ 这不是 JSON").build();
        when(packageRepository.findById(1L)).thenReturn(Optional.of(broken));

        assertThatThrownBy(() -> service.publish(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("manifest");
        verify(packageRepository, never()).save(any());
    }

    @Test
    void 发布成功后状态变PUBLISHED() {
        ToolPackage draft = ToolPackage.builder().id(1L).code("p").name("包")
                .status(PackageStatus.DRAFT)
                .manifest("""
                        {"manifestVersion":1,"tools":[{"key":"t","name":"t","description":"d",
                        "httpMethod":"GET","urlTemplate":"https://example.com/x",
                        "timeoutMs":5000,"maxResponseChars":4000}],
                        "mcpServers":[],"skills":[],"variables":[]}""")
                .build();
        when(packageRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(packageRepository.save(any(ToolPackage.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.publish(1L).getStatus()).isEqualTo(PackageStatus.PUBLISHED);
    }

    @Test
    @DisplayName("租户按 code 取包时，非 PUBLISHED 一律按「不存在」处理")
    void 未发布的包对租户不存在() {
        ToolPackage draft = ToolPackage.builder().id(1L).code("draft_pkg").name("草稿")
                .status(PackageStatus.DRAFT).manifest("{}").build();
        when(packageRepository.findByCode("draft_pkg")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.getPublished("draft_pkg"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 更新时没给的字段不被覆盖() {
        ToolPackage existing = ToolPackage.builder().id(1L).code("p").name("原名")
                .description("原描述").status(PackageStatus.DRAFT).manifest("{\"a\":1}").build();
        when(packageRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(packageRepository.save(any(ToolPackage.class))).thenAnswer(i -> i.getArgument(0));

        ToolPackageUpdateRequest request = new ToolPackageUpdateRequest();
        request.setName("新名字");

        ToolPackage updated = service.update(1L, request);

        assertThat(updated.getName()).isEqualTo("新名字");
        assertThat(updated.getDescription()).isEqualTo("原描述");
        assertThat(updated.getManifest()).isEqualTo("{\"a\":1}");
    }

    @Test
    void 更新时给的manifest解析不过整体被拒() {
        ToolPackage existing = ToolPackage.builder().id(1L).code("p").name("原名")
                .status(PackageStatus.DRAFT).manifest("{\"a\":1}").build();
        when(packageRepository.findById(1L)).thenReturn(Optional.of(existing));

        ToolPackageUpdateRequest request = new ToolPackageUpdateRequest();
        request.setManifest("{ 坏的 }");

        assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(BusinessException.class);
        verify(packageRepository, never()).save(any());
    }
}
```

- [ ] **Step 3: 实现 `ToolPackageService`**

创建 `backend/src/main/java/com/qingyu/care/service/pkg/ToolPackageService.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.dto.pkg.PackageDetailDTO;
import com.qingyu.care.dto.pkg.PackageExportRequest;
import com.qingyu.care.dto.pkg.PackageExportResult;
import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.dto.pkg.ToolPackageUpdateRequest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.McpTool;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.mcp.McpServerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 能力包的平台侧编排：CRUD、从现成配置导出、发布/下架。
 *
 * <p>规则都在三个纯函数类里（{@link PackageManifestBuilder} / {@link PackageManifestParser} /
 * {@link PackageVariableApplier}），本类只做取数与落库——范式同 {@code OpenApiImportService}。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class ToolPackageService {

    /** 包 code 的合法形状，与 MCP server 的 code 同口径（都是要被人在 URL 里读写的标识符）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** 一次导出的条目上限，防误操作把整个应用的配置灌成一个包。 */
    private static final int MAX_ITEMS = 50;

    private final ToolPackageRepository packageRepository;
    private final ChatToolService chatToolService;
    private final McpServerService mcpServerService;
    private final McpToolRepository mcpToolRepository;
    private final PackageManifestBuilder manifestBuilder;
    private final PackageManifestParser manifestParser;

    // ===== 查询 =====

    /** 平台侧列表：三种状态全给。 */
    public List<ToolPackage> listAll() {
        return packageRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 租户侧列表：只给已发布的。 */
    public List<ToolPackage> listPublished() {
        return packageRepository.findByStatusOrderByNameAsc(PackageStatus.PUBLISHED);
    }

    public ToolPackage getById(Long id) {
        return packageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("能力包", String.valueOf(id)));
    }

    /**
     * 租户按 code 取包。
     *
     * <p>⚠️ <b>非 {@code PUBLISHED} 一律按「不存在」处理</b>，不是「无权访问」：草稿包的存在
     * 本身就不该被租户探测到（同本仓库对越权一律按资源不存在处理的既有口径）。
     */
    public ToolPackage getPublished(String code) {
        return packageRepository.findByCode(code)
                .filter(p -> p.getStatus() == PackageStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("能力包", code));
    }

    /** 把包转成租户看得到的详情（不含 manifest 全文，见 {@link PackageDetailDTO} 的注释）。 */
    public PackageDetailDTO detailOf(ToolPackage toolPackage) {
        PackageManifest manifest = manifestParser.parse(toolPackage.getManifest());
        return new PackageDetailDTO(
                toolPackage.getCode(),
                toolPackage.getName(),
                toolPackage.getDescription(),
                PackageManifest.nullSafe(manifest.variables()),
                PackageManifest.nullSafe(manifest.tools()).stream()
                        .map(PackageManifest.ToolSpec::name).toList(),
                PackageManifest.nullSafe(manifest.mcpServers()).stream()
                        .map(PackageManifest.McpServerSpec::name).toList(),
                PackageManifest.nullSafe(manifest.skills()).stream()
                        .map(PackageManifest.SkillSpec::name).toList());
    }

    // ===== 导出 =====

    /**
     * 从平台管理员勾选的现成配置导出成一个 {@code DRAFT} 包。
     *
     * <p>⚠️ <b>只导出「平台自己的行」（{@code tenantId == null}）</b>。
     * {@code TenantOwnershipGuard.mutableInCurrentContext} 在上下文 tenantId 为空时
     * <b>无条件放行</b>——而平台管理员登录后 tenantId 恒为 null，于是
     * {@code chatToolService.getTool(任意 id)} 都能取到<b>任意租户</b>的工具。不在这里拦住，
     * 平台管理员切到某个租户的应用再导出，就会把该租户私有的工具配置（name / description /
     * urlTemplate）随包发给所有租户。判据取 {@code tenantId == null} 而非应用范围：平台管理员
     * 在任何应用下建的行 {@code tenant_id} 都是 null（{@code TenantEntityListener} 从空上下文
     * 回填），所以这条精确等于「只导出平台管理员自己建的配置」。
     */
    @Transactional
    public PackageExportResult export(PackageExportRequest request) {
        List<Long> toolIds = nullSafe(request.getToolIds());
        List<Long> serverIds = nullSafe(request.getMcpServerIds());
        List<Long> skillIds = nullSafe(request.getSkillIds());
        if (toolIds.isEmpty() && serverIds.isEmpty() && skillIds.isEmpty()) {
            throw new BusinessException("至少要勾选一个工具、MCP 服务或技能");
        }
        if (toolIds.size() + serverIds.size() + skillIds.size() > MAX_ITEMS) {
            throw new BusinessException("一个包最多包含 " + MAX_ITEMS + " 个条目，请拆分");
        }

        List<AiTool> tools = new ArrayList<>();
        for (Long id : toolIds) {
            AiTool tool = chatToolService.getTool(id);
            requirePlatformOwned(tool.getTenantId(), "工具", tool.getName());
            tools.add(tool);
        }
        List<McpServer> servers = new ArrayList<>();
        for (Long id : serverIds) {
            McpServer server = mcpServerService.getServer(id);
            requirePlatformOwned(server.getTenantId(), "MCP 服务", server.getName());
            servers.add(server);
        }
        List<AiSkill> skills = new ArrayList<>();
        for (Long id : skillIds) {
            AiSkill skill = chatToolService.getSkill(id);
            requirePlatformOwned(skill.getTenantId(), "技能", skill.getName());
            skills.add(skill);
        }

        // code 校验放在取数之后：先让「勾了别人的行」这类更严重的问题先报出来
        String code = request.getCode();
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new BusinessException("包标识只能是小写字母开头的字母、数字、下划线组合");
        }
        if (packageRepository.existsByCode(code)) {
            throw new BusinessException("包标识「" + code + "」已被占用");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException("请填写包名称");
        }

        PackageManifestBuilder.BuildResult built = manifestBuilder.build(
                new PackageManifestBuilder.BuildInput(tools, servers, skills,
                        toolNamesById(), mcpToolsById(skills)));

        ToolPackage saved = packageRepository.save(ToolPackage.builder()
                .code(code)
                .name(request.getName())
                .description(request.getDescription())
                .status(PackageStatus.DRAFT)
                .manifest(manifestParser.toJson(built.manifest()))
                .build());
        return new PackageExportResult(saved, built.warnings());
    }

    private void requirePlatformOwned(Long tenantId, String what, String name) {
        if (tenantId != null) {
            throw new BusinessException(what + "「" + name + "」属于某个租户，不能导出成平台能力包。"
                    + "请先在平台管理员自己的配置里把它调通，再导出");
        }
    }

    /** 全部可见工具的 id → name，只为把「集合外引用」的 id 翻译成人能看懂的名字。 */
    private Map<Long, String> toolNamesById() {
        Map<Long, String> names = new LinkedHashMap<>();
        chatToolService.listTools().forEach(t -> names.put(t.getId(), t.getName()));
        return names;
    }

    /**
     * 被这批 skill 引用到的 MCP 工具 id → (所属 server id, 远端工具名)。
     *
     * <p>只查引用到的那些，不整表扫。{@code findAllById} 是 filter-bound 的派生查询，
     * 别的租户的 id 天然查不出来。
     */
    private Map<Long, PackageManifestBuilder.McpToolRef> mcpToolsById(List<AiSkill> skills) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AiSkill skill : skills) {
            if (skill.getMcpToolIds() != null) {
                ids.addAll(skill.getMcpToolIds());
            }
        }
        Map<Long, PackageManifestBuilder.McpToolRef> refs = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return refs;
        }
        for (McpTool tool : mcpToolRepository.findAllById(ids)) {
            refs.put(tool.getId(),
                    new PackageManifestBuilder.McpToolRef(tool.getMcpServerId(), tool.getName()));
        }
        return refs;
    }

    // ===== 生命周期 =====

    @Transactional
    public ToolPackage update(Long id, ToolPackageUpdateRequest request) {
        ToolPackage existing = getById(id);
        if (request.getName() != null) {
            existing.setName(request.getName());
        }
        if (request.getDescription() != null) {
            existing.setDescription(request.getDescription());
        }
        if (request.getManifest() != null) {
            // 解析不过就整体拒绝：存进去一份坏 manifest，症状要等到某个租户点安装才现形
            manifestParser.parse(request.getManifest());
            existing.setManifest(request.getManifest());
        }
        return packageRepository.save(existing);
    }

    /**
     * 发布。
     *
     * <p>⚠️ 发布前<b>必须</b>重新解析一次 manifest：它可能被上一次 update 之后的手工编辑改坏，
     * 也可能是从别处导入的。发布出去就等于宣告「这个包装得下去」，把解析失败留到租户点安装时
     * 才炸，报错会落在租户头上而平台侧毫不知情。
     */
    @Transactional
    public ToolPackage publish(Long id) {
        ToolPackage existing = getById(id);
        manifestParser.parse(existing.getManifest());
        existing.setStatus(PackageStatus.PUBLISHED);
        return packageRepository.save(existing);
    }

    /**
     * 下架。
     *
     * <p>⚠️ <b>已经装出去的副本不受影响</b>（spec §2.3）：安装是一次性复制，装完与包断开。
     * 下架只是让它从租户的商店列表里消失。
     */
    @Transactional
    public ToolPackage archive(Long id) {
        ToolPackage existing = getById(id);
        existing.setStatus(PackageStatus.ARCHIVED);
        return packageRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        packageRepository.delete(getById(id));
    }

    private <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
```

- [ ] **Step 4: 跑单测**

```bash
cd backend && mvn test -Dtest=ToolPackageServiceTest
```

预期：**全部 PASS**。

- [ ] **Step 5: 登记进 `UpdateRequestFieldCoverageTest`（含 `Map.of` → `Map.ofEntries` 改写）**

编辑 `backend/src/test/java/com/qingyu/care/dto/UpdateRequestFieldCoverageTest.java`：

① 加两个 import：

```java
import com.qingyu.care.dto.pkg.ToolPackageUpdateRequest;
import com.qingyu.care.entity.ToolPackage;
```

② **把 `DTO_TO_ENTITY` 整体改写**（`Map.of` 最多 10 对，现在正好用满，加第 11 对编译不过）：

```java
    static final Map<Class<?>, Class<?>> DTO_TO_ENTITY = Map.ofEntries(
            Map.entry(ApplicationUpdateRequest.class, Application.class),
            Map.entry(TenantUpdateRequest.class, Tenant.class),
            Map.entry(UserUpdateRequest.class, User.class),
            Map.entry(AbTestUpdateRequest.class, AbExperiment.class),
            Map.entry(AiModelConfigUpdateRequest.class, AiModelConfig.class),
            Map.entry(AiToolUpdateRequest.class, AiTool.class),
            Map.entry(AiSkillUpdateRequest.class, AiSkill.class),
            Map.entry(ChatStrategyUpdateRequest.class, ChatStrategy.class),
            Map.entry(KnowledgeBaseUpdateRequest.class, KnowledgeBase.class),
            Map.entry(McpServerUpdateRequest.class, McpServer.class),
            // Map.of 最多 10 对（只有 0..10 的重载），第 11 条起必须用 Map.ofEntries
            Map.entry(ToolPackageUpdateRequest.class, ToolPackage.class));
```

③ 在 `buildWhitelist()` 里 `return whitelist;` 之前加两条：

```java
        Map<String, Exclusion> toolPackage = new HashMap<>();
        toolPackage.put("code", ex(ExclusionCategory.IDENTITY_KEY,
                "包的寻址标识，租户安装端点 POST /v1/packages/{code}/install 用它定位；"
                        + "只在 ToolPackageService.export 建包时写入一次，全仓没有任何更新路径调它的 setter"));
        toolPackage.put("status", ex(ExclusionCategory.LIFECYCLE_DEDICATED_ENDPOINT,
                "上架状态，由 POST /v1/platform/packages/{id}/publish 与 .../archive 两个专用端点驱动；"
                        + "publish 会先重新解析一遍 manifest（发布出去就等于宣告装得下去），"
                        + "经 update 直接改 status 会绕过这道校验，把解析失败留到租户点安装时才炸"));
        whitelist.put("ToolPackageUpdateRequest", toolPackage);
```

- [ ] **Step 6: 跑三道 DTO 哨兵**

```bash
cd backend && mvn test -Dtest='UpdateRequestFieldCoverageTest,UpdateRequestNoInitializerTest,PutEndpointBodyRegistrationTest'
```

预期：**全部 PASS**。

- `UpdateRequestFieldCoverageTest`：差集 `{code, status}` 全在白名单里。
- `UpdateRequestNoInitializerTest`：`ToolPackageUpdateRequest` 三个字段无初始化器。
- `PutEndpointBodyRegistrationTest`：本任务还没建控制器，所以它现在扫不到新端点、照常绿；Task 10 建完控制器后它会因为 `DTO_TO_ENTITY` 里已有登记而继续绿。

- [ ] **Step 7: 登记进 `UpdateRequestPropagationTest`**

编辑 `backend/src/test/java/com/qingyu/care/controller/UpdateRequestPropagationTest.java`：

① 加 import：

```java
import com.qingyu.care.dto.pkg.ToolPackageUpdateRequest;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.service.pkg.PackageManifestBuilder;
import com.qingyu.care.service.pkg.PackageManifestParser;
import com.qingyu.care.service.pkg.ToolPackageService;
```

② 在 `@Mock` 区加：

```java
    @Mock ToolPackageRepository toolPackageRepository;
    @Mock McpToolRepository mcpToolRepository;
    @Mock PackageManifestBuilder packageManifestBuilder;
    @Mock PackageManifestParser packageManifestParser;
```

③ 在 `@InjectMocks` 区加：

```java
    @InjectMocks ToolPackageService toolPackageService;
```

④ 在 `cases()` 里加一条（放在最后一条 `cases.add(...)` 之后、`return cases;` 之前）：

```java
        // 更新路径在服务层而非控制器（同 AbExperiment / KnowledgeBase 两条），
        // 写死成「调控制器」会让这条因 service 被 mock 而静默零覆盖
        cases.add(new UpdateCase(ToolPackageUpdateRequest.class, "ToolPackageService.update",
                () -> ToolPackage.builder().id(ID).code("old_pkg").name("旧包名")
                        .description("旧描述").status(PackageStatus.DRAFT)
                        .manifest("{\"manifestVersion\":1}").build(),
                (entity, dto) -> {
                    when(toolPackageRepository.findById(ID))
                            .thenReturn(Optional.of((ToolPackage) entity));
                    when(toolPackageRepository.save(any(ToolPackage.class)))
                            .thenAnswer(i -> i.getArgument(0));
                    toolPackageService.update(ID, (ToolPackageUpdateRequest) dto);
                }));
```

⚠️ `packageManifestParser` 是 mock，`parse(...)` 默认返回 null 且不抛，所以哨兵值填进 `manifest` 时不会被校验挡下——这正是本条 case 需要的（它验的是「那行 setter 在不在」，不是 manifest 合法性）。

- [ ] **Step 8: 跑传播哨兵**

```bash
cd backend && mvn test -Dtest=UpdateRequestPropagationTest
```

预期：**PASS**。

- [ ] **Step 9: 变异验证 —— 漏掉一行 setter 必须转红**

临时把 `ToolPackageService.update` 里 `existing.setDescription(request.getDescription());` 那一行删掉，重跑 `UpdateRequestPropagationTest`。

预期：**FAIL**，指出 `ToolPackageService.update` 的 `description` 没传播到实体。恢复，确认绿。

- [ ] **Step 10: 跑全量单测**

```bash
cd backend && mvn test
```

预期：全部 PASS。

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/dto/pkg/ \
        backend/src/main/java/com/qingyu/care/service/pkg/ToolPackageService.java \
        backend/src/test/java/com/qingyu/care/service/pkg/ToolPackageServiceTest.java \
        backend/src/test/java/com/qingyu/care/dto/UpdateRequestFieldCoverageTest.java \
        backend/src/test/java/com/qingyu/care/controller/UpdateRequestPropagationTest.java
git commit -m "feat(pkg): 平台侧包 CRUD 与导出编排（含四道 DTO 哨兵登记）"
```

---

## Task 8: `PackageInstallService` 段一（前置校验 + 冲突检测 + 事务内建行）

**⚠️ 又一条 spec 没写、不写就会静默事故的前置校验**：安装前必须断言 `TenantContext` 的 `tenantId` **和** `appId` 都非空。

- `appId` 为空时建出来的行 `app_id` 落 null，而 `ChatToolService.resolve()` 走的是 filter-bound 查询（`appFilter` 按 `app_id` 等值匹配）——`app_id IS NULL` 的行**对任何应用都匹配不上**。装了等于没装，且**完全静默**：包安装成功、界面上工具与技能都在列表里（管理面 list 按 `findByAppId` 查……也查不到），只是对话链路永远挂不上。
- `tenantId` 为空时（平台管理员调这个端点，而 `SecurityUtil.requireTenantAdmin()` 是放行平台管理员的）落的是 `tenant_id IS NULL` 的行，那是「平台共享」的语义位置——正是本仓库反复出事的地方。

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/pkg/PackageInstallRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/pkg/PackageInstallResult.java`
- Create: `backend/src/main/java/com/qingyu/care/service/pkg/PackageInstallService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/pkg/PackageInstallServiceTest.java`

**Interfaces:**
- Consumes: `ToolPackageService.getPublished(String)`（T7）、`PackageManifestParser`（T4）、`PackageVariableApplier`（T6）、`AiToolRepository` / `AiSkillRepository` / `McpServerRepository` / `McpServerService`
- Produces:
  - `PackageInstallRequest`：`Map<String, String> variables`
  - `record PackageInstallResult(List<String> toolNames, List<String> mcpServerNames, List<String> skillNames, List<String> warnings)`
  - `@Service PackageInstallService`，构造参数 `(ToolPackageService, PackageManifestParser, PackageVariableApplier, AiToolRepository, AiSkillRepository, McpServerRepository, McpServerService, McpToolRepository, TransactionTemplate)`
  - `PackageInstallResult install(String code, Map<String, String> values)`

- [ ] **Step 1: 写两个 DTO**

`backend/src/main/java/com/qingyu/care/dto/pkg/PackageInstallRequest.java`：

```java
package com.qingyu.care.dto.pkg;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 安装请求：变量名 → 租户填的取值。
 *
 * <p>⚠️ <b>这里没有 tenantId / appId / scope</b>：目标租户与应用由 {@code TenantContext} 决定
 * （Sa-Token 会话 + {@code X-App-Id} 头），归属一律服务端强制（spec §3.4）。
 *
 * @author ruan
 */
@Data
@NoArgsConstructor
public class PackageInstallRequest {

    private Map<String, String> variables;
}
```

`backend/src/main/java/com/qingyu/care/dto/pkg/PackageInstallResult.java`：

```java
package com.qingyu.care.dto.pkg;

import java.util.List;

/**
 * 安装摘要：建了哪些行、哪些需要后续操作。
 *
 * <p>{@code warnings} 承载「部分成功」（spec §4.3）——最典型的是某个 MCP 服务同步失败：
 * 服务本身已经装好了，但它的工具清单还没拉下来，技能里那几个 MCP 工具的绑定就还空着。
 * 界面必须把这句话显眼地摆出来，否则租户会以为装完就能用。
 *
 * @author ruan
 */
public record PackageInstallResult(
        List<String> toolNames,
        List<String> mcpServerNames,
        List<String> skillNames,
        List<String> warnings) {
}
```

- [ ] **Step 2: 写失败的单测（只覆盖段一：纯 HTTP 工具包）**

创建 `backend/src/test/java/com/qingyu/care/service/pkg/PackageInstallServiceTest.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.pkg.PackageInstallResult;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.service.mcp.McpServerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PackageInstallService} 的安装编排测试（段一：HTTP 工具 + 技能）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PackageInstallServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long APP_ID = 70L;

    @Mock ToolPackageService packageService;
    @Mock AiToolRepository toolRepository;
    @Mock AiSkillRepository skillRepository;
    @Mock McpServerRepository mcpServerRepository;
    @Mock McpServerService mcpServerService;
    @Mock McpToolRepository mcpToolRepository;
    @Mock TransactionTemplate transactionTemplate;

    @Spy PackageManifestParser manifestParser = new PackageManifestParser(new ObjectMapper());
    @Spy PackageVariableApplier variableApplier = new PackageVariableApplier();

    @InjectMocks PackageInstallService service;

    /** 一个只含 HTTP 工具 + 技能的包，工具的鉴权头是变量。 */
    private static final String MANIFEST = """
            {
              "manifestVersion": 1,
              "tools": [{
                "key": "weather_query", "name": "weather_query", "description": "查天气",
                "inputSchema": "{\\"type\\":\\"object\\"}",
                "httpMethod": "GET",
                "urlTemplate": "https://${WEATHER_HOST}/v1/weather?city={city}",
                "timeoutMs": 5000, "maxResponseChars": 4000,
                "authHeaderVar": "WEATHER_AUTH"
              }],
              "mcpServers": [],
              "skills": [{
                "key": "weather_skill", "name": "天气助手", "description": "回答天气问题",
                "content": "# 天气助手\\n用户问天气时调用 weather_query。",
                "toolKeys": ["weather_query"], "mcpRefs": []
              }],
              "variables": [
                {"name": "WEATHER_HOST", "label": "服务地址", "required": true, "secret": false, "help": "如 api.qweather.com"},
                {"name": "WEATHER_AUTH", "label": "鉴权头", "required": true, "secret": true, "help": "Authorization: Bearer xxx"}
              ]
            }
            """;

    private static final Map<String, String> VALUES = Map.of(
            "WEATHER_HOST", "api.qweather.com",
            "WEATHER_AUTH", "Authorization: Bearer sk-tenant-own");

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setAppId(APP_ID);

        when(packageService.getPublished("weather_pack")).thenReturn(
                ToolPackage.builder().id(1L).code("weather_pack").name("天气包")
                        .status(PackageStatus.PUBLISHED).manifest(MANIFEST).build());

        // TransactionTemplate 直接执行回调：本类验的是编排顺序与落库内容，不是事务边界
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        // 落库时补一个自增 id，供 skill 回填工具绑定
        AtomicLong toolIdSeq = new AtomicLong(100);
        when(toolRepository.save(any(AiTool.class))).thenAnswer(invocation -> {
            AiTool t = invocation.getArgument(0);
            t.setId(toolIdSeq.incrementAndGet());
            return t;
        });
        when(skillRepository.save(any(AiSkill.class))).thenAnswer(invocation -> {
            AiSkill s = invocation.getArgument(0);
            s.setId(200L);
            return s;
        });
        when(toolRepository.findByAppId(APP_ID)).thenReturn(List.of());
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());
        when(skillRepository.findByAppId(APP_ID)).thenReturn(List.of());
        when(mcpServerRepository.findByAppId(APP_ID)).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 安装建出工具与技能并返回摘要() {
        PackageInstallResult result = service.install("weather_pack", VALUES);

        assertThat(result.toolNames()).containsExactly("weather_query");
        assertThat(result.skillNames()).containsExactly("天气助手");
        assertThat(result.mcpServerNames()).isEmpty();
    }

    @Test
    @DisplayName("落库的工具：变量已替换、租户填的凭据已写入、scope 强制 TENANT")
    void 工具字段与归属正确() {
        service.install("weather_pack", VALUES);

        ArgumentCaptor<AiTool> captor = ArgumentCaptor.forClass(AiTool.class);
        verify(toolRepository).save(captor.capture());
        AiTool saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("weather_query");
        assertThat(saved.getUrlTemplate())
                .isEqualTo("https://api.qweather.com/v1/weather?city={city}");
        assertThat(saved.getAuthHeader()).isEqualTo("Authorization: Bearer sk-tenant-own");
        assertThat(saved.getHttpMethod()).isEqualTo("GET");
        assertThat(saved.getTimeoutMs()).isEqualTo(5000);
        assertThat(saved.getMaxResponseChars()).isEqualTo(4000);
        assertThat(saved.getEnabled()).isTrue();
        // ⚠️ scope 必须是服务端强制的 TENANT——manifest 里压根没有这个字段，
        // 但 AiTool.scope 有 @Builder.Default = TENANT，靠默认值是「碰巧对」而不是「强制对」
        assertThat(saved.getScope()).isEqualTo(OwnerScope.TENANT);
        // 归属交给 TenantEntityListener 从上下文回填，这里不该手工设（也不该被 manifest 影响）
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("skill 的 toolIds 指向新建行的 id，不是 manifest 里的任何东西")
    void skill绑定回填成新建工具的id() {
        service.install("weather_pack", VALUES);

        ArgumentCaptor<AiSkill> captor = ArgumentCaptor.forClass(AiSkill.class);
        verify(skillRepository).save(captor.capture());
        AiSkill saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("天气助手");
        assertThat(saved.getContent()).contains("weather_query");
        assertThat(saved.getToolIds()).containsExactly(101L);   // toolIdSeq 的第一个值
        assertThat(saved.getEnabled()).isTrue();
    }

    /** ⚠️ appId 为空时建出来的行对任何应用都检索不到，且完全静默——必须在入口拒绝。 */
    @Test
    @DisplayName("没选应用（appId 为空）直接拒绝安装")
    void 未选应用被拒绝() {
        TenantContext.setAppId(null);

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("应用");
        verify(toolRepository, never()).save(any());
    }

    /** ⚠️ requireTenantAdmin 放行平台管理员，而他没有 tenantId——落库会是 tenant_id IS NULL。 */
    @Test
    @DisplayName("没有租户上下文（平台管理员）直接拒绝安装")
    void 无租户上下文被拒绝() {
        TenantContext.setTenantId(null);

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户");
        verify(toolRepository, never()).save(any());
    }

    @Test
    @DisplayName("工具重名 → 整体拒绝并列出冲突项，不自动改名")
    void 工具重名整体拒绝() {
        AiTool existing = AiTool.builder().name("weather_query").description("已有的")
                .urlTemplate("https://x.example.com").build();
        when(toolRepository.findByAppId(APP_ID)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("weather_query");
        verify(toolRepository, never()).save(any());
    }

    @Test
    @DisplayName("与平台共享工具重名同样拒绝——resolve() 遇到重名会静默跳过其中一个")
    void 与共享工具重名也拒绝() {
        AiTool shared = AiTool.builder().name("weather_query").description("平台共享")
                .urlTemplate("https://x.example.com").scope(OwnerScope.PLATFORM).build();
        when(toolRepository.findAllSharedTools()).thenReturn(List.of(shared));

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("weather_query");
    }

    @Test
    void 技能重名整体拒绝() {
        AiSkill existing = AiSkill.builder().name("天气助手").content("已有").build();
        when(skillRepository.findByAppId(APP_ID)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("天气助手");
    }

    @Test
    @DisplayName("多个冲突一次列全，别让人改一个装一次")
    void 多个冲突一次列全() {
        when(toolRepository.findByAppId(APP_ID)).thenReturn(List.of(
                AiTool.builder().name("weather_query").description("x")
                        .urlTemplate("https://x.example.com").build()));
        when(skillRepository.findByAppId(APP_ID)).thenReturn(List.of(
                AiSkill.builder().name("天气助手").content("x").build()));

        assertThatThrownBy(() -> service.install("weather_pack", VALUES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("weather_query")
                .hasMessageContaining("天气助手");
    }

    @Test
    void 必填变量没填时在落库之前就被拒绝() {
        assertThatThrownBy(() -> service.install("weather_pack", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WEATHER_HOST");
        verify(toolRepository, never()).save(any());
    }

    @Test
    @DisplayName("MCP 服务的 code 在当前应用内重名同样拒绝（它是 LLM 工具名前缀）")
    void MCP服务code重名拒绝() {
        String mcpManifest = """
                {
                  "manifestVersion": 1, "tools": [],
                  "mcpServers": [{
                    "key": "ctx7", "name": "Context7", "code": "ctx7",
                    "transport": "STREAMABLE_HTTP", "url": "https://mcp.example.com/mcp",
                    "timeoutMs": 10000, "maxResponseChars": 4000
                  }],
                  "skills": [], "variables": []
                }
                """;
        when(packageService.getPublished("mcp_pack")).thenReturn(
                ToolPackage.builder().id(2L).code("mcp_pack").name("MCP 包")
                        .status(PackageStatus.PUBLISHED).manifest(mcpManifest).build());
        when(mcpServerRepository.findByAppId(APP_ID)).thenReturn(List.of(
                McpServer.builder().name("别的服务").code("ctx7")
                        .url("https://other.example.com/mcp").build()));

        assertThatThrownBy(() -> service.install("mcp_pack", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ctx7");
        verify(mcpServerService, never()).saveServer(any());
    }
}
```

- [ ] **Step 3: 跑它确认编译失败**

```bash
cd backend && mvn test -Dtest=PackageInstallServiceTest
```

预期：**编译错误**，`PackageInstallService` 不存在。

- [ ] **Step 4: 实现 `PackageInstallService`（段一部分，段二先留成空实现）**

创建 `backend/src/main/java/com/qingyu/care/service/pkg/PackageInstallService.java`：

```java
package com.qingyu.care.service.pkg;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.pkg.PackageInstallResult;
import com.qingyu.care.dto.pkg.PackageManifest;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.McpServerRepository;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.service.mcp.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能力包安装编排：冲突检测 + 两段式落库。
 *
 * <p><b>为什么必须分两段</b>（spec §4.3）：{@code McpServerService.sync()} 是<b>网络出站 +
 * 自己写库</b>，且<b>刻意不带 {@code @Transactional}</b>（它的半截状态靠重新同步收敛，
 * CLAUDE.md「MCP 接入」第 9 条）。把它包进安装事务有两重问题：长事务占着数据库连接做网络
 * 往返；以及跟它自己的非事务语义直接打架。
 *
 * <p><b>段二失败不回滚段一</b>，这是<b>有意的部分成功</b>：MCP 服务已经装好了，界面明确提示
 * 「同步失败，请手动同步后到技能里勾选工具」。整个回滚对租户更糟——工具和技能全白装，而它们
 * 跟 MCP 同步失败没有任何关系。
 *
 * <p>⚠️ <b>用 {@link TransactionTemplate} 而不是 {@code @Transactional} + 自注入代理</b>：
 * 段一要在事务里、段二要在事务外，同一个方法里做不到；而 {@code @Lazy} 自注入那条路
 * （{@code WebPageSyncService} 的写法）在纯 Mockito 单测里构造不出代理，会让本类最需要测的
 * 那段编排逻辑失去单测覆盖。范式同 {@code AnalyticsRollupService} 与 {@code ChatServiceImpl}。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PackageInstallService {

    private final ToolPackageService packageService;
    private final PackageManifestParser manifestParser;
    private final PackageVariableApplier variableApplier;
    private final AiToolRepository toolRepository;
    private final AiSkillRepository skillRepository;
    private final McpServerRepository mcpServerRepository;
    private final McpServerService mcpServerService;
    private final McpToolRepository mcpToolRepository;
    private final TransactionTemplate transactionTemplate;

    /** 段一的产物，供段二回填 skill 绑定用。 */
    private record Stage1(Map<String, Long> serverIdByKey, Map<String, Long> skillIdByKey,
                          List<String> toolNames, List<String> serverNames, List<String> skillNames) {
    }

    public PackageInstallResult install(String code, Map<String, String> values) {
        requireInstallContext();

        ToolPackage toolPackage = packageService.getPublished(code);
        PackageManifest manifest = manifestParser.parse(toolPackage.getManifest());
        PackageVariableApplier.AppliedPackage applied = variableApplier.apply(manifest, values);

        requireNoConflict(applied.manifest());

        List<String> warnings = new ArrayList<>();
        Stage1 stage1 = transactionTemplate.execute(status -> installStage1(applied));

        // 段二：MCP 工具清单同步 + skill 绑定回填。事务外，best-effort。
        syncMcpAndBindSkills(applied.manifest(), stage1, warnings);

        log.info("能力包安装完成 code={} 工具={} MCP={} 技能={}",
                code, stage1.toolNames().size(), stage1.serverNames().size(),
                stage1.skillNames().size());
        return new PackageInstallResult(stage1.toolNames(), stage1.serverNames(),
                stage1.skillNames(), warnings);
    }

    /**
     * 安装上下文的硬前置。
     *
     * <p>⚠️ <b>appId 为空</b>：建出来的行 {@code app_id} 落 null，而 {@code ChatToolService.resolve()}
     * 走 filter-bound 查询（{@code appFilter} 按 {@code app_id} 等值匹配），{@code NULL} 对任何
     * 应用都匹配不上——<b>装了等于没装，且完全静默</b>。
     *
     * <p>⚠️ <b>tenantId 为空</b>：{@code SecurityUtil.requireTenantAdmin()} 是<b>放行平台管理员</b>
     * 的，而平台管理员登录后 {@code tenantId} 恒为 null，落库会写成 {@code tenant_id IS NULL}
     * ——那是「平台共享」的语义位置，本仓库反复在这里出事（CLAUDE.md 里那几条「静默落 NULL」）。
     */
    private void requireInstallContext() {
        if (TenantContext.getTenantId() == null) {
            throw new BusinessException("请用租户管理员账号安装：平台管理员没有租户归属，"
                    + "装出来的配置不属于任何租户");
        }
        if (TenantContext.getAppId() == null) {
            throw new BusinessException("请先在左上角选择要安装到的应用");
        }
    }

    /**
     * 冲突检测：<b>整体拒绝，不自动改名</b>（spec §4.2）。
     *
     * <p>⚠️ 静默改名会让人以为装好了却在列表里找不到自己认识的名字。
     *
     * <p>⚠️ 工具名的已占用集合<b>必须算上平台共享工具</b>：{@code ChatToolService.resolve()}
     * 遇到重名会静默跳过其中一个（只 {@code log.warn}），所以与共享工具重名的工具即便装进去了
     * 也挂不上 LLM——同 {@code OpenApiImportService.existingNames()} 的理由。
     */
    private void requireNoConflict(PackageManifest manifest) {
        Long appId = TenantContext.getAppId();

        Set<String> takenToolNames = new LinkedHashSet<>();
        toolRepository.findByAppId(appId).forEach(t -> takenToolNames.add(t.getName()));
        toolRepository.findAllSharedTools().forEach(t -> takenToolNames.add(t.getName()));

        Set<String> takenSkillNames = new LinkedHashSet<>();
        skillRepository.findByAppId(appId).forEach(s -> takenSkillNames.add(s.getName()));

        Set<String> takenServerNames = new LinkedHashSet<>();
        Set<String> takenServerCodes = new LinkedHashSet<>();
        mcpServerRepository.findByAppId(appId).forEach(s -> {
            takenServerNames.add(s.getName());
            takenServerCodes.add(s.getCode());
        });

        List<String> conflicts = new ArrayList<>();
        for (PackageManifest.ToolSpec tool : PackageManifest.nullSafe(manifest.tools())) {
            if (takenToolNames.contains(tool.name())) {
                conflicts.add("工具「" + tool.name() + "」");
            }
        }
        for (PackageManifest.SkillSpec skill : PackageManifest.nullSafe(manifest.skills())) {
            if (takenSkillNames.contains(skill.name())) {
                conflicts.add("技能「" + skill.name() + "」");
            }
        }
        for (PackageManifest.McpServerSpec server : PackageManifest.nullSafe(manifest.mcpServers())) {
            if (takenServerNames.contains(server.name())) {
                conflicts.add("MCP 服务「" + server.name() + "」");
            }
            if (takenServerCodes.contains(server.code())) {
                // code 参与 LLM 可见的工具名（{code}__{toolName}），同应用内撞了会让
                // 全局工具名去重静默丢掉其中一个 server 的全部工具
                conflicts.add("MCP 服务标识「" + server.code() + "」");
            }
        }

        if (!conflicts.isEmpty()) {
            throw new BusinessException("以下名称在当前应用里已存在，请先重命名或删除已有的，再安装："
                    + String.join("、", conflicts));
        }
    }

    /**
     * 段一（事务内）：建 {@code AiTool} / {@code McpServer} / {@code AiSkill}。
     *
     * <p>⚠️ skill 的 {@code mcpToolIds} 这一步<b>留空</b>——安装那一刻 {@code mcp_tool} 表里
     * 一行都没有（spec §4.3），要等段二同步完才知道 id。
     *
     * <p>⚠️ 归属字段（{@code tenantId} / {@code appId}）<b>一个都不手工设</b>：由
     * {@code TenantEntityListener} 的 {@code @PrePersist} 从 {@code TenantContext} 回填。
     * {@code scope} 则必须<b>显式强制</b> {@code TENANT}——靠实体上的 {@code @Builder.Default}
     * 是「碰巧对」而不是「强制对」。
     */
    private Stage1 installStage1(PackageVariableApplier.AppliedPackage applied) {
        PackageManifest manifest = applied.manifest();

        Map<String, Long> toolIdByKey = new LinkedHashMap<>();
        List<String> toolNames = new ArrayList<>();
        for (PackageManifest.ToolSpec spec : PackageManifest.nullSafe(manifest.tools())) {
            AiTool tool = new AiTool();
            tool.setName(spec.name());
            tool.setDescription(spec.description());
            tool.setInputSchema(spec.inputSchema());
            tool.setHttpMethod(spec.httpMethod());
            tool.setUrlTemplate(spec.urlTemplate());
            tool.setAuthHeader(applied.toolAuthHeaders().get(spec.key()));
            tool.setTimeoutMs(spec.timeoutMs());
            tool.setMaxResponseChars(spec.maxResponseChars());
            tool.setEnabled(true);
            tool.setScope(OwnerScope.TENANT);
            AiTool saved = toolRepository.save(tool);
            toolIdByKey.put(spec.key(), saved.getId());
            toolNames.add(saved.getName());
        }

        Map<String, Long> serverIdByKey = new LinkedHashMap<>();
        List<String> serverNames = new ArrayList<>();
        for (PackageManifest.McpServerSpec spec : PackageManifest.nullSafe(manifest.mcpServers())) {
            McpServer server = new McpServer();
            server.setName(spec.name());
            server.setCode(spec.code());
            server.setDescription(spec.description());
            server.setTransport(spec.transport());
            server.setUrl(spec.url());
            server.setAuthHeader(applied.serverAuthHeaders().get(spec.key()));
            server.setTimeoutMs(spec.timeoutMs());
            server.setMaxResponseChars(spec.maxResponseChars());
            server.setEnabled(true);
            server.setScope(OwnerScope.TENANT);
            // 走 saveServer 而非裸 repository：复用它的 code 格式与同应用唯一性校验（纵深防御）
            McpServer saved = mcpServerService.saveServer(server);
            serverIdByKey.put(spec.key(), saved.getId());
            serverNames.add(saved.getName());
        }

        Map<String, Long> skillIdByKey = new LinkedHashMap<>();
        List<String> skillNames = new ArrayList<>();
        for (PackageManifest.SkillSpec spec : PackageManifest.nullSafe(manifest.skills())) {
            AiSkill skill = new AiSkill();
            skill.setName(spec.name());
            skill.setDescription(spec.description());
            skill.setContent(spec.content());
            Set<Long> toolIds = new LinkedHashSet<>();
            for (String toolKey : PackageManifest.nullSafe(spec.toolKeys())) {
                Long id = toolIdByKey.get(toolKey);
                if (id != null) {
                    toolIds.add(id);
                }
            }
            skill.setToolIds(toolIds);
            skill.setMcpToolIds(new LinkedHashSet<>());   // 段二回填
            skill.setEnabled(true);
            AiSkill saved = skillRepository.save(skill);
            skillIdByKey.put(spec.key(), saved.getId());
            skillNames.add(saved.getName());
        }

        return new Stage1(serverIdByKey, skillIdByKey, toolNames, serverNames, skillNames);
    }

    /** 段二在 Task 9 实现，本步先留空（没有 MCP server 时它本来就无事可做）。 */
    private void syncMcpAndBindSkills(PackageManifest manifest, Stage1 stage1,
                                      List<String> warnings) {
        // Task 9
    }
}
```

- [ ] **Step 5: 跑单测**

```bash
cd backend && mvn test -Dtest=PackageInstallServiceTest
```

预期：**全部 PASS**。

⚠️ 若 `skill绑定回填成新建工具的id` 报 `expected [101] but was [...]`，检查 `AtomicLong toolIdSeq` 的起始值与 `incrementAndGet` 语义（起 100、第一次 `incrementAndGet` 得 101）。

- [ ] **Step 6: 变异验证 A —— 去掉 scope 强制必须转红**

临时把 `tool.setScope(OwnerScope.TENANT);` 删掉，重跑。

⚠️ **预期是仍然绿**——因为 `new AiTool()` 走 Lombok 无参构造，`@Builder.Default` 只对 builder 生效，**无参构造下 `scope` 是 null**……请**实际跑一遍确认到底是红是绿**，然后按结果二选一：

- **红**：说明断言有效，恢复代码即可。
- **绿**：说明 `new AiTool()` 恰好给出了 `TENANT`（Lombok 对带 `@Builder.Default` 的字段在无参构造下的行为随版本变化）。这时**把段一改成用 `AiTool.builder()...build()` 并保留显式 `.scope(OwnerScope.TENANT)`**，再重做本次变异——必须做到「删掉那行就转红」，否则这条断言是永真的摆设。

这一步不是形式主义：`scope` 落错的后果是租户的工具连同其鉴权头出现在所有租户的共享列表里（CLAUDE.md「平台共享工具」第 1 条那次真实事故）。

- [ ] **Step 7: 变异验证 B —— 去掉共享工具重名检查必须转红**

临时把 `requireNoConflict` 里 `toolRepository.findAllSharedTools().forEach(...)` 那一行删掉，重跑。

预期：`与共享工具重名也拒绝` 转红。恢复，确认全绿。

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/dto/pkg/PackageInstallRequest.java \
        backend/src/main/java/com/qingyu/care/dto/pkg/PackageInstallResult.java \
        backend/src/main/java/com/qingyu/care/service/pkg/PackageInstallService.java \
        backend/src/test/java/com/qingyu/care/service/pkg/PackageInstallServiceTest.java
git commit -m "feat(pkg): 安装段一——冲突检测与事务内建行"
```

---

## Task 9: `PackageInstallService` 段二（MCP 同步 + skill 绑定回填）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/pkg/PackageInstallService.java`（实现 `syncMcpAndBindSkills`）
- Modify: `backend/src/test/java/com/qingyu/care/service/pkg/PackageInstallServiceTest.java`（追加 MCP 相关用例）

**Interfaces:**
- Consumes: Task 8 的 `Stage1` record、`McpServerService.sync(Long)`、`McpToolRepository.findByMcpServerId(Long)`
- Produces: 无新公开签名，`install()` 的 `warnings` 在 MCP 同步失败时非空

- [ ] **Step 1: 追加失败的单测**

在 `PackageInstallServiceTest` 末尾（最后一个 `}` 之前）追加：

```java
    /** 一个含 MCP 服务 + 技能引用其工具的包。 */
    private static final String MCP_MANIFEST = """
            {
              "manifestVersion": 1,
              "tools": [],
              "mcpServers": [{
                "key": "ctx7", "name": "Context7", "code": "ctx7", "description": "文档检索",
                "transport": "STREAMABLE_HTTP", "url": "https://mcp.context7.com/mcp",
                "timeoutMs": 10000, "maxResponseChars": 4000
              }],
              "skills": [{
                "key": "doc_skill", "name": "文档助手", "description": "查文档",
                "content": "查文档时用 ctx7 的工具。",
                "toolKeys": [],
                "mcpRefs": [{"serverKey": "ctx7", "toolNames": ["resolve-library-id", "get-docs"]}]
              }],
              "variables": []
            }
            """;

    private void seedMcpPackage() {
        when(packageService.getPublished("doc_pack")).thenReturn(
                ToolPackage.builder().id(3L).code("doc_pack").name("文档包")
                        .status(PackageStatus.PUBLISHED).manifest(MCP_MANIFEST).build());
        when(mcpServerService.saveServer(any(McpServer.class))).thenAnswer(invocation -> {
            McpServer s = invocation.getArgument(0);
            s.setId(300L);
            return s;
        });
    }

    private static com.qingyu.care.entity.McpTool mcpTool(Long id, String name, boolean readOnly) {
        com.qingyu.care.entity.McpTool t = com.qingyu.care.entity.McpTool.builder()
                .mcpServerId(300L).name(name).readOnly(readOnly).build();
        t.setId(id);
        return t;
    }

    @Test
    @DisplayName("MCP 同步成功 → 按工具名映射成新建的 mcp_tool id 并回填进 skill")
    void MCP同步成功后回填skill绑定() {
        seedMcpPackage();
        when(mcpServerService.sync(300L)).thenReturn(List.of());
        when(mcpToolRepository.findByMcpServerId(300L)).thenReturn(List.of(
                mcpTool(400L, "resolve-library-id", false),
                mcpTool(401L, "get-docs", false),
                mcpTool(402L, "write-something", false)));

        PackageInstallResult result = service.install("doc_pack", Map.of());

        assertThat(result.mcpServerNames()).containsExactly("Context7");

        // skill 被保存两次：段一建行（mcpToolIds 空）+ 段二回填
        ArgumentCaptor<AiSkill> captor = ArgumentCaptor.forClass(AiSkill.class);
        verify(skillRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        AiSkill last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getMcpToolIds()).containsExactlyInAnyOrder(400L, 401L);
        // manifest 没点名的工具不该被绑上
        assertThat(last.getMcpToolIds()).doesNotContain(402L);
    }

    @Test
    @DisplayName("MCP 同步失败 → 部分成功：服务与技能都在，warning 说清楚要手动同步")
    void MCP同步失败是部分成功而非整体失败() {
        seedMcpPackage();
        when(mcpServerService.sync(300L))
                .thenThrow(new BusinessException("同步失败：Connection refused"));

        PackageInstallResult result = service.install("doc_pack", Map.of());

        assertThat(result.mcpServerNames()).containsExactly("Context7");
        assertThat(result.skillNames()).containsExactly("文档助手");
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("Context7").contains("手动同步"));
    }

    @Test
    @DisplayName("远端少了 manifest 点名的工具 → 记 warning，别静默少绑")
    void 远端缺少点名的工具记warning() {
        seedMcpPackage();
        when(mcpServerService.sync(300L)).thenReturn(List.of());
        when(mcpToolRepository.findByMcpServerId(300L))
                .thenReturn(List.of(mcpTool(400L, "resolve-library-id", false)));

        PackageInstallResult result = service.install("doc_pack", Map.of());

        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("get-docs"));
    }

    /**
     * ⚠️ 这条守的是「sync 成功之后」那一段——它与上面那条（sync 本身失败）走的是**不同的**
     * 代码位置。变异测试对它无能为力：摘掉已有的 catch 只能证明「这段保护在被执行」，
     * 证明不了「所有可能抛异常的调用都被保护」。
     */
    @Test
    @DisplayName("sync 成功但读取工具行失败 → 仍是部分成功，不能把段一的成果拖下水")
    void 读取工具行失败也是部分成功() {
        seedMcpPackage();
        when(mcpServerService.sync(300L)).thenReturn(List.of());
        when(mcpToolRepository.findByMcpServerId(300L))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("连接抖动"));

        PackageInstallResult result = service.install("doc_pack", Map.of());

        assertThat(result.mcpServerNames()).containsExactly("Context7");
        assertThat(result.skillNames()).containsExactly("文档助手");
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("Context7").contains("手动同步"));
    }

    @Test
    @DisplayName("段二抛任何异常都不能把段一的成果连累掉")
    void 段二异常不影响段一结果() {
        seedMcpPackage();
        when(mcpServerService.sync(300L)).thenThrow(new RuntimeException("意料之外"));

        PackageInstallResult result = service.install("doc_pack", Map.of());

        assertThat(result.mcpServerNames()).containsExactly("Context7");
        assertThat(result.warnings()).isNotEmpty();
    }
```

⚠️ 追加后需要在文件顶部补 import：`com.qingyu.care.entity.McpTool` 用了全限定名以避免与既有 import 冲突，其余（`McpServer`、`BusinessException`、`ArgumentCaptor`）Task 8 已经 import 过。

- [ ] **Step 2: 跑它确认新用例失败**

```bash
cd backend && mvn test -Dtest=PackageInstallServiceTest
```

预期：4 条新用例 **FAIL**（`syncMcpAndBindSkills` 还是空实现，`warnings` 恒空、skill 只保存一次），Task 8 的老用例仍 PASS。

- [ ] **Step 3: 实现段二**

把 `PackageInstallService` 里那个空的 `syncMcpAndBindSkills` 替换为：

```java
    /**
     * 段二（事务外）：逐个同步 MCP 工具清单，成功则按 {@code mcpRefs.toolNames} 映射成新建的
     * {@code McpTool} id，再开一个短事务把绑定回填进 skill。
     *
     * <p>⚠️ <b>整段 best-effort，绝不把异常抛出去</b>（spec §4.3）：段一已经提交，工具与技能
     * 都建好了，它们跟 MCP 同步失败没有任何关系。抛出去会让客户端收到一个「安装失败」，而库里
     * 明明躺着一整套装好的行——租户会重装一次，然后撞上冲突检测。
     *
     * <p>⚠️ <b>catch 的是 {@code Exception} 而不只是 {@code BusinessException}</b>：
     * {@code sync()} 内部会抛 {@code DataAccessException}、网络层异常等各种东西。
     */
    private void syncMcpAndBindSkills(PackageManifest manifest, Stage1 stage1,
                                      List<String> warnings) {
        if (stage1.serverIdByKey().isEmpty()) {
            return;
        }

        // serverKey → (远端工具名 → 新建的 mcp_tool id)
        Map<String, Map<String, Long>> toolIdsByServerKey = new LinkedHashMap<>();
        for (PackageManifest.McpServerSpec spec : PackageManifest.nullSafe(manifest.mcpServers())) {
            Long serverId = stage1.serverIdByKey().get(spec.key());
            if (serverId == null) {
                continue;
            }
            Map<String, Long> byName = new LinkedHashMap<>();
            try {
                mcpServerService.sync(serverId);
                // ⚠️ 读取新建的工具行**必须在同一个 try 之内**：它紧跟一次成功的网络同步之后，
                // 一旦抛（连接抖动、超时、连接池耗尽）会不经任何拦截从 install() 冒出，把
                // 「部分成功」变成「整体失败」——而段一已经提交，客户端却收到「安装失败」，
                // 租户会重装一次然后撞上冲突检测。
                // ⚠️ 变异测试抓不到这条：摘掉已有的 catch 只能证明「这段保护在被执行」，
                // 证明不了「所有可能抛异常的调用都被保护」——它验不到本就没有保护的那条线。
                mcpToolRepository.findByMcpServerId(serverId)
                        .forEach(t -> byName.put(t.getName(), t.getId()));
            } catch (Exception e) {
                log.warn("能力包安装：MCP 服务 {} 同步失败", spec.code(), e);
                warnings.add("MCP 服务「" + spec.name() + "」的工具清单同步失败（" + e.getMessage()
                        + "）。服务本身已安装，请到 MCP 服务页手动同步一次，"
                        + "再到技能里勾选需要的工具");
                continue;
            }
            toolIdsByServerKey.put(spec.key(), byName);
        }

        for (PackageManifest.SkillSpec spec : PackageManifest.nullSafe(manifest.skills())) {
            Long skillId = stage1.skillIdByKey().get(spec.key());
            List<PackageManifest.McpRef> refs = PackageManifest.nullSafe(spec.mcpRefs());
            if (skillId == null || refs.isEmpty()) {
                continue;
            }
            Set<Long> mcpToolIds = new LinkedHashSet<>();
            for (PackageManifest.McpRef ref : refs) {
                Map<String, Long> byName = toolIdsByServerKey.get(ref.serverKey());
                if (byName == null) {
                    continue;   // 该 server 同步失败，上面已经记过 warning
                }
                for (String toolName : PackageManifest.nullSafe(ref.toolNames())) {
                    Long id = byName.get(toolName);
                    if (id == null) {
                        // 远端没有这个工具（改名/下线）。⚠️ 必须记 warning：不记的话技能会
                        // 静默少绑一个工具，而 resolve() 查不到 id 时同样是静默跳过
                        warnings.add("技能「" + spec.name() + "」需要的 MCP 工具「" + toolName
                                + "」在远端不存在，可能已改名或下线，该工具未绑定");
                        continue;
                    }
                    mcpToolIds.add(id);
                }
            }
            if (mcpToolIds.isEmpty()) {
                continue;
            }
            try {
                transactionTemplate.execute(status -> {
                    AiSkill skill = skillRepository.findById(skillId).orElse(null);
                    if (skill == null) {
                        return null;
                    }
                    Set<Long> merged = new LinkedHashSet<>(
                            skill.getMcpToolIds() == null ? Set.of() : skill.getMcpToolIds());
                    merged.addAll(mcpToolIds);
                    skill.setMcpToolIds(merged);
                    return skillRepository.save(skill);
                });
            } catch (Exception e) {
                log.warn("能力包安装：技能 {} 回填 MCP 工具绑定失败", spec.name(), e);
                warnings.add("技能「" + spec.name() + "」的 MCP 工具绑定没能自动完成，"
                        + "请到技能页手动勾选");
            }
        }
    }
```

⚠️ **同时要给段二回填加一个 stub**：单测里 `skillRepository.findById(200L)` 没有 stub，默认返回 `Optional.empty()`，回填会静默跳过。在 `PackageInstallServiceTest.setUp()` 里补：

```java
        when(skillRepository.findById(anyLong())).thenAnswer(invocation ->
                java.util.Optional.of(AiSkill.builder().id(invocation.getArgument(0))
                        .name("文档助手").content("x")
                        .mcpToolIds(new java.util.LinkedHashSet<>()).build()));
```

⚠️ 但这样 `MCP同步成功后回填skill绑定` 断言的「最后一次 save 的实体」就是这个 stub 造的对象而不是段一那个——**这正是我们要断言的对象**（回填走的是重新 `findById` 出来的行，不是段一那个已 detached 的实体），所以断言 `last.getMcpToolIds()` 依然成立。

- [ ] **Step 4: 跑单测**

```bash
cd backend && mvn test -Dtest=PackageInstallServiceTest
```

预期：**全部 PASS**（Task 8 的 11 条 + Task 9 的 4 条）。

- [ ] **Step 5: 变异验证 A —— 段二不 catch 必须转红**

临时把 `sync(serverId)` 外面的 `try/catch` 去掉（直接调），重跑。

预期：`MCP同步失败是部分成功而非整体失败` 与 `段二异常不影响段一结果` 双双转红。恢复，确认绿。

- [ ] **Step 6: 变异验证 B —— 缺失工具不记 warning 必须转红**

临时把「远端没有这个工具」那段的 `warnings.add(...)` 删掉（只留 `continue`），重跑。

预期：`远端缺少点名的工具记warning` 转红。恢复，确认全绿。

- [ ] **Step 7: 跑全量单测**

```bash
cd backend && mvn test
```

预期：全部 PASS。

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/service/pkg/PackageInstallService.java \
        backend/src/test/java/com/qingyu/care/service/pkg/PackageInstallServiceTest.java
git commit -m "feat(pkg): 安装段二——MCP 同步与技能绑定回填（部分成功语义）"
```

---

## Task 10: 两个控制器

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/controller/PlatformPackageController.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/ToolPackageController.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/pkg/ToolPackageService.java`（加 `listPublishedDetails()`）

**Interfaces:**
- Consumes: `ToolPackageService`（T7）、`PackageInstallService`（T8/T9）
- Produces（前端 Task 12/13 按这些路径接线）：
  - `GET /v1/platform/packages` → `ApiResponse<List<ToolPackage>>`
  - `GET /v1/platform/packages/{id}` → `ApiResponse<ToolPackage>`（含 manifest 全文）
  - `POST /v1/platform/packages/export` → `ApiResponse<PackageExportResult>`
  - `PUT /v1/platform/packages/{id}` → `ApiResponse<ToolPackage>`
  - `POST /v1/platform/packages/{id}/publish` → `ApiResponse<ToolPackage>`
  - `POST /v1/platform/packages/{id}/archive` → `ApiResponse<ToolPackage>`
  - `DELETE /v1/platform/packages/{id}` → `ApiResponse<Void>`
  - `GET /v1/packages` → `ApiResponse<List<PackageDetailDTO>>`
  - `GET /v1/packages/{code}` → `ApiResponse<PackageDetailDTO>`
  - `POST /v1/packages/{code}/install` → `ApiResponse<PackageInstallResult>`

- [ ] **Step 1: 给 `ToolPackageService` 加 fail-soft 的列表方法**

在 `ToolPackageService` 的 `listPublished()` 之后加：

```java
    /**
     * 租户侧商店列表：逐个转成详情。
     *
     * <p>⚠️ <b>单个包解析失败时跳过并记日志，不让整个列表 500</b>：manifest 是平台管理员可以
     * 手工编辑的文本，一个包被改坏不该让所有租户的商店页面打不开。这条 fail-soft 与
     * {@code ChatServiceImpl} 检索失败时返回空上下文同源——降级到「少一个包」，好过降级到
     * 「整页打不开」。
     */
    public List<PackageDetailDTO> listPublishedDetails() {
        List<PackageDetailDTO> details = new ArrayList<>();
        for (ToolPackage toolPackage : listPublished()) {
            try {
                details.add(detailOf(toolPackage));
            } catch (RuntimeException e) {
                log.warn("能力包 {} 的 manifest 解析失败，已从商店列表中跳过", toolPackage.getCode(), e);
            }
        }
        return details;
    }
```

同时给类加 `@Slf4j`（`import lombok.extern.slf4j.Slf4j;`）。

- [ ] **Step 2: 写平台侧控制器**

创建 `backend/src/main/java/com/qingyu/care/controller/PlatformPackageController.java`：

```java
package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.pkg.PackageExportRequest;
import com.qingyu.care.dto.pkg.PackageExportResult;
import com.qingyu.care.dto.pkg.ToolPackageUpdateRequest;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.service.pkg.ToolPackageService;
import com.qingyu.care.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 能力包的平台侧管理（仅平台管理员）：导出上架、编辑文案、发布/下架。
 *
 * <p>⚠️ <b>每个端点方法都必须第一行调 {@link SecurityUtil#requirePlatformAdmin()}</b>——
 * 本仓库没有声明式角色注解，授权就是这一句。{@code PlatformEndpointGuardTest} 会扫描强制，
 * 漏写当场红。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/platform/packages")
@RequiredArgsConstructor
@Tag(name = "能力包（平台侧）", description = "把调通的工具与技能导出成可安装的能力包")
public class PlatformPackageController {

    private final ToolPackageService packageService;

    @GetMapping
    @Operation(summary = "包列表（含草稿与已下架）")
    public ApiResponse<List<ToolPackage>> list() {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(packageService.listAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "包详情（含 manifest 全文，供编辑与发布前逐字核对）")
    public ApiResponse<ToolPackage> getById(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success(packageService.getById(id));
    }

    @PostMapping("/export")
    @Operation(summary = "从现成配置导出成草稿包")
    @AuditLog(action = "PACKAGE_EXPORT", targetType = "ToolPackage")
    public ApiResponse<PackageExportResult> export(@RequestBody PackageExportRequest request) {
        SecurityUtil.requirePlatformAdmin();
        PackageExportResult result = packageService.export(request);
        return ApiResponse.success("已导出为草稿包，请补完文案与变量说明后发布", result);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新包文案或 manifest")
    @AuditLog(action = "PACKAGE_UPDATE", targetType = "ToolPackage")
    public ApiResponse<ToolPackage> update(@PathVariable Long id,
                                           @RequestBody ToolPackageUpdateRequest request) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success("已更新", packageService.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "发布（发布前会重新校验一遍 manifest）")
    @AuditLog(action = "PACKAGE_PUBLISH", targetType = "ToolPackage")
    public ApiResponse<ToolPackage> publish(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success("已发布，租户现在可以看到并安装它", packageService.publish(id));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "下架（已装出去的副本不受影响）")
    @AuditLog(action = "PACKAGE_ARCHIVE", targetType = "ToolPackage")
    public ApiResponse<ToolPackage> archive(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        return ApiResponse.success("已下架，已安装的副本不受影响", packageService.archive(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除包")
    @AuditLog(action = "PACKAGE_DELETE", targetType = "ToolPackage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        SecurityUtil.requirePlatformAdmin();
        packageService.delete(id);
        return ApiResponse.success("已删除", null);
    }
}
```

- [ ] **Step 3: 写租户侧控制器**

创建 `backend/src/main/java/com/qingyu/care/controller/ToolPackageController.java`：

```java
package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.pkg.PackageDetailDTO;
import com.qingyu.care.dto.pkg.PackageInstallRequest;
import com.qingyu.care.dto.pkg.PackageInstallResult;
import com.qingyu.care.service.pkg.PackageInstallService;
import com.qingyu.care.service.pkg.ToolPackageService;
import com.qingyu.care.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工具商店的租户侧：浏览已发布的能力包、填自己的凭据一键安装。
 *
 * <p>⚠️ 返回的是 {@link PackageDetailDTO} 而<b>不是</b> {@code ToolPackage} 实体：后者带着
 * manifest 全文，里面的 {@code urlTemplate} 与 MCP 地址是平台侧的实现细节（可能含内网地址），
 * 租户拿到既无用又扩大暴露面。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/packages")
@RequiredArgsConstructor
@Tag(name = "工具商店（租户侧）", description = "浏览与安装能力包")
public class ToolPackageController {

    private final ToolPackageService packageService;
    private final PackageInstallService installService;

    @GetMapping
    @Operation(summary = "已发布的能力包列表")
    public ApiResponse<List<PackageDetailDTO>> list() {
        SecurityUtil.requireTenantAdmin();
        return ApiResponse.success(packageService.listPublishedDetails());
    }

    @GetMapping("/{code}")
    @Operation(summary = "包详情（含要填的变量表）")
    public ApiResponse<PackageDetailDTO> detail(@PathVariable String code) {
        SecurityUtil.requireTenantAdmin();
        return ApiResponse.success(packageService.detailOf(packageService.getPublished(code)));
    }

    /**
     * 安装。
     *
     * <p>⚠️ 必须打 {@code @AuditLog}：它一次性在租户名下建出一批<b>带凭据</b>的工具与 MCP 服务，
     * 事后要能查是谁在什么时候装的（spec §4.2）。
     */
    @PostMapping("/{code}/install")
    @Operation(summary = "安装到当前应用")
    @AuditLog(action = "PACKAGE_INSTALL", targetType = "ToolPackage")
    public ApiResponse<PackageInstallResult> install(@PathVariable String code,
                                                     @RequestBody PackageInstallRequest request) {
        SecurityUtil.requireTenantAdmin();
        PackageInstallResult result = installService.install(code, request.getVariables());
        return ApiResponse.success("安装完成", result);
    }
}
```

- [ ] **Step 4: 跑授权哨兵与 PUT 端点哨兵**

```bash
cd backend && mvn test -Dtest='PlatformEndpointGuardTest,PutEndpointBodyRegistrationTest'
```

预期：**全部 PASS**。

- `PlatformEndpointGuardTest` 现在会扫到 7 个新端点，每个都有守卫。
- `PutEndpointBodyRegistrationTest` 扫到 `PlatformPackageController.update` 的 `ToolPackageUpdateRequest`，它已在 Task 7 登记进 `DTO_TO_ENTITY`。

- [ ] **Step 5: 变异验证 —— 新端点漏守卫必须转红**

临时把 `PlatformPackageController.export` 里的 `SecurityUtil.requirePlatformAdmin();` 删掉，重跑 `PlatformEndpointGuardTest`。

预期：**FAIL**，`PlatformPackageController#export 是 /v1/platform 下的端点却没有调用...`。恢复，确认绿。

**这一步是 Task 1 存在的全部意义**——它证明哨兵真的在守护本任务新增的代码，而不只是对既有代码点头。

- [ ] **Step 6: 跑全量单测 + 启动一次确认建表**

```bash
cd backend && mvn test
```

预期：全部 PASS。

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

启动后确认：
1. 日志里没有 `SchemaUpdate` 的 WARN（`tool_package` 表应正常建出）；
2. 访问 `http://localhost:8080/api/swagger-ui.html`，能看到「能力包（平台侧）」与「工具商店（租户侧）」两组端点。

⚠️ 启动需要环境变量 `DASHSCOPE_API_KEY`，且 `docker compose up -d` 起来的 PostgreSQL 在跑。确认完 **Ctrl+C 停掉**——后面跑 `mvn verify` 前必须没有 dev 后端占着 Redis（CLAUDE.md 测试约定那条）。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/qingyu/care/controller/PlatformPackageController.java \
        backend/src/main/java/com/qingyu/care/controller/ToolPackageController.java \
        backend/src/main/java/com/qingyu/care/service/pkg/ToolPackageService.java
git commit -m "feat(pkg): 平台侧与租户侧能力包端点"
```

---

## Task 11: 集成测试（spec §7 的三条）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/PackageInstallIT.java`
- Create: `backend/src/test/java/com/qingyu/care/it/PackageExportIT.java`

**Interfaces:**
- Consumes: Task 10 的全部端点、`AbstractPostgresIT.seedApp(...)`
- Produces: 无

- [ ] **Step 1: 写安装 IT**

创建 `backend/src/test/java/com/qingyu/care/it/PackageInstallIT.java`：

```java
package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ToolPackage;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.PackageStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiSkillRepository;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.ToolPackageRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安装路径上只有真实 HTTP 请求才测得到的三件事：落库归属、{@code scope} 强制、符号引用回填。
 *
 * <p>⚠️ 必须走 MockMvc 而不是直调 service：{@code TenantContext} 由 {@code TenantInterceptor}
 * 写入，Hibernate {@code @Filter} 也只在请求流经它时才启用——而本 IT 的核心正是「归属用的是
 * 上下文，不是 manifest」。
 *
 * @author ruan
 */
class PackageInstallIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ToolPackageRepository packageRepository;
    @Autowired
    private AiToolRepository toolRepository;
    @Autowired
    private AiSkillRepository skillRepository;
    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper om = new ObjectMapper();

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    private Application seedTenantAdmin(String suffix, String username) {
        Application app = seedApp(suffix, "http://localhost");
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username(username).password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        return app;
    }

    /**
     * ⚠️ manifest 里塞了 {@code scope} 与 {@code tenantId}——它们在 record 上压根不存在，
     * 应当被反序列化直接忽略。断言看的是<b>落库归属</b>，不是响应状态码（同
     * {@code CreateTenantInjectionIT} 的口径：越权尝试不报错，行正常建进发起方自己的租户）。
     */
    private ToolPackage seedPublishedPackage(String code) {
        return packageRepository.saveAndFlush(ToolPackage.builder()
                .code(code).name("天气能力包").description("查天气")
                .status(PackageStatus.PUBLISHED)
                .manifest("""
                        {
                          "manifestVersion": 1,
                          "tools": [{
                            "key": "weather_query", "name": "pkg_weather_query",
                            "description": "查询天气",
                            "inputSchema": "{\\"type\\":\\"object\\"}",
                            "httpMethod": "GET",
                            "urlTemplate": "https://${WEATHER_HOST}/v1/weather?city={city}",
                            "timeoutMs": 5000, "maxResponseChars": 4000,
                            "authHeaderVar": "WEATHER_AUTH",
                            "scope": "PLATFORM",
                            "tenantId": 99999
                          }],
                          "mcpServers": [],
                          "skills": [{
                            "key": "weather_skill", "name": "包内天气助手",
                            "description": "回答天气问题",
                            "content": "用户问天气时调用 pkg_weather_query。",
                            "toolKeys": ["weather_query"], "mcpRefs": []
                          }],
                          "variables": [
                            {"name":"WEATHER_HOST","label":"服务地址","required":true,"secret":false,"help":"如 api.qweather.com"},
                            {"name":"WEATHER_AUTH","label":"鉴权头","required":true,"secret":true,"help":"Authorization: Bearer xxx"}
                          ]
                        }
                        """)
                .build());
    }

    private static final String INSTALL_BODY = """
            {"variables":{"WEATHER_HOST":"api.qweather.com",
             "WEATHER_AUTH":"Authorization: Bearer sk-tenant-own"}}""";

    @Test
    void 安装落库的归属与scope都由服务端决定() throws Exception {
        seedPublishedPackage("weather_pack");
        Application app = seedTenantAdmin("PKG_INSTALL", "pkg_install_admin");
        String token = login("pkg_install_admin", "pwd123456");

        mockMvc.perform(post("/v1/packages/weather_pack/install")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INSTALL_BODY))
                .andExpect(status().isOk());

        // ⚠️ 必须按 appId 收窄，不能只按工具名过滤后断言绝对条数：
        // ① `AbstractPostgresIT` 是**静态单例容器**，方法之间不清库、不回滚；
        // ② 测试线程在 MockMvc 请求结束后 `StpUtil.isLogin()` 为 false，`TenantDataAspect`
        //    直接 return 不启用任何过滤器 —— 所以这里的 `findAll()` 是**全库无过滤**的；
        // ③ 本类的 `两个租户先后安装同一个包归属都正确` 会再装两份同名工具，而 JUnit 5 的默认
        //    方法顺序「确定但故意不可预测」（本仓库没有 @TestMethodOrder，也没有全局配置）。
        // 三者叠加：不收窄的话，一旦那条测试排在前面，`hasSize(1)` 就变成 `hasSize(3)`。
        // 「现在能过」只是方法名哈希顺序凑巧，往这个类里加/删/改一个方法名都可能让它无故转红。
        List<AiTool> tools = toolRepository.findAll().stream()
                .filter(t -> "pkg_weather_query".equals(t.getName())
                        && app.getId().equals(t.getAppId()))
                .toList();
        assertThat(tools).hasSize(1);
        AiTool tool = tools.get(0);

        assertThat(tool.getTenantId())
                .as("必须落进发起方租户，manifest 里那个 tenantId:99999 不能生效")
                .isEqualTo(app.getTenant().getId());
        assertThat(tool.getAppId())
                .as("appId 由 X-App-Id 头决定")
                .isEqualTo(app.getId());
        assertThat(tool.getScope())
                .as("scope 必须服务端强制 TENANT，manifest 里那个 PLATFORM 不能生效——"
                        + "否则租户的工具连同其鉴权头会出现在所有租户的共享列表里")
                .isEqualTo(OwnerScope.TENANT);
        assertThat(tool.getAuthHeader())
                .as("落库的是租户自己填的凭据")
                .isEqualTo("Authorization: Bearer sk-tenant-own");
        assertThat(tool.getUrlTemplate())
                .as("行内变量已替换，LLM 占位符保留")
                .isEqualTo("https://api.qweather.com/v1/weather?city={city}");

        // 同上：技能这半也必须按 appId 收窄，理由与顺序脆弱性完全同构
        List<AiSkill> skills = skillRepository.findAll().stream()
                .filter(s -> "包内天气助手".equals(s.getName())
                        && app.getId().equals(s.getAppId()))
                .toList();
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getToolIds())
                .as("skill 绑的必须是新建那一行的 id——漏了这层映射是完全静默的")
                .containsExactly(tool.getId());
        assertThat(skills.get(0).getTenantId()).isEqualTo(app.getTenant().getId());
        assertThat(skills.get(0).getAppId()).isEqualTo(app.getId());
    }

    /**
     * spec §8 第 3 条的自动化版本：两个租户各装同一个包，第二个的归属仍正确。
     *
     * <p>⚠️ 单次安装永远是绿的——上下文泄漏只有连做两次才暴露（同站点爬取第 7 条）。
     * 真机那条手工验证仍然要做（它验的是真实浏览器会话下的连续操作），但这条能把最基本的
     * 「第二次装归属错了」挡在 CI 之前。
     */
    @Test
    void 两个租户先后安装同一个包归属都正确() throws Exception {
        seedPublishedPackage("shared_pack");

        Application appA = seedTenantAdmin("PKG_TENANT_A", "pkg_tenant_a_admin");
        Application appB = seedTenantAdmin("PKG_TENANT_B", "pkg_tenant_b_admin");

        mockMvc.perform(post("/v1/packages/shared_pack/install")
                        .header("satoken", login("pkg_tenant_a_admin", "pwd123456"))
                        .header("X-App-Id", appA.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(INSTALL_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/packages/shared_pack/install")
                        .header("satoken", login("pkg_tenant_b_admin", "pwd123456"))
                        .header("X-App-Id", appB.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(INSTALL_BODY))
                .andExpect(status().isOk());

        List<AiTool> installed = toolRepository.findAll().stream()
                .filter(t -> "pkg_weather_query".equals(t.getName()))
                .filter(t -> appA.getId().equals(t.getAppId()) || appB.getId().equals(t.getAppId()))
                .toList();

        assertThat(installed).hasSize(2);
        assertThat(installed).anySatisfy(t -> {
            assertThat(t.getAppId()).isEqualTo(appA.getId());
            assertThat(t.getTenantId()).isEqualTo(appA.getTenant().getId());
        });
        assertThat(installed).anySatisfy(t -> {
            assertThat(t.getAppId()).isEqualTo(appB.getId());
            assertThat(t.getTenantId()).isEqualTo(appB.getTenant().getId());
        });
    }

    @Test
    void 重名时整体拒绝且一行都不建() throws Exception {
        seedPublishedPackage("conflict_pack");
        Application app = seedTenantAdmin("PKG_CONFLICT", "pkg_conflict_admin");
        String token = login("pkg_conflict_admin", "pwd123456");

        // 先装一次
        mockMvc.perform(post("/v1/packages/conflict_pack/install")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(INSTALL_BODY))
                .andExpect(status().isOk());
        long skillCountAfterFirst = skillRepository.findAll().stream()
                .filter(s -> "包内天气助手".equals(s.getName())).count();

        // 再装一次：必须被冲突检测整体拒绝
        MvcResult r = mockMvc.perform(post("/v1/packages/conflict_pack/install")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(INSTALL_BODY))
                .andExpect(status().isOk())   // 业务异常是 HTTP 200 + body.code
                .andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt())
                .as("业务异常走 HTTP 200 + body.code，前端必须判 res.code").isNotEqualTo(200);
        assertThat(body.path("message").asString()).contains("pkg_weather_query");

        long skillCountAfterSecond = skillRepository.findAll().stream()
                .filter(s -> "包内天气助手".equals(s.getName())).count();
        assertThat(skillCountAfterSecond)
                .as("整体拒绝意味着一行都不该建出来")
                .isEqualTo(skillCountAfterFirst);
    }

    @Test
    void 草稿包对租户不可见也装不了() throws Exception {
        packageRepository.saveAndFlush(ToolPackage.builder()
                .code("draft_only").name("草稿包").status(PackageStatus.DRAFT)
                .manifest("{\"manifestVersion\":1,\"tools\":[],\"mcpServers\":[],\"skills\":[],\"variables\":[]}")
                .build());
        Application app = seedTenantAdmin("PKG_DRAFT", "pkg_draft_admin");
        String token = login("pkg_draft_admin", "pwd123456");

        MvcResult r = mockMvc.perform(post("/v1/packages/draft_only/install")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"variables\":{}}"))
                .andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isNotEqualTo(200);
    }
}
```

- [ ] **Step 2: 写导出 IT（钉住 OSIV 那条）**

创建 `backend/src/test/java/com/qingyu/care/it/PackageExportIT.java`：

```java
package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导出<b>不能吃掉平台管理员自己的凭据</b>（spec §4.1 / §6.2 / §7 的 IT ③）。
 *
 * <p>⚠️ <b>必须走真实 MockMvc</b>：本项目未配 {@code spring.jpa.open-in-view}（即 Boot 默认的
 * {@code true}），OSIV 只在真实请求里才让实体保持受管——直调 service 时实体本来就是分离的，
 * 断言会<b>静默退化成永真</b>。
 *
 * <p>⚠️ <b>光有导出请求本身还不足以触发脏检查刷库</b>：脏字段要有一次<b>非只读的写事务提交</b>
 * 才会被 flush（{@code SharedToolIT} 的类注释里记着两种不成立的直觉方案与实测过程）。导出路径
 * 本身有一次 {@code packageRepository.save(...)}，那就是那次写事务——所以本 IT 不需要
 * {@code SharedToolIT} 那样的 FlushProbe 拦截器，但仍保留一个 postHandle 探针作为纵深防御：
 * 万一将来 {@code export} 改成不落库（如「预览」端点），断言不会因此静默失去意义。
 *
 * @author ruan
 */
@Import(PackageExportIT.FlushProbeConfig.class)
class PackageExportIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AiToolRepository toolRepository;
    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper om = new ObjectMapper();

    /** 在导出请求收尾前补一次对 ai_tool 的写事务，逼出脏检查 flush（同 SharedToolIT）。 */
    @TestConfiguration
    static class FlushProbeConfig implements WebMvcConfigurer {

        @Autowired
        private AiToolRepository probeRepository;

        @Bean
        HandlerInterceptor packageExportFlushProbe() {
            return new HandlerInterceptor() {
                @Override
                public void postHandle(HttpServletRequest request, HttpServletResponse response,
                                       Object handler, ModelAndView modelAndView) {
                    probeRepository.save(AiTool.builder()
                            .name("__pkg_export_probe__").description("flush probe")
                            .urlTemplate("http://flush-probe.invalid")
                            .enabled(true).build());
                }
            };
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(packageExportFlushProbe())
                    .addPathPatterns("/v1/platform/packages/export");
        }
    }

    /**
     * 用内置的平台管理员账号登录。
     *
     * <p>⚠️ 不自己播种平台管理员：{@code User.tenant} 确实可空（null 即平台管理员），但账号是由
     * 启动时的数据初始化播种的，{@code LoginIT} 已经在用这一对 {@code admin}/{@code admin123}。
     * 自己再建一个只会多一份要维护的密码常量，且要处理 {@code username} 的唯一约束。
     */
    private String loginPlatformAdmin() throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    @Test
    @DisplayName("导出之后，平台管理员那条工具的 authHeader 在库里仍然完好")
    void 导出不抹掉源工具的凭据() throws Exception {
        Application app = seedApp("PKG_EXPORT", "http://localhost");

        AiTool platformTool = AiTool.builder()
                .name("export_source_tool").description("导出源")
                .urlTemplate("https://api.example.com/x?city={city}")
                .httpMethod("GET").timeoutMs(5000).maxResponseChars(4000)
                .scope(OwnerScope.TENANT).enabled(true).build();
        platformTool.setAuthHeader("Authorization: Bearer platform-secret-keepme");
        platformTool.setTenantId(null);          // 平台管理员建的行
        platformTool.setAppId(app.getId());
        AiTool seeded = toolRepository.saveAndFlush(platformTool);

        String token = loginPlatformAdmin();

        MvcResult r = mockMvc.perform(post("/v1/platform/packages/export")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"export_pack\",\"name\":\"导出测试包\","
                                + "\"toolIds\":[" + seeded.getId() + "]}"))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).as("导出应当成功").isEqualTo(200);

        String manifest = body.path("data").path("toolPackage").path("manifest").asString();
        assertThat(manifest)
                .as("manifest 里绝不能出现明文凭据")
                .doesNotContain("platform-secret-keepme");
        assertThat(manifest).contains("EXPORT_SOURCE_TOOL_AUTH");

        // ⚠️ 核心：源实体的凭据不能被脏检查刷成 null
        AiTool reloaded = toolRepository.findById(seeded.getId()).orElseThrow();
        assertThat(reloaded.getAuthHeader())
                .as("导出全程只读实体；写一句 setAuthHeader(null) 会在 OSIV 下把平台管理员"
                        + "自己的凭据真的删掉")
                .isEqualTo("Authorization: Bearer platform-secret-keepme");
    }

    @Test
    @DisplayName("勾中属于某个租户的工具 → 整个导出被拒绝")
    void 拒绝把租户私有配置导出成平台包() throws Exception {
        Application app = seedApp("PKG_EXPORT_TENANT", "http://localhost");

        AiTool tenantTool = AiTool.builder()
                .name("tenant_private_tool").description("租户私有")
                .urlTemplate("https://api.example.com/y")
                .httpMethod("GET").timeoutMs(5000).maxResponseChars(4000)
                .scope(OwnerScope.TENANT).enabled(true).build();
        tenantTool.setTenantId(app.getTenant().getId());
        tenantTool.setAppId(app.getId());
        AiTool seeded = toolRepository.saveAndFlush(tenantTool);

        String token = loginPlatformAdmin();

        MvcResult r = mockMvc.perform(post("/v1/platform/packages/export")
                        .header("satoken", token)
                        .header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"leak_pack\",\"name\":\"越权导出\","
                                + "\"toolIds\":[" + seeded.getId() + "]}"))
                .andExpect(status().isOk()).andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isNotEqualTo(200);
        assertThat(body.path("message").asString()).contains("tenant_private_tool");
    }

    @Test
    void 非平台管理员访问平台包端点被拒() throws Exception {
        Application app = seedApp("PKG_EXPORT_DENY", "http://localhost");
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username("pkg_export_tenant_admin").password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        MvcResult login = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pkg_export_tenant_admin\",\"password\":\"pwd123456\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = om.readTree(login.getResponse().getContentAsString())
                .path("data").path("token").asString();

        MvcResult r = mockMvc.perform(post("/v1/platform/packages/export")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"denied_pack\",\"name\":\"越权\",\"toolIds\":[]}"))
                .andReturn();

        var body = om.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt())
                .as("租户管理员不该能操作平台包端点")
                .isEqualTo(403);
    }
}
```

- [ ] **Step 3: 停掉 dev 后端后跑两条 IT**

```bash
# 先确认 :8080 上没有在跑的 dev 后端（否则 WebhookDeliveryIT 会因共用 Redis 消费组必红）
cd backend && mvn verify -Dit.test='PackageInstallIT,PackageExportIT'
```

预期：**全部 PASS**。

⚠️ 若 `安装落库的归属与scope都由服务端决定` 因 manifest 里的 `"scope": "PLATFORM"` / `"tenantId": 99999` 导致**解析失败**（Jackson 对未知属性的策略取决于配置），那说明本仓库开启了 `FAIL_ON_UNKNOWN_PROPERTIES`。此时**改测试的断言而不是改实现**：把它拆成两条——一条断言「带未知字段的 manifest 被拒绝」，另一条用干净 manifest 断言归属与 scope。两种行为都是安全的，测试要如实描述实际那种。

- [ ] **Step 4: 变异验证 —— 导出改成 setter 置空必须转红**

临时在 `PackageManifestBuilder.build()` 的工具循环里加一行 `tool.setAuthHeader(null);`，重跑 `PackageExportIT`。

预期：`导出不抹掉源工具的凭据` **FAIL**（`reloaded.getAuthHeader()` 变成 null）。

**这一步不能跳过**：`PackageManifestBuilderTest.导出不修改源实体` 那条单测在这次变异下**也会转红**，但它证明不了 OSIV 那一层——本步验的是「真实请求 + 脏检查 flush」这条链路真的被覆盖了。恢复代码，确认绿。

- [ ] **Step 5: 跑全量 verify**

```bash
cd backend && mvn verify
```

预期：全部 PASS（单测 + 全部 IT）。

⚠️ 首次跑会构建 zhparser 镜像，耗时数分钟。

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/com/qingyu/care/it/PackageInstallIT.java \
        backend/src/test/java/com/qingyu/care/it/PackageExportIT.java
git commit -m "test(pkg): 安装归属与导出凭据保全的集成测试"
```

---

## Task 12: 前端 API 层 + 平台侧包管理页

**Files:**
- Create: `frontend/src/api/toolPackage.ts`
- Create: `frontend/src/components/pkg/PackageExportDialog.tsx`
- Create: `frontend/src/pages/PlatformPackagePage.tsx`
- Create: `frontend/src/pages/PlatformPackagePage.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`

**Interfaces:**
- Consumes: Task 10 的全部端点
- Produces（Task 13 直接复用）：`toolPackageService`（含租户侧三个方法）、类型 `ToolPackage` / `PackageDetail` / `PackageVariable` / `PackageInstallResult` / `PackageExportResult`

- [ ] **Step 1: 写 API 层**

创建 `frontend/src/api/toolPackage.ts`：

```ts
import api from './client';
import type { ApiResponse } from '../types';

/** 能力包（对应后端 ToolPackage 实体，仅平台侧接口会返回它——含 manifest 全文） */
export interface ToolPackage {
    id: number;
    code: string;
    name: string;
    description?: string | null;
    status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
    manifest: string;
    createdAt?: string | null;
    updatedAt?: string | null;
}

/** 安装时要租户填的一个变量 */
export interface PackageVariable {
    name: string;
    label: string;
    required: boolean;
    /** true 时前端用 password 输入框，且永不回显 */
    secret: boolean;
    help?: string | null;
}

/** 租户侧看到的包详情（刻意不含 manifest 全文，见后端 PackageDetailDTO 的注释） */
export interface PackageDetail {
    code: string;
    name: string;
    description?: string | null;
    variables: PackageVariable[];
    toolNames: string[];
    mcpServerNames: string[];
    skillNames: string[];
}

export interface PackageExportResult {
    toolPackage: ToolPackage;
    /** 不阻断导出的告警，界面必须显眼展示——它们是发布前逐字核对的线索 */
    warnings: string[];
}

export interface PackageInstallResult {
    toolNames: string[];
    mcpServerNames: string[];
    skillNames: string[];
    /** 部分成功的说明（如某个 MCP 服务同步失败，需要手动同步后再勾选工具） */
    warnings: string[];
}

export interface PackageExportPayload {
    code: string;
    name: string;
    description?: string;
    toolIds?: number[];
    mcpServerIds?: number[];
    skillIds?: number[];
}

/**
 * 能力包接口。
 *
 * ⚠️ 路径不带 `/v1`——axios 实例的 baseURL 已经是 `/api/v1`（见 `api/client.ts`），
 * 写成 `/v1/platform/packages` 会拼成 `/api/v1/v1/...`（同 `api/platform.ts` 里那条注释）。
 */
export const toolPackageService = {
    // ===== 平台侧 =====
    listAll: (): Promise<ApiResponse<ToolPackage[]>> =>
        api.get('/platform/packages'),

    getById: (id: number): Promise<ApiResponse<ToolPackage>> =>
        api.get(`/platform/packages/${id}`),

    exportPackage: (body: PackageExportPayload): Promise<ApiResponse<PackageExportResult>> =>
        api.post('/platform/packages/export', body),

    update: (id: number, body: Partial<Pick<ToolPackage, 'name' | 'description' | 'manifest'>>)
        : Promise<ApiResponse<ToolPackage>> =>
        api.put(`/platform/packages/${id}`, body),

    publish: (id: number): Promise<ApiResponse<ToolPackage>> =>
        api.post(`/platform/packages/${id}/publish`),

    archive: (id: number): Promise<ApiResponse<ToolPackage>> =>
        api.post(`/platform/packages/${id}/archive`),

    remove: (id: number): Promise<ApiResponse<void>> =>
        api.delete(`/platform/packages/${id}`),

    // ===== 租户侧 =====
    listStore: (): Promise<ApiResponse<PackageDetail[]>> =>
        api.get('/packages'),

    detail: (code: string): Promise<ApiResponse<PackageDetail>> =>
        api.get(`/packages/${code}`),

    install: (code: string, variables: Record<string, string>)
        : Promise<ApiResponse<PackageInstallResult>> =>
        api.post(`/packages/${code}/install`, { variables }),
};
```

- [ ] **Step 2: 写导出对话框**

创建 `frontend/src/components/pkg/PackageExportDialog.tsx`：

```tsx
import React, { useEffect, useState } from 'react';
import { aiToolService, aiSkillService } from '@/api/chat-tool';
import { mcpApi } from '@/api/mcp';
import { toolPackageService } from '@/api/toolPackage';
import type { AiTool, AiSkill } from '@/api/chat-tool';
import type { McpServer } from '@/api/mcp';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
    Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    /** 导出成功后由父组件刷新列表并展示 warnings */
    onExported: (warnings: string[]) => void;
}

/**
 * 从现成配置导出成草稿包。
 *
 * ⚠️ 勾选列表来自**当前选中应用**下的工具/MCP 服务/技能。平台管理员必须先在上方选中自己的
 * 应用——选了别人租户的应用时，后端会拒绝导出（那些行的 tenant_id 非空）。
 *
 * @author ruan
 */
const PackageExportDialog: React.FC<Props> = ({ open, onOpenChange, onExported }) => {
    const { toast } = useToast();
    const [tools, setTools] = useState<AiTool[]>([]);
    const [servers, setServers] = useState<McpServer[]>([]);
    const [skills, setSkills] = useState<AiSkill[]>([]);
    const [code, setCode] = useState('');
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [toolIds, setToolIds] = useState<number[]>([]);
    const [serverIds, setServerIds] = useState<number[]>([]);
    const [skillIds, setSkillIds] = useState<number[]>([]);
    const [isSaving, setIsSaving] = useState(false);

    useEffect(() => {
        if (!open) return;
        // 每次打开都复位，否则上一次的勾选会残留（「组件级 state + 每次打开时复位」那类坑）
        setCode('');
        setName('');
        setDescription('');
        setToolIds([]);
        setServerIds([]);
        setSkillIds([]);
        void (async () => {
            try {
                const [toolRes, serverRes, skillRes] = await Promise.all([
                    aiToolService.list(), mcpApi.listServers(), aiSkillService.list(),
                ]);
                throwIfFailed(toolRes);
                throwIfFailed(serverRes);
                throwIfFailed(skillRes);
                setTools(toolRes.data ?? []);
                setServers(serverRes.data ?? []);
                setSkills(skillRes.data ?? []);
            } catch (error: unknown) {
                toast({ variant: 'destructive', title: '加载可选项失败', description: getErrorMessage(error) });
            }
        })();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open]);

    const toggle = (ids: number[], setIds: (v: number[]) => void, id: number) =>
        setIds(ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]);

    const handleExport = async () => {
        try {
            setIsSaving(true);
            const res = await toolPackageService.exportPackage({
                code: code.trim(), name: name.trim(), description: description.trim(),
                toolIds, mcpServerIds: serverIds, skillIds,
            });
            throwIfFailed(res);
            onExported(res.data?.warnings ?? []);
            onOpenChange(false);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '导出失败', description: getErrorMessage(error) });
        } finally {
            setIsSaving(false);
        }
    };

    // ⚠️ label 返回 ReactNode 而不是 string：勾选项要显示「名字 + 灰色描述」两段，
    // 用 JSX + Tailwind 间距做视觉分隔。**不要**在模板字符串里塞全角空格当分隔符——
    // 那会触发 eslint 的 no-irregular-whitespace（该规则专防不可见异常空白字符混入代码，
    // 全角空格在等宽字体下与普通空格几乎无法区分），而压掉它等于在代码里留一个隐形字符。
    const checkboxList = <T extends { id: number }>(
        items: T[], selected: number[], setSelected: (v: number[]) => void,
        label: (item: T) => React.ReactNode, emptyHint: string,
    ) => (
        items.length === 0
            ? <p className="text-sm text-muted-foreground py-2">{emptyHint}</p>
            : <div className="space-y-1 max-h-40 overflow-y-auto border rounded-md p-2">
                {items.map((item) => (
                    <label key={item.id} className="flex items-center gap-2 text-sm cursor-pointer">
                        <input
                            type="checkbox"
                            checked={selected.includes(item.id)}
                            onChange={() => toggle(selected, setSelected, item.id)}
                        />
                        <span>{label(item)}</span>
                    </label>
                ))}
            </div>
    );

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="max-w-2xl">
                <DialogHeader>
                    <DialogTitle>导出能力包</DialogTitle>
                </DialogHeader>
                <div className="space-y-3">
                    <p className="text-sm text-muted-foreground">
                        勾选你已经调通、并用真实对话验证过的配置。凭据会自动升格成安装时要租户填的变量，
                        明文不会写进包里。
                    </p>
                    <Input placeholder="包标识（小写字母开头，如 weather_pack）"
                           value={code} onChange={(e) => setCode(e.target.value)} />
                    <Input placeholder="包名称（展示给租户看）"
                           value={name} onChange={(e) => setName(e.target.value)} />
                    <Input placeholder="一句话说明这个包能做什么"
                           value={description} onChange={(e) => setDescription(e.target.value)} />

                    <div>
                        <p className="text-sm font-medium mb-1">工具</p>
                        {checkboxList(tools, toolIds, setToolIds,
                            (t) => (
                                <>
                                    <span>{t.name}</span>
                                    <span className="ml-2 text-muted-foreground">{t.description}</span>
                                </>
                            ), '当前应用下没有工具')}
                    </div>
                    <div>
                        <p className="text-sm font-medium mb-1">MCP 服务</p>
                        {checkboxList(servers, serverIds, setServerIds,
                            (s) => `${s.name}（${s.code}）`, '当前应用下没有 MCP 服务')}
                    </div>
                    <div>
                        <p className="text-sm font-medium mb-1">技能</p>
                        {checkboxList(skills, skillIds, setSkillIds,
                            (s) => s.name, '当前应用下没有技能')}
                    </div>
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
                    <Button onClick={handleExport} disabled={isSaving}>
                        {isSaving ? '导出中…' : '导出为草稿'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default PackageExportDialog;
```

⚠️ `@/api/chat-tool` 导出的服务对象名是 `aiToolService` 与 `aiSkillService`（已核实）；`mcpApi.listServers()` 同理（`@/api/mcp`）。三者的 `list()` 返回的都是 `ApiResponse<T[]>`，所以都要过 `throwIfFailed`。

- [ ] **Step 3: 写平台侧页面**

创建 `frontend/src/pages/PlatformPackagePage.tsx`：

```tsx
import React, { useEffect, useState } from 'react';
import { Package, Upload, Trash2, Send, Archive } from 'lucide-react';
import { toolPackageService } from '@/api/toolPackage';
import type { ToolPackage } from '@/api/toolPackage';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import {
    Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import PackageExportDialog from '@/components/pkg/PackageExportDialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

const statusLabel: Record<ToolPackage['status'], string> = {
    DRAFT: '草稿',
    PUBLISHED: '已发布',
    ARCHIVED: '已下架',
};

/**
 * 能力包管理（平台管理员）。
 *
 * ⚠️ 发布前必须逐字看一遍 manifest——凭据升格与密钥启发式都是启发式，没法自动化
 * （spec §4.1）。所以编辑对话框里的 manifest 文本框既是编辑器也是那份「预览」。
 *
 * @author ruan
 */
const PlatformPackagePage: React.FC = () => {
    const { toast } = useToast();
    const [list, setList] = useState<ToolPackage[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [exportOpen, setExportOpen] = useState(false);
    const [warnings, setWarnings] = useState<string[]>([]);
    const [editing, setEditing] = useState<ToolPackage | null>(null);
    const [draftName, setDraftName] = useState('');
    const [draftDescription, setDraftDescription] = useState('');
    const [draftManifest, setDraftManifest] = useState('');
    const [isSaving, setIsSaving] = useState(false);

    const reload = async () => {
        try {
            setIsLoading(true);
            const res = await toolPackageService.listAll();
            throwIfFailed(res);
            setList(res.data ?? []);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '加载失败', description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 同 SharedToolPage：取数函数开头会 setIsLoading(true)
        reload();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const openEditor = async (pkg: ToolPackage) => {
        try {
            const res = await toolPackageService.getById(pkg.id);
            throwIfFailed(res);
            const full = res.data;
            setEditing(full);
            setDraftName(full.name);
            setDraftDescription(full.description ?? '');
            setDraftManifest(prettify(full.manifest));
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '打开失败', description: getErrorMessage(error) });
        }
    };

    /** manifest 是一行紧凑 JSON，格式化后人才读得下去；解析不了就原样显示 */
    const prettify = (json: string): string => {
        try {
            return JSON.stringify(JSON.parse(json), null, 2);
        } catch {
            return json;
        }
    };

    const handleSave = async () => {
        if (!editing) return;
        try {
            setIsSaving(true);
            const res = await toolPackageService.update(editing.id, {
                name: draftName, description: draftDescription, manifest: draftManifest,
            });
            throwIfFailed(res);
            toast({ title: '已保存' });
            setEditing(null);
            await reload();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '保存失败', description: getErrorMessage(error) });
        } finally {
            setIsSaving(false);
        }
    };

    const handlePublish = async (pkg: ToolPackage) => {
        try {
            const res = await toolPackageService.publish(pkg.id);
            throwIfFailed(res);
            toast({ title: '已发布', description: '租户现在可以在工具商店里看到它' });
            await reload();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '发布失败', description: getErrorMessage(error) });
        }
    };

    const handleArchive = async (pkg: ToolPackage) => {
        try {
            const res = await toolPackageService.archive(pkg.id);
            throwIfFailed(res);
            toast({ title: '已下架', description: '已安装的副本不受影响' });
            await reload();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '下架失败', description: getErrorMessage(error) });
        }
    };

    const handleDelete = async (pkg: ToolPackage) => {
        try {
            const res = await toolPackageService.remove(pkg.id);
            throwIfFailed(res);
            toast({ title: '已删除' });
            await reload();
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '删除失败', description: getErrorMessage(error) });
        }
    };

    return (
        <div className="p-6 space-y-4 overflow-y-auto h-full">
            <div className="flex items-center justify-between">
                <div>
                    <h3 className="text-lg font-semibold flex items-center gap-2">
                        <Package className="w-5 h-5" /> 能力包
                    </h3>
                    <p className="text-sm text-muted-foreground">
                        把你已经调通的工具与技能导出成包，租户填自己的凭据即可一键安装
                    </p>
                </div>
                <Button onClick={() => setExportOpen(true)}>
                    <Upload className="w-4 h-4 mr-1" /> 导出能力包
                </Button>
            </div>

            {warnings.length > 0 && (
                <div className="border border-destructive/50 bg-destructive/10 rounded-md p-3 space-y-1">
                    <p className="text-sm font-medium text-destructive">
                        导出完成，但有 {warnings.length} 条需要你确认的地方：
                    </p>
                    <ul className="text-sm list-disc pl-5 space-y-0.5">
                        {warnings.map((w) => <li key={w}>{w}</li>)}
                    </ul>
                    <p className="text-xs text-muted-foreground">
                        发布前请点开包、逐字看一遍 manifest——凭据识别与密钥扫描都是启发式，只能靠人最终确认。
                    </p>
                </div>
            )}

            {isLoading ? (
                <p className="text-sm text-muted-foreground">加载中…</p>
            ) : list.length === 0 ? (
                <p className="text-sm text-muted-foreground">还没有能力包，点右上角导出一个。</p>
            ) : (
                <div className="space-y-2">
                    {list.map((pkg) => (
                        <div key={pkg.id} className="border rounded-lg p-3 flex items-center justify-between">
                            <div className="min-w-0">
                                <div className="flex items-center gap-2">
                                    <span className="font-medium">{pkg.name}</span>
                                    <Badge variant={pkg.status === 'PUBLISHED' ? 'default' : 'secondary'}>
                                        {statusLabel[pkg.status]}
                                    </Badge>
                                    <span className="text-xs text-muted-foreground">{pkg.code}</span>
                                </div>
                                <p className="text-sm text-muted-foreground truncate">{pkg.description}</p>
                            </div>
                            <div className="flex items-center gap-2 shrink-0">
                                <Button variant="outline" size="sm" onClick={() => openEditor(pkg)}>
                                    编辑与预览
                                </Button>
                                {pkg.status !== 'PUBLISHED' && (
                                    <Button size="sm" onClick={() => handlePublish(pkg)}>
                                        <Send className="w-4 h-4 mr-1" /> 发布
                                    </Button>
                                )}
                                {pkg.status === 'PUBLISHED' && (
                                    <Button variant="outline" size="sm" onClick={() => handleArchive(pkg)}>
                                        <Archive className="w-4 h-4 mr-1" /> 下架
                                    </Button>
                                )}
                                <Button variant="outline" size="sm" onClick={() => handleDelete(pkg)}>
                                    <Trash2 className="w-4 h-4" />
                                </Button>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <PackageExportDialog
                open={exportOpen}
                onOpenChange={setExportOpen}
                onExported={async (w) => {
                    setWarnings(w);
                    toast({ title: '已导出为草稿包', description: '补完文案与变量说明后再发布' });
                    await reload();
                }}
            />

            <Dialog open={editing !== null} onOpenChange={(v) => !v && setEditing(null)}>
                <DialogContent className="max-w-3xl">
                    <DialogHeader>
                        <DialogTitle>编辑能力包</DialogTitle>
                    </DialogHeader>
                    <div className="space-y-3">
                        <Input value={draftName} onChange={(e) => setDraftName(e.target.value)}
                               placeholder="包名称" />
                        <Input value={draftDescription} onChange={(e) => setDraftDescription(e.target.value)}
                               placeholder="包说明" />
                        <div>
                            <p className="text-sm font-medium mb-1">
                                manifest（发布前请逐字核对：不该有明文密钥，变量的标签与说明要是人话）
                            </p>
                            <textarea
                                className="w-full h-80 font-mono text-xs border rounded-md p-2 bg-background"
                                value={draftManifest}
                                onChange={(e) => setDraftManifest(e.target.value)}
                            />
                        </div>
                    </div>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => setEditing(null)}>取消</Button>
                        <Button onClick={handleSave} disabled={isSaving}>
                            {isSaving ? '保存中…' : '保存'}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
};

export default PlatformPackagePage;
```

⚠️ `components/ui/` 下**没有** textarea 封装，所以这里用原生 `<textarea>` 配 Tailwind class。

- [ ] **Step 4: 接线路由与导航**

`frontend/src/App.tsx`：加 import 与路由（放在 `platform/tools` 之后）：

```tsx
import PlatformPackagePage from './pages/PlatformPackagePage';
```
```tsx
          <Route path="platform/packages" element={<PlatformPackagePage />} />
```

`frontend/src/layouts/MainLayout.tsx`：在「平台管理」分组的 `共享工具` 之后加一条，并把 `Package` 加进 lucide 的 import 列表：

```tsx
                { label: '能力包', path: '/platform/packages', icon: Package },
```

- [ ] **Step 5: 写前端测试**

创建 `frontend/src/pages/PlatformPackagePage.test.tsx`：

```tsx
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { ToolPackage } from '@/api/toolPackage';
import { toolPackageService } from '@/api/toolPackage';
import PlatformPackagePage from './PlatformPackagePage';

/**
 * 写操作的业务错误不静默（「HTTP 200 + body.code」那条全局约定）。
 *
 * 挑「发布」作为代表路径：它是本页最短的一条写操作——点一下按钮直达
 * `toolPackageService.publish`，断言能精准落在「业务错误有没有被吐出来」上。
 *
 * ⚠️ 变异实验：把 `handlePublish` 里的 `throwIfFailed(res);` 删掉，本条必须转红。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));

// 导出对话框会拉三份列表，与本条要验的东西无关；桩成「只在 open 时渲染」的极简组件。
// ⚠️ 不能桩成 () => null——它是靠 onExported 回调驱动父组件的，桩空会让那条路径静默零覆盖。
vi.mock('@/components/pkg/PackageExportDialog', () => ({
    default: ({ open, onExported }: { open: boolean; onExported: (w: string[]) => void }) =>
        open ? <button onClick={() => onExported(['告警一'])}>触发导出完成</button> : null,
}));

vi.mock('@/api/toolPackage', () => ({
    toolPackageService: {
        listAll: vi.fn(),
        getById: vi.fn(),
        exportPackage: vi.fn(),
        update: vi.fn(),
        publish: vi.fn(),
        archive: vi.fn(),
        remove: vi.fn(),
    },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200, message: 'success', data, timestamp: '2026-08-25T00:00:00',
});
const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400, message, data: null as unknown as T, timestamp: '2026-08-25T00:00:00',
});

const draft: ToolPackage = {
    id: 1, code: 'weather_pack', name: '天气能力包', description: '查天气',
    status: 'DRAFT', manifest: '{"manifestVersion":1}',
};

const flush = async () => { await act(async () => { await Promise.resolve(); }); };

const renderPage = async () => {
    const result = render(<PlatformPackagePage />);
    await flush();
    return result;
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(toolPackageService.listAll).mockReset().mockResolvedValue(ok([draft]));
    vi.mocked(toolPackageService.publish).mockReset();
});

afterEach(() => { vi.clearAllMocks(); });

describe('PlatformPackagePage 写操作的业务错误不静默', () => {
    it('发布时后端返业务错误 → 弹 destructive toast 且不刷新列表', async () => {
        vi.mocked(toolPackageService.publish)
            .mockResolvedValue(fail<ToolPackage>('manifest 不是合法的 JSON'));

        await renderPage();
        expect(screen.getByText('天气能力包')).toBeInTheDocument();

        fireEvent.click(screen.getByText('发布'));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '发布失败',
            description: 'manifest 不是合法的 JSON',
        }));
        expect(toolPackageService.listAll).toHaveBeenCalledTimes(1);
    });

    it('发布成功 → 弹成功 toast 并重新拉列表', async () => {
        vi.mocked(toolPackageService.publish)
            .mockResolvedValue(ok({ ...draft, status: 'PUBLISHED' }));

        await renderPage();
        fireEvent.click(screen.getByText('发布'));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({ title: '已发布' }));
        expect(toastSpy).not.toHaveBeenCalledWith(
            expect.objectContaining({ variant: 'destructive' }));
        expect(toolPackageService.listAll).toHaveBeenCalledTimes(2);
    });

    it('导出返回的 warnings 必须显眼展示，不能只在 toast 里一闪而过', async () => {
        await renderPage();
        fireEvent.click(screen.getByText('导出能力包'));
        await flush();
        fireEvent.click(screen.getByText('触发导出完成'));
        await flush();

        expect(screen.getByText('告警一')).toBeInTheDocument();
    });
});
```

- [ ] **Step 6: 跑前端测试与 lint、构建**

```bash
cd frontend && pnpm test && pnpm lint && pnpm build
```

预期：测试全 PASS；lint 的既有 error 基线**零新增**；build 成功。

⚠️ 若 lint 报新的 error（如 `react-hooks/exhaustive-deps`），按既有页面的写法加同款 `eslint-disable-next-line` 注释并注明理由——**不要为了过 lint 改变行为**。

- [ ] **Step 7: 变异验证 —— 去掉 `throwIfFailed` 必须转红**

临时把 `PlatformPackagePage.handlePublish` 里的 `throwIfFailed(res);` 删掉，重跑 `pnpm test`。

预期：第一条测试 FAIL（一次 toast 都没调）。恢复，确认绿。

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/toolPackage.ts \
        frontend/src/components/pkg/PackageExportDialog.tsx \
        frontend/src/pages/PlatformPackagePage.tsx \
        frontend/src/pages/PlatformPackagePage.test.tsx \
        frontend/src/App.tsx frontend/src/layouts/MainLayout.tsx
git commit -m "feat(pkg): 平台侧能力包管理页与导出对话框"
```

---

## Task 13: 租户侧工具商店页 + 安装对话框

**Files:**
- Create: `frontend/src/components/pkg/PackageInstallDialog.tsx`
- Create: `frontend/src/pages/PackageStorePage.tsx`
- Create: `frontend/src/pages/PackageStorePage.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`

**Interfaces:**
- Consumes: Task 12 的 `toolPackageService.listStore` / `detail` / `install`、类型 `PackageDetail` / `PackageVariable` / `PackageInstallResult`
- Produces: 无（终端页面）

- [ ] **Step 1: 写安装对话框**

创建 `frontend/src/components/pkg/PackageInstallDialog.tsx`：

```tsx
import React, { useEffect, useState } from 'react';
import { toolPackageService } from '@/api/toolPackage';
import type { PackageDetail, PackageInstallResult } from '@/api/toolPackage';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
    Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

interface Props {
    pkg: PackageDetail | null;
    onClose: () => void;
    onInstalled: (result: PackageInstallResult) => void;
}

/**
 * 填变量并安装。
 *
 * ⚠️ `secret` 变量用 password 输入框且**永不回显**——同本仓库其它凭据字段的既有约定。
 *
 * @author ruan
 */
const PackageInstallDialog: React.FC<Props> = ({ pkg, onClose, onInstalled }) => {
    const { toast } = useToast();
    const [values, setValues] = useState<Record<string, string>>({});
    const [isInstalling, setIsInstalling] = useState(false);

    useEffect(() => {
        // 换一个包时必须清空上一个包填的值，否则会把别的包的凭据带过去
        setValues({});
    }, [pkg?.code]);

    if (!pkg) return null;

    const handleInstall = async () => {
        try {
            setIsInstalling(true);
            const res = await toolPackageService.install(pkg.code, values);
            throwIfFailed(res);
            onInstalled(res.data);
            onClose();
        } catch (error: unknown) {
            // 名字冲突、必填项没填、没选应用……全都是这条路回来的
            toast({ variant: 'destructive', title: '安装失败', description: getErrorMessage(error) });
        } finally {
            setIsInstalling(false);
        }
    };

    return (
        <Dialog open onOpenChange={(v) => !v && onClose()}>
            <DialogContent className="max-w-lg">
                <DialogHeader>
                    <DialogTitle>安装「{pkg.name}」</DialogTitle>
                </DialogHeader>
                <div className="space-y-3">
                    <div className="text-sm text-muted-foreground space-y-1">
                        <p>{pkg.description}</p>
                        <p>
                            将安装到当前应用：
                            {pkg.toolNames.length > 0 && ` ${pkg.toolNames.length} 个工具`}
                            {pkg.mcpServerNames.length > 0 && ` ${pkg.mcpServerNames.length} 个 MCP 服务`}
                            {pkg.skillNames.length > 0 && ` ${pkg.skillNames.length} 个技能`}
                        </p>
                    </div>

                    {pkg.variables.length === 0 ? (
                        <p className="text-sm text-muted-foreground">这个包不需要填写任何配置。</p>
                    ) : (
                        pkg.variables.map((v) => (
                            <div key={v.name} className="space-y-1">
                                <label className="text-sm font-medium" htmlFor={`pkg-var-${v.name}`}>
                                    {v.label}
                                    {v.required && <span className="text-destructive ml-0.5">*</span>}
                                </label>
                                <Input
                                    id={`pkg-var-${v.name}`}
                                    type={v.secret ? 'password' : 'text'}
                                    value={values[v.name] ?? ''}
                                    onChange={(e) =>
                                        setValues({ ...values, [v.name]: e.target.value })}
                                />
                                {v.help && <p className="text-xs text-muted-foreground">{v.help}</p>}
                            </div>
                        ))
                    )}
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={onClose}>取消</Button>
                    <Button onClick={handleInstall} disabled={isInstalling}>
                        {isInstalling ? '安装中…' : '安装'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default PackageInstallDialog;
```

- [ ] **Step 2: 写商店页**

创建 `frontend/src/pages/PackageStorePage.tsx`：

```tsx
import React, { useEffect, useState } from 'react';
import { Store } from 'lucide-react';
import { toolPackageService } from '@/api/toolPackage';
import type { PackageDetail, PackageInstallResult } from '@/api/toolPackage';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import PackageInstallDialog from '@/components/pkg/PackageInstallDialog';
import { useToast } from '@/hooks/use-toast';
import { getErrorMessage, throwIfFailed } from '@/lib/apiResult';

/**
 * 工具商店（租户侧）：挑一个包 → 填自己的凭据 → 装好能用。
 *
 * ⚠️ 安装结果里的 warnings 必须**留在页面上**而不是只弹一次 toast：最典型的那条
 * 「MCP 服务同步失败，请手动同步后到技能里勾选工具」是要人照着做的，一闪而过等于没说。
 *
 * @author ruan
 */
const PackageStorePage: React.FC = () => {
    const { toast } = useToast();
    const [list, setList] = useState<PackageDetail[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [installing, setInstalling] = useState<PackageDetail | null>(null);
    const [lastResult, setLastResult] = useState<PackageInstallResult | null>(null);

    const reload = async () => {
        try {
            setIsLoading(true);
            const res = await toolPackageService.listStore();
            throwIfFailed(res);
            setList(res.data ?? []);
        } catch (error: unknown) {
            toast({ variant: 'destructive', title: '加载失败', description: getErrorMessage(error) });
        } finally {
            setIsLoading(false);
        }
    };

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- 同 SharedToolPage
        reload();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return (
        <div className="p-6 space-y-4 overflow-y-auto h-full">
            <div>
                <h3 className="text-lg font-semibold flex items-center gap-2">
                    <Store className="w-5 h-5" /> 工具商店
                </h3>
                <p className="text-sm text-muted-foreground">
                    挑一个能力包，填上你自己的凭据即可安装到当前应用。装下来的是你自己的副本，
                    之后可以随意修改、停用或删除。
                </p>
            </div>

            {lastResult && (
                <div className="border rounded-md p-3 space-y-1 bg-muted/40">
                    <p className="text-sm font-medium">安装完成</p>
                    <p className="text-sm text-muted-foreground">
                        新增 {lastResult.toolNames.length} 个工具、
                        {lastResult.mcpServerNames.length} 个 MCP 服务、
                        {lastResult.skillNames.length} 个技能
                    </p>
                    {lastResult.warnings.length > 0 && (
                        <ul className="text-sm list-disc pl-5 text-destructive space-y-0.5">
                            {lastResult.warnings.map((w) => <li key={w}>{w}</li>)}
                        </ul>
                    )}
                </div>
            )}

            {isLoading ? (
                <p className="text-sm text-muted-foreground">加载中…</p>
            ) : list.length === 0 ? (
                <p className="text-sm text-muted-foreground">暂时还没有上架的能力包。</p>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    {list.map((pkg) => (
                        <div key={pkg.code} className="border rounded-lg p-4 space-y-2">
                            <div className="flex items-start justify-between gap-2">
                                <div className="min-w-0">
                                    <p className="font-medium">{pkg.name}</p>
                                    <p className="text-sm text-muted-foreground">{pkg.description}</p>
                                </div>
                                <Button size="sm" onClick={() => setInstalling(pkg)}>安装</Button>
                            </div>
                            <div className="flex flex-wrap gap-1">
                                {pkg.toolNames.map((n) => (
                                    <Badge key={`t-${n}`} variant="secondary">{n}</Badge>
                                ))}
                                {pkg.mcpServerNames.map((n) => (
                                    <Badge key={`m-${n}`} variant="secondary">MCP: {n}</Badge>
                                ))}
                                {pkg.skillNames.map((n) => (
                                    <Badge key={`s-${n}`} variant="outline">技能: {n}</Badge>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <PackageInstallDialog
                pkg={installing}
                onClose={() => setInstalling(null)}
                onInstalled={(result) => {
                    setLastResult(result);
                    toast({ title: '安装完成', description: '可以到工具与技能页查看装下来的副本' });
                }}
            />
        </div>
    );
};

export default PackageStorePage;
```

- [ ] **Step 3: 接线路由与导航**

`frontend/src/App.tsx`：加 import 与路由（放在 `ai-skills` 之后）：

```tsx
import PackageStorePage from './pages/PackageStorePage';
```
```tsx
          <Route path="packages" element={<RequireApp><PackageStorePage /></RequireApp>} />
```

⚠️ **必须包 `<RequireApp>`**：安装要往当前应用里建行，没选应用时后端会直接拒绝（`PackageInstallService.requireInstallContext`）。让用户点进一个空页面再被拒，不如一开始就显示「请先选择应用」。

`frontend/src/layouts/MainLayout.tsx`：在「应用配置 → AI 模型」子分组的 `技能` 之后加一条，并把 `Store` 加进 lucide import：

```tsx
                    { label: '工具商店', path: '/packages', icon: Store, roles: ['TENANT_ADMIN', 'PLATFORM_ADMIN'] },
```

⚠️ 加 `roles` 是因为后端 `ToolPackageController` 全部端点要 `requireTenantAdmin()`——`TENANT_MEMBER` 点进去只会看到 403，不如不显示（同「人工工作台」按角色过滤的既有做法）。

- [ ] **Step 4: 写前端测试**

创建 `frontend/src/pages/PackageStorePage.test.tsx`：

```tsx
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { PackageDetail, PackageInstallResult } from '@/api/toolPackage';
import { toolPackageService } from '@/api/toolPackage';
import PackageStorePage from './PackageStorePage';

/**
 * 安装路径上两件必须成立的事：
 * 1. 业务错误（名字冲突 / 必填项没填 / 没选应用）弹 destructive toast，不静默；
 * 2. 部分成功的 warnings **留在页面上**，不是只弹一次 toast——那条「请手动同步后再勾选工具」
 *    是要人照着做的。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastSpy }) }));

vi.mock('@/api/toolPackage', () => ({
    toolPackageService: {
        listStore: vi.fn(),
        detail: vi.fn(),
        install: vi.fn(),
    },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200, message: 'success', data, timestamp: '2026-08-25T00:00:00',
});
const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400, message, data: null as unknown as T, timestamp: '2026-08-25T00:00:00',
});

const pkg: PackageDetail = {
    code: 'weather_pack',
    name: '天气能力包',
    description: '查天气',
    variables: [
        { name: 'WEATHER_AUTH', label: '鉴权头', required: true, secret: true, help: '形如 Authorization: Bearer xxx' },
    ],
    toolNames: ['weather_query'],
    mcpServerNames: [],
    skillNames: ['天气助手'],
};

const flush = async () => { await act(async () => { await Promise.resolve(); }); };

const renderPage = async () => {
    const result = render(<PackageStorePage />);
    await flush();
    return result;
};

beforeEach(() => {
    toastSpy.mockReset();
    vi.mocked(toolPackageService.listStore).mockReset().mockResolvedValue(ok([pkg]));
    vi.mocked(toolPackageService.install).mockReset();
});

afterEach(() => { vi.clearAllMocks(); });

describe('PackageStorePage 安装路径', () => {
    it('安装时后端返业务错误 → 弹 destructive toast', async () => {
        vi.mocked(toolPackageService.install)
            .mockResolvedValue(fail<PackageInstallResult>('以下名称在当前应用里已存在：工具「weather_query」'));

        await renderPage();
        fireEvent.click(screen.getByText('安装'));
        await flush();

        // 对话框里那个「安装」按钮（列表上那个是同名的，取最后一个）
        const buttons = screen.getAllByText('安装');
        fireEvent.click(buttons[buttons.length - 1]);
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '安装失败',
            description: '以下名称在当前应用里已存在：工具「weather_query」',
        }));
    });

    it('secret 变量用 password 输入框，永不明文回显', async () => {
        await renderPage();
        fireEvent.click(screen.getByText('安装'));
        await flush();

        const input = screen.getByLabelText(/鉴权头/) as HTMLInputElement;
        expect(input.type).toBe('password');
    });

    it('部分成功的 warnings 留在页面上，不是只弹一次 toast', async () => {
        vi.mocked(toolPackageService.install).mockResolvedValue(ok<PackageInstallResult>({
            toolNames: ['weather_query'],
            mcpServerNames: ['Context7'],
            skillNames: ['天气助手'],
            warnings: ['MCP 服务「Context7」的工具清单同步失败，请到 MCP 服务页手动同步一次'],
        }));

        await renderPage();
        fireEvent.click(screen.getByText('安装'));
        await flush();
        const buttons = screen.getAllByText('安装');
        fireEvent.click(buttons[buttons.length - 1]);
        await flush();

        expect(screen.getByText(/手动同步一次/)).toBeInTheDocument();
    });
});
```

⚠️ 「取最后一个同名按钮」这种写法脆弱。**跑起来若定位不到，改成给对话框的确认按钮加一个稳定的可访问名**（如把文案改成「确认安装」），并同步改组件——**改文案而不是改断言去迁就**。

- [ ] **Step 5: 跑前端测试与 lint、构建**

```bash
cd frontend && pnpm test && pnpm lint && pnpm build
```

预期：全部 PASS，lint error 基线零新增。

- [ ] **Step 6: 变异验证 —— warnings 不上屏必须转红**

临时把 `PackageStorePage` 里 `{lastResult.warnings.length > 0 && (...)}` 整块删掉，重跑。

预期：第三条测试 FAIL。恢复，确认绿。

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/pkg/PackageInstallDialog.tsx \
        frontend/src/pages/PackageStorePage.tsx \
        frontend/src/pages/PackageStorePage.test.tsx \
        frontend/src/App.tsx frontend/src/layouts/MainLayout.tsx
git commit -m "feat(pkg): 租户侧工具商店页与安装对话框"
```

---

## Task 14: 文档与手工验证清单

**Files:**
- Modify: `CLAUDE.md`
- Modify: `scripts/manual-smoke-hints.sh`
- Modify: `docs/使用手册.md`
- Modify: `docs/superpowers/specs/2026-08-25-tool-package-store-design.md`（修一处笔误）

**Interfaces:**
- Consumes: 前 13 个任务的全部产物
- Produces: 无代码

- [ ] **Step 1: 往 `CLAUDE.md` 的「架构要点」加一节**

在「### OpenAPI 工具导入（`service/openapi/` + `service/tool/UrlTemplateFiller`）」这一节**之后**插入：

```markdown
### 工具商店（能力包，`service/pkg/` + `dto/pkg/`）

平台管理员把自己已经调通的一组工具 + skill **导出成包**上架，租户挑一个包、填自己的凭据、
一键安装成**自己名下的副本**（可改可停用可删）。三个纯函数类关住全部规则：
`PackageManifestBuilder`（实体 → manifest + warnings）、`PackageManifestParser`（JSON ↔ manifest
+ 结构校验）、`PackageVariableApplier`（变量填充 + 残留检测）；两个 `@Service` 只做取数与落库。
设计文档：`docs/superpowers/specs/2026-08-25-tool-package-store-design.md`。

⚠️ Java 包名用 `pkg` 而非 `package`——后者是关键字，`service.package` 编译不过。

⚠️ 容易写错的地方：

1. **⚠️ 变量语法 `${VAR}` 与 `UrlTemplateFiller` 的 `{param}` 正则重叠，替换后必须断言不残留
   `${`**。`UrlTemplateFiller.PLACEHOLDER` 是 `\{([^{}]+)}`，它**会匹配 `${VAR}` 里的 `{VAR}`**。
   一个没被替换掉的变量落库之后：残留在 **query 段**会让该段被**整段静默删除**（鉴权参数就此
   消失 → 第三方 401 → 模型拿着错误响应继续编答案，全程无异常无日志）；残留在 **path 段**会
   抛「缺少必填参数 VAR」——一个 LLM 永远填不出来的参数名，排查的人会一路去查 inputSchema。
   同理，被行内引用的变量**取值本身不得含 `{` 或 `}`**，否则租户填一个 `evil{city}host.com`
   就凭空造出一个假占位符。两条都由 `PackageVariableApplier` 硬拦。
2. **安装前必须断言 `TenantContext` 的 `tenantId` 与 `appId` 都非空**。`appId` 为空时建出来的行
   `app_id` 落 null，而 `ChatToolService.resolve()` 是 filter-bound 查询——`NULL` 对任何应用都
   匹配不上，**装了等于没装且完全静默**。`tenantId` 为空的来源是
   `SecurityUtil.requireTenantAdmin()` **放行平台管理员**，而他登录后 `tenantId` 恒为 null，
   落库会写成 `tenant_id IS NULL`（「平台共享」的语义位置）。
3. **导出必须显式拒绝 `tenantId != null` 的行**。`TenantOwnershipGuard.mutableInCurrentContext`
   在上下文 tenantId 为空时**无条件放行**，而平台管理员正是这种情况——于是
   `chatToolService.getTool(任意 id)` 能取到**任意租户**的工具。不拦住，平台管理员切到某个租户
   的应用再导出，就会把该租户私有的工具配置（name / description / urlTemplate）随包发给所有
   租户。判据取 `tenantId == null` 而非应用范围：平台管理员在任何应用下建的行 `tenant_id` 都是
   null，这条精确等于「只导出平台管理员自己建的配置」。
4. **导出全程只读实体，绝不 setter 置空**。本项目未配 `spring.jpa.open-in-view`（即 Boot 默认
   `true`），OSIV 让查出来的实体在整个请求期保持受管——为了「不导出明文凭据」而写一句
   `setAuthHeader(null)` 会被脏检查刷回库，**把平台管理员自己的凭据真的删掉**（同
   `SharedCredentialMasker` 那次，靠 `em.detach` 才闭合）。⚠️ 直调 service 的单测**证明不了**
   这条（实体本来就是分离的，断言静默退化成永真），由走真实 MockMvc 的 `PackageExportIT` 钉住。
5. **引用一律用包内 `key`，绝不用 id**。`AiSkill` 绑的是 `toolIds`，而安装时工具是新建行、id
   全新；安装期间维护 `key → 新 id` 映射再回填。漏掉这层映射**完全静默**：`resolve()` 查不到
   就跳过，界面上 skill 仍显示绑着 N 个工具（同「MCP 接入」第 7 条同一失效模式）。
6. **`sync()` 绝不进安装事务**，安装因此分两段：段一（事务内）建行，段二（事务外）同步 MCP
   工具清单并回填 skill 绑定。段二失败**不回滚段一**——这是有意的部分成功，界面必须把
   「请手动同步后再到技能里勾选工具」显眼地留在页面上，不能只弹一次 toast。
7. **manifest 里没有 `scope` / `tenantId` / `appId`，安装时服务端强制写入**。「`scope` 是客户端
   可控字段」这条已经真实栽过一次（见上面「平台共享工具」第 1 条），manifest 是另一条能把值送
   进实体的路径——**字段压根不存在比判空更可靠**。
8. **`tool_package.manifest` 必须是 `text`**，长度由包内容决定、无可预估上限（同
   `McpTool.description` 那次 context7 接不进来）。⚠️ 存量库另需迁移 SQL——`ddl-auto: update`
   只加列、不改已有列的类型。
9. **冲突检测按 name 整体拒绝，不自动改名**；工具名的已占用集合**必须算上平台共享工具**——
   `resolve()` 遇到重名会静默跳过其中一个，与共享工具重名的工具装进去了也挂不上 LLM。
10. **不建安装记录表**（spec §2.3）：一次性、装完断开。有了它就会长出「已安装」状态，而那个
    状态是假的——租户随手删掉装下来的工具，表里仍写着已安装。

**四道扫描哨兵覆盖本链路**：`PlatformEndpointGuardTest`（`/v1/platform/**` 每个端点必须调
`requirePlatformAdmin()`）、`PackageManifestFieldCoverageTest`（实体可变字段必须进 manifest 或
进白名单并挑一个类别）、以及既有的 `UpdateRequestFieldCoverageTest` / `UpdateRequestPropagationTest`
（`ToolPackageUpdateRequest` 已登记）。⚠️ `UpdateRequestFieldCoverageTest.DTO_TO_ENTITY` 现在用
`Map.ofEntries` 而非 `Map.of`——后者最多 10 对，已经用满。

### 改动工具商店 / 能力包链路后必须手工验证

自动化测试覆盖不到的四条：

1. **凭据真的是租户填的那个，且出站 URL 形态正确**：装一个含 HTTP 工具 + skill 的包（工具的
   `urlTemplate` 里要带一个 `${VAR}` 变量）→ 发一句触发工具的对话 → 确认**出站请求头里带的是
   安装时填的值**、**URL 里既没有残留 `${`、也没有半截的 query 段**。凭据若在某个环节被清掉，
   症状是第三方返 401、模型拿着错误响应继续编答案，**全程无异常无日志**（同平台共享工具那条）。
2. **MCP 包的两条路径**：正常 url 装一次，确认 sync 成功后 skill 的 `mcpToolIds` 真绑上了；再用
   坏 url 装一次，确认是**部分成功**（工具与 skill 都在、提示明确说了要手动同步），不是整体失败。
3. **上下文不泄漏**：连续给**两个不同租户**各装同一个包，确认第二个租户的行 `tenant_id` /
   `app_id` 仍正确。`PackageInstallIT` 已覆盖 MockMvc 层的同款场景，真机这条验的是**真实浏览器
   会话下连做两次**（切账号、切应用），只有真机连做才暴露会话级的上下文残留（同站点爬取第 7 条）。
4. **manifest 预览逐字核对**：导出一个 URL 里带 `?apikey=` 的工具，确认界面弹出了密钥告警；再
   人工通读一遍 manifest，确认没有明文凭据、变量的 label 与 help 是人话。凭据识别与密钥扫描都是
   启发式，**没法自动化**——同噪声剔除，这是唯一的验收方式。
```

- [ ] **Step 2: 往 `scripts/manual-smoke-hints.sh` 的 `RULES` 加一条**

在 `'widget/src/core/|^cli/::### 改动 widget 协议层或 CLI 后必须手工验证'` **之后**加：

```bash
  'service/pkg/|dto/pkg/|ToolPackage|PackageManifest|PackageInstall|PackageStorePage|PlatformPackagePage|api/toolPackage\.ts::### 改动工具商店 / 能力包链路后必须手工验证'
```

- [ ] **Step 3: 跑脚本自检**

```bash
./scripts/manual-smoke-hints.sh master...HEAD
```

预期：**exit 0**，且输出里包含「### 改动工具商店 / 能力包链路后必须手工验证」这一节标题。

⚠️ 若报 `✗ 规则引用的章节在 CLAUDE.md 里不存在`，说明 Step 1 里的标题与 Step 2 里的字符串**不
逐字相同**（全角括号、空格都算）——这正是脚本自校验要防的静默失效，按提示改到一致。

- [ ] **Step 4: 往 `docs/使用手册.md` 加一节**

在讲「MCP 服务」或「工具」的章节之后加（具体位置按该文件现有的章节顺序，插在同一层级）：

```markdown
## 工具商店（能力包）

如果平台方已经把某套外部能力调通并上架成「能力包」，你不必自己填工具、登记 MCP 服务或导入
OpenAPI ——在左侧「应用配置 → 工具商店」里挑一个包，填上你自己的账号凭据，点安装即可。

**安装前请先在左上角选好应用**：能力包是装到某个具体应用下的。

**装下来的是你自己的副本**：工具、MCP 服务、技能都会出现在对应的页面里，可以随意修改、停用或
删除，不受平台方后续改动影响。反过来说，平台方更新了包，你已经装好的这份也不会自动跟着变。

**名字冲突时会整体拒绝**：如果包里的工具名或技能名在当前应用里已经存在，安装会失败并列出冲突项
——请先把已有的重命名或删除，再安装。这样做是为了避免自动改名之后，你在列表里找不到自己认识的
名字。

**MCP 类型的包可能需要多一步**：包里如果含 MCP 服务，安装时会尝试同步一次它的工具清单。若同步
失败（网络不通、凭据不对），服务本身仍会装好，页面上会提示你到「MCP 服务」页手动同步一次，再到
「技能」页勾选需要的工具。
```

- [ ] **Step 5: 修 spec 里那处笔误**

编辑 `docs/superpowers/specs/2026-08-25-tool-package-store-design.md` 的 §9「已知限制」，把：

```markdown
- **变量只能出现在三个白名单字段**：需要租户填私有部署地址之类的场景已覆盖（`urlTemplate` / `url`
```

改成：

```markdown
- **变量只能出现在四个白名单字段**（`AiTool.authHeader` / `AiTool.urlTemplate` /
  `McpServer.authHeader` / `McpServer.url`，与 §3.5 一致）：需要租户填私有部署地址之类的场景已覆盖（`urlTemplate` / `url`
```

⚠️ §3.5 正文列的就是四个，§9 那句是笔误。留着会让后来的人以为少了一个字段。

- [ ] **Step 5.5: 修正 `PlatformEndpointGuardTest` 类注释的两处（执行期发现，计划原本没有）**

这两条都是本特性执行过程中暴露出来的，不修的话会让下一个人踩坑：

**① 补上「方法签名必须单行」这条未记录的限制。** `methodBody()` 里那行
`!line.stripTrailing().endsWith("{")` 要求**方法名与行尾 `{` 在同一行**——换行的长签名会让它找不到
方法体、触发防空转断言报错。Task 10 实现平台包控制器时就撞上了（把两行签名改成单行才通过）。
⚠️ 这**不是漏检而是报错**（有防空转断言兜着），方向安全；但它实际上强制了一种代码风格，而长签名
换行是常见写法。在类注释的「已知盲区」那段之后补一条：

```java
 * <p>⚠️ <b>另一条限制：方法签名必须写在一行内</b>（方法名与行尾 {@code {} 同行）。
 * {@link #methodBody} 靠「这一行既含方法名、又以 <code>{</code> 结尾」来定位方法体，
 * 换行的长签名会让它找不到、进而触发防空转断言报错。⚠️ 这是<b>报错而非漏检</b>，方向安全，
 * 但它实际上强制了一种代码风格——新增平台端点时若签名很长，把它挤到一行里
 * （同 {@code PlatformController.updateSharedModel} 的既有写法），别改这个扫描器去迁就。
```

**② 更新过时的端点计数。** 类注释里「全仓 12 个平台端点里 11 个守卫齐备」是写这条哨兵时的快照，
本特性新增 7 个平台端点后已经不准。⚠️ **不要改成写死另一个数字**——它同样会过时。改成不带数字的
表述：

```java
 * <p>补它的时机是新增 {@code PlatformPackageController} 那一刻：写这条哨兵时全仓的平台端点
 * <b>守卫齐备、只有一个刻意例外</b>（见 {@link #ALLOWLIST}），即<b>没有存量漏洞可修</b>，
 * 它守的完全是「以后新增平台端点时忘了写那一行」。
```

（原文里那句带「12 个 / 11 个」的话整句替换掉。）

- [ ] **Step 6: 全量回归**

```bash
# 停掉 dev 后端（:8080）后再跑
cd backend && mvn verify
cd ../frontend && pnpm test && pnpm lint && pnpm build
```

预期：全部 PASS。

⚠️ **widget 不受本特性影响**（没动 `widget/src/`），不需要重新 build `widget.js`。`buildFreshness.test.ts` 也因此不会红。

- [ ] **Step 7: Commit**

```bash
git add CLAUDE.md scripts/manual-smoke-hints.sh docs/使用手册.md \
        docs/superpowers/specs/2026-08-25-tool-package-store-design.md
git commit -m "docs(pkg): 工具商店的架构要点、手工验证清单与使用手册"
```

- [ ] **Step 8: 按清单做四条真机手工验证**

起 dev 后端与前端，按上面写进 CLAUDE.md 的四条逐条走一遍。

⚠️ **这一步不能跳过也不能假装做过**。本仓库有明确记录：多媒体消息那次真机冒烟抓到的两个缺陷，
八轮代码评审全部漏掉；MCP 接入那次补做真机验证挖出 3 个缺陷。四条里第 1 条（凭据真的出站）最
关键——它是唯一能证明「凭据没在某个环节被清掉」的方式，而那个失效是全程无异常无日志的。

验完把结果如实记在收尾提交的 commit message 里：**过了几条、哪条没过、为什么**。

---

## 收尾

- [ ] **跑一遍改动提示脚本，确认没有遗漏的手工验证节**

```bash
./scripts/manual-smoke-hints.sh master...HEAD
```

本分支应当只命中「工具商店 / 能力包」这一节（若还命中了别的，说明改到了那条链路的文件——去读
那一节并验证）。

- [ ] **请求代码评审**

按 `superpowers:requesting-code-review` 走一次全分支终审。

⚠️ **本仓库的历史记录明确指出：最严重的缺陷藏在任务接缝处，只有全分支终审抓得到**（子代理驱动
执行那次，8 处计划缺陷没有一处是我自己发现的）。终审时重点看这几条接缝：

1. **Task 5 的 key 生成 → Task 8 的 key 查表**：`toolKeyById` 用的 key 与 builder 编出来的 key
   是不是同一套（中文名 slug、冲突加序号那两条规则都要对上）。
2. **Task 4 的 `VARIABLE_REF` → Task 6 的 `substitute`**：是不是真的共用了同一个常量（各写一份
   正则的后果是「导出时写进去的引用，安装时认不出来」→ 残留 → query 段静默删除）。
3. **Task 8 的 `Stage1` → Task 9 的回填**：`skillIdByKey` / `serverIdByKey` 的 key 与 manifest
   里的 key 是否一致。
4. **Task 12 的 API 类型 → Task 10 的响应结构**：`PackageExportResult.toolPackage` 这个字段名在
   前后端是否一致（record 的组件名直接决定 JSON 字段名）。

- [ ] **按 `superpowers:finishing-a-development-branch` 决定如何合入**








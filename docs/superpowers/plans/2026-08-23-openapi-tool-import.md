# OpenAPI Schema 自动生成工具表单 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 租户管理员给一份 OpenAPI 3.x schema（粘贴或填 URL），解析出可用端点，勾选后批量生成 `AiTool` 行。

**Architecture:** 后端两个端点——`parse` 只解析不落库、`import` 收前端编辑后的候选列表批量落库。解析链拆成四个职责单一的组件，其中 `OpenApiToolMapper` 是**纯函数**（给定 `OpenAPI` 对象与配置，输出完全确定），承载全部映射规则与本特性绝大多数测试。同批把 `HttpToolCallback.fillTemplate` 抽成 `UrlTemplateFiller` 并修掉「未填占位符原样发出去」这个既有缺陷。

**Tech Stack:** Spring Boot 4.1 / JDK 17 / swagger-core-jakarta（已在 compile 类路径，随 springdoc 传递）/ Jackson 3（`tools.jackson`，项目主栈）/ React 19 + vitest。

**Spec:** `docs/superpowers/specs/2026-08-23-openapi-tool-import-design.md`

## Global Constraints

这些约束对**每个**任务都生效，不在单个任务里重复：

- **语言**：对话、代码注释、commit 信息一律简体中文。新建文件的 `@author` 写 `ruan`。
- **测试分层**：新增测试**优先写成单测**（Surefire，纯 Mockito / 纯函数，不启 Spring 容器、不需要 Docker）。IT 类名以 `IT` 结尾、放 `it/` 包、继承 `com.qingyu.care.support.AbstractPostgresIT`，用 `mvn verify` 跑（需 Docker Desktop）。
- **跑 IT 前必须先停掉本机在跑的 dev 后端**（`:8080`），否则 `WebhookDeliveryIT` 必红（与 dev 后端共用 Redis 消费组）。
- **Jackson 两套并存，别混用**：解析 OpenAPI 文本用 swagger-core 自带的 mapper（Jackson 2，`com.fasterxml.jackson`）；我们自己产出的 JSON 文本用项目的 `tools.jackson.databind.ObjectMapper`（Jackson 3）。**新代码里 `import` 写错包会静默用错 mapper**。
- **工具名截断到 64**，不是 `AiTool.name` 的列宽 100。64 是 OpenAI 兼容端点对函数名的限制；按 100 截断能存库能显示，但挂给 LLM 时被模型侧拒绝，症状是「工具配好了却从来不被调用」，无异常无日志。
- **前端所有写操作必须显式判 `res.code !== 200`**（用 `@/lib/apiResult` 的 `throwIfFailed`）。本项目业务异常是「HTTP 200 + `body.code`」约定，axios 不会 reject，不判就静默吞掉、用户点按钮毫无反应。
- **提交粒度**：每个任务至少一次提交，commit message 用中文，说明「为什么」而不只是「做了什么」。
- **分支**：已在 `feat/openapi-tool-import` 分支上（spec 提交 `63a8ef8` 在此分支）。不要切回 master。

---

## 文件结构

**新建（后端）**
| 文件 | 职责 |
|---|---|
| `service/tool/UrlTemplateFiller.java` | URL 模板填充纯函数（从 `HttpToolCallback` 抽出并修行为） |
| `service/openapi/OpenApiSchemaParser.java` | 文本 → `OpenAPI` 模型；JSON/YAML 判别；3.x 版本校验 |
| `service/openapi/OpenApiToolMapper.java` | `OpenAPI` + 配置 → 候选列表 + 两级 warnings（**纯函数**） |
| `service/openapi/OpenApiSchemaFetcher.java` | URL → schema 文本（委托 `WebPageFetcher.downloadRaw`） |
| `service/openapi/OpenApiImportService.java` | 编排 + 冲突标记 + 批量落库（唯一碰 DB 的） |
| `dto/openapi/OpenApiParseRequest.java` | `{schemaText | schemaUrl, baseUrl?}` |
| `dto/openapi/OpenApiToolCandidate.java` | 候选工具（parse 出、import 收，同一类型） |
| `dto/openapi/OpenApiParseResponse.java` | `{candidates, globalWarnings, truncated}` |
| `dto/openapi/OpenApiImportRequest.java` | `{candidates, baseUrl, authHeader, timeoutMs, maxResponseChars, enabled}` |

**修改（后端）**
| 文件 | 改动 |
|---|---|
| `service/tool/HttpToolCallback.java` | 删私有 `fillTemplate`，改调 `UrlTemplateFiller.fill` |
| `service/web/WebPageFetcher.java` | Content-Type 白名单参数化（**加重载，保留旧签名**） |
| `controller/AiToolController.java` | 加 `/openapi/parse` 与 `/openapi/import` |
| `pom.xml` | 显式声明 swagger-core-jakarta / swagger-models-jakarta（不写 version） |

**新建（前端）**
| 文件 | 职责 |
|---|---|
| `src/components/tools/OpenApiImportDialog.tsx` | 三步导入对话框 |
| `src/components/tools/OpenApiImportDialog.test.tsx` | 写操作回归测试 |

**修改（前端）**：`src/api/chat-tool.ts`（加两个 service 方法与类型）、`src/pages/ToolConfigPage.tsx`（加入口按钮）

**文档**：`CLAUDE.md`（新增「改动 OpenAPI 工具导入链路后必须手工验证」一节 + 更新工具相关说明）、`SaaS平台规划.md`（勾选 5.2 该项）、`scripts/manual-smoke-hints.sh`（新增路径→章节映射）

---

## Task 1: `UrlTemplateFiller`（运行时行为修正）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/tool/UrlTemplateFiller.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/tool/HttpToolCallback.java`（删 `fillTemplate` 私有方法，改调用点）
- Test: `backend/src/test/java/com/qingyu/care/service/tool/UrlTemplateFillerTest.java`

**Interfaces:**
- Consumes: 无（本任务是起点）
- Produces: `public static String UrlTemplateFiller.fill(String template, Map<String, Object> args)` — 抛 `IllegalArgumentException` 当 path 段有未填占位符。Task 3 的 `urlTemplate` 生成规则与它配对。

**为什么先做这个**：它与 OpenAPI 无关，是独立的既有缺陷修复；先落地能让后续任务在正确的运行时语义上设计 `urlTemplate`。

- [ ] **Step 1: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/tool/UrlTemplateFillerTest.java`：

```java
package com.qingyu.care.service.tool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URL 模板填充的边界。
 *
 * <p>本类守的是一个**既有缺陷的修复**：旧实现遍历 args 逐个 replace，LLM 没填的占位符会
 * 原样留在 URL 里被真的发出去（{@code ?limit={limit}}），可选 query 参数因此在本项目里
 * 一直是坏的——手工配置也一样坏，只是没人系统性地用过。
 *
 * <p>⚠️ 变异验证（实施时必须真跑一次）：把 {@code fill} 里「段内仍有占位符则跳过该段」
 * 改回「保留该段」，{@code 可选query参数未填时整段删除} 必须转红。
 *
 * @author ruan
 */
class UrlTemplateFillerTest {

    // ===== path 段 =====

    @Test
    void path占位符被URL编码后替换() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/orders/{orderId}", Map.of("orderId", "A B&C"));

        assertThat(url).isEqualTo("https://api.example.com/orders/A+B%26C");
    }

    @Test
    void path占位符未填时抛异常且点名缺哪个参数() {
        // 旧行为是把 {orderId} 字面量发出去（服务端必然 404，LLM 收到 404 无从判断原因）。
        // 新行为不发请求，直接把「缺少必填参数 orderId」经 HttpToolCallback 的 catch 返回给
        // LLM，它能据此补参数重试。
        assertThatThrownBy(() -> UrlTemplateFiller.fill(
                "https://api.example.com/orders/{orderId}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId");
    }

    @Test
    void 无占位符的URL原样返回() {
        String url = UrlTemplateFiller.fill("https://api.example.com/health", Map.of());

        assertThat(url).isEqualTo("https://api.example.com/health");
    }

    // ===== query 段 =====

    @Test
    void 可选query参数未填时整段删除() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/search?q={q}&limit={limit}", Map.of("q", "鞋"));

        assertThat(url).isEqualTo("https://api.example.com/search?q=%E9%9E%8B");
    }

    @Test
    void query参数全部未填时连问号一起去掉() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/search?q={q}&limit={limit}", Map.of());

        assertThat(url).isEqualTo("https://api.example.com/search");
    }

    @Test
    void 段内任一占位符缺值即删整段() {
        // range=5- 这种半填的值几乎肯定比不传更糟
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/items?range={from}-{to}&q={q}",
                Map.of("from", "5", "q", "x"));

        assertThat(url).isEqualTo("https://api.example.com/items?q=x");
    }

    @Test
    void 无占位符的静态query段always保留() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/search?apiKey=STATIC&q={q}", Map.of());

        assertThat(url).isEqualTo("https://api.example.com/search?apiKey=STATIC");
    }

    @Test
    void baseUrl自带query时后续段正常处理() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/api?v=1&q={q}", Map.of("q", "x"));

        assertThat(url).isEqualTo("https://api.example.com/api?v=1&q=x");
    }

    // ===== 编码与注入 =====

    @Test
    void 参数值含花括号不会引发二次替换() {
        // 这条钉的是「现状就成立」的正确性：URLEncoder 把 { } 编码成 %7B %7D，所以先替换
        // 进去的值不可能被后面的替换轮次再次识别成占位符。将来若有人「优化」掉编码，
        // 本条会红。
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("a", "{b}");
        args.put("b", "SECRET");

        String url = UrlTemplateFiller.fill("https://api.example.com/x/{a}/{b}", args);

        assertThat(url).isEqualTo("https://api.example.com/x/%7Bb%7D/SECRET");
    }

    @Test
    void 参数值含与号不会拆出新的query段() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/search?q={q}", Map.of("q", "a&admin=true"));

        assertThat(url).isEqualTo("https://api.example.com/search?q=a%26admin%3Dtrue");
    }

    @Test
    void 多余的args被忽略不影响URL() {
        // POST 时这些多余参数会进 body，这里只管 URL
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/orders/{id}", Map.of("id", "1", "note", "x"));

        assertThat(url).isEqualTo("https://api.example.com/orders/1");
    }

    @Test
    void null值按字面量null填入而不抛空指针() {
        Map<String, Object> args = new HashMap<>();
        args.put("id", null);

        String url = UrlTemplateFiller.fill("https://api.example.com/orders/{id}", args);

        assertThat(url).isEqualTo("https://api.example.com/orders/null");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=UrlTemplateFillerTest
```
预期：编译失败，`UrlTemplateFiller` 不存在。

- [ ] **Step 3: 实现 `UrlTemplateFiller`**

创建 `backend/src/main/java/com/qingyu/care/service/tool/UrlTemplateFiller.java`：

```java
package com.qingyu.care.service.tool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@code {key}} 占位符按参数填成最终 URL。纯函数，无状态。
 *
 * <p>从 {@link HttpToolCallback} 抽出来独立成类，因为它不再是三行 replace：path 段与
 * query 段的处理规则**不同**，且这是本项目里最需要密集边界测试的一段——旧实现对 LLM
 * 未填的占位符原样保留，可选 query 参数因此一直是坏的（{@code ?limit={limit}} 被真的
 * 发出去）。
 *
 * <p>两段规则：
 * <ul>
 *   <li><b>path 段</b>：占位符必须全部有值，缺一个就抛异常——path 参数填不出来意味着
 *       这次调用根本不成立，发出去只会拿到 404 而 LLM 无从判断原因；
 *   <li><b>query 段</b>：按 {@code &} 拆开逐段判断，段内任一占位符缺值就<b>整段删除</b>
 *       （{@code range=5-} 这种半填值几乎肯定比不传更糟），无占位符的静态段原样保留。
 * </ul>
 *
 * @author ruan
 */
public final class UrlTemplateFiller {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private UrlTemplateFiller() {
    }

    /**
     * @param template 含 {@code {key}} 占位符的 URL 模板
     * @param args     LLM 交来的参数（可能缺少可选项，也可能含模板里没有的键）
     * @throws IllegalArgumentException path 段有占位符没被填上
     */
    public static String fill(String template, Map<String, Object> args) {
        int q = template.indexOf('?');
        String path = q < 0 ? template : template.substring(0, q);
        String query = q < 0 ? null : template.substring(q + 1);

        String filledPath = replacePresent(path, args);
        requireNoPlaceholder(filledPath);

        if (query == null || query.isBlank()) {
            return filledPath;
        }

        List<String> kept = new ArrayList<>();
        for (String segment : query.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            String filled = replacePresent(segment, args);
            // 段内仍有占位符 = 该参数 LLM 没填 → 整段丢弃。
            // ⚠️ 变异点：改成 kept.add(filled) 会退回旧的坏行为，UrlTemplateFillerTest
            // 的「可选query参数未填时整段删除」会转红。
            if (PLACEHOLDER.matcher(filled).find()) {
                continue;
            }
            kept.add(filled);
        }
        return kept.isEmpty() ? filledPath : filledPath + "?" + String.join("&", kept);
    }

    /**
     * 只替换 args 里**存在**的键，不存在的占位符原样留下——留下的形态正是上面两段规则的判据。
     *
     * <p>遍历模板里的占位符而不是遍历 args：只有这样才能区分「填了」与「没填」。
     */
    private static String replacePresent(String text, Map<String, Object> args) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            if (!args.containsKey(key)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String encoded = URLEncoder.encode(String.valueOf(args.get(key)), StandardCharsets.UTF_8);
            // quoteReplacement：replacement 串里的 $ 与 \ 对 appendReplacement 有特殊含义。
            // URLEncoder 已把它们编码掉，此处是纵深防御，别删。
            m.appendReplacement(sb, Matcher.quoteReplacement(encoded));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void requireNoPlaceholder(String path) {
        Matcher m = PLACEHOLDER.matcher(path);
        if (m.find()) {
            throw new IllegalArgumentException("缺少必填参数 " + m.group(1));
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=UrlTemplateFillerTest
```
预期：13 条全绿。

- [ ] **Step 5: 改 `HttpToolCallback` 用它**

在 `backend/src/main/java/com/qingyu/care/service/tool/HttpToolCallback.java` 中：

1. 把 `call(String toolInput)` 里的 `String url = fillTemplate(tool.getUrlTemplate(), args);` 改成：

```java
            String url = UrlTemplateFiller.fill(tool.getUrlTemplate(), args);
```

2. **删除**整个私有方法 `fillTemplate`（含其 javadoc `/** 把 {key} 占位符用 URL 编码后的参数值替换。 */`）。

3. 删除不再需要的 import：`java.net.URLEncoder`、`java.nio.charset.StandardCharsets`。

⚠️ `call()` 既有的 `catch (Exception e)` 会接住 `IllegalArgumentException` 并返回
`"工具调用失败: 缺少必填参数 orderId"` —— 这正是设计意图，**不要**给它加单独的 catch。

- [ ] **Step 6: 跑工具相关全部测试**

```bash
cd backend && mvn test -Dtest='*Tool*Test'
```
预期：全绿。若 `HttpToolCallbackTest` 有断言旧的「占位符残留」行为，把它改成断言新行为并在注释里说明是有意变更。

- [ ] **Step 7: 变异验证（本仓库规矩，必须真跑）**

把 `UrlTemplateFiller.fill` 里的

```java
            if (PLACEHOLDER.matcher(filled).find()) {
                continue;
            }
```

临时改成

```java
            if (false) {
                continue;
            }
```

跑 `mvn test -Dtest=UrlTemplateFillerTest`，**确认「可选query参数未填时整段删除」与「query参数全部未填时连问号一起去掉」两条转红**，然后改回来再跑一次确认恢复全绿。把这次变异的输入输出记进 commit message。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/tool/UrlTemplateFiller.java \
        backend/src/main/java/com/qingyu/care/service/tool/HttpToolCallback.java \
        backend/src/test/java/com/qingyu/care/service/tool/UrlTemplateFillerTest.java
git commit -m "fix(tool): 未填的 URL 占位符不再原样发出去

旧实现遍历 args 逐个 replace，LLM 没填的占位符原样留在 URL 里被真的发出去
（?limit={limit}），可选 query 参数因此在本项目里一直是坏的——手工配置也一样坏。

抽成 UrlTemplateFiller 纯函数类，path 与 query 两段规则不同：path 缺值直接抛异常
（经既有 catch 变成「缺少必填参数 X」回给 LLM，它能据此重试，比拿一个 404 强）；
query 段内任一占位符缺值则整段删除，静态段保留，全删光时连 ? 一起去掉。

变异验证：把「段内仍有占位符则跳过」改成恒 false，两条相关测试转红。"
```

---

## Task 2: 依赖声明 + `OpenApiSchemaParser`

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiSchemaParser.java`
- Test: `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiSchemaParserTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `public io.swagger.v3.oas.models.OpenAPI OpenApiSchemaParser.parse(String text)` — 抛 `BusinessException`（非法文本 / 非 3.x）。Task 3 消费它的返回值。

- [ ] **Step 1: 在 pom.xml 显式声明两个坐标**

在 `backend/pom.xml` 的 `<dependencies>` 里，紧挨着 springdoc 那段之后加：

```xml
        <!-- OpenAPI schema 解析。这两个坐标现在是 springdoc 的传递依赖（compile scope），
             显式声明是为了钉住：哪天 springdoc 换实现或改依赖，编译会在这里明确断掉，
             而不是在 service/openapi 里报一堆看不出关系的 NoClassDefFound。
             ⚠️ 不写 version，由 springdoc / Boot BOM 托管。
             ⚠️ 刻意不用 io.swagger.parser.v3:swagger-parser-v3：它的 ParseOptions.setResolve(true)
             会自己 fetch 远程 $ref，那是一条绕过 SsrfGuard、藏在依赖内部的出站路径。
             只做反序列化的 swagger-core 在结构上就没有这个能力。见设计文档 §2.1。 -->
        <dependency>
            <groupId>io.swagger.core.v3</groupId>
            <artifactId>swagger-core-jakarta</artifactId>
        </dependency>
        <dependency>
            <groupId>io.swagger.core.v3</groupId>
            <artifactId>swagger-models-jakarta</artifactId>
        </dependency>
```

跑 `cd backend && mvn -q dependency:resolve` 确认能解析到版本（应为 2.2.47 或更高）。若报「缺少 version」，说明 BOM 没托管它，改为显式写 `<version>2.2.47</version>` 并在注释里说明原因。

- [ ] **Step 2: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiSchemaParserTest.java`：

```java
package com.qingyu.care.service.openapi;

import com.qingyu.care.exception.BusinessException;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * schema 文本的解析与版本判别。
 *
 * <p>JSON 与 YAML 走的是两条不同的反序列化分支，各测一条；版本判别必须在反序列化**之前**
 * 做——Swagger 2.0 文档反序列化成 {@code OpenAPI} 只会得到一堆 null，报不出有用的原因。
 *
 * @author ruan
 */
class OpenApiSchemaParserTest {

    private final OpenApiSchemaParser parser = new OpenApiSchemaParser();

    private static final String JSON_3_0 = """
            {
              "openapi": "3.0.3",
              "info": {"title": "订单 API", "version": "1.0"},
              "servers": [{"url": "https://api.example.com"}],
              "paths": {
                "/orders/{orderId}": {
                  "get": {
                    "operationId": "getOrder",
                    "summary": "查订单",
                    "parameters": [
                      {"name": "orderId", "in": "path", "required": true,
                       "schema": {"type": "string"}}
                    ]
                  }
                }
              }
            }""";

    private static final String YAML_3_1 = """
            openapi: 3.1.0
            info:
              title: 订单 API
              version: '1.0'
            servers:
              - url: https://api.example.com
            paths:
              /orders:
                get:
                  operationId: listOrders
                  summary: 列订单
            """;

    @Test
    void JSON格式的3_0文档能解析出路径与服务器() {
        OpenAPI api = parser.parse(JSON_3_0);

        assertThat(api.getOpenapi()).startsWith("3.0");
        assertThat(api.getServers().get(0).getUrl()).isEqualTo("https://api.example.com");
        assertThat(api.getPaths()).containsKey("/orders/{orderId}");
        assertThat(api.getPaths().get("/orders/{orderId}").getGet().getOperationId())
                .isEqualTo("getOrder");
    }

    @Test
    void YAML格式的3_1文档能解析() {
        OpenAPI api = parser.parse(YAML_3_1);

        assertThat(api.getOpenapi()).startsWith("3.1");
        assertThat(api.getPaths().get("/orders").getGet().getOperationId()).isEqualTo("listOrders");
    }

    @Test
    void Swagger2_0给出点名版本的错误而不是含糊的解析失败() {
        String swagger2 = """
                {"swagger": "2.0", "info": {"title": "旧 API", "version": "1.0"}, "paths": {}}""";

        assertThatThrownBy(() -> parser.parse(swagger2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3.x")
                .hasMessageContaining("2.0");
    }

    @Test
    void 缺少openapi字段的文档被拒() {
        assertThatThrownBy(() -> parser.parse("{\"info\": {\"title\": \"x\"}}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OpenAPI");
    }

    @Test
    void 非法文本抛业务异常而不是原始解析器异常() {
        // 原始的 Jackson/SnakeYAML 异常消息会把解析器内部细节暴露给前端
        assertThatThrownBy(() -> parser.parse("这不是一份 schema {{{"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 空文本被拒() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=OpenApiSchemaParserTest
```
预期：编译失败，`OpenApiSchemaParser` 不存在。

- [ ] **Step 4: 实现 `OpenApiSchemaParser`**

创建 `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiSchemaParser.java`：

```java
package com.qingyu.care.service.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.exception.BusinessException;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.stereotype.Component;

/**
 * schema 文本 → {@link OpenAPI} 模型。无 IO、无 DB。
 *
 * <p>⚠️ 本类是全项目**唯一**使用 Jackson 2（{@code com.fasterxml.jackson}）的地方：
 * swagger-core 的 {@code Json31}/{@code Yaml31} 是 Jackson 2 的 mapper，而项目主栈是
 * Jackson 3（{@code tools.jackson}）。两者在类路径上共存、包名不同互不冲突，但**别在
 * 别处混用**——我们自己产出的 JSON（如 inputSchema）一律走项目的 Jackson 3 ObjectMapper。
 *
 * <p>⚠️ 刻意只做反序列化，不解析 {@code $ref}：swagger-parser 那种「自动 resolve 远程
 * $ref」的能力等于一条绕过 {@link com.qingyu.care.config.SsrfGuard} 的出站路径。本地
 * {@code $ref} 由 {@link OpenApiToolMapper} 自己解（带深度上限与循环检测）。
 *
 * @author ruan
 */
@Component
public class OpenApiSchemaParser {

    /**
     * @throws BusinessException 文本为空 / 不是合法 JSON 或 YAML / 不是 OpenAPI 3.x
     */
    public OpenAPI parse(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException("schema 内容为空");
        }
        String trimmed = text.trim();
        // 以 { 开头当 JSON，否则当 YAML。YAML 是 JSON 的超集，理论上 Yaml31 也能读 JSON，
        // 但用各自的 mapper 能让报错消息更贴近用户实际给的格式。
        ObjectMapper mapper = trimmed.startsWith("{") ? Json31.mapper() : Yaml31.mapper();

        JsonNode root;
        try {
            root = mapper.readTree(trimmed);
        } catch (Exception e) {
            // 不透传原始异常消息：Jackson/SnakeYAML 的报错含解析器内部细节，对用户无意义
            throw new BusinessException("schema 解析失败，请确认是合法的 JSON 或 YAML");
        }
        requireOpenApi3(root);

        try {
            return mapper.treeToValue(root, OpenAPI.class);
        } catch (Exception e) {
            throw new BusinessException("schema 结构不符合 OpenAPI 规范，无法解析");
        }
    }

    /**
     * 版本判别必须在反序列化**之前**：Swagger 2.0 文档也能被反序列化成 OpenAPI 对象，
     * 只是所有字段都是 null，那时再报错只能说「没有可导入的端点」，用户完全看不出真正原因。
     */
    private void requireOpenApi3(JsonNode root) {
        JsonNode swagger = root.get("swagger");
        if (swagger != null && !swagger.isNull()) {
            throw new BusinessException(
                    "仅支持 OpenAPI 3.x，检测到 Swagger " + swagger.asText() + "，请先转换后再导入");
        }
        JsonNode version = root.get("openapi");
        if (version == null || version.isNull() || !version.asText().startsWith("3.")) {
            throw new BusinessException("仅支持 OpenAPI 3.x：未找到有效的 openapi 版本字段");
        }
    }
}
```

⚠️ 若 `Json31` / `Yaml31` 在当前 swagger-core 版本中不存在，改用 `Json.mapper()` / `Yaml.mapper()`（同包下的 3.0 变体），并在注释里记下版本差异。先用 `javap -classpath` 或 IDE 确认类名再写。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=OpenApiSchemaParserTest
```
预期：6 条全绿。

- [ ] **Step 6: 提交**

```bash
git add backend/pom.xml \
        backend/src/main/java/com/qingyu/care/service/openapi/OpenApiSchemaParser.java \
        backend/src/test/java/com/qingyu/care/service/openapi/OpenApiSchemaParserTest.java
git commit -m "feat(openapi): schema 文本解析与 3.x 版本判别

用已在 compile 类路径的 swagger-core（springdoc 传递进来）做反序列化，显式声明坐标以免
springdoc 换实现时在别处报一堆看不出关系的错。刻意不引 swagger-parser：它会自动 fetch
远程 \$ref，那是一条绕过 SsrfGuard 且藏在依赖内部的出站路径。

版本判别放在反序列化之前——Swagger 2.0 文档也能反序列化成 OpenAPI 对象，只是字段全空，
那时再报错只能说「没有可导入的端点」，用户看不出真正原因。"
```

---

## Task 3: `OpenApiToolMapper` 之一——operation 筛选、名字、描述、urlTemplate

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/openapi/OpenApiToolCandidate.java`
- Create: `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiToolMapper.java`
- Test: `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiToolMapperTest.java`

**Interfaces:**
- Consumes: `OpenApiSchemaParser.parse` 的返回值 `io.swagger.v3.oas.models.OpenAPI`（Task 2）
- Produces:
  - `OpenApiToolCandidate`（`@Data`，字段：`name`/`description`/`httpMethod`/`urlTemplate`/`inputSchema`/`warnings: List<String>`/`nameConflict: boolean`）
  - `public OpenApiToolMapper.MapResult map(OpenAPI api, String baseUrl)`，`MapResult` 是 `record MapResult(List<OpenApiToolCandidate> candidates, List<String> globalWarnings, boolean truncated)`
  - Task 4 在同一个类里补 `inputSchema` 的构建；Task 6 消费 `MapResult`。

- [ ] **Step 1: 建候选 DTO**

创建 `backend/src/main/java/com/qingyu/care/dto/openapi/OpenApiToolCandidate.java`：

```java
package com.qingyu.care.dto.openapi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个待导入的候选工具。**parse 产出它、import 收它**，是同一个类型——这样前端在预览里
 * 改过的 name/description 能原样回传，不需要后端二次解析 schema（两次解析可能因 schema
 * 内容变化或解析器版本差异而分叉，用户看到的与实际落库的会悄悄不一致，见设计文档 §2.5）。
 *
 * <p>⚠️ 不用 record：import 端点要反序列化它，且前端会改其中的字段，普通可变 DTO 更贴合
 * 本仓库既有 DTO 的写法。
 *
 * @author ruan
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiToolCandidate {

    private String name;
    private String description;
    /** 只可能是 GET 或 POST，见设计文档 §2.4 */
    private String httpMethod;
    private String urlTemplate;
    /** JSON Schema 文本，由 Task 4 构建 */
    private String inputSchema;

    /**
     * 候选级 warning：这个工具**能**导入，但有能力被降级（header 参数被忽略、描述被截断、
     * $ref 被降级、名字与既有工具冲突）。必须显示在勾选列表里就近可见——不这样用户会以为
     * 导进来就能用，而一个「因为忽略了 header 认证参数所以永远返回 401」的工具在运行时是
     * fail-soft 的：模型拿着错误响应继续编答案，全程无异常无日志。
     */
    private List<String> warnings = new ArrayList<>();

    /** 与当前租户已有工具重名。前端据此高亮并要求改名后才能提交。 */
    private boolean nameConflict;
}
```

- [ ] **Step 2: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiToolMapperTest.java`：

```java
package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI → 候选工具的映射规则。本特性绝大多数规则都在这里，故测试也最密。
 *
 * <p>{@link OpenApiToolMapper} 是纯函数：给定一份 {@code OpenAPI} 与一个 baseUrl，输出
 * 完全确定，不碰 DB 也不发请求。
 *
 * @author ruan
 */
class OpenApiToolMapperTest {

    private final OpenApiSchemaParser parser = new OpenApiSchemaParser();
    private final OpenApiToolMapper mapper = new OpenApiToolMapper(new tools.jackson.databind.ObjectMapper());

    private OpenApiToolMapper.MapResult map(String json, String baseUrl) {
        OpenAPI api = parser.parse(json);
        return mapper.map(api, baseUrl);
    }

    // ===== operation 筛选 =====

    @Test
    void 只导入GET与POST_其余方法跳过并记全局warning() {
        // RestClientHttpToolExecutor 把非 POST 一律降级成 GET 发出去，硬导 DELETE 会让
        // 一个删除端点被当成 GET 请求，语义错得无声无息，故只能跳过。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/items/{id}":{
                   "get":{"operationId":"getItem"},
                   "post":{"operationId":"createItem"},
                   "put":{"operationId":"updateItem"},
                   "delete":{"operationId":"deleteItem"},
                   "patch":{"operationId":"patchItem"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates()).extracting(OpenApiToolCandidate::getName)
                .containsExactlyInAnyOrder("getItem", "createItem");
        assertThat(r.globalWarnings()).anySatisfy(w ->
                assertThat(w).contains("PUT").contains("updateItem"));
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("deleteItem"));
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("patchItem"));
    }

    // ===== 名字 =====

    @Test
    void operationId缺失时按方法与路径生成名字() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders/{orderId}/items":{"get":{"summary":"列条目"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates()).hasSize(1);
        assertThat(r.candidates().get(0).getName()).isEqualTo("get_orders_orderId_items");
    }

    @Test
    void 名字非法字符被清洗且首字符非字母时加前缀() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"9lives-of.cat"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getName()).isEqualTo("t_9lives_of_cat");
    }

    @Test
    void 名字截断到64而不是列宽100() {
        // 64 是 OpenAI 兼容端点对函数名的限制。按列宽 100 截断能存库能显示，但挂给 LLM 时
        // 被模型侧拒绝，症状是「工具配好了却从来不被调用」，无异常无日志。
        String longId = "a".repeat(120);
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"%s"}}}}""".formatted(longId);

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getName()).hasSize(64);
    }

    @Test
    void 批内重名加后缀且仍不超过64() {
        String base = "b".repeat(64);
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"%s"}},
                          "/b":{"get":{"operationId":"%s"}}}}""".formatted(base, base);

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        List<String> names = r.candidates().stream().map(OpenApiToolCandidate::getName).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).allSatisfy(n -> assertThat(n.length()).isLessThanOrEqualTo(64));
    }

    // ===== 描述 =====

    @Test
    void 描述优先summary_缺失时用description_再缺用方法与路径() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"a","summary":"取 A","description":"细节"}},
                          "/b":{"get":{"operationId":"b","description":"只有细节"}},
                          "/c":{"get":{"operationId":"c"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(byName(r, "a").getDescription()).isEqualTo("取 A");
        assertThat(byName(r, "b").getDescription()).isEqualTo("只有细节");
        assertThat(byName(r, "c").getDescription()).isEqualTo("GET /c");
    }

    @Test
    void 超长描述被截断到500并记候选级warning() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"a","summary":"%s"}}}}"""
                .formatted("说".repeat(600));

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getDescription()).hasSize(500);
        assertThat(r.candidates().get(0).getWarnings()).anySatisfy(w ->
                assertThat(w).contains("描述"));
    }

    // ===== urlTemplate =====

    @Test
    void path占位符原样拼接_query参数按原始顺序追加() {
        // OpenAPI 的 {orderId} 与本项目 fillTemplate 的 {key} 语法完全一致，无需转换
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders/{orderId}":{"get":{"operationId":"getOrder","parameters":[
                   {"name":"orderId","in":"path","required":true,"schema":{"type":"string"}},
                   {"name":"q","in":"query","required":true,"schema":{"type":"string"}},
                   {"name":"limit","in":"query","schema":{"type":"integer"}}]}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/orders/{orderId}?q={q}&limit={limit}");
    }

    @Test
    void baseUrl带query时剥掉它并记全局warning() {
        // 拼接顺序是 base + path + ?query，base 自带 query 会拼出 .../api?v=1/search&q={q}
        // 这种 path 跑到 query 后面的坏 URL。OpenAPI 的 servers.url 本就不该带 query，
        // 故剥掉并告知，而不是硬拼一个必然请求失败的地址。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/search":{"get":{"operationId":"s","parameters":[
                   {"name":"q","in":"query","schema":{"type":"string"}}]}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com/api?v=1");

        assertThat(r.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/api/search?q={q}");
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("查询参数"));
    }

    @Test
    void baseUrl末尾斜杠不会拼出双斜杠() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"get":{"operationId":"o"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com/");

        assertThat(r.candidates().get(0).getUrlTemplate()).isEqualTo("https://api.example.com/orders");
    }

    @Test
    void baseUrl为空时记全局warning且候选仍产出等待用户填() {
        // servers 缺失或是相对路径（/v1）时会走到这里。⚠️ 绝不能猜成 schema URL 的 origin：
        // schema 托管地址（GitHub raw、文档站）与 API 实际地址不同是常态，猜测会静默生成
        // 一批指向错误主机的工具。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"get":{"operationId":"o"}}}}""";

        OpenApiToolMapper.MapResult r = map(json, "");

        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("服务器地址"));
        assertThat(r.candidates()).hasSize(1);
    }

    // ===== 上限 =====

    @Test
    void 超过200个候选时截断并置truncated标记() {
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            paths.append("\"/p%d\":{\"get\":{\"operationId\":\"op%d\"}},".formatted(i, i));
        }
        paths.setLength(paths.length() - 1); // 去掉末尾多余的逗号
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},"paths":{%s}}"""
                .formatted(paths);

        OpenApiToolMapper.MapResult r = map(json, "https://api.example.com");

        assertThat(r.candidates()).hasSize(200);
        assertThat(r.truncated()).isTrue();
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("200"));
    }

    private OpenApiToolCandidate byName(OpenApiToolMapper.MapResult r, String name) {
        return r.candidates().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }
}
```

⚠️ spec §3.3 原写的是「baseUrl 已含 query 时用 `&` 连接」，那条规则会拼出
`https://api.example.com/api?v=1/search&q={q}` 这种 path 跑到 query 后面的坏 URL。计划里
改为**剥掉 baseUrl 的 query 并记全局 warning**（上面这条测试即为此），实施时请一并把 spec
§3.3 那一句改掉，别让两份文档各说各的。

- [ ] **Step 3: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=OpenApiToolMapperTest
```
预期：编译失败，`OpenApiToolMapper` 不存在。

- [ ] **Step 4: 实现 `OpenApiToolMapper`（本任务只做 inputSchema 以外的部分）**

创建 `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiToolMapper.java`：

```java
package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link OpenAPI} → 候选工具列表。**纯函数**：给定 schema 与 baseUrl，输出完全确定，
 * 不碰 DB、不发请求。本特性的映射规则几乎全在这里，故单测也集中在这里。
 *
 * <p>⚠️ warnings 分两级，别混成一个列表：
 * <ul>
 *   <li><b>候选级</b>挂在具体候选上（描述截断、header 参数被忽略、$ref 降级），随它显示
 *       在勾选列表里；
 *   <li><b>全局级</b>描述「压根没能成为候选」的那些（PUT/DELETE 被跳过、requestBody 非
 *       object 被跳过、超出上限被截断）——它们没有候选行可挂，只做候选级 warning 的话这些
 *       operation 会<b>无声消失</b>，用户只会觉得「我那个端点怎么没导进来」。
 * </ul>
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class OpenApiToolMapper {

    /** 候选数量上限，防一份上千 operation 的 schema 撑爆预览界面 */
    static final int MAX_CANDIDATES = 200;
    /** AiTool.description 的列宽 */
    static final int MAX_DESCRIPTION = 500;
    /** ⚠️ 不是 AiTool.name 的列宽 100：64 是 OpenAI 兼容端点对函数名的限制 */
    static final int MAX_NAME = 64;

    private final ObjectMapper objectMapper;

    public record MapResult(List<OpenApiToolCandidate> candidates,
                            List<String> globalWarnings,
                            boolean truncated) {
    }

    public MapResult map(OpenAPI api, String baseUrl) {
        List<OpenApiToolCandidate> candidates = new ArrayList<>();
        List<String> globalWarnings = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();
        boolean truncated = false;

        String base = normalizeBaseUrl(baseUrl, globalWarnings);
        if (api.getPaths() == null || api.getPaths().isEmpty()) {
            globalWarnings.add("schema 里没有任何路径");
            return new MapResult(candidates, globalWarnings, false);
        }

        outer:
        for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem item = pathEntry.getValue();
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : item.readOperationsMap().entrySet()) {
                PathItem.HttpMethod method = opEntry.getKey();
                Operation op = opEntry.getValue();

                if (method != PathItem.HttpMethod.GET && method != PathItem.HttpMethod.POST) {
                    globalWarnings.add("已跳过 %s %s（%s）：本平台的工具只支持 GET 与 POST"
                            .formatted(method, path, op.getOperationId() == null ? "无 operationId" : op.getOperationId()));
                    continue;
                }
                if (candidates.size() >= MAX_CANDIDATES) {
                    truncated = true;
                    globalWarnings.add("端点数超过 " + MAX_CANDIDATES + " 个上限，只保留了前 "
                            + MAX_CANDIDATES + " 个，请分批导入");
                    break outer;
                }

                OpenApiToolCandidate candidate = toCandidate(api, path, method, op, base, usedNames);
                if (candidate == null) {
                    // toCandidate 内部已记全局 warning（如 requestBody 非 object）
                    globalWarnings.add("已跳过 %s %s：请求体不是对象结构，当前工具模型无法表达"
                            .formatted(method, path));
                    continue;
                }
                candidates.add(candidate);
            }
        }
        return new MapResult(candidates, globalWarnings, truncated);
    }

    /** @return null 表示该 operation 无法表达、应跳过 */
    private OpenApiToolCandidate toCandidate(OpenAPI api, String path, PathItem.HttpMethod method,
            Operation op, String baseUrl, Set<String> usedNames) {
        OpenApiToolCandidate c = new OpenApiToolCandidate();
        List<String> warnings = new ArrayList<>();

        c.setName(uniqueName(rawName(op, method, path), usedNames));
        c.setHttpMethod(method.name());
        c.setDescription(description(op, method, path, warnings));

        List<Parameter> params = op.getParameters() == null ? List.of() : op.getParameters();
        warnIgnoredParams(params, warnings);
        c.setUrlTemplate(buildUrlTemplate(baseUrl, path, params));

        // inputSchema 由 Task 4 填充；本任务先置空对象，保证候选结构完整
        c.setInputSchema("{\"type\":\"object\",\"properties\":{}}");

        c.setWarnings(warnings);
        return c;
    }

    // ===== 名字 =====

    private String rawName(Operation op, PathItem.HttpMethod method, String path) {
        if (op.getOperationId() != null && !op.getOperationId().isBlank()) {
            return sanitize(op.getOperationId());
        }
        return sanitize(method.name().toLowerCase() + "_" + path);
    }

    /** 清洗成 [A-Za-z0-9_]，首字符非字母时前置 t_，截断到 {@link #MAX_NAME}。 */
    static String sanitize(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+", "").replaceAll("_+$", "");
        if (s.isEmpty()) {
            s = "tool";
        }
        if (!Character.isLetter(s.charAt(0))) {
            s = "t_" + s;
        }
        return s.length() <= MAX_NAME ? s : s.substring(0, MAX_NAME);
    }

    /** 批内重名加 _2/_3，且保证加完后仍不超过 {@link #MAX_NAME}。 */
    private String uniqueName(String base, Set<String> used) {
        if (used.add(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String suffix = "_" + n;
            String head = base.length() + suffix.length() <= MAX_NAME
                    ? base
                    : base.substring(0, MAX_NAME - suffix.length());
            String candidate = head + suffix;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    // ===== 描述 =====

    private String description(Operation op, PathItem.HttpMethod method, String path,
            List<String> warnings) {
        String text = op.getSummary();
        if (text == null || text.isBlank()) {
            text = op.getDescription();
        }
        if (text == null || text.isBlank()) {
            return method.name() + " " + path;
        }
        if (text.length() > MAX_DESCRIPTION) {
            warnings.add("描述过长已截断到 " + MAX_DESCRIPTION + " 字，建议手工改写得更贴合调用时机");
            return text.substring(0, MAX_DESCRIPTION);
        }
        return text;
    }

    // ===== urlTemplate =====

    /**
     * baseUrl + path + query 串。OpenAPI 的 path 占位符 {@code {orderId}} 与本项目
     * {@link com.qingyu.care.service.tool.UrlTemplateFiller} 的 {@code {key}} 语法完全一致，
     * 直接原样拼。query 参数按 schema 原始顺序追加（不按 required 重排——顺序稳定才可预期，
     * 且 UrlTemplateFiller 能正确处理任意位置的可选参数）。
     */
    private String buildUrlTemplate(String base, String path, List<Parameter> params) {
        StringBuilder url = new StringBuilder(base).append(path);
        String separator = "?";
        for (Parameter p : params) {
            if (!"query".equals(p.getIn())) {
                continue;
            }
            url.append(separator).append(p.getName()).append("={").append(p.getName()).append("}");
            separator = "&";
        }
        return url.toString();
    }

    /**
     * 规范化服务器地址：去掉末尾斜杠、剥掉 query。
     *
     * <p>⚠️ 剥 query 不是洁癖：拼接顺序是 base + path + {@code ?query}，base 自带 query 会
     * 拼出 {@code .../api?v=1/search&q={q}} 这种 path 跑到 query 后面的坏 URL，请求必然失败。
     * OpenAPI 的 {@code servers.url} 本就不该带 query，剥掉并告知比硬拼一个坏地址诚实。
     */
    private String normalizeBaseUrl(String baseUrl, List<String> globalWarnings) {
        if (baseUrl == null || baseUrl.isBlank()) {
            globalWarnings.add("schema 未提供绝对的服务器地址，请手工填写服务器地址后再导入");
            return "";
        }
        String base = baseUrl.trim();
        int q = base.indexOf('?');
        if (q >= 0) {
            globalWarnings.add("服务器地址里的查询参数已被忽略（" + base.substring(q)
                    + "）：它会让路径拼到查询串后面，请把这类参数写进各端点自身的参数里");
            base = base.substring(0, q);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * header / cookie 参数在 AiTool 模型里无法表达（只有一个静态 authHeader，没有按调用
     * 填充的 header 通道）。**必须记 warning**：少了认证 header 的工具会永远返回 401，而
     * 工具调用是 fail-soft 的——模型拿着错误响应继续编答案，全程无异常无日志。
     */
    private void warnIgnoredParams(List<Parameter> params, List<String> warnings) {
        List<String> ignored = params.stream()
                .filter(p -> "header".equals(p.getIn()) || "cookie".equals(p.getIn()))
                .map(Parameter::getName)
                .toList();
        if (!ignored.isEmpty()) {
            warnings.add("以下 header/cookie 参数无法表达、已忽略：" + String.join("、", ignored)
                    + "。若其中含认证信息，请在下方「鉴权头」里统一填写");
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=OpenApiToolMapperTest
```
预期：全绿。`baseUrl已含query时用与号连接` 若按 Step 2 的提醒简化过，同步改测试。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/dto/openapi/OpenApiToolCandidate.java \
        backend/src/main/java/com/qingyu/care/service/openapi/OpenApiToolMapper.java \
        backend/src/test/java/com/qingyu/care/service/openapi/OpenApiToolMapperTest.java
git commit -m "feat(openapi): operation 筛选与名字/描述/urlTemplate 映射

只收 GET 与 POST——执行器把非 POST 一律降级成 GET，硬导 DELETE 会让删除端点被当成 GET
发出去，语义错得无声无息。

工具名截断到 64 而非列宽 100：64 是 OpenAI 兼容端点对函数名的限制，按 100 截断能存库
能显示，但挂给 LLM 时被模型侧拒绝，症状是「配好了却从来不被调用」。

warnings 分两级：候选级挂在行上，全局级描述「压根没成为候选」的那些——后者没有行可挂，
不单独收集的话被跳过的 operation 会无声消失。"
```

---

## Task 4: `OpenApiToolMapper` 之二——`inputSchema` 三来源合并与 `$ref`

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiToolMapper.java`
- Test: `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiToolMapperSchemaTest.java`（新建，与 Task 3 的测试分开，两边都长）

**Interfaces:**
- Consumes: Task 3 的 `OpenApiToolMapper.map` 与 `OpenApiToolCandidate`
- Produces: `OpenApiToolCandidate.inputSchema` 里真实的 JSON Schema 文本；`toCandidate` 返回 `null` 表示该 operation 应跳过（Task 3 已接好这条分支）

- [ ] **Step 1: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiToolMapperSchemaTest.java`：

```java
package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * inputSchema 的构建：path / query 参数与 requestBody 三个来源**平铺合并到顶层**。
 *
 * <p>平铺是被运行时逼出来的：{@code HttpToolCallback} 在 POST 时把整个 toolInput 原样
 * 当 body 发出去，所以 body 字段必须在参数对象顶层，不能嵌在 {@code body: {...}} 里。
 *
 * @author ruan
 */
class OpenApiToolMapperSchemaTest {

    private final ObjectMapper om = new ObjectMapper();
    private final OpenApiSchemaParser parser = new OpenApiSchemaParser();
    private final OpenApiToolMapper mapper = new OpenApiToolMapper(om);

    private OpenApiToolMapper.MapResult map(String json) {
        OpenAPI api = parser.parse(json);
        return mapper.map(api, "https://api.example.com");
    }

    private JsonNode schemaOf(OpenApiToolCandidate c) {
        return om.readTree(c.getInputSchema());
    }

    @Test
    void path参数全部必填_query按各自required标记() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders/{orderId}":{"get":{"operationId":"getOrder","parameters":[
                   {"name":"orderId","in":"path","required":true,"description":"订单号",
                    "schema":{"type":"string"}},
                   {"name":"q","in":"query","required":true,"schema":{"type":"string"}},
                   {"name":"limit","in":"query","schema":{"type":"integer"}}]}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("properties").propertyNames()).contains("orderId", "q", "limit");
        assertThat(schema.path("properties").path("orderId").path("description").asString())
                .isEqualTo("订单号");
        assertThat(schema.path("properties").path("limit").path("type").asString())
                .isEqualTo("integer");
        assertThat(schema.path("required").toString()).contains("orderId").contains("q")
                .doesNotContain("limit");
    }

    @Test
    void POST的requestBody字段被平铺合并到顶层() {
        // 必须平铺：HttpToolCallback 把整个 toolInput 当 body 发出去，嵌套的 body 字段
        // 在 LLM 那边永远填不对位置
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"post":{"operationId":"createOrder",
                   "requestBody":{"required":true,"content":{"application/json":{"schema":{
                     "type":"object",
                     "properties":{"sku":{"type":"string"},"qty":{"type":"integer"}},
                     "required":["sku"]}}}}}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("properties").propertyNames()).contains("sku", "qty");
        assertThat(schema.path("required").toString()).contains("sku");
    }

    @Test
    void requestBody不是对象结构时整个operation被跳过() {
        // POST /items 收一个裸数组，在当前工具模型里根本表达不了——LLM 交出来的永远是 object
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/items":{"post":{"operationId":"bulkCreate",
                   "requestBody":{"content":{"application/json":{"schema":{
                     "type":"array","items":{"type":"string"}}}}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).isEmpty();
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("/items"));
    }

    @Test
    void 非json请求体的operation被跳过() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/upload":{"post":{"operationId":"upload",
                   "requestBody":{"content":{"multipart/form-data":{"schema":{
                     "type":"object","properties":{"file":{"type":"string"}}}}}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).isEmpty();
        assertThat(r.globalWarnings()).anySatisfy(w -> assertThat(w).contains("/upload"));
    }

    @Test
    void 本地ref被解析成真实字段() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/orders":{"post":{"operationId":"createOrder",
                   "requestBody":{"content":{"application/json":{"schema":
                     {"$ref":"#/components/schemas/OrderReq"}}}}}}},
                 "components":{"schemas":{"OrderReq":{"type":"object",
                   "properties":{"sku":{"type":"string","description":"商品编码"}},
                   "required":["sku"]}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("properties").path("sku").path("description").asString())
                .isEqualTo("商品编码");
        assertThat(schema.path("required").toString()).contains("sku");
    }

    @Test
    void 循环ref不栈溢出且降级为object并记warning() {
        // 自引用结构（评论树、组织架构、分类树）在真实 schema 里很常见，且是合法建模，
        // 不是畸形输入。不设防就是无限递归栈溢出。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/tree":{"post":{"operationId":"createNode",
                   "requestBody":{"content":{"application/json":{"schema":
                     {"$ref":"#/components/schemas/Node"}}}}}}},
                 "components":{"schemas":{"Node":{"type":"object","properties":{
                   "name":{"type":"string"},
                   "child":{"$ref":"#/components/schemas/Node"}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).hasSize(1);
        OpenApiToolCandidate c = r.candidates().get(0);
        assertThat(schemaOf(c).path("properties").propertyNames()).contains("name", "child");
        assertThat(c.getWarnings()).anySatisfy(w -> assertThat(w).contains("引用"));
    }

    @Test
    void 指向不存在的ref降级为object并记warning() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"post":{"operationId":"a",
                   "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                     "properties":{"x":{"$ref":"#/components/schemas/Missing"}}}}}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);

        assertThat(r.candidates()).hasSize(1);
        assertThat(r.candidates().get(0).getWarnings()).anySatisfy(w ->
                assertThat(w).contains("引用"));
    }

    @Test
    void body字段与query参数同名时保留参数侧定义并记warning() {
        // URL 必须能填出来，所以参数侧优先
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"post":{"operationId":"a",
                   "parameters":[{"name":"id","in":"query","schema":{"type":"string"}}],
                   "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                     "properties":{"id":{"type":"integer"}}}}}}}}}}""";

        OpenApiToolMapper.MapResult r = map(json);
        OpenApiToolCandidate c = r.candidates().get(0);

        assertThat(schemaOf(c).path("properties").path("id").path("type").asString())
                .isEqualTo("string");
        assertThat(c.getWarnings()).anySatisfy(w -> assertThat(w).contains("同名"));
    }

    @Test
    void GET即使声明了requestBody也不合并它() {
        // 执行器只在 POST 时发 body，GET 合并进来只会让 LLM 填一堆到不了服务端的参数
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"operationId":"a",
                   "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                     "properties":{"ghost":{"type":"string"}}}}}}}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("properties").propertyNames()).doesNotContain("ghost");
    }

    @Test
    void 无参数时产出空properties的合法object() {
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/ping":{"get":{"operationId":"ping"}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("properties").isObject()).isTrue();
        assertThat(schema.path("properties").size()).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=OpenApiToolMapperSchemaTest
```
预期：大部分红（inputSchema 目前是 Task 3 里写死的空对象）。

- [ ] **Step 3: 在 `OpenApiToolMapper` 里实现 schema 构建**

在 `OpenApiToolMapper` 中新增常量与方法，并把 `toCandidate` 里那行写死的空 schema 换掉。

新增 import：

```java
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
```

新增常量：

```java
    /** $ref 递归深度上限，超出即降级为 object */
    static final int MAX_REF_DEPTH = 5;
```

把 `toCandidate` 里这两行：

```java
        // inputSchema 由 Task 4 填充；本任务先置空对象，保证候选结构完整
        c.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
```

替换为：

```java
        ObjectNode schema = buildInputSchema(api, op, params, method, warnings);
        if (schema == null) {
            return null; // requestBody 无法表达 → 跳过整个 operation
        }
        c.setInputSchema(objectMapper.writeValueAsString(schema));
```

新增方法：

```java
    /**
     * 三个来源平铺合并成一个扁平 object：path 参数（全 required）、query 参数（按各自
     * required）、POST 的 application/json requestBody（**平铺到顶层**）。
     *
     * <p>平铺是被运行时逼出来的：{@link com.qingyu.care.service.tool.HttpToolCallback}
     * 在 POST 时把整个 toolInput 原样当 body 发出去，嵌套的 body 字段 LLM 永远填不对位置。
     *
     * @return null 表示该 operation 无法表达、应整个跳过
     */
    private ObjectNode buildInputSchema(OpenAPI api, Operation op, List<Parameter> params,
            PathItem.HttpMethod method, List<String> warnings) {
        Components components = api.getComponents();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        java.util.LinkedHashSet<String> required = new java.util.LinkedHashSet<>();

        for (Parameter p : params) {
            if (!"path".equals(p.getIn()) && !"query".equals(p.getIn())) {
                continue; // header/cookie 已在 warnIgnoredParams 里记过 warning
            }
            ObjectNode node = schemaToNode(p.getSchema(), components, new HashSet<>(), 0, warnings);
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                node.put("description", p.getDescription());
            }
            properties.set(p.getName(), node);
            // path 参数一律必填：URL 里填不出来就意味着这次调用根本不成立
            if ("path".equals(p.getIn()) || Boolean.TRUE.equals(p.getRequired())) {
                required.add(p.getName());
            }
        }

        // ⚠️ 只有 POST 才合并 requestBody：执行器只在 POST 时发 body，GET 合并进来只会让
        // LLM 填一堆到不了服务端的参数。
        if (method == PathItem.HttpMethod.POST && op.getRequestBody() != null) {
            if (!mergeRequestBody(op.getRequestBody(), components, properties, required, warnings)) {
                return null;
            }
        }

        if (!required.isEmpty()) {
            ArrayNode arr = root.putArray("required");
            required.forEach(arr::add);
        }
        return root;
    }

    /** @return false 表示 requestBody 无法表达，调用方应跳过整个 operation */
    private boolean mergeRequestBody(RequestBody body, Components components,
            ObjectNode properties, java.util.LinkedHashSet<String> required, List<String> warnings) {
        Content content = body.getContent();
        io.swagger.v3.oas.models.media.MediaType json =
                content == null ? null : content.get("application/json");
        if (json == null || json.getSchema() == null) {
            return false; // 非 JSON 请求体（form-urlencoded / multipart）：执行器只发 JSON
        }
        Schema<?> resolved = derefSchema(json.getSchema(), components, new HashSet<>(), 0, warnings);
        if (resolved == null || resolved.getProperties() == null || resolved.getProperties().isEmpty()) {
            // ⚠️ 判据是「有 properties」而不是 type 字面量 == "object"：OpenAPI 3.1 里 type
            // 可以是数组（["object","null"]），而 swagger-models 的 getType() 只返回单值，
            // 按字面量判会把合法的 3.1 对象体误判成非对象。
            return false;
        }
        resolved.getProperties().forEach((key, value) -> {
            if (properties.has(key)) {
                warnings.add("请求体字段「" + key + "」与同名的 URL 参数冲突，已保留 URL 参数的定义");
                return;
            }
            properties.set(key, schemaToNode((Schema<?>) value, components, new HashSet<>(), 0, warnings));
        });
        if (resolved.getRequired() != null) {
            // 与 URL 参数同名而被跳过的 body 字段仍要进 required：properties 里有它（URL 参数
            // 那一版），且它确实是必填的。
            resolved.getRequired().forEach(required::add);
        }
        return true;
    }

    /**
     * 把 swagger 的 {@link Schema} 转成给 LLM 看的 JSON Schema 片段。
     *
     * <p>只提取有限字段（type/format/description/enum/items/properties/required），不直接
     * 序列化 Schema 对象——后者会带出一堆 null 字段与 {@code exampleSetFlag} 之类的内部噪声，
     * 白白占 LLM 的上下文。
     */
    private ObjectNode schemaToNode(Schema<?> schema, Components components,
            Set<String> refStack, int depth, List<String> warnings) {
        ObjectNode node = objectMapper.createObjectNode();
        Schema<?> resolved = derefSchema(schema, components, refStack, depth, warnings);
        if (resolved == null) {
            node.put("type", "object");
            return node;
        }
        String type = resolved.getType();
        if (type == null && resolved.getTypes() != null && !resolved.getTypes().isEmpty()) {
            type = resolved.getTypes().iterator().next(); // 3.1 的 type 可以是数组
        }
        node.put("type", type == null ? "string" : type);
        if (resolved.getFormat() != null) {
            node.put("format", resolved.getFormat());
        }
        if (resolved.getDescription() != null && !resolved.getDescription().isBlank()) {
            node.put("description", resolved.getDescription());
        }
        if (resolved.getEnum() != null && !resolved.getEnum().isEmpty()) {
            ArrayNode arr = node.putArray("enum");
            resolved.getEnum().forEach(v -> arr.add(String.valueOf(v)));
        }
        if (resolved.getItems() != null) {
            node.set("items", schemaToNode(resolved.getItems(), components, refStack, depth + 1, warnings));
        }
        if (resolved.getProperties() != null && !resolved.getProperties().isEmpty()) {
            ObjectNode props = node.putObject("properties");
            resolved.getProperties().forEach((k, v) ->
                    props.set(k, schemaToNode((Schema<?>) v, components, refStack, depth + 1, warnings)));
        }
        return node;
    }

    /**
     * 解本地 {@code #/components/schemas/X} 引用。跨文件与远程 $ref 一律不支持（那等于一条
     * 绕过 SsrfGuard 的出站路径，见设计文档 §2.1）。
     *
     * <p>⚠️ 递归返回后必须把 ref 从 {@code refStack} 里**移除**：否则两个兄弟字段引用同一个
     * schema（很常见，如两个字段都是 Address）会被误判成循环引用，第二个字段静默降级成
     * 空 object。栈语义 ≠ 访问过集合。
     */
    private Schema<?> derefSchema(Schema<?> schema, Components components,
            Set<String> refStack, int depth, List<String> warnings) {
        if (schema == null) {
            return null;
        }
        String ref = schema.get$ref();
        if (ref == null) {
            return schema;
        }
        if (depth >= MAX_REF_DEPTH) {
            warnings.add("引用嵌套过深（超过 " + MAX_REF_DEPTH + " 层），相关字段已降级为通用对象");
            return null;
        }
        if (!refStack.add(ref)) {
            warnings.add("检测到循环引用 " + ref + "，相关字段已降级为通用对象");
            return null;
        }
        try {
            String prefix = "#/components/schemas/";
            if (!ref.startsWith(prefix) || components == null || components.getSchemas() == null) {
                warnings.add("无法解析的引用 " + ref + "（只支持同文档内的 components/schemas），已降级为通用对象");
                return null;
            }
            Schema<?> target = components.getSchemas().get(ref.substring(prefix.length()));
            if (target == null) {
                warnings.add("引用 " + ref + " 指向的定义不存在，已降级为通用对象");
                return null;
            }
            return derefSchema(target, components, refStack, depth + 1, warnings);
        } finally {
            refStack.remove(ref);
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest='OpenApiToolMapper*Test'
```
预期：Task 3 与 Task 4 的测试全绿。

- [ ] **Step 5: 补一条兄弟字段共享 `$ref` 的测试（防上面那个坑退化）**

在 `OpenApiToolMapperSchemaTest` 里追加：

```java
    @Test
    void 两个字段引用同一个schema时都要被解析出来() {
        // ⚠️ 这条守的是 derefSchema 的 finally { refStack.remove(ref) }：把它删掉后，
        // 第二个引用同一 schema 的字段会被误判成循环引用、静默降级成空 object。
        // 两个字段共用一个 Address 之类的定义在真实 schema 里极其常见。
        String json = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"post":{"operationId":"a",
                   "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                     "properties":{
                       "from":{"$ref":"#/components/schemas/Addr"},
                       "to":{"$ref":"#/components/schemas/Addr"}}}}}}}}},
                 "components":{"schemas":{"Addr":{"type":"object",
                   "properties":{"city":{"type":"string"}}}}}}""";

        JsonNode schema = schemaOf(map(json).candidates().get(0));

        assertThat(schema.path("properties").path("from").path("properties").propertyNames())
                .contains("city");
        assertThat(schema.path("properties").path("to").path("properties").propertyNames())
                .contains("city");
    }
```

跑 `mvn test -Dtest=OpenApiToolMapperSchemaTest` 确认绿。然后**做一次变异验证**：把 `derefSchema` 的 `finally { refStack.remove(ref); }` 注释掉，确认本条转红，再恢复。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/openapi/OpenApiToolMapper.java \
        backend/src/test/java/com/qingyu/care/service/openapi/OpenApiToolMapperSchemaTest.java
git commit -m "feat(openapi): inputSchema 三来源平铺合并与本地 \$ref 解析

path/query 参数与 POST 的 json requestBody 平铺合并到顶层——HttpToolCallback 把整个
toolInput 当 body 发出去，嵌套字段 LLM 永远填不对位置。由此推出「requestBody 非对象
结构则跳过整个 operation」这个硬边界。

判据用「有 properties」而不是 type == object：3.1 里 type 可以是数组，按字面量判会把
合法的 3.1 对象体误判成非对象。

\$ref 只解本地，带深度上限与循环检测。⚠️ 递归返回后必须把 ref 移出栈，否则两个兄弟字段
引用同一 schema（极常见）会被误判成循环、第二个静默降级——已加测试并变异验证。"
```

---

## Task 5: 拉取门槛——`WebPageFetcher` 白名单参数化 + `OpenApiSchemaFetcher`

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/web/WebPageFetcher.java`
- Create: `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiSchemaFetcher.java`
- Test: `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiSchemaFetcherTest.java`

**Interfaces:**
- Consumes: `WebPageFetcher.downloadRaw`（既有）
- Produces: `public String OpenApiSchemaFetcher.fetch(String url)`；`WebPageFetcher` 新增重载 `downloadRaw(String, Set<String>)`

⚠️ **必须保留旧签名作为重载委托**：既有测试直接调用 `WebPageFetcher.requireAllowedContentType(MediaType)` 与 `WebPageFetcher.exchange(client, url)`（见 `WebPageFetcherTest`、`WebPageFetcherDownloadTest`）。改签名而不留重载会让一批既有测试编译失败。

- [ ] **Step 1: 给 `WebPageFetcher` 加重载**

在 `backend/src/main/java/com/qingyu/care/service/web/WebPageFetcher.java` 中做四处改动，**每处都保留原签名**：

```java
    public Downloaded downloadRaw(String url) {
        return downloadRaw(url, ALLOWED_TYPES);
    }

    /**
     * 同上，但允许调用方指定 Content-Type 白名单。
     *
     * <p>OpenAPI schema 的类型是 {@code application/json} / {@code application/yaml} 等，
     * 不在网页抓取的默认白名单里；参数化这一处即可复用整条出站防线（SSRF 钉 IP + 禁重定向
     * + 超时 + 5MB 上限），而不必复制第二份——安全代码复制必然漂移（卡片 Action URI 白名单
     * 分裂三处是本仓库的前车之鉴）。
     */
    public Downloaded downloadRaw(String url, Set<String> allowedTypes) {
        final String host = URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException("URL 缺少主机名: " + url);
        }
        final InetAddress[] pinned = SsrfGuard.resolveAndValidate(host);

        try (CloseableHttpClient httpClient = buildHttpClient(pinnedResolver(host, pinned))) {
            return exchange(httpClient, url, allowedTypes);
        } catch (IOException e) {
            throw new BusinessException("网页抓取失败: " + e.getMessage());
        }
    }
```

`exchange` 与 `requireAllowedContentType` 同样加重载（旧签名委托到新签名并传 `ALLOWED_TYPES`）：

```java
    static Downloaded exchange(CloseableHttpClient httpClient, String url) {
        return exchange(httpClient, url, ALLOWED_TYPES);
    }

    static Downloaded exchange(CloseableHttpClient httpClient, String url, Set<String> allowedTypes) {
        RestClient client = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();

        return client.get().uri(url).exchange((request, response) -> {
            int status = response.getStatusCode().value();
            requireSuccessStatus(status);
            MediaType contentType = response.getHeaders().getContentType();
            requireAllowedContentType(contentType, allowedTypes);
            Charset charset = contentType.getCharset();
            return new Downloaded(readLimited(response.getBody()),
                    charset == null ? null : charset.name(), status);
        });
    }

    static void requireAllowedContentType(MediaType contentType) {
        requireAllowedContentType(contentType, ALLOWED_TYPES);
    }

    static void requireAllowedContentType(MediaType contentType, Set<String> allowedTypes) {
        if (contentType == null
                || !allowedTypes.contains(contentType.getType() + "/" + contentType.getSubtype())) {
            throw new BusinessException("不支持的内容类型: " + contentType
                    + "（允许: " + String.join("、", allowedTypes) + "）");
        }
    }
```

⚠️ 错误消息保留「不支持的内容类型」前缀——既有测试断言的是这个前缀（`WebPageFetcherTest`、`WebPageFetcherDownloadTest` 各有几条），改掉前缀会让它们转红。

- [ ] **Step 2: 跑既有网页抓取测试确认零回归**

```bash
cd backend && mvn test -Dtest='WebPageFetcher*Test,RobotsCheckerTest,SiteCrawlServiceTest'
```
预期：全绿（重载保证既有调用点与测试都不用改）。**若有任何一条红，先修好再继续**——这条防线是网站知识库与站点爬取共用的。

- [ ] **Step 3: 写 `OpenApiSchemaFetcher` 的失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiSchemaFetcherTest.java`：

```java
package com.qingyu.care.service.openapi;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.web.WebPageFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * schema 拉取：复用网页抓取那条出站防线，只换 Content-Type 白名单。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class OpenApiSchemaFetcherTest {

    @Mock
    private WebPageFetcher webPageFetcher;

    @InjectMocks
    private OpenApiSchemaFetcher fetcher;

    @Test
    void 按声明的字符集解码正文() {
        when(webPageFetcher.downloadRaw(eq("https://x.com/openapi.json"), any()))
                .thenReturn(new WebPageFetcher.Downloaded(
                        "{\"openapi\":\"3.0.0\"}".getBytes(StandardCharsets.UTF_8), "UTF-8", 200));

        String text = fetcher.fetch("https://x.com/openapi.json");

        assertThat(text).contains("3.0.0");
    }

    @Test
    void 未声明字符集时按UTF8解码() {
        when(webPageFetcher.downloadRaw(any(), any()))
                .thenReturn(new WebPageFetcher.Downloaded(
                        "标题".getBytes(StandardCharsets.UTF_8), null, 200));

        assertThat(fetcher.fetch("https://x.com/openapi.json")).isEqualTo("标题");
    }

    @Test
    void 传给下层的白名单包含json与yaml且不含html() {
        when(webPageFetcher.downloadRaw(any(), any()))
                .thenReturn(new WebPageFetcher.Downloaded("{}".getBytes(StandardCharsets.UTF_8), null, 200));

        fetcher.fetch("https://x.com/openapi.json");

        assertThat(OpenApiSchemaFetcher.SCHEMA_TYPES)
                .contains("application/json", "application/yaml", "text/plain")
                .doesNotContain("text/html");
    }

    @Test
    void 下层失败时补上跳转提示() {
        // 禁重定向是硬安全取舍（重定向目标不再经 SsrfGuard 校验），而会 302 的 schema URL
        // 在真实世界很常见，只能靠消息质量补偿——否则用户只看到「网页返回 HTTP 302」，
        // 完全不知道该怎么办。
        when(webPageFetcher.downloadRaw(any(), any()))
                .thenThrow(new BusinessException("网页返回 HTTP 302"));

        assertThatThrownBy(() -> fetcher.fetch("https://x.com/openapi.json"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("302")
                .hasMessageContaining("最终地址");
    }
}
```

- [ ] **Step 4: 实现 `OpenApiSchemaFetcher`**

创建 `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiSchemaFetcher.java`：

```java
package com.qingyu.care.service.openapi;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.web.WebPageFetcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 从 URL 取 schema 文本。**不自己建 HTTP 客户端**——整条出站防线（SSRF 钉 IP + 禁重定向
 * + 超时 + 5MB 上限）复用 {@link WebPageFetcher#downloadRaw}，只换 Content-Type 白名单。
 *
 * <p>新开一条抓取路径就等于新开一个 SSRF 缺口，这是网站知识库那边就立下的规矩。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class OpenApiSchemaFetcher {

    /**
     * schema 常见的类型。⚠️ 含 {@code text/plain} 是因为 GitHub raw 用它下发；
     * 不含 {@code text/html}——那多半意味着填的是文档页而不是 schema 本身。
     */
    static final Set<String> SCHEMA_TYPES = Set.of(
            "application/json", "application/yaml", "application/x-yaml",
            "text/yaml", "text/x-yaml", "text/plain");

    private final WebPageFetcher webPageFetcher;

    public String fetch(String url) {
        WebPageFetcher.Downloaded downloaded;
        try {
            downloaded = webPageFetcher.downloadRaw(url, SCHEMA_TYPES);
        } catch (BusinessException e) {
            // 禁重定向是硬安全取舍，而会 302 的 schema URL 很常见——统一补一句可操作的提示，
            // 而不是把「网页返回 HTTP 302」原样丢给用户去猜。
            throw new BusinessException("拉取 schema 失败：" + e.getMessage()
                    + "。若该地址会跳转（3xx），请直接填写跳转后的最终地址");
        }
        Charset charset = downloaded.charsetName() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(downloaded.charsetName());
        return new String(downloaded.body(), charset);
    }
}
```

- [ ] **Step 5: 跑测试**

```bash
cd backend && mvn test -Dtest='OpenApiSchemaFetcherTest,WebPageFetcher*Test'
```
预期：全绿。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/web/WebPageFetcher.java \
        backend/src/main/java/com/qingyu/care/service/openapi/OpenApiSchemaFetcher.java \
        backend/src/test/java/com/qingyu/care/service/openapi/OpenApiSchemaFetcherTest.java
git commit -m "feat(openapi): 按 URL 拉取 schema，复用网页抓取的出站防线

Content-Type 白名单参数化（加重载、保留旧签名，既有测试与调用点零改动），schema 类型
走 json/yaml/text-plain 一套。不复制第二份出站防线：安全代码复制必然漂移，卡片 Action
URI 白名单分裂三处是本仓库的前车之鉴。

禁重定向是硬安全取舍（重定向目标不再经 SsrfGuard 校验），而会 302 的 schema URL 很常见，
故统一补一句「请填写跳转后的最终地址」——否则用户只看到 HTTP 302 无从下手。"
```

---

## Task 6: 编排服务与端点

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/openapi/OpenApiParseRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/openapi/OpenApiParseResponse.java`
- Create: `backend/src/main/java/com/qingyu/care/dto/openapi/OpenApiImportRequest.java`
- Create: `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiImportService.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/AiToolController.java`
- Test: `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiImportServiceTest.java`

**Interfaces:**
- Consumes: `OpenApiSchemaParser.parse`、`OpenApiToolMapper.map`、`OpenApiSchemaFetcher.fetch`、`AiToolRepository`
- Produces:
  - `OpenApiParseResponse OpenApiImportService.parse(OpenApiParseRequest)`
  - `List<AiTool> OpenApiImportService.importTools(OpenApiImportRequest)`
  - 端点 `POST /v1/ai-tools/openapi/parse`、`POST /v1/ai-tools/openapi/import`（Task 7 的 IT 与 Task 8 的前端消费它们）

- [ ] **Step 1: 建三个 DTO**

`OpenApiParseRequest.java`：

```java
package com.qingyu.care.dto.openapi;

import lombok.Data;

/**
 * 解析请求：{@code schemaText} 与 {@code schemaUrl} 二选一。
 *
 * @author ruan
 */
@Data
public class OpenApiParseRequest {

    /** 直接粘贴的 schema 文本（JSON 或 YAML） */
    private String schemaText;

    /** 或者给一个可拉取的地址。⚠️ 走 SsrfGuard 与禁重定向，见 OpenApiSchemaFetcher */
    private String schemaUrl;

    /**
     * 服务器地址。留空时取 schema 的 {@code servers[0].url}，若那也不是绝对地址则由
     * mapper 记全局 warning 要求用户填。⚠️ **绝不猜成 schemaUrl 的 origin**：schema 托管
     * 地址与 API 实际地址不同是常态，猜测会静默生成一批指向错误主机的工具。
     */
    private String baseUrl;
}
```

`OpenApiParseResponse.java`：

```java
package com.qingyu.care.dto.openapi;

import java.util.List;

/**
 * @param candidates     可导入的候选工具（已标好 nameConflict）
 * @param globalWarnings 「压根没能成为候选」的说明（被跳过的方法、非对象请求体、超上限截断）
 * @param truncated      是否因超过上限而截断
 * @author ruan
 */
public record OpenApiParseResponse(List<OpenApiToolCandidate> candidates,
                                   List<String> globalWarnings,
                                   boolean truncated) {
}
```

`OpenApiImportRequest.java`：

```java
package com.qingyu.care.dto.openapi;

import lombok.Data;

import java.util.List;

/**
 * 导入请求：候选列表（前端可能已改过 name/description）+ 统一应用到本批的配置。
 *
 * <p>⚠️ 收候选列表而不是「schema + 勾选的 operationId」：后者要在后端重新解析一次，两次
 * 结果可能因 schema 内容变化或解析器版本差异而分叉，用户看到的与实际落库的会悄悄不一致。
 *
 * @author ruan
 */
@Data
public class OpenApiImportRequest {

    private List<OpenApiToolCandidate> candidates;

    /** 统一鉴权头，格式同 AiTool.authHeader（如 "Authorization: Bearer xxx"） */
    private String authHeader;

    private Integer timeoutMs;
    private Integer maxResponseChars;
    private Boolean enabled;
}
```

- [ ] **Step 2: 写失败测试**

创建 `backend/src/test/java/com/qingyu/care/service/openapi/OpenApiImportServiceTest.java`：

```java
package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiImportRequest;
import com.qingyu.care.dto.openapi.OpenApiParseRequest;
import com.qingyu.care.dto.openapi.OpenApiParseResponse;
import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AiToolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排层：取文本 → 解析 → 映射 → 冲突标记 / 批量落库。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiImportServiceTest {

    @Mock
    private OpenApiSchemaFetcher schemaFetcher;
    @Mock
    private AiToolRepository toolRepository;

    private OpenApiImportService service;

    private static final String SCHEMA = """
            {"openapi":"3.0.0","info":{"title":"t","version":"1"},
             "servers":[{"url":"https://api.example.com"}],
             "paths":{"/orders":{"get":{"operationId":"listOrders","summary":"列订单"}}}}""";

    private OpenApiImportService service() {
        ObjectMapper om = new ObjectMapper();
        return new OpenApiImportService(new OpenApiSchemaParser(), new OpenApiToolMapper(om),
                schemaFetcher, toolRepository);
    }

    @Test
    void 粘贴文本时不发任何出站请求() {
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        when(toolRepository.findByAppId(any())).thenReturn(List.of());
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates()).hasSize(1);
        verify(schemaFetcher, never()).fetch(any());
    }

    @Test
    void 未填文本也未填URL时报错() {
        service = service();

        assertThatThrownBy(() -> service.parse(new OpenApiParseRequest()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void baseUrl留空时取schema的servers第一项() {
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        when(toolRepository.findByAppId(any())).thenReturn(List.of());
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates().get(0).getUrlTemplate())
                .isEqualTo("https://api.example.com/orders");
    }

    @Test
    void 与既有工具重名的候选被标记冲突() {
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        AiTool existing = new AiTool();
        existing.setName("listOrders");
        when(toolRepository.findByAppId(any())).thenReturn(List.of(existing));
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates().get(0).isNameConflict()).isTrue();
    }

    @Test
    void 与平台共享工具重名同样算冲突() {
        // ChatToolService.resolve() 里同名工具会被静默跳过其中一个（只 log.warn），
        // 所以共享工具的名字同样是「占用」状态，不检出来的话导进去也挂不上
        service = service();
        OpenApiParseRequest req = new OpenApiParseRequest();
        req.setSchemaText(SCHEMA);
        AiTool shared = new AiTool();
        shared.setName("listOrders");
        when(toolRepository.findByAppId(any())).thenReturn(List.of());
        when(toolRepository.findAllSharedTools()).thenReturn(List.of(shared));

        OpenApiParseResponse res = service.parse(req);

        assertThat(res.candidates().get(0).isNameConflict()).isTrue();
    }

    @Test
    void 导入时强制租户归属且套用统一配置() {
        service = service();
        when(toolRepository.findByAppId(any())).thenReturn(List.of());
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());
        when(toolRepository.saveAll(any())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));

        OpenApiImportRequest req = new OpenApiImportRequest();
        req.setCandidates(List.of(candidate("listOrders")));
        req.setAuthHeader("Authorization: Bearer x");
        req.setTimeoutMs(8000);
        req.setMaxResponseChars(2000);
        req.setEnabled(true);

        service.importTools(req);

        ArgumentCaptor<List<AiTool>> captor = ArgumentCaptor.forClass(List.class);
        verify(toolRepository).saveAll(captor.capture());
        AiTool saved = captor.getValue().get(0);
        // scope 是客户端可控字段，必须由服务层强制 TENANT——不清掉的话租户能自封 PLATFORM，
        // 让自己的工具连同 authHeader 出现在所有租户的共享列表里（ai_tool 上的真实事故）
        assertThat(saved.getScope()).isEqualTo(OwnerScope.TENANT);
        assertThat(saved.getAuthHeader()).isEqualTo("Authorization: Bearer x");
        assertThat(saved.getTimeoutMs()).isEqualTo(8000);
        assertThat(saved.getId()).isNull();
    }

    @Test
    void 导入时再检一次冲突_有冲突则整批拒绝() {
        // parse 与 import 是两次请求，中间可能有并发创建；且界面拦得住的东西接口层拦不住
        service = service();
        AiTool existing = new AiTool();
        existing.setName("listOrders");
        when(toolRepository.findByAppId(any())).thenReturn(List.of(existing));
        when(toolRepository.findAllSharedTools()).thenReturn(List.of());

        OpenApiImportRequest req = new OpenApiImportRequest();
        req.setCandidates(List.of(candidate("listOrders")));

        assertThatThrownBy(() -> service.importTools(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("listOrders");
        verify(toolRepository, never()).saveAll(any());
    }

    @Test
    void 超过50条时拒绝整批() {
        service = service();
        List<OpenApiToolCandidate> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(candidate("tool" + i));
        }
        OpenApiImportRequest req = new OpenApiImportRequest();
        req.setCandidates(many);

        assertThatThrownBy(() -> service.importTools(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50");
    }

    @Test
    void 空候选列表被拒() {
        service = service();
        OpenApiImportRequest req = new OpenApiImportRequest();
        req.setCandidates(List.of());

        assertThatThrownBy(() -> service.importTools(req))
                .isInstanceOf(BusinessException.class);
    }

    private OpenApiToolCandidate candidate(String name) {
        OpenApiToolCandidate c = new OpenApiToolCandidate();
        c.setName(name);
        c.setDescription("说明");
        c.setHttpMethod("GET");
        c.setUrlTemplate("https://api.example.com/orders");
        c.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        return c;
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=OpenApiImportServiceTest
```
预期：编译失败。

- [ ] **Step 4: 实现 `OpenApiImportService`**

创建 `backend/src/main/java/com/qingyu/care/service/openapi/OpenApiImportService.java`：

```java
package com.qingyu.care.service.openapi;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.openapi.OpenApiImportRequest;
import com.qingyu.care.dto.openapi.OpenApiParseRequest;
import com.qingyu.care.dto.openapi.OpenApiParseResponse;
import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AiToolRepository;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAPI 导入的编排层：唯一碰数据库的组件。解析与映射都在无 IO 的纯组件里完成。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class OpenApiImportService {

    /** 一次导入的条数上限，防误操作把一份大 schema 整批灌进来 */
    static final int MAX_IMPORT = 50;

    private final OpenApiSchemaParser parser;
    private final OpenApiToolMapper mapper;
    private final OpenApiSchemaFetcher schemaFetcher;
    private final AiToolRepository toolRepository;

    /**
     * 解析并返回候选。**只读**：会查一次现有工具名做冲突标记，但绝不落任何行——用户会为
     * 调整配置反复解析同一份 schema，每次落库会留下大量垃圾。
     */
    public OpenApiParseResponse parse(OpenApiParseRequest request) {
        String text = request.getSchemaText();
        if (text == null || text.isBlank()) {
            if (request.getSchemaUrl() == null || request.getSchemaUrl().isBlank()) {
                throw new BusinessException("请粘贴 schema 内容或填写 schema 地址");
            }
            text = schemaFetcher.fetch(request.getSchemaUrl());
        }
        OpenAPI api = parser.parse(text);

        String baseUrl = request.getBaseUrl() != null && !request.getBaseUrl().isBlank()
                ? request.getBaseUrl().trim()
                : firstAbsoluteServer(api);

        OpenApiToolMapper.MapResult result = mapper.map(api, baseUrl);
        markConflicts(result.candidates());
        return new OpenApiParseResponse(result.candidates(), result.globalWarnings(), result.truncated());
    }

    @Transactional
    public List<AiTool> importTools(OpenApiImportRequest request) {
        List<OpenApiToolCandidate> candidates = request.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            throw new BusinessException("没有选中任何要导入的端点");
        }
        if (candidates.size() > MAX_IMPORT) {
            throw new BusinessException("一次最多导入 " + MAX_IMPORT + " 个工具，请分批操作");
        }
        // ⚠️ 再检一次冲突：parse 与 import 是两次请求，中间可能有并发创建；而且界面拦得住的
        // 东西接口层拦不住，门槛必须落在服务端。
        Set<String> taken = existingNames();
        Set<String> batch = new LinkedHashSet<>();
        for (OpenApiToolCandidate c : candidates) {
            if (c.getName() == null || c.getName().isBlank()) {
                throw new BusinessException("存在没有名字的工具，请先补全");
            }
            if (taken.contains(c.getName())) {
                throw new BusinessException("工具名「" + c.getName() + "」已存在，请改名后再导入");
            }
            if (!batch.add(c.getName())) {
                throw new BusinessException("本批里有重复的工具名「" + c.getName() + "」");
            }
        }

        List<AiTool> tools = new ArrayList<>();
        for (OpenApiToolCandidate c : candidates) {
            AiTool t = new AiTool();
            t.setName(c.getName());
            t.setDescription(c.getDescription());
            t.setHttpMethod(c.getHttpMethod());
            t.setUrlTemplate(c.getUrlTemplate());
            t.setInputSchema(c.getInputSchema());
            t.setAuthHeader(request.getAuthHeader());
            t.setTimeoutMs(request.getTimeoutMs() == null ? 5000 : request.getTimeoutMs());
            t.setMaxResponseChars(request.getMaxResponseChars() == null ? 4000 : request.getMaxResponseChars());
            t.setEnabled(request.getEnabled() == null || request.getEnabled());
            // ⚠️ 与 AiToolController.create 同款强制：scope 是客户端可控字段，不清掉的话
            // 租户能自封 PLATFORM，让自己的工具连同 authHeader 出现在所有租户的共享列表里。
            t.setScope(OwnerScope.TENANT);
            tools.add(t);
        }
        return toolRepository.saveAll(tools);
    }

    /** schema 的 servers[0]，只认绝对地址——相对路径（/v1）对出站请求毫无意义。 */
    private String firstAbsoluteServer(OpenAPI api) {
        if (api.getServers() == null || api.getServers().isEmpty()) {
            return "";
        }
        Server first = api.getServers().get(0);
        String url = first.getUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "";
        }
        return url;
    }

    private void markConflicts(List<OpenApiToolCandidate> candidates) {
        Set<String> taken = existingNames();
        candidates.forEach(c -> c.setNameConflict(taken.contains(c.getName())));
    }

    /**
     * 已被占用的工具名 = 本应用自有 + 平台共享。
     *
     * <p>⚠️ 必须算上平台共享工具：{@code ChatToolService.resolve()} 遇到重名会静默跳过其中
     * 一个（只 log.warn），所以与共享工具重名的工具即便导进去了也挂不上 LLM。
     */
    private Set<String> existingNames() {
        Set<String> names = new LinkedHashSet<>();
        toolRepository.findByAppId(TenantContext.getAppId()).forEach(t -> names.add(t.getName()));
        toolRepository.findAllSharedTools().forEach(t -> names.add(t.getName()));
        return names;
    }
}
```

⚠️ 实施时先确认 `TenantContext.getAppId()` 的确切方法名（读 `config/TenantContext.java`），与 `ChatToolService.listTools()` 取应用范围的写法保持一致。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=OpenApiImportServiceTest
```

- [ ] **Step 6: 加两个端点**

在 `backend/src/main/java/com/qingyu/care/controller/AiToolController.java` 里加字段与两个方法：

```java
    private final OpenApiImportService openApiImportService;

    /**
     * 解析 schema 返回候选，**不落库**。刻意不打 @AuditLog：它是只读的，且用户会为调整
     * 配置反复解析同一份 schema，打审计只会把日志刷满无信息量的行。
     */
    @PostMapping("/openapi/parse")
    public ApiResponse<OpenApiParseResponse> parseOpenApi(@RequestBody OpenApiParseRequest request) {
        return ApiResponse.success(openApiImportService.parse(request));
    }

    @AuditLog(action = "TOOL_IMPORT", targetType = "AiTool")
    @PostMapping("/openapi/import")
    public ApiResponse<List<AiTool>> importOpenApi(@RequestBody OpenApiImportRequest request) {
        List<AiTool> created = openApiImportService.importTools(request);
        return ApiResponse.success("已导入 " + created.size() + " 个工具", created);
    }
```

补 import：`com.qingyu.care.dto.openapi.*`、`com.qingyu.care.service.openapi.OpenApiImportService`。

- [ ] **Step 7: 全量单测 + 提交**

```bash
cd backend && mvn test
```
预期：全绿（含既有 792 条）。

```bash
git add backend/src/main/java/com/qingyu/care/dto/openapi/ \
        backend/src/main/java/com/qingyu/care/service/openapi/OpenApiImportService.java \
        backend/src/main/java/com/qingyu/care/controller/AiToolController.java \
        backend/src/test/java/com/qingyu/care/service/openapi/OpenApiImportServiceTest.java
git commit -m "feat(openapi): 解析与导入两个端点

parse 只读、不打审计（用户会反复解析同一份 schema 调配置）；import 整批一个事务，任一
失败全回滚——冲突已在 parse 阶段暴露过一轮，此处失败是小概率，而部分成功会让用户完全
不知道哪些进去了。

冲突集合含平台共享工具的名字：ChatToolService.resolve() 遇重名会静默跳过其中一个，
与共享工具重名的工具导进去也挂不上 LLM。

导入时强制 scope=TENANT，同 create 端点——scope 是客户端可控字段，不清掉租户可自封
PLATFORM 把自己的工具连同 authHeader 推给所有租户。"
```

---

## Task 7: 集成测试

**Files:**
- Create: `backend/src/test/resources/openapi/sample.json`
- Create: `backend/src/test/java/com/qingyu/care/it/OpenApiToolImportIT.java`

**Interfaces:**
- Consumes: Task 6 的两个端点

- [ ] **Step 0: 建测试用 schema 资源文件**

创建 `backend/src/test/resources/openapi/sample.json`——放资源文件而不是写进 Java 常量，是因为 schema 要经过「Java 字符串转义 → 再塞进 JSON 请求体转义」两层，手写必错且没人看得懂：

```json
{
  "openapi": "3.0.3",
  "info": {"title": "订单 API", "version": "1.0"},
  "servers": [{"url": "https://api.example.com"}],
  "paths": {
    "/orders/{orderId}": {
      "get": {
        "operationId": "getOrder",
        "summary": "查订单",
        "parameters": [
          {"name": "orderId", "in": "path", "required": true, "schema": {"type": "string"}}
        ]
      }
    }
  }
}
```

**⚠️ 跑之前先停掉本机 dev 后端（`:8080`）**，否则 `WebhookDeliveryIT` 必红（与 dev 后端共用 Redis 消费组 `webhook-workers`）。

- [ ] **Step 1: 写 IT**

创建 `backend/src/test/java/com/qingyu/care/it/OpenApiToolImportIT.java`：

```java
package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AiToolRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 导入的落库与隔离。
 *
 * <p>⚠️ 必须走真实 MockMvc 请求：多租户过滤器与 {@code TenantEntityListener} 的回填只在
 * 请求流经 {@code TenantInterceptor} 时生效，直调 service 断言会静默退化成永真。
 *
 * @author ruan
 */
class OpenApiToolImportIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AiToolRepository toolRepository;

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    private String createAdminAndLogin(Application app, String username) throws Exception {
        userRepository.saveAndFlush(User.builder()
                .tenant(app.getTenant())
                .username(username).password(BCrypt.hashpw("pwd123456"))
                .role(UserRole.TENANT_ADMIN).enabled(true).build());
        return login(username, "pwd123456");
    }

    /**
     * 把资源文件里的 schema 包成 parse 请求体。用 Jackson 生成而不是手工拼接转义——schema
     * 要经「Java 字符串 → JSON 请求体」两层转义，手拼必错且没人看得懂。
     */
    private String parseBody() throws Exception {
        String schema = new String(getClass().getResourceAsStream("/openapi/sample.json")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return om.writeValueAsString(java.util.Map.of("schemaText", schema));
    }

    @Test
    void parse不落任何行() throws Exception {
        Application app = seedApp("OAS_PARSE", "http://localhost");
        String token = createAdminAndLogin(app, "oas_parse_admin");
        long before = toolRepository.count();

        mockMvc.perform(post("/v1/ai-tools/openapi/parse")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(parseBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.candidates[0].name").value("getOrder"))
                .andExpect(jsonPath("$.data.candidates[0].urlTemplate")
                        .value("https://api.example.com/orders/{orderId}"));

        // 用户会为调整配置反复解析同一份 schema，落库会留下大量垃圾
        assertThat(toolRepository.count()).as("parse 必须是只读的").isEqualTo(before);
    }

    @Test
    void import落库且租户与应用字段正确() throws Exception {
        Application app = seedApp("OAS_IMPORT", "http://localhost");
        String token = createAdminAndLogin(app, "oas_import_admin");

        String body = """
                {"candidates":[{"name":"getOrder","description":"查订单","httpMethod":"GET",
                  "urlTemplate":"https://api.example.com/orders/{orderId}",
                  "inputSchema":"{}","warnings":[],"nameConflict":false}],
                 "authHeader":"Authorization: Bearer x","timeoutMs":8000,
                 "maxResponseChars":2000,"enabled":true}""";

        MvcResult r = mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        long id = om.readTree(r.getResponse().getContentAsString())
                .path("data").get(0).path("id").asLong();
        var saved = toolRepository.findById(id).orElseThrow();
        assertThat(saved.getTenantId()).as("@Async 之外的写入靠 TenantEntityListener 回填")
                .isEqualTo(app.getTenant().getId());
        assertThat(saved.getAppId()).isEqualTo(app.getId());
        assertThat(saved.getScope().name()).isEqualTo("TENANT");
        assertThat(saved.getAuthHeader()).isEqualTo("Authorization: Bearer x");
    }

    @Test
    void 同名工具在不同租户之间互不冲突() throws Exception {
        // 冲突检测是 filter-bound 的，只该看本租户；跨租户同名完全正常，拦下来是缺陷
        Application appA = seedApp("OAS_ISO_A", "http://localhost");
        Application appB = seedApp("OAS_ISO_B", "http://localhost");
        String tokenA = createAdminAndLogin(appA, "oas_iso_a_admin");
        String tokenB = createAdminAndLogin(appB, "oas_iso_b_admin");

        String body = """
                {"candidates":[{"name":"getOrder","description":"查订单","httpMethod":"GET",
                  "urlTemplate":"https://api.example.com/orders","inputSchema":"{}",
                  "warnings":[],"nameConflict":false}],"enabled":true}""";

        mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", tokenA).header("X-App-Id", appA.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", tokenB).header("X-App-Id", appB.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 本租户重名时整批拒绝且一行都不落() throws Exception {
        Application app = seedApp("OAS_DUP", "http://localhost");
        String token = createAdminAndLogin(app, "oas_dup_admin");

        String body = """
                {"candidates":[
                  {"name":"dupTool","description":"a","httpMethod":"GET",
                   "urlTemplate":"https://api.example.com/a","inputSchema":"{}",
                   "warnings":[],"nameConflict":false},
                  {"name":"newTool","description":"b","httpMethod":"GET",
                   "urlTemplate":"https://api.example.com/b","inputSchema":"{}",
                   "warnings":[],"nameConflict":false}],"enabled":true}""";

        mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(200));

        long after = toolRepository.count();
        // 再导一次：dupTool 冲突 → 整批回滚，newTool2 也不该落库
        String second = body.replace("\"newTool\"", "\"newTool2\"");
        mockMvc.perform(post("/v1/ai-tools/openapi/import")
                        .header("satoken", token).header("X-App-Id", app.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        assertThat(toolRepository.count()).as("整批一个事务，任一冲突则一行都不落")
                .isEqualTo(after);
    }
}
```

- [ ] **Step 2: 跑 IT**

```bash
# 先确认 8080 没有 dev 后端在跑
netstat -ano | grep ":8080 " | grep LISTENING
cd backend && mvn verify -Dit.test=OpenApiToolImportIT
```
预期：4 条全绿。⚠️ Failsafe 用 `it.test` 而不是 `test`。

- [ ] **Step 3: 跑全量 verify**

```bash
cd backend && mvn verify
```
预期：单测 + IT 全绿（基线：单测 792+、IT 143+）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/OpenApiToolImportIT.java
git commit -m "test(openapi): 导入落库、只读 parse 与跨租户隔离的 IT

parse 只读用「表行数不变」钉住；跨租户同名必须允许（冲突检测只该看本租户，拦下来是缺陷）；
本租户重名时整批回滚——用「第二批里那个不冲突的工具也没落库」来验证事务边界，只断言接口
返回 400 是测不出回滚的。"
```

---

## Task 8: 前端 API 层与导入对话框

**Files:**
- Modify: `frontend/src/api/chat-tool.ts`
- Create: `frontend/src/components/tools/OpenApiImportDialog.tsx`
- Modify: `frontend/src/pages/ToolConfigPage.tsx`

**Interfaces:**
- Consumes: Task 6 的两个端点
- Produces: `openApiImportService.parse/import`；`<OpenApiImportDialog open onOpenChange onImported />`（Task 9 的测试消费它）

- [ ] **Step 1: 扩 api 层**

在 `frontend/src/api/chat-tool.ts` 末尾加类型与 service：

```typescript
export interface OpenApiToolCandidate {
    name: string;
    description: string;
    httpMethod: string;
    urlTemplate: string;
    inputSchema: string;
    warnings: string[];
    nameConflict: boolean;
}

export interface OpenApiParseResponse {
    candidates: OpenApiToolCandidate[];
    /** 「压根没能成为候选」的说明：被跳过的方法、非对象请求体、超上限截断 */
    globalWarnings: string[];
    truncated: boolean;
}

export const openApiImportService = {
    parse: (data: { schemaText?: string; schemaUrl?: string; baseUrl?: string }):
        Promise<ApiResponse<OpenApiParseResponse>> => api.post('/ai-tools/openapi/parse', data),
    import: (data: {
        candidates: OpenApiToolCandidate[];
        authHeader?: string;
        timeoutMs?: number;
        maxResponseChars?: number;
        enabled?: boolean;
    }): Promise<ApiResponse<AiTool[]>> => api.post('/ai-tools/openapi/import', data),
};
```

- [ ] **Step 2: 写导入对话框**

创建 `frontend/src/components/tools/OpenApiImportDialog.tsx`：

```tsx
import React, { useState } from 'react';
import { throwIfFailed } from '@/lib/apiResult';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
    Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { openApiImportService } from '@/api/chat-tool';
import type { OpenApiToolCandidate } from '@/api/chat-tool';
import { useToast } from '@/hooks/use-toast';

interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    /** 导入成功后通知父组件刷新列表 */
    onImported: () => void;
}

/**
 * 从 OpenAPI schema 批量导入工具。一个对话框内三步：输入 → 预览勾选 → 提交。
 *
 * ⚠️ 改 baseUrl 后必须重新解析：urlTemplate 是在后端映射时拼好的，前端不做二次拼接
 * ——那会变成第二份映射规则，与后端各自漂移。
 */
const OpenApiImportDialog: React.FC<Props> = ({ open, onOpenChange, onImported }) => {
    const [schemaText, setSchemaText] = useState('');
    const [schemaUrl, setSchemaUrl] = useState('');
    const [baseUrl, setBaseUrl] = useState('');
    const [authHeader, setAuthHeader] = useState('');
    const [candidates, setCandidates] = useState<OpenApiToolCandidate[]>([]);
    const [globalWarnings, setGlobalWarnings] = useState<string[]>([]);
    const [selected, setSelected] = useState<Set<number>>(new Set());
    const [isBusy, setIsBusy] = useState(false);
    const { toast } = useToast();

    const reset = () => {
        setSchemaText(''); setSchemaUrl(''); setBaseUrl(''); setAuthHeader('');
        setCandidates([]); setGlobalWarnings([]); setSelected(new Set());
    };

    const handleParse = async () => {
        try {
            setIsBusy(true);
            const res = await openApiImportService.parse({
                schemaText: schemaText || undefined,
                schemaUrl: schemaUrl || undefined,
                baseUrl: baseUrl || undefined,
            });
            throwIfFailed(res);
            setCandidates(res.data.candidates);
            setGlobalWarnings(res.data.globalWarnings);
            // 默认全选无冲突的
            setSelected(new Set(res.data.candidates
                .map((c, i) => (c.nameConflict ? -1 : i)).filter(i => i >= 0)));
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '解析失败', description: msg });
        } finally {
            setIsBusy(false);
        }
    };

    const handleImport = async () => {
        const picked = candidates.filter((_, i) => selected.has(i));
        if (picked.length === 0) {
            toast({ variant: 'destructive', title: '请至少勾选一个端点' });
            return;
        }
        try {
            setIsBusy(true);
            const res = await openApiImportService.import({
                candidates: picked,
                authHeader: authHeader || undefined,
                enabled: true,
            });
            throwIfFailed(res);
            toast({ title: `已导入 ${picked.length} 个工具` });
            reset();
            onOpenChange(false);
            onImported();
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ variant: 'destructive', title: '导入失败', description: msg });
        } finally {
            setIsBusy(false);
        }
    };

    const updateCandidate = (index: number, patch: Partial<OpenApiToolCandidate>) => {
        setCandidates(prev => prev.map((c, i) => (i === index ? { ...c, ...patch } : c)));
    };

    const toggle = (index: number) => {
        setSelected(prev => {
            const next = new Set(prev);
            if (next.has(index)) { next.delete(index); } else { next.add(index); }
            return next;
        });
    };

    return (
        <Dialog open={open} onOpenChange={(v) => { if (!v) { reset(); } onOpenChange(v); }}>
            <DialogContent className="max-w-3xl">
                <DialogHeader>
                    <DialogTitle>从 OpenAPI 导入工具</DialogTitle>
                    <DialogDescription>
                        支持 OpenAPI 3.x（JSON / YAML）。只会导入 GET 与 POST 端点。
                    </DialogDescription>
                </DialogHeader>

                <div className="space-y-3 max-h-[60vh] overflow-y-auto">
                    <label className="text-sm font-medium">粘贴 schema 内容</label>
                    <textarea
                        className="w-full h-32 rounded-md border p-2 text-sm font-mono"
                        value={schemaText}
                        onChange={(e) => setSchemaText(e.target.value)}
                        placeholder='{"openapi":"3.0.0", ...}'
                    />
                    <label className="text-sm font-medium">或填写 schema 地址</label>
                    <Input value={schemaUrl} onChange={(e) => setSchemaUrl(e.target.value)}
                        placeholder="https://api.example.com/openapi.json" />
                    <label className="text-sm font-medium">服务器地址（留空则取 schema 的 servers）</label>
                    <Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)}
                        placeholder="https://api.example.com" />
                    <Button onClick={handleParse} disabled={isBusy}>解析</Button>

                    {globalWarnings.length > 0 && (
                        <div className="rounded-md border border-amber-300 bg-amber-50 p-2 text-xs">
                            {globalWarnings.map((w, i) => <div key={i}>{w}</div>)}
                        </div>
                    )}

                    {candidates.length > 0 && (
                        <>
                            <label className="text-sm font-medium">鉴权头（统一应用到本批全部工具，可选）</label>
                            <Input type="password" value={authHeader}
                                onChange={(e) => setAuthHeader(e.target.value)}
                                placeholder="Authorization: Bearer xxx" />
                            <div className="space-y-2">
                                {candidates.map((c, i) => (
                                    <div key={i} className="rounded-md border p-2 space-y-1">
                                        <div className="flex items-center gap-2">
                                            <input type="checkbox" checked={selected.has(i)}
                                                onChange={() => toggle(i)} aria-label={`选择 ${c.name}`} />
                                            <Badge>{c.httpMethod}</Badge>
                                            <Input value={c.name} aria-label={`名称 ${i}`}
                                                onChange={(e) => updateCandidate(i, { name: e.target.value })}
                                                className={c.nameConflict ? 'border-destructive' : ''} />
                                        </div>
                                        <Input value={c.description} aria-label={`描述 ${i}`}
                                            onChange={(e) => updateCandidate(i, { description: e.target.value })} />
                                        <div className="text-xs text-muted-foreground break-all">{c.urlTemplate}</div>
                                        {c.nameConflict && (
                                            <div className="text-xs text-destructive">名称与已有工具重复，请修改后再导入</div>
                                        )}
                                        {c.warnings.map((w, wi) => (
                                            <div key={wi} className="text-xs text-amber-600">{w}</div>
                                        ))}
                                    </div>
                                ))}
                            </div>
                        </>
                    )}
                </div>

                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
                    <Button onClick={handleImport} disabled={isBusy || candidates.length === 0}>
                        导入选中项
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default OpenApiImportDialog;
```

- [ ] **Step 3: 在 `ToolConfigPage` 加入口**

在 `frontend/src/pages/ToolConfigPage.tsx`：

1. 加 import：`import OpenApiImportDialog from '@/components/tools/OpenApiImportDialog';`
2. 加状态：`const [isImportOpen, setIsImportOpen] = useState(false);`
3. 在「新建工具」按钮旁加：

```tsx
                <Button variant="outline" onClick={() => setIsImportOpen(true)}>
                    从 OpenAPI 导入
                </Button>
```

4. 在组件返回的 JSX 末尾（与既有 Dialog 并列）加：

```tsx
            <OpenApiImportDialog
                open={isImportOpen}
                onOpenChange={setIsImportOpen}
                onImported={fetchTools}
            />
```

- [ ] **Step 4: 构建与 lint**

```bash
cd frontend && pnpm lint && pnpm build
```
预期：均通过，lint 的既有 error 基线**零新增**。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/api/chat-tool.ts \
        frontend/src/components/tools/OpenApiImportDialog.tsx \
        frontend/src/pages/ToolConfigPage.tsx
git commit -m "feat(openapi): 工具页的导入对话框

三步在一个 Dialog 内完成：输入（粘贴/URL）→ 预览勾选并可改名字与描述 → 提交。
候选级 warning 就近显示在每一行上，全局 warning（被跳过的方法等）单独一块——不这样
被跳过的端点会无声消失，用户只会觉得「我那个端点怎么没导进来」。

改 baseUrl 后必须重新解析：urlTemplate 由后端映射时拼好，前端不做二次拼接，否则会变成
第二份会漂移的映射规则。"
```

---

## Task 9: 前端回归测试

**Files:**
- Create: `frontend/src/components/tools/OpenApiImportDialog.test.tsx`

**Interfaces:**
- Consumes: Task 8 的 `OpenApiImportDialog`

- [ ] **Step 1: 写测试**

创建 `frontend/src/components/tools/OpenApiImportDialog.test.tsx`：

```tsx
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '@/types';
import type { OpenApiParseResponse, OpenApiToolCandidate } from '@/api/chat-tool';
import { openApiImportService } from '@/api/chat-tool';
import OpenApiImportDialog from './OpenApiImportDialog';

/**
 * 导入对话框的两条写操作。守的是本仓库的全局约定：业务异常是「HTTP 200 + body.code」，
 * axios 不会 reject，写操作不显式判 code 就会把后端错误静默吞掉（用户点按钮毫无反应）。
 *
 * ⚠️ 不把 Dialog 桩成 () => null：本组件的解析与导入都由对话框内部的回调驱动，桩成空
 * 组件会让这两条路径静默零覆盖而测试照样全绿（CrawlSiteDialog 的前车之鉴）。这里直接用
 * 真实的 Radix Dialog——它在 jsdom 下能渲染，只是 portal 到 body。
 */

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }));

vi.mock('@/hooks/use-toast', () => ({
    useToast: () => ({ toast: toastSpy }),
}));

vi.mock('@/api/chat-tool', () => ({
    openApiImportService: {
        parse: vi.fn(),
        import: vi.fn(),
    },
}));

const ok = <T,>(data: T): ApiResponse<T> => ({
    code: 200, message: 'success', data, timestamp: '2026-08-23T00:00:00',
});

const fail = <T,>(message: string): ApiResponse<T> => ({
    code: 400, message, data: null as unknown as T, timestamp: '2026-08-23T00:00:00',
});

const candidate = (name: string, conflict = false): OpenApiToolCandidate => ({
    name,
    description: '查订单',
    httpMethod: 'GET',
    urlTemplate: 'https://api.example.com/orders/{orderId}',
    inputSchema: '{"type":"object","properties":{}}',
    warnings: [],
    nameConflict: conflict,
});

const flush = async () => {
    await act(async () => { await Promise.resolve(); });
};

const onImported = vi.fn();

const renderDialog = () =>
    render(<OpenApiImportDialog open onOpenChange={() => {}} onImported={onImported} />);

beforeEach(() => {
    toastSpy.mockReset();
    onImported.mockReset();
    vi.mocked(openApiImportService.parse).mockReset();
    vi.mocked(openApiImportService.import).mockReset();
});

afterEach(() => {
    vi.clearAllMocks();
});

describe('OpenApiImportDialog', () => {
    it('解析失败时弹 destructive toast 且不显示候选列表', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(
            fail<OpenApiParseResponse>('仅支持 OpenAPI 3.x，检测到 Swagger 2.0'));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '解析失败',
            description: '仅支持 OpenAPI 3.x，检测到 Swagger 2.0',
        }));
        expect(screen.queryByLabelText('名称 0')).not.toBeInTheDocument();
    });

    it('解析成功后展示候选与全局 warning', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('getOrder')],
            globalWarnings: ['已跳过 DELETE /orders/{id}：本平台的工具只支持 GET 与 POST'],
            truncated: false,
        }));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();

        expect(screen.getByDisplayValue('getOrder')).toBeInTheDocument();
        // 全局 warning 必须可见：被跳过的端点没有候选行可挂，不显示就等于无声消失
        expect(screen.getByText(/已跳过 DELETE/)).toBeInTheDocument();
    });

    it('导入失败时弹 destructive toast 且不通知父组件刷新', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('getOrder')], globalWarnings: [], truncated: false,
        }));
        vi.mocked(openApiImportService.import).mockResolvedValue(
            fail('工具名「getOrder」已存在，请改名后再导入'));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();
        fireEvent.click(screen.getByRole('button', { name: '导入选中项' }));
        await flush();

        expect(toastSpy).toHaveBeenCalledWith(expect.objectContaining({
            variant: 'destructive',
            title: '导入失败',
            description: '工具名「getOrder」已存在，请改名后再导入',
        }));
        expect(onImported).not.toHaveBeenCalled();
    });

    it('导入成功后通知父组件刷新列表', async () => {
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('getOrder')], globalWarnings: [], truncated: false,
        }));
        vi.mocked(openApiImportService.import).mockResolvedValue(ok([]));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();
        fireEvent.click(screen.getByRole('button', { name: '导入选中项' }));
        await flush();

        expect(onImported).toHaveBeenCalledTimes(1);
        expect(toastSpy).not.toHaveBeenCalledWith(expect.objectContaining({ variant: 'destructive' }));
    });

    it('名字冲突的候选默认不勾选', async () => {
        // 默认全选会让用户一路点下去撞上整批拒绝，且看不出是哪一条的问题
        vi.mocked(openApiImportService.parse).mockResolvedValue(ok<OpenApiParseResponse>({
            candidates: [candidate('dup', true)], globalWarnings: [], truncated: false,
        }));

        renderDialog();
        fireEvent.click(screen.getByRole('button', { name: '解析' }));
        await flush();

        expect(screen.getByLabelText('选择 dup')).not.toBeChecked();
        expect(screen.getByText('名称与已有工具重复，请修改后再导入')).toBeInTheDocument();
    });
});
```

- [ ] **Step 2: 跑测试**

```bash
cd frontend && pnpm test
```
预期：新增 5 条全绿，既有 63 条不受影响。

⚠️ 若 Radix Dialog 在 jsdom 下渲染有问题（找不到按钮），改成把 `@/components/ui/dialog` 桩成「只在 `open` 时渲染 children、暴露回调触发点」的极简 stub（模板见 `CrawlSiteList.test.tsx`），**不要**桩成 `() => null`。

- [ ] **Step 3: 变异验证**

把 `OpenApiImportDialog.handleParse` 里的 `throwIfFailed(res);` 临时删掉，跑测试，确认「解析失败时弹 destructive toast」转红；恢复后再跑一次确认全绿。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/components/tools/OpenApiImportDialog.test.tsx
git commit -m "test(openapi): 导入对话框的写操作回归

按「改到哪补到哪」的常态约定补齐。守的是全局约定：业务异常是 HTTP 200 + body.code，
不显式判就静默吞掉。另钉住两条产品判断：全局 warning 必须可见（被跳过的端点没有候选行
可挂，不显示就无声消失）、冲突项默认不勾选（默认全选会让用户撞上整批拒绝还看不出是谁）。

变异验证：删掉 handleParse 里的 throwIfFailed，解析失败那条转红。"
```

---

## Task 10: 文档收口

**Files:**
- Modify: `CLAUDE.md`
- Modify: `SaaS平台规划.md`
- Modify: `scripts/manual-smoke-hints.sh`

- [ ] **Step 1: CLAUDE.md 加手工验证一节**

在「改动 MCP 接入链路后必须手工验证」一节**之后**插入：

```markdown
### 改动 OpenAPI 工具导入链路后必须手工验证
自动化测试覆盖不到的四条：
1. **真实公开 schema 走通全链**：导入 → 勾选 → 绑进 skill → 对话触发 → 确认出站 URL 与参数正确。单测只能证明映射规则对，证明不了「生成出来的工具真的能被 LLM 调用并拿到结果」。
2. **可选参数不填时端到端正确**：提一个不涉及可选参数的问题，确认出站 URL 里既没有残留 `{占位符}`、也没有半截的 query 段。`UrlTemplateFillerTest` 钉的是纯函数，这条钉的是它真的被接在链路上。
3. **YAML schema 能导入**：JSON 与 YAML 走解析器的两条不同分支。
4. **会 302 的 schema URL 给出可读错误**：禁重定向是硬安全取舍（重定向目标不再经 `SsrfGuard` 校验），只能靠错误消息补偿——必须明确说「请填写跳转后的最终地址」，而不是丢一个含糊的失败。
```

同时在「架构要点」里工具相关位置补一段说明 `UrlTemplateFiller` 的两段语义（path 缺值抛异常、query 缺值删整段），以及「OpenAPI 导入只收 GET/POST、名字截断 64」这两条最容易写错的规则。

- [ ] **Step 2: 更新规划**

在 `SaaS平台规划.md` 中：
1. 把 5.2 的 `- [ ] **OpenAPI Schema 自动解析生成工具表单**` 改成 `- [x]`，并追加一段落地说明（参照该文件里 MCP、站点爬取那两条的写法，点明关键取舍与已知限制，附 spec 路径）。
2. 更新「当前状态」的待办段落：Phase 5.2 中期全部完成（多智能体编排已标暂缓）。

- [ ] **Step 3: 更新手工验证提示脚本**

在 `scripts/manual-smoke-hints.sh` 里加映射：`backend/src/main/java/com/qingyu/care/service/openapi/`、`backend/src/main/java/com/qingyu/care/service/tool/UrlTemplateFiller.java`、`frontend/src/components/tools/OpenApiImportDialog.tsx` → 「改动 OpenAPI 工具导入链路后必须手工验证」。

⚠️ 该脚本只存「路径 → 章节标题」的映射、**不复述条目内容**；章节标题在 CLAUDE.md 里找不到时脚本 exit 1，所以 Step 1 的标题必须与这里写的**逐字一致**。改完跑一次验证：

```bash
./scripts/manual-smoke-hints.sh master...HEAD
```
预期：列出新章节标题，exit 0。

- [ ] **Step 4: 全量验证**

```bash
cd backend && mvn verify          # 先确认 8080 没有 dev 后端
cd ../frontend && pnpm lint && pnpm test && pnpm build
```
预期：全绿。

- [ ] **Step 5: 提交**

```bash
git add CLAUDE.md SaaS平台规划.md scripts/manual-smoke-hints.sh
git commit -m "docs(openapi): 手工验证清单、规划勾选与提示脚本映射

四条手工验证都是自动化覆盖不到的：全链真的能被 LLM 调用、可选参数不填时出站 URL 干净、
YAML 分支、302 的可读错误。"
```

- [ ] **Step 6: 真机手工验证（按上面那四条逐条走）**

⚠️ **这一步不能跳**。本仓库反复记载的教训是「真机验证抓缺陷的密度远高于代码评审」，MCP 那次合入后补做真机验证挖出 3 个缺陷，多媒体消息那次抓到的两个缺陷 8 轮代码评审全部漏掉。

推荐用公开 schema：Swagger Petstore（`https://petstore3.swagger.io/api/v3/openapi.json`）。走完后把结果（含发现的问题）记进最终提交或反馈给用户。


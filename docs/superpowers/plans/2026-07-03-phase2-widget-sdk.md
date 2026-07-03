---
title: Phase 2 · JS Widget SDK 实现计划
author: ruan
date: 2026-07-03
status: 待执行
spec: docs/superpowers/specs/2026-07-03-phase2-widget-sdk-design.md
---

# JS Widget SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **本项目协作约定**：子代理**不自行 git 提交**；每个 Task 末尾的「提交」步骤由**编排者**在该单元通过「spec 审查 + 质量审查」双审后，按显式文件路径提交到分支 `feature/phase2-widget-sdk`。全部完成后 `--ff-only` 合并 master 并删分支。

**Goal:** 交付一行 `<script>` 即可嵌入任意网站的浏览器聊天挂件，复用已完成的开放 REST API，支持匿名访客多轮对话与转人工。

**Architecture:** 后端扩展开放 API 认证（appKey + Origin 域名白名单，与现有 appKey+secret 认证在拦截器分流），新增公开 `/open/v1/widget/config` 端点，`/completions` 对失效 sessionId 显式报错；新建独立 `widget/`（Vanilla TS + Vite lib + Shadow DOM）项目，产出单文件 IIFE `widget.js` 由后端 `static/` 托管并**提交入库**；管理后台 `IntegrationPage` 增 allowedOrigins 编辑 + 真实嵌入片段。

**Tech Stack:** 后端 Spring Boot 3 + JPA + JUnit5/Mockito/AssertJ；Widget 用 Vite 7 + TypeScript 5.9 + Vitest + jsdom（pnpm）；管理后台 React 19 + Vite。

---

## File Structure

**后端（`backend/src/main/java/com/qingyu/care/`）**
- `entity/Application.java` — 修改：加 `allowedOrigins` 字段
- `service/OpenApiAuthService.java` — 修改：加 `authenticateByOrigin(appId, origin)`
- `service/ChatService.java` + `service/impl/ChatServiceImpl.java` — 修改：加 `sessionExists(sessionId)`
- `config/OpenApiAuthInterceptor.java` — 修改：认证分流（X-Api-Key vs Origin）
- `config/WebConfig.java` — 修改：给 `/widget.js` 加较短缓存的静态资源处理
- `controller/open/OpenWidgetController.java` — 新建：`GET /open/v1/widget/config`
- `dto/open/OpenWidgetConfigResponse.java` — 新建：品牌信息响应体
- `controller/open/OpenChatController.java` — 修改：`/completions` 对失效 sessionId 抛 404
- `controller/ApplicationController.java` — 修改：`update` 透传 `allowedOrigins`

**后端测试（`backend/src/test/java/com/qingyu/care/`）**
- `service/OpenApiAuthServiceTest.java` — 修改：增补 `authenticateByOrigin` 用例

**Widget（新建 `widget/`）**
- `package.json` / `vite.config.ts` / `tsconfig.json` / `index.html`
- `src/config.ts`（+ `src/config.test.ts`）— 解析 appKey / apiBase
- `src/session.ts`（+ `src/session.test.ts`）— localStorage 会话（24h 过期 + 容错）
- `src/api.ts`（+ `src/api.test.ts`）— 开放 API 客户端（含失效识别）
- `src/styles.ts` — Shadow DOM CSS 字符串
- `src/ui.ts` — Shadow DOM 渲染（气泡 / 面板 / 消息 / 输入 / 转人工）
- `src/main.ts` — 装配入口
- 构建产物：`backend/src/main/resources/static/widget.js`（**提交入库**）

**管理后台（`frontend/src/`）**
- `api/integration.ts` — 修改：`AppDetail` 加 `allowedOrigins` + `updateAllowedOrigins`
- `pages/IntegrationPage.tsx` — 修改：allowedOrigins 编辑 + 真实嵌入片段

**文档 / 配置**
- `widget/README.md` — 构建说明
- `.gitignore` — 确认不忽略产物

---

## 里程碑 A：后端认证扩展与端点

### Task 1: Application 加 allowedOrigins 字段

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/entity/Application.java`

- [ ] **Step 1: 在实体加字段**

在 `rateLimitPerMinute` 字段之后（第 87 行后）插入：

```java
    /**
     * Widget 允许的来源域名白名单（逗号分隔，如 https://a.com,https://www.a.com）。
     * 为空表示未开放 Widget 接入。
     */
    @Column(name = "allowed_origins", columnDefinition = "TEXT")
    private String allowedOrigins;
```

- [ ] **Step 2: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS（`ddl-auto: update` 会在启动时自动为 `sys_application` 加 `allowed_origins` 列，旧行 NULL 向后兼容）

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/Application.java
git commit -m "feat(widget): Application 新增 allowedOrigins 字段"
```

---

### Task 2: OpenApiAuthService.authenticateByOrigin（TDD）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/OpenApiAuthService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/OpenApiAuthServiceTest.java`

- [ ] **Step 1: 写失败测试**

在 `OpenApiAuthServiceTest` 类中追加以下用例（复用现有 `app(...)` 工厂，另加一个带 origins 的重载）：

```java
    private Application appWithOrigins(String origins, boolean enabled) {
        return Application.builder()
                .id(1L).name("测试应用").appKey("app_x").appSecret("sec").enabled(enabled)
                .allowedOrigins(origins)
                .tenant(Tenant.builder().id(9L).build())
                .build();
    }

    @Test
    void authenticateByOrigin_returnsApp_whenOriginWhitelisted() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("https://a.com, https://www.a.com", true)));

        Application result = service.authenticateByOrigin("app_x", "https://www.a.com");

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void authenticateByOrigin_401_whenAppIdMissing() {
        assertThatThrownBy(() -> service.authenticateByOrigin(null, "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticateByOrigin_401_whenAppKeyNotFound() {
        when(applicationRepository.findByAppKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticateByOrigin("nope", "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void authenticateByOrigin_403_whenAppDisabled() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("https://a.com", false)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void authenticateByOrigin_403_whenOriginsEmpty() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("  ", true)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", "https://a.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void authenticateByOrigin_403_whenOriginNotWhitelisted() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("https://a.com", true)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", "https://evil.com"))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void authenticateByOrigin_403_whenOriginNull() {
        when(applicationRepository.findByAppKey("app_x"))
                .thenReturn(Optional.of(appWithOrigins("https://a.com", true)));

        assertThatThrownBy(() -> service.authenticateByOrigin("app_x", null))
                .isInstanceOf(OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiException) e).getStatus()).isEqualTo(403));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=OpenApiAuthServiceTest test`
Expected: 编译失败或测试失败（`authenticateByOrigin` 尚不存在）

- [ ] **Step 3: 实现方法**

在 `OpenApiAuthService` 的 `authenticate(...)` 之后追加：

```java
    /**
     * Widget 浏览器认证：公开 appKey + Origin 域名白名单（不校验密钥）。
     * appId 缺失 → 401；appKey 不存在 → 401；应用停用 → 403；
     * 未配置 allowedOrigins → 403；origin 为空或不在白名单 → 403。
     */
    public Application authenticateByOrigin(String appId, String origin) {
        if (appId == null || appId.isBlank()) {
            throw new OpenApiException(401, "缺少 X-App-Id");
        }
        Application app = applicationRepository.findByAppKey(appId)
                .orElseThrow(() -> new OpenApiException(401, "无效的凭据"));
        if (Boolean.FALSE.equals(app.getEnabled())) {
            throw new OpenApiException(403, "应用已停用");
        }
        String allowed = app.getAllowedOrigins();
        if (allowed == null || allowed.isBlank()) {
            throw new OpenApiException(403, "该应用未开放 Widget 接入");
        }
        if (origin == null || origin.isBlank() || !isOriginAllowed(allowed, origin)) {
            throw new OpenApiException(403, "来源域名未授权");
        }
        return app;
    }

    /** 逗号拆分、trim 后精确匹配（首版不支持通配子域）。 */
    private boolean isOriginAllowed(String allowed, String origin) {
        for (String s : allowed.split(",")) {
            if (s.trim().equals(origin)) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=OpenApiAuthServiceTest test`
Expected: PASS（新增 7 个 + 原有 6 个用例全绿）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/OpenApiAuthService.java backend/src/test/java/com/qingyu/care/service/OpenApiAuthServiceTest.java
git commit -m "feat(widget): OpenApiAuthService 增加 Origin 白名单认证"
```

---

### Task 3: OpenApiAuthInterceptor 认证分流

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/config/OpenApiAuthInterceptor.java`

- [ ] **Step 1: 改 preHandle 认证分流**

将 `preHandle` 中原本的认证行（第 40-41 行）：

```java
            Application app = openApiAuthService.authenticate(
                    request.getHeader("X-App-Id"), request.getHeader("X-Api-Key"));
```

替换为分流逻辑：

```java
            String appId = request.getHeader("X-App-Id");
            String apiKey = request.getHeader("X-Api-Key");
            Application app;
            if (apiKey != null && !apiKey.isBlank()) {
                // 服务端到服务端：appKey + secret
                app = openApiAuthService.authenticate(appId, apiKey);
            } else {
                // 浏览器 Widget：appKey + Origin 白名单（浏览器强制带 Origin，不可被页面脚本伪造）
                app = openApiAuthService.authenticateByOrigin(appId, resolveOrigin(request));
            }
```

- [ ] **Step 2: 加 resolveOrigin 辅助方法**

在 `writeError(...)` 方法之前追加：

```java
    /** 取 Origin 头；缺失时从 Referer 解析出 scheme://host[:port]。 */
    private String resolveOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin;
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                java.net.URI u = java.net.URI.create(referer);
                if (u.getScheme() != null && u.getHost() != null) {
                    String o = u.getScheme() + "://" + u.getHost();
                    if (u.getPort() != -1) {
                        o += ":" + u.getPort();
                    }
                    return o;
                }
            } catch (IllegalArgumentException ignored) {
                // Referer 非法 URI，忽略，回退 null → 认证按未授权处理
            }
        }
        return null;
    }
```

- [ ] **Step 3: 编译并跑全部测试**

Run: `mvn -f backend/pom.xml -q test`
Expected: BUILD SUCCESS（已有测试不受影响；分流不改变带密钥路径的行为）

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/config/OpenApiAuthInterceptor.java
git commit -m "feat(widget): 开放 API 拦截器按 X-Api-Key/Origin 分流认证"
```

---

### Task 4: OpenWidgetController + OpenWidgetConfigResponse

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/open/OpenWidgetConfigResponse.java`
- Create: `backend/src/main/java/com/qingyu/care/controller/open/OpenWidgetController.java`

- [ ] **Step 1: 新建响应 DTO**

`backend/src/main/java/com/qingyu/care/dto/open/OpenWidgetConfigResponse.java`：

```java
package com.qingyu.care.dto.open;

import com.qingyu.care.entity.Application;
import lombok.Builder;
import lombok.Data;

/**
 * Widget 公开配置响应：仅暴露渲染挂件所需的品牌信息（无任何密钥）。
 *
 * @author ruan
 */
@Data
@Builder
public class OpenWidgetConfigResponse {

    /** 应用名称 */
    private String appName;

    /** 欢迎语 */
    private String welcomeMessage;

    /** 头像 URL */
    private String avatar;

    /** 主题色 */
    private String themeColor;

    public static OpenWidgetConfigResponse of(Application app) {
        return OpenWidgetConfigResponse.builder()
                .appName(app.getName())
                .welcomeMessage(app.getWelcomeMessage())
                .avatar(app.getAvatar())
                .themeColor(app.getThemeColor())
                .build();
    }
}
```

- [ ] **Step 2: 新建控制器**

`backend/src/main/java/com/qingyu/care/controller/open/OpenWidgetController.java`：

```java
package com.qingyu.care.controller.open;

import com.qingyu.care.config.OpenApiAuthInterceptor;
import com.qingyu.care.dto.open.OpenWidgetConfigResponse;
import com.qingyu.care.entity.Application;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Widget 公开配置端点。认证由 OpenApiAuthInterceptor 完成（无密钥则走 Origin 白名单），
 * 拦截器已将 Application 存入 request attribute，此处直接取用、不再查库。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/open/v1/widget")
public class OpenWidgetController {

    /**
     * GET /open/v1/widget/config —— 返回渲染挂件所需的品牌信息。
     */
    @GetMapping("/config")
    public OpenWidgetConfigResponse config(HttpServletRequest request) {
        Application app = (Application) request.getAttribute(OpenApiAuthInterceptor.ATTR_APP);
        return OpenWidgetConfigResponse.of(app);
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS（`/open/v1/widget/config` 落在 `/open/v1/**`，自动走拦截器 Origin 认证）

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/dto/open/OpenWidgetConfigResponse.java backend/src/main/java/com/qingyu/care/controller/open/OpenWidgetController.java
git commit -m "feat(widget): 新增 /open/v1/widget/config 公开配置端点"
```

---

### Task 5: /completions 对失效 sessionId 显式报错（§4.5）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatService.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`

**背景**：`ChatServiceImpl.getOrCreateSession`（第 241-247 行）对未知 sessionId 会**静默新建**——内部 ChatController 依赖此行为，不能改。故在开放 API 层显式校验。

- [ ] **Step 1: ChatService 接口加 sessionExists**

在 `ChatService` 接口的 `getSession(...)` 之后追加：

```java
    /**
     * 判断会话是否存在（按当前上下文可见）。开放 API 用于对失效 sessionId 显式报错。
     */
    boolean sessionExists(String sessionId);
```

- [ ] **Step 2: ChatServiceImpl 实现**

在 `ChatServiceImpl.getSession(...)` 之后追加：

```java
    @Override
    public boolean sessionExists(String sessionId) {
        return sessionId != null && sessionRepository.findBySessionId(sessionId).isPresent();
    }
```

- [ ] **Step 3: OpenChatController.completions 加校验**

在 `OpenChatController` 顶部 import 区加：

```java
import com.qingyu.care.exception.OpenApiException;
```

将 `completions` 方法体首行改为先校验：

```java
    @PostMapping("/completions")
    public OpenChatResponse completions(@Valid @RequestBody OpenChatRequest req) {
        // 传了 sessionId 但服务端已不存在/过期 → 显式 404，供 Widget 清本地会话后自动重开
        if (req.getSessionId() != null && !req.getSessionId().isBlank()
                && !chatService.sessionExists(req.getSessionId())) {
            throw new OpenApiException(404, "会话不存在或已过期");
        }
        ChatResponse resp = chatService.chat(toInternal(req, false));
        long tokens = resp.getTokenUsage() != null ? resp.getTokenUsage() : 0L;
        apiUsageService.record(
                TenantContext.getTenantId(),
                TenantContext.getAppId(),
                1L, tokens);
        return OpenChatResponse.from(resp);
    }
```

- [ ] **Step 4: 编译并跑全部测试**

Run: `mvn -f backend/pom.xml -q test`
Expected: BUILD SUCCESS（`OpenApiException(404)` 由 `OpenApiExceptionHandler` 统一转成 `{error:{code:404,...}}`）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatService.java backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java
git commit -m "feat(widget): /completions 对失效 sessionId 返回 404"
```

---

### Task 6: ApplicationController.update 透传 allowedOrigins

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/ApplicationController.java`

- [ ] **Step 1: update 加透传**

在 `update` 方法中 `rateLimitPerMinute` 透传行（第 110 行）之后追加：

```java
        if (application.getAllowedOrigins() != null) existing.setAllowedOrigins(application.getAllowedOrigins());
```

（`getById` 返回整个 `Application` 实体，`allowedOrigins` 会随之序列化返回，前端无需额外端点即可读取。）

- [ ] **Step 2: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/ApplicationController.java
git commit -m "feat(widget): 应用更新接口透传 allowedOrigins"
```

---

### Task 7: 静态资源缓存（widget.js 较短 TTL）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/config/WebConfig.java`

- [ ] **Step 1: 加 addResourceHandlers**

在 `WebConfig` 类中，`addInterceptors` 方法之后追加（并确保 `import java.time.Duration;`）：

```java
    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        // Widget 入口文件名固定、不带 hash，给较短缓存；嵌入片段用 ?v=<版本> 做 cache-bust
        registry.addResourceHandler("/widget.js")
                .addResourceLocations("classpath:/static/widget.js")
                .setCacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)));
    }
```

- [ ] **Step 2: 编译验证**

Run: `mvn -f backend/pom.xml -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/config/WebConfig.java
git commit -m "feat(widget): widget.js 静态资源设置 5 分钟缓存"
```

---

## 里程碑 B：Widget 独立项目

### Task 8: 初始化 widget/ 项目骨架

**Files:**
- Create: `widget/package.json`
- Create: `widget/tsconfig.json`
- Create: `widget/vite.config.ts`
- Create: `widget/index.html`

- [ ] **Step 1: package.json**

`widget/package.json`：

```json
{
  "name": "qingyu-widget",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "test": "vitest run"
  },
  "devDependencies": {
    "jsdom": "^25.0.1",
    "typescript": "~5.9.3",
    "vite": "^7.2.4",
    "vitest": "^2.1.9"
  }
}
```

- [ ] **Step 2: tsconfig.json**

`widget/tsconfig.json`：

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "skipLibCheck": true,
    "types": ["vitest/globals"]
  },
  "include": ["src"]
}
```

- [ ] **Step 3: vite.config.ts（lib 模式，单文件 IIFE，输出到后端 static）**

`widget/vite.config.ts`：

```ts
import { defineConfig } from 'vite';
import { resolve } from 'node:path';

// 库模式：产出单文件 IIFE，直接输出到后端 static/。
// emptyOutDir=false 至关重要——避免清空后端 static 目录里的其它文件。
export default defineConfig({
  build: {
    outDir: resolve(__dirname, '../backend/src/main/resources/static'),
    emptyOutDir: false,
    lib: {
      entry: resolve(__dirname, 'src/main.ts'),
      name: 'QingyuWidget',
      formats: ['iife'],
      fileName: () => 'widget.js',
    },
    rollupOptions: {
      output: { inlineDynamicImports: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
});
```

- [ ] **Step 4: index.html（本地调试页）**

`widget/index.html`：

```html
<!doctype html>
<html lang="zh-CN">
  <head><meta charset="UTF-8" /><title>Qingyu Widget 调试</title></head>
  <body>
    <h1>宿主页面</h1>
    <!-- 本地调试：appKey 换成真实值，后端需运行在 5173 代理或改成绝对地址 -->
    <script type="module" src="/src/main.ts" data-appkey="app_debug"></script>
  </body>
</html>
```

- [ ] **Step 5: 安装依赖**

Run: `pnpm -C widget install`
Expected: 依赖装好，生成 `widget/pnpm-lock.yaml`

- [ ] **Step 6: 提交**

```bash
git add widget/package.json widget/tsconfig.json widget/vite.config.ts widget/index.html widget/pnpm-lock.yaml
git commit -m "chore(widget): 初始化 widget 独立项目骨架"
```

---

### Task 9: config.ts —— 解析 appKey 与 apiBase（TDD）

**Files:**
- Create: `widget/src/config.ts`
- Test: `widget/src/config.test.ts`

- [ ] **Step 1: 写失败测试**

`widget/src/config.test.ts`：

```ts
import { describe, it, expect, afterEach } from 'vitest';
import { resolveConfig } from './config';

function addScript(src: string): HTMLScriptElement {
  const s = document.createElement('script');
  s.src = src;
  document.body.appendChild(s);
  return s;
}

afterEach(() => {
  document.querySelectorAll('script').forEach((s) => s.remove());
});

describe('resolveConfig', () => {
  it('从 script src 的 ?appKey 解析出配置', () => {
    addScript('https://host.example.com/api/widget.js?appKey=app_123&v=1');
    const cfg = resolveConfig();
    expect(cfg).not.toBeNull();
    expect(cfg!.appKey).toBe('app_123');
    expect(cfg!.apiBase).toBe('https://host.example.com/api');
  });

  it('缺少 appKey 时返回 null', () => {
    addScript('https://host.example.com/api/widget.js');
    expect(resolveConfig()).toBeNull();
  });

  it('找不到脚本时返回 null', () => {
    expect(resolveConfig()).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm -C widget test`
Expected: FAIL（`./config` 尚不存在）

- [ ] **Step 3: 实现 config.ts**

`widget/src/config.ts`：

```ts
export interface WidgetConfig {
  appKey: string;
  /** 开放 API 基地址，如 https://host/api */
  apiBase: string;
}

/**
 * 从加载本脚本的 <script> 标签解析 appKey，并推导开放 API 基地址。
 * 优先 document.currentScript；兜底按 src 含 "widget.js" 查找（应对 async/defer）。
 * 缺 appKey 时告警并返回 null（fail silently，不渲染）。
 */
export function resolveConfig(): WidgetConfig | null {
  const script =
    (document.currentScript as HTMLScriptElement | null) ??
    document.querySelector<HTMLScriptElement>('script[src*="widget.js"]');
  if (!script || !script.src) {
    console.warn('[Qingyu Widget] 未找到加载脚本，无法初始化');
    return null;
  }
  const url = new URL(script.src);
  const appKey = url.searchParams.get('appKey');
  if (!appKey) {
    console.warn('[Qingyu Widget] 缺少 appKey，无法初始化');
    return null;
  }
  return { appKey, apiBase: url.origin + '/api' };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm -C widget test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add widget/src/config.ts widget/src/config.test.ts
git commit -m "feat(widget): config 解析 appKey 与 apiBase"
```

---

### Task 10: session.ts —— localStorage 会话（24h 过期 + 容错）（TDD）

**Files:**
- Create: `widget/src/session.ts`
- Test: `widget/src/session.test.ts`

- [ ] **Step 1: 写失败测试**

`widget/src/session.test.ts`：

```ts
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { loadSession, saveSession, clearSession, SESSION_TTL_MS } from './session';

const APP = 'app_123';
const KEY = `qingyu_widget_session_${APP}`;

beforeEach(() => localStorage.clear());
afterEach(() => vi.useRealTimers());

describe('session', () => {
  it('保存后可读回 sessionId', () => {
    saveSession(APP, 's-1');
    expect(loadSession(APP)).toBe('s-1');
  });

  it('超过 24h 视为过期并清除', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-03T00:00:00Z'));
    saveSession(APP, 's-1');
    vi.setSystemTime(new Date(Date.now() + SESSION_TTL_MS + 1000));
    expect(loadSession(APP)).toBeNull();
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('损坏数据按无会话降级且不抛错', () => {
    localStorage.setItem(KEY, '{不是json');
    expect(loadSession(APP)).toBeNull();
  });

  it('clearSession 移除会话', () => {
    saveSession(APP, 's-1');
    clearSession(APP);
    expect(loadSession(APP)).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm -C widget test`
Expected: FAIL（`./session` 尚不存在）

- [ ] **Step 3: 实现 session.ts**

`widget/src/session.ts`：

```ts
export const SESSION_TTL_MS = 24 * 60 * 60 * 1000; // 24h

const keyOf = (appKey: string) => `qingyu_widget_session_${appKey}`;

interface Stored {
  sessionId: string;
  createdAt: number;
}

/** 读取未过期的 sessionId；过期/损坏/不可用时返回 null 并清理。 */
export function loadSession(appKey: string): string | null {
  try {
    const raw = localStorage.getItem(keyOf(appKey));
    if (!raw) return null;
    const data = JSON.parse(raw) as Partial<Stored>;
    if (
      !data ||
      typeof data.sessionId !== 'string' ||
      typeof data.createdAt !== 'number' ||
      Date.now() - data.createdAt > SESSION_TTL_MS
    ) {
      clearSession(appKey);
      return null;
    }
    return data.sessionId;
  } catch {
    return null; // localStorage 被禁用（隐私模式）或数据损坏，降级为无会话
  }
}

export function saveSession(appKey: string, sessionId: string): void {
  try {
    const data: Stored = { sessionId, createdAt: Date.now() };
    localStorage.setItem(keyOf(appKey), JSON.stringify(data));
  } catch {
    // 忽略写失败，不影响会话进行
  }
}

export function clearSession(appKey: string): void {
  try {
    localStorage.removeItem(keyOf(appKey));
  } catch {
    // 忽略
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm -C widget test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add widget/src/session.ts widget/src/session.test.ts
git commit -m "feat(widget): session 本地存储含 24h 过期与容错"
```

---

### Task 11: api.ts —— 开放 API 客户端（含失效识别）（TDD）

**Files:**
- Create: `widget/src/api.ts`
- Test: `widget/src/api.test.ts`

- [ ] **Step 1: 写失败测试**

`widget/src/api.test.ts`：

```ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { createApi, SessionExpiredError } from './api';

const cfg = { appKey: 'app_123', apiBase: 'https://host/api' };

function mockFetch(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  });
}

afterEach(() => vi.unstubAllGlobals());

describe('api', () => {
  it('send 带 X-App-Id 头与消息体，新会话不带 sessionId', async () => {
    const f = mockFetch(200, { sessionId: 's-1', reply: '你好', handoverSuggested: false });
    vi.stubGlobal('fetch', f);

    const reply = await createApi(cfg).send('hi', null);

    expect(reply.sessionId).toBe('s-1');
    const [url, init] = f.mock.calls[0];
    expect(url).toBe('https://host/api/open/v1/chat/completions');
    expect((init as RequestInit).method).toBe('POST');
    expect((init as any).headers['X-App-Id']).toBe('app_123');
    expect(JSON.parse((init as any).body)).toEqual({ message: 'hi' });
  });

  it('send 带 sessionId 时请求体包含它', async () => {
    const f = mockFetch(200, { sessionId: 's-1', reply: 'ok', handoverSuggested: false });
    vi.stubGlobal('fetch', f);

    await createApi(cfg).send('hi', 's-1');

    expect(JSON.parse((f.mock.calls[0][1] as any).body)).toEqual({ message: 'hi', sessionId: 's-1' });
  });

  it('send 收到 404 抛 SessionExpiredError', async () => {
    vi.stubGlobal('fetch', mockFetch(404, { error: { code: 404, message: '会话不存在或已过期' } }));

    await expect(createApi(cfg).send('hi', 's-x')).rejects.toBeInstanceOf(SessionExpiredError);
  });

  it('getConfig 请求 widget/config 端点', async () => {
    const f = mockFetch(200, { appName: 'A', welcomeMessage: 'hi', avatar: '', themeColor: '#000' });
    vi.stubGlobal('fetch', f);

    const brand = await createApi(cfg).getConfig();

    expect(brand.appName).toBe('A');
    expect(f.mock.calls[0][0]).toBe('https://host/api/open/v1/widget/config');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm -C widget test`
Expected: FAIL（`./api` 尚不存在）

- [ ] **Step 3: 实现 api.ts**

`widget/src/api.ts`：

```ts
import type { WidgetConfig } from './config';

/** 服务端判定会话失效（404）时抛出，供上层清本地会话并自动重开。 */
export class SessionExpiredError extends Error {
  constructor() {
    super('会话不存在或已过期');
    this.name = 'SessionExpiredError';
  }
}

export interface WidgetBrand {
  appName: string;
  welcomeMessage: string;
  avatar: string;
  themeColor: string;
}

export interface ChatReply {
  sessionId: string;
  reply: string;
  handoverSuggested: boolean;
}

export function createApi(cfg: WidgetConfig) {
  const headers = (): Record<string, string> => ({
    'Content-Type': 'application/json',
    'X-App-Id': cfg.appKey, // 仅公开 appKey，无任何密钥；浏览器自动带 Origin 供服务端校验
  });

  return {
    async getConfig(): Promise<WidgetBrand> {
      const r = await fetch(`${cfg.apiBase}/open/v1/widget/config`, { headers: headers() });
      if (!r.ok) throw new Error(`widget config 请求失败: ${r.status}`);
      return (await r.json()) as WidgetBrand;
    },

    async send(message: string, sessionId: string | null): Promise<ChatReply> {
      const body = sessionId ? { message, sessionId } : { message };
      const r = await fetch(`${cfg.apiBase}/open/v1/chat/completions`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify(body),
      });
      if (r.status === 404) throw new SessionExpiredError();
      if (!r.ok) throw new Error(`对话请求失败: ${r.status}`);
      return (await r.json()) as ChatReply;
    },

    async handover(sessionId: string): Promise<void> {
      await fetch(`${cfg.apiBase}/open/v1/sessions/${sessionId}/handover`, {
        method: 'POST',
        headers: headers(),
      });
    },
  };
}

export type WidgetApi = ReturnType<typeof createApi>;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm -C widget test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add widget/src/api.ts widget/src/api.test.ts
git commit -m "feat(widget): 开放 API 客户端含会话失效识别"
```

---

### Task 12: styles.ts —— Shadow DOM 样式

**Files:**
- Create: `widget/src/styles.ts`

- [ ] **Step 1: 实现 styles.ts**

`widget/src/styles.ts`（CSS 字符串注入 shadow root，用 `--qy-theme` 变量承载主题色；移动端 <480px 面板全屏）：

```ts
export function buildStyles(themeColor: string): string {
  return `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
    .qy-bubble {
      position: fixed; right: 20px; bottom: 20px; width: 56px; height: 56px;
      border-radius: 50%; background: ${themeColor}; color: #fff; border: none;
      cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.2); font-size: 24px; z-index: 2147483000;
    }
    .qy-panel {
      position: fixed; right: 20px; bottom: 88px; width: 360px; height: 520px;
      background: #fff; border-radius: 12px; box-shadow: 0 8px 30px rgba(0,0,0,.18);
      display: none; flex-direction: column; overflow: hidden; z-index: 2147483000;
    }
    .qy-panel.open { display: flex; }
    .qy-header {
      background: ${themeColor}; color: #fff; padding: 12px 16px;
      display: flex; align-items: center; justify-content: space-between; font-weight: 600;
    }
    .qy-header .qy-handover {
      background: rgba(255,255,255,.2); color: #fff; border: none; border-radius: 6px;
      padding: 4px 10px; cursor: pointer; font-size: 12px;
    }
    .qy-msgs { flex: 1; overflow-y: auto; padding: 12px; background: #f7f7f8; }
    .qy-msg { margin-bottom: 10px; display: flex; }
    .qy-msg.user { justify-content: flex-end; }
    .qy-msg .qy-text {
      max-width: 78%; padding: 8px 12px; border-radius: 10px; font-size: 14px; line-height: 1.5;
      white-space: pre-wrap; word-break: break-word;
    }
    .qy-msg.bot .qy-text { background: #fff; border: 1px solid #eee; }
    .qy-msg.user .qy-text { background: ${themeColor}; color: #fff; }
    .qy-input { display: flex; border-top: 1px solid #eee; }
    .qy-input textarea {
      flex: 1; border: none; resize: none; padding: 12px; font-size: 14px; outline: none; height: 48px;
    }
    .qy-input button {
      border: none; background: ${themeColor}; color: #fff; padding: 0 16px; cursor: pointer;
    }
    .qy-input button:disabled { opacity: .5; cursor: not-allowed; }
    @media (max-width: 480px) {
      .qy-panel { right: 0; bottom: 0; width: 100vw; height: 100vh; border-radius: 0; }
    }
  `;
}
```

- [ ] **Step 2: 提交**

```bash
git add widget/src/styles.ts
git commit -m "feat(widget): Shadow DOM 样式（主题色/移动端全屏）"
```

---

### Task 13: ui.ts —— Shadow DOM 渲染

**Files:**
- Create: `widget/src/ui.ts`

- [ ] **Step 1: 实现 ui.ts**

`widget/src/ui.ts`（纯渲染 + 事件回调，无网络逻辑，便于装配）：

```ts
import { buildStyles } from './styles';
import type { WidgetBrand } from './api';

export interface UiHandlers {
  onSend: (message: string) => void;
  onHandover: () => void;
}

export interface WidgetUi {
  addUserMessage: (text: string) => void;
  addBotMessage: (text: string) => void;
  setTyping: (on: boolean) => void;
  setInputEnabled: (on: boolean) => void;
}

/**
 * 在 Shadow DOM 内构建挂件 UI，返回操作句柄。
 * 所有样式与节点都在 shadow root 内，不污染宿主页面、也不受其影响。
 */
export function mountUi(brand: WidgetBrand, handlers: UiHandlers): WidgetUi {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = host.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = buildStyles(brand.themeColor || '#6366f1');
  root.appendChild(style);

  const bubble = document.createElement('button');
  bubble.className = 'qy-bubble';
  bubble.textContent = '💬';
  bubble.setAttribute('aria-label', '打开客服');

  const panel = document.createElement('div');
  panel.className = 'qy-panel';
  panel.innerHTML = `
    <div class="qy-header">
      <span>${escapeHtml(brand.appName || '在线客服')}</span>
      <button class="qy-handover" type="button">转人工</button>
    </div>
    <div class="qy-msgs"></div>
    <div class="qy-input">
      <textarea placeholder="输入消息…"></textarea>
      <button type="button">发送</button>
    </div>
  `;

  root.appendChild(bubble);
  root.appendChild(panel);

  const msgs = panel.querySelector<HTMLDivElement>('.qy-msgs')!;
  const textarea = panel.querySelector<HTMLTextAreaElement>('textarea')!;
  const sendBtn = panel.querySelector<HTMLButtonElement>('.qy-input button')!;
  const handoverBtn = panel.querySelector<HTMLButtonElement>('.qy-handover')!;

  let firstOpen = true;
  bubble.addEventListener('click', () => {
    panel.classList.toggle('open');
    if (firstOpen && panel.classList.contains('open')) {
      firstOpen = false;
      if (brand.welcomeMessage) addBotMessage(brand.welcomeMessage);
    }
  });

  const doSend = () => {
    const text = textarea.value.trim();
    if (!text) return;
    textarea.value = '';
    handlers.onSend(text);
  };
  sendBtn.addEventListener('click', doSend);
  textarea.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      doSend();
    }
  });
  handoverBtn.addEventListener('click', () => handlers.onHandover());

  function appendMsg(text: string, who: 'user' | 'bot'): HTMLDivElement {
    const row = document.createElement('div');
    row.className = `qy-msg ${who}`;
    const bubbleEl = document.createElement('div');
    bubbleEl.className = 'qy-text';
    bubbleEl.textContent = text;
    row.appendChild(bubbleEl);
    msgs.appendChild(row);
    msgs.scrollTop = msgs.scrollHeight;
    return row;
  }

  function addUserMessage(text: string) {
    appendMsg(text, 'user');
  }
  function addBotMessage(text: string) {
    appendMsg(text, 'bot');
  }

  let typingRow: HTMLDivElement | null = null;
  function setTyping(on: boolean) {
    if (on && !typingRow) {
      typingRow = appendMsg('输入中…', 'bot');
    } else if (!on && typingRow) {
      typingRow.remove();
      typingRow = null;
    }
  }

  function setInputEnabled(on: boolean) {
    textarea.disabled = !on;
    sendBtn.disabled = !on;
  }

  return { addUserMessage, addBotMessage, setTyping, setInputEnabled };
}

function escapeHtml(s: string): string {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}
```

- [ ] **Step 2: 类型检查**

Run: `pnpm -C widget exec tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 3: 提交**

```bash
git add widget/src/ui.ts
git commit -m "feat(widget): Shadow DOM UI 渲染（气泡/面板/消息/转人工）"
```

---

### Task 14: main.ts —— 装配入口

**Files:**
- Create: `widget/src/main.ts`

- [ ] **Step 1: 实现 main.ts**

`widget/src/main.ts`（装配 config→api→ui，串起会话与失效自动重开）：

```ts
import { resolveConfig } from './config';
import { createApi, SessionExpiredError } from './api';
import { loadSession, saveSession, clearSession } from './session';
import { mountUi } from './ui';

async function init(): Promise<void> {
  const cfg = resolveConfig();
  if (!cfg) return; // 缺 appKey 已在 resolveConfig 内告警

  const api = createApi(cfg);

  let brand;
  try {
    brand = await api.getConfig();
  } catch (e) {
    console.warn('[Qingyu Widget] 拉取配置失败，停止渲染', e);
    return; // fail silently，不干扰宿主页面
  }

  let sessionId: string | null = loadSession(cfg.appKey);

  const ui = mountUi(brand, {
    onSend: (message) => void handleSend(message),
    onHandover: () => void handleHandover(),
  });

  async function handleSend(message: string): Promise<void> {
    ui.addUserMessage(message);
    ui.setInputEnabled(false);
    ui.setTyping(true);
    try {
      const reply = await sendWithRetry(message);
      ui.setTyping(false);
      ui.addBotMessage(reply.reply);
    } catch (e) {
      ui.setTyping(false);
      ui.addBotMessage('抱歉，服务暂时不可用，请稍后再试。');
      console.warn('[Qingyu Widget] 发送失败', e);
    } finally {
      ui.setInputEnabled(true);
    }
  }

  // 会话失效兜底：首次 404 → 清本地会话、以新会话重发一次；用户无感
  async function sendWithRetry(message: string) {
    try {
      const reply = await api.send(message, sessionId);
      sessionId = reply.sessionId;
      saveSession(cfg!.appKey, sessionId);
      return reply;
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        clearSession(cfg!.appKey);
        sessionId = null;
        const reply = await api.send(message, null);
        sessionId = reply.sessionId;
        saveSession(cfg!.appKey, sessionId);
        return reply;
      }
      throw e;
    }
  }

  async function handleHandover(): Promise<void> {
    if (!sessionId) {
      ui.addBotMessage('请先发送消息开始会话，再申请转人工。');
      return;
    }
    try {
      await api.handover(sessionId);
      ui.addBotMessage('已为你转接人工，请稍候。');
    } catch (e) {
      console.warn('[Qingyu Widget] 转人工失败', e);
    }
  }
}

// DOM 就绪后初始化，避免 body 尚未可用
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => void init());
} else {
  void init();
}
```

- [ ] **Step 2: 全量检查（类型 + 测试 + 构建）**

Run: `pnpm -C widget test && pnpm -C widget run build`
Expected: 测试全绿；构建产出 `backend/src/main/resources/static/widget.js`（单文件 IIFE）

- [ ] **Step 3: 确认产物存在**

Run: `ls -la backend/src/main/resources/static/widget.js`
Expected: 文件存在（单文件）

- [ ] **Step 4: 提交（源码 + 构建产物一并入库）**

```bash
git add widget/src/main.ts backend/src/main/resources/static/widget.js
git commit -m "feat(widget): 装配入口并产出 widget.js 构建产物"
```

---

## 里程碑 C：管理后台

### Task 15: IntegrationPage 增 allowedOrigins 编辑 + 真实嵌入片段

**Files:**
- Modify: `frontend/src/api/integration.ts`
- Modify: `frontend/src/pages/IntegrationPage.tsx`

- [ ] **Step 1: integration.ts 加字段与方法**

将 `AppDetail` 接口加一行、并加更新方法：

```ts
interface AppDetail {
    id: number;
    name: string;
    appKey: string;
    appSecret: string;
    rateLimitPerMinute: number | null;
    allowedOrigins: string | null;
}
```

在 `integrationService` 对象中追加：

```ts
    updateAllowedOrigins: (id: number, allowedOrigins: string) =>
        http.put<ApiResponse<AppDetail>>(`/v1/applications/${id}`, { allowedOrigins }),
```

- [ ] **Step 2: IntegrationPage 加 state 与加载**

在现有 `rateLimit` state 后加：

```tsx
    const [allowedOrigins, setAllowedOrigins] = useState('');
```

在 `load()` 内 `setRateLimit(...)` 后加：

```tsx
            setAllowedOrigins(r.data.allowedOrigins ?? '');
```

- [ ] **Step 3: 加保存 allowedOrigins 处理函数**

在 `saveRateLimit` 之后追加：

```tsx
    const saveAllowedOrigins = async () => {
        if (!currentApp) return;
        try {
            const r = await integrationService.updateAllowedOrigins(currentApp.id, allowedOrigins.trim());
            if (r.code !== 200) throw new Error(r.message);
            toast({ title: '已保存' });
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : '未知错误';
            toast({ title: '保存失败', description: msg, variant: 'destructive' });
        }
    };
```

- [ ] **Step 4: 替换「Widget 嵌入代码」占位块为真实 UI**

将现有占位块（第 118-121 行）：

```tsx
            <div className="space-y-2">
                <label className="text-sm font-medium">Widget 嵌入代码</label>
                <p className="text-xs text-muted-foreground">Widget 子项完成后在此生成一行嵌入代码（暂未生效）。</p>
            </div>
```

替换为 allowedOrigins 编辑 + 真实嵌入片段（`?v=1` 做 cache-bust，发版时 bump）：

```tsx
            <div className="space-y-2">
                <label className="text-sm font-medium">Widget 允许来源域名（每行一个，如 https://www.a.com）</label>
                <textarea
                    className="w-full min-h-[80px] text-sm rounded-md border p-2 bg-background"
                    value={allowedOrigins}
                    onChange={(e) => setAllowedOrigins(e.target.value.split('\n').map((s) => s.trim()).filter(Boolean).join(','))}
                    placeholder={'https://www.a.com\nhttps://a.com'}
                />
                <p className="text-xs text-muted-foreground">留空表示未开放 Widget。仅这些域名的页面可加载挂件。</p>
                <Button onClick={saveAllowedOrigins}>保存来源</Button>
            </div>

            <div className="space-y-2">
                <label className="text-sm font-medium">Widget 嵌入代码（粘贴到目标网站 &lt;/body&gt; 前）</label>
                <pre className="text-xs bg-muted p-3 rounded-md overflow-x-auto whitespace-pre-wrap">{embed}</pre>
                <Button variant="outline" onClick={() => copy(embed)}>复制嵌入代码</Button>
            </div>
```

在 `curl` 常量定义之后加 `embed` 常量：

```tsx
    const embed = `<script src="${window.location.origin}/api/widget.js?appKey=${appKey}&v=1"></script>`;
```

> 说明：`allowedOrigins` 在 state 中始终以逗号分隔存储（与后端一致）；textarea 显示时保持逗号存储、用户以换行输入，onChange 归一化为逗号。若希望换行展示，可在 `value` 处用 `allowedOrigins.split(',').join('\n')`——本步骤保持简单，逗号展示即可。

- [ ] **Step 5: 前端构建校验**

Run: `pnpm -C frontend build`
Expected: 构建通过（tsc + vite），无类型错误

- [ ] **Step 6: 提交**

```bash
git add frontend/src/api/integration.ts frontend/src/pages/IntegrationPage.tsx
git commit -m "feat(widget): 接入页支持 allowedOrigins 编辑与真实嵌入片段"
```

---

## 收尾

### Task 16: README 与 .gitignore

**Files:**
- Create: `widget/README.md`
- Modify: `.gitignore`（若存在且忽略了产物）

- [ ] **Step 1: 写 widget/README.md**

`widget/README.md`：

```markdown
# Qingyu Widget

一行 `<script>` 嵌入的浏览器客服挂件，复用开放 REST API。

## 开发

```bash
pnpm install
pnpm dev      # 本地调试（index.html）
pnpm test     # vitest 单测
```

## 构建

```bash
pnpm build    # 产出单文件到 ../backend/src/main/resources/static/widget.js
```

**重要**：`widget.js` 为构建产物且**提交入库**（自托管无 CDN，保证部署零依赖、`/api/widget.js` 不 404）。
修改 `src/` 后必须重新 `pnpm build` 并把更新后的 `backend/src/main/resources/static/widget.js` 一并提交，
否则线上产物会与源码不一致。CI 应校验一致性：

```bash
pnpm -C widget build && git diff --exit-code backend/src/main/resources/static/widget.js
```

## 嵌入

```html
<script src="https://<host>/api/widget.js?appKey=app_xxx&v=1"></script>
```

`appKey` 在管理后台「接入配置」页获取；需先配置该应用的「允许来源域名」。
```

- [ ] **Step 2: 确认 .gitignore 不忽略产物**

Run: `git check-ignore backend/src/main/resources/static/widget.js; echo "exit=$?"`
Expected: `exit=1`（未被忽略）。若被忽略（exit=0），在 `.gitignore` 中追加取消忽略规则：`!backend/src/main/resources/static/widget.js`

- [ ] **Step 3: 提交**

```bash
git add widget/README.md .gitignore
git commit -m "docs(widget): 构建说明与产物入库约定"
```

---

## 联调验收（人工，非自动化）

计划实现后由编排者/用户手工验收：

1. 启动后端（`mvn -f backend/pom.xml spring-boot:run`），在管理后台某应用「接入配置」页填 `http://localhost:5173`（或调试宿主页 origin）到允许来源、保存。
2. 用 `widget/index.html` 或任意本地页面嵌入 `<script src="http://localhost:8080/api/widget.js?appKey=<该应用appKey>&v=1"></script>`。
3. 验证：气泡出现（主题色正确）→ 展开显示欢迎语 → 发消息得到 AI 回复 → 刷新页面会话延续（localStorage）→ 手动清除服务端会话后再发消息能无感重开 → 点「转人工」会话转 WAITING_AGENT。
4. 反例：从未登记的 origin 加载 → `/widget/config` 返回 403、不渲染。

---

## Self-Review（作者自查）

**Spec 覆盖**（对照 spec §11 交付物清单）：
- 后端 `allowedOrigins` → Task 1 ✅；`authenticateByOrigin` → Task 2 ✅；拦截器分流 → Task 3 ✅；`OpenWidgetController`+`OpenWidgetConfigResponse` → Task 4 ✅；`/completions` 失效 404（§4.5）→ Task 5 ✅；`update` 透传 → Task 6 ✅；`OpenApiAuthServiceTest` 增补 → Task 2 ✅；widget.js 缓存（§4.4）→ Task 7 ✅。
- Widget 项目骨架 → Task 8 ✅；config/session/api/ui/styles/main + 单测 → Task 9-14 ✅；会话 24h 过期 + 失效自动重开 + 容错（§5.2）→ Task 10/14 ✅；转人工 v1（§5.2）→ Task 13/14 ✅；构建产出并提交 → Task 14 ✅。
- 管理后台 allowedOrigins 编辑 + 真实嵌入片段 → Task 15 ✅。
- README + 产物入库 + CI 一致性校验（§4.4/§10）→ Task 16 ✅。

**类型一致性**：`resolveConfig(): WidgetConfig|null`、`createApi(cfg).send/getConfig/handover`、`mountUi(brand, handlers): WidgetUi`、`loadSession/saveSession/clearSession(appKey)`、`SessionExpiredError` 在 Task 9-14 间签名一致。后端 `sessionExists`、`authenticateByOrigin`、`OpenWidgetConfigResponse.of` 命名前后一致。

**无占位符**：所有代码步骤含完整实现，无 TBD/TODO（保留原 stream 计量 TODO 属既有代码，非本计划引入）。

**已知非目标**（不实现）：流式回复、通配子域、HMAC 登录用户身份校验、Maven 集成前端构建（frontend-maven-plugin）——均列为后续增强。

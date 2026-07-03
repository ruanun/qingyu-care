---
title: Phase 2 · JS Widget SDK 设计
author: ruan
date: 2026-07-03
status: 待评审
---

# Phase 2 · JS Widget SDK 设计

## 1. 背景与范围

Phase 2 第二子项：一行 `<script>` 即可嵌入任意网站的浏览器聊天挂件，调用已完成的开放 REST API。

**决策已定**：
- 认证：**扩展开放 API——appKey + Origin 域名白名单**（浏览器不出现密钥）。
- 托管：widget 构建产物 `widget.js` 由**后端静态资源**托管。
- 回复方式：**v1 用非流式 `/completions`**（可靠维持会话连续性，见 §10 说明）；流式列为后续增强。Widget 通过公开配置接口按 appKey 拉取品牌信息。

**本子项范围**：
1. 后端：`Application.allowedOrigins`；开放 API 认证支持 Origin 路径；公开 `/widget/config` 端点；流式端点补基础计量；静态托管 widget.js。
2. 新建独立 `widget/`（Vanilla TS + Vite）项目，产出单文件 IIFE `widget.js`。
3. 管理后台 `IntegrationPage`：allowedOrigins 配置 + 真实嵌入代码片段。

**非目标**：Webhook（Phase 2 第三子项）；多语言/富媒体消息；Widget 主题深度定制（仅主题色/头像/欢迎语）；appSecret 加密存储（沿用现状）。

## 2. 现状与约束（基于代码核实）

- 开放 API 已就绪：`/api/open/v1/chat/completions`、`/chat/stream`(SSE, POST)、`GET /sessions/{id}`、`/sessions/{id}/handover`；认证经 `OpenApiAuthInterceptor` → `OpenApiAuthService.authenticate(appId, apiKey)`（明文比对 secret），认证后写 `TenantContext(tenantId, appId)`，并把 Application 存入 request attribute（`OpenApiAuthInterceptor.ATTR_APP`）。
- **安全约束（本子项核心）**：Widget 运行在客户浏览器，**不能携带 appSecret**。故浏览器调用用 appKey（公开）+ 服务端校验请求 `Origin` 在应用登记的白名单内。
- `Application` 已有 `appKey/appSecret/enabled/avatar/welcomeMessage/themeColor/rateLimitPerMinute`，**无 allowedOrigins**（需新增）。
- CORS：`WebConfig` 全局 `allowedOriginPatterns("*")` + `allowedHeaders("*")` + `allowCredentials(true)`——浏览器可跨域读响应、可带 `X-App-Id` 头（预检放行）。**安全边界不靠 CORS，而靠服务端 Origin 白名单**。无需改 CORS。
- 限流：`OpenApiAuthInterceptor` 认证后按 appKey 限流——Widget 流量同样受限流保护，无需改动。
- 计量：`/completions` 已计量。Widget 走 `/completions`，用量自动计入，本子项无需改计量。（`/stream` 未计量是既有 TODO，与本子项无关，留待流式增强时处理。）
- 静态资源：Spring Boot 默认服务 `classpath:/static/`；context-path 为 `/api`，故 `static/widget.js` 对外路径为 `/api/widget.js`。
- **会话连续性约束（关键）**：`/completions` 响应含 `sessionId`，可存端维持多轮；`/stream` 只返回文本块、不回传 sessionId，故 v1 不用流式。

## 3. 总体设计原则

1. **复用而非新增端点**：Widget 复用现有 `/open/v1/*`，认证在拦截器分流（有密钥/无密钥）。
2. **Widget 零框架、样式隔离**：Vanilla TS + Shadow DOM，产物小、不污染宿主页面 CSS。
3. 安全默认：allowedOrigins 未配置 → Origin 认证一律拒绝（不因空配置放行任意来源）。
4. 关注点分离：widget/ 独立项目，只依赖开放 API 的 HTTP 契约，不与管理后台前端耦合。

## 4. 后端设计

### 4.1 Application.allowedOrigins

- 新增字段 `allowedOrigins`（`@Column(columnDefinition="TEXT")`，逗号分隔的允许来源，如 `https://a.com,https://www.a.com`）。为空表示未开放 Widget。

### 4.2 认证扩展（Origin 路径）

- `OpenApiAuthService` 增加 `Application authenticateByOrigin(String appId, String origin)`：
  - appId 为空 → `OpenApiException(401,"缺少 X-App-Id")`；
  - `findByAppKey` 不存在 → 401；`enabled==false` → 403；
  - `allowedOrigins` 为空 → `OpenApiException(403,"该应用未开放 Widget 接入")`；
  - `origin` 为空或不在白名单（按逗号拆分、trim 后精确匹配）→ `OpenApiException(403,"来源域名未授权")`；
  - 命中 → 返回 Application。
- `OpenApiAuthInterceptor.preHandle` 分流：
  - `X-Api-Key` 存在 → 现有 `authenticate(appId, apiKey)`（服务端）；
  - 否则 → 取 `Origin` 头（缺失时回退 `Referer` 解析出的 origin），调 `authenticateByOrigin(appId, origin)`（浏览器 Widget）。
  - 两条路径成功后逻辑相同：写 `TenantContext`、存 `ATTR_APP`、限流。
- 现有 `OpenApiAuthServiceTest` 不变；新增针对 `authenticateByOrigin` 的用例。

### 4.3 公开 Widget 配置端点

- `GET /open/v1/widget/config`（经拦截器 Origin 认证）→ `OpenWidgetConfigResponse { appName, welcomeMessage, avatar, themeColor }`。
- 控制器 `OpenWidgetController` 从 request attribute 取 `ATTR_APP`（拦截器已存）构造响应，不额外查库。

### 4.4 静态托管

- widget 项目 `vite build` 输出单文件到 `backend/src/main/resources/static/widget.js`（IIFE、无 hash 文件名）。
- 对外嵌入：`<script src="http(s)://<host>/api/widget.js?appKey=app_xxx"></script>`。
- 该文件为构建产物；`.gitignore` 忽略之，部署/开发时由 widget 构建生成（README 记录构建命令）。

## 5. Widget 前端项目（新建 `widget/`）

### 5.1 项目结构

```
widget/
├── package.json          # vite + typescript，脚本 build
├── vite.config.ts        # lib 模式：IIFE、单文件、name=QingyuWidget、outDir=../backend/src/main/resources/static
├── tsconfig.json
├── index.html            # 本地开发调试页
└── src/
    ├── main.ts           # 入口：解析 appKey、初始化、挂载 Shadow DOM
    ├── config.ts         # 从 currentScript 读 appKey；推导 apiBase
    ├── api.ts            # 开放 API 客户端（config/completions/stream/handover），带 X-App-Id
    ├── session.ts        # localStorage 按 appKey 存 sessionId
    ├── ui.ts             # Shadow DOM 渲染：气泡 + 面板 + 消息列表 + 输入
    └── styles.ts         # 注入 Shadow DOM 的 CSS 字符串（用主题色变量）
```

### 5.2 行为

- **加载**：`main.ts` 读 `document.currentScript`（或按 `src*="widget.js"` 兜底查找）src 的 `?appKey=`；apiBase = script src 的 origin + `/api`。缺 appKey → console 报错、不渲染。
- **初始化**：拉 `GET /open/v1/widget/config` 得品牌信息 → 渲染气泡（themeColor）。
- **交互**：点击气泡展开面板（首次显示 welcomeMessage + avatar）；输入发送 → `POST /open/v1/chat/completions`，展示"输入中…"后渲染助手回复。首条请求不带 sessionId，服务端在响应 `{sessionId, reply}` 里回传新 sessionId，Widget 存入 localStorage（`qingyu_widget_session_<appKey>`），后续请求带上以维持多轮上下文。
- **样式隔离**：整个 UI 挂在 `attachShadow({mode:'open'})` 的容器内，CSS 注入到 shadow root，不受宿主页面影响、也不外泄。
- **移动端**：视口宽度 < 480px 时面板全屏。
- **可选转人工**：面板头部一个"转人工"按钮 → `POST /open/v1/sessions/{id}/handover`。

### 5.3 认证与安全

- 请求仅带 `X-App-Id`（公开 appKey），**不带任何密钥**；服务端按 Origin 白名单授权。
- 浏览器自动带 `Origin` 头，无法伪造（受同源策略保护），构成有效来源校验。

## 6. 管理后台（IntegrationPage 增强）

- 新增 **allowedOrigins** 编辑（多行/逗号分隔文本 → 保存走应用更新接口）。
- 后端 `ApplicationController.update` 增加 `allowedOrigins` 透传。
- 将现有"Widget 嵌入代码"占位替换为**真实片段**（自动填当前 appKey）：
  `<script src="${origin}/api/widget.js?appKey=${appKey}"></script>`，带一键复制。

## 7. 数据模型

- `sys_application` 加列 `allowed_origins TEXT NULL`（`ddl-auto: update` 自动加列，旧行 NULL → Widget 未开放，向后兼容）。

## 8. 测试策略

- 后端单测：`OpenApiAuthServiceTest` 增补 `authenticateByOrigin`（白名单命中/未命中/空配置/停用/缺 appId）。
- Widget 单测（vitest + jsdom）：`config` 的 appKey 解析、`session` 的 localStorage 存取、`api` 的请求构造（header/URL/body）。UI 交互、流式渲染靠人工联调。

## 9. 安全考量

- **无密钥暴露**：Widget 只用公开 appKey；密钥仅服务端到服务端使用。
- **来源白名单**：Origin 由浏览器强制、不可被页面脚本伪造，服务端精确匹配 allowedOrigins。
- **限流仍生效**：Widget 调用经同一拦截器按 appKey 限流，防滥用。
- 残留：allowedOrigins 精确匹配不含通配子域（如 `*.a.com`），首版不支持通配（YAGNI），需要时再加。

## 10. 风险与非目标

- **非目标**：Webhook、富媒体/多语言、通配子域、Widget 深度主题定制、appSecret 加密。
- **风险**：
  - **流式为后续增强**：`/stream` 只返回文本块、不回传服务端 sessionId，无法可靠维持多轮会话；v1 用 `/completions` 规避。后续做流式需让 stream 端点通过响应头 `X-Session-Id` 回传会话 id 再逐字渲染。
  - widget.js 为构建产物：需在部署流程中执行 widget 构建，否则 `/api/widget.js` 404（README 记录）。

## 11. 交付物清单

**后端**：`Application` 加 `allowedOrigins`；`OpenApiAuthService.authenticateByOrigin`；`OpenApiAuthInterceptor` 分流；`OpenWidgetController` + `OpenWidgetConfigResponse`（`/open/v1/widget/config`）；`ApplicationController.update` 透传 allowedOrigins；`OpenApiAuthServiceTest` 增补。

**Widget 项目**：新建 `widget/`（vite 配置 + main/config/api/session/ui/styles + vitest 单测）；构建输出 `backend/.../static/widget.js`。

**管理后台**：`IntegrationPage` 加 allowedOrigins 编辑 + 真实嵌入片段。

**其它**：`.gitignore` 忽略 `backend/src/main/resources/static/widget.js`；README 记录 widget 构建命令。

# 轻语（QingYu Care）全项目安全审查报告

> 日期：2026-09-03 ｜ 方式：静态代码审查（未做真机渗透） ｜ 基线：`master` @ `6ac9bb6`

## 0. 范围与方法

**覆盖**：后端鉴权与会话（Sa-Token、`TenantInterceptor`、`SecurityUtil`）、多租户隔离（`TenantDataAspect`、`TenantOwnershipGuard`、全部原生 SQL 调用点）、管理端控制器（用户/租户/应用/聊天/坐席/分析/计费/模型）、开放 API 与 Widget 鉴权限流额度、五条出站请求路径（HTTP 工具、网页抓取、MCP、OpenAPI schema、Webhook）、文件上传与附件、RAG 对话链路（`ChatServiceImpl`）、全局异常处理、`application.yml` / docker compose / Dockerfile / nginx、前端与 widget 的 HTML 注入面与 URL 白名单、审计脱敏。

**未覆盖**：第三方依赖 CVE 的离线核对（版本清单见 §5，建议接入扫描器）；真机验证；`QaPair` / `AiSkill` / `AbTest` / `HandoverRule` / `Prompt` / `ChatStrategy` 五个控制器只做了「有无角色校验」的抽查，未逐端点通读。

**严重度口径**：
- **高**：无需特殊条件即可越权、拿到凭据或控制他人资源；或生产默认配置即不安全。
- **中**：需要一定条件（同租户账号、公开的 appKey、特定浏览器/部署环境），或后果是信息泄漏、资源耗尽。
- **低**：加固项、纵深防御、设计层面的注意点。

每条标注「已确认」（读代码即成立）或「疑似」（需运行时验证）。

## 1. 结论摘要

| 编号 | 严重度 | 标题 | 位置 | 状态 |
|---|---|---|---|---|
| H1 | 高 | 启动自动创建 `admin / admin123` 平台管理员，无法用环境变量覆盖，口令打进日志 | `AuthService.init` | ✅ 已修（分支 fix/security-review-p0） |
| H2 | 高 | 自助注册凭租户 code 即可加入任意租户，且身份是**租户管理员** | `AuthService.register` | ✅ 已修（分支 fix/security-review-p0） |
| H3 | 高 | 同租户任意成员可重置任何用户（含租户管理员）密码 | `UserController.updateUser` | ✅ 已修（分支 fix/security-review-p0） |
| H4 | 高 | 应用管理端点无角色校验：坐席可读 appSecret/webhookSecret、改 Origin 白名单与 Webhook URL、重置密钥、删应用 | `ApplicationController` | ✅ 已修（分支 fix/security-review-p0） |
| H5 | 高 | Webhook 出站不经 `SsrfGuard`、跟随重定向，「测试 Webhook」回显状态码与异常文本 | `WebhookConfig` / `ApplicationController.testWebhook` | ✅ 已修（分支 fix/security-review-p0） |
| H6 | 高 | CORS 对任意来源反射 + `allowCredentials(true)`，叠加 Sa-Token 写 cookie | `WebConfig.addCorsMappings` | ✅ 已修（分支 fix/security-review-p0） |
| M1 | 中 | `User` 实体直接序列化，密码 BCrypt 哈希随用户列表返回给所有租户成员 | `UserController` / `User` | ✅ 已修（分支 fix/security-review-p0） |
| M2 | 中 | 全局异常处理把 `RuntimeException.getMessage()` 回给客户端 | `GlobalExceptionHandler` | ✅ 已修（分支 fix/security-review-p0） |
| M3 | 中 | `X-App-Id` 不校验归属：可把行建到他人应用名下、触发他人 Webhook 并用他人密钥签名 | `TenantInterceptor` / `WebhookDispatcher` | ✅ 已修（分支 fix/security-review-p0，第二批） |
| M4 | 中 | `SsrfGuard` 漏 `100.64.0.0/10`（阿里云元数据 `100.100.100.200`）；模型 endpoint 与「测试连接」完全不过守卫 | `SsrfGuard` / `ModelRouterService` | ✅ 已修（网段 + 模型端点守卫；真机复验时又补了「放开私网也不放行云元数据」） |
| M5 | 中 | Origin 白名单挡不住非浏览器客户端：公开 appKey 即可无凭据烧额度、灌附件；限流键只有 appKey | `OpenApiAuthService` / `OpenApiAuthInterceptor` | ✅ 已修（限流子桶 + 单会话上限 + 孤儿 2 小时 + 租户级存储配额） |
| M6 | 中 | 开放 API 不校验租户状态；登录不校验 `expireTime` | `OpenApiAuthService` / `AuthService.login` | ✅ 已修 |
| M7 | 中 | 前端「退出登录」不调后端注销；token 30 天有效、`is-share: true`、cookie 不清 | `MainLayout` / `application.yml` | ✅ 已修（前端调注销 + token 7 天 / 活跃 12 小时 / is-share=false） |
| M8 | 中 | 知识库文档上传零校验，100MB 直入 S3，落库失败不回收；Tika 整文件进堆且输出无上限 | `KnowledgeServiceImpl.uploadDocument` | ✅ 已修（上传校验 + 回收 + 流式抽取 + 文本/chunk 上限，第三批） |
| M9 | 中 | `/actuator/metrics` 与 Swagger 匿名可访问 | `SaTokenConfig` / `application.yml` | ✅ 已修（基线关闭，dev profile 打开） |
| L1–L13 | 低 | 密钥明文存库与非常量时间比较、Sa-Token 参数、默认口令、日志级别、nginx 安全头、Webhook 重放、消息长度无上限等 | 见 §4 | 大部分已修：L3/L4/L5/L6/L7/L8/L9/L10/L11/L12/L13；剩 L1（密钥哈希，用户决定不做）、L2（`is-log`） |

**上线前必须修**：H1–H6、M1、M2——**已于 2026-09-03 在分支 `fix/security-review-p0` 修完**（单测 979 + 集成测试 173 全绿，新增守护测试见 §7）。其余按 §6 路线图。

## 2. 高危

### H1 默认平台管理员 `admin / admin123`
- 位置：`backend/src/main/java/com/qingyu/care/service/AuthService.java:47-62`
- 状态：已确认
- 证据：
  ```java
  @PostConstruct @Transactional
  public void init() {
      if (!userRepository.existsByUsernameAcrossTenants("admin")) {
          User admin = User.builder().username("admin")
                  .password(BCrypt.hashpw("admin123"))
                  .role(UserRole.PLATFORM_ADMIN).enabled(true).build();
          userRepository.save(admin);
          log.info("Platform admin created: admin / admin123");
  ```
- 场景：生产首启即存在众所周知的平台管理员口令，没有环境变量覆盖、没有首登强制改密；部署文档也没有把「改 admin 口令」列进安全清单。平台管理员绕过全部租户过滤器，等于整库读写。
- 修复：口令改从环境变量读（如 `QINGYU_ADMIN_INITIAL_PASSWORD`），缺省时生成随机口令只打一次到日志；或首次登录强制改密（给 `User` 加 `mustChangePassword`）。删掉那行把口令打进日志的 `log.info`。

### H2 自助注册可加入任意租户并成为租户管理员
- 位置：`AuthService.java:142-146`、`:170`；`dto/RegisterRequest.java:29`
- 状态：已确认
- 证据：
  ```java
  if (request.getTenantCode() != null && !request.getTenantCode().isEmpty()) {
      tenant = tenantRepository.findByCode(request.getTenantCode())
              .orElseThrow(...);           // 加入已有租户
  }
  ...
  .role(UserRole.TENANT_ADMIN) // 注册用户默认为租户管理员
  ```
- 场景：`POST /v1/auth/register` 带 `tenantCode` 无需任何邀请/审批即落入该租户，且角色是 `TENANT_ADMIN`。租户 code 并不保密：平台管理员建租户时自己填（`TenantController.create` 收整个实体），多半是 `acme` 这类可猜的值；演示租户 `demo-cloudboat` 更是写在 README 里。开着 `demo` profile 的公网实例，任何人注册即成为演示租户管理员，可读全部会话与知识库、改 Webhook、拿 appSecret。
- 修复：去掉「凭 code 加入」这条路径，加入租户只能走租户管理员的「邀请」（`UserController.createUser` 已具备）；若要保留，改成一次性邀请 token + 默认 `TENANT_MEMBER`。

### H3 同租户任意成员可重置任何用户的密码
- 位置：`controller/UserController.java:169-214`（重点 `:207-209`）、`:259-270`
- 状态：已确认
- 证据：
  ```java
  return userRepository.findById(id)
          .filter(this::canAccessUser)            // 只校验「同租户」
          .map(user -> {
              ...
              if (request.getEnabled() != null) { if (isPlatformAdmin() || isTenantAdmin()) ... }
              if (request.getRole() != null && (isPlatformAdmin() || isTenantAdmin())) ...
              if (request.getPassword() != null && !request.getPassword().isBlank()) {
                  user.setPassword(BCrypt.hashpw(request.getPassword()));   // ← 无角色校验
  ```
- 场景：`TENANT_MEMBER`（坐席）对本租户管理员的 id 发 `PUT /v1/users/{id}` `{"password":"x"}`，管理员密码被改，坐席以管理员身份登录 → 租户内完全提权。同一分支里 `nickname/email/phone/avatar` 也可被任意同租户成员改写。
- 修复：`updateUser` 里区分「改自己」与「改别人」：改别人必须 `isTenantAdmin() || isPlatformAdmin()`；改自己密码要求提供旧密码并校验。建议同时给 `UserController` 整体加 `SecurityUtil.requireTenantAdmin()`，只放行「本人改本人资料」这一条例外。

### H4 应用管理端点无角色校验，坐席可读取并重置密钥
- 位置：`controller/ApplicationController.java:45-68`（list/get）、`:108-125`（update）、`:132-138`（delete）、`:145-167`（regenerate）、`:208-216`（`checkAppAccess` 只判租户）；`entity/Application.java:52,109`（`appSecret`/`webhookSecret` 无 `@JsonIgnore`）
- 状态：已确认
- 证据：
  ```java
  private void checkAppAccess(Application app) {
      if (authService.isPlatformAdmin()) return;
      Long tenantId = TenantContext.getTenantId();
      if (!app.getTenant().getId().equals(tenantId)) throw ...;   // 没有任何角色判断
  }
  @GetMapping public ApiResponse<List<Application>> getAll() { ... return ApiResponse.success(apps); } // 整个实体含两把密钥
  ```
  `grep -L SecurityUtil controller/*.java` 显示 17 个控制器没有任何 `SecurityUtil` 调用，`ApplicationController` 是其中之一。
- 场景：坐席账号 `GET /v1/applications` 直接拿到全部应用的 `appSecret`（服务端凭据，可见 INTERNAL 文档）与 `webhookSecret`；可把 `allowedOrigins` 改成自己的域名嵌 widget、把 `webhookUrl` 改到自己的服务器收全部会话事件、重置密钥让租户集成中断、直接删应用。前端把「我的应用」对成员隐藏，但「接入配置」页对成员可见且能复制 appSecret，前后端都没守住。
- 修复：`ApplicationController` 的 create/update/delete/regenerate-*/webhook/test 全部加 `SecurityUtil.requireTenantAdmin()`；list/get 对非管理员返回脱敏 DTO（`appSecret`/`webhookSecret` 置 null 或只给尾 4 位）。更彻底的做法是密钥只在生成/重置时返回一次，存库改存哈希（见 L1）。同时补一张「TENANT_MEMBER 允许做什么」的权限矩阵——目前后端除 `UserController` 与 `requireAgent` 外对成员与管理员完全不区分，知识库/工具/模型/MCP 的增删改成员都能做。

### H5 Webhook 出站没有 SSRF 防护
- 位置：`config/WebhookConfig.java:24-30`、`service/webhook/RestClientWebhookHttpClient.java:22-28`、`controller/ApplicationController.java:121`（任意 `webhookUrl` 直接落库）、`:173-194`（`testWebhook`）
- 状态：已确认
- 证据：
  ```java
  SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory(); // JDK HttpURLConnection，默认跟随重定向
  return RestClient.builder().requestFactory(factory).build();                   // 无 SsrfGuard、无钉 IP
  ...
  } catch (Exception e) {
      return ApiResponse.success(new WebhookTestResult(false, null, e.getMessage()));  // 回显连接错误文本
  ```
  `grep SsrfGuard service/webhook/ controller/ApplicationController.java` 为空。其余四条出站路径（HTTP 工具、网页抓取、MCP、OpenAPI schema）都经 `SsrfGuard.resolveAndValidate` + 钉 IP + 禁重定向，唯独 Webhook 漏了。
- 场景：租户用户把 `webhookUrl` 设成 `http://169.254.169.254/latest/meta-data/`、`http://redis:6379`、`http://rustfs:9000`、`http://postgres:5432`，点「测试」：返回 HTTP 状态码或 `Connection refused`/`Read timed out` 文本 → 对容器网络与云元数据做端口探测；正式投递则以平台身份向内网服务发 POST（body 是签名 JSON，头 `X-Qingyu-*`）。DNS rebinding 也能用（没有钉 IP）。
- 修复：`WebhookConfig` 改用与 `RestClientHttpToolExecutor` 同款的 Apache HttpClient 5 构造（`SsrfGuard.resolveAndValidate` + `pinnedResolver` + `disableRedirectHandling`），最好把这段构造抽成公共工厂让五条路径共用；`ApplicationController.update` 落库前先 `SsrfGuard.assertAllowed(webhookUrl)` 且只允许 `https://`（至少 http/https）；`testWebhook` 的失败原因只回「连接失败/超时/非 2xx」三类枚举，不回 `e.getMessage()`。

### H6 CORS 全开 + 携带凭据
- 位置：`config/WebConfig.java:28-34`；`application.yml:101-116`（Sa-Token 未关 cookie）
- 状态：已确认（利用条件见下）
- 证据：
  ```java
  registry.addMapping("/**")
          .allowedOriginPatterns("*")
          .allowedHeaders("*")
          .exposedHeaders("Authorization", "satoken")
          .allowCredentials(true)
  ```
  Sa-Token 默认 `is-read-cookie=true`，登录时会写 `satoken` cookie（2026-09-02 真机验证时已观察到「前端 `localStorage.clear()` 清不掉它」）；cookie 未配置 `SameSite`/`Secure`/`HttpOnly`。
- 场景：受害者登录管理后台后访问攻击者页面，页面执行 `fetch('https://<后台>/api/v1/applications', {credentials:'include'})`，浏览器带上 `satoken` cookie，响应头 `Access-Control-Allow-Origin` 被反射成攻击者域 + `Allow-Credentials: true`，攻击者读到响应（含 H4 里的密钥）并可发任意写请求。Chrome/Edge 因 cookie 默认 `SameSite=Lax` 会拦下跨站 XHR，Firefox 默认不启用 Lax，可直接利用；Safari 受 ITP 影响。这个配置本身在管理端 `/v1/**` 上没有任何正当用途——管理前端与后端同源（nginx 反代 `/api/`），只有 `/open/v1/**` 与 `/widget.js` 需要跨域，而它们**不需要 cookie**。
- 修复：CORS 拆两条：`/open/v1/**` 与 `/widget.js` 允许任意来源但 `allowCredentials(false)`；`/v1/**` 只允许配置的前端来源（环境变量），或干脆不配 CORS。同时 `sa-token.is-read-cookie: false`（SPA 只用请求头，cookie 没有用处，关掉即消灭整个 CSRF 面），若保留 cookie 则显式 `sa-token.cookie.same-site: Lax`、`secure: true`、`http-only: true`。

## 3. 中危

### M1 密码哈希随用户列表返回
- 位置：`entity/User.java:43-44`（`password` 无 `@JsonIgnore`）、`controller/UserController.java:62-74`（`ApiResponse<List<User>>`，无角色校验）、`:79-85`、`:159-161`、`:210-211`
- 状态：已确认
- 场景：任意租户成员 `GET /v1/users` 得到本租户所有账号的 BCrypt 哈希，可离线爆破；平台管理员的 `findAll()` 泄全平台。
- 修复：`password` 字段加 `@JsonProperty(access = WRITE_ONLY)` 或 `@JsonIgnore`（注意 `createUser` 用 `Map` 收请求体，不受影响）；更稳妥是返回 `UserDTO`。`listUsers` 建议限 `requireTenantAdmin`，或对成员只返回 id/nickname/role。

### M2 全局异常处理回显内部异常消息
- 位置：`exception/GlobalExceptionHandler.java:144-148`
- 状态：已确认
- 证据：
  ```java
  @ExceptionHandler(RuntimeException.class)
  public void handleRuntimeException(RuntimeException e, HttpServletResponse response) {
      writeError(response, 500, 500, "服务器内部错误: " + e.getMessage());
  ```
- 场景：`DataIntegrityViolationException`、`JpaSystemException`、`IllegalArgumentException`（如 `UserRole.valueOf` 非法值）、第三方 SDK 异常的 message 都原样回给客户端——含 SQL 语句、约束名、表名、内部主机名。CLAUDE.md 里记录的「撞唯一约束 → 500 且把原始 SQL 与约束名回给客户端」正是由这一行造成。
- 修复：统一回「服务器内部错误」+ 一个请求 id（写进日志便于关联）；`IllegalArgumentException` 单独映射成 400 并给通用文案。

### M3 `X-App-Id` 不校验归属
- 位置：`config/TenantInterceptor.java:36-43`；后果链：`config/TenantEntityListener.java:32-38` → `service/impl/ChatServiceImpl.java:775-777`（事件用 `session.getAppId()`）→ `service/webhook/WebhookDispatcher.java:46-49`（按 appId 反查应用取 URL 与密钥）
- 状态：已确认
- 证据：
  ```java
  String appIdHeader = request.getHeader("X-App-Id");
  if (appIdHeader != null && !appIdHeader.isEmpty()) {
      TenantContext.setAppId(Long.valueOf(appIdHeader));   // 未校验该 app 属于当前租户
  ```
- 场景：租户 A 的用户把请求头改成租户 B 的应用 id（自增整数，可枚举）。读路径被 `tenantFilter`+`appFilter` 双重过滤挡住，但写路径 `TenantEntityListener` 会把新建行写成 `tenant_id=A, app_id=B`；在管理端「智能问答」聊一句并结束/转人工，`WebhookDispatcher` 按 `app_id=B` 反查出**租户 B** 的 `webhookUrl` 与 `webhookSecret`，向 B 的回调地址发出用 B 的密钥正确签名的 `session.ended` / `session.handover_requested` 事件——B 的下游系统无法分辨真伪。
- 修复：`TenantInterceptor` 取到 appId 后用 `ApplicationRepository.findTenantIdByAppId`（已存在的 native 查询）校验等于当前 `tenantId`，不等则忽略该头（平台管理员豁免）。`WebhookDispatcher` 再加一道 `app.getTenant().getId().equals(event.getTenantId())` 断言。

### M4 `SsrfGuard` 覆盖缺口 + 模型 endpoint 不过守卫
- 位置：`config/SsrfGuard.java:63-72`；`service/ModelRouterService.java:213-223`（`baseUrl(config.getEndpoint())`）；`controller/AiModelController.java:92-109`（`testModel`，回显 `e.getMessage()`）
- 状态：已确认
- 证据：
  ```java
  static boolean isBlocked(InetAddress a) {
      if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
              || a.isAnyLocalAddress() || a.isMulticastAddress()) return true;
      byte[] bytes = a.getAddress();
      return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;   // 只补了 IPv6 ULA
  }
  ```
  `isSiteLocalAddress()` 只覆盖 10/8、172.16/12、192.168/16。`100.64.0.0/10`（CGNAT）不在其中，而**阿里云 ECS 的元数据服务是 `100.100.100.200`**——本项目默认对接 DashScope，阿里云是最可能的部署目标。`grep -rn "100\.64\|100\.100\.100" backend/src` 为空，测试也没覆盖。
  `AiModelConfig.endpoint` 是租户任填的 URL，`createOpenAiCompatibleClient` 直接 `baseUrl(...)`，没有任何守卫；`POST /v1/ai-models/{id}/test` 会向该地址发一次真实 POST 并把异常文本回给调用方。
- 场景：租户把模型 endpoint 填成 `http://100.100.100.200/latest/meta-data/ram/security-credentials/`（阿里云）或 `http://postgres:5432`，点「测试连接」拿到错误文本做探测；正式对话时每一轮都会往该地址发带 `Authorization` 头的 POST。
- 修复：`isBlocked` 补 `100.64.0.0/10`、`192.0.0.0/24`、`198.18.0.0/15`、`240.0.0.0/4`、`::ffff:` 映射段（Java 已转成 Inet4Address，顺带核对）、NAT64 `64:ff9b::/96`；给 `SsrfGuardTest` 加这些用例。`ModelRouterService.saveModel` 与 `testModel` 对 endpoint 做 `SsrfGuard.assertAllowed`，出站客户端同样钉 IP。

### M5 开放 API 的免凭据路径可被非浏览器客户端滥用；限流粒度只到应用
- 位置：`service/OpenApiAuthService.java:39-66`；`config/OpenApiAuthInterceptor.java:60-64`、`:70-76`；`service/RateLimitService.java:56-64`（key = `ratelimit:` + appKey）
- 状态：已确认
- 场景：appKey 明写在宿主页 `<script src=...?appKey=...>` 里，任何人用 curl 带 `Origin: https://<白名单域名>` 即通过鉴权（CLI 的 `--as-visitor --origin` 就是这么做的）。于是无需任何凭据即可：① 以 60 次/分（默认）持续调用 `/open/v1/chat/*` 烧租户的 LLM 额度与费用；② `/open/v1/chat/files` 每次最多 20MB，60 次/分 ≈ 1.2GB/分，孤儿 24 小时后才清，理论上能灌到 TB 级；③ 用满限流桶让该应用所有真实访客一起吃 429（限流键只有 appKey）。
- 修复：限流增加 `appKey + 客户端 IP` 与 `sessionId` 两个子桶（IP 取 `X-Forwarded-For` 时须只信任 nginx 追加的最后一跳）；附件上传单独限「每会话每小时 N 个 / 总字节数」；租户级存储配额；对匿名 Origin 路径考虑加 PoW/验证码或把会话创建与消息数绑到 IP。

### M6 租户状态与到期时间未在开放 API / 登录路径生效
- 位置：`service/OpenApiAuthService.java:18-56`（只判 `app.enabled`）；`service/AuthService.java:82-84`（只判 `status`，不判 `expireTime`）
- 状态：已确认
- 场景：平台管理员冻结（`FROZEN`）某租户后，该租户的 widget 与开放 API 照常工作、照常消耗模型费用；套餐 `expireTime` 过了也不影响登录与调用，只有手动切 status 才生效。
- 修复：`authenticate`/`authenticateByOrigin` 加 `app.getTenant().getStatus() == ACTIVE` 与 `expireTime` 判断（403）；登录同样判 `expireTime`。

### M7 退出登录不注销服务端 token；token 生命周期过长
- 位置：`frontend/src/layouts/MainLayout.tsx:145-148`（只清本地状态）、`frontend/src/stores/authStore.ts:32-38`；`frontend/src/api/auth.ts:40-41`（`logout` 接口已封装但无人调用）；`application.yml:106-114`
- 状态：已确认
- 证据：`timeout: 2592000`（30 天）、`active-timeout: -1`、`is-share: true`（同账号所有登录共用一个 token）。
- 场景：用户在公共电脑「退出」，token 在服务端仍有效 30 天，cookie 也还在（`localStorage.removeItem` 清不掉）；`is-share` 让泄漏的一个 token 等于该账号全部会话。
- 修复：`handleLogout` 先 `await authService.logout()` 再清本地；`sa-token.timeout` 缩到 1–7 天并设 `active-timeout`（如 2 小时）；`is-share: false`；`is-read-cookie: false`（同 H6）。

### M8 知识库文档上传零校验与摄取内存风险
- 位置：`service/impl/KnowledgeServiceImpl.java:172-190`；`service/FileService.java:46-59`；`application.yml:42-45`（100MB / 200MB）；Tika 段：`KnowledgeServiceImpl.java:316-331` 与 `FileService.java:100-118`（由子代理反编译 `spring-ai-tika-document-reader-2.0.0` 核对：`BodyContentHandler(-1)` 无输出上限）
- 状态：上传段已确认；Tika 段疑似（未实测 OOM 阈值）
- 证据：
  ```java
  public Document uploadDocument(Long knowledgeBaseId, MultipartFile file) {
      KnowledgeBase kb = getKnowledgeBase(knowledgeBaseId);
      FileInfo fileInfo = fileService.uploadKnowledgeFile(file);   // 先传 S3
      Document doc = Document.builder().name(file.getOriginalFilename())  // length=255 无截断
              .documentType(detectDocumentType(file.getOriginalFilename())) // default → TEXT，不拒绝任何扩展名
      doc = documentRepository.save(doc);                           // 失败时 S3 对象无人回收
  ```
  对比聊天附件路径 `ChatAttachmentService.upload` 有大小/扩展名/Tika MIME 四道校验且失败回滚 S3，知识库路径一道都没有；`KnowledgeController` 无任何角色校验，租户成员即可触发。
- 场景：反复上传 100MB 任意文件（或文件名 >255 字符让 INSERT 失败）填满对象存储且无库行指向；6 线程长任务池并发解析大文件时整文件 `byte[]` + 无上限文本抽取可把堆打爆。
- 修复：`uploadDocument` 前置 `getSize()` 硬闸门、扩展名白名单（与 `detectDocumentType` 的 switch 对齐、去掉 default 分支）、Tika `detect` 比对、文件名截断；`save` 失败 `deleteFile` 再抛；摄取改流式（`TikaDocumentReader` 接 `InputStreamResource`）并给 `BodyContentHandler` 设上限；按租户加存储配额；给 `documents/` 前缀做一轮「无库行指向」对账清理。

### M9 actuator 与 Swagger 匿名可访问
- 位置：`config/SaTokenConfig.java:44-53`（豁免 `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`）；`application.yml:244-249`（暴露 `health,info,metrics`）
- 状态：已确认
- 场景：`/api/actuator/metrics/http.server.requests` 等匿名可读（暴露 URI、错误率、JVM 信息）；`/api/v3/api-docs` 把全部接口契约给出去，方便攻击者对 H3/H4 这类问题做定向利用。
- 修复：生产 profile `springdoc.api-docs.enabled=false`、`springdoc.swagger-ui.enabled=false`；actuator 只暴露 `health`，`metrics` 若要保留就单独走内网端口（`management.server.port`）或加鉴权。

## 4. 低危与加固项

- **L1 密钥明文存库 + 非常量时间比较**：`Application.appSecret/webhookSecret`、`AiModelConfig.apiKey`、`AiTool/McpServer.authHeader` 均明文；`OpenApiAuthService.java:28` 用 `apiKey.equals(app.getAppSecret())`。建议 `appSecret` 只在生成时返回一次、库里存 SHA-256，比较用 `MessageDigest.isEqual`；第三方凭据用 KMS/信封加密。
- **L2 Sa-Token 参数**：`is-log: true`（生产关掉，避免登录事件含 token 进日志）；cookie 未显式配置 `secure/httpOnly/sameSite`；`token-style: uuid` 可接受。
- **L3 配置与容器默认口令**：`application.yml:12-14` 数据源 `postgres/postgres` 写死不走环境变量（生产靠 `SPRING_DATASOURCE_*` 覆盖，但基线仍是弱口令）；`:228-229` RustFS 默认 `rustfsadmin/rustfsadmin123`；`docker/docker-compose.yml` 三个基础设施端口绑 `0.0.0.0`、Redis 无密码、RustFS 控制台开放。开发用可以接受，但建议开发 compose 也绑 `127.0.0.1:` 前缀，生产 Redis 加 `--requirepass`（当前 `docker-compose.prod.yml:87-100` 没有）。
- **L4 日志级别**：`application.yml:237-241` 基线就是 `com.qingyu.care: DEBUG` 与 `org.springframework.ai: DEBUG`，后者可能把完整 prompt（含知识库正文与访客消息）写进日志；部署文档要求生产降到 INFO，但更稳妥是基线 INFO、dev profile 再抬 DEBUG。
- **L5 nginx 无安全响应头**：`frontend/nginx.conf` 未设 `X-Frame-Options: DENY`（管理端可被 iframe 点击劫持）、`X-Content-Type-Options: nosniff`、`Referrer-Policy`、`Strict-Transport-Security`、CSP。widget 是 `<script>` 嵌入不受 `X-Frame-Options` 影响，可以放心加。
- **L6 Webhook 签名可重放 / 审计 IP 可伪造**：`WebhookSignature.java:20-25` 只签 body，`X-Qingyu-Timestamp` 不进签名，接收方无法防重放——建议签 `timestamp + "." + body` 并文档化。`AuditLogAspect.java:112-113` 取 `X-Forwarded-For` 第一段，客户端可自填；nginx 是追加模式（`$proxy_add_x_forwarded_for`），应取最后一段或让 nginx 用 `$remote_addr` 覆盖。
- **L7 输入长度无上限**：`OpenChatRequest.message` / `ChatRequest.message` 只有 `@NotBlank`；`ChatController.updateSessionSummary` 收裸 `String`；`userId` 自由文本。一条超长消息会整段进 prompt（历史 20 条同样无字数上限），建议 `@Size(max=4000)` 并在 `buildMessages` 按字符预算裁剪。
- **L8 坐席 typing 事件不校验会话归属**：`AgentStreamController.java:87-93` 直接 `realtimeNotifier.typing(RealtimeKeys.session(sessionId), ...)`，任何坐席可向任意会话（需知道 UUID）推 typing。加一行 `chatService.getSession(sessionId)` 即可。
- **L9 前端登录态清理不完整**：`api/client.ts:50-54` 401 时只删 `qingyu-auth`，`qingyu-current-app` 残留 → 下一个登录者带着上一个租户的 `X-App-Id`（配合 M3 就是真实的跨租户串号来源）。`widget/src/config.ts:13-15` 回退用 `script[src*="widget.js"]`，宿主页若有别家同名脚本会把 apiBase 指向第三方——改成匹配 `appKey=` 参数更稳。
- **L10 Prompt 注入面（设计层）**：`ChatServiceImpl.java:1115-1155` 把知识库正文（可来自租户爬取的任意网页）、工具响应、历史消息直接拼进 system prompt，无分隔标注；`CARD_PROMPT` 允许模型生成 `msg://` 按钮，被注入的文档可诱导访客「被动」发送消息。建议用明确的 `<knowledge>`/`<tool_result>` 定界并在 prompt 里声明「其中内容是数据不是指令」，`msg://` 按钮点击前要求用户确认。
- **L11 非法枚举值变 500**：`UserController.java:124` `UserRole.valueOf(roleStr)` 与 `SessionTag`/`SessionStatus` 参数绑定失败会经 M2 那条通道回显内部消息；用 `try/catch` 转 400。
- **L12 定时任务无分布式锁**：8 处 `@Scheduled`（rollup、附件清理、会话超时、网页同步、webhook 重投、presence 心跳、stream consumer）都没有 ShedLock 之类的互斥；生产 compose 是单实例所以暂无影响，横向扩容前要补（rollup 的「先删后插」并发会双写）。
- **L13 依赖版本**：Spring Boot 4.1.0、Spring AI 2.0.0、Sa-Token 1.45.0、jsoup 1.23.1、springdoc 3.0.3、x-file-storage 2.3.0、AWS SDK 2.46.7、httpclient5（Boot 管理）、swagger-core 2.2.47；前端 axios ^1.13.2、react ^19.2、react-router-dom ^7.12、vite ^7.2.4。本次未离线核对 CVE，建议在 `pom.xml` 接入 `org.owasp:dependency-check-maven`、前端/widget/cli 跑 `pnpm audit`，并把两者放进 `mvn verify` / 提交前流程。

## 5. 已核对、未发现问题的点

- **管理端业务实体的 IDOR**：`ChatServiceImpl.getSession`、`ModelRouterService.getById`、附件下载、反馈提交均在 `findById/findBySessionId` 之后用 `TenantOwnershipGuard.mutableInCurrentContext` 校验，越权按 404 处理；`AgentSessionDao`、分析类原生 SQL 都显式带 `tenant_id`；`AnalyticsService` 的 `appId` 下钻只在本租户内生效。
- **原生 SQL**：50 余处 `nativeQuery`/`JdbcTemplate` 全部参数绑定；`KeywordSearchDao` 的 `plainto_tsquery` 入参走占位符；`RetrievalScope.toFilterExpression` 的值全部来自服务端上下文与库查询，不含用户输入。
- **四条出站路径**（HTTP 工具、网页抓取/robots、MCP、OpenAPI schema）：`SsrfGuard.resolveAndValidate` + 连接期钉 IP + 禁重定向 + 超时 + 5MB 上限 + Content-Type 白名单；`UrlTemplateFiller` 对 LLM 参数 `URLEncoder.encode`，path 缺参抛错、query 缺参整段删除；`OpenApiSchemaParser` 走 Jackson 树解析，不做远程 `$ref` 解析。
- **聊天附件**：大小 → 扩展名白名单（无 svg/html）→ Tika MIME 比对 → 只读文件头取尺寸；私有桶代理下发、`nosniff`、开放 API 需 `?sessionId=` 二重凭证；`attachToMessage` 校验租户 + 会话 + 未占用。
- **前端与 widget**：无 `dangerouslySetInnerHTML`；widget 仅对模块常量与经 `escapeHtml` 的品牌名用 `innerHTML`，其余全 `textContent`；`safeUrl` 只放行 http/https 且 fail-closed；`window.open` 带 `noopener,noreferrer`；登录后固定跳 `/`，无 open redirect；审计详情按文本渲染。
- **密码与平台端点**：BCrypt 存储与比对；登录失败信息不区分用户名/密码；`/v1/platform/**` 由 `PlatformEndpointGuardTest` 强制 `requirePlatformAdmin`；`TenantController` 每个端点 `checkPlatformAdmin`；包安装请求的变量值经显式 `toString` 不进审计表。
- **生产编排**：`docker-compose.prod.yml` 不对宿主机暴露 DB/Redis/RustFS 端口，RustFS 控制台关闭，凭据走 `.env`，后端容器非 root；`.env` 在 `.gitignore`，仓库与 `.env.example` 中未发现真实密钥（`sk-your-...`、`change-me-in-production` 均为占位符）。

## 6. 修复路线图

| 阶段 | 项 | 说明 |
|---|---|---|
| P0（上线前） | H1 H2 H3 H4 H6 M1 M2 | 全是几十行以内的改动：环境变量口令、删「凭 code 加入」、`updateUser` 角色判断、`ApplicationController` 加 `requireTenantAdmin` + 脱敏 DTO、CORS 拆分 + 关 cookie、`User.password` 加 `WRITE_ONLY`、异常消息收口 |
| P0（上线前） | H5 | Webhook 客户端换成与工具执行器同款的钉 IP 构造；落库前校验 URL |
| P1 | M3 M4 M5 M6 M7 M9 | 归属校验一行、`SsrfGuard` 补网段 + 模型 endpoint 过守卫、限流子桶、租户状态校验、前端调注销、关 Swagger/metrics |
| P1 | M8 | 知识库上传补校验与回滚，摄取改流式；这条改动量最大 |
| P2 | L1–L13 | 按需排期；L9 与 M3 同批做最省事，L12 在横向扩容前做 |

**修完后建议补的守护测试**：`UserControllerTest`（成员改管理员密码 → 403）、`ApplicationControllerTest`（成员 list 不含 secret、成员 update → 403）、`RegisterIT`（带 tenantCode 注册被拒或落 MEMBER）、`SsrfGuardTest` 补 `100.64.0.0/10` 等网段、`WebhookSsrfTest`（内网 URL 被拒 + 302 不跟随）、`CorsConfigTest`（`/v1/**` 不反射任意 Origin）、`TenantInterceptorTest`（他租户 appId 被忽略）。

## 7. 修复记录

### 2026-09-03 · 分支 `fix/security-review-p0`（P0 全部 + 顺手带的几条）

| 项 | 改动 | 守护测试 |
|---|---|---|
| H1 | `AuthService.init` 口令来自 `qingyu.care.platform.admin-initial-password`（`QINGYU_ADMIN_INITIAL_PASSWORD`），为空则随机生成、只打一次 WARN；dev/test profile 固定 `admin123` | `AuthServiceInitTest` |
| H2 | `register` 带 `tenantCode` 一律 403，删掉「凭 code 加入」分支 | `AuthServiceRegisterTest`、`MemberPrivilegeIT` |
| H3 | `UserController.updateUser` 先判 self / 管理员，坐席只能改自己 | `UserControllerTest`（+3 条）、`MemberPrivilegeIT` |
| H4 | `ApplicationController` 写端点全部 `requireTenantAdmin`；响应改 `ApplicationView`，密钥只给管理员；前端「接入配置」只对管理员显示 | `ApplicationControllerTest`、`MemberPrivilegeIT` |
| H5 | 新增 `PinnedHttpClientFactory`，HTTP 工具 / 网页抓取 / Webhook 三条路径共用；`RestClientWebhookHttpClient` 走 SsrfGuard + 钉 IP + 禁重定向；`webhookUrl` 落库前校验；`testWebhook` 不回显异常文本；`allow-private-targets` 只在 test profile 打开 | `RestClientWebhookHttpClientTest`、`PinnedHttpClientFactoryTest`、`ApplicationControllerTest`、既有 `WebPageFetcherDownloadTest`（302 不跟随） |
| H6 | CORS 拆两套：`/open/v1/**` 与 `/widget.js` 任意来源不带凭据，其余只放行 `qingyu.care.cors.allowed-origins`（默认空）；`sa-token.is-read-cookie: false` | `CorsPolicyIT` |
| M1 | `User.password` 标 `WRITE_ONLY` | `UserPasswordNotSerializedTest`、`MemberPrivilegeIT` |
| M2 | `RuntimeException` 回「服务器内部错误（错误编号 xxx）」；`IllegalArgumentException` 归 400 且不回显 | `GlobalExceptionHandlerTest` |
| M4（部分） | `SsrfGuard.isBlocked` 补 100.64/10、192.0.0/24、198.18/15、240/4、NAT64 | `SsrfGuardTest` |
| M7（部分） | 前端退出登录走 `lib/session.performLogout`：先调后端注销再清本地（含 `qingyu-current-app`）；401 也清 `qingyu-current-app`（L9） | `session.test.ts` |
| L5 | `frontend/nginx.conf` 加 `X-Frame-Options` / `nosniff` / `Referrer-Policy` / `Permissions-Policy` | — |

配套：`.env.example` 与 `docker-compose.prod.yml` 新增 `QINGYU_ADMIN_INITIAL_PASSWORD`、`CORS_ALLOWED_ORIGINS`；部署文档凭据表、环境变量表、安全清单同步；CLAUDE.md 新增「安全基线」一节。

### 2026-09-03 · 分支 `fix/security-review-p0`（第二批：P1 + M5/M8 最小修复）

| 项 | 改动 | 守护测试 |
|---|---|---|
| M3 | `TenantInterceptor` 用 `findTenantIdById` 校验 `X-App-Id` 属于当前租户，不属于则忽略并 WARN（平台管理员豁免）；`WebhookDispatcher` 再兜一道「应用租户 == 事件租户」 | `TenantInterceptorTest`、`WebhookDispatcherTest` |
| M4 | `ModelRouterService.requireValidEndpoint`（http(s) + SsrfGuard），`AiModelController` 建/改/测试连接与 `PlatformController` 共享模型建/改都先过它；`qingyu.care.model.allow-private-endpoints` 只在 dev 打开 | `ModelRouterServiceEndpointGuardTest`、`AiModelControllerTest` |
| M5（部分） | `OpenApiAuthInterceptor` 在应用级桶之下再按「应用 + 客户端 IP（XFF 最后一段）」分桶，`per-client-rate-limit-per-minute` 默认 20 | `OpenApiAuthInterceptorClientLimitTest` |
| M6 | `OpenApiAuthService` 两条鉴权路径校验租户 `status` 与 `expireTime`；登录同样校验 `expireTime`；`findByAppKey` 加 `@EntityGraph(tenant)`（拦截器阶段没有 EntityManager，懒加载会炸） | `OpenApiAuthServiceTest`（+3）、`AuthServiceLoginTest` |
| M7 | `sa-token.timeout` 7 天、`active-timeout` 12 小时、`is-share: false` | — |
| M8（部分） | `KnowledgeServiceImpl.uploadDocument`：大小闸门（`max-upload-bytes`，默认 100MB）→ 非空 → 扩展名白名单；文件名截断到 255；落库失败删 S3 对象 | `KnowledgeUploadValidationTest` |
| M9 / L4 | 基线关闭 Swagger、actuator 只暴露 health、日志 INFO；dev profile 打开；`NoResourceFoundException` 归 404 而非 500 | `ManagementEndpointsExposureIT` |
| L3 | 开发 compose 三个基础设施端口只绑 `127.0.0.1` | — |
| L6（部分） | 审计日志与限流的客户端 IP 都取 `X-Forwarded-For` 最后一段 | `OpenApiAuthInterceptorClientLimitTest` |
| L7 | `OpenChatRequest.message` / `ChatRequest.message` 加 `@Size(max = 8000)` | `ChatMessageLengthValidationTest` |
| L8 | `AgentStreamController.agentTyping` 先 `getSession` 校验归属 | `AgentStreamControllerTest` |
| L11 | `UserController.createUser` 非法角色回 400 | `UserControllerTest` |
| 顺带 | `OpenApiAuthInterceptor` 的兜底 catch 记日志（曾静默吞掉 LazyInitializationException，28 条 IT 一起红却查不到原因） | — |

**仍未修**（都是设计层或需要产品决策）：M5 的附件按会话配额与租户存储配额、M8 的 Tika 流式解析与输出上限、L1 密钥哈希存储、L2 `is-log`、L6 Webhook 签名纳入时间戳（改契约）、L10 prompt 定界、L12 调度器分布式锁（横向扩容前）、L13 依赖扫描接入。

### 2026-09-03 · 分支 `fix/security-review-p0`（第三批：讨论后选定的六项）

| 项 | 改动 | 守护测试 |
|---|---|---|
| M5（部分） | `ChatAttachmentService.upload` 先 `countBySessionId` 判单会话上限（`max-per-session`，20）；孤儿保留 24h → 2h | `ChatAttachmentServiceTest`（+2） |
| M8 | 新增 `TikaTextExtractor`：S3 `openStream` 流式 + `BodyContentHandler(maxChars)`，超限截断 + WARN；切分后 `subList` 封顶 chunk 数（`TokenTextSplitter` 到顶会把剩余文本追加成最后一块）；单文件上限 30MB、multipart 32MB、nginx 40m | `TikaTextExtractorTest`、`KnowledgeIngestLimitsTest` |
| L6 | 签名改为 `HMAC(secret, timestamp + "." + body)`，`X-Qingyu-Timestamp` 同时发出；文档写明接收方三件事 | `WebhookSignatureTest`（重写）、`WebhookSenderTest`、`WebhookDeliveryIT` |
| L10 | `buildContext` 包 `<knowledge>`；`ToolResultDelimiter.wrap` 包 `<tool_result>`（HTTP 工具 + MCP）；系统提示末尾追加 `DATA_GUARD_PROMPT` | `ChatPromptGuardTest`、`HttpToolCallbackTest`、`McpToolCallbackTest` |
| L12 | `AnalyticsRollupDao.tryLockDay`（`pg_try_advisory_xact_lock(classId, day)`），抢不到跳过且不计入成功 | `AnalyticsRollupServiceTest`（+1） |
| L13 | `pom.xml` 加 `depcheck` profile（dependency-check 11.1.1，CVSS ≥ 7 失败）+ `scripts/dep-audit.sh`（含三个 pnpm 项目的 `pnpm audit`，显式走官方 registry）；写进部署文档发版清单。首次实跑即抓到 react-router 高危 GHSA-qwww-vcr4-c8h2（RSC 模式 CSRF，本项目是 Vite SPA 不受影响），`react-router-dom` 7.18.1 → 7.18.3 后三个 pnpm 项目全部干净 | 手动步骤 |

**仍未修**：L1 密钥哈希/加密存储（用户决定不做）；L2 `sa-token.is-log`。M5 的租户级存储配额已于 2026-09-04 补齐，见末节。

### 2026-09-03 · 真机冒烟验证（12 条）与由此挖出的缺陷

三批改完后起 `dev,demo` 真机走了一遍。**自动化测试全绿、评审也看不出的一条缺陷，是真机第一次点出来的**：

> **`allow-private-*` 开关放行粒度过粗，把云元数据段一起放开了。**
> 两个开关（`qingyu.care.model.allow-private-endpoints`、`qingyu.care.webhook.allow-private-targets`）
> 是给「本地跑 Ollama」「集成测试的本地 Webhook 接收器」用的，它们都在环回或普通私网地址上；
> 但原实现打开后**只做形态校验**，等于连 `169.254.169.254`（AWS/Azure/GCP）与 `100.100.100.200`
> （阿里云）也一起放开。dev profile 下实测：模型 endpoint 填阿里云元数据地址**创建成功并落库**。
> 单测当时是绿的——因为我把「放开私网时只校验形态」当成预期行为写进了断言，测试固化了错误。
> 修法：新增 `SsrfGuard.assertAllowedHttpUrlAllowingPrivate` 与 `isCloudMetadata`，两个开关都改走它，
> 云元数据段（169.254.0.0/16、100.64.0.0/10、fe80::/10、fd00:ec2::/64）**任何开关都不放行**。
> 复验：两个元数据地址均 400，本地 Ollama `127.0.0.1:11434` 仍 200。

真机验证结果：

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 向量检索活性（换掉 Tika 读取器后最该验的一条）：上传新文档 → 提问 | 命中 5 条，最高 score **0.7997**，排第一的正是新文档，AI 回答准确引用其内容 |
| 2 | Prompt 定界不破坏正常问答 | 同上，`<knowledge>` 标签与 `DATA_GUARD_PROMPT` 未影响回答质量 |
| 3 | token 计量非零（开放 API 专属） | `sys_api_usage` 当日 call=1、token=**2718** |
| 4 | 坐席看应用列表 | `appSecret` / `webhookSecret` 均为 `null` |
| 5 | 坐席改应用 | 403「无权限：该操作仅限租户管理员」 |
| 6 | 用户列表含密码哈希？ | 2 个用户，含 `password` 字段的 **0** 个 |
| 7 | Webhook 回调地址指向内网 / 元数据 / 非 http | 三种都 400，消息可读 |
| 8 | 模型 endpoint 指向云元数据 | **首轮放行 → 修复后 400**（见上） |
| 9 | 模型 endpoint 指向本地 Ollama（dev） | 200，正当用途未被误伤 |
| 10 | 管理端 CORS | 恶意 Origin **403**；配置内 Origin 200 |
| 11 | 开放 API CORS | 任意 Origin 200、回显 Origin、**无** `Allow-Credentials` 头 |
| 12 | 附件单会话上限 + 客户端子桶限流 | 第 1–20 个 200、第 21 个 **400**（上限）、第 22 个 **429**（IP 子桶）——一次验到两条改动 |

冒烟数据已清理（文档删除、知识库恢复 4 篇演示文档、孤儿附件交给 2 小时清理任务）。

### 2026-09-03 · 第二轮真机验证（补做手工清单缺口）与由此挖出的四个缺陷

第一轮只覆盖了 `scripts/manual-smoke-hints.sh` 点名的 5 个章节里的两条半。第二轮补做流式对话、
流式工具调用、Webhook 签名、孤儿清理、前端浏览器五项，**又挖出四个缺陷，其中一个是我自己引入的
生产回归**：

> **① CORS 改动会让生产环境登录不了（我引入的回归，最严重）。**
> nginx 终结 TLS 后转发给后端的是 http，而浏览器的 `Origin` 是 https。Spring 判「是不是跨域」
> 靠 Origin 与请求 URI 的 scheme/host/port 比对，scheme 不同即判为跨域，撞上我把管理端 CORS
> 白名单改成默认为空这条，**同源的登录请求直接 403，整个管理后台进不去**。
> 浏览器里症状只有一句「网络错误，请稍后再试」。
> 修法：`server.forward-headers-strategy: framework`，让 Spring 用 `X-Forwarded-Proto` 重建 URI。
> `CorsPolicyIT` 补两条（同源放行、反代终结 TLS 后同源仍放行），后者变异验证过。

> **② Vite 代理不摘 `Origin`，开发环境端口一漂移就 403。**
> `changeOrigin: true` 只改 Host 不改 Origin。5173 被占用时 Vite 自动漂到 5174/5175，
> 白名单永远追不上。修法：代理的 `proxyReq` 钩子里 `removeHeader('origin')`——走反代时前端与
> 后端本就该视为同源。dev 白名单同时列上三个端口兜底，注释也改对了（原注释说「只有直连才需要」，
> 实际走代理也受限）。

> **③ `allow-private-targets` 落库与出站看的不是同一个开关。**
> 开关打开后出站放行了，但 `ApplicationController` 的落库校验仍硬拦，本地联调根本配不上地址，
> 开关等于半失效。集成测试发现不了——它们直接 `setWebhookUrl` 写库，绕过了控制器。

> **④ 工具调用失败的日志看不出原因。**
> 只记异常类名，被 SSRF 守卫拦下时日志只有 `IllegalArgumentException` 一个词。本机代理是
> fake-IP 模式（所有域名解析到 198.18.0.x，恰在守卫黑名单里），排查花了好几分钟；生产上若某个
> 租户的工具域名解析到被拦网段，运维会完全无从下手。修法：守卫抛的 `IllegalArgumentException`
> 消息里只有 host，可安全入日志；其余异常仍只记类名（消息可能带含密钥的 URL）。

第二轮验证结果：

| # | 验证项 | 结果 |
|---|---|---|
| 13 | 流式逐字增量 | 27 个 delta、跨度 587ms、26/26 相邻间隔非零、最大间隔 87ms → 真逐字，非一次性刷出 |
| 14 | 流式 token 计量 | 7919 → 12123 |
| 15 | 流式工具调用（本项目最大上游坑） | 日志确认 `NoSuchElementException` 触发降级；客户端收到 start/delta/done **无 error**；AI 消息只 +1；sessionId 与 START 一致 |
| 16 | Webhook 签名覆盖时间戳 | 复算 `HMAC(secret, ts + "." + body)` 与收到的签名逐字节相同，且与旧的只签 body 不同 |
| 17 | 孤儿清理连 S3 一起删 | 库行 20 → 0，S3 对象 45 → 25，正好少 20 |
| 18 | 坐席侧边栏无「接入配置」 | 菜单里查不到该项，「我的应用」同样不可见 |
| 19 | 退出登录真的注销 | 本地登录态与 `qingyu-current-app` 都清空；**旧 token 调接口 401「token 无效」** |
| 20 | 管理端登录（浏览器真实请求） | 修复前 403，修复后 200 |

**本机环境限制，记录备查**：本机代理是 fake-IP 模式，`postman-echo.com` / `httpbin.org` /
`dashscope.aliyuncs.com` 全部解析到 `198.18.0.x`，而该段（RFC 2544 基准测试段）在守卫黑名单里。
后果是**本机所有经 SsrfGuard 的出站调用都会被拒**，工具的「凭据真的出站」这条在本机无法验证
（同 MCP 那次的结论）。AI 对话不受影响，它走 openai-java SDK 直连、不经守卫。生产环境不会用
fake-IP 代理，拦 198.18.0.0/15 是对的，不因此放宽。

### 2026-09-03 · 依赖漏洞扫描结果与处置

NVD API key 无效（35 位，标准 UUID 是 36 位；带它请求 NVD 返回 404，不带反而 200），
改用 **OSV 公开 API** 完成扫描——它不需要任何凭证，扫 329 个运行时依赖只用几秒。

结果：13 个依赖命中 23 个漏洞，其中 CRITICAL 3、HIGH 8、MODERATE 12。

**逐条核对触发条件后，只有一条对本项目成立**：

| 条目 | 是否适用 | 依据 |
|---|---|---|
| Tomcat 11.0.22 三个 CRITICAL | 否 | 全是 Tomcat 内置认证器的漏洞（DIGEST 重放、FORM 授权、访问控制）。本项目用 Sa-Token 自己做认证，从未启用这些 authenticator，也没配 security-constraint |
| Netty 4.2.15 八条 | 否 | 多为服务端场景（SPDY 解码、CorsHandler、HTTP/3 帧）。项目只把 Netty 当 MCP 的出站客户端用，不做服务端、不开 HTTP/3 |
| PostgreSQL JDBC 42.7.11 | 否 | 要求启用 SCRAM 通道绑定且攻击者已在中间人位置。数据库在容器网络内 |
| Jackson databind 两条 | 否 | 是 `@JsonView` 与 per-property `@JsonIgnoreProperties` 的绕过；本项目的绑定层边界靠 `@JsonProperty(READ_ONLY)`，没用那两个做安全控制 |
| Log4j API 2.25.4 | 否 | 走 MapMessage 的 JSON 序列化；日志实现是 logback |
| **HttpCore5 5.4.2（GHSA-hf6x-8p5f-cgmf，HIGH）** | **是** | HTTP 头解析可被打到内存耗尽。工具调用 / 网页抓取 / Webhook 三条出站路径都走 httpclient5，目标地址由租户自由填写，恶意服务器返回超大响应头即可触发；现有的超时与 5MB 上限都作用在 body 上，头部大小由 HttpCore 自己管，挡不住 |

**处置**：只覆盖 `httpcore5.version` 到 5.4.3（连带 httpcore5-h2），不整体抬 BOM 版本——
其余条目触发条件不成立，为它们承担 Tomcat / Netty 大版本联动的回归风险不划算。
升级后 OSV 复查 httpcore5 与 httpcore5-h2 均「无已知漏洞」，单测 1029 + IT 177 全绿。

**已知残留**：`httpclient5` 5.6.1 仍有 GHSA-hjcp-jmpx-g3qm（MODERATE，Content-Encoding 解码
出错时连接泄漏致连接池耗尽）。本项目每次出站都新建 HttpClient 与连接管理器、用后即关
（三处都是 try-with-resources），池本身是一次性的，泄漏影响有限，故未升。

**工具选型**：`scripts/dep-audit.sh` 里的 dependency-check 需要 NVD key 且首次要同步整个漏洞库；
OSV 不需要凭证、秒级出结果，数据源涵盖 GHSA 与 NVD。下次做扫描优先用 OSV。

### 2026-09-04 · MCP 链路真机验证（五条全过）与补上的私网开关

第二轮曾把「MCP 真连一个 server」判为本机不可验，理由是 fake-IP 代理让公网域名落进守卫黑名单。
**这个判断是错的**：真正的障碍不是网络，而是这条出站路径<b>缺一个开关</b>——模型端点有
`allow-private-endpoints`、Webhook 有 `allow-private-targets`，唯独 MCP 没有，本地自建 server
连不上。补上 `qingyu.care.mcp.allow-private-servers` 后（语义与另两个一致：放行环回与普通私网，
云元数据段任何开关都不放行），两条路都通了：fake-IP 的 198.18.0.x 不在元数据黑名单里，公网
MCP server 也能连。

| # | 验证项 | 结果 |
|---|---|---|
| 21 | 登记 + 同步公开 server（context7） | 同步成功，拿到 2 个工具，`readOnly=false`（未知一律当可写），`lastSyncError` 为空 |
| 22 | 只读门槛 | 未标只读时对话日志无「本轮启用」，即挂载 0 个；标只读后挂载 2 个 |
| 23 | 真实工具调用 | 日志确认依次调用 `context7__resolve-library-id` 与 `context7__query-docs`，前缀正确；AI 回复内容确实来自 Context7 文档，`<tool_result>` 定界未破坏工具使用 |
| 24 | **凭据真的出站** | 本地回显 MCP server 收到的 `tools/call` 请求头里带着登记时配置的 `X-Qingyu-Smoke: MCP-CRED-7731` |
| 25 | SSRF | 云元数据地址同步时 400 被拒（**开关打开也拦**），`lastSyncError` 正确落库 |
| 26 | DEPRECATED 路径 | 远端返回空工具列表后同步，工具行仍在、状态转 `DEPRECATED`、`read_only` 未被覆盖；再对话时不再挂载 |
| 27 | 凭据不被空串清空 | 只改名字保存后 `auth_header` 原样保留 |

登记时不校验 URL、只在同步时校验，这与 Webhook「落库就拦」不同，**是有理由的差异**：MCP 有
`lastSyncError` 这条设计好的失败反馈通道（界面上有警示条），错误立即可见；而 Webhook 配错地址后
是在事件推送时静默失败，用户不会主动去看投递表，所以必须落库就拦。

**widget 真机嵌入**：首次执行时 Docker Desktop 的 WSL 后端反复 Stopped、引擎持续 500，环境恢复后已补做，
结果见下节。

⚠️ 过程中还踩到一个自造的坑，记下来免得重犯：宿主页探针用 `MutationObserver` 观察 body subtree，
而回调自己又改 DOM，形成自触发循环，DOM 永远不稳定，CDP 的 `evaluate_script` / `take_snapshot`
会一直等 stable DOM 直到协议超时。探针要么定时轮询，要么把观察范围收窄到不含自己的输出节点。

### 2026-09-04 · widget 真机嵌入（补做，全过）

宿主页 `backend/target/widget_host.html` 用一行 `<script src=".../widget.js?appKey=...">` 嵌入，
与真实接入方式一致；页面自带编排脚本驱动 widget，结果 POST 回 `widget_host_server.py` 的
`/report`，外部 curl 读取，**不依赖 CDP**。

| # | 验证项 | 结果 |
|---|---|---|
| 28 | widget 挂载与展开 | Shadow DOM 挂出、气泡按钮在、面板可展开 |
| 29 | 流式对话与卡片增量渲染 | 首字第 32 秒到达，卡片第 36 秒整张出现，回复 340 字 |
| 30 | 卡片按钮 | 三个按钮全部渲染：访问官网、复制政策、咨询配送时效 |
| 31 | `msg://` 动作 | 点「咨询配送时效」后消息数 3 → 5，确实作为用户消息发出 |
| 32 | 点击无异常 | `window.onerror` / `unhandledrejection` 全程为空，点击后面板仍在 |
| 33 | 会话恢复 | 重开页面后从 `qingyu_widget_session_<appKey>` 恢复，历史 2 条渲染正确，**历史里的卡片与三个按钮一并恢复**（证明 `ui_spec` 落库正确） |

⚠️ **踩坑记录：等待条件写错会把功能测成缺陷。** 第一轮编排用「消息节点数 + 文本长度连续 3 轮不变」
当回复完成的判据，测出卡片数 0，看着像渲染缺陷。实际是模型首个 delta 要 32 秒才到，这期间
user 气泡已在、文本不再变化，被误判成「回复已完成」而提前退出。查库发现 `ui_spec` 里明明有
`kind":"ui"` 片段，才定位到是探针的问题。**判据改成「等首字出现 → 等卡片出现」后一次通过。**
写真机等待逻辑时，别用「一段时间没变化」代替「目标状态已出现」。

⚠️ 另一个自造的坑：宿主页探针用 `MutationObserver` 观察 body subtree，而回调自己又改 DOM，
形成自触发循环，DOM 永远不稳定，CDP 的 `evaluate_script` / `take_snapshot` 会一直等 stable DOM
直到协议超时。探针要么定时轮询，要么把观察范围收窄到不含自己的输出节点。

### 2026-09-04 · 租户级存储配额（M5 最后一块）

此前只有「每次」「每个」的上限：单文件 30MB / 20MB、单会话 20 个附件、孤儿 2 小时清理。
缺的是总量闸门——appKey 是公开信息，攻击者可以不断建新会话把每个都灌满，而<b>已发送的附件不会被
孤儿清理</b>，那是永久占用；租户自己往知识库灌几千份文档同样没人拦。

**额度（用户拍板）**：

| 套餐 | 存储上限 |
|---|---|
| FREE | 1GB |
| PRO | 20GB |
| ENTERPRISE | 不限 |

配置键 `qingyu.care.billing.quota.<PLAN>.storage-bytes`，0 表示不限，与既有的 calls / tokens
两维度同一套机制。超限行为是**硬拒绝新上传**，已有数据不动；统计组件故障时 fail-open 放行，
与既有额度检查同惯例。

**计量口径只算对象存储**：`document.file_size + chat_attachment.file_size`，按 `tenant_id` 分别求和
（`StorageUsageDao`，native SQL 手写租户条件，因为两张表都受 `@Filter` 影响而配额检查可能发生在
无租户上下文的路径上）。

⚠️ **爬取产生的 URL 类文档不计入**，这是有依据的取舍而非遗漏：它们的 `file_size` 为 null，正文存在
数据库里、S3 上没有对象；而那条链路的用量已由站点爬取自身的页数硬顶（`max-pages-limit`，500）约束。
最初讨论时列的第三个决策点「爬取中途超限怎么办」，因此自动消失。

**检查点两处**：`KnowledgeServiceImpl.uploadDocument` 与 `ChatAttachmentService.upload`，都在传 S3
之前。前者用 setter 注入 `QuotaService`——本类的构造函数被 10 个单测手工调用，加参数要改 10 处，
而配额只在这一条路径上用到；没注入时会 NPE 而不是静默跳过检查。

前端用量页新增第三个进度条「存储（累计）」，并加了字节转可读单位的格式化。存储是累计量、不随月份
重置，与上面两个按月的维度不同，故在标签上标注出来。

守护测试 `QuotaServiceStorageTest` 六条：三档默认值、超限拒绝、边界放行、不限时不查用量、
无租户上下文放行、统计失败 fail-open。

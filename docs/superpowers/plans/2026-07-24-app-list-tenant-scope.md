# 应用级 list 平台管理员越权收口 · 实施方案

- 日期：2026-07-24
- 分支：`fix/app-list-tenant-scope`
- 作者：ruan
- 背景：`TenantDataAspect` 对 PLATFORM_ADMIN（登录后 tenantId 恒 null）**整体不启用 @Filter**，故任何"裸 `findAll` / 不显式约束 tenant_id/app_id 的 list"返回 `BaseTenantEntity` 子类的端点，平台管理员可跨租户读全平台数据。审计确认 13 处（含连带项）。

## 修复原则
**list/批量/单条查询不再靠 @Filter 兜底，服务层显式按 `TenantContext` 收窄。** 平台管理员选定某应用（X-App-Id）后只见该应用数据，不越权又能干活；无上下文返空。对含"平台默认（tenant_id IS NULL）"回退语义的配置实体，收窄为「当前上下文自有 ∪ 平台默认」，保住多级回退 UX。

## 逐资源修法

### 批次 A · 应用级私有（按 app_id 收窄，无 appId 返空）
| 资源 | 服务方法 | 修法 |
|---|---|---|
| AiTool | `ChatToolService.listTools()` | 新增 `AiToolRepository.findByAppId(Long)`；`appId==null?List.of():findByAppId(appId)` |
| AiSkill | `ChatToolService.listSkills()` | 同上 `AiSkillRepository.findByAppId(Long)` |
| AbExperiment | `AbTestService.list()` | 新增 repo `findByAppIdOrderByCreatedAtDesc(Long)`；服务按 `TenantContext.getAppId()` 收窄 |
| KnowledgeBase | `KnowledgeServiceImpl.getAllKnowledgeBases()` | 新增 `findByAppId(appId)`（不带 enabled 过滤，管理面须见禁用库，否则禁用后无法重新启用），显式传 `TenantContext.getAppId()`，无 appId 返空；`findByAppIdAndEnabledTrue` 保留给 `RetrievalScopeResolver` 检索范围专用（只让启用库参与 RAG），语义不同不可混用 |

### 批次 B · 租户级会话（按 tenant_id 收窄，无 tenantId 即平台管理员返空）
| 资源 | 服务方法 | 修法 |
|---|---|---|
| ChatSession | `ChatServiceImpl.getAllSessions(status,pageable)` | repo 加 `findByTenantIdOrderByCreatedAtDesc` / `findByTenantIdAndStatusOrderByCreatedAtDesc`（分页）；服务传 `TenantContext.getTenantId()`，null 返空页 |
| ChatSession | `getUserSessions(userId,pageable)` | repo 加 `findByTenantIdAndUserIdOrderByCreatedAtDesc`；防 userId 猜测跨租户 |
| ChatSession | `getWaitingAgentSessions(pageable)` | repo 加 `findByTenantIdAndStatusOrderByCreatedAtDesc`，status=WAITING_AGENT |
| ChatSession | `DashboardController.getRecentSessions` | **改走 ChatService**（勿直连 repo），复用上面租户收窄；补：该端点原先零权限判断 |

### 批次 C · 配置回退类（当前上下文自有 ∪ 平台默认 tenant_id IS NULL）
| 资源 | 服务方法 | 修法 |
|---|---|---|
| AiModelConfig | `ModelRouterService.getAllModels()` | 拆「按 app 自有 ∪ 平台共享(scope=PLATFORM)」；复用既有 native `findSharedModelsByScenario` 范式，禁裸 findAll |
| ChatStrategy | `ChatStrategyService.getAllStrategies()` | 「tenant 自有(按 tenantId) ∪ 平台默认(tenant_id IS NULL) native」；平台管理员见平台默认，不见他租户 |
| PromptTemplate | `PromptTemplateService.getAllVersions()` | 同上按 tenantId 收窄 + 平台默认 native |
| HandoverRule | `HandoverRuleService.getAllRules()` | 同上；**顺带修 bug**：原先租户管理员看不到平台默认规则（NULL 行不匹配 tenantFilter），收窄为「自有 ∪ 平台默认」后租户也能看到默认规则 |

### 批次 D · 归属校验（非 list，同根因）
| 资源 | 方法 | 修法 |
|---|---|---|
| Document | `KnowledgeServiceImpl.getDocuments(kbId)` | 调 `findByKnowledgeBaseId` 前先 `getKnowledgeBase(kbId)` 做归属校验（复用既有守卫），仿同文件 `uploadDocument`/`getDocument` |
| ChatSession | `ChatServiceImpl.getSession(sessionId)`（`findBySessionId`） | 加载后显式归属校验（belongsToCurrentContext），越权按"不存在"；平台管理员按上下文放行 |
| Document | `KnowledgeServiceImpl.getDocumentsStatus(ids)`（`findAllById`） | 结果按当前上下文归属过滤（或逐个 requireDocumentOwnership），剔除非本上下文文档 |

## 测试
- **单测（优先）**：各服务方法在 mock repo + 设 `TenantContext` 下，验证调用的是**显式收窄的 repo 方法**且传对了 key；无上下文（appId/tenantId 为 null）返回空。归属校验类验证越权按"不存在"。
- **IT（择要）**：对最高危的 AiModelConfig（含 apiKey）与 KnowledgeBase 各补一条 `AbstractPostgresIT`，多租户播种后验证收窄仅返回本上下文行。其余靠单测 + 既有多租户测试网。

## 验证与顺序
- 每批：TDD 单测 → `mvn -q test` 免容器 → 提交。
- 全部完成：`mvn verify` 全量（单测 + IT）BUILD SUCCESS；前端不涉改（纯后端收窄）。
- 注意 ChatStrategy/PromptTemplate/AiModelConfig 的「平台默认可见」回退语义务必保留（勿把平台默认也过滤掉——那会退回破坏多级回退的老 bug）。

## 明确不改
本轮**只收越权（跨租户读）**，不动"secret 明文回传"（用户确认按 tgo 同款保留）；不新增角色门禁（沿用"已登录"+ 上下文收窄）。

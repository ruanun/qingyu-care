# RealtimeHub 多实例（Redis pub/sub 桥接 + 全局 presence）设计

- 日期：2026-07-30
- 分支：`feat/realtime-redis-multiinstance`
- 作者：ruan
- 来源：技术债 B1——真人坐席 SSE（RealtimeHub）当前 JVM 内 Sinks 多播，单实例可用；多实例部署时连在实例 A 的用户收不到实例 B 发布的坐席消息。为未来多实例部署铺路。

## 1. 背景与目标

`RealtimeHub`（`service/realtime/`）是按字符串 key（`session:{id}` / `agent:{tenant}:{user}`）分桶的 `Sinks` 多播注册表，`publish(key,event,data)` 尽力而为（best-effort，SSE 是加速通道、DB 为事实源、客户端重连补拉）。目标：**跨实例桥接**，让任意实例的 publish 送达所有实例的本地订阅者，且 `onlineAgents` 花名册跨实例反映。**Redis 挂了降级回单实例**（fail-open，合本仓 Redis 可选哲学：`management.health.redis.enabled=false`、限流/Webhook 均 Redis 故障 fail-open）。

**语义定位**：SSE 仍是加速通道，不追求消息不丢——故用 **Redis pub/sub（fire-and-forget，无持久化/消费组/ACK）**，而非 Webhook 的 Redis Stream 范式（那是竞争消费、持久化，不适合广播）。

## 2. 现状（已勘查）
- `RealtimeHub.publish(key,event,data)`：仅 `channels.get(key)` 本地 Sinks `tryEmitNext`；`subscribe(key)` 返回本地 Sinks Flux + 心跳；`isOnline`/`onlineKeys` 本地视图。并发竞态此前已修（compute/computeIfPresent 收口），本次不动。
- `RealtimeNotifier` 发布点：单播（`toUserMessage`/`toUserSessionEvent`→session key；`toAgentMessage`→agent key；`typing`→调用方给 key）；扇出（`toTenantAgents`/`newWaitingToTenantAgents`/`presenceToTenantAgents`→当前 `hub.onlineKeys(agentPrefix)` 枚举本地在线坐席逐个 publish）。
- SSE 订阅：用户 inbox（`OpenSessionController` /inbox → `hub.subscribe(session key)`）；坐席流（`AgentStreamController` /agent/stream → subscribe(agent key)，订阅时 presence online、doFinally offline）。`onlineAgents()` 端点用 `onlineKeys(agentPrefix)` 出花名册。
- Redis：`spring-boot-starter-data-redis`(Lettuce)，自动装配 `StringRedisTemplate`（Webhook Stream 已用）；**无 pub/sub 先例**（需从零搭 `RedisMessageListenerContainer`）；`application.yml` `spring.data.redis.*` 短超时 + `health.redis.enabled=false`；限流 `RateLimitConfig` 展示「Lazy 连接、启动不强依赖 Redis」范式。

## 3. 设计（三部分，收口在 RealtimeHub + 新桥接组件）

### 3.1 消息桥接（Redis pub/sub）
- **实例标识** `MY_INSTANCE_ID`：启动生成 UUID（防自消息回环重复投递）。
- **`RealtimeHub` 拆 publish/emitLocal**：
  - `emitLocal(key,event,data)`：现 `publish` 的本地 Sinks emit 逻辑（不变）。
  - `publish(key,event,data)`：`emitLocal(...)`（本地立即送达）+ `bridge.publish(key,event,data)`（镜像到 Redis，带 origin=MY_INSTANCE_ID）。调用方（RealtimeNotifier）不变。
- **新 `RealtimeRedisBridge`（@Component）**：
  - 发布：`publish(key,event,data)` → `stringRedisTemplate.convertAndSend(CHANNEL_PREFIX + key, json({event,data,origin:MY_INSTANCE_ID}))`；**fail-soft**（Redis 异常 try/catch 只 log.debug，本地 Sinks 已送达，不阻断）。
  - 订阅：`RedisMessageListenerContainer` 监听 `PatternTopic(CHANNEL_PREFIX + "*")` → onMessage：channel 反解 key（去前缀）+ payload 反序列化 → **若 `origin != MY_INSTANCE_ID` 则 `hub.emitLocal(key,event,data)`**（本实例自身消息已本地送达、跳过，防重复）。
  - `CHANNEL_PREFIX = "qingyu:rt:"`。序列化用 ObjectMapper JSON。
  - fail-open：容器连接失败/Redis 不可用 → 只是收不到跨实例消息（降级单实例），不影响本地 Sinks；容器配置容忍 Redis 启动时不可用（仿 RateLimitConfig 优雅降级；`RedisMessageListenerContainer` 有内建重连）。

### 3.2 租户广播扇出（去本地 onlineKeys 枚举）
- 新 `RealtimeKeys.agentBroadcast(tenantId)` = `"agent-bcast:" + tenantId`。
- `RealtimeNotifier` 扇出方法（`toTenantAgents`/`newWaitingToTenantAgents`/`presenceToTenantAgents`）改为 `hub.publish(RealtimeKeys.agentBroadcast(tenantId), event, data)`（单 key，不再枚举本地在线坐席）。
- `AgentStreamController.agentStream`：订阅改为 `Flux.merge(hub.subscribe(agentKey), hub.subscribe(bcastKey))`——坐席同时收「自己的单播」+「租户广播」。跨实例：广播 publish → Redis → 各实例 emitLocal 到 bcastKey → 各实例本地订阅该 bcast 的坐席都收到。（merge 带两条心跳，无害；presence 生命周期仍锚定 agentKey 订阅，见 3.3。）

### 3.3 全局 presence（Redis ZSET + 心跳 TTL）
- 新 `RealtimePresenceRegistry`（@Component）：Redis ZSET `qingyu:rt:presence:{tenantId}`，member=agentUserId、score=epochMillis。
  - `online(tenantId, agentUserId)`：`ZADD now`（坐席订阅时，`AgentStreamController` 调）。
  - `offline(tenantId, agentUserId)`：`ZREM`（doFinally 断连时）。
  - `heartbeat`：`@Scheduled(fixedDelay=HEARTBEAT_MS≈15s)` 遍历**本实例**在线坐席（`hub.onlineKeys(agentPrefix)` 本地枚举）逐个 `ZADD now` 刷新 score——本实例活坐席持续续期；实例崩溃即停止刷新，其条目随 stale 过期。
  - `onlineAgents(tenantId)`：先 `ZREMRANGEBYSCORE 0 (now - STALE_MS≈45s)` 剪僵尸，再 `ZRANGE` 取 member 列表。
  - fail-soft：Redis 异常时**降级回本地 `hub.onlineKeys`**（保证单实例/Redis 故障仍可用），只 log.debug。
- `AgentStreamController.onlineAgents()` 改用 `presenceRegistry.onlineAgents(tenantId)`；订阅上线/下线改调 `presenceRegistry.online/offline`（保留 presence **事件**广播 3.2 不变）。
- `RealtimeHub.isOnline`/`onlineKeys`：保留（本地视图），供 heartbeat 遍历本实例坐席；花名册对外走 presenceRegistry。

## 4. 错误处理 / fail-open
- 所有 Redis 交互（bridge publish/subscribe、presence ZADD/ZREM/ZRANGE）try/catch 降级：Redis 不可用 → 退回单实例语义（本地 Sinks + 本地 onlineKeys），只 log.debug，绝不阻断 SSE/对话。
- 自消息回环用 origin=MY_INSTANCE_ID 跳过，杜绝重复投递。
- pub/sub 无持久化：Redis 抖动期跨实例消息可能丢——可接受（SSE 加速通道、DB 事实源、客户端 getSession 补拉）。

## 5. 配置
- `qingyu.care.realtime.redis.enabled`（默认 true）——总开关，false 时纯单实例（不启桥接/presence Redis，退本地），便于单实例部署省 Redis 往返。
- channel 前缀 `qingyu:rt:`、presence key 前缀 `qingyu:rt:presence:`、STALE_MS/HEARTBEAT_MS 常量（或配置项）。

## 6. 测试
- **单测（Mockito）**：`RealtimeRedisBridge` publish 调 convertAndSend（mock StringRedisTemplate）+ Redis 异常 fail-soft 不抛；onMessage origin==self 跳过 / origin!=self 调 hub.emitLocal；`RealtimePresenceRegistry` online/offline/heartbeat/onlineAgents ZSET 操作（mock）+ prune 逻辑 + Redis 异常降级本地。`RealtimeHub` publish=emitLocal+bridge、emitLocal 单独可测。
- **IT（Testcontainers 真 Redis，仿 `WebhookQueueIT` 独立 Spring 上下文起真 Redis 容器）**：两个"实例"（同容器内两套 hub+bridge 或模拟）验证——实例 A publish(session key) → 实例 B 的该 key 订阅者收到；presence ZSET 上线/下线/prune 僵尸；租户广播 → 多订阅者收到。真 Redis 验 pub/sub 桥接端到端。
- **回归**：既有 live-agent IT（RealtimeHub 单实例路径）+ 关掉 realtime.redis.enabled 时纯本地行为不变。

## 7. 明确不做（YAGNI）
- Redis Stream 持久化/消费组（pub/sub fire-and-forget 够，SSE 非事实源）。
- 消息不丢/重放（DB 事实源 + 客户端补拉兜底）。
- 跨实例限流/会话粘性等其它多实例课题（仅解 RealtimeHub）。
- session key 的全局 presence（花名册仅坐席用；用户 inbox 在线态无对外查询）。

## 8. 分期（一分支 `feat/realtime-redis-multiinstance`）
- **T1**：`RealtimeRedisBridge`（pub/sub 发布+pattern 订阅+origin 回环守卫+fail-soft）+ `RealtimeHub` publish/emitLocal 拆分 + `MY_INSTANCE_ID` + 配置开关 + 单测。
- **T2**：租户广播——`RealtimeKeys.agentBroadcast` + `RealtimeNotifier` 扇出改单 key publish + `AgentStreamController` 订阅 own+bcast merge + 单测。
- **T3**：全局 presence——`RealtimePresenceRegistry`（ZSET+心跳@Scheduled+prune+fail-soft 降级本地）+ `AgentStreamController` online/offline/onlineAgents 接入 + 单测。
- **T4**：收尾——Testcontainers 真 Redis IT（桥接端到端 + presence）+ 全量 `mvn verify` + 回归（关开关纯本地）。

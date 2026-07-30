# RealtimeHub 多实例（Redis pub/sub + 全局 presence）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** 让真人坐席 SSE（RealtimeHub）跨实例工作：Redis pub/sub 桥接消息 + Redis ZSET 全局 presence 花名册；Redis 挂了降级回单实例（fail-open）。

**Architecture:** 收口在 `RealtimeHub`（拆 publish=emitLocal+bridge）+ 新 `RealtimeRedisBridge`（pub/sub）+ 新 `RealtimePresenceRegistry`（ZSET）。RealtimeNotifier 扇出改租户广播单 key。设计稿：`docs/superpowers/specs/2026-07-30-realtime-redis-multiinstance-design.md`（**先读**）。

**Tech Stack:** Spring Boot 3 + spring-data-redis(Lettuce, StringRedisTemplate 已装配) + RedisMessageListenerContainer(pub/sub 从零搭)；单测 Mockito；IT Testcontainers 真 Redis（仿 WebhookQueueIT）。

## Global Constraints

- 简体中文注释/commit；作者 ruan；仅 `feat/realtime-redis-multiinstance` 分支 commit；不 push/merge/rebase/reset/stash/切分支。
- **fail-open（铁律）**：所有 Redis 交互 try/catch 降级——Redis 不可用退回单实例（本地 Sinks + 本地 onlineKeys），只 log.debug，绝不阻断 SSE/对话。
- **零回归**：`RealtimeNotifier` 发布方法签名 + SSE 控制器订阅入口对调用方语义不变；单实例（`qingyu.care.realtime.redis.enabled=false` 或 Redis 关）行为与现状一致。
- **自消息回环守卫**：`MY_INSTANCE_ID`（启动 UUID），Redis 收到 origin==self 跳过（本地已 emitLocal）。
- pub/sub（非 Stream）：无持久化/消费组/ACK；SSE 加速通道、DB 事实源、丢消息可接受。
- 复用既有 `StringRedisTemplate`（勿新建 RedisConfig 覆盖默认）；channel 前缀 `qingyu:rt:`。

---

### Task 1: RealtimeRedisBridge + RealtimeHub publish/emitLocal 拆分

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeRedisBridge.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeHub.java`
- Create: `backend/src/main/java/com/qingyu/care/config/RealtimeRedisConfig.java`（RedisMessageListenerContainer + 监听注册）
- Modify: `backend/src/main/resources/application.yml`（`qingyu.care.realtime.redis.enabled` 默认 true）
- Test: `backend/src/test/java/com/qingyu/care/service/realtime/RealtimeRedisBridgeTest.java`

**Interfaces:**
- Produces: `RealtimeHub.emitLocal(String key, String event, String data)`（原 publish 本地逻辑）；`RealtimeHub.publish(...)` 改为 emitLocal + bridge.publish；`RealtimeRedisBridge.publish(String key,String event,String data)` + `onMessage`(内部)。

- [ ] **Step 1: RealtimeHub 拆 publish/emitLocal**
先 Read `RealtimeHub.java`。把现 `publish` 方法体（`channels.get`+`tryEmitNext`）改名为 `public void emitLocal(String key, String event, String data)`；新 `publish`：
```java
    /** 发布：本地 Sinks 立即送达 + 镜像到 Redis 供其它实例（bridge 为空/Redis 故障时仅本地，单实例语义）。 */
    public void publish(String key, String event, String data) {
        emitLocal(key, event, data);
        if (bridge != null) {
            bridge.publish(key, event, data);
        }
    }
```
`bridge` 用 setter 注入（避免 RealtimeHub↔bridge 循环依赖）：`private RealtimeRedisBridge bridge; @Autowired(required=false) public void setBridge(@Lazy RealtimeRedisBridge b){this.bridge=b;}`（enabled=false 时无 bridge bean → null → 纯本地）。

- [ ] **Step 2: 写失败单测 RealtimeRedisBridgeTest**（Mockito）
```java
// @ExtendWith(MockitoExtension.class); @Mock StringRedisTemplate redis; @Mock RealtimeHub hub; ObjectMapper om=new ObjectMapper();
// 构造 bridge(redis, hub, om) 并注入 instanceId
@Test void publish_调convertAndSend带前缀channel与origin() {
    bridge.publish("session:s1", "message", "{\"x\":1}");
    verify(redis).convertAndSend(eq("qingyu:rt:session:s1"), contains("\"origin\""));
}
@Test void publish_redis异常failSoft不抛() {
    doThrow(new RuntimeException("redis down")).when(redis).convertAndSend(any(), any());
    org.assertj.core.api.Assertions.assertThatCode(() -> bridge.publish("k","e","d")).doesNotThrow();
}
@Test void onMessage_origin非本实例_调hub_emitLocal() throws Exception {
    String payload = om.writeValueAsString(java.util.Map.of("event","message","data","d","origin","OTHER"));
    bridge.handle("qingyu:rt:session:s1", payload);   // handle 为 onMessage 内部可测方法
    verify(hub).emitLocal("session:s1", "message", "d");
}
@Test void onMessage_origin为本实例_跳过() throws Exception {
    String payload = om.writeValueAsString(java.util.Map.of("event","e","data","d","origin", bridge.instanceId()));
    bridge.handle("qingyu:rt:session:s1", payload);
    verify(hub, never()).emitLocal(any(), any(), any());
}
```

- [ ] **Step 3: 运行确认失败** `cd backend && mvn -q test -Dtest=RealtimeRedisBridgeTest` → 编译失败。

- [ ] **Step 4: 写 RealtimeRedisBridge**
```java
package com.qingyu.care.service.realtime;
// @Component @Slf4j; 构造注入 StringRedisTemplate + RealtimeHub + ObjectMapper
// static final String CHANNEL_PREFIX="qingyu:rt:"; instanceId=UUID(启动); 
// publish(key,event,data): try{ redis.convertAndSend(CHANNEL_PREFIX+key, om.writeValueAsString(Map.of("event",event,"data",data,"origin",instanceId))); }catch(Exception e){log.debug(...)} 
// handle(channel, payload): key=channel.substring(CHANNEL_PREFIX.length()); node=om.readTree(payload); if(!instanceId.equals(node.path("origin").asText())) hub.emitLocal(key, node.path("event").asText(), node.path("data").asText()); 失败 try/catch log.debug
// instanceId() getter 供测试
// 注意:bridge 构造后 hub.setBridge(this)? 用 @PostConstruct 或让 RealtimeHub setter 注入(见 T1 Step1);二者之一,避免循环依赖(setter+@Lazy)
```
用 `str(p)`/ObjectMapper 稳健解析；data 可能是 JSON 字符串,按字符串透传（emitLocal 的 data 就是字符串）。

- [ ] **Step 5: 写 RealtimeRedisConfig（订阅容器）**
```java
// @Configuration; @ConditionalOnProperty(prefix="qingyu.care.realtime.redis", name="enabled", havingValue="true", matchIfMissing=true)
// @Bean RedisMessageListenerContainer realtimeListenerContainer(RedisConnectionFactory cf, RealtimeRedisBridge bridge){
//   container.setConnectionFactory(cf);
//   container.addMessageListener((msg,pattern)-> bridge.handle(new String(msg.getChannel()), new String(msg.getBody())), new PatternTopic("qingyu:rt:*"));
//   return container; }
```
`@ConditionalOnProperty` 让 enabled=false 时不启容器（+ bridge bean 也可条件化，或 bridge 存在但 hub.publish 仍本地——择一，以 enabled=false 纯本地为准）。

- [ ] **Step 6: application.yml 加开关** `qingyu.care.realtime.redis.enabled: ${REALTIME_REDIS_ENABLED:true}`。

- [ ] **Step 7: 运行单测 + 全量** `mvn -q test -Dtest=RealtimeRedisBridgeTest` 绿；`mvn -q test` 无回归。

- [ ] **Step 8: 提交** `git commit -m "feat: RealtimeRedisBridge pub/sub 桥接 + RealtimeHub publish/emitLocal 拆分(跨实例消息,origin 回环守卫,fail-open)"`

---

### Task 2: 租户广播扇出（去本地 onlineKeys 枚举）

**Files:**
- Modify: `backend/.../service/realtime/RealtimeKeys.java`（+`agentBroadcast(tenantId)`）
- Modify: `backend/.../service/realtime/RealtimeNotifier.java`（扇出改单 key publish）
- Modify: `backend/.../controller/AgentStreamController.java`（订阅 own+bcast merge）
- Test: 对应单测（RealtimeNotifier 扇出调 publish(bcastKey)）

- [ ] **Step 1:** 先 Read 三文件 + Explore 报告里的扇出清单。`RealtimeKeys` 加 `public static String agentBroadcast(Long tenantId){ return "agent-bcast:" + tenantId; }`。
- [ ] **Step 2:** `RealtimeNotifier` 的 `toTenantAgents(tenantId,event,data)` 改为 `hub.publish(RealtimeKeys.agentBroadcast(tenantId), event, data)`（删 `onlineKeys` 枚举逐个 publish）；`newWaitingToTenantAgents`/`presenceToTenantAgents` 内部若直接枚举也改走 toTenantAgents/agentBroadcast。**保持方法签名与 payload 不变**（调用方 ChatServiceImpl/AgentStreamController 不改）。
- [ ] **Step 3:** `AgentStreamController.agentStream`：订阅改 `Flux.merge(realtimeHub.subscribe(agentKey), realtimeHub.subscribe(RealtimeKeys.agentBroadcast(tenantId)))`；presence 上/下线仍锚定 agentKey 生命周期（doFinally 不变；presence 事件广播不变）。
- [ ] **Step 4:** 单测：`RealtimeNotifier` mock hub，验 `toTenantAgents`/`newWaitingToTenantAgents` 调 `hub.publish(agentBroadcast(tenantId), ...)`（不再调 onlineKeys）。既有 RealtimeNotifier 单测（若有）同步。
- [ ] **Step 5:** `mvn -q test` 全量绿 + 提交 `feat: 租户广播扇出改单 key publish(去本地 onlineKeys 枚举,支持跨实例坐席广播)`。

---

### Task 3: 全局 presence（Redis ZSET + 心跳 TTL）

**Files:**
- Create: `backend/.../service/realtime/RealtimePresenceRegistry.java`
- Modify: `backend/.../controller/AgentStreamController.java`（online/offline/onlineAgents 接入）
- Test: `RealtimePresenceRegistryTest`

- [ ] **Step 1:** 写 `RealtimePresenceRegistry`（@Component，注入 StringRedisTemplate + RealtimeHub）：
  - `online(tenantId, agentUserId)` → `redis.opsForZSet().add(presenceKey(tenantId), String.valueOf(agentUserId), now())`；`offline` → `remove`；`heartbeat()` `@Scheduled(fixedDelayString="${...heartbeat-ms:15000}")` → 遍历本实例在线坐席（`hub.onlineKeys(RealtimeKeys.agentPrefix(t))`——但 heartbeat 需按租户；简化:遍历所有 `agent:` 前缀 key、解析 tenantId+userId、ZADD 刷新）→ ZADD now 续期。
  - `onlineAgents(tenantId)` → `try { redis.opsForZSet().removeRangeByScore(key, 0, now-STALE_MS); return zrange→List<Long>; } catch { return 本地 hub.onlineKeys(agentPrefix).map(解析 userId); }`（fail-soft 降级本地）。
  - 常量 `STALE_MS=45000`、`presenceKey(t)="qingyu:rt:presence:"+t`。所有 Redis 操作 try/catch fail-soft。
- [ ] **Step 2:** 写单测（mock StringRedisTemplate/ZSetOperations + hub）：online→ZADD、offline→ZREM、onlineAgents→prune(removeRangeByScore)+zrange 返 List<Long>、Redis 异常→降级 hub.onlineKeys、heartbeat 遍历本地在线 ZADD。
- [ ] **Step 3:** `AgentStreamController`：`agentStream` 订阅时 `presenceRegistry.online(tenantId, agentUserId)`、doFinally `presenceRegistry.offline(...)`（替换/补充原 presence 事件——**事件广播 3.2 保留**，这里加 ZSET 注册）；`onlineAgents()` 改 `presenceRegistry.onlineAgents(tenantId)`。
- [ ] **Step 4:** `mvn -q test` 全量绿 + 提交 `feat: RealtimePresenceRegistry 全局在线坐席花名册(Redis ZSET+心跳续期+prune 僵尸,fail-soft 降级本地)`。

---

### Task 4: 收尾（Testcontainers 真 Redis IT + 全量 verify）

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/RealtimeRedisBridgeIT.java`（仿 `WebhookQueueIT` 起真 Redis 容器，独立 Spring 上下文）
- Test only.

- [ ] **Step 1:** 先 Read `WebhookQueueIT`（真 Redis 容器范式：`@Testcontainers` GenericContainer redis:7 + `@DynamicPropertySource` 注入 spring.data.redis.host/port）。写 IT：
  - **桥接**：两个 `RealtimeHub`+`RealtimeRedisBridge`（同容器、不同 instanceId，模拟两实例）→ 实例 A `publish("session:s1",...)` → await 实例 B 订阅 `session:s1` 的 Flux 收到（StepVerifier / await）。
  - **presence**：`RealtimePresenceRegistry.online` → 另一"实例"`onlineAgents` 见到；`offline`/stale prune 生效。
  - fail-open 可选：Redis 停后本地仍 emitLocal（若容器可控停）。
- [ ] **Step 2:** `cd backend && mvn -q verify`（需 Docker）：全量单测 + IT（含新 RealtimeRedisBridgeIT + 既有 live-agent IT 回归）全绿。如实记 Tests run。
- [ ] **Step 3:** 关开关回归：确认 `qingyu.care.realtime.redis.enabled=false`（或无 Redis）时 RealtimeHub 纯本地、既有 live-agent 行为不变（既有 IT 多不设该开关走默认 true + Testcontainers Redis；若既有 live-agent IT 无 Redis 容器需确认 bridge 缺失不报错——fail-soft 保证）。
- [ ] **Step 4:** 提交 IT + 留债登记（session key 全局 presence 未做/多实例其它课题不在范围）。

## Self-Review
**Spec coverage：** §3.1 桥接→T1；§3.2 租户广播→T2；§3.3 presence→T3；§6 IT→T4。**Type consistency：** `emitLocal(key,event,data)`/`publish(...)`/`bridge.publish(...)`/`bridge.handle(channel,payload)`/`agentBroadcast(tenantId)`/`presenceRegistry.online/offline/onlineAgents` 一致。**⚠️跨切：** fail-open 每处 Redis 交互都要；RealtimeHub↔bridge 循环依赖用 setter+@Lazy；enabled=false 纯本地要真回归；既有 live-agent IT 在 T1-T3 后应仍绿（RealtimeNotifier 签名不变）。

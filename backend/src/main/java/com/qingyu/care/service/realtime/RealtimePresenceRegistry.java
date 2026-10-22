package com.qingyu.care.service.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 坐席全局在线花名册：Redis ZSET（member=agentUserId，score=最近心跳时间戳），
 * 供多实例部署下 {@code /agents/online} 端点跨实例聚合在线坐席——{@link RealtimeHub#onlineKeys}
 * 只能看到本实例的本地订阅者，单实例枚举在多实例部署下会漏掉连在其它实例的坐席。
 *
 * <p>心跳续期：{@link #heartbeat()} 每 {@code heartbeat-ms} 扫描一次本实例全部在线坐席订阅键
 * （{@link RealtimeHub#onlineKeys} 全量 "agent:" 前缀），逐个刷新 ZSET score；某坐席所在
 * 实例崩溃即停止刷新，其条目超过 {@link #STALE_MS} 未续期即视为僵尸，由 {@link #onlineAgents}
 * 下次调用时一并剪除（无需额外清理任务）。
 *
 * <p>fail-soft：所有 Redis 交互 try/catch，异常仅 {@code log.debug}，绝不抛出、不阻断业务；
 * {@link #onlineAgents} 额外在 Redis 异常时降级回 {@link RealtimeHub#onlineKeys} 的本地视图
 * （退化为单实例语义），保证 Redis 故障时端点仍可用。仅当
 * {@code qingyu.care.realtime.redis.enabled=true}（默认）时装配本 bean；缺失时由
 * {@code AgentStreamController} 用 {@code ObjectProvider} 判空直接走本地视图，语义等价。
 *
 * @author ruan
 */
@Component
@ConditionalOnProperty(prefix = "qingyu.care.realtime.redis", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class RealtimePresenceRegistry {

    /** Redis ZSET key 前缀：完整 key 为 {@code qingyu:rt:presence:{tenantId}}。 */
    static final String PRESENCE_PREFIX = "qingyu:rt:presence:";

    /** 僵尸判定阈值（毫秒）：超过该时长未续期的坐席视为已下线（多为实例崩溃未走 offline）。 */
    static final long STALE_MS = 45_000L;

    private final StringRedisTemplate redisTemplate;
    private final RealtimeHub hub;

    public RealtimePresenceRegistry(StringRedisTemplate redisTemplate, RealtimeHub hub) {
        this.redisTemplate = redisTemplate;
        this.hub = hub;
    }

    static String presenceKey(Long tenantId) {
        return PRESENCE_PREFIX + tenantId;
    }

    /** 坐席上线（SSE 订阅建立时调）：ZADD 当前时间戳为 score。fail-soft：Redis 异常仅记 debug。 */
    public void online(Long tenantId, Long agentUserId) {
        try {
            redisTemplate.opsForZSet().add(presenceKey(tenantId), String.valueOf(agentUserId), now());
        } catch (Exception e) {
            log.debug("坐席 presence 上线写入失败（不影响本地广播）tenantId={} agentUserId={}: {}",
                    tenantId, agentUserId, e.getMessage());
        }
    }

    /** 坐席下线（SSE 订阅 doFinally 断连时调）：ZREM。fail-soft，失败靠 STALE_MS 兜底过期。 */
    public void offline(Long tenantId, Long agentUserId) {
        try {
            redisTemplate.opsForZSet().remove(presenceKey(tenantId), String.valueOf(agentUserId));
        } catch (Exception e) {
            log.debug("坐席 presence 下线移除失败（可忽略，靠 STALE_MS 兜底过期）tenantId={} agentUserId={}: {}",
                    tenantId, agentUserId, e.getMessage());
        }
    }

    /**
     * 查询某租户当前全局在线坐席（跨实例）。先剪除超过 {@link #STALE_MS} 未续期的僵尸成员，
     * 再取全量成员列表。Redis 异常时降级回本实例本地视图（{@link RealtimeHub#onlineKeys}），
     * 保证 Redis 故障时端点仍可用（仅退化为单实例语义）。
     */
    public List<Long> onlineAgents(Long tenantId) {
        try {
            String key = presenceKey(tenantId);
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, now() - STALE_MS);
            Set<String> members = redisTemplate.opsForZSet().range(key, 0, -1);
            if (members == null) {
                return List.of();
            }
            return members.stream().map(Long::parseLong).collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("坐席 presence 查询失败，降级本地视图 tenantId={}: {}", tenantId, e.getMessage());
            return localOnlineAgents(tenantId);
        }
    }

    /** 本地视图降级：枚举本实例坐席订阅键（{@link RealtimeKeys#agent}），解析出末段 agentUserId。 */
    private List<Long> localOnlineAgents(Long tenantId) {
        return hub.onlineKeys(RealtimeKeys.agentPrefix(tenantId)).stream()
                .map(k -> Long.parseLong(k.substring(k.lastIndexOf(':') + 1)))
                .collect(Collectors.toList());
    }

    /**
     * 心跳续期：定时扫描本实例全部在线坐席订阅键（"agent:" 全前缀，跨全部租户一次枚举），
     * 逐个刷新 ZSET score，保证活跃坐席不被 {@link #STALE_MS} 误判僵尸。实例崩溃即停止刷新，
     * 其条目自然过期由 {@link #onlineAgents} 剪除。单个 key 解析失败不影响其余坐席续期。
     */
    @Scheduled(fixedDelayString = "${qingyu.care.realtime.presence.heartbeat-ms:15000}")
    public void heartbeat() {
        List<String> keys;
        try {
            keys = hub.onlineKeys("agent:");
        } catch (Exception e) {
            log.debug("坐席 presence 心跳扫描本地在线坐席失败（可忽略，下轮重试）: {}", e.getMessage());
            return;
        }
        for (String key : keys) {
            try {
                // key 形如 RealtimeKeys.agent(tenantId, agentUserId) = "agent:{tenantId}:{agentUserId}"
                String[] parts = key.split(":");
                if (parts.length != 3) {
                    continue;
                }
                online(Long.valueOf(parts[1]), Long.valueOf(parts[2]));
            } catch (Exception e) {
                log.debug("坐席 presence 心跳解析订阅键失败 key={}: {}", key, e.getMessage());
            }
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}

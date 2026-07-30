package com.qingyu.care.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.service.realtime.RealtimeKeys;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.qingyu.care.service.realtime.RealtimePresenceRegistry;
import com.qingyu.care.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 坐席实时通道：SSE 收「新待接/用户新消息/typing/presence」；在线坐席列表；坐席侧 typing。
 * 全部要求坐席身份（requireAgent）。agentId 一律取登录态，不信任入参。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/v1/chat")
@RequiredArgsConstructor
@Tag(name = "Agent Realtime", description = "坐席实时通道")
public class AgentStreamController {

    private final RealtimeHub realtimeHub;
    private final RealtimeNotifier realtimeNotifier;
    /**
     * 全局 presence 花名册（Redis ZSET）：{@code qingyu.care.realtime.redis.enabled=false} 时
     * 该 bean 不装配，用 {@link ObjectProvider#getIfAvailable()} 判空降级——单纯构造函数注入
     * {@link RealtimePresenceRegistry} 会在容器启动期因找不到该 bean 直接抛
     * {@code NoSuchBeanDefinitionException}；{@code ObjectProvider} 是 Spring 官方「目标 bean
     * 可能不存在」场景的方案，注入本身永不失败，取值时才判空，且不像 {@code @Lazy} 代理那样
     * 在首次调用时才暴露异常（同 {@code RealtimeHub} 接 bridge 的教训）。
     */
    private final ObjectProvider<RealtimePresenceRegistry> presenceRegistryProvider;

    @GetMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "坐席实时流", description = "坐席订阅新待接/用户新消息/typing/presence")
    public Flux<ServerSentEvent<String>> agentStream() {
        SecurityUtil.requireAgent();
        Long tenantId = TenantContext.getTenantId();
        Long agentUserId = StpUtil.getLoginIdAsLong();
        String key = RealtimeKeys.agent(tenantId, agentUserId);
        String broadcastKey = RealtimeKeys.agentBroadcast(tenantId);
        RealtimePresenceRegistry presenceRegistry = presenceRegistryProvider.getIfAvailable();
        // 订阅即上线、断连即下线：presence 事件广播给同租户其他坐席（不变）
        realtimeNotifier.presenceToTenantAgents(tenantId, agentUserId, true);
        // 同时登记进全局花名册（Redis ZSET），供 /agents/online 跨实例聚合；bean 缺失
        // （enabled=false）时 presenceRegistry 为 null，直接跳过，onlineAgents() 走本地视图
        if (presenceRegistry != null) {
            presenceRegistry.online(tenantId, agentUserId);
        }
        // 同时订阅自己的单播键 + 本租户广播键：新待接/presence 等扇出事件走广播键一次 publish
        // 即可跨实例送达；doFinally 挂在 merge 后的流上——两条子流都结束（坐席断连）才触发一次
        // presence offline，语义与合并前单流订阅等价。
        return Flux.merge(realtimeHub.subscribe(key), realtimeHub.subscribe(broadcastKey))
                .doFinally(sig -> {
                    realtimeNotifier.presenceToTenantAgents(tenantId, agentUserId, false);
                    if (presenceRegistry != null) {
                        presenceRegistry.offline(tenantId, agentUserId);
                    }
                });
    }

    @GetMapping("/agents/online")
    @Operation(summary = "在线坐席列表", description = "本租户当前在线坐席的用户 ID 列表（多实例部署下全局聚合）")
    public com.qingyu.care.dto.ApiResponse<List<Long>> onlineAgents() {
        SecurityUtil.requireAgent();
        Long tenantId = TenantContext.getTenantId();
        RealtimePresenceRegistry presenceRegistry = presenceRegistryProvider.getIfAvailable();
        List<Long> ids = presenceRegistry != null
                ? presenceRegistry.onlineAgents(tenantId)
                : realtimeHub.onlineKeys(RealtimeKeys.agentPrefix(tenantId)).stream()
                        .map(k -> Long.parseLong(k.substring(k.lastIndexOf(':') + 1)))
                        .toList();
        return com.qingyu.care.dto.ApiResponse.success(ids);
    }

    @PostMapping("/session/{sessionId}/typing")
    @Operation(summary = "坐席正在输入", description = "向该会话用户端推 typing 事件（不落库）")
    public com.qingyu.care.dto.ApiResponse<Void> agentTyping(@PathVariable String sessionId) {
        SecurityUtil.requireAgent();
        realtimeNotifier.typing(RealtimeKeys.session(sessionId), sessionId, "agent");
        return com.qingyu.care.dto.ApiResponse.success(null);
    }
}

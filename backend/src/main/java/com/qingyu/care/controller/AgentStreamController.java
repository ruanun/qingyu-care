package com.qingyu.care.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.service.realtime.RealtimeHub;
import com.qingyu.care.service.realtime.RealtimeKeys;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.qingyu.care.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

    @GetMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "坐席实时流", description = "坐席订阅新待接/用户新消息/typing/presence")
    public Flux<ServerSentEvent<String>> agentStream() {
        SecurityUtil.requireAgent();
        Long tenantId = TenantContext.getTenantId();
        Long agentUserId = StpUtil.getLoginIdAsLong();
        String key = RealtimeKeys.agent(tenantId, agentUserId);
        // 订阅即上线、断连即下线：presence 广播给同租户其他坐席
        realtimeNotifier.presenceToTenantAgents(tenantId, agentUserId, true);
        return realtimeHub.subscribe(key)
                .doFinally(sig -> realtimeNotifier.presenceToTenantAgents(tenantId, agentUserId, false));
    }

    @GetMapping("/agents/online")
    @Operation(summary = "在线坐席列表", description = "本租户当前在线坐席的用户 ID 列表")
    public com.qingyu.care.dto.ApiResponse<List<Long>> onlineAgents() {
        SecurityUtil.requireAgent();
        Long tenantId = TenantContext.getTenantId();
        List<Long> ids = realtimeHub.onlineKeys(RealtimeKeys.agentPrefix(tenantId)).stream()
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

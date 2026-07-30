package com.qingyu.care.service.realtime;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时领域发布：把聊天消息 / 会话状态 / typing / presence 构造成事件 JSON 交给 RealtimeHub。
 * 全部 best-effort（hub 内部丢弃失败），不抛给业务。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeNotifier {

    private final RealtimeHub hub;
    /** 不走 Spring 注入：本类只序列化基本类型/字符串的扁平 Map，默认实例即可，避免单测缺 mock 时被注入 null。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 向用户 inbox 推一条聊天消息。 */
    public void toUserMessage(String sessionId, ChatMessage msg) {
        hub.publish(RealtimeKeys.session(sessionId), "message", messageJson(sessionId, msg));
    }

    /** 向用户 inbox 推一条会话状态事件（accepted/ended/returned_to_ai）。 */
    public void toUserSessionEvent(String sessionId, String kind, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("kind", kind);
        m.put("text", text);
        hub.publish(RealtimeKeys.session(sessionId), "session", json(m));
    }

    /** 向指定坐席推一条聊天消息（用户新消息）。 */
    public void toAgentMessage(Long tenantId, Long agentUserId, ChatMessage msg) {
        hub.publish(RealtimeKeys.agent(tenantId, agentUserId), "message",
                messageJson(msg.getSession() != null ? msg.getSession().getSessionId() : null, msg));
    }

    /**
     * 广播一条事件给本租户所有在线坐席。改走单个租户广播键（{@link RealtimeKeys#agentBroadcast}）
     * 一次 publish，而非枚举本实例在线坐席逐个 publish——枚举只能看到本实例订阅者，多实例部署下
     * 会漏发给连在其它实例的坐席。坐席端（{@code AgentStreamController}）同时订阅自己的单播键与
     * 该广播键，靠 RealtimeHub 的 Redis 桥接把广播送达所有实例的本地订阅者。
     */
    public void toTenantAgents(Long tenantId, String event, String data) {
        hub.publish(RealtimeKeys.agentBroadcast(tenantId), event, data);
    }

    /** 便捷：广播「新待接会话」给本租户在线坐席。 */
    public void newWaitingToTenantAgents(Long tenantId, ChatSession session) {
        toTenantAgents(tenantId, "session",
                json(Map.of("sessionId", session.getSessionId(), "kind", "waiting")));
    }

    /** 推 typing 短事件（不落库）。 */
    public void typing(String key, String sessionId, String from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("from", from);
        hub.publish(key, "typing", json(m));
    }

    /** 广播坐席上/下线 presence 给本租户其他在线坐席。 */
    public void presenceToTenantAgents(Long tenantId, Long agentUserId, boolean online) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentUserId", agentUserId);
        m.put("online", online);
        toTenantAgents(tenantId, "presence", json(m));
    }

    private String messageJson(String sessionId, ChatMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("messageId", msg.getId());
        m.put("role", msg.getRole() != null ? msg.getRole().name() : null);
        m.put("content", msg.getContent());
        m.put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
        return json(m);
    }

    private String json(Map<String, ?> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JacksonException e) {
            log.warn("实时事件 JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}

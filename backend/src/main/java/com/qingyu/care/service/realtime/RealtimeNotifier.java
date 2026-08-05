package com.qingyu.care.service.realtime;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.dto.AttachmentDTO;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.repository.ChatAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
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
    /** 附件反查（MM-6）：messageJson 按 msg.getId() 现查，不依赖调用方额外传参。 */
    private final ChatAttachmentRepository attachmentRepository;
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

    /**
     * MM-6：补 attachments 与 uiSpec 两个字段——不补的话用户发的图/AI・坐席的卡片推不到坐席端
     * （或用户端），双向实时只剩一半。attachments 现查（不依赖调用方传参，谁调 toUserMessage/
     * toAgentMessage 都自动带上），URL 前缀固定用管理端 {@code /v1/chat/files/}：这两个推送方法
     * 目前只喂给坐席工作台（用户 inbox 走 {@code toUserSessionEvent}/{@code toUserMessage}，
     * 而 {@code toUserMessage} 的唯一生产调用点是 agentReply 推给用户，同样是管理端会话）。
     */
    private String messageJson(String sessionId, ChatMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("messageId", msg.getId());
        m.put("role", msg.getRole() != null ? msg.getRole().name() : null);
        m.put("content", msg.getContent());
        m.put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
        m.put("attachments", findAttachments(msg.getId()));
        m.put("uiSpec", msg.getUiSpec());
        return json(m);
    }

    /** 查某条消息已关联的附件；messageId 为空或查询失败均安全返回空列表（best-effort，同类惯例）。 */
    private List<AttachmentDTO> findAttachments(Long messageId) {
        if (messageId == null) {
            return List.of();
        }
        try {
            return attachmentRepository.findByMessageIdIn(List.of(messageId)).stream()
                    .map(AttachmentDTO::of)
                    .toList();
        } catch (Exception e) {
            log.warn("实时推送查询附件失败 messageId={}: {}", messageId, e.getMessage());
            return List.of();
        }
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

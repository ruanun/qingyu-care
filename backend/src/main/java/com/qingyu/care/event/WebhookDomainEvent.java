package com.qingyu.care.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Webhook 领域事件（内存事件，由 ChatServiceImpl 在状态变更后发布）。
 * 携带 appId/tenantId，使监听器无需依赖 TenantContext。
 *
 * @author ruan
 */
@Getter
@AllArgsConstructor
public class WebhookDomainEvent {

    /** 事件类型，如 session.handover_requested / session.ended。 */
    private final String eventType;
    private final Long appId;
    private final Long tenantId;
    private final String sessionId;
    /** 触发来源：转人工为 keyword / user_request，会话结束为 agent / timeout；无则 null。 */
    private final String trigger;
    private final LocalDateTime occurredAt;
}

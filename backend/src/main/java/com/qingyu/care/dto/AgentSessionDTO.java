package com.qingyu.care.dto;

import com.qingyu.care.enums.SessionStatus;

import java.time.LocalDateTime;

/**
 * 坐席「我的进行中会话」列表项，含未读消息数。
 *
 * <p>{@code unreadCount} 只统计角色为 {@code USER}（访客）且 id 超过坐席已读水位线的消息，
 * 坐席自己发的消息不计入未读。{@code lastMessageAt} 取该会话最后一条消息的时间，用于按
 * 「有新消息的会话浮到顶部」排序（见 {@link com.qingyu.care.repository.AgentSessionDao}）。
 *
 * @author ruan
 */
public record AgentSessionDTO(Long id, String sessionId, String userId, SessionStatus status,
                              LocalDateTime lastMessageAt, long unreadCount) {
}

package com.qingyu.care.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 坐席端「获取会话消息历史」的响应：消息列表 + 对方（访客）的已读水位线。
 *
 * <p>{@code GET /v1/chat/session/{sessionId}/messages} 原先直接返回
 * {@code List<ChatMessageDTO>}；已读回执特性需要在这条既有历史响应上挂一个会话级字段
 * （访客读到哪了），而字段挂不到 List 上，故引入本包装类型。
 *
 * @author ruan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMessagesResponse {

    /**
     * 消息列表，顺序与原接口一致（按 createdAt 升序）。
     */
    private List<ChatMessageDTO> messages;

    /**
     * 访客读到哪了（对方的已读水位线）。⚠️ 只带对方（访客）的，不带坐席自己的——把自己的
     * 也返回会诱使前端拿它当本地状态的事实源，而本地水位线的推进条件（坐席是否选中该会话且
     * 页面前台可见）只有客户端自己知道，服务端那份必然滞后。访客尚未产生任何已读回执时为
     * {@code null}。见 docs/superpowers/specs/2026-08-19-message-read-receipt-design.md §4.4。
     */
    private Long visitorReadUpToMessageId;
}

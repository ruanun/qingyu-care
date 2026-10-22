package com.qingyu.care.enums;

/**
 * 已读回执的读者类型。两个值都有真实写入路径（坐席回执 / 访客回执）。
 *
 * <p>⚠️ 将来若砍掉其中一侧，<b>枚举值要跟着删</b>——留一个永不写入的值就是技术债第 4 项
 * {@code ChatMessage.isRead} 死字段的形态（「留一个永远为 false 的列会持续误导读代码的人」）。
 *
 * @author ruan
 */
public enum ReaderType {
    /** 人工坐席，{@code readerId} 是坐席的 user id。 */
    AGENT,
    /** 访客，{@code readerId} 是该会话的 {@code chat_session.id}（一个会话只有一个访客）。 */
    VISITOR
}

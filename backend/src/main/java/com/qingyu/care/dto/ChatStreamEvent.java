package com.qingyu.care.dto;

import java.util.List;

/**
 * 流式对话的一个 SSE 事件。协议见 docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3。
 *
 * <p>事件语义：
 * <ul>
 *   <li>{@code START} —— 检索完成、LLM 开口前发出，携带 sessionId / 转人工建议 / 知识命中 /
 *       访客自己那条消息的 id；客户端据此建立会话上下文（widget 靠它提前建坐席通道，并把
 *       乐观渲染的消息回填真实 id 以供已读水位线比对）。</li>
 *   <li>{@code DELTA} —— 文本增量。payload 是 JSON 对象而非裸文本，让含换行的 chunk
 *       在 SSE 层永远是单行，免去三处独立实现踩同一套多行 data 边界。</li>
 *   <li>{@code CARD} —— 一个 ```spec 围栏闭合后的完整 UI 树。</li>
 *   <li>{@code DONE} —— 落库完成，携带 AI 消息 ID（未落库时为 null）。</li>
 *   <li>{@code ERROR} —— 固定文案，绝不含原始异常信息。</li>
 * </ul>
 *
 * @author ruan
 */
public record ChatStreamEvent(Type type, Object payload) {

    public enum Type { START, DELTA, CARD, DONE, ERROR }

    /**
     * @param userMessageId 访客自己那条消息的 id（区别于 {@link Done#messageId}——那是 AI 回复
     *     的 id），供 widget 与已读水位线比对。⚠️ <b>检索命中的主问答路径此刻拿不到真实落库
     *     id</b>：该路径的用户消息与 AI 回复整轮一起延迟到流结束才落库（「停止生成不落库」
     *     契约的一部分，见 {@code ChatServiceImpl#persistStreamedMessages}），此时为
     *     {@code null}；人工接管静默 / 关键词触发转人工 / 知识库未命中兜底三个早退分支的用户
     *     消息在构造 START 前已经落库，均为真实 id。
     */
    public record Start(String sessionId, boolean suggestHandover,
                        List<ChatResponse.KnowledgeHit> knowledgeHits, Long userMessageId) {}

    public record Delta(String text) {}

    public record Card(Object node) {}

    public record Done(Long messageId) {}

    public record Error(String message) {}

    public static ChatStreamEvent start(String sessionId, boolean suggestHandover,
                                        List<ChatResponse.KnowledgeHit> knowledgeHits,
                                        Long userMessageId) {
        return new ChatStreamEvent(Type.START,
                new Start(sessionId, suggestHandover, knowledgeHits, userMessageId));
    }

    public static ChatStreamEvent delta(String text) {
        return new ChatStreamEvent(Type.DELTA, new Delta(text));
    }

    public static ChatStreamEvent card(Object node) {
        return new ChatStreamEvent(Type.CARD, new Card(node));
    }

    public static ChatStreamEvent done(Long messageId) {
        return new ChatStreamEvent(Type.DONE, new Done(messageId));
    }

    public static ChatStreamEvent error(String message) {
        return new ChatStreamEvent(Type.ERROR, new Error(message));
    }
}

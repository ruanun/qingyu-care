package com.qingyu.care.dto.open;

import com.qingyu.care.dto.ChatResponse;
import com.qingyu.care.dto.UiPart;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 开放对话响应（由内部 ChatResponse 映射，仅暴露对外必要字段）
 *
 * @author ruan
 */
@Data
@Builder
public class OpenChatResponse {

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * AI 消息 ID（提交赞/踩反馈时使用）
     */
    private Long messageId;

    /**
     * 访客<b>自己发出</b>的那条消息的 id。
     *
     * <p>⚠️ 与 {@link #messageId} 是两个不同的字段，极易混用：{@link #messageId} 是 <b>AI 回复</b>
     * 的 id（供反馈按钮使用）；本字段是访客那条消息自身的 id，widget 要拿它与已读水位线
     * （{@code agentReadUpToMessageId}）比对，才知道自己发的消息有没有被坐席读过。混用的后果是
     * 拿 AI 回复的 id 去跟访客侧水位线比对，已读标记会落在错误的位置。
     *
     * <p>⚠️ <b>不要给这个字段加初始化器</b>（如 {@code = 0L}）：本类当前只经 {@code @Builder}
     * 构造（无 {@code @NoArgsConstructor}），初始化器本就不生效；但若将来有人改走无参构造并顺手
     * 加了初始化器，widget 会把 0 当成一个真实 id 去比对，而水位线 {@code >= 0} 恒成立——
     * 访客发出第一条消息就会立刻被误判为「已读」。
     *
     * <p>检索命中的主问答（流式）路径此刻拿不到真实落库 id（用户消息与 AI 回复整轮一起延迟到
     * 流结束才落库，是「停止生成不落库」这条既有契约的一部分），此时为 {@code null}；非流式
     * 响应与流式其余分支（人工接管静默 / 关键词触发转人工 / 知识库未命中兜底）均为真实 id。
     */
    private Long userMessageId;

    /**
     * AI 回复内容
     */
    private String reply;

    /**
     * 是否建议转人工
     */
    private boolean handoverSuggested;

    /**
     * 结构化卡片有序片段（文本/卡片交错；非流式带卡片回复才有）
     */
    private List<UiPart> uiParts;

    /**
     * 从内部 ChatResponse 映射为对外响应
     */
    public static OpenChatResponse from(ChatResponse r) {
        return OpenChatResponse.builder()
                .sessionId(r.getSessionId())
                .messageId(r.getMessageId())
                .userMessageId(r.getUserMessageId())
                .reply(r.getContent())
                .handoverSuggested(r.isSuggestHandover())
                .uiParts(r.getUiParts())
                .build();
    }
}

package com.qingyu.care.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    
    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 落库后的 AI 消息 ID（供反馈接口定位；静默/转人工提示等未落 AI 消息的路径为 null）
     */
    private Long messageId;

    /**
     * 访客<b>自己发出</b>的那条消息的 id，区别于 {@link #messageId}（AI 回复的 id）。
     * 经 {@code OpenChatResponse.from} 透出给开放 API，供 widget 与已读水位线比对。
     * 未落库用户消息的路径（理论上不存在，chat() 的每个分支都会先落用户消息）为 null。
     */
    private Long userMessageId;

    /**
     * AI回复内容
     */
    private String content;
    
    /**
     * 置信度分数
     */
    private Double confidenceScore;
    
    /**
     * 命中的知识片段
     */
    private List<KnowledgeHit> knowledgeHits;
    
    /**
     * 是否建议转人工
     */
    private boolean suggestHandover;
    
    /**
     * Token 使用量
     */
    private Integer tokenUsage;

    /**
     * 结构化卡片有序片段（文本/卡片交错；非流式带卡片回复才有，其余为单个 text 片段或 null）
     */
    private List<UiPart> uiParts;

    /**
     * 知识命中信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgeHit {
        private String content;
        private Double score;
        private String source;
    }
}

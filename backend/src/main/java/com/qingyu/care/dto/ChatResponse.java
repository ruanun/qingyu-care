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

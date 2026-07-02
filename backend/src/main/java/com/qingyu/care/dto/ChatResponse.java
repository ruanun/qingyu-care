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
